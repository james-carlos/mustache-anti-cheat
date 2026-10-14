package me.guardac;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;

/** Pauses movement checks after events that legitimately change a player's movement. */
public final class ExemptionListener implements Listener {

    private final GuardAC plugin;

    public ExemptionListener(GuardAC plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        d.exempt(plugin.getConfig().getLong("exempt.join-ms", 5000));
        d.resetMovementState();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.removeData(e.getPlayer().getUniqueId());
        plugin.violations().forget(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        d.exempt(1500);
        d.resetMovementState();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVelocity(PlayerVelocityEvent e) {
        // knockback, explosions, wind charges, etc.
        plugin.data(e.getPlayer()).exempt(2000);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        d.exempt(2000);
        d.resetMovementState();
    }

    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        d.exempt(3000);
        d.resetMovementState();
    }

    @EventHandler
    public void onToggleFlight(PlayerToggleFlightEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        d.exempt(3000);
        d.resetMovementState();
    }

    @EventHandler
    public void onRiptide(PlayerRiptideEvent e) {
        plugin.data(e.getPlayer()).exempt(6000);
    }

    @EventHandler
    public void onGlide(EntityToggleGlideEvent e) {
        if (e.getEntity() instanceof Player p) {
            plugin.data(p).exempt(3000);
        }
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent e) {
        if (e.getExited() instanceof Player p) {
            PlayerData d = plugin.data(p);
            d.exempt(1500);
            d.resetMovementState();
        }
    }
}
