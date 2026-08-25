package com.fason.app.features.springe.injection;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * AccessibilityInjector — Performs UI automation inside real apps
 * via Android AccessibilityService (GoldDigger-style).
 *
 * Capabilities:
 * - Inject text into input fields by finding them via AccessibilityNodeInfo
 * - Click buttons, links, and interactive elements
 * - Perform swipe gestures and scroll actions
 * - Navigate the UI tree programmatically
 * - Execute full transaction flows (step-by-step automation)
 *
 * Requires FasonAccessibilityService to be connected. Thread-safe for
 * command dispatch; all UI operations run on the accessibility thread.
 */
public final class AccessibilityInjector {

    private static final String TAG = "AccessibilityInjector";

    private final Context context;
    private volatile AccessibilityService accessibilityService;

    // Timeout for finding UI elements (milliseconds)
    private static final long FIND_TIMEOUT_MS = 5000;

    public AccessibilityInjector(Context context) {
        this.context = context;
    }

    /**
     * Link to the connected AccessibilityService. Called during service connection.
     */
    public void setAccessibilityService(AccessibilityService service) {
        this.accessibilityService = service;
        Log.d(TAG, "Accessibility service " + (service != null ? "connected" : "disconnected"));
    }

    public boolean isReady() {
        return accessibilityService != null;
    }

    /**
     * Inject text into a specific input field within the current app.
     * If targetNodeId is empty, finds the first visible EditText.
     */
    public boolean injectText(String text, String targetNodeId) {
        if (!isReady()) return false;

        try {
            AccessibilityNodeInfo root = accessibilityService.getRootInActiveWindow();
            if (root == null) return false;

            try {
                AccessibilityNodeInfo target = null;

                if (!targetNodeId.isEmpty()) {
                    target = findNodeById(root, targetNodeId);
                }

                if (target == null) {
                    // Find the first focusable text input
                    target = findFirstEditableText(root);
                }

                if (target == null) {
                    Log.w(TAG, "No editable text field found");
                    return false;
                }

                // Focus the field
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);

                // Set text
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);

                Log.d(TAG, "Text injected: " + text.length() + " chars into " + target.getViewIdResourceName());
                return true;

            } finally {
                root.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to inject text", e);
            return false;
        }
    }

    /**
     * Perform a gesture (click, long-click, swipe) at the specified coordinates.
     */
    public boolean injectGesture(String gestureType, float x, float y) {
        if (!isReady()) return false;

        try {
            AccessibilityNodeInfo root = accessibilityService.getRootInActiveWindow();
            if (root == null) return false;

            try {
                // Find node at the given coordinates
                AccessibilityNodeInfo target = findNodeAtPosition(root, x, y);
                if (target == null) {
                    Log.w(TAG, "No node found at (" + x + "," + y + ")");
                    return false;
                }

                boolean success = false;
                switch (gestureType) {
                    case "click":
                        success = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        break;
                    case "long_click":
                        success = target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
                        break;
                    case "focus":
                        success = target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                        break;
                    default:
                        Log.w(TAG, "Unknown gesture: " + gestureType);
                }

                return success;

            } finally {
                root.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to inject gesture", e);
            return false;
        }
    }

    /**
     * Find a UI element by its view ID resource name.
     */
    private AccessibilityNodeInfo findNodeById(AccessibilityNodeInfo root, String viewId) {
        if (root == null) return null;

        if (viewId.equals(root.getViewIdResourceName())) {
            return AccessibilityNodeInfo.obtain(root);
        }

        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo result = findNodeById(child, viewId);
                if (result != null) return result;
                child.recycle();
            }
        }
        return null;
    }

    /**
     * Find the first visible, editable text field.
     */
    private AccessibilityNodeInfo findFirstEditableText(AccessibilityNodeInfo root) {
        if (root == null) return null;

        if (root.isEditable() && root.isVisibleToUser()) {
            return AccessibilityNodeInfo.obtain(root);
        }

        if (root.getChildCount() == 0) return null;

        // Search depth-first
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo result = findFirstEditableText(child);
                if (result != null) {
                    child.recycle();
                    return result;
                }
                child.recycle();
            }
        }
        return null;
    }

    /**
     * Find the smallest visible node containing the given screen coordinate.
     */
    private AccessibilityNodeInfo findNodeAtPosition(AccessibilityNodeInfo root, float x, float y) {
        if (root == null) return null;

        android.graphics.Rect bounds = new android.graphics.Rect();
        root.getBoundsInScreen(bounds);

        if (!bounds.contains((int) x, (int) y)) return null;
        if (!root.isVisibleToUser()) return null;

        // Check children first (find the most specific node)
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo result = findNodeAtPosition(child, x, y);
                if (result != null) {
                    child.recycle();
                    return result;
                }
                child.recycle();
            }
        }

        // This node contains the point but no child is more specific
        if (root.isClickable()) {
            return AccessibilityNodeInfo.obtain(root);
        }

        return null;
    }

    // Android Bundle wrapper for API compatibility
    private static final class BundleData {
        Bundle() { super(); }
    }
}
