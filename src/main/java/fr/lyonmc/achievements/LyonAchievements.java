package fr.lyonmc.achievements;

import fr.lyonmc.achievements.board.BoardManager;
import fr.lyonmc.achievements.command.SuccesCommand;
import fr.lyonmc.achievements.data.AdvancementRegistry;
import fr.lyonmc.achievements.data.StatsService;
import fr.lyonmc.achievements.listener.AdvancementListener;
import fr.lyonmc.achievements.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class LyonAchievements extends JavaPlugin {

    private AdvancementRegistry registry;
    private StatsService stats;
    private BoardManager boards;
    private Text text;

    private BukkitTask refreshTask;
    private BukkitTask autosaveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.text = new Text(this);
        this.registry = new AdvancementRegistry(this);
        this.registry.load();

        this.stats = new StatsService(this, registry);
        this.stats.load();

        this.boards = new BoardManager(this, registry, stats, text);

        getServer().getPluginManager().registerEvents(new AdvancementListener(this, registry, stats, boards, text), this);

        SuccesCommand command = new SuccesCommand(this, registry, stats, boards, text);
        PluginCommand pc = getCommand("succes");
        if (pc != null) {
            pc.setExecutor(command);
            pc.setTabCompleter(command);
        }

        // Joueurs déjà connectés (cas d'un /reload)
        for (Player p : Bukkit.getOnlinePlayers()) {
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
        if (boards != null) boards.removeAll();
        if (stats != null) stats.saveSync();
    }

    public void startTasks() {
        if (refreshTask != null) refreshTask.cancel();
        if (autosaveTask != null) autosaveTask.cancel();

        long interval = Math.max(5L, getConfig().getLong("settings.update-interval-ticks", 20L));
        refreshTask = Bukkit.getScheduler().runTaskTimer(this, boards::tick, interval, interval);

        long saveTicks = Math.max(1L, getConfig().getLong("settings.autosave-minutes", 5L)) * 60L * 20L;
        autosaveTask = Bukkit.getScheduler().runTaskTimer(this, stats::saveAsync, saveTicks, saveTicks);
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
