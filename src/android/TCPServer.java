
package cordova_plugin_tcp_server;

import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.util.Base64;
import android.util.Log;

public class TCPServer extends CordovaPlugin {

    private static final String TAG = "TCPServer";
    private static final int DEFAULT_PORT            = 8443;
    private static final int BUFFER_SIZE             = 8192;
    private static final int SERVER_SO_TIMEOUT_MS    = 1000;   // accept() poll interval
    private static final int CLIENT_SO_TIMEOUT_MS    = 30000;  // 30 s per-client read timeout

    // Watchdog / auto-recovery
    private static final int MAX_RESTART_ATTEMPTS    = 10;     // per watchdog cycle
    private static final int RESTART_BASE_DELAY_MS   = 1000;   // initial back-off delay
    private static final int RESTART_MAX_DELAY_MS    = 30000;  // cap at 30 s
    private static final int BIND_RETRY_COUNT        = 5;      // port-bind retries
    // FIX: was 500 ms — increased to give OS more time to fully reclaim the port
    private static final int BIND_RETRY_DELAY_MS     = 800;

    // ── State ──
    private volatile ServerSocket           serverSocket;
    private final ConcurrentHashMap<String, Socket> clientSockets = new ConcurrentHashMap<>();
    private volatile ExecutorService        threadPool;
    private final ScheduledExecutorService  watchdogScheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile ScheduledFuture<?>     watchdogFuture;

    private final AtomicBoolean  isRunning         = new AtomicBoolean(false);
    private final AtomicBoolean  serverIntentional = new AtomicBoolean(false);
    private final AtomicInteger  restartAttempts   = new AtomicInteger(0);
    private volatile int         currentPort       = DEFAULT_PORT;
    private volatile boolean     debugEnabled      = false;

    // Callback kept alive for the lifetime of the server session
    private volatile CallbackContext serverCallbackContext;

    // Network-change listeners
    private BroadcastReceiver                networkReceiver;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean                          networkReceiverRegistered = false;

    // ────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ────────────────────────────────────────────────────────────────────────

    @Override
    public void pluginInitialize() {
        ensureThreadPool();
        registerNetworkReceiver();
        logDebug("Plugin initialized");
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Plugin destroying...");
        serverIntentional.set(false);
        unregisterNetworkReceiver();
        cancelWatchdog();
        forceCleanup();
        shutdownThreadPool();
        Log.i(TAG, "Plugin destroyed");
    }

    @Override
    public void onReset() {
        Log.i(TAG, "Plugin reset (WebView navigation)");
        logDebug("onReset() - server kept alive for re-attach");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Cordova dispatch
    // ────────────────────────────────────────────────────────────────────────

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext)
            throws JSONException {
        logDebug("execute() action=" + action + " args=" + args);

        switch (action) {
            case "startServer": {
                int port = args.optInt(0, DEFAULT_PORT);
                startServer(port, callbackContext);
                return true;
            }
            case "stopServer": {
                stopServer(callbackContext);
                return true;
            }
            case "restartServer": {
                int port = args.optInt(0, DEFAULT_PORT);
                restartServer(port, callbackContext);
                return true;
            }
            case "setDebugLogging": {
                boolean enabled = args.optBoolean(0, false);
                setDebugLogging(enabled, callbackContext);
                return true;
            }
            case "getStatus": {
                getStatus(callbackContext);
                return true;
            }
            default:
                Log.w(TAG, "Invalid action: " + action);
                callbackContext.error("Invalid action: " + action);
                return false;
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Public actions
    // ────────────────────────────────────────────────────────────────────────

    private void setDebugLogging(boolean enabled, CallbackContext cb) {
        debugEnabled = enabled;
        Log.i(TAG, "Debug logging " + (enabled ? "ENABLED" : "DISABLED"));
        cb.success("Debug logging " + (enabled ? "enabled" : "disabled"));
    }

    private void getStatus(CallbackContext cb) {
        String status = isRunning.get() ? "RUNNING|" + currentPort : "STOPPED";
        cb.success(status);
    }

    private synchronized void startServer(int port, CallbackContext callbackContext) {
        logDebug("startServer() port=" + port + " isRunning=" + isRunning.get()
                + " currentPort=" + currentPort);

        if (isRunning.get() && port == currentPort) {
            // FIX: Same port, already healthy — just re-register callback so the new
            // page receives events after WebView reload or repeated startServer() calls.
            Log.i(TAG, "startServer() - already running on port " + currentPort + "; re-registering callback");
            serverCallbackContext = callbackContext;
            sendStatus("STARTED|" + currentPort);
            return;
        }

        if (isRunning.get() && port != currentPort) {
            // FIX: Different port requested while already running — stop + restart cleanly.
            Log.i(TAG, "startServer() - port change " + currentPort + " -> " + port + "; restarting");
            restartServer(port, callbackContext);
            return;
        }

        serverCallbackContext = callbackContext;
        currentPort           = port;
        serverIntentional.set(true);
        restartAttempts.set(0);
        ensureThreadPool();
        threadPool.execute(() -> doStartWithRetry(port));
    }

    private synchronized void stopServer(CallbackContext callbackContext) {
        logDebug("stopServer() isRunning=" + isRunning.get());

        serverIntentional.set(false);
        cancelWatchdog();

        if (!isRunning.getAndSet(false)) {
            // Not running — still run cleanup to release any stale/partially-bound socket
            forceCleanup();
            callbackContext.error("Server not running");
            return;
        }

        ensureThreadPool();
        threadPool.execute(() -> {
            forceCleanup();
            callbackContext.success("Server stopped");
            Log.i(TAG, "Server stopped by user");
        });
    }

    private synchronized void restartServer(int port, CallbackContext callbackContext) {
        logDebug("restartServer() port=" + port);
        serverCallbackContext = callbackContext;
        currentPort           = port;
        serverIntentional.set(true);
        restartAttempts.set(0);
        cancelWatchdog();

        ensureThreadPool();
        threadPool.execute(() -> {
            forceCleanup();
            // FIX: Increased grace period from 500 ms to 1000 ms so the OS
            // has enough time to fully reclaim the port before the next bind.
            sleepMs(1000);
            doStartWithRetry(port);
        });
    }

    // ────────────────────────────────────────────────────────────────────────
    // Core start with port-bind retry
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Attempts to bind and start the server, retrying on EADDRINUSE up to
     * BIND_RETRY_COUNT times with linearly increasing back-off.
     *
     * FIX (primary): Each attempt now calls safeClose(serverSocket) before
     * creating a new ServerSocket. This guarantees the stale socket from the
     * previous attempt (or from the previous accept loop) is fully closed
     * before we try to bind again. SO_REUSEADDR alone is not sufficient when
     * the same process still owns the socket.
     */
    private void doStartWithRetry(int port) {
        for (int attempt = 1; attempt <= BIND_RETRY_COUNT; attempt++) {
            try {
                logDebug("doStartWithRetry() attempt=" + attempt + " port=" + port);

                // FIX: Close any lingering socket before each bind attempt.
                safeClose(serverSocket);
                serverSocket = null;

                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.setSoTimeout(SERVER_SO_TIMEOUT_MS);
                serverSocket.bind(new InetSocketAddress(port));
                isRunning.set(true);
                restartAttempts.set(0);

                String startMsg = (attempt == 1) ? "STARTED|" + port : "RESTARTED|" + port;
                sendStatus(startMsg);
                Log.i(TAG, "Server started on port " + port + " (attempt " + attempt + ")");

                acceptLoop(); // blocks until loop exits

                if (serverIntentional.get()) {
                    String reason = "Accept loop exited unexpectedly";
                    Log.w(TAG, reason);
                    sendStatus("CONNECTION_FAILED|SERVER|" + reason);
                    scheduleWatchdog();
                }
                return;

            } catch (IOException e) {
                // FIX: Always close the partially-created socket so it doesn't
                // accumulate and keep the port reserved across retry iterations.
                safeClose(serverSocket);
                serverSocket = null;
                isRunning.set(false);

                String msg = friendlyError(e);
                Log.e(TAG, "Bind attempt " + attempt + " failed: " + msg);

                if (attempt < BIND_RETRY_COUNT) {
                    sendStatus("CONNECTION_FAILED|SERVER|Bind attempt " + attempt + " failed: "
                            + msg + " - retrying...");
                    // Linearly increasing back-off: 800 ms, 1600 ms, 2400 ms, 3200 ms
                    sleepMs((long) BIND_RETRY_DELAY_MS * attempt);
                } else {
                    sendStatus("CONNECTION_FAILED|SERVER|Unable to bind on port " + port
                            + " after " + BIND_RETRY_COUNT + " attempts: " + msg);
                    if (serverIntentional.get()) {
                        scheduleWatchdog();
                    }
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Accept loop
    // ────────────────────────────────────────────────────────────────────────

    private void acceptLoop() {
        logDebug("acceptLoop() entered");

        while (isRunning.get()) {
            try {
                Socket clientSocket = serverSocket.accept();
                String clientKey    = getClientKey(clientSocket);
                Log.i(TAG, "Client connected: " + clientKey);

                try {
                    clientSocket.setSoTimeout(CLIENT_SO_TIMEOUT_MS);
                    clientSocket.setKeepAlive(true);
                    clientSocket.setTcpNoDelay(true);
                } catch (SocketException se) {
                    Log.e(TAG, "Socket config failed [" + clientKey + "]: " + se.getMessage());
                    sendStatus("CONNECTION_FAILED|" + clientKey
                            + "|Socket configuration error - " + se.getMessage());
                    safeClose(clientSocket);
                    continue;
                }

                Socket existing = clientSockets.put(clientKey, clientSocket);
                if (existing != null) {
                    logDebug("Replacing stale connection: " + clientKey);
                    safeClose(existing);
                }

                sendStatus("CONNECTED|" + clientKey);
                ensureThreadPool();
                threadPool.execute(() -> handleClient(clientSocket, clientKey));

            } catch (SocketTimeoutException ignored) {
                // Normal poll tick — continue

            } catch (SocketException e) {
                if (isRunning.get()) {
                    Log.e(TAG, "Accept error: " + e.getMessage());
                    sendStatus("CONNECTION_FAILED|SERVER|Accept socket error: " + friendlyError(e));
                }
                break;

            } catch (IOException e) {
                if (isRunning.get()) {
                    Log.e(TAG, "Accept IO error: " + e.getMessage());
                    sendStatus("CONNECTION_FAILED|SERVER|Accept IO error: " + friendlyError(e));
                }
                break;
            }
        }

        isRunning.set(false);
        logDebug("acceptLoop() exited");
        Log.i(TAG, "Accept loop ended");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Client handling
    // ────────────────────────────────────────────────────────────────────────

    private void handleClient(Socket clientSocket, String clientKey) {
        logDebug("handleClient() started for: " + clientKey);

        try (InputStream input = clientSocket.getInputStream()) {
            byte[] data = readStream(input, clientKey);

            if (data.length > 0) {
                String b64 = Base64.encodeToString(data, Base64.NO_WRAP);
                sendStatus("DATA|" + clientKey + "|" + b64);
                Log.i(TAG, "Received " + data.length + " bytes from: " + clientKey);
            } else {
                Log.w(TAG, "No data received from: " + clientKey);
            }

        } catch (SocketTimeoutException e) {
            Log.w(TAG, "Read timeout [" + clientKey + "]");
            sendStatus("CONNECTION_FAILED|" + clientKey
                    + "|Read timeout - client did not send data within 30 s");

        } catch (SocketException e) {
            Log.e(TAG, "Client socket error [" + clientKey + "]: " + e.getMessage());
            sendStatus("CONNECTION_FAILED|" + clientKey + "|" + friendlyError(e));

        } catch (IOException e) {
            Log.e(TAG, "Client IO error [" + clientKey + "]: " + e.getMessage());
            sendStatus("CONNECTION_FAILED|" + clientKey + "|" + friendlyError(e));

        } finally {
            sendStatus("DISCONNECTED|" + clientKey);
            safeClose(clientSocket);
            clientSockets.remove(clientKey);
            logDebug("handleClient() finished for: " + clientKey
                    + " remaining=" + clientSockets.size());
        }
    }

    private byte[] readStream(InputStream input, String clientKey) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
            logDebug("readStream() " + clientKey + " chunk=" + bytesRead);
        }
        return output.toByteArray();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Watchdog – auto-recovery with exponential back-off
    // ────────────────────────────────────────────────────────────────────────

    private synchronized void scheduleWatchdog() {
        if (!serverIntentional.get()) return;

        int attempts = restartAttempts.incrementAndGet();
        if (attempts > MAX_RESTART_ATTEMPTS) {
            Log.e(TAG, "Max restart attempts reached - giving up auto-recovery");
            sendStatus("CONNECTION_FAILED|SERVER|Server could not recover after "
                    + MAX_RESTART_ATTEMPTS + " attempts. Please restart the app.");
            serverIntentional.set(false);
            return;
        }

        int shift = Math.min(attempts - 1, 5);
        long delayMs = Math.min(RESTART_BASE_DELAY_MS * (1L << shift), RESTART_MAX_DELAY_MS);
        Log.i(TAG, "Watchdog: scheduling restart attempt " + attempts + " in " + delayMs + " ms");
        sendStatus("CONNECTION_FAILED|SERVER|Auto-recovering in " + (delayMs / 1000)
                + " s (attempt " + attempts + ")");

        watchdogFuture = watchdogScheduler.schedule(() -> {
            if (!serverIntentional.get() || isRunning.get()) return;
            Log.i(TAG, "Watchdog: attempting server restart #" + attempts);

            // FIX (primary): Always call forceCleanup() here before retrying.
            // The stale ServerSocket from the previous accept loop may still be
            // holding the port even though isRunning==false. Without this call,
            // every watchdog retry sees EADDRINUSE because the old socket was
            // never closed — causing the infinite retry loop you observed.
            forceCleanup();
            sleepMs(500); // brief OS grace period before rebinding

            ensureThreadPool();
            threadPool.execute(() -> doStartWithRetry(currentPort));
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelWatchdog() {
        if (watchdogFuture != null && !watchdogFuture.isDone()) {
            watchdogFuture.cancel(false);
            logDebug("Watchdog cancelled");
        }
        watchdogFuture = null;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Network-change listener
    // ────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private void registerNetworkReceiver() {
        if (networkReceiverRegistered) return;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            registerNetworkCallback();
        } else {
            registerLegacyReceiver();
        }
    }

    @android.annotation.TargetApi(android.os.Build.VERSION_CODES.N)
    private void registerNetworkCallback() {
        ConnectivityManager cm = (ConnectivityManager)
                cordova.getActivity().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        android.net.NetworkRequest request = new android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(android.net.Network network) {
                Log.i(TAG, "NetworkCallback: network available");
                if (serverIntentional.get() && !isRunning.get()) {
                    Log.i(TAG, "Network restored - triggering server auto-recovery");
                    sendStatus("CONNECTION_FAILED|SERVER|Network restored - reconnecting...");
                    restartAttempts.set(0);
                    cancelWatchdog();
                    // FIX: cleanup before network-triggered restart too
                    forceCleanup();
                    sleepMs(500);
                    ensureThreadPool();
                    threadPool.execute(() -> doStartWithRetry(currentPort));
                }
            }

            @Override
            public void onLost(android.net.Network network) {
                Log.w(TAG, "NetworkCallback: network lost");
                if (isRunning.get()) {
                    sendStatus("CONNECTION_FAILED|SERVER|Network connectivity lost");
                }
            }
        };

        cm.registerNetworkCallback(request, networkCallback);
        networkReceiverRegistered = true;
        logDebug("NetworkCallback registered (API 24+)");
    }

    @SuppressWarnings("deprecation")
    private void registerLegacyReceiver() {
        networkReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                ConnectivityManager cm = (ConnectivityManager)
                        context.getSystemService(Context.CONNECTIVITY_SERVICE);
                @SuppressWarnings("deprecation")
                NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
                boolean connected = (ni != null && ni.isConnected());

                Log.i(TAG, "Network change (broadcast) - connected=" + connected);

                if (connected && serverIntentional.get() && !isRunning.get()) {
                    Log.i(TAG, "Network restored - triggering server auto-recovery");
                    sendStatus("CONNECTION_FAILED|SERVER|Network restored - reconnecting...");
                    restartAttempts.set(0);
                    cancelWatchdog();
                    // FIX: cleanup before network-triggered restart too
                    forceCleanup();
                    sleepMs(500);
                    ensureThreadPool();
                    threadPool.execute(() -> doStartWithRetry(currentPort));
                }

                if (!connected && isRunning.get()) {
                    Log.w(TAG, "Network lost - server may drop clients");
                    sendStatus("CONNECTION_FAILED|SERVER|Network connectivity lost");
                }
            }
        };

        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        cordova.getActivity().registerReceiver(networkReceiver, filter);
        networkReceiverRegistered = true;
        logDebug("Legacy network receiver registered (pre-API 24)");
    }

    private void unregisterNetworkReceiver() {
        if (!networkReceiverRegistered) return;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N
                && networkCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager)
                        cordova.getActivity().getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) { }
            networkCallback = null;
        } else if (networkReceiver != null) {
            try {
                cordova.getActivity().unregisterReceiver(networkReceiver);
            } catch (Exception ignored) { }
            networkReceiver = null;
        }

        networkReceiverRegistered = false;
        logDebug("Network listener unregistered");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Cleanup helpers
    // ────────────────────────────────────────────────────────────────────────

    private void forceCleanup() {
        logDebug("forceCleanup() clients=" + clientSockets.size());
        isRunning.set(false);

        for (Map.Entry<String, Socket> entry : clientSockets.entrySet()) {
            safeClose(entry.getValue());
        }
        clientSockets.clear();

        safeClose(serverSocket);
        serverSocket = null;
        logDebug("forceCleanup() done");
    }

    private void safeClose(Socket socket) {
        if (socket == null) return;
        try { if (!socket.isClosed()) socket.shutdownInput();  } catch (IOException ignored) { }
        try { if (!socket.isClosed()) socket.shutdownOutput(); } catch (IOException ignored) { }
        try { socket.close(); } catch (IOException ignored) { }
    }

    private void safeClose(ServerSocket socket) {
        if (socket == null) return;
        try { if (!socket.isClosed()) socket.close(); } catch (IOException ignored) { }
    }

    private synchronized void ensureThreadPool() {
        if (threadPool == null || threadPool.isShutdown()) {
            threadPool = Executors.newCachedThreadPool();
            logDebug("Thread pool (re)created");
        }
    }

    private void shutdownThreadPool() {
        if (threadPool == null) return;
        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                threadPool.shutdownNow();
                threadPool.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Utility
    // ────────────────────────────────────────────────────────────────────────

    private String getClientKey(Socket socket) {
        return socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
    }

    private String friendlyError(Exception e) {
        String raw = e.getMessage();
        if (raw == null) raw = e.getClass().getSimpleName();
        if (raw.contains("EADDRINUSE") || raw.contains("bind failed"))
            return "Port already in use - another process may be holding port " + currentPort;
        if (raw.contains("EACCES") || raw.contains("Permission denied"))
            return "Permission denied - cannot bind to port " + currentPort;
        if (raw.contains("ENETUNREACH") || raw.contains("Network is unreachable"))
            return "Network unreachable - check Wi-Fi/LAN connection";
        if (raw.contains("ECONNRESET") || raw.contains("Connection reset"))
            return "Connection reset by client";
        if (raw.contains("EPIPE") || raw.contains("Broken pipe"))
            return "Connection broken - client disconnected unexpectedly";
        if (raw.contains("ETIMEDOUT") || raw.contains("timed out"))
            return "Connection timed out";
        if (raw.contains("ECONNREFUSED"))
            return "Connection refused by remote host";
        return raw;
    }

    private void sendStatus(String message) {
        if (serverCallbackContext == null) {
            Log.w(TAG, "sendStatus() - no callback registered, message=" + message);
            return;
        }
        logDebug("sendStatus() -> " + message);
        PluginResult result = new PluginResult(PluginResult.Status.OK, message);
        result.setKeepCallback(true);
        serverCallbackContext.sendPluginResult(result);
    }

    private void logDebug(String message) {
        if (debugEnabled) {
            Log.d(TAG, "[DEBUG][" + Thread.currentThread().getName() + "] " + message);
        }
    }

    private void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
