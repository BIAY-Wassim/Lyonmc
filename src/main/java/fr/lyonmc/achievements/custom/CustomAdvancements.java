package fr.lyonmc.achievements.custom;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Profile;
import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Vrais progrès Minecraft propres à LyonMC (onglet "LyonMC" dans l'écran des progrès,
 * notification du jeu, comptés dans le total des succès).
 */
@SuppressWarnings("deprecation")
public final class CustomAdvancements implements Listener {

    public static final String NAMESPACE = "lyonmc";

    private record Def(String id, String parent, String icon, String title, String description, String frame) {
    }

    private static final List<Def> DEFS = List.of(
            new Def("root", null, "nether_star", "LyonMC", "Rejoindre l'aventure LyonMC", "task"),
            new Def("premiere_mission", "root", "writable_book", "Au travail", "Terminer une mission", "task"),
            new Def("routine", "premiere_mission", "clock", "Routine", "Terminer toutes les missions du jour", "task"),
            new Def("serie_7", "routine", "campfire", "Régulier", "Atteindre une série de 7 jours de missions", "goal"),
            new Def("serie_30", "serie_7", "soul_campfire", "Machine", "Atteindre une série de 30 jours de missions", "challenge"),
            new Def("hebdo", "premiere_mission", "bookshelf", "Semaine chargée", "Terminer une mission de la semaine", "task"),
            new Def("missions_100", "hebdo", "enchanted_book", "Infatigable", "Terminer 100 missions", "challenge"),
            new Def("fidele", "root", "cake", "Fidèle", "Récupérer 7 récompenses quotidiennes d'affilée", "goal"),
            new Def("premier_salaire", "root", "gold_nugget", "Premier salaire", "Gagner 1 000 pièces au total", "task"),
            new Def("fortune", "premier_salaire", "gold_block", "Fortune", "Posséder 25 000 pièces", "goal"),
            new Def("commercant", "premier_salaire", "emerald", "Commerçant", "Vendre un objet à l'hôtel des ventes", "task"),
            new Def("style", "root", "firework_rocket", "Avec style", "Équiper une cosmétique", "task"),
            new Def("collectionneur", "style", "name_tag", "Collectionneur", "Débloquer 10 titres", "goal"),
            new Def("equipe", "root", "white_banner", "Esprit d'équipe", "Rejoindre ou créer une équipe", "task"),
            new Def("colosse", "root", "wither_skeleton_skull", "Chasseur de Colosse", "Participer à la victoire contre le boss mondial", "goal"),
            new Def("coup_de_grace", "colosse", "netherite_sword", "Coup de grâce", "Porter le coup final au boss mondial", "challenge"),
            new Def("podium", "root", "golden_helmet", "Sur le podium", "Terminer une saison dans le top 3", "challenge"),
            new Def("marathon", "root", "leather_boots", "Marathonien", "Parcourir 100 km à pied", "goal"),
            new Def("exterminateur", "root", "iron_sword", "Exterminateur", "Tuer 1 000 créatures", "goal"),
            new Def("mineur", "root", "diamond_pickaxe", "Mineur acharné", "Miner 10 000 blocs naturels", "goal"),
            new Def("survivant", "root", "totem_of_undying", "Survivant", "Rester 10 heures de jeu sans mourir", "challenge"),
            new Def("pilier", "root", "beacon", "Pilier du serveur", "Jouer 100 heures sur le serveur", "challenge")
    );

    private final LyonAchievements plugin;

    public CustomAdvancements(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    /** Enregistre les progrès (à faire avant le chargement du registre des succès). */
    public void register() {
        if (!plugin.getConfig().getBoolean("modules.custom-advancements", true)) return;
        int added = 0;
        for (Def d : DEFS) {
            NamespacedKey key = NamespacedKey.fromString(NAMESPACE + ":" + d.id());
            if (key == null || Bukkit.getAdvancement(key) != null) continue;
            try {
                if (Bukkit.getUnsafe().loadAdvancement(key, json(d)) != null) added++;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Progrès " + key + " impossible à créer", e);
            }
        }
        if (added > 0) plugin.getLogger().info(added + " succès LyonMC ajoutés.");
    }

    private static String json(Def d) {
        StringBuilder sb = new StringBuilder("{");
        if (d.parent() != null) sb.append("\"parent\":\"").append(NAMESPACE).append(':').append(d.parent()).append("\",");
        sb.append("\"display\":{");
        sb.append("\"icon\":{\"id\":\"minecraft:").append(d.icon()).append("\",\"count\":1},");
        sb.append("\"title\":{\"text\":\"").append(esc(d.title())).append("\"},");
        sb.append("\"description\":{\"text\":\"").append(esc(d.description())).append("\",\"color\":\"gray\"},");
        sb.append("\"frame\":\"").append(d.frame()).append("\",");
        if (d.parent() == null) sb.append("\"background\":\"minecraft:block/deepslate_tiles\",");
        sb.append("\"show_toast\":").append(d.parent() != null).append(',');
        sb.append("\"announce_to_chat\":").append(d.parent() != null).append(',');
        sb.append("\"hidden\":false},");
        sb.append("\"criteria\":{\"done\":{\"trigger\":\"minecraft:impossible\"}},");
        sb.append("\"requirements\":[[\"done\"]]}");
        return sb.toString();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @EventHandler
    public void onReload(ServerResourcesReloadedEvent event) {
        // Un /minecraft:reload efface les progrès ajoutés par plugin : on les remet
        Bukkit.getScheduler().runTask(plugin, () -> {
            register();
            plugin.registry().load();
        });
    }

    // ───────────────────────────── Attribution ─────────────────────────────

    /** Donne un progrès LyonMC (sans effet s'il est déjà obtenu). */
    public void grant(Player player, String id) {
        if (player == null || !player.isOnline()) return;
        NamespacedKey key = NamespacedKey.fromString(NAMESPACE + ":" + id);
        Advancement adv = key == null ? null : Bukkit.getAdvancement(key);
        if (adv == null) return;
        AdvancementProgress progress = player.getAdvancementProgress(adv);
        if (progress.isDone()) return;
        for (String c : new ArrayList<>(progress.getRemainingCriteria())) progress.awardCriteria(c);
    }

    /** Pour un joueur hors ligne : donné à sa prochaine connexion. */
    public void grantLater(Profile p, String id) {
        Player online = Bukkit.getPlayer(p.uuid());
        if (online != null) {
            grant(online, id);
        } else if (p.pendingAdvancements.add(id)) {
            p.dirty();
        }
    }

    public void onJoin(Player player, Profile p) {
        grant(player, "root");
        if (!p.pendingAdvancements.isEmpty()) {
            for (String id : new ArrayList<>(p.pendingAdvancements)) grant(player, id);
            p.pendingAdvancements.clear();
            p.dirty();
        }
        checkStats(player, p);
        checkEconomy(player, p);
        checkTitles(player, p);
    }

    /** Progrès basés sur les statistiques du jeu (appelé régulièrement). */
    public void checkStats(Player player, Profile p) {
        long walked = (long) player.getStatistic(Statistic.WALK_ONE_CM) + player.getStatistic(Statistic.SPRINT_ONE_CM);
        if (walked >= 10_000_000L) grant(player, "marathon");
        if (player.getStatistic(Statistic.MOB_KILLS) >= 1000) grant(player, "exterminateur");
        if (player.getStatistic(Statistic.TIME_SINCE_DEATH) >= 20 * 3600 * 10) grant(player, "survivant");
        if (player.getStatistic(Statistic.PLAY_ONE_MINUTE) >= 20L * 3600 * 100) grant(player, "pilier");
        if (p.blocksMined >= 10_000) grant(player, "mineur");
        if (p.missionsDone >= 1) grant(player, "premiere_mission");
        if (p.missionsDone >= 100) grant(player, "missions_100");
        if (p.bestMissionStreak >= 7) grant(player, "serie_7");
        if (p.bestMissionStreak >= 30) grant(player, "serie_30");
        if (p.bestLoginStreak >= 7) grant(player, "fidele");
        if (p.bossKills >= 1) grant(player, "colosse");
        if (p.bossLastHits >= 1) grant(player, "coup_de_grace");
        if (p.hdvSales >= 1) grant(player, "commercant");
        if (p.seasonPodiums >= 1) grant(player, "podium");
    }

    public void checkEconomy(Player player, Profile p) {
        if (p.earned >= 1000) grant(player, "premier_salaire");
        if (p.coins >= 25_000) grant(player, "fortune");
    }

    public void checkTitles(Player player, Profile p) {
        if (plugin.rewards().unlockedTitles(p) >= 10) grant(player, "collectionneur");
        if (p.color != null || p.trail != null) grant(player, "style");
    }

    public void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Profile p = plugin.profiles().get(player);
            checkStats(player, p);
            checkEconomy(player, p);
        }
    }
}
