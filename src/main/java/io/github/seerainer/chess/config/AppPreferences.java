package io.github.seerainer.chess.config;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Persists and retrieves user-configurable application settings using the Java
 * {@link Preferences} API. All values are stored under the node path derived
 * from the package name of {@link ChessConfig}.
 *
 * <p>
 * Keys and default values mirror the constants in {@link ChessConfig}. The
 * class is not instantiable; use the static {@code load*} / {@code save*}
 * methods directly.
 * </p>
 */
public final class AppPreferences {

    // -----------------------------------------------------------------------
    // Preference keys
    // -----------------------------------------------------------------------

    // Stockfish
    public static final String KEY_SKILL_LEVEL = "stockfish.skillLevel";
    public static final String KEY_THINK_TIME_MS = "stockfish.thinkTimeMs";
    public static final String KEY_LIMIT_STRENGTH = "stockfish.limitStrength";
    public static final String KEY_UCI_ELO = "stockfish.uciElo";
    public static final String KEY_SEARCH_DEPTH = "stockfish.searchDepth";
    public static final String KEY_AI_MOVE_DELAY_MS = "ui.aiMoveDelayMs";

    // Debug / Performance
    public static final String KEY_ENABLE_DEBUG_LOGGING = "debug.enableDebugLogging";
    public static final String KEY_ENABLE_PERFORMANCE_MONITORING = "debug.enablePerformanceMonitoring";

    // -----------------------------------------------------------------------
    // Preference node
    // -----------------------------------------------------------------------

    private static final Preferences PREFS = Preferences.userNodeForPackage(ChessConfig.class);

    private AppPreferences() {
	// non-instantiable
    }

    // -----------------------------------------------------------------------
    // Stockfish settings
    // -----------------------------------------------------------------------

    /** Returns the persisted skill level, or the compiled-in default. */
    public static int loadSkillLevel() {
	return PREFS.getInt(KEY_SKILL_LEVEL, ChessConfig.Stockfish.SKILL_LEVEL);
    }

    /** Persists the skill level. */
    public static void saveSkillLevel(final int level) {
	PREFS.putInt(KEY_SKILL_LEVEL, Math.max(0, Math.min(20, level)));
	flush();
    }

    /** Returns the persisted think time in ms, or the compiled-in default. */
    public static int loadThinkTimeMs() {
	return PREFS.getInt(KEY_THINK_TIME_MS, ChessConfig.Stockfish.THINK_TIME_MS);
    }

    /** Persists the think time in ms. */
    public static void saveThinkTimeMs(final int timeMs) {
	PREFS.putInt(KEY_THINK_TIME_MS, Math.max(100, timeMs));
	flush();
    }

    /** Returns whether strength limiting is enabled. */
    public static boolean loadLimitStrength() {
	return PREFS.getBoolean(KEY_LIMIT_STRENGTH, ChessConfig.Stockfish.LIMIT_STRENGTH);
    }

    /** Persists the strength-limiting flag. */
    public static void saveLimitStrength(final boolean limit) {
	PREFS.putBoolean(KEY_LIMIT_STRENGTH, limit);
	flush();
    }

    /** Returns the persisted UCI ELO target, or the compiled-in default. */
    public static int loadUciElo() {
	return PREFS.getInt(KEY_UCI_ELO, ChessConfig.Stockfish.UCI_ELO);
    }

    /** Persists the UCI ELO target. */
    public static void saveUciElo(final int elo) {
	PREFS.putInt(KEY_UCI_ELO, Math.max(100, Math.min(3190, elo)));
	flush();
    }

    /** Returns the persisted search depth, or the compiled-in default. */
    public static int loadSearchDepth() {
	return PREFS.getInt(KEY_SEARCH_DEPTH, ChessConfig.Stockfish.DEPTH);
    }

    /** Persists the search depth. */
    public static void saveSearchDepth(final int depth) {
	PREFS.putInt(KEY_SEARCH_DEPTH, Math.max(1, depth));
	flush();
    }

    // -----------------------------------------------------------------------
    // UI settings
    // -----------------------------------------------------------------------

    /** Returns the persisted AI move delay in ms, or the compiled-in default. */
    public static int loadAiMoveDelayMs() {
	return PREFS.getInt(KEY_AI_MOVE_DELAY_MS, ChessConfig.UI.AI_MOVE_DELAY_MS);
    }

    /** Persists the AI move delay in ms. */
    public static void saveAiMoveDelayMs(final int delayMs) {
	PREFS.putInt(KEY_AI_MOVE_DELAY_MS, Math.max(0, delayMs));
	flush();
    }

    // -----------------------------------------------------------------------
    // Debug settings
    // -----------------------------------------------------------------------

    /** Returns whether debug logging is enabled. */
    public static boolean loadEnableDebugLogging() {
	return PREFS.getBoolean(KEY_ENABLE_DEBUG_LOGGING, ChessConfig.Debug.ENABLE_DEBUG_LOGGING);
    }

    /** Persists the debug-logging flag. */
    public static void saveEnableDebugLogging(final boolean enabled) {
	PREFS.putBoolean(KEY_ENABLE_DEBUG_LOGGING, enabled);
	flush();
    }

    /** Returns whether performance monitoring is enabled. */
    public static boolean loadEnablePerformanceMonitoring() {
	return PREFS.getBoolean(KEY_ENABLE_PERFORMANCE_MONITORING, ChessConfig.Debug.ENABLE_PERFORMANCE_MONITORING);
    }

    /** Persists the performance-monitoring flag. */
    public static void saveEnablePerformanceMonitoring(final boolean enabled) {
	PREFS.putBoolean(KEY_ENABLE_PERFORMANCE_MONITORING, enabled);
	flush();
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private static void flush() {
	try {
	    PREFS.flush();
	} catch (final BackingStoreException e) {
	    System.err.println("Could not flush preferences: " + e.getMessage());
	}
    }
}
