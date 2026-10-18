package fr.lyonmc.achievements.world;

import fr.lyonmc.achievements.LyonAchievements;
import net.kyori.adventure.util.TriState;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.YearMonth;
import java.util.logging.Level;

/**
 * Monde "ressources" : se réinitialise chaque mois pour garder le monde principal intact.
 * Seul un dossier créé par ce plugin (fichier témoin) peut être supprimé.
 */
public final class ResourceWorldService {

    private static final String MARKER = ".lyonmc-ressources";

    private final LyonAchievements plugin;
    private boolean resetting;

    public ResourceWorldService(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    private ConfigurationSection cfg() {
        return plugin.getConfig().getConfigurationSection("resource-world");
    }

    public boolean enabled() {
        return plugin.module("resource-world") && cfg() != null && cfg().getBoolean("enabled", true);
    }

    public String name() {
        return cfg() == null ? "ressources" : cfg().getString("name", "ressources");
    }

    public World world() {
        return Bukkit.getWorld(name());
    }

    private String month() {
        return YearMonth.now(plugin.text().zone()).toString();
    }

    /** Au démarrage : crée le monde (et le réinitialise si on a changé de mois). */
    public void init() {
        if (!enabled()) return;
        File folder = new File(Bukkit.getWorldContainer(), name());
        String created = plugin.configs().state().getString("resource-world.month");
        boolean ours = new File(folder, MARKER).exists();
        if (folder.exists() && ours && created != null && !created.equals(month()) && cfg().getBoolean("monthly-reset", true)) {
            plugin.getLogger().info("Nouveau mois : réinitialisation du monde " + name() + ".");
            delete(folder.toPath());
        } else if (folder.exists() && !ours && Bukkit.getWorld(name()) == null) {
            plugin.getLogger().warning("Le dossier " + name() + " existe mais n'a pas été créé par LyonAchievements : il ne sera jamais supprimé.");
        }
        create();
    }

    private void create() {
        boolean fresh = !new File(Bukkit.getWorldContainer(), name()).exists();
        WorldCreator wc = new WorldCreator(name()).environment(World.Environment.NORMAL).keepSpawnLoaded(TriState.FALSE);
        World w = wc.createWorld();
        if (w == null) {
            plugin.getLogger().warning("Impossible de créer le monde " + name());
            return;
        }
        w.setGameRule(GameRule.SPAWN_CHUNK_RADIUS, 0);
        int radius = cfg().getInt("border-radius", 3000);
        w.getWorldBorder().setCenter(w.getSpawnLocation());
        w.getWorldBorder().setSize(radius * 2.0);
        try {
            Files.writeString(new File(w.getWorldFolder(), MARKER).toPath(), "Monde ressources LyonMC");
        } catch (IOException ignored) {
        }
        if (fresh || plugin.configs().state().getString("resource-world.month") == null) {
            plugin.configs().state().set("resource-world.month", month());
            plugin.configs().saveState();
        }
    }

    /** Réinitialisation en cours de partie quand le mois change (appelé chaque minute). */
    public void tick() {
        if (!enabled() || resetting || !cfg().getBoolean("monthly-reset", true)) return;
        String created = plugin.configs().state().getString("resource-world.month");
        if (created == null || created.equals(month())) return;
        World w = world();
        if (w == null) return;
        resetting = true;
        Location spawn = plugin.teleportCommands().spawn();
        for (Player p : w.getPlayers()) {
            p.teleport(spawn);
            plugin.msg(p, "<gray>Le monde ressources se réinitialise pour le nouveau mois.");
        }
        File folder = w.getWorldFolder();
        if (!Bukkit.unloadWorld(w, false)) {
            plugin.getLogger().warning("Impossible de décharger le monde " + name() + " : réinitialisation au prochain redémarrage.");
            resetting = false;
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (new File(folder, MARKER).exists()) delete(folder.toPath());
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.configs().state().set("resource-world.month", null);
                create();
                resetting = false;
                Bukkit.broadcast(plugin.text().parse("<accent>✦</accent> <gray>Le monde ressources a été réinitialisé : de nouveaux "
                        + "minerais t'attendent ! <dark_gray>(/ressources)"));
            });
        });
    }

    public void teleport(Player player) {
        if (!enabled()) {
            plugin.msg(player, "<gray>Le monde ressources est désactivé.");
            return;
        }
        World w = world();
        if (w == null || resetting) {
            plugin.msg(player, "<gray>Le monde ressources n'est pas disponible pour le moment.");
            return;
        }
        int radius = cfg().getInt("border-radius", 3000);
        plugin.teleportCommands().rtp(player, w, 100, radius - 50, false);
    }

    private void delete(Path root) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Suppression incomplète de " + root, e);
        }
    }
}
