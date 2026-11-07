package de.muffin.universalprotect;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Speichert und belebt Entities samt Metadaten wieder.
 * <ol>
 *   <li>{@code nbt:} – vollständiger Entity-NBT über Paper's Entity-Serialisierung (per Reflection, daher versionssicher)</li>
 *   <li>{@code yml:} – Fallback über die Bukkit-API (Name, Gesundheit, Alter, Ausrüstung, Villager inkl. Handel)</li>
 * </ol>
 */
public final class EntitySerializer {
    private EntitySerializer() {}

    private static volatile Method serializeMethod;
    private static volatile Method deserializeMethod;
    private static volatile boolean resolved;

    @SuppressWarnings("deprecation")
    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            for (Method m : org.bukkit.UnsafeValues.class.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("serializeEntity") && p.length >= 1 && p[0] == Entity.class) serializeMethod = m;
                if (m.getName().equals("deserializeEntity") && p.length >= 2 && p[0] == byte[].class && p[1] == World.class
                        && (deserializeMethod == null || p.length == 3)) deserializeMethod = m;
            }
        } catch (Throwable ignored) { /* Fallback auf YAML */ }
    }

    // ------------------------------------------------------------------ Serialisieren (Hauptthread)

    @SuppressWarnings("deprecation")
    public static String serialize(Entity e) {
        resolve();
        if (serializeMethod != null) {
            try {
                Class<?>[] p = serializeMethod.getParameterTypes();
                Object[] args = new Object[p.length];
                args[0] = e;
                for (int i = 1; i < p.length; i++) {
                    args[i] = p[i].isArray() ? Array.newInstance(p[i].getComponentType(), 0) : null;
                }
                byte[] bytes = (byte[]) serializeMethod.invoke(Bukkit.getUnsafe(), args);
                if (bytes != null && bytes.length > 0) return "nbt:" + Base64.getEncoder().encodeToString(bytes);
            } catch (Throwable ignored) { /* Fallback */ }
        }
        try {
            return "yml:" + Base64.getEncoder().encodeToString(toYaml(e).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String toYaml(Entity e) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("type", e.getType().name());
        Component name = e.customName();
        if (name != null) y.set("name", GsonComponentSerializer.gson().serialize(name));
        if (e instanceof LivingEntity le) {
            y.set("health", Math.max(1.0, le.getHealth()));
            EntityEquipment eq = le.getEquipment();
            if (eq != null) {
                y.set("eq.head", eq.getHelmet());
                y.set("eq.chest", eq.getChestplate());
                y.set("eq.legs", eq.getLeggings());
                y.set("eq.feet", eq.getBoots());
                y.set("eq.main", eq.getItemInMainHand());
                y.set("eq.off", eq.getItemInOffHand());
            }
        }
        if (e instanceof Ageable a) y.set("age", a.getAge());
        if (e instanceof Villager v) {
            y.set("v.profession", keyOf(v.getProfession()));
            y.set("v.type", keyOf(v.getVillagerType()));
            y.set("v.level", v.getVillagerLevel());
            y.set("v.xp", v.getVillagerExperience());
            List<Map<String, Object>> recipes = new ArrayList<>();
            for (MerchantRecipe r : v.getRecipes()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("result", r.getResult());
                m.put("ingredients", new ArrayList<>(r.getIngredients()));
                m.put("uses", r.getUses());
                m.put("maxUses", r.getMaxUses());
                m.put("xpReward", r.hasExperienceReward());
                m.put("villagerXp", r.getVillagerExperience());
                m.put("mult", (double) r.getPriceMultiplier());
                recipes.add(m);
            }
            y.set("v.recipes", recipes);
        }
        return y.saveToString();
    }

    private static String keyOf(Object keyed) {
        try { return ((Keyed) keyed).getKey().getKey(); } catch (Throwable t) { return null; }
    }

    // ------------------------------------------------------------------ Wiederbeleben (Hauptthread)

    /** Belebt ein gespeichertes Entity an der Position wieder und gibt es zurück (oder null). */
    @SuppressWarnings({"deprecation", "unchecked"})
    public static Entity respawn(World world, double x, double y, double z, String data) {
        if (data == null) return null;
        Location loc = new Location(world, x, y, z);
        Entity result = null;
        try {
            if (data.startsWith("nbt:")) {
                resolve();
                if (deserializeMethod != null) {
                    byte[] bytes = Base64.getDecoder().decode(data.substring(4));
                    Object[] args = deserializeMethod.getParameterCount() == 3 ? new Object[]{bytes, world, Boolean.TRUE} : new Object[]{bytes, world};
                    Entity ent = (Entity) deserializeMethod.invoke(Bukkit.getUnsafe(), args);
                    result = place(ent, world, loc);
                }
            } else if (data.startsWith("yml:")) {
                result = fromYaml(new String(Base64.getDecoder().decode(data.substring(4)), StandardCharsets.UTF_8), loc);
            }
        } catch (Throwable t) {
            return null;
        }
        if (result instanceof LivingEntity le && le.getHealth() <= 0) {
            try { le.setHealth(maxHealth(le)); } catch (Throwable ignored) { /* best effort */ }
        }
        return result;
    }

    /** Fügt ein deserialisiertes Entity der Welt hinzu. */
    private static Entity place(Entity ent, World world, Location loc) throws Exception {
        if (ent == null) return null;
        try {
            Method spawnAt = Entity.class.getMethod("spawnAt", Location.class);
            spawnAt.invoke(ent, loc);
        } catch (NoSuchMethodException nsme) {
            Method add = World.class.getMethod("addEntity", Entity.class);
            add.invoke(world, ent);
        }
        return ent;
    }

    @SuppressWarnings("unchecked")
    private static Entity fromYaml(String yaml, Location loc) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(yaml);
        EntityType type = EntityType.valueOf(y.getString("type", ""));
        Entity e = loc.getWorld().spawnEntity(loc, type);
        String name = y.getString("name");
        if (name != null) e.customName(GsonComponentSerializer.gson().deserialize(name));
        if (e instanceof LivingEntity le) {
            if (y.isSet("health")) { try { le.setHealth(Math.min(maxHealth(le), y.getDouble("health"))); } catch (Throwable ignored) { /* ok */ } }
            EntityEquipment eq = le.getEquipment();
            if (eq != null) {
                eq.setHelmet(y.getItemStack("eq.head"));
                eq.setChestplate(y.getItemStack("eq.chest"));
                eq.setLeggings(y.getItemStack("eq.legs"));
                eq.setBoots(y.getItemStack("eq.feet"));
                eq.setItemInMainHand(y.getItemStack("eq.main"));
                eq.setItemInOffHand(y.getItemStack("eq.off"));
            }
        }
        if (e instanceof Ageable a && y.isSet("age")) a.setAge(y.getInt("age"));
        if (e instanceof Villager v && y.isSet("v.level")) {
            Object prof = constant(Villager.Profession.class, y.getString("v.profession"));
            Object vt = constant(Villager.Type.class, y.getString("v.type"));
            if (prof != null) v.setProfession((Villager.Profession) prof);
            if (vt != null) v.setVillagerType((Villager.Type) vt);
            v.setVillagerLevel(Math.max(1, y.getInt("v.level", 1)));
            v.setVillagerExperience(y.getInt("v.xp", 0));
            List<MerchantRecipe> recipes = new ArrayList<>();
            List<?> raw = y.getList("v.recipes");
            if (raw != null) {
                for (Object o : raw) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    ItemStack result = (ItemStack) m.get("result");
                    if (result == null) continue;
                    MerchantRecipe r = new MerchantRecipe(result, num(m.get("uses")), Math.max(1, num(m.get("maxUses"))),
                            Boolean.TRUE.equals(m.get("xpReward")), num(m.get("villagerXp")),
                            m.get("mult") instanceof Number n ? n.floatValue() : 0.0f);
                    List<ItemStack> ingredients = new ArrayList<>();
                    if (m.get("ingredients") instanceof List<?> l) for (Object i : l) if (i instanceof ItemStack is) ingredients.add(is);
                    r.setIngredients(ingredients);
                    recipes.add(r);
                }
            }
            v.setRecipes(recipes);
        }
        return e;
    }

    private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }

    /** Liest eine öffentliche statische Konstante (Enum-Wert oder Registry-Konstante) per Namen. */
    private static Object constant(Class<?> cls, String key) {
        if (key == null) return null;
        try { return cls.getField(key.toUpperCase(Locale.ROOT)).get(null); } catch (Throwable t) { return null; }
    }

    /** Entfernt ein Entity anhand seiner UUID (Hauptthread). */
    public static boolean removeByUuid(World world, int x, int z, String uuid) {
        try {
            world.getChunkAt(x >> 4, z >> 4);
            Entity e = Bukkit.getEntity(UUID.fromString(uuid));
            if (e == null || e instanceof org.bukkit.entity.Player) return false;
            e.remove();
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /** Maximale Lebenspunkte über das Attribut (getMaxHealth ist veraltet). */
    private static double maxHealth(org.bukkit.entity.LivingEntity le) {
        org.bukkit.attribute.AttributeInstance a = le.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        return a != null ? a.getValue() : Math.max(1.0, le.getHealth());
    }
}
