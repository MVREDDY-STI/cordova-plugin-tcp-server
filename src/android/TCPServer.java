

package cordova_plugin_tcp_server;

import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import android.util.Base64;
import android.util.Log;

public class TCPServer extends CordovaPlugin {
  private static final String TAG = "TCPServer";
  private static final int DEFAULT_PORT = 8443;
  private static final int BUFFER_SIZE = 8192;
  private static final int SERVER_SO_TIMEOUT = 1000; // For accept() loop checks

  private volatile ServerSocket serverSocket;
  private final ConcurrentHashMap<String, Socket> clientSockets = new ConcurrentHashMap<>();
  private final ExecutorService threadPool = Executors.newCachedThreadPool();
  private final AtomicBoolean isRunning = new AtomicBoolean(false);

  @Override
  public boolean execute(String action, JSONArray args, CallbackContext callbackContext)
          throws JSONException {
    switch (action) {
      case "startServer":
        int port = args.optInt(0, DEFAULT_PORT);
        startServer(port, callbackContext);
        return true;

      case "stopServer":
        stopServer(callbackContext);
        return true;

      case "restartServer":
        int newPort = args.optInt(0, DEFAULT_PORT);
        restartServer(newPort, callbackContext);
        return true;

      default:
        callbackContext.error("Invalid action: " + action);
        return false;
    }
  }

  private synchronized void startServer(int port, CallbackContext callbackContext) {
    if (isRunning.get()) {
      callbackContext.error("Server already running");
      return;
    }

    threadPool.execute(() -> {
      try {
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        serverSocket.setSoTimeout(SERVER_SO_TIMEOUT); // Allow periodic checks
        isRunning.set(true);

        sendStatus(callbackContext, "STARTED|" + port);
        Log.i(TAG, "Server started on port: " + port);

        acceptLoop(callbackContext);

      } catch (IOException e) {
        Log.e(TAG, "Failed to start server", e);
        callbackContext.error("Start error: " + e.getMessage());
        isRunning.set(false);
      } finally {
        isRunning.set(false);
        Log.i(TAG, "Server accept loop ended");
      }
    });
  }

  private void acceptLoop(CallbackContext callbackContext) {
    while (isRunning.get()) {
      try {
        Socket clientSocket = serverSocket.accept();

        String clientKey = getClientKey(clientSocket);
        Log.i(TAG, "Client connected: " + clientKey);

        // Store the socket
        Socket existing = clientSockets.put(clientKey, clientSocket);
        if (existing != null) {
          Log.w(TAG, "Replacing existing connection: " + clientKey);
          safeClose(existing);
        }

        sendStatus(callbackContext, "CONNECTED|" + clientKey);

        // Handle client in separate thread
        handleClient(clientSocket, callbackContext);

      } catch (SocketTimeoutException e) {
        // Normal timeout - check if still running and continue
        continue;
      } catch (SocketException e) {
        if (isRunning.get()) {
          Log.e(TAG, "Socket error during accept", e);
        }
        break;
      } catch (IOException e) {
        if (isRunning.get()) {
          Log.e(TAG, "Accept error", e);
        }
      }
    }
  }

  private void handleClient(Socket clientSocket, CallbackContext callbackContext) {
    threadPool.execute(() -> {
      String clientKey = getClientKey(clientSocket);

      try (InputStream input = clientSocket.getInputStream()) {

        // Read all data until client closes connection
        byte[] data = readStream(input);

        if (data.length > 0) {
          // Convert complete data to base64
          String base64Data = Base64.encodeToString(data, Base64.NO_WRAP);

          // Send complete base64 data at once
          sendStatus(callbackContext, "DATA|" + clientKey + "|" + base64Data);

          Log.i(TAG, "Received " + data.length + " bytes from: " + clientKey);
        } else {
          Log.w(TAG, "No data received from: " + clientKey);
        }

      } catch (IOException e) {
        Log.e(TAG, "Client error [" + clientKey + "]: " + e.getMessage());
      } finally {
        // Always notify disconnection and cleanup
        sendStatus(callbackContext, "DISCONNECTED|" + clientKey);
        safeClose(clientSocket);
        clientSockets.remove(clientKey);
        Log.i(TAG, "Client disconnected and cleaned up: " + clientKey);
      }
    });
  }

  /**
   * Reads all data from input stream until client closes connection.
   * This blocks until the client sends EOF (closes the connection).
   */
  private byte[] readStream(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[BUFFER_SIZE];
    int bytesRead;

    // Read until client closes connection (returns -1)
    while ((bytesRead = input.read(buffer)) != -1) {
      output.write(buffer, 0, bytesRead);
    }

    return output.toByteArray();
  }

  private synchronized void stopServer(CallbackContext callbackContext) {
    if (!isRunning.getAndSet(false)) {
      callbackContext.error("Server not running");
      return;
    }

    threadPool.execute(() -> {
      Log.i(TAG, "Stopping server...");

      // Close all client connections
      clientSockets.forEach((key, socket) -> {
        Log.i(TAG, "Closing client: " + key);
        safeClose(socket);
      });
      clientSockets.clear();

      // Close server socket
      safeClose(serverSocket);
      serverSocket = null;

      callbackContext.success("Server stopped");
      Log.i(TAG, "Server stopped successfully");
    });
  }

  private synchronized void restartServer(int port, CallbackContext callbackContext) {
    threadPool.execute(() -> {
      try {
        if (isRunning.get()) {
          Log.i(TAG, "Stopping server for restart...");
          forceCleanup();
          Thread.sleep(500); // Brief pause for cleanup
        }

        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        serverSocket.setSoTimeout(SERVER_SO_TIMEOUT);
        isRunning.set(true);

        sendStatus(callbackContext, "RESTARTED|" + port);
        Log.i(TAG, "Server restarted on port: " + port);

        acceptLoop(callbackContext);

      } catch (Exception e) {
        Log.e(TAG, "Restart failed", e);
        callbackContext.error("Restart error: " + e.getMessage());
        isRunning.set(false);
      }
    });
  }

  private void forceCleanup() {
    isRunning.set(false);

    // Close all client sockets
    clientSockets.forEach((key, socket) -> safeClose(socket));
    clientSockets.clear();

    // Close server socket
    safeClose(serverSocket);
  }

  private void safeClose(Socket socket) {
    if (socket == null) return;

    try {
      if (!socket.isClosed()) {
        socket.shutdownInput();
      }
    } catch (IOException e) {
      // Ignore - socket may already be closed
    }

    try {
      if (!socket.isClosed()) {
        socket.shutdownOutput();
      }
    } catch (IOException e) {
      // Ignore - socket may already be closed
    }

    try {
      socket.close();
    } catch (IOException e) {
      Log.d(TAG, "Error closing socket: " + e.getMessage());
    }
  }

  private void safeClose(ServerSocket socket) {
    if (socket == null) return;

    try {
      if (!socket.isClosed()) {
        socket.close();
      }
    } catch (IOException e) {
      Log.d(TAG, "Error closing server socket: " + e.getMessage());
    }
  }

  private String getClientKey(Socket socket) {
    return socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
  }

  private void sendStatus(CallbackContext ctx, String message) {
    PluginResult result = new PluginResult(PluginResult.Status.OK, message);
    result.setKeepCallback(true);
    ctx.sendPluginResult(result);
  }

  @Override
  public void onDestroy() {
    Log.i(TAG, "Plugin destroying...");
    forceCleanup();

    threadPool.shutdown();
    try {
      if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
        Log.w(TAG, "Thread pool did not terminate gracefully");
        threadPool.shutdownNow();

        if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
          Log.e(TAG, "Thread pool did not terminate");
        }
      }
    } catch (InterruptedException e) {
      threadPool.shutdownNow();
      Thread.currentThread().interrupt();
    }

    Log.i(TAG, "Plugin destroyed");
  }
}
