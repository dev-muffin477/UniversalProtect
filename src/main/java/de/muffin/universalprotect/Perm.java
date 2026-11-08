package de.muffin.universalprotect;

import net.minecraft.commands.CommandSourceStack;

import java.lang.reflect.Method;

/**
 * Rechteprüfung: (1) fabric-permissions-api / LuckPerms, falls installiert, (2) Vanilla-Operator (Stufe 2).
 * Die Konsole darf immer. Reflexion hält den Code unabhängig von der Rechte-API der jeweiligen Minecraft-Version.
 */
public final class Perm {
    private Perm() {}

    public static boolean has(CommandSourceStack src, String node) {
        if (src.getEntity() == null) return true;
        Boolean api = fabricApi(src, node);
        if (api != null) return api;
        return vanilla(src);
    }

    private static Boolean fabricApi(CommandSourceStack src, String node) {
        try {
            Class<?> c = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
            for (Method m : c.getMethods()) {
                if (m.getName().equals("getPermissionValue") && m.getParameterCount() == 2) {
                    Object tri = m.invoke(null, src, node);
                    String s = String.valueOf(tri);
                    if (s.equals("TRUE")) return true;
                    if (s.equals("FALSE")) return false;
                    return null;
                }
            }
        } catch (ClassNotFoundException ignored) {
            // nicht installiert
        } catch (Throwable ignored) {
            // API anders als erwartet: auf Vanilla zurückfallen
        }
        return null;
    }

    private static boolean vanilla(CommandSourceStack src) {
        try {
            Method m = src.getClass().getMethod("hasPermission", int.class);
            return (boolean) m.invoke(src, 2);
        } catch (NoSuchMethodException ignored) {
            // neueres Rechtesystem
        } catch (Throwable t) {
            return false;
        }
        try {
            Object perms = src.getClass().getMethod("permissions").invoke(src);
            Class<?> pc = Class.forName("net.minecraft.server.permissions.Permissions");
            Object gm = pc.getField("COMMANDS_GAMEMASTER").get(null);
            for (Method m : perms.getClass().getMethods()) {
                if (m.getName().equals("hasPermission") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isInstance(gm)) {
                    return (boolean) m.invoke(perms, gm);
                }
            }
        } catch (Throwable ignored) {
            // unbekannt
        }
        return false;
    }
}
