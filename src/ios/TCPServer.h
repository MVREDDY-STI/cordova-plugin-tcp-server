#import <Cordova/CDV.h>

@interface TCPServer : CDVPlugin

- (void)startServer:(CDVInvokedUrlCommand*)command;
- (void)stopServer:(CDVInvokedUrlCommand*)command;

@end
