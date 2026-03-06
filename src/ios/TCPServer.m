#import "TCPServer.h"
#import <Cordova/CDV.h>
#import <Cordova/CDVPluginResult.h>
#import <SystemConfiguration/SystemConfiguration.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <errno.h>

static const int kDefaultPort          = 8443;
static const int kBufferSize           = 8192;
static const int kClientReadTimeout    = 30;   // seconds
static const int kBindRetryCount       = 5;
static const int kBindRetryDelayMs     = 500;  // milliseconds
static const int kMaxRestartAttempts   = 10;
static const int kRestartBaseDelayMs   = 1000;
static const int kRestartMaxDelayMs    = 30000;

// ─────────────────────────────── Private Interface ──────────────────────────

@interface TCPServer ()

@property (nonatomic, assign) int          serverFd;
@property (atomic,    assign) BOOL         isRunning;
@property (atomic,    assign) BOOL         serverIntentional; // user started
// Guards against a queued reachability/watchdog start firing right behind
// an already-in-progress start on the serial acceptQueue.
@property (atomic,    assign) BOOL         startInProgress;
@property (nonatomic, assign) BOOL         debugEnabled;
@property (nonatomic, assign) int          currentPort;
@property (nonatomic, assign) int          restartAttempts;
@property (nonatomic, strong) NSString    *serverCallbackId;
@property (nonatomic, strong) dispatch_queue_t acceptQueue;
@property (nonatomic, strong) dispatch_queue_t clientQueue;
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSNumber *> *clientSockets;
@property (nonatomic, strong) NSLock      *clientLock;

// Watchdog
@property (nonatomic, strong) dispatch_source_t watchdogTimer;

// Network reachability
@property (nonatomic, assign) SCNetworkReachabilityRef reachability;

@end

// ─────────────────────────────── Implementation ─────────────────────────────

@implementation TCPServer

#pragma mark - Lifecycle

- (void)pluginInitialize {
    self.serverFd           = -1;
    self.isRunning          = NO;
    self.serverIntentional  = NO;
    self.startInProgress    = NO;
    self.debugEnabled       = NO;
    self.currentPort        = 0;
    self.restartAttempts    = 0;
    self.clientSockets      = [NSMutableDictionary dictionary];
    self.clientLock         = [[NSLock alloc] init];
    self.acceptQueue        = dispatch_queue_create("com.tcpserver.accept",  DISPATCH_QUEUE_SERIAL);
    self.clientQueue        = dispatch_queue_create("com.tcpserver.clients", DISPATCH_QUEUE_CONCURRENT);
    [self setupReachability];
    [self logDebug:@"Plugin initialized"];
}

- (void)dispose {
    NSLog(@"TCPServer: dispose called");
    [self logDebug:@"dispose()"];
    self.serverIntentional = NO;
    [self cancelWatchdog];
    [self teardownReachability];
    [self forceCleanup];
}

#pragma mark - Plugin Actions

- (void)setDebugLogging:(CDVInvokedUrlCommand *)command {
    BOOL enabled = [[command argumentAtIndex:0 withDefault:@NO] boolValue];
    self.debugEnabled = enabled;
    NSLog(@"TCPServer: Debug logging %@", enabled ? @"ENABLED" : @"DISABLED");
    CDVPluginResult *r = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                          messageAsString:[NSString stringWithFormat:@"Debug logging %@",
                                                           enabled ? @"enabled" : @"disabled"]];
    [self.commandDelegate sendPluginResult:r callbackId:command.callbackId];
}

- (void)getStatus:(CDVInvokedUrlCommand *)command {
    NSString *status = self.isRunning
        ? [NSString stringWithFormat:@"RUNNING|%d", self.currentPort]
        : @"STOPPED";
    CDVPluginResult *r = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:status];
    [self.commandDelegate sendPluginResult:r callbackId:command.callbackId];
}

- (void)startServer:(CDVInvokedUrlCommand *)command {
    int port = [[command argumentAtIndex:0 withDefault:@(kDefaultPort)] intValue];
    [self logDebug:[NSString stringWithFormat:@"startServer() port=%d isRunning=%d", port, self.isRunning]];

    if (self.isRunning) {
        NSLog(@"TCPServer: Server already running on port %d – re-registering callback", self.currentPort);
        // Re-register so a reloaded WebView still receives events
        self.serverCallbackId = command.callbackId;
        [self sendStatus:[NSString stringWithFormat:@"STARTED|%d", self.currentPort]];
        return;
    }

    self.serverCallbackId  = command.callbackId;
    self.currentPort       = port;
    self.serverIntentional = YES;
    self.restartAttempts   = 0;

    dispatch_async(self.acceptQueue, ^{
        [self doStartWithRetry:port];
    });
}

- (void)stopServer:(CDVInvokedUrlCommand *)command {
    [self logDebug:[NSString stringWithFormat:@"stopServer() isRunning=%d", self.isRunning]];

    self.serverIntentional = NO;
    [self cancelWatchdog];

    if (!self.isRunning) {
        CDVPluginResult *r = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                                              messageAsString:@"Server not running"];
        [self.commandDelegate sendPluginResult:r callbackId:command.callbackId];
        return;
    }

    [self forceCleanup];

    CDVPluginResult *r = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                          messageAsString:@"Server stopped"];
    [self.commandDelegate sendPluginResult:r callbackId:command.callbackId];
    NSLog(@"TCPServer: Server stopped by user");
}

- (void)restartServer:(CDVInvokedUrlCommand *)command {
    int port = [[command argumentAtIndex:0 withDefault:@(kDefaultPort)] intValue];
    [self logDebug:[NSString stringWithFormat:@"restartServer() port=%d", port]];

    self.serverCallbackId  = command.callbackId;
    self.currentPort       = port;
    self.serverIntentional = YES;
    self.restartAttempts   = 0;
    [self cancelWatchdog];

    // Stop current run synchronously so the serial acceptQueue is free for the new start
    if (self.isRunning) {
        [self forceCleanup];
    }

    dispatch_async(self.acceptQueue, ^{
        [NSThread sleepForTimeInterval:0.5];
        [self doStartWithRetry:port];
    });
}

#pragma mark - Core Server Start (with port-bind retry)

/**
 * Attempts to create, bind, and listen the server socket.
 * Retries up to kBindRetryCount times on EADDRINUSE before giving up.
 * On success, enters the run-loop synchronously (blocking the acceptQueue thread).
 *
 * Although acceptQueue is serial (so true parallel execution is impossible),
 * a queued recovery task can run immediately after the current one finishes.
 * startInProgress lets a queued task detect there's nothing to do.
 */
- (void)doStartWithRetry:(int)port {
    if (self.startInProgress) {
        [self logDebug:@"doStartWithRetry() – start already in progress; skipping queued call"];
        return;
    }
    self.startInProgress = YES;
    for (int attempt = 1; attempt <= kBindRetryCount; attempt++) {
        [self logDebug:[NSString stringWithFormat:@"doStartWithRetry() attempt=%d port=%d", attempt, port]];

        if ([self doStartServerInternal:port]) {
            // Successful bind – announce and enter accept loop
            NSString *msg = (attempt == 1)
                ? [NSString stringWithFormat:@"STARTED|%d", port]
                : [NSString stringWithFormat:@"RESTARTED|%d", port];
            [self sendStatus:msg];
            self.restartAttempts = 0;
            NSLog(@"TCPServer: Server started on port %d (attempt %d)", port, attempt);
            // Release guard before the blocking accept loop so stop/restart
            // commands issued later can queue a new start.
            self.startInProgress = NO;
            [self doAcceptLoop];

            // doAcceptLoop returned – check if we should auto-recover
            if (self.serverIntentional) {
                NSLog(@"TCPServer: Accept loop exited unexpectedly");
                [self sendStatus:@"CONNECTION_FAILED|SERVER|Accept loop exited unexpectedly"];
                [self scheduleWatchdog];
            }
            return;
        }

        // Bind failed – retry with delay unless last attempt
        if (attempt < kBindRetryCount) {
            int delayMs = kBindRetryDelayMs * attempt;
            [self sendStatus:[NSString stringWithFormat:
                @"CONNECTION_FAILED|SERVER|Bind attempt %d failed – retrying in %d ms…", attempt, delayMs]];
            [NSThread sleepForTimeInterval:(delayMs / 1000.0)];
        } else {
            [self sendStatus:[NSString stringWithFormat:
                @"CONNECTION_FAILED|SERVER|Unable to bind on port %d after %d attempts",
                port, kBindRetryCount]];
            if (self.serverIntentional) {
                [self scheduleWatchdog];
            }
        }
    }
    self.startInProgress = NO;
}

/** Creates, binds, and listens the server socket. Returns YES on success. */
- (BOOL)doStartServerInternal:(int)port {
    int fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (fd < 0) {
        NSString *err = [NSString stringWithFormat:@"Start error: %s", strerror(errno)];
        NSLog(@"TCPServer: create socket failed – %s", strerror(errno));
        [self sendStatus:[NSString stringWithFormat:@"CONNECTION_FAILED|SERVER|%@", err]];
        return NO;
    }

    int yes = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_len    = sizeof(addr);
    addr.sin_family = AF_INET;
    addr.sin_port   = htons(port);
    addr.sin_addr.s_addr = htonl(INADDR_ANY);

    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        NSLog(@"TCPServer: bind failed on port %d – %s", port, strerror(errno));
        close(fd);
        return NO;
    }

    if (listen(fd, 16) < 0) {
        NSLog(@"TCPServer: listen failed – %s", strerror(errno));
        close(fd);
        [self sendStatus:[NSString stringWithFormat:@"CONNECTION_FAILED|SERVER|Listen error: %s", strerror(errno)]];
        return NO;
    }

    self.serverFd    = fd;
    self.currentPort = port;
    self.isRunning   = YES;
    return YES;
}

#pragma mark - Accept Loop

- (void)doAcceptLoop {
    [self logDebug:[NSString stringWithFormat:@"acceptLoop() entered isRunning=%d", self.isRunning]];

    while (self.isRunning) {
        int currentFd = self.serverFd;
        if (currentFd < 0) break;

        struct pollfd pfd = { currentFd, POLLIN, 0 };
        int pr = poll(&pfd, 1, 1000);

        if (!self.isRunning) break;

        if (pr < 0) {
            if (errno == EINTR) continue;
            if (self.isRunning) {
                NSLog(@"TCPServer: poll error – %s", strerror(errno));
                [self sendStatus:[NSString stringWithFormat:@"CONNECTION_FAILED|SERVER|poll error: %s", strerror(errno)]];
            }
            break;
        }
        if (pr == 0) continue; // timeout – loop check

        struct sockaddr_in clientAddr;
        socklen_t len = sizeof(clientAddr);
        int clientFd  = accept(currentFd, (struct sockaddr *)&clientAddr, &len);

        if (clientFd < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) continue;
            if (self.isRunning) {
                NSString *reason = [self friendlyErrorForErrno:errno port:self.currentPort];
                NSLog(@"TCPServer: accept error – %s", strerror(errno));
                [self sendStatus:[NSString stringWithFormat:@"CONNECTION_FAILED|UNKNOWN|%@", reason]];
            }
            break;
        }

        char ipBuf[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &clientAddr.sin_addr, ipBuf, sizeof(ipBuf));
        NSString *clientKey = [NSString stringWithFormat:@"%s:%d", ipBuf, ntohs(clientAddr.sin_port)];
        NSLog(@"TCPServer: Client connected: %@", clientKey);

        if (![self configureClientSocket:clientFd clientKey:clientKey]) {
            [self sendStatus:[NSString stringWithFormat:@"CONNECTION_FAILED|%@|Socket configuration failed", clientKey]];
            close(clientFd);
            continue;
        }

        // Register client
        [self.clientLock lock];
        NSNumber *existing = self.clientSockets[clientKey];
        if (existing) {
            [self logDebug:[NSString stringWithFormat:@"Replacing stale connection: %@", clientKey]];
            close([existing intValue]);
        }
        self.clientSockets[clientKey] = @(clientFd);
        [self.clientLock unlock];

        [self sendStatus:[NSString stringWithFormat:@"CONNECTED|%@", clientKey]];

        dispatch_async(self.clientQueue, ^{
            [self handleClient:clientFd clientKey:clientKey];
        });
    }

    self.isRunning = NO;
    NSLog(@"TCPServer: Accept loop ended");
    [self logDebug:@"acceptLoop() exited"];
}

#pragma mark - Client socket configuration

- (BOOL)configureClientSocket:(int)fd clientKey:(NSString *)clientKey {
    struct timeval timeout = { kClientReadTimeout, 0 };
    if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) < 0) {
        NSLog(@"TCPServer: SO_RCVTIMEO failed for %@ – %s (no read timeout)", clientKey, strerror(errno));
    }
    int yes = 1;
    setsockopt(fd, SOL_SOCKET,  SO_KEEPALIVE, &yes, sizeof(yes));
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY,  &yes, sizeof(yes));
    return YES; // individual option failures are non-fatal
}

#pragma mark - Client Handling

- (void)handleClient:(int)clientFd clientKey:(NSString *)clientKey {
    [self logDebug:[NSString stringWithFormat:@"handleClient() started for: %@", clientKey]];

    NSMutableData *accumulated = [NSMutableData data];
    uint8_t buffer[kBufferSize];
    ssize_t totalRead = 0;
    BOOL hadError     = NO;

    for (;;) {
        ssize_t n = recv(clientFd, buffer, sizeof(buffer), 0);

        if (n > 0) {
            [accumulated appendBytes:buffer length:n];
            totalRead += n;
            [self logDebug:[NSString stringWithFormat:@"recv() %@ chunk=%zd total=%zd", clientKey, n, totalRead]];

        } else if (n == 0) {
            [self logDebug:[NSString stringWithFormat:@"handleClient() %@ – EOF", clientKey]];
            break;

        } else {
            if (errno == EINTR) continue;

            NSString *description = [self friendlyErrorForErrno:errno port:self.currentPort];

            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                NSLog(@"TCPServer: Read timeout [%@]", clientKey);
                [self sendStatus:[NSString stringWithFormat:
                    @"CONNECTION_FAILED|%@|Read timeout – no data for %d s",
                    clientKey, kClientReadTimeout]];
            } else {
                NSLog(@"TCPServer: recv error [%@]: %s", clientKey, strerror(errno));
                [self sendStatus:[NSString stringWithFormat:@"CONNECTION_FAILED|%@|%@",
                    clientKey, description]];
            }
            hadError = YES;
            break;
        }
    }

    if ([accumulated length] > 0) {
        NSString *b64 = [accumulated base64EncodedStringWithOptions:0];
        [self sendStatus:[NSString stringWithFormat:@"DATA|%@|%@", clientKey, b64]];
        NSLog(@"TCPServer: Received %lu bytes from: %@", (unsigned long)[accumulated length], clientKey);
    } else if (!hadError) {
        NSLog(@"TCPServer: No data received from: %@", clientKey);
    }

    [self sendStatus:[NSString stringWithFormat:@"DISCONNECTED|%@", clientKey]];

    // Guard against double-close
    [self.clientLock lock];
    NSNumber *storedFd = self.clientSockets[clientKey];
    BOOL shouldClose = (storedFd != nil && [storedFd intValue] == clientFd);
    if (shouldClose) [self.clientSockets removeObjectForKey:clientKey];
    NSUInteger remaining = [self.clientSockets count];
    [self.clientLock unlock];

    if (shouldClose) close(clientFd);

    NSLog(@"TCPServer: Client disconnected: %@ remaining=%lu", clientKey, (unsigned long)remaining);
}

#pragma mark - Watchdog (auto-recovery with exponential back-off)

- (void)scheduleWatchdog {
    if (!self.serverIntentional) return;

    self.restartAttempts++;
    if (self.restartAttempts > kMaxRestartAttempts) {
        NSLog(@"TCPServer: Max restart attempts reached – giving up");
        [self sendStatus:[NSString stringWithFormat:
            @"CONNECTION_FAILED|SERVER|Server could not recover after %d attempts. Please restart the app.",
            kMaxRestartAttempts]];
        self.serverIntentional = NO;
        return;
    }

    int shift = MIN(self.restartAttempts - 1, 5); // cap shift to avoid overflow (2^5 = 32× base)
    long long delayMs = MIN((long long)kRestartBaseDelayMs << shift, (long long)kRestartMaxDelayMs);
    NSLog(@"TCPServer: Watchdog – scheduling restart #%d in %lld ms", self.restartAttempts, delayMs);
    [self sendStatus:[NSString stringWithFormat:
        @"CONNECTION_FAILED|SERVER|Auto-recovering in %lld s (attempt %d)",
        delayMs / 1000, self.restartAttempts]];

    dispatch_source_t timer = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0,
                                                      dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0));
    self.watchdogTimer = timer;
    uint64_t interval = (uint64_t)(delayMs * NSEC_PER_MSEC);
    dispatch_source_set_timer(timer, dispatch_time(DISPATCH_TIME_NOW, interval), DISPATCH_TIME_FOREVER, NSEC_PER_MSEC * 100);
    dispatch_source_set_event_handler(timer, ^{
        dispatch_source_cancel(timer);
        if (!self.serverIntentional || self.isRunning || self.startInProgress) return;
        NSLog(@"TCPServer: Watchdog firing restart #%d", self.restartAttempts);
        dispatch_async(self.acceptQueue, ^{
            [self doStartWithRetry:self.currentPort];
        });
    });
    dispatch_resume(timer);
}

- (void)cancelWatchdog {
    if (self.watchdogTimer) {
        dispatch_source_cancel(self.watchdogTimer);
        self.watchdogTimer = nil;
        [self logDebug:@"Watchdog cancelled"];
    }
}

#pragma mark - Network Reachability

static void ReachabilityCallback(SCNetworkReachabilityRef target, SCNetworkReachabilityFlags flags, void *info) {
    TCPServer *self_ = (__bridge TCPServer *)info;
    BOOL reachable = (flags & kSCNetworkReachabilityFlagsReachable) != 0
                  && (flags & kSCNetworkReachabilityFlagsConnectionRequired) == 0;

    NSLog(@"TCPServer: Network reachability changed – reachable=%d isRunning=%d intended=%d",
          reachable, self_.isRunning, self_.serverIntentional);

    if (reachable && self_.serverIntentional && !self_.isRunning && !self_.startInProgress) {
        NSLog(@"TCPServer: Network restored – triggering auto-recovery");
        [self_ sendStatus:@"CONNECTION_FAILED|SERVER|Network restored – reconnecting…"];
        self_.restartAttempts = 0;
        [self_ cancelWatchdog];
        [self_ forceCleanup];
        dispatch_async(self_.acceptQueue, ^{
            [NSThread sleepForTimeInterval:0.5];
            [self_ doStartWithRetry:self_.currentPort];
        });
    }

    if (!reachable && self_.isRunning) {
        NSLog(@"TCPServer: Network lost – clients may drop");
        [self_ sendStatus:@"CONNECTION_FAILED|SERVER|Network connectivity lost"];
    }
}

- (void)setupReachability {
    // Use zero-address form: monitors general internet reachability without DNS lookup.
    // More reliable on iOS 16/17+ than the name-based form.
    struct sockaddr_in zeroAddr;
    memset(&zeroAddr, 0, sizeof(zeroAddr));
    zeroAddr.sin_len    = sizeof(zeroAddr);
    zeroAddr.sin_family = AF_INET;

    self.reachability = SCNetworkReachabilityCreateWithAddress(NULL, (struct sockaddr *)&zeroAddr);
    if (!self.reachability) return;

    SCNetworkReachabilityContext ctx = { 0, (__bridge void *)self, NULL, NULL, NULL };
    if (SCNetworkReachabilitySetCallback(self.reachability, ReachabilityCallback, &ctx)) {
        SCNetworkReachabilityScheduleWithRunLoop(self.reachability,
                                                 CFRunLoopGetMain(),
                                                 kCFRunLoopDefaultMode);
    }
    [self logDebug:@"Network reachability monitoring started"];
}

- (void)teardownReachability {
    if (self.reachability) {
        SCNetworkReachabilityUnscheduleFromRunLoop(self.reachability, CFRunLoopGetMain(), kCFRunLoopDefaultMode);
        CFRelease(self.reachability);
        self.reachability = nil;
        [self logDebug:@"Network reachability monitoring stopped"];
    }
}

#pragma mark - Cleanup

- (void)forceCleanup {
    [self logDebug:[NSString stringWithFormat:@"forceCleanup() clients=%lu",
                    (unsigned long)[self.clientSockets count]]];
    self.isRunning = NO;

    [self.clientLock lock];
    for (NSString *key in self.clientSockets) {
        int fd = [self.clientSockets[key] intValue];
        shutdown(fd, SHUT_RDWR);
        close(fd);
    }
    [self.clientSockets removeAllObjects];
    [self.clientLock unlock];

    if (self.serverFd >= 0) {
        close(self.serverFd);
        self.serverFd = -1;
    }
    self.currentPort = 0;
    [self logDebug:@"forceCleanup() done"];
}

#pragma mark - Status Messaging

- (void)sendStatus:(NSString *)message {
    if (!self.serverCallbackId) {
        NSLog(@"TCPServer: WARNING – sendStatus with no callbackId: %@", message);
        return;
    }
    [self logDebug:[NSString stringWithFormat:@"sendStatus() → %@", message]];
    CDVPluginResult *r = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:message];
    [r setKeepCallbackAsBool:YES];
    [self.commandDelegate sendPluginResult:r callbackId:self.serverCallbackId];
}

- (void)sendError:(NSString *)message {
    if (!self.serverCallbackId) return;
    CDVPluginResult *r = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:message];
    [self.commandDelegate sendPluginResult:r callbackId:self.serverCallbackId];
}

#pragma mark - Friendly Error Messages

- (NSString *)friendlyErrorForErrno:(int)err port:(int)port {
    switch (err) {
        case EADDRINUSE:    return [NSString stringWithFormat:@"Port %d already in use – another process may be holding it", port];
        case EACCES:        return [NSString stringWithFormat:@"Permission denied – cannot bind to port %d", port];
        case ENETUNREACH:   return @"Network unreachable – check Wi-Fi/LAN connection";
        case ECONNRESET:    return @"Connection reset by client";
        case EPIPE:         return @"Connection broken – client disconnected unexpectedly";
        case ETIMEDOUT:     return @"Connection timed out";
        case ECONNREFUSED:  return @"Connection refused by remote host";
        default:            return [NSString stringWithUTF8String:strerror(err)] ?: @"Unknown error";
    }
}

#pragma mark - Debug Logging

- (void)logDebug:(NSString *)message {
    if (self.debugEnabled) {
        NSString *thread = NSThread.currentThread.name ?: @"unnamed";
        NSLog(@"TCPServer: [DEBUG][%@] %@", thread, message);
    }
}

@end
