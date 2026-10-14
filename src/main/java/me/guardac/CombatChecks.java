package me.guardac;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.Locale;

/** Reach, KillAura (angle + multi-target) and AutoClicker checks. */
public final class CombatChecks implements Listener {

    private final GuardAC plugin;

    public CombatChecks(GuardAC plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) {
            return;
        }
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            return;
        }
        if (exempt(p)) {
            return;
        }

        Entity target = e.getEntity();
        if (!p.getWorld().equals(target.getWorld())) {
            return;
        }

        FileConfiguration cfg = plugin.getConfig();
        PlayerData d = plugin.data(p);
        long now = System.currentTimeMillis();
        boolean cancel = cfg.getBoolean("cancel-hits", true);

        Location eye = p.getEyeLocation();
        BoundingBox box = target.getBoundingBox();
        double nx = clamp(eye.getX(), box.getMinX(), box.getMaxX());
        double ny = clamp(eye.getY(), box.getMinY(), box.getMaxY());
        double nz = clamp(eye.getZ(), box.getMinZ(), box.getMaxZ());
        double dist = Math.sqrt(sq(eye.getX() - nx) + sq(eye.getY() - ny) + sq(eye.getZ() - nz));

        // ---------------------------------------------------------------- Reach
        if (cfg.getBoolean("checks.reach.enabled", true)) {
            double max = cfg.getDouble("checks.reach.max", 3.1);
            double leeway = Math.min(
                    cfg.getDouble("checks.reach.max-ping-leeway", 1.0),
                    Math.max(0, p.getPing()) * cfg.getDouble("checks.reach.ping-factor", 0.004));
            if (dist > max + leeway) {
                if (cancel) {
                    e.setCancelled(true);
                }
                double buf = d.addBuffer("reach", 1.0);
                if (buf >= cfg.getDouble("checks.reach.buffer", 3.0)) {
                    plugin.violations().flag(p, "reach", "Reach",
                            String.format(Locale.ROOT, "%.2f blocks (max %.2f)", dist, max + leeway));
                    d.setBuffer("reach", 0.0);
                }
                return;
            }
            d.lowerBuffer("reach", 0.25);
        }

        if (!cfg.getBoolean("checks.killaura.enabled", true)) {
            return;
        }

        // ------------------------------------------------------ KillAura: angle
        if (dist >= cfg.getDouble("checks.killaura.min-distance", 1.5)) {
            Vector toTarget = box.getCenter().subtract(eye.toVector());
            if (toTarget.lengthSquared() > 1.0E-6) {
                double angle = Math.toDegrees(eye.getDirection().angle(toTarget));
                if (angle > cfg.getDouble("checks.killaura.max-angle", 65.0)) {
                    if (cancel) {
                        e.setCancelled(true);
                    }
                    double buf = d.addBuffer("aura-angle", 1.0);
                    if (buf >= cfg.getDouble("checks.killaura.buffer", 3.0)) {
                        plugin.violations().flag(p, "killaura", "KillAura",
                                String.format(Locale.ROOT, "hit %.0f deg off-target", angle));
                        d.setBuffer("aura-angle", 0.0);
                    }
                    return;
                }
                d.lowerBuffer("aura-angle", 0.25);
            }
        }

        // ------------------------------------------------- KillAura: multi-target
        long window = cfg.getLong("checks.killaura.multi-window-ms", 400);
        d.recentTargets.values().removeIf(t -> now - t > window);
        d.recentTargets.put(target.getUniqueId(), now);
        if (d.recentTargets.size() >= cfg.getInt("checks.killaura.multi-count", 3)) {
            plugin.violations().flag(p, "killaura", "KillAura",
                    d.recentTargets.size() + " targets in " + window + "ms");
            d.recentTargets.clear();
            if (cancel) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent e) {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("checks.autoclicker.enabled", true)) {
            return;
        }
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        Player p = e.getPlayer();
        if (exempt(p)) {
            return;
        }
        // Swinging at a block is mining/building, not attacking.
        if (p.rayTraceBlocks(5.0) != null) {
            return;
        }

        PlayerData d = plugin.data(p);
        long now = System.currentTimeMillis();

        // clicks per second
        d.clickTimes.addLast(now);
        while (!d.clickTimes.isEmpty() && now - d.clickTimes.peekFirst() > 1000) {
            d.clickTimes.pollFirst();
        }
        int cps = d.clickTimes.size();
        if (cps > cfg.getInt("checks.autoclicker.max-cps", 22)) {
            plugin.violations().flag(p, "autoclicker", "AutoClicker", "cps=" + cps);
            d.clickTimes.clear();
            d.clickIntervals.clear();
            d.lastClickMs = now;
            return;
        }

        // click consistency (humans are never perfectly regular)
        if (d.lastClickMs != 0) {
            long interval = now - d.lastClickMs;
            if (interval < 1000) {
                d.clickIntervals.addLast(interval);
                int samples = cfg.getInt("checks.autoclicker.samples", 40);
                while (d.clickIntervals.size() > samples) {
                    d.clickIntervals.pollFirst();
                }
                if (d.clickIntervals.size() == samples) {
                    double sum = 0;
                    for (long v : d.clickIntervals) {
                        sum += v;
                    }
                    double mean = sum / samples;
                    double var = 0;
                    for (long v : d.clickIntervals) {
                        var += sq(v - mean);
                    }
                    double std = Math.sqrt(var / samples);
                    if (mean < cfg.getDouble("checks.autoclicker.max-mean-ms", 150.0)
                            && std < cfg.getDouble("checks.autoclicker.min-std-ms", 6.0)) {
                        plugin.violations().flag(p, "autoclicker", "AutoClicker",
                                String.format(Locale.ROOT, "too consistent: mean=%.0fms std=%.1fms", mean, std));
                    }
                    d.clickIntervals.clear();
                }
            } else {
                d.clickIntervals.clear();
            }
        }
        d.lastClickMs = now;
    }

    private boolean exempt(Player p) {
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
            return true;
        }
        return plugin.recentLag() || p.hasPermission("guardac.bypass");
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double sq(double v) {
        return v * v;
    }
}
