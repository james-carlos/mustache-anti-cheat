package me.guardac;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;

/** Fly, Speed, Timer and NoFall checks. */
public final class MovementChecks implements Listener {

    private static final double[] OFFSETS = {-0.31, 0.31};

    private final GuardAC plugin;
    private final PotionEffectType speedEffect = effect("speed");
    private final PotionEffectType levitation = effect("levitation");
    private final PotionEffectType slowFalling = effect("slow_falling");

    public MovementChecks(GuardAC plugin) {
        this.plugin = plugin;
    }

    private static PotionEffectType effect(String key) {
        return PotionEffectType.getByKey(NamespacedKey.minecraft(key));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || from.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return;
        }

        Player p = e.getPlayer();
        PlayerData d = plugin.data(p);
        long now = System.currentTimeMillis();
        long nowNs = System.nanoTime();

        if (isExempt(p, d, now)) {
            d.resetMovementState();
            return;
        }
        if (!to.getWorld().isChunkLoaded(to.getBlockX() >> 4, to.getBlockZ() >> 4)) {
            return;
        }

        FileConfiguration cfg = plugin.getConfig();

        // ---------------------------------------------------------------- Timer
        if (cfg.getBoolean("checks.timer.enabled", true)) {
            if (d.lastMoveNs != 0) {
                double dtMs = (nowNs - d.lastMoveNs) / 1_000_000.0;
                double clamp = cfg.getDouble("checks.timer.clamp-ms", 1000.0);
                d.timerBalance = Math.max(-clamp, d.timerBalance + 50.0 - dtMs);
                if (d.timerBalance > cfg.getDouble("checks.timer.threshold-ms", 300.0)) {
                    plugin.violations().flag(p, "timer", "Timer",
                            String.format(Locale.ROOT, "balance=%.0fms", d.timerBalance));
                    d.timerBalance = 0;
                    d.lastMoveNs = nowNs;
                    e.setCancelled(true);
                    return;
                }
            }
            d.lastMoveNs = nowNs;
        }

        boolean moved = from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ();
        if (!moved) {
            return;
        }

        long gapMs = d.lastMoveMs == 0 ? Long.MAX_VALUE : now - d.lastMoveMs;
        d.lastMoveMs = now;

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.hypot(dx, dz);

        Block fromSupport = support(from);
        Block toSupport = support(to);
        boolean fromGround = fromSupport != null;
        boolean toGround = toSupport != null;
        trackSurface(d, now, fromSupport);
        trackSurface(d, now, toSupport);

        boolean special = touchesSpecial(from) || touchesSpecial(to);
        boolean airborne = !fromGround && !toGround;
        boolean gliding = p.isGliding() || p.isRiptiding();

        if (fromGround && toGround && !special) {
            d.groundTicks++;
        } else {
            d.groundTicks = 0;
        }

        // ---------------------------------------------------------------- Speed
        if (cfg.getBoolean("checks.speed.enabled", true) && !gliding && now - d.lastIceMs > 2000) {
            double mult = speedMultiplier(p);
            double limit = cfg.getDouble("checks.speed.max-horizontal", 0.55) * mult;
            String mode = "air";
            if (d.groundTicks >= cfg.getInt("checks.speed.ground-ticks", 4)) {
                limit = Math.min(limit, cfg.getDouble("checks.speed.max-ground", 0.33) * mult);
                mode = "ground";
            }
            if (horizontal > limit) {
                double buf = d.addBuffer("speed", 1.0);
                if (buf >= cfg.getDouble("checks.speed.buffer", 4.0)) {
                    plugin.violations().flag(p, "speed", "Speed",
                            String.format(Locale.ROOT, "%.3f b/tick, max %.3f (%s)", horizontal, limit, mode));
                    d.setBuffer("speed", 0.0);
                    d.hasLastDy = false;
                    e.setCancelled(true);
                    return;
                }
            } else {
                d.lowerBuffer("speed", 0.5);
            }
        }

        // ------------------------------------------------------- Fly + NoFall
        boolean flyExempt = special || gliding || p.isSwimming()
                || hasEffect(p, levitation) || hasEffect(p, slowFalling)
                || now - d.lastBounceMs < 4500;

        if (flyExempt) {
            d.hasLastDy = false;
            d.airTicks = 0;
            d.trackedFall = 0;
            return;
        }

        if (!airborne) {
            d.airTicks = 0;
            d.trackedFall = 0;
        } else {
            d.airTicks++;
            if (dy < 0) {
                d.trackedFall += -dy;
            }

            if (cfg.getBoolean("checks.fly.enabled", true)) {
                boolean suspicious = false;
                String info = "";

                if (d.hasLastDy && gapMs <= 80) {
                    double predicted = Math.max(-3.92, (d.lastDy - 0.08) * 0.98);
                    double tolerance = cfg.getDouble("checks.fly.prediction-tolerance", 0.06);
                    if (dy > predicted + tolerance) {
                        suspicious = true;
                        info = String.format(Locale.ROOT, "dy=%.3f, max %.3f", dy, predicted + tolerance);
                    }
                }
                if (!suspicious && d.airTicks > cfg.getInt("checks.fly.hover-air-ticks", 12) && Math.abs(dy) < 0.01) {
                    suspicious = true;
                    info = "hovering for " + d.airTicks + " ticks";
                }

                if (suspicious && !standingOnEntity(p)) {
                    double buf = d.addBuffer("fly", 1.0);
                    if (buf >= cfg.getDouble("checks.fly.buffer", 3.0)) {
                        plugin.violations().flag(p, "fly", "Fly", info);
                        d.setBuffer("fly", 0.0);
                        d.hasLastDy = false;
                        e.setCancelled(true);
                        return;
                    }
                } else if (!suspicious) {
                    d.lowerBuffer("fly", 0.1);
                }
            }

            if (cfg.getBoolean("checks.nofall.enabled", true)) {
                double diff = d.trackedFall - p.getFallDistance();
                if (diff > cfg.getDouble("checks.nofall.tolerance", 4.5)) {
                    double buf = d.addBuffer("nofall", 1.0);
                    if (buf >= cfg.getDouble("checks.nofall.buffer", 2.0)) {
                        plugin.violations().flag(p, "nofall", "NoFall",
                                String.format(Locale.ROOT, "fell %.1f, server saw %.1f", d.trackedFall, p.getFallDistance()));
                        d.setBuffer("nofall", 0.0);
                        d.trackedFall = 0;
                        d.hasLastDy = false;
                        e.setCancelled(true);
                        return;
                    }
                } else {
                    d.lowerBuffer("nofall", 0.1);
                }
            }
        }

        d.lastDy = dy;
        d.hasLastDy = true;
    }

    // ------------------------------------------------------------------ helpers

    private boolean isExempt(Player p, PlayerData d, long now) {
        if (now < d.exemptUntil || plugin.recentLag()) {
            return true;
        }
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
            return true;
        }
        if (p.getAllowFlight() || p.isFlying() || p.isInsideVehicle() || p.isDead()) {
            return true;
        }
        return p.hasPermission("guardac.bypass");
    }

    private boolean hasEffect(Player p, PotionEffectType type) {
        return type != null && p.hasPotionEffect(type);
    }

    private double speedMultiplier(Player p) {
        double mult = Math.max(1.0, p.getWalkSpeed() / 0.2);
        if (speedEffect != null) {
            PotionEffect pe = p.getPotionEffect(speedEffect);
            if (pe != null) {
                mult *= 1.0 + 0.2 * (pe.getAmplifier() + 1);
            }
        }
        return mult;
    }

    private void trackSurface(PlayerData d, long now, Block support) {
        if (support == null) {
            return;
        }
        Material m = support.getType();
        switch (m) {
            case ICE, PACKED_ICE, BLUE_ICE, FROSTED_ICE, SLIME_BLOCK -> d.lastIceMs = now;
            default -> { }
        }
        if (m == Material.SLIME_BLOCK || Tag.BEDS.isTagged(m)) {
            d.lastBounceMs = now;
        }
    }

    /** The block the player is standing on (null if the player is in mid-air). */
    private Block support(Location l) {
        World w = l.getWorld();
        double x = l.getX();
        double y = l.getY();
        double z = l.getZ();

        Block b = solid(w, x, y - 0.08, z);
        if (b != null) {
            return b;
        }
        for (double ox : OFFSETS) {
            for (double oz : OFFSETS) {
                b = solid(w, x + ox, y - 0.08, z + oz);
                if (b != null) {
                    return b;
                }
            }
        }
        // fences, walls and fence gates are 1.5 blocks tall
        b = tall(w, x, y - 0.6, z);
        if (b != null) {
            return b;
        }
        for (double ox : OFFSETS) {
            for (double oz : OFFSETS) {
                b = tall(w, x + ox, y - 0.6, z + oz);
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }

    private Block solid(World w, double x, double y, double z) {
        Block b = blockIfLoaded(w, floor(x), floor(y), floor(z));
        return (b != null && !b.isPassable()) ? b : null;
    }

    private Block tall(World w, double x, double y, double z) {
        Block b = blockIfLoaded(w, floor(x), floor(y), floor(z));
        if (b == null) {
            return null;
        }
        Material m = b.getType();
        boolean isTall = Tag.FENCES.isTagged(m) || Tag.WALLS.isTagged(m) || Tag.FENCE_GATES.isTagged(m);
        return isTall ? b : null;
    }

    /** Liquids, climbables, cobwebs, honey, etc: places where normal physics don't apply. */
    private boolean touchesSpecial(Location l) {
        World w = l.getWorld();
        int x = l.getBlockX();
        int y = l.getBlockY();
        int z = l.getBlockZ();
        Block feet = blockIfLoaded(w, x, y, z);
        if (feet != null && special(feet)) {
            return true;
        }
        Block head = blockIfLoaded(w, x, y + 1, z);
        return head != null && special(head);
    }

    private boolean special(Block b) {
        Material m = b.getType();
        if (m.isAir()) {
            return false;
        }
        if (b.isLiquid()) {
            return true;
        }
        switch (m) {
            case BUBBLE_COLUMN, KELP, KELP_PLANT, SEAGRASS, TALL_SEAGRASS,
                 COBWEB, POWDER_SNOW, SWEET_BERRY_BUSH, HONEY_BLOCK -> {
                return true;
            }
            default -> { }
        }
        if (Tag.CLIMBABLE.isTagged(m)) {
            return true;
        }
        return b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged();
    }

    private boolean standingOnEntity(Player p) {
        for (Entity en : p.getNearbyEntities(1.2, 2.0, 1.2)) {
            if (en instanceof Boat || en instanceof Minecart || en instanceof Shulker) {
                return true;
            }
        }
        return false;
    }

    private Block blockIfLoaded(World w, int x, int y, int z) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }
        return w.getBlockAt(x, y, z);
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
