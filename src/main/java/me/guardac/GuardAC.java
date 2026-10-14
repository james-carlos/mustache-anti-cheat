package me.guardac;

import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GuardAC extends JavaPlugin {

    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();
    private ViolationManager violations;

    private long lastTickNanos;
    private long lastLagSpikeMs;
    private int tickCounter;

    private long lagSpikeMs = 150;
    private long lagExemptMs = 3000;
    private double minTps = 18.0;
    private long joinExemptMs = 5000;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();

        violations = new ViolationManager(this);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents((Listener) new ExemptionListener(this), this);
        pm.registerEvents((Listener) new MovementChecks(this), this);
        pm.registerEvents((Listener) new CombatChecks(this), this);

        PluginCommand command = getCommand("guardac");
        if (command != null) {
            GuardCommand handler = new GuardCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        lastTickNanos = System.nanoTime();
        getServer().getScheduler().runTaskTimer(this, this::monitorTick, 1L, 1L);
        violations.startDecayTask();

        getLogger().info("GuardAC enabled.");
    }

    @Override
    public void onDisable() {
        players.clear();
    }

    public void reloadSettings() {
        lagSpikeMs = getConfig().getLong("lag.spike-ms", 150);
        lagExemptMs = getConfig().getLong("lag.exempt-ms", 3000);
        minTps = getConfig().getDouble("lag.min-tps", 18.0);
        joinExemptMs = getConfig().getLong("exempt.join-ms", 5000);
    }

    /** Watches the main thread: a long gap between ticks means the server lagged. */
    private void monitorTick() {
        long now = System.nanoTime();
        long gapMs = (now - lastTickNanos) / 1_000_000L;
        lastTickNanos = now;
        if (gapMs > lagSpikeMs) {
            lastLagSpikeMs = System.currentTimeMillis();
        }
        if (++tickCounter % 20 == 0) {
            try {
                double[] tps = getServer().getTPS();
                if (tps.length > 0 && tps[0] < minTps) {
                    lastLagSpikeMs = System.currentTimeMillis();
                }
            } catch (Throwable ignored) {
                // getTPS not available on this server software
            }
        }
    }

    public boolean recentLag() {
        return System.currentTimeMillis() - lastLagSpikeMs < lagExemptMs;
    }

    public PlayerData data(Player player) {
        return players.computeIfAbsent(player.getUniqueId(), id -> new PlayerData(id, joinExemptMs));
    }

    public void removeData(UUID id) {
        players.remove(id);
    }

    public ViolationManager violations() {
        return violations;
    }
}
