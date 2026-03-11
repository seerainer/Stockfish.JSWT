package io.github.seerainer.chess;

import static org.eclipse.swt.events.SelectionListener.widgetSelectedAdapter;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;

import io.github.seerainer.chess.config.AppPreferences;

/**
 * Modal settings dialog that lets the user view and change all persisted
 * application preferences.
 *
 * <p>
 * Changes are applied and saved only when the user clicks <em>OK</em>. The
 * dialog is intentionally modal (application-modal via
 * {@link SWT#APPLICATION_MODAL}) so it blocks the main shell while open.
 * </p>
 *
 * <p>
 * After the dialog closes, callers should check {@link #isConfirmed()} and then
 * read back the individual {@code get*} values, or simply re-query
 * {@link AppPreferences} directly.
 * </p>
 */
public class SettingsDialog {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final Shell parent;
    private Shell dialog;

    // Stockfish controls
    private Spinner skillLevelSpinner;
    private Combo thinkTimeCombo;
    private Button limitStrengthCheck;
    private Spinner uciEloSpinner;
    private Spinner searchDepthSpinner;

    // UI controls
    private Combo aiMoveDelayCombo;

    // Debug controls
    private Button debugLoggingCheck;
    private Button perfMonitoringCheck;

    // State
    private boolean confirmed = false;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Creates a new settings dialog owned by {@code parent}.
     *
     * @param parent the parent shell; must not be {@code null}
     */
    public SettingsDialog(final Shell parent) {
	this.parent = parent;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Opens the dialog and blocks until it is closed.
     *
     * @return {@code true} if the user confirmed the dialog with <em>OK</em>
     */
    public boolean open() {
	createDialog();
	dialog.open();

	final var display = parent.getDisplay();
	while (!dialog.isDisposed()) {
	    if (!display.readAndDispatch()) {
		display.sleep();
	    }
	}

	return confirmed;
    }

    // -----------------------------------------------------------------------
    // Dialog construction
    // -----------------------------------------------------------------------

    private void createDialog() {
	dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
	dialog.setText("Settings");
	dialog.setLayout(new GridLayout(1, false));

	// Main content area
	final var content = new Composite(dialog, SWT.NONE);
	content.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
	content.setLayout(new GridLayout(1, false));

	createStockfishGroup(content);
	createUIGroup(content);
	createDebugGroup(content);

	// Button bar
	createButtonBar(dialog);

	dialog.pack();
	centerOnParent();
    }

    private void createStockfishGroup(final Composite composite) {
	final var group = new Group(composite, SWT.NONE);
	group.setText("Engine (Stockfish)");
	group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
	group.setLayout(new GridLayout(2, false));

	// Skill Level
	new Label(group, SWT.NONE).setText("Skill Level (0–20):");
	skillLevelSpinner = new Spinner(group, SWT.BORDER);
	skillLevelSpinner.setMinimum(0);
	skillLevelSpinner.setMaximum(20);
	skillLevelSpinner.setSelection(AppPreferences.loadSkillLevel());
	skillLevelSpinner.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
	skillLevelSpinner.setToolTipText("0 = weakest, 20 = strongest");

	// Think Time
	new Label(group, SWT.NONE).setText("Think Time:");
	thinkTimeCombo = new Combo(group, SWT.READ_ONLY | SWT.DROP_DOWN);
	thinkTimeCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
	final var thinkTimes = new int[] { 500, 1000, 2000, 3000, 5000, 10000, 15000, 30000 };
	final var thinkTimeLabels = new String[] { "0.5 seconds", "1 second", "2 seconds", "3 seconds", "5 seconds",
		"10 seconds", "15 seconds", "30 seconds" };
	for (final var label : thinkTimeLabels) {
	    thinkTimeCombo.add(label);
	}
	final var savedThinkTime = AppPreferences.loadThinkTimeMs();
	var thinkTimeIdx = 2; // default to 2 s
	for (var i = 0; i < thinkTimes.length; i++) {
	    if (thinkTimes[i] == savedThinkTime) {
		thinkTimeIdx = i;
		break;
	    }
	}
	thinkTimeCombo.select(thinkTimeIdx);
	thinkTimeCombo.setData("values", thinkTimes);

	// Search Depth
	new Label(group, SWT.NONE).setText("Search Depth:");
	searchDepthSpinner = new Spinner(group, SWT.BORDER);
	searchDepthSpinner.setMinimum(1);
	searchDepthSpinner.setMaximum(30);
	searchDepthSpinner.setSelection(AppPreferences.loadSearchDepth());
	searchDepthSpinner.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
	searchDepthSpinner.setToolTipText("Half-moves (plies) Stockfish searches ahead");

	// Limit Strength
	new Label(group, SWT.NONE).setText("Limit Strength:");
	limitStrengthCheck = new Button(group, SWT.CHECK);
	limitStrengthCheck.setSelection(AppPreferences.loadLimitStrength());
	limitStrengthCheck.setToolTipText("Enable to cap engine strength at the specified ELO");
	limitStrengthCheck.addSelectionListener(widgetSelectedAdapter(_ -> updateEloSpinnerState()));

	// UCI ELO
	new Label(group, SWT.NONE).setText("Target ELO (100–3190):");
	uciEloSpinner = new Spinner(group, SWT.BORDER);
	uciEloSpinner.setMinimum(100);
	uciEloSpinner.setMaximum(3190);
	uciEloSpinner.setIncrement(50);
	uciEloSpinner.setPageIncrement(200);
	uciEloSpinner.setSelection(AppPreferences.loadUciElo());
	uciEloSpinner.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
	uciEloSpinner.setToolTipText("Approximate ELO rating when strength limiting is active");

	// Reflect initial limit-strength state
	updateEloSpinnerState();
    }

    private void createUIGroup(final Composite composite) {
	final var group = new Group(composite, SWT.NONE);
	group.setText("User Interface");
	group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
	group.setLayout(new GridLayout(2, false));

	// AI Move Delay
	new Label(group, SWT.NONE).setText("AI Move Delay:");
	aiMoveDelayCombo = new Combo(group, SWT.READ_ONLY | SWT.DROP_DOWN);
	aiMoveDelayCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
	final var delays = new int[] { 0, 100, 200, 300, 500, 1000 };
	final var delayLabels = new String[] { "None (0 ms)", "100 ms", "200 ms", "300 ms", "500 ms", "1 second" };
	for (final var label : delayLabels) {
	    aiMoveDelayCombo.add(label);
	}
	final var savedDelay = AppPreferences.loadAiMoveDelayMs();
	var delayIdx = 3; // default 300 ms
	for (var i = 0; i < delays.length; i++) {
	    if (delays[i] == savedDelay) {
		delayIdx = i;
		break;
	    }
	}
	aiMoveDelayCombo.select(delayIdx);
	aiMoveDelayCombo.setData("values", delays);
	aiMoveDelayCombo.setToolTipText("Pause between moves in Computer vs Computer mode");
    }

    private void createDebugGroup(final Composite composite) {
	final var group = new Group(composite, SWT.NONE);
	group.setText("Diagnostics");
	group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
	group.setLayout(new GridLayout(2, false));

	// Debug Logging
	new Label(group, SWT.NONE).setText("Debug Logging:");
	debugLoggingCheck = new Button(group, SWT.CHECK);
	debugLoggingCheck.setSelection(AppPreferences.loadEnableDebugLogging());
	debugLoggingCheck.setToolTipText("Print verbose engine output to stdout");

	// Performance Monitoring
	new Label(group, SWT.NONE).setText("Performance Monitoring:");
	perfMonitoringCheck = new Button(group, SWT.CHECK);
	perfMonitoringCheck.setSelection(AppPreferences.loadEnablePerformanceMonitoring());
	perfMonitoringCheck.setToolTipText("Log search timing and statistics after each move");
    }

    private void createButtonBar(final Composite composite) {
	final var bar = new Composite(composite, SWT.NONE);
	bar.setLayoutData(new GridData(SWT.RIGHT, SWT.BOTTOM, true, false));
	bar.setLayout(new GridLayout(3, true));

	final var resetBtn = new Button(bar, SWT.PUSH);
	resetBtn.setText("Reset Defaults");
	resetBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
	resetBtn.addSelectionListener(widgetSelectedAdapter(_ -> resetControls()));

	final var cancelBtn = new Button(bar, SWT.PUSH);
	cancelBtn.setText("Cancel");
	cancelBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
	cancelBtn.addSelectionListener(widgetSelectedAdapter(_ -> dialog.dispose()));

	final var okBtn = new Button(bar, SWT.PUSH);
	okBtn.setText("OK");
	okBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
	dialog.setDefaultButton(okBtn);
	okBtn.addSelectionListener(widgetSelectedAdapter(_ -> {
	    saveAll();
	    confirmed = true;
	    dialog.dispose();
	}));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Enables / disables the ELO spinner based on the limit-strength checkbox. */
    private void updateEloSpinnerState() {
	uciEloSpinner.setEnabled(limitStrengthCheck.getSelection());
    }

    /** Resets all controls to the compiled-in defaults (does not flush prefs). */
    private void resetControls() {
	skillLevelSpinner.setSelection(io.github.seerainer.chess.config.ChessConfig.Stockfish.SKILL_LEVEL);
	selectComboByValue(thinkTimeCombo, io.github.seerainer.chess.config.ChessConfig.Stockfish.THINK_TIME_MS);
	searchDepthSpinner.setSelection(io.github.seerainer.chess.config.ChessConfig.Stockfish.DEPTH);
	limitStrengthCheck.setSelection(io.github.seerainer.chess.config.ChessConfig.Stockfish.LIMIT_STRENGTH);
	uciEloSpinner.setSelection(io.github.seerainer.chess.config.ChessConfig.Stockfish.UCI_ELO);
	selectComboByValue(aiMoveDelayCombo, io.github.seerainer.chess.config.ChessConfig.UI.AI_MOVE_DELAY_MS);
	debugLoggingCheck.setSelection(io.github.seerainer.chess.config.ChessConfig.Debug.ENABLE_DEBUG_LOGGING);
	perfMonitoringCheck
		.setSelection(io.github.seerainer.chess.config.ChessConfig.Debug.ENABLE_PERFORMANCE_MONITORING);
	updateEloSpinnerState();
    }

    /**
     * Reads all control values and writes them to {@link AppPreferences}.
     */
    private void saveAll() {
	AppPreferences.saveSkillLevel(skillLevelSpinner.getSelection());
	AppPreferences.saveThinkTimeMs(getComboIntValue(thinkTimeCombo));
	AppPreferences.saveSearchDepth(searchDepthSpinner.getSelection());
	AppPreferences.saveLimitStrength(limitStrengthCheck.getSelection());
	AppPreferences.saveUciElo(uciEloSpinner.getSelection());
	AppPreferences.saveAiMoveDelayMs(getComboIntValue(aiMoveDelayCombo));
	AppPreferences.saveEnableDebugLogging(debugLoggingCheck.getSelection());
	AppPreferences.saveEnablePerformanceMonitoring(perfMonitoringCheck.getSelection());
    }

    /**
     * Returns the integer value at the selected index of a combo whose
     * {@code "values"} data key holds an {@code int[]}.
     */
    private static int getComboIntValue(final Combo combo) {
	final var values = (int[]) combo.getData("values");
	final var idx = combo.getSelectionIndex();
	if (values == null || idx < 0 || idx >= values.length) {
	    return 0;
	}
	return values[idx];
    }

    /**
     * Selects the combo entry whose backing int value equals {@code target}. Falls
     * back to index 0 if no match is found.
     */
    private static void selectComboByValue(final Combo combo, final int target) {
	final var values = (int[]) combo.getData("values");
	if (values == null) {
	    return;
	}
	for (var i = 0; i < values.length; i++) {
	    if (values[i] == target) {
		combo.select(i);
		return;
	    }
	}
	combo.select(0);
    }

    /** Centers the dialog shell over the parent shell. */
    private void centerOnParent() {
	final var parentBounds = parent.getBounds();
	final var dialogSize = dialog.getSize();
	final var x = parentBounds.x + (parentBounds.width - dialogSize.x) / 2;
	final var y = parentBounds.y + (parentBounds.height - dialogSize.y) / 2;
	dialog.setLocation(x, y);
    }
}
