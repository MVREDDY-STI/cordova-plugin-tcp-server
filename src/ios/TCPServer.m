/********* TCPServer.m Cordova Plugin Implementation *******/

#import <Cordova/CDV.h>
#import <Foundation/Foundation.h>
#import "GCDAsyncSocket.h"

@interface TCPServer : CDVPlugin {
  // Member variables go here.
}
@interface TCPServer () <GCDAsyncSocketDelegate>

@property (nonatomic, strong) GCDAsyncSocket *serverSocket;
@property (nonatomic, strong) NSMutableArray *clientSockets;

- (void)coolMethod:(CDVInvokedUrlCommand*)command;
- (void)startServer:(CDVInvokedUrlCommand*)command;
- (void)stopServer:(CDVInvokedUrlCommand*)command;

@end

@implementation TCPServer

- (void)coolMethod:(CDVInvokedUrlCommand*)command
{
    CDVPluginResult* pluginResult = nil;
    NSString* echo = [command.arguments objectAtIndex:0];

    if (echo != nil && [echo length] > 0) {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:echo];
    } else {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR];
    }

    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}



- (void)pluginInitialize {
    self.clientSockets = [NSMutableArray array];
}

- (void)startServer:(CDVInvokedUrlCommand*)command {
    CDVPluginResult *pluginResult = nil;

    NSString* port = [command.arguments objectAtIndex:0];
     NSLog(@"Received port: %@", port);

    if (!self.serverSocket) {
        self.serverSocket = [[GCDAsyncSocket alloc] initWithDelegate:self delegateQueue:dispatch_get_main_queue()];
        
        NSError *error = nil;
        if ([self.serverSocket acceptOnPort:8082 error:&error]) {
            NSLog(@"Server is running on port 8082");
            pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"Server is running on port 8082"];
            // Set the KeepCallback before sending the result will keep the callback id for further callbacks
            [pluginResult setKeepCallbackAsBool:YES];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];

        } else {
            NSLog(@"Error starting server: %@", error);
            pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Error starting server"];
        }
    } else {
        NSLog(@"Server is already running.");
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Server is already running"];
    }

    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)stopServer:(CDVInvokedUrlCommand*)command {
    CDVPluginResult *pluginResult = nil;

    if (self.serverSocket) {
        [self.serverSocket disconnect];
        self.serverSocket = nil;
        [self.clientSockets removeAllObjects];
        
        NSLog(@"Server stopped.");
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"Server stopped"];

    } else {

        NSLog(@"Server is not running.");
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Server is not running"];

    }

    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

#pragma mark - GCDAsyncSocketDelegate

- (void)socket:(GCDAsyncSocket *)sock didAcceptNewSocket:(GCDAsyncSocket *)newSocket {
    NSLog(@"New client connected: %@", newSocket.connectedHost);

    [self.clientSockets addObject:newSocket];
    [newSocket readDataWithTimeout:-1 tag:0];
}

- (void)socket:(GCDAsyncSocket *)sock didReadData:(NSData *)data withTag:(long)tag {
    NSString *message = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
    CDVPluginResult *pluginResultMsg = nil;
    NSLog(@"Received message from client %@: %@", sock.connectedHost, message);

    // Do something with the received message, if needed
    pluginResultMsg = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:message];
    // Set the KeepCallback before sending the result will keep the callback id for further callbacks
    [pluginResultMsg setKeepCallbackAsBool:YES];
    [self.commandDelegate sendPluginResult:pluginResultMsg callbackId:command.callbackId];

    [sock readDataWithTimeout:-1 tag:0];
}

@end
