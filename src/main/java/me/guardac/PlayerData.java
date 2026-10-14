package me.guardac;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-player state used by the checks. Only touched from the main server thread. */
public final class PlayerData {

    public final UUID id;

    // ---- movement state ----
    public long exemptUntil;
    public double lastDy;
    public boolean hasLastDy;
    public long lastMoveMs;
    public long lastMoveNs;
    public double timerBalance;
    public int airTicks;
    public int groundTicks;
    public double trackedFall;
    public long lastIceMs;
    public long lastBounceMs;

    // ---- combat state ----
    public final Deque<Long> clickTimes = new ArrayDeque<>();
    public final Deque<Long> clickIntervals = new ArrayDeque<>();
    public long lastClickMs;
    public final Map<UUID, Long> recentTargets = new HashMap<>();

    private final Map<String, Double> buffers = new HashMap<>();

    public PlayerData(UUID id, long joinExemptMs) {
        this.id = id;
        this.exemptUntil = System.currentTimeMillis() + joinExemptMs;
    }

    /** Pause movement checks for the given number of milliseconds. */
    public void exempt(long ms) {
        exemptUntil = Math.max(exemptUntil, System.currentTimeMillis() + ms);
    }

    public void resetMovementState() {
        hasLastDy = false;
        lastMoveMs = 0;
        lastMoveNs = 0;
        timerBalance = 0;
        airTicks = 0;
        groundTicks = 0;
        trackedFall = 0;
    }

    public double addBuffer(String key, double amount) {
        return buffers.merge(key, amount, Double::sum);
    }

    public void lowerBuffer(String key, double amount) {
        buffers.computeIfPresent(key, (k, v) -> Math.max(0.0, v - amount));
    }

    public void setBuffer(String key, double value) {
        buffers.put(key, value);
    }
}
