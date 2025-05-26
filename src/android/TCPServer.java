package cordova_plugin_tcp_server;

import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class TCPServer extends CordovaPlugin {
  private ServerSocket serverSocket;
  private final ConcurrentHashMap<String, Socket> clientSockets = new ConcurrentHashMap<>();
  private final ExecutorService threadPool = Executors.newCachedThreadPool();
  private final AtomicBoolean isRunning = new AtomicBoolean(false);
  private static final int DEFAULT_PORT = 54321;
  private static final int SO_TIMEOUT = 30000;
  private static final int BUFFER_SIZE = 8192;

  @Override
  public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
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
        callbackContext.error("Invalid action");
        return false;
    }
  }

  private void restartServer(int port, CallbackContext callbackContext) {
    threadPool.execute(() -> {
      if (isRunning.get()) {
        stopServer(new CallbackContext("internal", null) {
          @Override
          public void sendPluginResult(PluginResult result) {
            if (result.getStatus() == PluginResult.Status.OK.ordinal()) {
              startServer(port, callbackContext);
            } else {
              callbackContext.error("Restart failed during stop phase");
            }
          }
        });
      } else {
        startServer(port, callbackContext);
      }
    });
  }

  private void startServer(int port, CallbackContext callbackContext) {
    threadPool.execute(() -> {
      try {
        // Cleanup any existing server state
        if (isRunning.get()) {
          forceCleanup();
        }

        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        isRunning.set(true);

        sendStatus(callbackContext, "Server running on port: " + port);

        while (isRunning.get()) {
          try {
            Socket clientSocket = serverSocket.accept();
            clientSocket.setSoTimeout(SO_TIMEOUT);
            String clientKey = clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort();

            // Gracefully handle existing connections
            Socket existingSocket = clientSockets.put(clientKey, clientSocket);
            if (existingSocket != null) {
              safeClose(existingSocket);
            }

            handleClient(clientSocket, callbackContext);
          } catch (SocketException e) {
            if (!isRunning.get()) break;
            Log.e("TCPServer", "Accept error: " + e.getMessage());
          }
        }
      } catch (IOException e) {
        callbackContext.error("Start error: " + e.getMessage());
        isRunning.set(false);
      }
    });
  }

  private void stopServer(CallbackContext callbackContext) {
    threadPool.execute(() -> {
      if (!isRunning.getAndSet(false)) {
        callbackContext.error("Server not running");
        return;
      }

      clientSockets.forEach((key, socket) -> safeClose(socket));
      clientSockets.clear();

      try {
        if (serverSocket != null && !serverSocket.isClosed()) {
          serverSocket.close();
        }
        callbackContext.success("Server stopped");
      } catch (IOException e) {
        callbackContext.error("Stop error: " + e.getMessage());
      }
    });
  }

  private void forceCleanup() {
    isRunning.set(false);
    clientSockets.forEach((key, socket) -> safeClose(socket));
    clientSockets.clear();
    safeClose(serverSocket);
  }

  private void handleClient(Socket clientSocket, CallbackContext callbackContext) {
    threadPool.execute(() -> {
      String clientKey = clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort();
      try (InputStream input = clientSocket.getInputStream()) {
        byte[] data = readStream(input);
        String base64Data = Base64.encodeToString(data, Base64.NO_WRAP);
        sendStatus(callbackContext, clientKey + "|" + base64Data);
      } catch (IOException e) {
        Log.e("TCPServer", "Client error: " + e.getMessage());
      } finally {
        safeClose(clientSocket);
        clientSockets.remove(clientKey);
      }
    });
  }

  private byte[] readStream(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[BUFFER_SIZE];
    int bytesRead;
    while ((bytesRead = input.read(buffer)) != -1) {
      output.write(buffer, 0, bytesRead);
    }
    return output.toByteArray();
  }

  private void safeClose(Socket socket) {
    try {
      if (socket != null && !socket.isClosed()) {
        socket.shutdownInput();
        socket.shutdownOutput();
        socket.close();
      }
    } catch (IOException e) {
      Log.e("TCPServer", "Close error: " + e.getMessage());
    }
  }

  private void safeClose(ServerSocket socket) {
    try {
      if (socket != null && !socket.isClosed()) {
        socket.close();
      }
    } catch (IOException e) {
      Log.e("TCPServer", "Server close error: " + e.getMessage());
    }
  }

  private void sendStatus(CallbackContext ctx, String message) {
    PluginResult result = new PluginResult(PluginResult.Status.OK, message);
    result.setKeepCallback(true);
    ctx.sendPluginResult(result);
  }

  @Override
  public void onDestroy() {
    forceCleanup();
    threadPool.shutdownNow();
  }
}
