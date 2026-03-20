package io.github.seerainer.chess;

import org.eclipse.swt.widgets.Display;

import io.github.seerainer.chess.config.ChessConfig;

public class Main {
    private static OutputConsole console;

    public static void main(final String[] args) {
	// Add shutdown hook for proper cleanup
	Runtime.getRuntime().addShutdownHook(new Thread(() -> {
	    if (ChessConfig.Debug.ENABLE_DEBUG_LOGGING) {
		System.out.println("Chess application shutting down...");
	    }
	}));

	Display display = null;
	ChessGameUI gameUI = null;

	try {
	    display = new Display();
	    console = new OutputConsole(display);
	    gameUI = new ChessGameUI(display, console);
	    final var shell = gameUI.open();

	    // Main event loop with proper exception handling
	    while (!shell.isDisposed()) {
		try {
		    if (!display.readAndDispatch()) {
			display.sleep();
		    }
		} catch (final Exception e) {
		    System.err.println("Error in main event loop: " + e.getMessage());
		    e.printStackTrace();
		    // Continue running unless it's a critical error
		}
	    }
	} catch (final Exception e) {
	    System.err.println("Critical error in chess application: " + e.getMessage());
	    e.printStackTrace();
	} finally {
	    // Proper cleanup in finally block
	    if (gameUI != null) {
		try {
		    gameUI.dispose();
		} catch (final Exception e) {
		    System.err.println("Error disposing game UI: " + e.getMessage());
		}
	    }
	    if (console != null) {
		try {
		    console.close();
		} catch (final Exception e) {
		    System.err.println("Error disposing output console: " + e.getMessage());
		}
	    }
	    if (display != null && !display.isDisposed()) {
		try {
		    display.dispose();
		} catch (final Exception e) {
		    System.err.println("Error disposing display: " + e.getMessage());
		}
	    }
	}
    }
}
