package me.minestorm.altingsystem.commands;

import me.minestorm.altingsystem.MineStormAltingSystemPlugin;
import me.minestorm.altingsystem.managers.SensitivityManager;
import me.minestorm.altingsystem.util.ChatTheme;
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

public class AltCommand implements CommandExecutor, TabCompleter {

    private final MineStormAltingSystemPlugin plugin;
    private final SensitivityManager manager;

    public AltCommand(MineStormAltingSystemPlugin plugin, SensitivityManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("minestorm.alt")) {
            sender.sendMessage(ChatTheme.error("You do not have permission to use this command."));
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(ChatTheme.PREFIX + ChatColor.GRAY + "Usage: " + ChatColor.AQUA + "/alt <player>");
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        String displayName = target != null ? target.getName() : args[0];
        boolean online = target != null;
        boolean live = false;
        String hSens = null;
        String vSens = null;

        // Online player: prefer fresh data straight from Grim
        if (online && plugin.isTrackingEnabled()) {
            String[] sens = plugin.getGrimSensitivity(target);
            if (sens != null && manager.isValid(sens[0]) && manager.isValid(sens[1])) {
                hSens = sens[0];
                vSens = sens[1];
                live = true;
                manager.saveSensitivity(target.getUniqueId(), target.getName(), hSens, vSens);
            }
        }

        // Fall back to stored data (offline players, or Grim has nothing yet)
        if (hSens == null) {
            String[] cached = manager.getOfflineSensitivity(displayName);
            if (cached != null) {
                hSens = cached[0];
                vSens = cached[1];
            }
        }

        if (hSens == null) {
            if (online) {
                sender.sendMessage(ChatTheme.error(displayName + " has no valid sensitivity yet"
                        + (plugin.isTrackingEnabled()
                        ? " - GrimAC may still be calculating it. Try again shortly."
                        : " and GrimAC tracking is currently disabled.")));
            } else {
                sender.sendMessage(ChatTheme.error(displayName + " is offline and has no saved sensitivity data."));
            }
            return true;
        }

        List<String> alts = manager.findAlts(hSens, vSens, displayName);

        ChatTheme.sendHeader(sender, "ALT CHECK", displayName + " " + ChatTheme.tagOnline(online));
        ChatTheme.sendField(sender, "Horizontal", hSens);
        ChatTheme.sendField(sender, "Vertical", vSens);
        ChatTheme.sendField(sender, "Source", live
                ? ChatColor.GREEN + "Live (GrimAC)"
                : ChatColor.GRAY + "Saved data");
        sender.sendMessage("");

        if (alts.isEmpty()) {
            sender.sendMessage("  " + ChatTheme.tagClean() + ChatColor.GRAY + " No matching accounts found.");
        } else {
            sender.sendMessage("  " + ChatTheme.tagAlt(alts.size()));
            sender.sendMessage("  " + ChatColor.AQUA + "Matches" + ChatColor.DARK_GRAY + ": "
                    + ChatTheme.formatAltNames(alts));
        }
        ChatTheme.sendFooter(sender);

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1 && sender.hasPermission("minestorm.alt")) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    completions.add(p.getName());
                }
            }
        }
        return completions;
    }
}
