package de.muffin.universalprotect;

import java.util.*;

public final class Actions {
    public static final String PLACED = "placed";
    public static final String BROKE = "broke";
    public static final String PUT = "put";
    public static final String TAKE = "take";
    public static final String INV_ADD = "inv_add";
    public static final String INV_REMOVE = "inv_remove";
    public static final String DROP = "drop";
    public static final String PICKUP = "pickup";
    public static final String THROW = "throw";
    public static final String SHOOT = "shoot";
    public static final String TOOL_BREAK = "tool_break";
    public static final String DESTROY = "destroy";
    public static final String CREATE = "create";
    public static final String SELL = "sell";
    public static final String BUY = "buy";
    public static final String ENDER_ADD = "ender_add";
    public static final String ENDER_REMOVE = "ender_remove";
    public static final String SIGN = "sign";
    public static final String USE = "use";
    public static final String FRAME = "frame";
    public static final String KILL = "kill";
    public static final String SPAWN = "spawn";
    public static final String DEATH = "death";
    public static final String CHAT = "chat";
    public static final String COMMAND = "command";
    public static final String JOIN = "join";
    public static final String QUIT = "quit";
    public static final String USERNAME = "username";
    public static final String AGG_BROKE = "agg_broke";
    public static final String AGG_PLACED = "agg_placed";
    public static final String TELEPORT = "teleport";
    public static final String STAFF = "staff";

    public static final Set<String> BLOCK = linked(PLACED, BROKE);
    public static final Set<String> CONTAINER = linked(PUT, TAKE);
    public static final Set<String> INVENTORY = linked(INV_ADD, INV_REMOVE);
    public static final Set<String> ITEM_ADD = linked(PICKUP, CREATE, BUY, ENDER_REMOVE);
    public static final Set<String> ITEM_REMOVE = linked(DROP, THROW, SHOOT, TOOL_BREAK, DESTROY, SELL, ENDER_ADD);
    public static final Set<String> ITEM_ALL = union(ITEM_ADD, ITEM_REMOVE);
    public static final Set<String> TEXT = linked(CHAT, COMMAND, SIGN);
    public static final Set<String> ROLLBACKABLE = union(BLOCK, CONTAINER, INVENTORY, linked(KILL, SPAWN));

    public static final Set<String> ALL = union(BLOCK, CONTAINER, INVENTORY, ITEM_ALL,
            linked(SIGN, USE, FRAME, KILL, SPAWN, DEATH, CHAT, COMMAND, JOIN, QUIT, USERNAME, AGG_BROKE, AGG_PLACED, TELEPORT, STAFF));

    private Actions() {}

    private static Set<String> linked(String... s) { return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(s))); }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        Set<String> out = new LinkedHashSet<>();
        for (Set<String> s : sets) out.addAll(s);
        return Collections.unmodifiableSet(out);
    }

    public static final List<String> USER_NAMES = List.of(
            "block", "+block", "-block", "chat", "click", "command", "sign",
            "container", "+container", "-container", "inventory", "+inventory", "-inventory",
            "item", "+item", "-item", "drop", "pickup", "throw", "shoot", "destroy", "create", "sell", "buy",
            "kill", "spawn", "session", "+session", "-session", "username", "frame", "death", "agg", "teleport", "staff");

    public static Set<String> expand(String token) {
        String a = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
        return switch (a) {
            case "" -> Set.of();
            case "+", "+block", "placed", "place" -> linked(PLACED);
            case "-", "-block", "broke", "break" -> linked(BROKE);
            case "block" -> BLOCK;
            case "+container", "put" -> linked(PUT);
            case "-container", "take" -> linked(TAKE);
            case "container" -> CONTAINER;
            case "+inventory", "+inv" -> linked(INV_ADD);
            case "-inventory", "-inv" -> linked(INV_REMOVE);
            case "inventory", "inv" -> INVENTORY;
            case "+item" -> ITEM_ADD;
            case "-item" -> ITEM_REMOVE;
            case "item" -> ITEM_ALL;
            case "drop" -> linked(DROP);
            case "pickup" -> linked(PICKUP);
            case "throw" -> linked(THROW);
            case "shoot" -> linked(SHOOT);
            case "destroy" -> linked(DESTROY);
            case "create" -> linked(CREATE);
            case "sell" -> linked(SELL);
            case "buy" -> linked(BUY);
            case "sign" -> linked(SIGN);
            case "frame" -> linked(FRAME);
            case "use", "click", "interact" -> linked(USE);
            case "kill" -> linked(KILL);
            case "spawn" -> linked(SPAWN);
            case "death" -> linked(DEATH);
            case "chat" -> linked(CHAT);
            case "command" -> linked(COMMAND);
            case "+session", "join", "login" -> linked(JOIN);
            case "-session", "quit", "logout" -> linked(QUIT);
            case "session" -> linked(JOIN, QUIT);
            case "username" -> linked(USERNAME);
            case "teleport", "tp" -> linked(TELEPORT);
            case "staff", "audit" -> linked(STAFF);
            case "agg", "aggregated" -> linked(AGG_BROKE, AGG_PLACED);
            case "inspect" -> linked(PLACED, BROKE, PUT, TAKE, SIGN, USE, FRAME);
            default -> linked(a);
        };
    }

    public static Set<String> expandAll(String csv) {
        if (csv == null || csv.isBlank()) return null;
        Set<String> out = new LinkedHashSet<>();
        for (String part : csv.split(",")) out.addAll(expand(part));
        return out.isEmpty() ? null : out;
    }

    public static int cpId(String action) {
        if (action == null) return -1;
        return switch (action) {
            case BROKE -> 0;
            case PLACED -> 1;
            case USE -> 2;
            case KILL -> 3;
            case PUT, TAKE -> 4;
            case CHAT -> 6;
            case COMMAND -> 7;
            case JOIN, QUIT -> 8;
            case USERNAME -> 9;
            case SIGN -> 10;
            case SPAWN -> 13;
            default -> ITEM_ALL.contains(action) ? 11 : (INVENTORY.contains(action) ? 12 : -1);
        };
    }

    public static Set<String> fromCpId(int id) {
        return switch (id) {
            case 0 -> linked(BROKE);
            case 1 -> linked(PLACED);
            case 2 -> linked(USE);
            case 3 -> linked(KILL);
            case 4 -> CONTAINER;
            case 6 -> linked(CHAT);
            case 7 -> linked(COMMAND);
            case 8 -> linked(JOIN, QUIT);
            case 9 -> linked(USERNAME);
            case 10 -> linked(SIGN);
            case 11 -> ITEM_ALL;
            case 12 -> INVENTORY;
            case 13 -> linked(SPAWN);
            default -> Set.of();
        };
    }

    public static boolean isBlockAction(String a) { return PLACED.equals(a) || BROKE.equals(a); }

    public static String blockId(String data) {
        if (data == null) return "";
        int b = data.indexOf('[');
        String id = (b >= 0 ? data.substring(0, b) : data).toLowerCase(Locale.ROOT);
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }
}
