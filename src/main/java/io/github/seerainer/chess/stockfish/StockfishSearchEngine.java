package io.github.seerainer.chess.stockfish;

import java.util.concurrent.atomic.AtomicBoolean;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;

import io.github.seerainer.chess.config.ChessConfig;
import io.github.seerainer.chess.stockfish.StockfishEngine.StockfishInitException;
import io.github.seerainer.chess.stockfish.UCIProtocol.SearchInfo;

/**
 * High-level search engine that delegates move calculation to Stockfish WASM
 * running in GraalJS. Handles conversion between chesslib {@link Board}/
 * {@link Move} objects and the UCI protocol.
 *
 * <p>
 * The engine is lazily initialized on first use. If initialization fails, it
 * logs the error and returns null, allowing the caller to fall back to the
 * custom AI engine.
 * </p>
 */
public class StockfishSearchEngine implements AutoCloseable {

    private final StockfishEngine engine;
    private final AtomicBoolean initAttempted = new AtomicBoolean(false);
    private final AtomicBoolean initSucceeded = new AtomicBoolean(false);

    // Configurable search parameters
    private volatile int searchDepth = ChessConfig.Stockfish.DEPTH;
    private volatile int thinkTimeMs = ChessConfig.Stockfish.THINK_TIME_MS;
    private volatile int skillLevel = ChessConfig.Stockfish.SKILL_LEVEL;
    private volatile boolean limitStrength = ChessConfig.Stockfish.LIMIT_STRENGTH;
    private volatile int uciElo = ChessConfig.Stockfish.UCI_ELO;

    // Last search info for display
    private volatile SearchInfo lastSearchInfo;

    public StockfishSearchEngine() {
	this.engine = new StockfishEngine();
    }

    /**
     * Ensure the engine is initialized. Performs lazy initialization on first call.
     * Thread-safe.
     *
     * @return true if the engine is ready to accept commands
     */
    public boolean ensureInitialized() {
	if (initSucceeded.get()) {
	    return true;
	}

	if (initAttempted.compareAndSet(false, true)) {
	    try {
		final var startTime = System.currentTimeMillis();

		engine.initialize();
		applyConfiguration();

		final var elapsed = System.currentTimeMillis() - startTime;
		System.out.println(new StringBuilder().append("Stockfish WASM engine initialized in ").append(elapsed)
			.append("ms").toString());

		initSucceeded.set(true);
		return true;
	    } catch (final StockfishInitException e) {
		System.err.println("Failed to initialize Stockfish: " + e.getMessage());
		if (ChessConfig.Debug.ENABLE_DEBUG_LOGGING) {
		    e.printStackTrace();
		}
		return false;
	    }
	}

	// Another thread attempted init - check if it succeeded
	return initSucceeded.get();
    }

    /**
     * Find the best move for the given board position using Stockfish.
     *
     * @param board the current board position
     * @return the best move found, or null if Stockfish is unavailable or fails
     */
    public Move getBestMove(final Board board) {
	return getBestMove(board, searchDepth, thinkTimeMs);
    }

    /**
     * Find the best move for the given board position with explicit search
     * parameters.
     *
     * @param board      the current board position
     * @param depth      the search depth (0 to use movetime only)
     * @param moveTimeMs the time limit in milliseconds (0 to use depth only)
     * @return the best move found, or null if Stockfish is unavailable or fails
     */
    public Move getBestMove(final Board board, final int depth, final int moveTimeMs) {
	if (!ensureInitialized()) {
	    return null;
	}

	try {
	    // Clear any stale output from previous searches
	    engine.clearOutputQueue();
	    lastSearchInfo = null;

	    // Set the position
	    final var fen = board.getFen();
	    engine.sendCommand(UCIProtocol.buildPositionCommand(fen));

	    // Build the go command — use movetime when available for
	    // predictable timing, with depth as an additional constraint.
	    final String goCommand;
	    final long responseTimeoutMs;
	    if (depth > 0 && moveTimeMs > 0) {
		// Both constraints — Stockfish stops at whichever comes first.
		// The response timeout is based on the time bound plus overhead.
		goCommand = new StringBuilder().append("go depth ").append(depth).append(" movetime ")
			.append(moveTimeMs).toString();
		responseTimeoutMs = moveTimeMs + ChessConfig.Stockfish.MOVE_TIMEOUT_OVERHEAD_MS;
	    } else if (moveTimeMs > 0) {
		goCommand = UCIProtocol.buildGoMoveTimeCommand(moveTimeMs);
		responseTimeoutMs = moveTimeMs + ChessConfig.Stockfish.MOVE_TIMEOUT_OVERHEAD_MS;
	    } else if (depth > 0) {
		// Depth-only: no time bound, search runs until the depth is reached.
		// Use a large ceiling rather than thinkTimeMs, which is unrelated.
		goCommand = UCIProtocol.buildGoDepthCommand(depth);
		responseTimeoutMs = ChessConfig.Stockfish.DEPTH_SEARCH_TIMEOUT_MS;
	    } else {
		// Fallback: use configured think time
		goCommand = UCIProtocol.buildGoMoveTimeCommand(thinkTimeMs);
		responseTimeoutMs = thinkTimeMs + ChessConfig.Stockfish.MOVE_TIMEOUT_OVERHEAD_MS;
	    }
	    engine.sendCommand(goCommand);

	    // Wait for bestmove response with the computed timeout.
	    final var bestMoveLine = engine.waitForResponse(UCIProtocol.RESP_BESTMOVE, responseTimeoutMs);

	    // Capture last search info
	    lastSearchInfo = engine.getLastSearchInfo();

	    if (bestMoveLine == null) {
		System.err.println("Stockfish: No bestmove response received");
		return null;
	    }

	    // Parse the bestmove response
	    final var result = UCIProtocol.parseBestMove(bestMoveLine);
	    if (result == null) {
		System.err.println("Stockfish: Could not parse bestmove: " + bestMoveLine);
		return null;
	    }

	    // Convert UCI move string to chesslib Move
	    final var move = UCIProtocol.parseUCIMove(result.move(), board);
	    if (move == null) {
		System.err.println(new StringBuilder().append("Stockfish: Could not match UCI move '")
			.append(result.move()).append("' to legal moves for position: ").append(fen).toString());
		return null;
	    }

	    if (ChessConfig.Debug.ENABLE_DEBUG_LOGGING) {
		System.out.println(new StringBuilder().append("Stockfish bestmove: ").append(result.move())
			.append(lastSearchInfo != null
				? new StringBuilder().append(" (").append(lastSearchInfo).append(")").toString()
				: "")
			.toString());
	    }

	    return move;
	} catch (final Exception e) {
	    System.err.println("Stockfish search error: " + e.getMessage());
	    if (ChessConfig.Debug.ENABLE_DEBUG_LOGGING) {
		e.printStackTrace();
	    }
	    return null;
	}
    }

    /**
     * Stop the current search.
     */
    public void stopSearch() {
	if (engine.isInitialized()) {
	    engine.sendCommand(UCIProtocol.CMD_STOP);
	}
    }

    /**
     * Signal the start of a new game to the engine.
     */
    public void newGame() {
	if (engine.isInitialized()) {
	    engine.newGame();
	}
    }

    /**
     * Apply all configuration options to the engine.
     */
    private void applyConfiguration() {
	engine.setOption("Hash", String.valueOf(ChessConfig.Stockfish.HASH_SIZE_MB));
	engine.setOption("Threads", String.valueOf(ChessConfig.Stockfish.THREADS));
	engine.setOption("MultiPV", String.valueOf(ChessConfig.Stockfish.MULTI_PV));
	engine.setOption("Skill Level", String.valueOf(skillLevel));
	engine.setOption("UCI_LimitStrength", limitStrength ? "true" : "false");
	engine.setOption("UCI_Elo", String.valueOf(uciElo));

	// Wait for engine to acknowledge options
	engine.isReady();
    }

    /**
     * Update the skill level configuration.
     *
     * @param newSkillLevel the new skill level (0-20)
     */
    public void setSkillLevel(final int newSkillLevel) {
	this.skillLevel = Math.max(0, Math.min(20, newSkillLevel));
	if (!engine.isInitialized()) {
	    return;
	}
	engine.setOption("Skill Level", String.valueOf(this.skillLevel));
	engine.isReady();
    }

    /**
     * Update the search depth.
     *
     * @param depth the new search depth
     */
    public void setSearchDepth(final int depth) {
	this.searchDepth = Math.max(1, depth);
    }

    /**
     * Update the think time.
     *
     * @param timeMs the new think time in milliseconds
     */
    public void setThinkTimeMs(final int timeMs) {
	this.thinkTimeMs = Math.max(100, timeMs);
    }

    /**
     * Configure strength limiting.
     *
     * @param limit  whether to limit strength
     * @param eloVal the target ELO rating (used when limit is true)
     */
    public void setStrengthLimit(final boolean limit, final int eloVal) {
	this.limitStrength = limit;
	this.uciElo = Math.max(100, Math.min(3190, eloVal));
	if (!engine.isInitialized()) {
	    return;
	}
	engine.setOption("UCI_LimitStrength", limitStrength ? "true" : "false");
	engine.setOption("UCI_Elo", String.valueOf(this.uciElo));
	engine.isReady();
    }

    /**
     * Get the last search info from the most recent search.
     *
     * @return the last SearchInfo, or null if no search has been performed
     */
    public SearchInfo getLastSearchInfo() {
	return lastSearchInfo;
    }

    /**
     * Check if the engine is initialized and available.
     *
     * @return true if Stockfish is ready
     */
    public boolean isAvailable() {
	return initSucceeded.get() && engine.isInitialized();
    }

    /**
     * Get formatted statistics from the last search.
     *
     * @return a human-readable statistics string
     */
    public String getStatistics() {
	final var info = lastSearchInfo;
	if (info == null) {
	    return "Stockfish: No search performed yet";
	}
	return "Stockfish: " + info.toString();
    }

    @Override
    public void close() {
	engine.close();
	initSucceeded.set(false);
    }
}
