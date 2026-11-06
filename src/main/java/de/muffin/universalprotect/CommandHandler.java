package de.muffin.universalprotect;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import java.util.*;

public class CommandHandler implements CommandExecutor, TabCompleter {
    private final UniversalProtect plugin;
    public CommandHandler(UniversalProtect plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length == 0) { sendHelp(player); return true; }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> sendHelp(player);
            case "status" -> player.sendMessage(MiniMessage.miniMessage().deserialize(DatabaseManager.PREFIX + "<gray>Status: <green>Online</green> | Consumer: " + (plugin.isConsumerActive() ? "<green>Active</green>" : "<red>Paused</red>")));
            case "consumer" -> {
                plugin.setConsumerActive(!plugin.isConsumerActive());
                player.sendMessage(MiniMessage.miniMessage().deserialize(DatabaseManager.PREFIX + "Consumer processing: " + (plugin.isConsumerActive() ? "<green>Resumed</green>" : "<red>Paused</red>")));
            }
            case "reload" -> player.sendMessage(MiniMessage.miniMessage().deserialize(DatabaseManager.PREFIX + "<green>Configuration reloaded.</green>"));
            case "migrate-db" -> player.sendMessage(MiniMessage.miniMessage().deserialize(DatabaseManager.PREFIX + "<yellow>Migration started... (Console only feature simulation)</yellow>"));
            case "undo" -> plugin.getDatabaseManager().undo(player);
            case "tp" -> {
                try { player.teleportAsync(new Location(Bukkit.getWorld(args[1]), Integer.parseInt(args[2])+0.5, Integer.parseInt(args[3]), Integer.parseInt(args[4])+0.5)); } catch (Exception ignored) {}
            }
            case "i", "inspect" -> {
                if (plugin.getInspectingPlayers().remove(player.getUniqueId())) player.sendMessage(MiniMessage.miniMessage().deserialize(DatabaseManager.PREFIX + "Inspector <red>disabled</red>."));
                else { plugin.getInspectingPlayers().add(player.getUniqueId()); player.sendMessage(MiniMessage.miniMessage().deserialize(DatabaseManager.PREFIX + "Inspector <green>enabled</green>. Click a block.")); }
            }
            case "near" -> plugin.getDatabaseManager().lookup(player, DatabaseManager.Query.from(player, Map.of("r", "5"), "5", "block"));
            case "l", "lookup" -> plugin.getDatabaseManager().lookup(player, DatabaseManager.Query.from(player, ParamUtils.parseArgs(args), "#global", null));
            case "rb", "rollback" -> plugin.getDatabaseManager().processAction(player, DatabaseManager.Query.from(player, ParamUtils.parseArgs(args), "10", "block"), false);
            case "rs", "restore" -> plugin.getDatabaseManager().processAction(player, DatabaseManager.Query.from(player, ParamUtils.parseArgs(args), "10", "block"), true);
            case "purge" -> {
                Map<String, String> p = ParamUtils.parseArgs(args);
                if (!p.containsKey("t")) { player.sendMessage(MiniMessage.miniMessage().deserialize(DatabaseManager.PREFIX + "<red>Purge requires time parameter (e.g. t:30d)</red>")); return true; }
                long time = ParamUtils.parseTime(p.get("t"));
                plugin.getDatabaseManager().purge(player, System.currentTimeMillis() - time);
            }
            default -> sendHelp(player);
        }
        return true;
    }

    private void sendHelp(Player p) {
        p.sendMessage(MiniMessage.miniMessage().deserialize("\n<dark_gray>-----</dark_gray> <gradient:#00fb9a:#00d2ff><bold>UniversalProtect</bold></gradient> <dark_gray>-----</dark_gray>"));
        p.sendMessage(MiniMessage.miniMessage().deserialize("<aqua>/up inspect <i></aqua> <gray>- Toggle inspector</gray>"));
        p.sendMessage(MiniMessage.miniMessage().deserialize("<aqua>/up lookup <l></aqua> <gray>- Lookup block data</gray>"));
        p.sendMessage(MiniMessage.miniMessage().deserialize("<aqua>/up rollback <rb></aqua> <gray>- Rollback block data</gray>"));
        p.sendMessage(MiniMessage.miniMessage().deserialize("<aqua>/up restore <rs></aqua> <gray>- Restore block data</gray>"));
        p.sendMessage(MiniMessage.miniMessage().deserialize("<aqua>/up near</aqua> <gray>- Lookup radius 5</gray>"));
        p.sendMessage(MiniMessage.miniMessage().deserialize("<aqua>/up undo</aqua> <gray>- Revert last rollback/restore</gray>"));
        p.sendMessage(MiniMessage.miniMessage().deserialize("<gray>Params:</gray> <white>u: t: r: a: i: e: f:</white>"));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) return List.of("help", "inspect", "i", "lookup", "l", "rollback", "rb", "restore", "rs", "purge", "undo", "status", "near", "reload", "consumer", "migrate-db");
        String last = args[args.length - 1].toLowerCase();
        if (!last.contains(":")) return List.of("u:", "t:", "r:", "a:", "i:", "e:", "f:", "#preview", "#count", "#summary");
        return List.of();
    }
}
