package de.muffin.universalprotect;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

public class InspectorListener implements Listener {
    private final UniversalProtect plugin;
    public InspectorListener(UniversalProtect plugin) { this.plugin = plugin; }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (!plugin.getInspectingPlayers().contains(e.getPlayer().getUniqueId())) return;
        if (e.getAction() != Action.LEFT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) { e.setCancelled(true); return; }
        
        Block b = e.getClickedBlock(); if (b == null) return;
        e.setCancelled(true);
        Block target = e.getAction() == Action.RIGHT_CLICK_BLOCK ? b.getRelative(e.getBlockFace()) : b;
        e.getPlayer().sendMessage(MiniMessage.miniMessage().deserialize(DatabaseManager.PREFIX + "<gray>Inspecting block at <aqua>" + target.getX() + "/" + target.getY() + "/" + target.getZ() + "</aqua></gray>"));
        
        plugin.getDatabaseManager().lookup(e.getPlayer(), DatabaseManager.Query.from(e.getPlayer(), java.util.Map.of("r", "0"), "0", "block"));
    }
    
    @EventHandler public void onQuit(PlayerQuitEvent e) { plugin.getInspectingPlayers().remove(e.getPlayer().getUniqueId()); }
}
