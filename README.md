# cordova-plugin-tcp-server
This is the android and iOS cordova plugin to act as a TCP/IP Server to receive messages from the TCP/IP client

**Plugin supports**
- Android
- iOS

**Plugin Usage**

**Step 2** : Declare the below line of code under the imports in the page you want to use TCP server.

  ```TypeScript
  declare var window:any;
  ```

**Step 2** : Use the Below line of code for starting the TCP/IP Server.

  ```TypeScript
   window.cordova.plugins.TCPServer.startServer("8082",(data:any) => {
          console.log("DATA RECEIVED(S) : ", data);
  },(err:any)=>{
          console.warn("DATA RECEIVED(E)  : ", err);
  });
  ```

**Step 3** : Use the Below line of code for stopping the TCP/IP Server.

  ```TypeScript
   window.cordova.plugins.TCPServer.stopServer("8082",(data:any) => {
          console.log("DATA RECEIVED(S) : ", data);
  },(err:any)=>{
          console.warn("DATA RECEIVED(E)  : ", err);
  });
  ```
