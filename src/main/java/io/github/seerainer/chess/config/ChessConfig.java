package io.github.seerainer.chess.config;

/**
 * Centralized configuration for the chess application.
 */
public class ChessConfig {

    // Debug and Logging
    public static final class Debug {
	public static final boolean ENABLE_DEBUG_LOGGING = false;
	public static final boolean ENABLE_PERFORMANCE_MONITORING = true;
    }

    // Memory Management
    public static final class Memory {
	public static final int POSITION_HISTORY_LIMIT = 500;
	public static final int CLEANUP_INTERVAL_MS = 60000;
	public static final long MAX_MEMORY_USAGE_MB = 1024;
	public static final long MEMORY_CHECK_INTERVAL_MS = 15000;
	public static final float MEMORY_CLEANUP_THRESHOLD = 0.85f;
    }

    // Game Rules
    public static final class Rules {
	public static final int THREEFOLD_REPETITION_LIMIT = 3;
    }

    // Stockfish WASM Engine Configuration
    public static final class Stockfish {
	/** Default search depth for Stockfish */
	public static final int DEPTH = 20;
	/** Default think time in milliseconds */
	public static final int THINK_TIME_MS = 3000;
	/** Hash table size in MB for Stockfish */
	public static final int HASH_SIZE_MB = 128;
	/** Number of threads (WASM is single-threaded) */
	public static final int THREADS = 1;
	/** Skill level (0-20, 20 = maximum strength) */
	public static final int SKILL_LEVEL = 10;
	/** Target ELO rating when strength is limited */
	public static final int UCI_ELO = 1500;
	/** Whether to limit Stockfish playing strength */
	public static final boolean LIMIT_STRENGTH = true;
	/** Number of principal variations to calculate */
	public static final int MULTI_PV = 1;
	/** Timeout for engine initialization in milliseconds */
	public static final long INIT_TIMEOUT_MS = 30000;
	/**
	 * Timeout used when dispatching non-search commands (position, setoption,
	 * isready, etc.) to the engine thread. These complete in milliseconds.
	 */
	public static final long MOVE_TIMEOUT_MS = 10000;
	/**
	 * Extra headroom added on top of the search movetime budget when waiting for
	 * the {@code bestmove} response. Absorbs WASM scheduling jitter, engine startup
	 * latency, and the time between the Java {@code go} dispatch and the engine
	 * actually beginning its search.
	 */
	public static final long MOVE_TIMEOUT_OVERHEAD_MS = 5000;
	/**
	 * Maximum time to wait for a {@code bestmove} response when the search is
	 * depth-only (no {@code movetime} constraint). A pure depth search has no
	 * inherent time bound; very deep searches on slow hardware can take minutes.
	 * This ceiling prevents an indefinite wait while still allowing deep analysis.
	 */
	public static final long DEPTH_SEARCH_TIMEOUT_MS = 300000; // 5 minutes
    }

    // UI Configuration
    public static final class UI {
	public static final int MAX_MOVES_WITHOUT_PROGRESS = 100;
	public static final int MAX_COMPUTER_MOVES = 300;
	public static final int AI_MOVE_DELAY_MS = 300;
	public static final int THREAD_SHUTDOWN_TIMEOUT_MS = 5000;
	public static final String WINDOW_TITLE = "Stockfish.JSWT";
	public static final int BOARD_SIZE = 640;
    }

    // 3D Rendering Configuration
    public static final class Rendering3D {
	/** Width of the GLFW 3D window in pixels. */
	public static final int WINDOW_WIDTH = 800;
	/** Height of the GLFW 3D window in pixels. */
	public static final int WINDOW_HEIGHT = 800;
	/** Title shown on the 3D window. */
	public static final String WINDOW_TITLE = "Stockfish.JSWT — 3D Board";
	/** Vertical field-of-view for the perspective camera, in degrees. */
	public static final float FOV_DEGREES = 45.0f;
	/** Near clip plane distance. */
	public static final float NEAR_PLANE = 0.1f;
	/** Far clip plane distance. */
	public static final float FAR_PLANE = 100.0f;
	/** Camera eye position — height above the board (Y axis). */
	public static final float CAMERA_Y = 8.0f;
	/** Camera eye position — distance back along Z axis. */
	public static final float CAMERA_Z = 10.0f;
	/** Piece glyph font size (points) used when baking the glyph texture atlas. */
	public static final int GLYPH_FONT_SIZE = 64;
	/** Size in pixels of each glyph cell in the texture atlas. */
	public static final int GLYPH_CELL_SIZE = 72;
    }
}
