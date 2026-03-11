package io.github.seerainer.chess;

import java.util.concurrent.CompletableFuture;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;

import io.github.seerainer.chess.config.ChessConfig;
import io.github.seerainer.chess.stockfish.StockfishSearchEngine;
import io.github.seerainer.chess.utils.ResourceManager;

/**
 * Chess AI facade that delegates all move calculation to the Stockfish WASM
 * engine running inside a GraalVM JS context.
 */
public class ChessAI {

    private final StockfishSearchEngine stockfishSearchEngine;
    private final ResourceManager resourceManager;

    /**
     * Constructs a new ChessAI and eagerly initialises the Stockfish engine.
     */
    public ChessAI() {
	this.stockfishSearchEngine = new StockfishSearchEngine();
	this.resourceManager = new ResourceManager();

	// Eagerly initialise so the first move is not delayed.
	if (!stockfishSearchEngine.ensureInitialized()) {
	    System.err.println("Stockfish engine could not be initialised at startup.");
	}
    }

    /**
     * Returns the best move for the current board position.
     *
     * @param board the current position
     * @return the best move, or {@code null} if no legal move is available
     */
    public Move getBestMove(final Board board) {
	if (ChessConfig.Debug.ENABLE_DEBUG_LOGGING) {
	    System.out.println("Starting Stockfish search for position: " + board.getFen());
	}

	final var startTime = System.currentTimeMillis();
	final var move = stockfishSearchEngine.getBestMove(board);
	final var elapsed = System.currentTimeMillis() - startTime;

	if (ChessConfig.Debug.ENABLE_PERFORMANCE_MONITORING) {
	    final var info = stockfishSearchEngine.getLastSearchInfo();
	    System.out.println(new StringBuilder().append("Stockfish search completed in ").append(elapsed).append("ms")
		    .append(info != null ? " - " + info : "").toString());
	}

	if (move == null) {
	    System.err.println("Stockfish returned no move for position: " + board.getFen());
	}

	return move;
    }

    /**
     * Returns the best move asynchronously. The board is copied before submitting
     * the task to avoid concurrency issues with the caller's board object.
     *
     * @param board the current position
     * @return a future that resolves to the best move, or {@code null} on failure
     */
    public CompletableFuture<Move> getBestMoveAsync(final Board board) {
	final var boardCopy = new Board();
	boardCopy.loadFromFen(board.getFen());

	return CompletableFuture.supplyAsync(() -> {
	    try {
		return getBestMove(boardCopy);
	    } catch (final Exception e) {
		System.err.println("Error in AI calculation: " + e.getMessage());
		e.printStackTrace();
		return null;
	    }
	}, resourceManager.getExecutorService()).exceptionally(throwable -> {
	    System.err.println("AI search failed: " + throwable.getMessage());
	    return null;
	});
    }

    /**
     * Stops any ongoing Stockfish search.
     */
    public void cancelSearch() {
	stockfishSearchEngine.stopSearch();
    }

    /**
     * Releases all resources held by this instance.
     */
    public void cleanup() {
	try {
	    stockfishSearchEngine.close();
	} catch (final Exception e) {
	    System.err.println("Error closing Stockfish engine: " + e.getMessage());
	}

	try {
	    resourceManager.close();
	} catch (final Exception e) {
	    System.err.println("Error closing resource manager: " + e.getMessage());
	}
    }

    /**
     * Signals the start of a new game to the Stockfish engine.
     */
    public void stockfishNewGame() {
	if (stockfishSearchEngine.isAvailable()) {
	    stockfishSearchEngine.newGame();
	}
    }

    /**
     * Configures the Stockfish skill level.
     *
     * @param level the skill level (0–20)
     */
    public void setStockfishSkillLevel(final int level) {
	stockfishSearchEngine.setSkillLevel(level);
    }

    /**
     * Configures the Stockfish think time.
     *
     * @param timeMs think time in milliseconds
     */
    public void setStockfishThinkTime(final int timeMs) {
	stockfishSearchEngine.setThinkTimeMs(timeMs);
    }

    /**
     * Configures Stockfish strength limiting (UCI_LimitStrength / UCI_Elo).
     *
     * @param limit whether to enable strength limiting
     * @param elo   target ELO rating when limiting is enabled
     */
    public void setStockfishStrengthLimit(final boolean limit, final int elo) {
	stockfishSearchEngine.setStrengthLimit(limit, elo);
    }

    /**
     * Configures the Stockfish search depth.
     *
     * @param depth the search depth in half-moves (plies)
     */
    public void setSearchDepth(final int depth) {
	stockfishSearchEngine.setSearchDepth(depth);
    }
}
