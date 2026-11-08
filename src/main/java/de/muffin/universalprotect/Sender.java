package de.muffin.universalprotect;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Absender eines Befehls (Spieler oder Konsole). Ersetzt Bukkits CommandSender. */
public final class Sender {
    public static final UUID CONSOLE_KEY = new UUID(0L, 0L);

    private final CommandSourceStack src;

    public Sender(CommandSourceStack src) { this.src = src; }

    public static Sender console() { return new Sender(Mc.server().createCommandSourceStack()); }

    public CommandSourceStack source() { return src; }

    /** Der Spieler oder null (Konsole, Befehlsblock, RCON). */
    public ServerPlayer player() { return src.getEntity() instanceof ServerPlayer p ? p : null; }

    public boolean isPlayer() { return player() != null; }

    public UUID key() { ServerPlayer p = player(); return p == null ? CONSOLE_KEY : p.getUUID(); }

    public String name() { ServerPlayer p = player(); return p == null ? "Console" : p.getName().getString(); }

    public boolean hasPermission(String node) { return Perm.has(src, node); }

    public void sendMessage(Component c) { src.sendSystemMessage(c); }

    public void sendMini(String mini) { sendMessage(Mini.parse(mini)); }

    /** Schickt die Nachricht über den Hauptthread (von jedem Thread aufrufbar). */
    public void sendMiniSafe(String mini) { Sched.main(() -> sendMini(mini)); }
}
