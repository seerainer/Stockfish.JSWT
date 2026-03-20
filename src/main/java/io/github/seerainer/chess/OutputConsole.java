package io.github.seerainer.chess;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * A graphical console window that captures all output written to
 * {@link System#out} and {@link System#err} and displays it in an SWT
 * {@link StyledText} widget.
 *
 * <p>
 * Standard-output lines are displayed in the default foreground colour;
 * standard-error lines are displayed in red. The stream redirect stays active
 * even after the window is closed; any output produced while the window is
 * hidden is buffered and replayed when the window is reopened via
 * {@link #reopen()}.
 * </p>
 *
 * <p>
 * {@link #close()} permanently tears down both the window and the stream
 * redirect and restores the original {@link System#out}/{@link System#err}.
 * </p>
 */
public class OutputConsole implements AutoCloseable {

    private static final int CONSOLE_WIDTH = 800;
    private static final int CONSOLE_HEIGHT = 400;
    private static final int MAX_CHAR_COUNT = 200_000;
    /** Maximum number of buffered chunks kept while the window is closed. */
    private static final int MAX_BUFFER_CHUNKS = 4_000;
    private final Display display;
    private final PrintStream originalOut;
    private final PrintStream originalErr;
    /**
     * Guards {@link #pendingChunks} and {@link #shellOpen}. Every field that is
     * read or written from a background thread must be accessed under this lock.
     */
    private final Object lock = new Object();
    /** Chunks buffered while the window is closed. */
    private final List<Chunk> pendingChunks = new ArrayList<>();
    /** {@code true} while the console {@link Shell} is open. */
    private volatile boolean shellOpen = false;
    /** {@code true} after {@link #close()} has been called. */
    private volatile boolean terminated = false;
    private Shell shell;
    private StyledText text;
    private Color errorColor;
    private Font monoFont;

    /**
     * Creates the console window and redirects {@link System#out} and
     * {@link System#err} to it. Must be called on the SWT display thread.
     *
     * @param display the SWT display; must not be null
     */
    public OutputConsole(final Display display) {
	this.display = display;
	this.originalOut = System.out;
	this.originalErr = System.err;

	openShell();

	// Install redirected streams. These remain installed until close().
	System.setOut(buildStream(false));
	System.setErr(buildStream(true));
    }

    /**
     * (Re-)creates the SWT shell, widgets, and graphical resources. Must be called
     * on the display thread.
     */
    private void openShell() {
	shell = new Shell(display, SWT.SHELL_TRIM);
	shell.setText("Output Console");
	shell.setSize(CONSOLE_WIDTH, CONSOLE_HEIGHT);
	shell.setLayout(new FillLayout());

	// Position in the upper-right area of the primary monitor
	final var bounds = display.getPrimaryMonitor().getBounds();
	shell.setLocation(bounds.x + bounds.width - CONSOLE_WIDTH - 20, bounds.y + 20);
	shell.setMaximized(true);

	// StyledText widget — read-only, scrollable
	text = new StyledText(shell, SWT.MULTI | SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL | SWT.READ_ONLY);
	text.setText("Initializing Stockfish WASM engine...\n");
	text.setWordWrap(false);

	// Monospaced font for readability
	monoFont = new Font(display, new FontData("Courier New", 9, SWT.NORMAL));
	text.setFont(monoFont);

	// Colour for stderr output
	errorColor = new Color(display, new RGB(200, 0, 0));

	// When the user closes the window, mark it as hidden but keep the streams.
	shell.addDisposeListener(_ -> onShellDisposed());

	shell.open();

	synchronized (lock) {
	    shellOpen = true;
	    // Replay anything buffered while the window was closed
	    replayPending();
	}
    }

    /**
     * Called on the display thread when the shell's dispose listener fires (i.e.
     * the user clicked the close button).
     */
    private void onShellDisposed() {
	synchronized (lock) {
	    shellOpen = false;
	}
	disposeGraphicalResources();
    }

    /** Disposes colours and fonts. Must be called on the display thread. */
    private void disposeGraphicalResources() {
	if (errorColor != null && !errorColor.isDisposed()) {
	    errorColor.dispose();
	    errorColor = null;
	}
	if (((monoFont == null) || monoFont.isDisposed())) {
	    return;
	}
	monoFont.dispose();
	monoFont = null;
    }

    /**
     * Reopens the console window if it has been closed. If the window is already
     * open, it is brought to the front. Must be called on the SWT display thread.
     */
    public void reopen() {
	if (terminated) {
	    return;
	}
	if (shell == null || shell.isDisposed()) {
	    openShell();
	} else {
	    // Already open — just bring it to the front
	    shell.setVisible(true);
	    shell.forceActive();
	}
    }

    /**
     * Permanently stops the stream redirect, restores the original
     * {@link System#out} and {@link System#err}, and disposes the SWT window and
     * all graphical resources. Must be called on the SWT display thread.
     */
    @Override
    public void close() {
	if (terminated) {
	    return;
	}
	terminated = true;

	// Flush any pending output before restoring
	System.out.flush();
	System.err.flush();

	// Restore the original streams
	System.setOut(originalOut);
	System.setErr(originalErr);

	// Dispose SWT resources on the display thread
	if (display.isDisposed()) {
	    return;
	}
	final Runnable dispose = () -> {
	    disposeGraphicalResources();
	    if (shell != null && !shell.isDisposed()) {
		// Remove our listener first so onShellDisposed() is not called
		shell.dispose();
	    }
	};
	if (display.getThread() == Thread.currentThread()) {
	    dispose.run();
	} else {
	    display.syncExec(dispose);
	}
    }

    /**
     * Builds a {@link PrintStream} that routes all written bytes to
     * {@link #accept(String, boolean)}.
     *
     * @param isError {@code true} for stderr (shown in red)
     * @return the new redirecting stream
     */
    private PrintStream buildStream(final boolean isError) {
	final var out = new OutputStream() {
	    private final StringBuilder buf = new StringBuilder();

	    @Override
	    public void write(final int b) throws IOException {
		buf.append((char) (b & 0xff));
		if (b == '\n') {
		    flush();
		}
	    }

	    @Override
	    public void write(final byte[] b, final int off, final int len) throws IOException {
		buf.append(new String(b, off, len, StandardCharsets.UTF_8));
		if (buf.indexOf("\n") >= 0 || buf.length() > 4096) {
		    flush();
		}
	    }

	    @Override
	    public void flush() {
		if (buf.isEmpty()) {
		    return;
		}
		final var chunk = buf.toString();
		buf.setLength(0);
		accept(chunk, isError);
	    }
	};
	try {
	    return new PrintStream(out, true, StandardCharsets.UTF_8.name());
	} catch (final UnsupportedEncodingException e) {
	    throw new IllegalStateException(e);
	}
    }

    /**
     * Routes a text chunk to the console widget (if the window is open) or to the
     * pending buffer (if the window is currently closed). Safe to call from any
     * thread.
     *
     * @param chunk   the text to display
     * @param isError {@code true} to display in red
     */
    private void accept(final String chunk, final boolean isError) {
	if (terminated || display.isDisposed()) {
	    return;
	}

	synchronized (lock) {
	    if (!shellOpen) {
		// Window is closed — buffer for later replay
		if (pendingChunks.size() < MAX_BUFFER_CHUNKS) {
		    pendingChunks.add(new Chunk(chunk, isError));
		}
		return;
	    }
	}

	// Window is open — dispatch to display thread
	appendToWidget(chunk, isError);
    }

    /**
     * Replays all buffered chunks into the widget. Must be called with
     * {@link #lock} held and from the display thread (via {@link #openShell()}).
     */
    private void replayPending() {
	pendingChunks.forEach((final var c) -> appendToWidget(c.text(), c.isError()));
	pendingChunks.clear();
    }

    /**
     * Appends a chunk directly to the {@link StyledText} widget. Safe to call from
     * any thread — dispatches to the display thread when necessary.
     *
     * @param chunk   the text to append
     * @param isError {@code true} to display in red
     */
    private void appendToWidget(final String chunk, final boolean isError) {
	final Runnable runnable = () -> {
	    if (shell == null || shell.isDisposed() || text == null || text.isDisposed()) {
		return;
	    }

	    // Trim oldest content to keep memory bounded
	    final var currentLen = text.getCharCount();
	    if (currentLen > MAX_CHAR_COUNT) {
		text.replaceTextRange(0, currentLen - MAX_CHAR_COUNT / 2, "");
	    }

	    final var insertOffset = text.getCharCount();
	    text.append(chunk);

	    if (isError && errorColor != null && !errorColor.isDisposed()) {
		final var style = new StyleRange();
		style.start = insertOffset;
		style.length = chunk.length();
		style.foreground = errorColor;
		text.setStyleRange(style);
	    }

	    // Auto-scroll to the bottom
	    text.setTopIndex(text.getLineCount() - 1);
	};

	if (display.getThread() == Thread.currentThread()) {
	    runnable.run();
	} else {
	    display.asyncExec(runnable);
	}
    }

    // -----------------------------------------------------------------------
    // A single buffered chunk: the text and whether it came from stderr.
    // -----------------------------------------------------------------------
    private record Chunk(String text, boolean isError) {
    }

    // -----------------------------------------------------------------------
    // Shared state — accessed from multiple threads
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // SWT widgets — only accessed on the display thread
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // Shell management
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // Stream construction
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // Output routing
    // -----------------------------------------------------------------------

}
