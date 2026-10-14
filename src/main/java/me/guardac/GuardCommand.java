package me.guardac;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class GuardCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("reload", "alerts", "vl", "clear");

    private final GuardAC plugin;

    public GuardCommand(GuardAC plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "GuardAC " + ChatColor.GRAY
                    + "- /" + label + " <reload | alerts | vl <player> | clear <player>>");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            plugin.reloadConfig();
            plugin.reloadSettings();
            sender.sendMessage(ChatColor.GREEN + "GuardAC config reloaded.");
            return true;
        }

        if (sub.equals("alerts")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can toggle alerts.");
                return true;
            }
            boolean on = plugin.violations().toggleAlerts(player.getUniqueId());
            sender.sendMessage(ChatColor.GRAY + "Alerts: " + (on ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
            return true;
        }

        if (sub.equals("vl") || sub.equals("clear")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /" + label + " " + sub + " <player>");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player not online.");
                return true;
            }
            if (sub.equals("clear")) {
                plugin.violations().clear(target.getUniqueId());
                sender.sendMessage(ChatColor.GREEN + "Cleared violations for " + target.getName() + ".");
                return true;
            }
            Map<String, Double> map = plugin.violations().get(target.getUniqueId());
            if (map.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + target.getName() + " has no violations.");
            } else {
                sender.sendMessage(ChatColor.RED + target.getName() + ChatColor.GRAY + " violations:");
                map.forEach((check, vl) -> sender.sendMessage(ChatColor.GRAY + " - " + ChatColor.WHITE + check
                        + ChatColor.GRAY + ": " + ChatColor.RED + String.format(Locale.ROOT, "%.1f", vl)));
            }
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Unknown subcommand.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBCOMMANDS) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("vl") || args[0].equalsIgnoreCase("clear"))) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        }
        return out;
    }
}
