package io.github.seerainer.chess.stockfish;

import java.util.Locale;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;

/**
 * UCI (Universal Chess Interface) protocol command building and response
 * parsing. Utility class for converting between Java chess objects and UCI
 * protocol strings.
 */
public final class UCIProtocol {

    // UCI command constants
    public static final String CMD_UCI = "uci";
    public static final String CMD_ISREADY = "isready";
    public static final String CMD_UCINEWGAME = "ucinewgame";
    public static final String CMD_STOP = "stop";
    public static final String CMD_QUIT = "quit";

    // UCI response constants
    public static final String RESP_UCIOK = "uciok";
    public static final String RESP_READYOK = "readyok";
    public static final String RESP_BESTMOVE = "bestmove";
    public static final String RESP_INFO = "info";

    private UCIProtocol() {
	throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Build a "position fen" command from a board position.
     *
     * @param fen the FEN string representing the board position
     * @return the UCI position command
     */
    public static String buildPositionCommand(final String fen) {
	return fen == null || fen.isBlank() ? "position startpos" : "position fen " + fen;
    }

    /**
     * Build a "go" command with depth limit.
     *
     * @param depth the search depth
     * @return the UCI go command
     */
    public static String buildGoDepthCommand(final int depth) {
	return "go depth " + Math.max(1, depth);
    }

    /**
     * Build a "go" command with movetime limit.
     *
     * @param moveTimeMs the time limit in milliseconds
     * @return the UCI go command
     */
    public static String buildGoMoveTimeCommand(final int moveTimeMs) {
	return "go movetime " + Math.max(100, moveTimeMs);
    }

    /**
     * Build a "setoption" command.
     *
     * @param name  the option name
     * @param value the option value
     * @return the UCI setoption command
     */
    public static String buildSetOptionCommand(final String name, final String value) {
	return new StringBuilder().append("setoption name ").append(name).append(" value ").append(value).toString();
    }

    /**
     * Parse the best move from a "bestmove" UCI response line.
     *
     * @param line the UCI response line (e.g., "bestmove e2e4 ponder d7d5")
     * @return the parsed result, or null if the line is not a bestmove response
     */
    public static BestMoveResult parseBestMove(final String line) {
	if (line == null || !line.startsWith(RESP_BESTMOVE)) {
	    return null;
	}

	final var parts = line.split("\\s+");
	if (parts.length < 2) {
	    return null;
	}

	final var moveStr = parts[1];
	// "(none)" is returned when there are no legal moves
	if ("(none)".equals(moveStr)) {
	    return null;
	}

	String ponderMoveStr = null;
	if (parts.length >= 4 && "ponder".equals(parts[2])) {
	    ponderMoveStr = parts[3];
	}

	return new BestMoveResult(moveStr, ponderMoveStr);
    }

    /**
     * Parse search info from an "info" UCI response line.
     *
     * @param line the UCI response line
     * @return the parsed info, or null if the line is not an info response
     */
    public static SearchInfo parseInfo(final String line) {
	if (line == null || !line.startsWith(RESP_INFO)) {
	    return null;
	}

	final var parts = line.split("\\s+");
	var depth = 0;
	var score = 0;
	var isMateScore = false;
	var nodes = 0L;
	var nps = 0L;
	var time = 0L;
	String pvLine = null;

	for (var i = 1; i < parts.length; i++) {
	    switch (parts[i]) {
	    case "depth" -> {
		if (i + 1 < parts.length) {
		    try {
			depth = Integer.parseInt(parts[++i]);
		    } catch (final NumberFormatException e) {
			// Skip invalid depth
		    }
		}
	    }
	    case "score" -> {
		if (i + 1 < parts.length) {
		    final var scoreType = parts[++i];
		    if ("cp".equals(scoreType) && i + 1 < parts.length) {
			try {
			    score = Integer.parseInt(parts[++i]);
			} catch (final NumberFormatException e) {
			    // Skip invalid score
			}
		    } else if ("mate".equals(scoreType) && i + 1 < parts.length) {
			try {
			    score = Integer.parseInt(parts[++i]);
			    isMateScore = true;
			} catch (final NumberFormatException e) {
			    // Skip invalid mate score
			}
		    }
		}
	    }
	    case "nodes" -> {
		if (i + 1 < parts.length) {
		    try {
			nodes = Long.parseLong(parts[++i]);
		    } catch (final NumberFormatException e) {
			// Skip invalid nodes
		    }
		}
	    }
	    case "nps" -> {
		if (i + 1 < parts.length) {
		    try {
			nps = Long.parseLong(parts[++i]);
		    } catch (final NumberFormatException e) {
			// Skip invalid nps
		    }
		}
	    }
	    case "time" -> {
		if (i + 1 < parts.length) {
		    try {
			time = Long.parseLong(parts[++i]);
		    } catch (final NumberFormatException e) {
			// Skip invalid time
		    }
		}
	    }
	    case "pv" -> {
		// Everything after "pv" is the principal variation
		if (i + 1 < parts.length) {
		    final var sb = new StringBuilder();
		    for (var j = i + 1; j < parts.length; j++) {
			if (j > i + 1) {
			    sb.append(' ');
			}
			sb.append(parts[j]);
		    }
		    pvLine = sb.toString();
		    i = parts.length; // Stop outer loop
		}
	    }
	    default -> {
		// Ignore other tokens
	    }
	    }
	}

	return new SearchInfo(depth, score, isMateScore, nodes, nps, time, pvLine);
    }

    /**
     * Convert a UCI move string (e.g., "e2e4", "e7e8q") to a chesslib Move by
     * matching against legal moves.
     *
     * @param uciMove the UCI move string
     * @param board   the current board position
     * @return the matching Move, or null if no match found
     */
    public static Move parseUCIMove(final String uciMove, final Board board) {
	if (uciMove == null || uciMove.length() < 4 || board == null) {
	    return null;
	}

	try {
	    final var fromStr = uciMove.substring(0, 2).toUpperCase();
	    final var toStr = uciMove.substring(2, 4).toUpperCase();
	    final var from = Square.fromValue(fromStr);
	    final var to = Square.fromValue(toStr);

	    // Check for promotion suffix (e.g., "e7e8q")
	    var promotion = Piece.NONE;
	    if (uciMove.length() >= 5) {
		promotion = parsePromotionPiece(uciMove.charAt(4), board);
	    }

	    // Match against legal moves
	    final var legalMoves = board.legalMoves();
	    for (final var move : legalMoves) {
		if (move.getFrom() == from && move.getTo() == to) {
		    // If promotion specified, match promotion piece
		    if (promotion != Piece.NONE) {
			if (move.getPromotion() == promotion) {
			    return move;
			}
		    } else if (move.getPromotion() == Piece.NONE) {
			return move;
		    }
		}
	    }

	    // Fallback: if no exact match for promotion, return first matching move
	    for (final var move : legalMoves) {
		if (move.getFrom() == from && move.getTo() == to) {
		    return move;
		}
	    }
	} catch (final Exception e) {
	    System.err.println(new StringBuilder().append("Error parsing UCI move '").append(uciMove).append("': ")
		    .append(e.getMessage()).toString());
	}

	return null;
    }

    /**
     * Parse a promotion character to a Piece based on the side to move.
     */
    private static Piece parsePromotionPiece(final char promoChar, final Board board) {
	final var isWhite = board.getSideToMove() == com.github.bhlangonijr.chesslib.Side.WHITE;
	return switch (Character.toLowerCase(promoChar)) {
	case 'q' -> isWhite ? Piece.WHITE_QUEEN : Piece.BLACK_QUEEN;
	case 'r' -> isWhite ? Piece.WHITE_ROOK : Piece.BLACK_ROOK;
	case 'b' -> isWhite ? Piece.WHITE_BISHOP : Piece.BLACK_BISHOP;
	case 'n' -> isWhite ? Piece.WHITE_KNIGHT : Piece.BLACK_KNIGHT;
	default -> Piece.NONE;
	};
    }

    /**
     * Result of parsing a "bestmove" response.
     *
     * @param move       the best move in UCI notation (e.g., "e2e4")
     * @param ponderMove the ponder move in UCI notation, or null
     */
    public record BestMoveResult(String move, String ponderMove) {
    }

    /**
     * Parsed search info from an "info" response line.
     *
     * @param depth       the search depth
     * @param score       the evaluation score (centipawns or mate-in-N)
     * @param isMateScore true if the score represents mate-in-N moves
     * @param nodes       the number of nodes searched
     * @param nps         nodes per second
     * @param timeMs      search time in milliseconds
     * @param pvLine      the principal variation line
     */
    public record SearchInfo(int depth, int score, boolean isMateScore, long nodes, long nps, long timeMs,
	    String pvLine) {

	/**
	 * Format the score for display.
	 *
	 * @return human-readable score string
	 */
	public String formatScore() {
	    if (isMateScore) {
		return "Mate in " + Math.abs(score);
	    }
	    return String.format(Locale.ROOT, "%.2f", score / 100.0);
	}

	@Override
	public String toString() {
	    final var sb = new StringBuilder();
	    sb.append("depth=").append(depth);
	    sb.append(" score=").append(formatScore());
	    if (nodes > 0) {
		sb.append(" nodes=").append(nodes);
	    }
	    if (nps > 0) {
		sb.append(" nps=").append(nps);
	    }
	    if (timeMs > 0) {
		sb.append(" time=").append(timeMs).append("ms");
	    }
	    if (pvLine != null) {
		sb.append(" pv=").append(pvLine);
	    }
	    return sb.toString();
	}
    }
}
