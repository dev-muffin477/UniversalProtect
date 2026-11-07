package de.muffin.universalprotect;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Networking-API (Version 1) für Client-Mods wie Watson/ShadowTrace.
 * <ul>
 *   <li>{@code coreprotect:handshake} Client→Server: Mod-Version (UTF), Mod-ID (UTF), Protokoll (int).
 *       Server→Client: ein boolean (registriert).</li>
 *   <li>{@code coreprotect:data} Server→Client: Ergebnis-Pakete vom Typ 1–4 (siehe docs.coreprotect.net/api/networking).</li>
 * </ul>
 * Nur Spieler mit der Permission {@code universalprotect.networking} oder {@code coreprotect.networking} werden bedient.
 */
public final class NetworkHandler implements PluginMessageListener {
    public static final String HANDSHAKE = "coreprotect:handshake";
    public static final String DATA = "coreprotect:data";
    private static final int MAX_PACKETS = 200;

    private final UniversalProtect plugin;
    private final Set<UUID> registered = ConcurrentHashMap.newKeySet();

    public NetworkHandler(UniversalProtect plugin) { this.plugin = plugin; }

    public void register() {
        Messenger m = plugin.getServer().getMessenger();
        m.registerIncomingPluginChannel(plugin, HANDSHAKE, this);
        m.registerOutgoingPluginChannel(plugin, HANDSHAKE);
        m.registerOutgoingPluginChannel(plugin, DATA);
    }

    public void unregister() {
        Messenger m = plugin.getServer().getMessenger();
        m.unregisterIncomingPluginChannel(plugin);
        m.unregisterOutgoingPluginChannel(plugin);
        registered.clear();
    }

    public void forget(UUID id) { registered.remove(id); }

    private static boolean allowed(Player p) {
        return p.hasPermission("universalprotect.networking") || p.hasPermission("coreprotect.networking");
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte[] message) {
        if (!HANDSHAKE.equals(channel) || !allowed(player)) return;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            String modVersion = in.readUTF();
            String modId = in.readUTF();
            int protocol = in.readInt();
            registered.add(player.getUniqueId());
            plugin.getLogger().fine(player.getName() + " registered networking client " + modId + " " + modVersion + " (protocol " + protocol + ")");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bos)) { out.writeBoolean(true); }
            player.sendPluginMessage(plugin, HANDSHAKE, bos.toByteArray());
        } catch (IOException ignored) {
            // ungültiges Paket: ignorieren
        }
    }

    /** Sendet Lookup-Ergebnisse als Datenpakete an registrierte Clients (von jedem Thread aufrufbar). */
    public void sendResults(Player player, List<LogRow> rows) {
        if (!plugin.settings().networking() || !registered.contains(player.getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            int sent = 0;
            for (LogRow r : rows) {
                if (sent++ >= MAX_PACKETS) break;
                try { player.sendPluginMessage(plugin, DATA, packet(r)); } catch (IOException ignored) { /* überspringen */ }
            }
        });
    }

    private static byte[] packet(LogRow r) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            String a = r.action() == null ? "" : r.action();
            String user = r.player() == null ? "" : r.player();
            String world = r.world() == null ? "" : r.world();
            String target = r.data() == null ? "" : r.data();
            boolean add = Actions.PLACED.equals(a) || Actions.PUT.equals(a) || Actions.INV_ADD.equals(a) || Actions.SPAWN.equals(a)
                    || Actions.ITEM_ADD.contains(a);
            String selector = add ? "FIRST" : "SECOND";

            if (Actions.BLOCK.contains(a) || Actions.CONTAINER.contains(a)) {
                out.writeInt(1);
                out.writeLong(r.time());
                out.writeUTF(selector);
                out.writeUTF(user);
                out.writeUTF(Actions.BLOCK.contains(a) ? Msg.cleanBlock(target) : ItemSerializer.materialOf(target));
                out.writeInt(Math.max(1, r.amount()));
                out.writeInt(r.x()); out.writeInt(r.y()); out.writeInt(r.z());
                out.writeUTF(world);
                out.writeBoolean(r.rolledBack());
                out.writeBoolean(Actions.CONTAINER.contains(a));
                out.writeBoolean(add);
            } else if (Actions.TEXT.contains(a)) {
                out.writeInt(3);
                out.writeLong(r.time());
                out.writeUTF(user);
                out.writeUTF(target);
                out.writeBoolean(Actions.SIGN.equals(a));
                out.writeInt(r.x()); out.writeInt(r.y()); out.writeInt(r.z());
                out.writeUTF(world);
            } else if (Actions.JOIN.equals(a) || Actions.QUIT.equals(a) || Actions.USERNAME.equals(a)) {
                out.writeInt(4);
                out.writeLong(r.time());
                out.writeUTF(user);
                out.writeUTF(Actions.JOIN.equals(a) ? "login" : Actions.QUIT.equals(a) ? "logout" : target);
            } else {
                out.writeInt(2);
                out.writeLong(r.time());
                out.writeUTF(selector);
                out.writeUTF(user);
                out.writeUTF(ItemSerializer.materialOf(target).toLowerCase());
                out.writeInt(Math.max(1, r.amount()));
                out.writeInt(r.x()); out.writeInt(r.y()); out.writeInt(r.z());
                out.writeUTF(world);
            }
        }
        return bos.toByteArray();
    }
}
