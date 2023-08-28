#import "TCPServer.h"
#import <Cordova/CDV.h>
#import <Cordova/CDVPluginResult.h>
#import <CoreFoundation/CoreFoundation.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

@interface TCPServer ()

@property (nonatomic, assign) CFSocketRef serverSocket;
@property (nonatomic, assign) CFSocketNativeHandle clientSocket;
@property (nonatomic, strong) NSString *receiveCallbackId;

@end

@implementation TCPServer

static void TCPServerAcceptCallBack(CFSocketRef socket, CFSocketCallBackType type, CFDataRef address, const void *data, void *info) {
    TCPServer *tcpServer = (__bridge TCPServer *)(info);

    if (type == kCFSocketAcceptCallBack) {
        CFSocketNativeHandle nativeSocketHandle = *(CFSocketNativeHandle *)data;

        tcpServer.clientSocket = nativeSocketHandle;
        [tcpServer receiveMessages];
    }
}


- (void)startServer:(CDVInvokedUrlCommand*)command {

   NSLog(command.callbackId);
    if (self.serverSocket) {
        NSLog(@"Server is already running.");
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Server is already running."];
        [pluginResult setKeepCallbackAsBool:YES];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        return;
    }

    CFSocketContext socketContext = {0, (__bridge void *)(self), NULL, NULL, NULL};
    self.serverSocket = CFSocketCreate(NULL, PF_INET, SOCK_STREAM, IPPROTO_TCP, kCFSocketAcceptCallBack, TCPServerAcceptCallBack, &socketContext);

    if (self.serverSocket == NULL) {
        NSLog(@"Failed to create server socket.");
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Failed to create server socket."];
        [pluginResult setKeepCallbackAsBool:YES];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        return;
    }

    int yes = 1;
    setsockopt(CFSocketGetNative(self.serverSocket), SOL_SOCKET, SO_REUSEADDR, (void *)&yes, sizeof(yes));

    struct sockaddr_in serverAddress;
    memset(&serverAddress, 0, sizeof(serverAddress));
    serverAddress.sin_len = sizeof(serverAddress);
    serverAddress.sin_family = AF_INET;
    serverAddress.sin_port = htons(8082);
    serverAddress.sin_addr.s_addr = htonl(INADDR_ANY);

    CFDataRef addressData = CFDataCreate(NULL, (const UInt8 *)&serverAddress, sizeof(serverAddress));
    CFSocketSetAddress(self.serverSocket, addressData);

    CFRunLoopSourceRef runLoopSource = CFSocketCreateRunLoopSource(kCFAllocatorDefault, self.serverSocket, 0);
    CFRunLoopAddSource(CFRunLoopGetCurrent(), runLoopSource, kCFRunLoopCommonModes);
    CFRelease(runLoopSource);

    NSLog(@"Server started.");
    self.receiveCallbackId = command.callbackId;
    CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"Server started."];
    [pluginResult setKeepCallbackAsBool:YES];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:self.receiveCallbackId];


}


- (void)receiveMessages {
    NSMutableData *accumulatedData = [NSMutableData data];
    uint8_t buffer[4096];

    while (1) {
        CFIndex bytesRead = recv(self.clientSocket, buffer, sizeof(buffer), 0);

        if (bytesRead > 0) {
            [accumulatedData appendBytes:buffer length:bytesRead];
        } else if (bytesRead == 0) {
            // Client disconnected
            break;
        } else {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                // No data available for now, continue the loop
                // Optionally add a delay or other logic here if desired
                continue;
            } else {
                // Error during recv
                NSLog(@"Error during recv: %s", strerror(errno));
                break;
            }
        }
    }

    // Process and send all accumulated data in a single shot
    if ([accumulatedData length] > 0) {
        [self processAndSendData:accumulatedData];
    }
}

- (void)processAndSendData:(NSData *)data {
    NSString *base64String = [data base64EncodedStringWithOptions:0];
    CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:base64String];
    [pluginResult setKeepCallbackAsBool:YES];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:self.receiveCallbackId];
}
@end
