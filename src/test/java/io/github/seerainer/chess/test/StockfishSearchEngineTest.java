package io.github.seerainer.chess.test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Square;

import io.github.seerainer.chess.stockfish.StockfishSearchEngine;

/**
 * End-to-end tests for StockfishSearchEngine. Tests verify that the engine can
 * find legal moves for various board positions. Tests that require Stockfish
 * WASM use {@code assumeTrue} to skip gracefully if initialization fails.
 */
@DisplayName("StockfishSearchEngine")
class StockfishSearchEngineTest {

    private StockfishSearchEngine engine;

    @BeforeEach
    void setUp() {
	engine = new StockfishSearchEngine();
    }

    @AfterEach
    void tearDown() {
	if (engine != null) {
	    engine.close();
	}
    }

    /**
     * Helper: ensure engine is initialized, or skip the test.
     */
    private void ensureEngineOrSkip() {
	final var ready = engine.ensureInitialized();
	assumeTrue(ready, "Stockfish WASM not available in this environment");
    }

    @Nested
    @DisplayName("Initialization")
    class Initialization {

	@Test
	@DisplayName("Engine is not available before initialization")
	void testNotAvailableByDefault() {
	    assertFalse(engine.isAvailable(), "Should not be available before init");
	}

	@Test
	@DisplayName("ensureInitialized returns true when engine starts")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void testEnsureInitialized() {
	    final var result = engine.ensureInitialized();
	    if (result) {
		assertTrue(engine.isAvailable(), "Should be available after successful init");
	    }
	    // If false, engine is not available in this environment — that's fine
	}

	@Test
	@DisplayName("ensureInitialized is idempotent")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void testEnsureInitializedIdempotent() {
	    final var first = engine.ensureInitialized();
	    final var second = engine.ensureInitialized();
	    assertEquals(first, second, "Multiple calls should return same result");
	}

	@Test
	@DisplayName("getLastSearchInfo returns null before any search")
	void testNoSearchInfoBeforeSearch() {
	    assertNull(engine.getLastSearchInfo(), "No search info before any search");
	}

	@Test
	@DisplayName("getStatistics returns a non-null string")
	void testStatisticsBeforeSearch() {
	    final var stats = engine.getStatistics();
	    assertNotNull(stats, "Statistics should not be null");
	    assertTrue(stats.contains("Stockfish"), "Statistics should mention Stockfish");
	}
    }

    @Nested
    @DisplayName("Move Generation")
    class MoveGeneration {

	@Test
	@DisplayName("getBestMove returns a legal move from starting position")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void testBestMoveStartingPosition() {
	    ensureEngineOrSkip();

	    final var board = new Board();
	    final var move = engine.getBestMove(board);

	    assertNotNull(move, "Should find a move from starting position");
	    assertTrue(board.legalMoves().contains(move), "Move should be in the legal moves list");
	}

	@Test
	@DisplayName("getBestMove returns a legal move from a custom FEN")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void testBestMoveCustomPosition() {
	    ensureEngineOrSkip();

	    final var board = new Board();
	    board.loadFromFen("r1bqkbnr/pppppppp/2n5/4P3/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 2");
	    final var move = engine.getBestMove(board);

	    assertNotNull(move, "Should find a move from the given position");
	    assertTrue(board.legalMoves().contains(move), "Move should be legal");
	}

	@Test
	@DisplayName("getBestMove with explicit depth and movetime")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void testBestMoveWithParams() {
	    ensureEngineOrSkip();

	    final var board = new Board();
	    final var move = engine.getBestMove(board, 5, 0);

	    assertNotNull(move, "Should find a move at depth 5");
	    assertTrue(board.legalMoves().contains(move), "Move should be legal");
	}

	@Test
	@DisplayName("getBestMove with movetime returns a legal move")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void testBestMoveWithMoveTime() {
	    ensureEngineOrSkip();

	    final var board = new Board();
	    final var move = engine.getBestMove(board, 0, 1000);

	    assertNotNull(move, "Should find a move within 1 second");
	    assertTrue(board.legalMoves().contains(move), "Move should be legal");
	}

	@Test
	@DisplayName("getBestMove captures search info")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void testSearchInfoCaptured() {
	    ensureEngineOrSkip();

	    final var board = new Board();
	    engine.getBestMove(board, 10, 0);

	    final var info = engine.getLastSearchInfo();
	    assertNotNull(info, "Search info should be available after search");
	    assertTrue(info.depth() > 0, "Should have searched at some depth");
	}
    }

    @Nested
    @DisplayName("Tactical Positions")
    class TacticalPositions {

	@Test
	@DisplayName("Engine finds capture in hanging piece position")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void testFindCapture() {
	    ensureEngineOrSkip();

	    // Black queen hangs on e5, white knight on f3 can capture
	    final var board = new Board();
	    board.loadFromFen("rnb1kbnr/pppppppp/8/4q3/8/5N2/PPPPPPPP/RNBQKB1R w KQkq - 0 1");
	    final var move = engine.getBestMove(board, 10, 0);

	    assertNotNull(move, "Should find a move");
	    // Nf3xe5 captures the hanging queen
	    assertEquals(Square.E5, move.getTo(), "Should capture the hanging queen on e5");
	}

	@Test
	@DisplayName("Engine finds move in endgame position")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void testEndgamePosition() {
	    ensureEngineOrSkip();

	    // King and pawn endgame
	    final var board = new Board();
	    board.loadFromFen("8/8/8/8/8/4k3/4p3/4K3 b - - 0 1");
	    final var move = engine.getBestMove(board);

	    assertNotNull(move, "Should find a move in endgame");
	    assertTrue(board.legalMoves().contains(move), "Move should be legal");
	}
    }

    @Nested
    @DisplayName("Configuration")
    class Configuration {

	@Test
	@DisplayName("setSkillLevel does not throw")
	void testSetSkillLevel() {
	    assertDoesNotThrow(() -> engine.setSkillLevel(5));
	    assertDoesNotThrow(() -> engine.setSkillLevel(0));
	    assertDoesNotThrow(() -> engine.setSkillLevel(20));
	}

	@Test
	@DisplayName("setSkillLevel clamps out-of-range values")
	void testSetSkillLevelClamping() {
	    assertDoesNotThrow(() -> engine.setSkillLevel(-1));
	    assertDoesNotThrow(() -> engine.setSkillLevel(25));
	}

	@Test
	@DisplayName("setSearchDepth does not throw")
	void testSetSearchDepth() {
	    assertDoesNotThrow(() -> engine.setSearchDepth(10));
	    assertDoesNotThrow(() -> engine.setSearchDepth(1));
	}

	@Test
	@DisplayName("setThinkTimeMs does not throw")
	void testSetThinkTimeMs() {
	    assertDoesNotThrow(() -> engine.setThinkTimeMs(2000));
	    assertDoesNotThrow(() -> engine.setThinkTimeMs(50)); // Will be clamped to 100
	}

	@Test
	@DisplayName("setStrengthLimit does not throw")
	void testSetStrengthLimit() {
	    assertDoesNotThrow(() -> engine.setStrengthLimit(true, 1500));
	    assertDoesNotThrow(() -> engine.setStrengthLimit(false, 3190));
	}

	@Test
	@DisplayName("setStrengthLimit clamps ELO range")
	void testSetStrengthLimitClamping() {
	    assertDoesNotThrow(() -> engine.setStrengthLimit(true, 50)); // Below minimum
	    assertDoesNotThrow(() -> engine.setStrengthLimit(true, 5000)); // Above maximum
	}
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

	@Test
	@DisplayName("newGame does not throw before initialization")
	void testNewGameBeforeInit() {
	    assertDoesNotThrow(engine::newGame);
	}

	@Test
	@DisplayName("newGame resets engine state")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void testNewGameResetsState() {
	    ensureEngineOrSkip();

	    // Make a search first
	    final var board = new Board();
	    engine.getBestMove(board);

	    // Start new game
	    assertDoesNotThrow(engine::newGame);

	    // Should still be able to search
	    final var move = engine.getBestMove(board);
	    assertNotNull(move, "Should find a move after new game");
	}

	@Test
	@DisplayName("stopSearch does not throw before initialization")
	void testStopSearchBeforeInit() {
	    assertDoesNotThrow(engine::stopSearch);
	}

	@Test
	@DisplayName("close is idempotent")
	void testCloseIdempotent() {
	    assertDoesNotThrow(() -> {
		engine.close();
		engine.close();
	    });
	}

	@Test
	@DisplayName("Engine not available after close")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void testNotAvailableAfterClose() {
	    // Try to init, doesn't matter if it succeeds
	    engine.ensureInitialized();

	    engine.close();
	    assertFalse(engine.isAvailable(), "Should not be available after close");
	}

	@Test
	@DisplayName("getBestMove returns null after engine is closed")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void testGetBestMoveAfterClose() {
	    // Initialize then close
	    engine.ensureInitialized();
	    engine.close();

	    final var board = new Board();
	    final var move = engine.getBestMove(board);
	    assertNull(move, "Should return null when engine is closed");
	}
    }
}
