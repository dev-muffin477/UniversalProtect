package de.muffin.universalprotect;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Inspector-Modus ({@code /up inspect}): Linksklick auf einen Block zeigt dessen Verlauf,
 * Rechtsklick den Verlauf der angeklickten Seite (Platzierposition).
 */
public final class InspectorListener implements Listener {
    private final UniversalProtect plugin;

    public InspectorListener(UniversalProtect plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInspect(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!plugin.getInspectingPlayers().contains(p.getUniqueId())) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.LEFT_CLICK_BLOCK && a != Action.RIGHT_CLICK_BLOCK) return;
        Block clicked = e.getClickedBlock();
        if (clicked == null) return;

        e.setCancelled(true);
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setUseItemInHand(Event.Result.DENY);

        if (!p.hasPermission("universalprotect.use")) {
            Msg.err(p, "You don't have permission.");
            return;
        }
        Block target = clicked;
        if (a == Action.RIGHT_CLICK_BLOCK) {
            BlockFace face = e.getBlockFace();
            target = clicked.getRelative(face);
        }
        plugin.db().lookup(p, Query.at(target, plugin.settings().defaultLines()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.getInspectingPlayers().remove(e.getPlayer().getUniqueId());
        plugin.getAlertPlayers().remove(e.getPlayer().getUniqueId());
    }
}
