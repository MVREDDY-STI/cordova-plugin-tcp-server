package cordova_plugin_tcp_server;

import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.PluginResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.os.Build;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import android.util.Base64;

/**
 * This class echoes a string called from JavaScript.
 */
public class TCPServer extends CordovaPlugin {

  private ServerSocket serverSocket;
  private ArrayList<Socket> clientSockets = new ArrayList<>();
  String response;
  int c;

  @Override
  public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
    if (action.equals("coolMethod")) {
      String message = args.getString(0);
      this.coolMethod(message, callbackContext);
      return true;
    } else if (action.equals("startServer")) {
      String message = args.getString(0);
      startServer(message, callbackContext);
      return true;
    } else if (action.equals("stopServer")) {
      stopServer(callbackContext);
      return true;
    }
    return false;
  }

  private void coolMethod(String message, CallbackContext callbackContext) {
    if (message != null && message.length() > 0) {
      callbackContext.success(message);
    } else {
      callbackContext.error("Expected one non-empty string argument.");
    }
  }

  private void startServer(String message, CallbackContext callbackContext) {
    cordova.getThreadPool().execute(() -> {
      try {
        int port = Integer.parseInt(message);
        if (port != (int) port) {
          port = 8082;
        }
        serverSocket = new ServerSocket(port);
        Log.d("TcpServerPlugin", "Server is running on port : " + Integer.toString(port));
        //callbackContext.success("Server is running on port 8082");
        PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT, "Server is running on port : " + Integer.toString(port));
        result.setKeepCallback(true);
        callbackContext.sendPluginResult(result);
        while (true) {
          Socket clientSocket = serverSocket.accept();
          Log.d("TcpServerPlugin", "New client connected: " + clientSocket.getInetAddress().getHostAddress());
          result = new PluginResult(PluginResult.Status.OK, "New client connected: " + clientSocket.getInetAddress().getHostAddress());
          result.setKeepCallback(true);
          callbackContext.sendPluginResult(result);
          clientSockets.add(clientSocket);
          handleClientSocket(clientSocket, callbackContext);
        }
      } catch (IOException e) {
        e.printStackTrace();
        callbackContext.error("Error starting server: " + e.getMessage());
      }
    });
  }

  private void stopServer(CallbackContext callbackContext) {
    cordova.getThreadPool().execute(() -> {
      if (serverSocket != null && !serverSocket.isClosed()) {
        try {
          for (Socket clientSocket : clientSockets) {
            clientSocket.close();
          }
          clientSockets.clear();
          serverSocket.close();
          Log.d("TcpServerPlugin", "Server stopped.");
          callbackContext.success("Server stopped.");
        } catch (IOException e) {
          e.printStackTrace();
          callbackContext.error("Error stopping server: " + e.getMessage());
        }
      } else {
        Log.d("TcpServerPlugin", "Server is not running.");
        callbackContext.error("Server is not running.");
      }
    });
  }

  private void handleClientSocket(Socket clientSocket, CallbackContext callbackContext) {
    cordova.getThreadPool().execute(() -> {
      try {
        // Read image data
        InputStream inputStream = clientSocket.getInputStream();
        byte[] imageData = readInputStreamFully(inputStream);
        String base64String = Base64Utils.byteArrayToBase64(imageData);
        PluginResult result = new PluginResult(PluginResult.Status.OK, base64String);
        result.setKeepCallback(true);
        callbackContext.sendPluginResult(result);
        clientSocket.close();
        Log.d("TcpServerPlugin", "Connection with client " + clientSocket.getInetAddress().getHostAddress() + " closed.");
      } catch (IOException e) {
        e.printStackTrace();
      }
    });
  }

  public class Base64Utils {
    public static String byteArrayToBase64(byte[] byteArray) {
      return Base64.encodeToString(byteArray, Base64.DEFAULT);
    }

    public static byte[] base64ToByteArray(String base64String) {
      return Base64.decode(base64String, Base64.DEFAULT);
    }
  }

  public static byte[] readInputStreamFully(InputStream inputStream) throws IOException {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096]; // Adjust buffer size as needed
    int bytesRead;

    while ((bytesRead = inputStream.read(buffer)) != -1) {
      outputStream.write(buffer, 0, bytesRead);
    }

    return outputStream.toByteArray();
  }

}
