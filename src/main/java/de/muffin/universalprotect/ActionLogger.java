package de.muffin.universalprotect;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.block.Block;

public class ActionLogger implements Listener {
    private final UniversalProtect plugin;
    public ActionLogger(UniversalProtect plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        plugin.getDatabaseManager().logAction("block", e.getPlayer().getName(), "broke", b.getBlockData().getAsString(), b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), b.getBlockData().getAsString(), BlockExtras.capture(b.getState()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlock();
        plugin.getDatabaseManager().logAction("block", e.getPlayer().getName(), "placed", b.getBlockData().getAsString(), b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), e.getBlockReplacedState().getBlockData().getAsString(), null);
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        plugin.getDatabaseManager().logAction("session", e.getPlayer().getName(), "join", "Login", e.getPlayer().getWorld().getName(), e.getPlayer().getLocation().getBlockX(), e.getPlayer().getLocation().getBlockY(), e.getPlayer().getLocation().getBlockZ(), null, null);
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        plugin.getDatabaseManager().logAction("session", e.getPlayer().getName(), "quit", "Logout", e.getPlayer().getWorld().getName(), e.getPlayer().getLocation().getBlockX(), e.getPlayer().getLocation().getBlockY(), e.getPlayer().getLocation().getBlockZ(), null, null);
    }
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        plugin.getDatabaseManager().logAction("chat", e.getPlayer().getName(), "chat", PlainTextComponentSerializer.plainText().serialize(e.message()), e.getPlayer().getWorld().getName(), e.getPlayer().getLocation().getBlockX(), e.getPlayer().getLocation().getBlockY(), e.getPlayer().getLocation().getBlockZ(), null, null);
    }
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        plugin.getDatabaseManager().logAction("command", e.getPlayer().getName(), "command", e.getMessage(), e.getPlayer().getWorld().getName(), e.getPlayer().getLocation().getBlockX(), e.getPlayer().getLocation().getBlockY(), e.getPlayer().getLocation().getBlockZ(), null, null);
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent e) {
        if (e.getEntity().getKiller() != null) {
            plugin.getDatabaseManager().logAction("kill", e.getEntity().getKiller().getName(), "kill", e.getEntity().getType().name(), e.getEntity().getWorld().getName(), e.getEntity().getLocation().getBlockX(), e.getEntity().getLocation().getBlockY(), e.getEntity().getLocation().getBlockZ(), null, null);
        }
    }
}
