package me.guardac;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Tracks violation levels, sends staff alerts and runs punishments. */
public final class ViolationManager {

    private final GuardAC plugin;
    private final Map<UUID, Map<String, Double>> levels = new HashMap<>();
    private final Map<String, Long> lastAlert = new HashMap<>();
    private final Set<UUID> alertsOff = new HashSet<>();

    public ViolationManager(GuardAC plugin) {
        this.plugin = plugin;
    }

    public void flag(Player player, String checkId, String display, String info) {
        double add = plugin.getConfig().getDouble("checks." + checkId + ".vl", 1.0);
        Map<String, Double> map = levels.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        double total = map.merge(checkId, add, Double::sum);

        alert(player, display, info, total);
        punish(player, checkId, total, map);
    }

    private void alert(Player player, String display, String info, double total) {
        long now = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("alerts.cooldown-ms", 750);
        String key = player.getUniqueId() + ":" + display;
        Long last = lastAlert.get(key);
        if (last != null && now - last < cooldown) {
            return;
        }
        lastAlert.put(key, now);

        String msg = ChatColor.RED + "[GuardAC] " + ChatColor.WHITE + player.getName()
                + ChatColor.GRAY + " failed " + ChatColor.RED + display
                + ChatColor.GRAY + " (" + info + ") "
                + ChatColor.DARK_GRAY + "VL " + ChatColor.RED + String.format(Locale.ROOT, "%.1f", total);

        if (plugin.getConfig().getBoolean("alerts.console", true)) {
            plugin.getLogger().info(ChatColor.stripColor(msg));
        }
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("guardac.alerts") && !alertsOff.contains(staff.getUniqueId())) {
                staff.sendMessage(msg);
            }
        }
    }

    private void punish(Player player, String checkId, double total, Map<String, Double> map) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("punishments." + checkId);
        if (section == null) {
            return;
        }
        if (total < section.getDouble("threshold", Double.MAX_VALUE)) {
            return;
        }
        map.put(checkId, 0.0);
        for (String raw : section.getStringList("commands")) {
            String command = raw
                    .replace("%player%", player.getName())
                    .replace("%check%", checkId)
                    .replace("%vl%", String.format(Locale.ROOT, "%.1f", total));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
    }

    public void startDecayTask() {
        long interval = Math.max(1L, plugin.getConfig().getLong("decay.interval-seconds", 30)) * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            double amount = plugin.getConfig().getDouble("decay.amount", 1.0);
            Iterator<Map.Entry<UUID, Map<String, Double>>> it = levels.entrySet().iterator();
            while (it.hasNext()) {
                Map<String, Double> map = it.next().getValue();
                map.replaceAll((k, v) -> Math.max(0.0, v - amount));
                map.values().removeIf(v -> v <= 0.0);
                if (map.isEmpty()) {
                    it.remove();
                }
            }
        }, interval, interval);
    }

    public Map<String, Double> get(UUID id) {
        return levels.getOrDefault(id, Map.of());
    }

    public void clear(UUID id) {
        levels.remove(id);
    }

    /** @return true if alerts are now enabled for this player. */
    public boolean toggleAlerts(UUID id) {
        if (alertsOff.remove(id)) {
            return true;
        }
        alertsOff.add(id);
        return false;
    }

    public void forget(UUID id) {
        levels.remove(id);
        String prefix = id.toString();
        lastAlert.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
