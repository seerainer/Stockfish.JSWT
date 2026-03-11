package io.github.seerainer.chess.test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.seerainer.chess.stockfish.StockfishEngine;
import io.github.seerainer.chess.stockfish.StockfishEngine.StockfishInitException;
import io.github.seerainer.chess.stockfish.UCIProtocol;

/**
 * Integration tests for the StockfishEngine WASM bridge. These tests require
 * the GraalVM polyglot runtime and the Stockfish WASM resources to be on the
 * classpath. Tests that depend on successful engine initialization use
 * {@code assumeTrue} to skip gracefully if the engine cannot start.
 */
@DisplayName("StockfishEngine")
class StockfishEngineTest {

    private StockfishEngine engine;

    @BeforeEach
    void setUp() {
	engine = new StockfishEngine();
    }

    @AfterEach
    void tearDown() {
	if (engine != null) {
	    engine.close();
	}
    }

    @Test
    @DisplayName("Engine is not initialized before calling initialize()")
    void testNotInitializedByDefault() {
	assertFalse(engine.isInitialized(), "Engine should not be initialized by default");
    }

    @Test
    @DisplayName("Engine can be initialized with WASM resources")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testInitialization() {
	try {
	    engine.initialize();
	    assertTrue(engine.isInitialized(), "Engine should be initialized after init");
	} catch (final StockfishInitException e) {
	    System.err.println("Stockfish initialization failed (expected in CI): " + e.getMessage());
	    // Skip remaining assertions — engine not available
	    assumeTrue(false, "Stockfish WASM not available in this environment");
	}
    }

    @Test
    @DisplayName("Engine responds to isready command")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testIsReady() {
	try {
	    engine.initialize();
	} catch (final StockfishInitException e) {
	    assumeTrue(false, "Stockfish WASM not available");
	}

	assertTrue(engine.isReady(), "Engine should respond readyok to isready");
    }

    @Test
    @DisplayName("Engine can set UCI options")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testSetOption() {
	try {
	    engine.initialize();
	} catch (final StockfishInitException e) {
	    assumeTrue(false, "Stockfish WASM not available");
	}

	// Should not throw
	assertDoesNotThrow(() -> {
	    engine.setOption("Skill Level", "10");
	    engine.setOption("UCI_LimitStrength", "true");
	    engine.setOption("UCI_Elo", "1500");
	});

	// Verify engine is still responsive
	assertTrue(engine.isReady(), "Engine should be ready after setting options");
    }

    @Test
    @DisplayName("Engine can process a position and return bestmove")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testBestMoveResponse() {
	try {
	    engine.initialize();
	} catch (final StockfishInitException e) {
	    assumeTrue(false, "Stockfish WASM not available");
	}

	engine.clearOutputQueue();

	// Set starting position
	engine.sendCommand("position startpos");

	// Ask for best move at shallow depth
	engine.sendCommand("go depth 5");

	// Wait for bestmove response
	final var response = engine.waitForResponse(UCIProtocol.RESP_BESTMOVE, 15000);
	assertNotNull(response, "Should receive a bestmove response");
	assertTrue(response.startsWith("bestmove"), "Response should start with 'bestmove'");

	// Parse the result
	final var result = UCIProtocol.parseBestMove(response);
	assertNotNull(result, "Should parse the bestmove response");
	assertNotNull(result.move(), "Should have a move string");
	assertTrue(result.move().length() >= 4, "Move string should be at least 4 characters");
    }

    @Test
    @DisplayName("Engine stores last search info from info lines")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testSearchInfoCapture() {
	try {
	    engine.initialize();
	} catch (final StockfishInitException e) {
	    assumeTrue(false, "Stockfish WASM not available");
	}

	engine.clearOutputQueue();
	engine.sendCommand("position startpos");
	engine.sendCommand("go depth 8");

	// Wait for bestmove (info lines are processed along the way)
	engine.waitForResponse(UCIProtocol.RESP_BESTMOVE, 15000);

	final var info = engine.getLastSearchInfo();
	assertNotNull(info, "Should have captured search info from info lines");
	assertTrue(info.depth() > 0, "Search info should have a positive depth");
    }

    @Test
    @DisplayName("newGame resets engine state")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testNewGame() {
	try {
	    engine.initialize();
	} catch (final StockfishInitException e) {
	    assumeTrue(false, "Stockfish WASM not available");
	}

	assertDoesNotThrow(engine::newGame);
	assertTrue(engine.isReady(), "Engine should be ready after new game");
    }

    @Test
    @DisplayName("Closing engine makes it not initialized")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testClose() {
	try {
	    engine.initialize();
	} catch (final StockfishInitException e) {
	    assumeTrue(false, "Stockfish WASM not available");
	}

	assertTrue(engine.isInitialized(), "Should be initialized before close");
	engine.close();
	assertFalse(engine.isInitialized(), "Should not be initialized after close");
    }

    @Test
    @DisplayName("Closing engine is idempotent")
    void testCloseIdempotent() {
	assertDoesNotThrow(() -> {
	    engine.close();
	    engine.close();
	    engine.close();
	});
    }

    @Test
    @DisplayName("Cannot initialize after closing")
    void testInitAfterClose() {
	engine.close();
	assertThrows(StockfishInitException.class, engine::initialize,
		"Should throw when initializing a closed engine");
    }

    @Test
    @DisplayName("sendCommand on uninitialized engine does not throw")
    void testSendCommandUninitialized() {
	// Should log a warning but not throw
	assertDoesNotThrow(() -> engine.sendCommand("uci"));
    }

    @Test
    @DisplayName("clearOutputQueue does not throw")
    void testClearOutputQueue() {
	assertDoesNotThrow(engine::clearOutputQueue);
    }

    @Test
    @DisplayName("setOutputListener accepts null")
    void testSetOutputListenerNull() {
	assertDoesNotThrow(() -> engine.setOutputListener(null));
    }

    @Test
    @DisplayName("setOutputListener accepts a listener")
    void testSetOutputListener() {
	assertDoesNotThrow(() -> engine.setOutputListener(_ -> {
	    // no-op
	}));
    }
}
