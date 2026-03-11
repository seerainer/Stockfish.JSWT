/*
 * GraalJS Bridge for Stockfish WASM
 *
 * This script sets up a minimal Node.js-like environment in GraalJS
 * so that stockfish-18-lite-single.js can initialize properly. The bridge:
 * 1. Mocks process, require, module, etc.
 * 2. Captures the Stockfish factory from module.exports
 * 3. Creates the engine with wasmBinary (provided from Java)
 * 4. Builds a processCommand function using the Emscripten ccall API
 * 5. Exposes _sendCommand() and output via Java callbacks
 *
 * Java must set these bindings before evaluating this script:
 *   __javaOutputHandler  - ProxyExecutable for engine output lines
 *   __wasmBinary         - byte[] of the stockfish-18-lite-single.wasm file
 *   __stockfishJsSource  - String content of stockfish-18-lite-single.js
 */

// ============================================================
// Phase 1: Environment setup for Emscripten/stockfish-18-lite-single.js
// ============================================================

var global = globalThis;

// Mock Node.js __dirname and __filename globals
var __dirname = '.';
var __filename = 'stockfish-18-lite-single.js';

// Provide setTimeout/clearTimeout if not available (GraalJS doesn't include them by default)
if (typeof setTimeout === 'undefined') {
    // Simple synchronous setTimeout - executes callback immediately (delay ignored)
    // This works because Stockfish WASM runs synchronously in single-threaded mode
    global.setTimeout = function(fn, delay) {
        if (typeof fn === 'function') {
            fn();
        }
        return 0;
    };
    global.clearTimeout = function(id) {};
}

// Mock Node.js process object
// The toString tag makes Object.prototype.toString.call(process) === "[object process]"
var process = {
    argv: ['node', 'stockfish-18-lite-single.js'],
    on: function() { return this; },
    exit: function() {},
    versions: { node: '20.0.0' },
    stdout: { write: function() {} },
    stderr: { write: function() {} },
    exitCode: 0,
    cwd: function() { return '.'; },
    env: {},
    hrtime: function(prev) {
        // Returns [seconds, nanoseconds] high-resolution time
        var now = Date.now();
        var sec = Math.floor(now / 1000);
        var nsec = (now % 1000) * 1e6;
        if (prev) {
            sec -= prev[0];
            nsec -= prev[1];
            if (nsec < 0) {
                sec--;
                nsec += 1e9;
            }
        }
        return [sec, nsec];
    }
};
Object.defineProperty(process, Symbol.toStringTag, { value: 'process' });
global.process = process;

// Mock module system
var module = { exports: {}, id: 'stockfish' };
var exports = module.exports;

// Mock require function - provides minimal implementations of Node.js modules
var require = function(name) {
    if (name === 'path') {
        return {
            dirname: function(p) {
                var idx = p.lastIndexOf('/');
                return idx >= 0 ? p.substring(0, idx) : '.';
            },
            basename: function(p, ext) {
                var idx = p.lastIndexOf('/');
                var base = idx >= 0 ? p.substring(idx + 1) : p;
                if (ext && base.endsWith(ext)) {
                    base = base.substring(0, base.length - ext.length);
                }
                return base;
            },
            extname: function(p) {
                var idx = p.lastIndexOf('.');
                return idx >= 0 ? p.substring(idx) : '';
            },
            join: function() {
                return Array.prototype.slice.call(arguments).join('/');
            },
            normalize: function(e) { return e; }
        };
    }
    if (name === 'fs') {
        return {
            readFileSync: function(path, opts) {
                // WASM binary is provided directly via config.wasmBinary
                return new Uint8Array(0);
            },
            readFile: function(path, callback) {
                callback(new Error('fs.readFile not supported in GraalJS bridge'));
            }
        };
    }
    if (name === 'worker_threads') {
        return { isMainThread: true };
    }
    if (name === 'readline') {
        return {
            createInterface: function() {
                return {
                    on: function() { return this; },
                    setPrompt: function() {},
                    close: function() {}
                };
            }
        };
    }
    return {};
};
// Make require.main !== module so stockfish-18-lite-single.js takes the module.exports path
require.main = {};

// ============================================================
// Phase 2: Load stockfish-18-lite-single.js (sets module.exports = factory)
// ============================================================

(0, eval)(__stockfishJsSource);

// ============================================================
// Phase 3: Engine initialization and command interface
// ============================================================

var _factory = module.exports;
var _engine = null;
var _engineReady = false;
var _pendingCommands = [];
var _searchQueue = []; // Queue for go/setoption commands (like stockfish-18-lite-single.js's n[])

/**
 * Send output to the Java handler.
 */
function _output(line) {
    if (typeof __javaOutputHandler !== 'undefined' && __javaOutputHandler !== null) {
        __javaOutputHandler(line);
    }
}

/**
 * Execute a UCI command directly on the engine via ccall.
 * This mirrors the i(e) function in stockfish-18-lite-single.js.
 */
function _execCommand(cmd) {
    if (!_engine || !_engine.ccall) {
        return;
    }
    _engine.ccall('command', null, ['string'], [cmd], {
        async: false
    });
}

/**
 * Drain the search queue — process queued go/setoption commands
 * when the engine is not currently searching.
 * This mirrors the c() function in stockfish-18-lite-single.js.
 */
function _drainQueue() {
    while (_searchQueue.length > 0 && (!_engine._isSearching || !_engine._isSearching())) {
        _execCommand(_searchQueue.shift());
    }
}

/**
 * Process a UCI command with proper queueing for go/setoption.
 * This mirrors the f(e) function (processCommand) in stockfish-18-lite-single.js.
 */
function _processCommand(cmd) {
    cmd = cmd.trim();
    if (cmd.substring(0, 2) === 'go' || cmd.substring(0, 9) === 'setoption') {
        _searchQueue.push(cmd);
    } else {
        _execCommand(cmd);
    }
    _drainQueue();
}

/**
 * Initialize the Stockfish engine with the provided WASM binary.
 * Returns a Promise that resolves to true when the engine is ready.
 */
function _initEngine() {
    if (!_factory || typeof _factory !== 'function') {
        throw new Error('Stockfish factory not found in module.exports');
    }

    // Convert Java byte array to Uint8Array for Emscripten
    var wasmBytes;
    if (__wasmBinary && typeof __wasmBinary.length !== 'undefined') {
        var len = __wasmBinary.length;
        wasmBytes = new Uint8Array(len);
        for (var i = 0; i < len; i++) {
            // Java bytes are signed (-128 to 127), convert to unsigned
            var b = __wasmBinary[i];
            wasmBytes[i] = b < 0 ? b + 256 : b;
        }
    }

    var config = {
        locateFile: function(path) {
            // WASM is provided as wasmBinary, so locateFile is not critical
            return path;
        },
        listener: function(line) {
            _output(line);
        },
        print: function(line) {
            _output(line);
        },
        printErr: function(line) {
            _output('info string error: ' + line);
        }
    };

    // Provide WASM binary directly to skip file loading
    if (wasmBytes && wasmBytes.length > 0) {
        config.wasmBinary = wasmBytes.buffer;
    }

    // Call the factory to get the module creator, then create the engine
    var moduleCreator = _factory();
    var enginePromise = moduleCreator(config);

    return enginePromise.then(function(instance) {
        _engine = instance;

        // Set up the onDoneSearching callback to drain the command queue
        // This mirrors the l() function in stockfish-18-lite-single.js
        _engine.onDoneSearching = _drainQueue;

        _engineReady = true;

        // Process any commands that were queued before engine was ready
        for (var i = 0; i < _pendingCommands.length; i++) {
            _processCommand(_pendingCommands[i]);
        }
        _pendingCommands = [];

        return true;
    });
}

/**
 * Send a UCI command to the engine.
 */
function _sendCommand(cmd) {
    if (_engineReady && _engine) {
        _processCommand(cmd);
    } else {
        // Queue command for when engine is ready
        _pendingCommands.push(cmd);
    }
}

/**
 * Check if the engine is initialized and ready.
 */
function _isEngineReady() {
    return _engineReady;
}
