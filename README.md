# cordova-plugin-tcp-server

> **v2.0.0** – Resilient TCP/IP server plugin for Android & iOS with automatic recovery, network-switch handling, and rich error reporting.

This Cordova plugin lets your app act as a **TCP/IP server** that receives binary or text messages from any TCP client. Version 2 focuses entirely on reliability: the server now survives OS restarts, network changes, port conflicts, and app reloads without any manual intervention.

---

## Platform Support

| Platform | Min Version |
|----------|-------------|
| Android  | API 21 (Android 5.0) |
| iOS      | iOS 12 |

---

## Installation

**Cordova**
```bash
ionic cordova plugin add https://github.com/MVREDDY-STI/cordova-plugin-tcp-server.git
```

**Capacitor**
```bash
npm install https://github.com/MVREDDY-STI/cordova-plugin-tcp-server.git
npx cap sync
```

---

## What's New in v2.0.0

| Feature | Description |
|---------|-------------|
| **Auto-recovery watchdog** | If the server stops unexpectedly (OS kill, crash, restart) it automatically restarts with exponential back-off (1 s → 2 s → 4 s … up to 30 s, max 10 attempts). |
| **Port-bind retry** | If the port is temporarily busy (`EADDRINUSE`), the plugin retries up to 5 times before giving up. |
| **Network-change listener** | On Android (`ConnectivityManager`) and iOS (`SCNetworkReachability`), the server detects Wi-Fi/LAN drops and reconnects automatically when the network is restored. |
| **Callback re-registration** | After a WebView reload (page navigation), the server keeps running and re-attaches to the new JS callback automatically. |
| **Structured event objects** | The JS layer now parses all native events into typed objects with a `.message` field ready for display in the app UI. |
| **New `getStatus` API** | Query the running state at any time without starting or stopping the server. |
| **New `decodeData` helper** | Convenience function to base64-decode a DATA event payload into a UTF-8 string. |
| **Friendly error messages** | Every `CONNECTION_FAILED` event includes a `.message` string written in plain English, ready to show the user. |

---

## API Reference

### Start the Server

```typescript
declare var window: any;

window.cordova.plugins.TCPServer.startServer(
  "8443",
  (event: any, raw: string) => {
    // event.event → 'STARTED' | 'RESTARTED' | 'CONNECTED' | 'DISCONNECTED' | 'DATA' | 'CONNECTION_FAILED'

    switch (event.event) {
      case 'STARTED':
      case 'RESTARTED':
        console.log('Server listening on port', event.port);
        break;

      case 'CONNECTED':
        console.log('Client connected:', event.client);
        break;

      case 'DISCONNECTED':
        console.log('Client disconnected:', event.client);
        break;

      case 'DATA':
        const text = window.cordova.plugins.TCPServer.decodeData(event);
        console.log('Data from', event.client, '→', text);
        break;

      case 'CONNECTION_FAILED':
        // Show event.message directly in the app UI
        console.warn('Connection issue:', event.message);
        this.showToast(event.message); // your UI method
        break;
    }
  },
  (err: any) => {
    console.error('Hard start failure:', err);
  }
);
```

### Stop the Server

```typescript
window.cordova.plugins.TCPServer.stopServer(
  (msg: string) => console.log('Stopped:', msg),
  (err: any)   => console.error('Stop error:', err)
);
```

### Restart the Server

```typescript
window.cordova.plugins.TCPServer.restartServer(
  "8443",
  (event: any, raw: string) => { /* same shape as startServer */ },
  (err: any)                => console.error('Restart error:', err)
);
```

### Query Current Status (without affecting the server)

```typescript
window.cordova.plugins.TCPServer.getStatus(
  (status: string) => {
    // "RUNNING|8443"  or  "STOPPED"
    console.log('Server status:', status);
  },
  (err: any) => console.error(err)
);
```

### Enable Debug Logging

```typescript
window.cordova.plugins.TCPServer.setDebugLogging(
  true,
  (msg: string) => console.log(msg),
  (err: any)    => console.error(err)
);
```

---

## Event Object Reference

| `event.event`       | Extra fields                              | Description |
|---------------------|-------------------------------------------|-------------|
| `STARTED`           | `port`                                    | Server is up and listening |
| `RESTARTED`         | `port`                                    | Server restarted (after restart call or auto-recovery) |
| `CONNECTED`         | `client` (`"ip:port"`)                    | A client connected |
| `DISCONNECTED`      | `client`                                  | A client disconnected cleanly |
| `DATA`              | `client`, `base64`                        | Data received; use `decodeData(event)` for text |
| `CONNECTION_FAILED` | `target`, `reason`, **`message`** (UI-ready) | Any error — server or client level |

### `CONNECTION_FAILED` — when does it fire?

| Cause | `target` | Example `message` |
|-------|----------|--------------------|
| Port already in use | `SERVER` | `"Server port is already occupied. Retrying…"` |
| Network lost | `SERVER` | `"Network connection lost. The server will reconnect automatically…"` |
| Network restored | `SERVER` | `"Network restored. Reconnecting server…"` |
| Auto-recovery in progress | `SERVER` | `"Auto-recovering in 2 s (attempt 3)"` |
| Max retries exceeded | `SERVER` | `"Server could not recover after 10 attempts. Please restart the app."` |
| Client read timeout | `ip:port` | `"Client 192.168.1.5:54321 timed out (no data received)."` |
| Client reset | `ip:port` | `"Client 192.168.1.5:54321 was reset – it may have restarted."` |

---

## Required Permissions

The following permissions are added automatically by the plugin:

**Android (`AndroidManifest.xml`)**
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
```

**iOS (`plugin.xml`)**
```xml
<framework src="SystemConfiguration.framework" />
```
`SystemConfiguration` is needed for `SCNetworkReachability` (network-change detection). No Info.plist entries are required.

---

## Resilience Scenarios Handled

| Scenario | Behaviour |
|----------|-----------|
| Port temporarily busy on start | Retried up to 5× with increasing delay |
| Server crashes / OS kills the process | Watchdog restarts it automatically (exponential back-off) |
| Wi-Fi / network switch | Detected immediately; server reconnects once network is back |
| WebView page reload | Server stays alive; callback re-registered automatically |
| App put to background and resumed | Server continues; any stale clients are cleaned up |
| Multiple clients connecting at once | Each handled on a separate thread/GCD queue |
| Client sends no data (stale connection) | Closed after 30 s read timeout; `CONNECTION_FAILED` sent to JS |
| Client disconnects unexpectedly | Detected via `ECONNRESET`/`EPIPE`; `CONNECTION_FAILED` sent to JS |
| Max auto-restart attempts reached | User notified with a clear message asking to restart the app |

---

## License

ISC © venkat
