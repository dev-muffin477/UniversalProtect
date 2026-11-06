package de.muffin.universalprotect;

import org.bukkit.plugin.java.JavaPlugin;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class UniversalProtect extends JavaPlugin {
    private final Set<UUID> inspectingPlayers = ConcurrentHashMap.newKeySet();
    private DatabaseManager databaseManager;
    private boolean consumerActive = true;

    @Override
    public void onEnable() {
        try {
            this.databaseManager = new DatabaseManager(this);
            CommandHandler cmdHandler = new CommandHandler(this);
            getCommand("up").setExecutor(cmdHandler);
            getCommand("up").setTabCompleter(cmdHandler);

            getServer().getPluginManager().registerEvents(new InspectorListener(this), this);
            getServer().getPluginManager().registerEvents(new ActionLogger(this), this);

            getLogger().info("UniversalProtect v1.3.0 enabled successfully!");
        } catch (Throwable t) {
            getLogger().severe("CRITICAL ERROR starting UniversalProtect:");
            t.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        inspectingPlayers.clear();
        if (databaseManager != null) databaseManager.close();
    }

    public Set<UUID> getInspectingPlayers() { return inspectingPlayers; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public boolean isConsumerActive() { return consumerActive; }
    public void setConsumerActive(boolean active) { this.consumerActive = active; }
}
