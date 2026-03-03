#import <Cordova/CDV.h>

@interface TCPServer : CDVPlugin

- (void)startServer:(CDVInvokedUrlCommand *)command;
- (void)stopServer:(CDVInvokedUrlCommand *)command;
- (void)restartServer:(CDVInvokedUrlCommand *)command;
- (void)setDebugLogging:(CDVInvokedUrlCommand *)command;
- (void)getStatus:(CDVInvokedUrlCommand *)command;

@end
