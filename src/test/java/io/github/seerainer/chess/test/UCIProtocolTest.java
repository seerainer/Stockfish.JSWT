package io.github.seerainer.chess.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Square;

import io.github.seerainer.chess.stockfish.UCIProtocol;

/**
 * Unit tests for the UCI protocol command building and response parsing.
 */
@DisplayName("UCIProtocol")
class UCIProtocolTest {

    @Nested
    @DisplayName("Command Building")
    class CommandBuilding {

	@Test
	@DisplayName("buildPositionCommand with valid FEN")
	void testBuildPositionCommandWithFen() {
	    final var fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1";
	    final var command = UCIProtocol.buildPositionCommand(fen);
	    assertEquals("position fen " + fen, command);
	}

	@Test
	@DisplayName("buildPositionCommand with null FEN returns startpos")
	void testBuildPositionCommandWithNull() {
	    final var command = UCIProtocol.buildPositionCommand(null);
	    assertEquals("position startpos", command);
	}

	@Test
	@DisplayName("buildPositionCommand with blank FEN returns startpos")
	void testBuildPositionCommandWithBlank() {
	    final var command = UCIProtocol.buildPositionCommand("  ");
	    assertEquals("position startpos", command);
	}

	@Test
	@DisplayName("buildGoDepthCommand with valid depth")
	void testBuildGoDepthCommand() {
	    final var command = UCIProtocol.buildGoDepthCommand(10);
	    assertEquals("go depth 10", command);
	}

	@Test
	@DisplayName("buildGoDepthCommand clamps minimum depth to 1")
	void testBuildGoDepthCommandMinimum() {
	    final var command = UCIProtocol.buildGoDepthCommand(0);
	    assertEquals("go depth 1", command);
	}

	@Test
	@DisplayName("buildGoDepthCommand clamps negative depth to 1")
	void testBuildGoDepthCommandNegative() {
	    final var command = UCIProtocol.buildGoDepthCommand(-5);
	    assertEquals("go depth 1", command);
	}

	@Test
	@DisplayName("buildGoMoveTimeCommand with valid time")
	void testBuildGoMoveTimeCommand() {
	    final var command = UCIProtocol.buildGoMoveTimeCommand(3000);
	    assertEquals("go movetime 3000", command);
	}

	@Test
	@DisplayName("buildGoMoveTimeCommand clamps minimum to 100ms")
	void testBuildGoMoveTimeCommandMinimum() {
	    final var command = UCIProtocol.buildGoMoveTimeCommand(50);
	    assertEquals("go movetime 100", command);
	}

	@Test
	@DisplayName("buildSetOptionCommand formats correctly")
	void testBuildSetOptionCommand() {
	    final var command = UCIProtocol.buildSetOptionCommand("Skill Level", "10");
	    assertEquals("setoption name Skill Level value 10", command);
	}

	@Test
	@DisplayName("buildSetOptionCommand with UCI_LimitStrength")
	void testBuildSetOptionCommandBoolean() {
	    final var command = UCIProtocol.buildSetOptionCommand("UCI_LimitStrength", "true");
	    assertEquals("setoption name UCI_LimitStrength value true", command);
	}
    }

    @Nested
    @DisplayName("BestMove Parsing")
    class BestMoveParsing {

	@Test
	@DisplayName("parseBestMove with simple move")
	void testParseBestMoveSimple() {
	    final var result = UCIProtocol.parseBestMove("bestmove e2e4");
	    assertNotNull(result);
	    assertEquals("e2e4", result.move());
	    assertNull(result.ponderMove());
	}

	@Test
	@DisplayName("parseBestMove with ponder move")
	void testParseBestMoveWithPonder() {
	    final var result = UCIProtocol.parseBestMove("bestmove e2e4 ponder d7d5");
	    assertNotNull(result);
	    assertEquals("e2e4", result.move());
	    assertEquals("d7d5", result.ponderMove());
	}

	@Test
	@DisplayName("parseBestMove with promotion")
	void testParseBestMoveWithPromotion() {
	    final var result = UCIProtocol.parseBestMove("bestmove e7e8q");
	    assertNotNull(result);
	    assertEquals("e7e8q", result.move());
	}

	@Test
	@DisplayName("parseBestMove returns null for (none)")
	void testParseBestMoveNone() {
	    final var result = UCIProtocol.parseBestMove("bestmove (none)");
	    assertNull(result);
	}

	@Test
	@DisplayName("parseBestMove returns null for null input")
	void testParseBestMoveNull() {
	    final var result = UCIProtocol.parseBestMove(null);
	    assertNull(result);
	}

	@Test
	@DisplayName("parseBestMove returns null for non-bestmove line")
	void testParseBestMoveWrongPrefix() {
	    final var result = UCIProtocol.parseBestMove("info depth 10 score cp 50");
	    assertNull(result);
	}

	@Test
	@DisplayName("parseBestMove returns null for incomplete bestmove")
	void testParseBestMoveIncomplete() {
	    final var result = UCIProtocol.parseBestMove("bestmove");
	    assertNull(result);
	}
    }

    @Nested
    @DisplayName("SearchInfo Parsing")
    class SearchInfoParsing {

	@Test
	@DisplayName("parseInfo with full info line")
	void testParseInfoFull() {
	    final var info = UCIProtocol
		    .parseInfo("info depth 12 score cp 35 nodes 123456 nps 1000000 time 123 pv e2e4 e7e5 g1f3");
	    assertNotNull(info);
	    assertEquals(12, info.depth());
	    assertEquals(35, info.score());
	    assertFalse(info.isMateScore());
	    assertEquals(123456L, info.nodes());
	    assertEquals(1000000L, info.nps());
	    assertEquals(123L, info.timeMs());
	    assertEquals("e2e4 e7e5 g1f3", info.pvLine());
	}

	@Test
	@DisplayName("parseInfo with mate score")
	void testParseInfoMateScore() {
	    final var info = UCIProtocol.parseInfo("info depth 15 score mate 3 nodes 5000");
	    assertNotNull(info);
	    assertEquals(15, info.depth());
	    assertEquals(3, info.score());
	    assertTrue(info.isMateScore());
	    assertEquals(5000L, info.nodes());
	}

	@Test
	@DisplayName("parseInfo with negative mate score")
	void testParseInfoNegativeMateScore() {
	    final var info = UCIProtocol.parseInfo("info depth 10 score mate -2");
	    assertNotNull(info);
	    assertEquals(-2, info.score());
	    assertTrue(info.isMateScore());
	}

	@Test
	@DisplayName("parseInfo with only depth and score")
	void testParseInfoPartial() {
	    final var info = UCIProtocol.parseInfo("info depth 5 score cp -100");
	    assertNotNull(info);
	    assertEquals(5, info.depth());
	    assertEquals(-100, info.score());
	    assertFalse(info.isMateScore());
	    assertEquals(0L, info.nodes());
	    assertNull(info.pvLine());
	}

	@Test
	@DisplayName("parseInfo returns null for null input")
	void testParseInfoNull() {
	    final var info = UCIProtocol.parseInfo(null);
	    assertNull(info);
	}

	@Test
	@DisplayName("parseInfo returns null for non-info line")
	void testParseInfoWrongPrefix() {
	    final var info = UCIProtocol.parseInfo("bestmove e2e4");
	    assertNull(info);
	}

	@Test
	@DisplayName("SearchInfo.formatScore for centipawns")
	void testFormatScoreCentipawns() {
	    final var info = new UCIProtocol.SearchInfo(10, 150, false, 0, 0, 0, null);
	    assertEquals("1.50", info.formatScore());
	}

	@Test
	@DisplayName("SearchInfo.formatScore for negative centipawns")
	void testFormatScoreNegativeCentipawns() {
	    final var info = new UCIProtocol.SearchInfo(10, -250, false, 0, 0, 0, null);
	    assertEquals("-2.50", info.formatScore());
	}

	@Test
	@DisplayName("SearchInfo.formatScore for mate")
	void testFormatScoreMate() {
	    final var info = new UCIProtocol.SearchInfo(10, 3, true, 0, 0, 0, null);
	    assertEquals("Mate in 3", info.formatScore());
	}

	@Test
	@DisplayName("SearchInfo.toString contains all fields")
	void testSearchInfoToString() {
	    final var info = new UCIProtocol.SearchInfo(12, 50, false, 100000, 500000, 200, "e2e4");
	    final var str = info.toString();
	    assertTrue(str.contains("depth=12"));
	    assertTrue(str.contains("score="));
	    assertTrue(str.contains("nodes=100000"));
	    assertTrue(str.contains("nps=500000"));
	    assertTrue(str.contains("time=200ms"));
	    assertTrue(str.contains("pv=e2e4"));
	}
    }

    @Nested
    @DisplayName("UCI Move Parsing")
    class UCIMoveParsing {

	@Test
	@DisplayName("parseUCIMove with valid opening move e2e4")
	void testParseUCIMoveE2E4() {
	    final var board = new Board();
	    final var move = UCIProtocol.parseUCIMove("e2e4", board);
	    assertNotNull(move, "Should parse e2e4 as a legal move");
	    assertEquals(Square.E2, move.getFrom());
	    assertEquals(Square.E4, move.getTo());
	}

	@Test
	@DisplayName("parseUCIMove with knight move g1f3")
	void testParseUCIMoveKnight() {
	    final var board = new Board();
	    final var move = UCIProtocol.parseUCIMove("g1f3", board);
	    assertNotNull(move, "Should parse g1f3 as a legal move");
	    assertEquals(Square.G1, move.getFrom());
	    assertEquals(Square.F3, move.getTo());
	}

	@Test
	@DisplayName("parseUCIMove with promotion e7e8q")
	void testParseUCIMovePromotion() {
	    // Position where White pawn on e7 can promote (black king away from e8)
	    final var board = new Board();
	    board.loadFromFen("8/4P3/8/8/8/8/8/4K2k w - - 0 1");
	    final var move = UCIProtocol.parseUCIMove("e7e8q", board);
	    assertNotNull(move, "Should parse promotion move");
	    assertEquals(Square.E7, move.getFrom());
	    assertEquals(Square.E8, move.getTo());
	    assertEquals(Piece.WHITE_QUEEN, move.getPromotion());
	}

	@Test
	@DisplayName("parseUCIMove with knight promotion")
	void testParseUCIMoveKnightPromotion() {
	    final var board = new Board();
	    board.loadFromFen("8/4P3/8/8/8/8/8/4K2k w - - 0 1");
	    final var move = UCIProtocol.parseUCIMove("e7e8n", board);
	    assertNotNull(move, "Should parse knight promotion move");
	    assertEquals(Piece.WHITE_KNIGHT, move.getPromotion());
	}

	@Test
	@DisplayName("parseUCIMove case insensitive")
	void testParseUCIMoveCaseInsensitive() {
	    final var board = new Board();
	    final var move = UCIProtocol.parseUCIMove("E2E4", board);
	    assertNotNull(move, "Should handle uppercase input");
	    assertEquals(Square.E2, move.getFrom());
	    assertEquals(Square.E4, move.getTo());
	}

	@Test
	@DisplayName("parseUCIMove returns null for null input")
	void testParseUCIMoveNull() {
	    final var board = new Board();
	    assertNull(UCIProtocol.parseUCIMove(null, board));
	}

	@Test
	@DisplayName("parseUCIMove returns null for too-short string")
	void testParseUCIMoveTooShort() {
	    final var board = new Board();
	    assertNull(UCIProtocol.parseUCIMove("e2", board));
	}

	@Test
	@DisplayName("parseUCIMove returns null for null board")
	void testParseUCIMoveNullBoard() {
	    assertNull(UCIProtocol.parseUCIMove("e2e4", null));
	}

	@Test
	@DisplayName("parseUCIMove returns null for illegal move")
	void testParseUCIMoveIllegal() {
	    final var board = new Board();
	    // e1e3 is not a legal move from the starting position
	    final var move = UCIProtocol.parseUCIMove("e1e3", board);
	    assertNull(move, "Should return null for illegal move");
	}

	@Test
	@DisplayName("parseUCIMove works for castling move")
	void testParseUCIMoveCastling() {
	    // Position where White can castle kingside
	    final var board = new Board();
	    board.loadFromFen("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1");
	    final var move = UCIProtocol.parseUCIMove("e1g1", board);
	    assertNotNull(move, "Should parse kingside castling");
	    assertEquals(Square.E1, move.getFrom());
	    assertEquals(Square.G1, move.getTo());
	}

	@Test
	@DisplayName("parseUCIMove with black promotion")
	void testParseUCIMoveBlackPromotion() {
	    final var board = new Board();
	    board.loadFromFen("4k3/8/8/8/8/8/4p3/K7 b - - 0 1");
	    final var move = UCIProtocol.parseUCIMove("e2e1q", board);
	    assertNotNull(move, "Should parse black queen promotion");
	    assertEquals(Piece.BLACK_QUEEN, move.getPromotion());
	}
    }

    @Nested
    @DisplayName("UCI Constants")
    class UCIConstants {

	@Test
	@DisplayName("UCI command constants are correct")
	void testCommandConstants() {
	    assertEquals("uci", UCIProtocol.CMD_UCI);
	    assertEquals("isready", UCIProtocol.CMD_ISREADY);
	    assertEquals("ucinewgame", UCIProtocol.CMD_UCINEWGAME);
	    assertEquals("stop", UCIProtocol.CMD_STOP);
	    assertEquals("quit", UCIProtocol.CMD_QUIT);
	}

	@Test
	@DisplayName("UCI response constants are correct")
	void testResponseConstants() {
	    assertEquals("uciok", UCIProtocol.RESP_UCIOK);
	    assertEquals("readyok", UCIProtocol.RESP_READYOK);
	    assertEquals("bestmove", UCIProtocol.RESP_BESTMOVE);
	    assertEquals("info", UCIProtocol.RESP_INFO);
	}
    }
}
