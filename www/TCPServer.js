var exec = require('cordova/exec');

// ─── Internal helpers ────────────────────────────────────────────────────────

/**
 * Parses the pipe-delimited status string sent by the native layer.
 *
 * Formats:
 *   STARTED|<port>
 *   RESTARTED|<port>
 *   CONNECTED|<ip:port>
 *   DISCONNECTED|<ip:port>
 *   DATA|<ip:port>|<base64>
 *   CONNECTION_FAILED|<target>|<reason>
 *   RUNNING|<port>   (from getStatus)
 *   STOPPED          (from getStatus)
 */
function parseEvent(raw) {
    if (typeof raw !== 'string') return { event: 'UNKNOWN', raw: raw };
    var parts = raw.split('|');
    var event = parts[0];
    switch (event) {
        case 'STARTED':
        case 'RESTARTED':
            return { event: event, port: parseInt(parts[1], 10) };
        case 'CONNECTED':
        case 'DISCONNECTED':
            return { event: event, client: parts[1] };
        case 'DATA':
            return { event: event, client: parts[1], base64: parts[2] };
        case 'CONNECTION_FAILED':
            return { event: event, target: parts[1], reason: parts.slice(2).join('|'),
                     message: buildUserMessage(parts[1], parts.slice(2).join('|')) };
        case 'RUNNING':
            return { event: event, port: parseInt(parts[1], 10) };
        case 'STOPPED':
            return { event: event };
        default:
            return { event: 'UNKNOWN', raw: raw };
    }
}

/** Produces a human-readable message suitable for display in the app UI. */
function buildUserMessage(target, reason) {
    if (!reason) reason = 'Unknown error';
    if (target === 'SERVER') {
        // Server-level failures
        if (/already in use/i.test(reason))
            return 'Server port is already occupied. Retrying…';
        if (/Permission denied/i.test(reason))
            return 'Cannot start server: permission denied on selected port.';
        if (/Network unreachable/i.test(reason) || /connectivity lost/i.test(reason))
            return 'Network connection lost. The server will reconnect automatically when the network is restored.';
        if (/Network restored/i.test(reason) || /reconnecting/i.test(reason))
            return 'Network restored. Reconnecting server…';
        if (/Auto-recovering/i.test(reason))
            return reason; // already human-readable from native
        if (/could not recover/i.test(reason))
            return reason;
        if (/Accept loop exited/i.test(reason))
            return 'Server encountered an unexpected error. Attempting to restart…';
        return 'Server error: ' + reason;
    }
    // Client-level failures
    if (/Read timeout/i.test(reason))
        return 'Client ' + target + ' timed out (no data received).';
    if (/Connection reset/i.test(reason))
        return 'Client ' + target + ' was reset – it may have restarted or lost its connection.';
    if (/Broken pipe/i.test(reason))
        return 'Client ' + target + ' disconnected unexpectedly.';
    if (/timed out/i.test(reason))
        return 'Client ' + target + ' connection timed out.';
    return 'Client ' + target + ' error: ' + reason;
}

// ─── Public API ──────────────────────────────────────────────────────────────

/**
 * Start the TCP server on the given port.
 *
 * @param {string|number} port   Port to listen on (e.g. "8443").
 * @param {Function}      success Called for every server event (STARTED, CONNECTED,
 *                                DISCONNECTED, DATA, CONNECTION_FAILED, …).
 *                                Receives a parsed event object + raw string.
 * @param {Function}      error  Called only on hard startup failure.
 */
exports.startServer = function (port, success, error) {
    exec(
        function (raw) {
            if (typeof success === 'function') success(parseEvent(raw), raw);
        },
        error,
        'TCPServer',
        'startServer',
        [port]
    );
};

/**
 * Stop the TCP server.
 */
exports.stopServer = function (success, error) {
    exec(success, error, 'TCPServer', 'stopServer', []);
};

/**
 * Restart the TCP server on the given port.
 */
exports.restartServer = function (port, success, error) {
    exec(
        function (raw) {
            if (typeof success === 'function') success(parseEvent(raw), raw);
        },
        error,
        'TCPServer',
        'restartServer',
        [port]
    );
};

/**
 * Enable or disable verbose native debug logging.
 */
exports.setDebugLogging = function (enabled, success, error) {
    exec(success, error, 'TCPServer', 'setDebugLogging', [!!enabled]);
};

/**
 * Query the current server state without starting or stopping it.
 * success is called once with either "RUNNING|<port>" or "STOPPED".
 */
exports.getStatus = function (success, error) {
    exec(success, error, 'TCPServer', 'getStatus', []);
};

/**
 * Convenience: decode a base64 DATA event payload to a UTF-8 string.
 * Returns null if decoding fails.
 *
 * @param {object} eventObj  Parsed event object from startServer success callback.
 * @returns {string|null}
 */
exports.decodeData = function (eventObj) {
    try {
        if (!eventObj || eventObj.event !== 'DATA' || !eventObj.base64) return null;
        return decodeURIComponent(
            escape(typeof atob === 'function'
                ? atob(eventObj.base64)
                : Buffer.from(eventObj.base64, 'base64').toString('binary'))
        );
    } catch (e) {
        return null;
    }
};
