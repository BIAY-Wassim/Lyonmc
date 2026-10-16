package fr.lyonmc.achievements;

import fr.lyonmc.achievements.board.BoardManager;
import fr.lyonmc.achievements.command.SuccesCommand;
import fr.lyonmc.achievements.data.AdvancementRegistry;
import fr.lyonmc.achievements.data.StatsService;
import fr.lyonmc.achievements.listener.AdvancementListener;
import fr.lyonmc.achievements.listener.ChatListener;
import fr.lyonmc.achievements.listener.MotdListener;
import fr.lyonmc.achievements.menu.HeadService;
import fr.lyonmc.achievements.menu.MenuListener;
import fr.lyonmc.achievements.menu.MissionsMenu;
import fr.lyonmc.achievements.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;

public final class LyonAchievements extends JavaPlugin {

    private AdvancementRegistry registry;
    private StatsService stats;
    private BoardManager boards;
    private Text text;
    private ChatListener chat;
    private MotdListener motd;
    private HeadService heads;
    private BukkitTask headsTask;
    private BukkitTask motdTask;

    private BukkitTask refreshTask;
    private BukkitTask autosaveTask;

    @Override
    public void onEnable() {
        migrateConfig();
        saveDefaultConfig();

        this.text = new Text(this);
        this.registry = new AdvancementRegistry(this);
        this.registry.load();

        this.stats = new StatsService(this, registry);
        this.stats.load();

        this.boards = new BoardManager(this, registry, stats, text);

        this.chat = new ChatListener(this, registry, stats, text);
        this.heads = new HeadService(this);
        for (Player p : Bukkit.getOnlinePlayers()) heads.remember(p);
        MissionsMenu menu = new MissionsMenu(registry, stats, text, heads);

        getServer().getPluginManager().registerEvents(new AdvancementListener(this, registry, stats, boards, text), this);
        getServer().getPluginManager().registerEvents(chat, this);
        getServer().getPluginManager().registerEvents(new MenuListener(this), this);
        getServer().getPluginManager().registerEvents(heads, this);
        this.motd = new MotdListener(this, registry, stats, text);
        getServer().getPluginManager().registerEvents(motd, this);

        SuccesCommand command = new SuccesCommand(this, registry, stats, boards, menu, text);
        for (String name : new String[]{"succes", "missions", "classement", "carte"}) {
            PluginCommand pc = getCommand(name);
            if (pc != null) {
                pc.setExecutor(command);
                pc.setTabCompleter(command);
            }
        }

        // Joueurs déjà connectés (cas d'un /reload)
        for (Player p : Bukkit.getOnlinePlayers()) {
            stats.startSession(p.getUniqueId());
            stats.refreshOnline(p);
            boards.create(p);
        }
        stats.rebuildRanking();

        if (getConfig().getBoolean("settings.import-on-startup", true)) {
            stats.importFromWorld(count -> getLogger().info("Import terminé : " + count + " fichiers de succès analysés."));
        }

        startTasks();

        getLogger().info("LyonAchievements activé — " + registry.total() + " succès comptabilisés.");
    }

    @Override
    public void onDisable() {
        if (refreshTask != null) refreshTask.cancel();
        if (autosaveTask != null) autosaveTask.cancel();
        if (motdTask != null) motdTask.cancel();
        if (headsTask != null) headsTask.cancel();
        if (boards != null) boards.removeAll();
        if (stats != null) stats.saveSync();
    }

    public void startTasks() {
        if (refreshTask != null) refreshTask.cancel();
        if (autosaveTask != null) autosaveTask.cancel();

        long interval = Math.max(5L, getConfig().getLong("settings.update-interval-ticks", 20L));
        refreshTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            boards.tick();
            chat.refreshAll();
        }, 1L, interval);

        if (motdTask != null) motdTask.cancel();
        motdTask = Bukkit.getScheduler().runTaskTimer(this, motd::refresh, 20L, 100L);

        // Skins du top 10 (API Mojang), rafraîchis toutes les 10 minutes
        if (headsTask != null) headsTask.cancel();
        headsTask = Bukkit.getScheduler().runTaskTimer(this,
                () -> heads.preload(stats.ranking().subList(0, Math.min(10, stats.ranking().size()))), 200L, 12000L);

        long saveTicks = Math.max(1L, getConfig().getLong("settings.autosave-minutes", 5L)) * 60L * 20L;
        autosaveTask = Bukkit.getScheduler().runTaskTimer(this, stats::saveAsync, saveTicks, saveTicks);
    }

    /** Sauvegarde l'ancienne config (v1) et installe la nouvelle. */
    private void migrateConfig() {
        File file = new File(getDataFolder(), "config.yml");
        if (!file.exists()) return;
        if (org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file).getInt("config-version", 1) >= 4) return;
        File backup = new File(getDataFolder(), "config-ancienne.yml");
        if (backup.exists()) backup.delete();
        if (file.renameTo(backup)) {
            getLogger().info("Nouvelle configuration installée (l'ancienne est dans config-ancienne.yml).");
        }
        saveDefaultConfig();
        reloadConfig();
    }

    /** /succes reload */
    public void reloadEverything() {
        reloadConfig();
        text.reload();
        registry.load();
        for (Player p : Bukkit.getOnlinePlayers()) {
            stats.refreshOnline(p);
        }
        stats.recomputeAll();
        stats.rebuildRanking();
        boards.recreateAll();
        startTasks();
    }
}
