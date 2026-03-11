package io.github.seerainer.chess.stockfish;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;

import io.github.seerainer.chess.config.ChessConfig;

/**
 * Low-level bridge between Java and the Stockfish WASM engine running in
 * GraalJS. Manages the GraalVM Polyglot context, loads the Stockfish
 * JavaScript/WASM files, and provides UCI protocol communication.
 *
 * <p>
 * All GraalJS operations run on a dedicated single thread since {@link Context}
 * is not thread-safe. Commands are submitted to the engine thread and output is
 * collected via a {@link BlockingQueue}.
 * </p>
 */
public class StockfishEngine implements AutoCloseable {

    // Resource paths for Stockfish files
    private static final String BRIDGE_JS_RESOURCE = "/stockfish/stockfish-graaljs-bridge.js";
    private static final String STOCKFISH_JS_RESOURCE = "/stockfish/stockfish-18-lite-single.js";
    private static final String STOCKFISH_WASM_RESOURCE = "/stockfish/stockfish-18-lite-single.wasm";

    // GraalVM Polyglot context and state
    private Context jsContext;
    private Value sendCommandFunction;

    // Threading - all GraalJS operations happen on this single thread
    private final ExecutorService engineThread;

    // Output handling
    private final BlockingQueue<String> outputQueue = new LinkedBlockingQueue<>();
    private volatile Consumer<String> outputListener;

    // State tracking
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile UCIProtocol.SearchInfo lastSearchInfo;

    public StockfishEngine() {
	this.engineThread = Executors.newSingleThreadExecutor(r -> {
	    final var thread = new Thread(r, "Stockfish-Engine");
	    thread.setDaemon(true);
	    return thread;
	});
    }

    /**
     * Initialize the Stockfish engine. Loads the WASM binary and JavaScript files,
     * creates the GraalJS context, and performs the UCI handshake.
     *
     * @throws StockfishInitException if initialization fails
     */
    public void initialize() throws StockfishInitException {
	if (initialized.get()) {
	    return;
	}
	if (closed.get()) {
	    throw new StockfishInitException("Engine has been closed");
	}

	try {
	    final Future<Boolean> future = engineThread.submit(() -> {
		initializeOnEngineThread();
		return Boolean.TRUE;
	    });

	    final var result = future.get(ChessConfig.Stockfish.INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
	    if (Boolean.TRUE.equals(result)) {
		initialized.set(true);
	    }
	} catch (final Exception e) {
	    throw new StockfishInitException("Failed to initialize Stockfish engine: " + e.getMessage(), e);
	}
    }

    /**
     * Performs initialization on the dedicated engine thread.
     */
    private void initializeOnEngineThread() throws IOException {
	// Load resources
	final var bridgeJs = loadStringResource(BRIDGE_JS_RESOURCE);
	final var stockfishJs = loadStringResource(STOCKFISH_JS_RESOURCE);
	final var wasmBytes = loadBinaryResource(STOCKFISH_WASM_RESOURCE);

	// Create GraalJS context with WebAssembly support
	// Both "js" and "wasm" languages must be permitted, and polyglot
	// access must be enabled so JS can instantiate WebAssembly modules.
	jsContext = Context.newBuilder("js", "wasm").allowExperimentalOptions(true).allowHostAccess(HostAccess.ALL)
		.allowPolyglotAccess(PolyglotAccess.ALL).option("js.webassembly", "true")
		.option("js.ecmascript-version", "2022").option("js.foreign-object-prototype", "true").build();

	// Set Java bindings accessible from JavaScript
	final var bindings = jsContext.getBindings("js");

	// Output handler - routes Stockfish output to Java
	// Use ProxyExecutable so GraalJS can call it directly as a function
	final ProxyExecutable outputHandler = (final Value... args) -> {
	    if (args.length > 0 && args[0] != null && !args[0].isNull()) {
		handleEngineOutput(args[0].asString());
	    }
	    return null;
	};
	bindings.putMember("__javaOutputHandler", outputHandler);

	// WASM binary bytes
	bindings.putMember("__wasmBinary", wasmBytes);

	// Stockfish JS source (the bridge will eval it)
	bindings.putMember("__stockfishJsSource", stockfishJs);

	// Evaluate the bridge script (sets up environment + loads
	// stockfish-18-lite-single.js)
	final var bridgeSource = Source.newBuilder("js", bridgeJs, "stockfish-graaljs-bridge.js").build();
	jsContext.eval(bridgeSource);

	// Initialize the engine (loads WASM, starts Stockfish)
	final var initPromise = jsContext.eval("js", "_initEngine()");

	// Wait for the Promise to resolve
	// GraalJS processes microtasks during eval, so the Promise may already be
	// resolved
	waitForPromise(initPromise);

	// Capture the sendCommand function for later use
	sendCommandFunction = jsContext.eval("js", "_sendCommand");

	// Perform UCI handshake
	performUCIHandshake();
    }

    /**
     * Wait for a JavaScript Promise to resolve. GraalJS processes microtasks during
     * eval calls, so we pump the context to give the Promise a chance to resolve.
     */
    private void waitForPromise(final Value promise) {
	if (promise == null) {
	    return;
	}

	// Use a simple polling approach: check if the engine is ready
	final var startTime = System.currentTimeMillis();
	final var timeout = ChessConfig.Stockfish.INIT_TIMEOUT_MS;

	while (System.currentTimeMillis() - startTime < timeout) {
	    // Pump the JS event loop by evaluating a trivial expression
	    final var ready = jsContext.eval("js", "_isEngineReady()");
	    if (ready.isBoolean() && ready.asBoolean()) {
		return;
	    }

	    // Brief sleep to avoid busy-waiting
	    try {
		Thread.sleep(50);
	    } catch (final InterruptedException e) {
		Thread.currentThread().interrupt();
		throw new RuntimeException("Interrupted while waiting for engine initialization", e);
	    }
	}

	// Check one final time
	final var ready = jsContext.eval("js", "_isEngineReady()");
	if (!ready.isBoolean() || !ready.asBoolean()) {
	    throw new RuntimeException(new StringBuilder().append("Stockfish engine initialization timed out after ")
		    .append(timeout).append("ms").toString());
	}
    }

    /**
     * Perform the initial UCI protocol handshake.
     */
    private void performUCIHandshake() {
	// Send "uci" and wait for "uciok"
	sendCommandInternal(UCIProtocol.CMD_UCI);
	waitForResponse(UCIProtocol.RESP_UCIOK, 10000);

	// Send "isready" and wait for "readyok"
	sendCommandInternal(UCIProtocol.CMD_ISREADY);
	waitForResponse(UCIProtocol.RESP_READYOK, 10000);
    }

    /**
     * Send a UCI command to the engine. Thread-safe — can be called from any
     * thread.
     *
     * <p>
     * The Stockfish WASM engine uses a <em>synchronous</em> {@code ccall} for
     * search commands: {@code sendCommandInternal} for a {@code go} command blocks
     * on the engine thread for the entire duration of the search before returning.
     * Therefore {@code go} commands are submitted fire-and-forget; the caller must
     * use {@link #waitForResponse} to collect the {@code bestmove} result with a
     * timeout that covers the full search budget.
     * </p>
     *
     * <p>
     * All other commands complete quickly and are awaited synchronously so that
     * subsequent commands (e.g. {@code position} before {@code go}) are guaranteed
     * to have been processed before this method returns.
     * </p>
     *
     * @param command the UCI command to send
     */
    public void sendCommand(final String command) {
	if (!initialized.get() || closed.get()) {
	    System.err.println("Stockfish: Cannot send command, engine not initialized or closed");
	    return;
	}

	try {
	    final var isSearchCommand = command.trim().startsWith("go");
	    if (isSearchCommand) {
		// The WASM ccall is synchronous: sendCommandInternal blocks for the entire
		// search duration on the engine thread. Submit fire-and-forget so this
		// method returns immediately and waitForResponse can begin polling the
		// output queue while the search runs.
		engineThread.submit(() -> sendCommandInternal(command));
	    } else {
		// Non-search commands complete in milliseconds; wait for them so that
		// ordering guarantees hold (e.g. position is set before go is sent).
		engineThread.submit(() -> sendCommandInternal(command)).get(ChessConfig.Stockfish.MOVE_TIMEOUT_MS,
			TimeUnit.MILLISECONDS);
	    }
	} catch (final Exception e) {
	    System.err.println("Error sending command to Stockfish: " + e.getMessage());
	}
    }

    /**
     * Send a command directly on the engine thread (must be called from engine
     * thread).
     */
    private void sendCommandInternal(final String command) {
	if (sendCommandFunction != null) {
	    sendCommandFunction.execute(command);
	} else {
	    // Fallback: call the function by name
	    jsContext.eval("js",
		    new StringBuilder().append("_sendCommand('").append(escapeJs(command)).append("')").toString());
	}
    }

    /**
     * Wait for a specific response line from the engine.
     *
     * @param expectedPrefix the prefix to match (e.g., "uciok", "readyok",
     *                       "bestmove")
     * @param timeoutMs      the maximum time to wait in milliseconds
     * @return the matching line, or null if timeout
     */
    public String waitForResponse(final String expectedPrefix, final long timeoutMs) {
	final var startTime = System.currentTimeMillis();

	while (System.currentTimeMillis() - startTime < timeoutMs) {
	    try {
		final var line = outputQueue.poll(100, TimeUnit.MILLISECONDS);
		if (line != null) {
		    // Parse and store search info
		    final var info = UCIProtocol.parseInfo(line);
		    if (info != null) {
			lastSearchInfo = info;
		    }

		    if (line.startsWith(expectedPrefix)) {
			return line;
		    }
		}
	    } catch (final InterruptedException e) {
		Thread.currentThread().interrupt();
		return null;
	    }
	}

	System.err.println(new StringBuilder().append("Stockfish: Timeout waiting for '").append(expectedPrefix)
		.append("' after ").append(timeoutMs).append("ms").toString());
	return null;
    }

    /**
     * Handle output from the Stockfish engine (called from the JS listener).
     */
    private void handleEngineOutput(final String line) {
	if (line == null || line.isEmpty()) {
	    return;
	}

	// Add to the output queue for synchronous waiting
	outputQueue.offer(line);

	// Notify the external listener if set
	final var listener = outputListener;
	if (listener != null) {
	    listener.accept(line);
	}

	if (ChessConfig.Debug.ENABLE_DEBUG_LOGGING) {
	    System.out.println("Stockfish> " + line);
	}
    }

    /**
     * Configure a UCI option.
     *
     * @param name  the option name
     * @param value the option value
     */
    public void setOption(final String name, final String value) {
	sendCommand(UCIProtocol.buildSetOptionCommand(name, value));
    }

    /**
     * Send "isready" and wait for "readyok".
     *
     * @return true if the engine responded in time
     */
    public boolean isReady() {
	if (!initialized.get()) {
	    return false;
	}
	sendCommand(UCIProtocol.CMD_ISREADY);
	return waitForResponse(UCIProtocol.RESP_READYOK, 5000) != null;
    }

    /**
     * Signal the engine to start a new game.
     */
    public void newGame() {
	sendCommand(UCIProtocol.CMD_UCINEWGAME);
	isReady(); // Wait for engine to be ready after clearing state
    }

    /**
     * Get the last search info received from the engine.
     *
     * @return the most recent SearchInfo, or null
     */
    public UCIProtocol.SearchInfo getLastSearchInfo() {
	return lastSearchInfo;
    }

    /**
     * Set an external output listener for engine lines.
     *
     * @param listener the listener to receive output lines
     */
    public void setOutputListener(final Consumer<String> listener) {
	this.outputListener = listener;
    }

    /**
     * Check if the engine is initialized and ready.
     *
     * @return true if initialized
     */
    public boolean isInitialized() {
	return initialized.get() && !closed.get();
    }

    /**
     * Clear the output queue.
     */
    public void clearOutputQueue() {
	outputQueue.clear();
	lastSearchInfo = null;
    }

    @Override
    public void close() {
	if (!closed.compareAndSet(false, true)) {
	    return;
	}
	initialized.set(false);
	// Capture the context reference before nulling it
	final var ctx = jsContext;
	try {
	    // Send quit command
	    if (ctx != null) {
		engineThread.submit(() -> {
		    try {
			sendCommandInternal(UCIProtocol.CMD_QUIT);
		    } catch (final Exception e) {
			// Ignore errors during shutdown
		    }
		}).get(2000, TimeUnit.MILLISECONDS);
	    }
	} catch (final Exception e) {
	    // Ignore timeout during shutdown
	}
	// Shutdown the engine thread first — this interrupts any
	// running WASM operations and prevents new tasks from being
	// submitted.
	engineThread.shutdownNow();
	try {
	    engineThread.awaitTermination(5, TimeUnit.SECONDS);
	} catch (final InterruptedException e) {
	    Thread.currentThread().interrupt();
	}
	// Close the GraalJS context from the calling thread.
	// This is safe because the engine thread has been shut down.
	// Context.close(true) cancels any in-flight operations.
	if (ctx != null) {
	    try {
		ctx.close(true);
	    } catch (final Exception e) {
		// Ignore errors during close — the context may
		// already be in an inconsistent state after
		// thread interruption.
	    }
	}
	sendCommandFunction = null;
	jsContext = null;
    }

    /**
     * Load a text resource from the classpath.
     */
    private static String loadStringResource(final String path) throws IOException {
	try (final var is = StockfishEngine.class.getResourceAsStream(path)) {
	    if (is == null) {
		throw new IOException("Resource not found: " + path);
	    }
	    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
	}
    }

    /**
     * Load a binary resource from the classpath.
     */
    private static byte[] loadBinaryResource(final String path) throws IOException {
	try (final var is = StockfishEngine.class.getResourceAsStream(path)) {
	    if (is == null) {
		throw new IOException("Resource not found: " + path);
	    }
	    return is.readAllBytes();
	}
    }

    /**
     * Escape a string for safe inclusion in a JavaScript string literal.
     */
    private static String escapeJs(final String s) {
	return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * Exception thrown when Stockfish engine initialization fails.
     */
    public static class StockfishInitException extends Exception {

	private static final long serialVersionUID = 1L;

	public StockfishInitException(final String message) {
	    super(message);
	}

	public StockfishInitException(final String message, final Throwable cause) {
	    super(message, cause);
	}
    }
}
