package de.muffin.universalprotect;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.io.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class NetworkHandler {
    public static final String HANDSHAKE = "coreprotect:handshake";
    public static final String DATA = "coreprotect:data";
    private static final int MAX_PACKETS = 200;

    public record Raw(CustomPacketPayload.Type<Raw> type, byte[] data) implements CustomPacketPayload {}

    private static final CustomPacketPayload.Type<Raw> T_HANDSHAKE = new CustomPacketPayload.Type<>(Identifier.parse(HANDSHAKE));
    private static final CustomPacketPayload.Type<Raw> T_DATA = new CustomPacketPayload.Type<>(Identifier.parse(DATA));

    private static StreamCodec<FriendlyByteBuf, Raw> codec(CustomPacketPayload.Type<Raw> t) {
        return StreamCodec.of(
                (buf, raw) -> buf.writeBytes(raw.data()),
                buf -> {
                    byte[] b = new byte[buf.readableBytes()];
                    buf.readBytes(b);
                    return new Raw(t, b);
                });
    }

    private final UniversalProtect plugin;
    private final Set<UUID> registered = ConcurrentHashMap.newKeySet();

    public NetworkHandler(UniversalProtect plugin) { this.plugin = plugin; }

    public void register() {
        PayloadTypeRegistry.serverboundPlay().register(T_HANDSHAKE, codec(T_HANDSHAKE));
        PayloadTypeRegistry.clientboundPlay().register(T_HANDSHAKE, codec(T_HANDSHAKE));
        PayloadTypeRegistry.clientboundPlay().register(T_DATA, codec(T_DATA));
        ServerPlayNetworking.registerGlobalReceiver(T_HANDSHAKE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> onHandshake(player, payload.data()));
        });
    }

    public void forget(UUID id) { registered.remove(id); }

    private static boolean allowed(ServerPlayer p) {
        var src = p.createCommandSourceStack();
        return Perm.has(src, "universalprotect.networking") || Perm.has(src, "coreprotect.networking");
    }

    private void onHandshake(ServerPlayer player, byte[] message) {
        if (!plugin.settings().networking() || !allowed(player)) return;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            String modVersion = in.readUTF();
            String modId = in.readUTF();
            int protocol = in.readInt();
            registered.add(player.getUUID());
            plugin.getLogger().fine(Mc.name(player) + " registered networking client " + modId + " " + modVersion + " (protocol " + protocol + ")");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bos)) { out.writeBoolean(true); }
            ServerPlayNetworking.send(player, new Raw(T_HANDSHAKE, bos.toByteArray()));
        } catch (IOException ignored) {
        }
    }

    public void sendResults(ServerPlayer player, List<LogRow> rows) {
        if (!plugin.settings().networking() || !registered.contains(player.getUUID())) return;
        Sched.main(() -> {
            int sent = 0;
            for (LogRow r : rows) {
                if (sent++ >= MAX_PACKETS) break;
                try { ServerPlayNetworking.send(player, new Raw(T_DATA, packet(r))); } catch (IOException ignored) {  }
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
                out.writeUTF(ItemSerializer.materialOf(target).toLowerCase(java.util.Locale.ROOT));
                out.writeInt(Math.max(1, r.amount()));
                out.writeInt(r.x()); out.writeInt(r.y()); out.writeInt(r.z());
                out.writeUTF(world);
            }
        }
        return bos.toByteArray();
    }
}
