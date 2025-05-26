var exec = require('cordova/exec');

exports.coolMethod = function (arg0, success, error) {
    exec(success, error, 'TCPServer', 'coolMethod', [arg0]);
};

exports.startServer = function (arg0, success, error) {
    exec(success, error, 'TCPServer', 'startServer', [arg0]);
};

exports.restartServer = function (arg0, success, error) {
    exec(success, error, 'TCPServer', 'restartServer', [arg0]);
};

exports.stopServer = function (success, error) {
    exec(success, error, 'TCPServer', 'stopServer', []);
};
