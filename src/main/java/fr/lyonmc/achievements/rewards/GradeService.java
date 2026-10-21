package fr.lyonmc.achievements.rewards;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Profile;
import fr.lyonmc.achievements.data.PlayerStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Grades automatiques, calculés selon le pourcentage de succès obtenus.
 * Personne ne choisit son grade : il monte tout seul et s'affiche dans le chat.
 */
public final class GradeService {

    public record Grade(String id, String display, double percent) {
    }

    private final LyonAchievements plugin;
    private final List<Grade> grades = new ArrayList<>();

    public GradeService(LyonAchievements plugin) {
        this.plugin = plugin;
        plugin.text().addProvider((b, uuid) -> {
            if (uuid == null || grades.isEmpty()) return;
            Grade g = of(uuid);
            Grade n = next(g);
            b.resolver(Placeholder.component("grade", plugin.text().parse(shown(uuid).display())));
            b.resolver(Placeholder.component("grade_next", n == null ? Component.text("-") : plugin.text().parse(n.display())));
            b.resolver(Placeholder.unparsed("grade_next_percent", n == null ? "100" : fmt(n.percent())));
        });
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.format(java.util.Locale.FRANCE, "%.1f", d);
    }

    public void load() {
        grades.clear();
        ConfigurationSection s = plugin.configs().rewards().getConfigurationSection("grades");
        if (s != null) {
            for (String id : s.getKeys(false)) {
                ConfigurationSection g = s.getConfigurationSection(id);
                if (g == null) continue;
                grades.add(new Grade(id, g.getString("display", id), g.getDouble("percent", 0)));
            }
        }
        if (grades.isEmpty()) grades.add(new Grade("novice", "<gray>Novice", 0));
        grades.sort(Comparator.comparingDouble(Grade::percent));
    }

    public List<Grade> all() {
        return grades;
    }

    public Set<String> ids() {
        return grades.stream().map(Grade::id).collect(Collectors.toSet());
    }

    /** Pourcentage de succès obtenus par un joueur. */
    public double percent(UUID uuid) {
        int total = plugin.registry().total();
        PlayerStats s = plugin.stats().get(uuid);
        if (total <= 0 || s == null) return 0;
        return s.count() * 100.0 / total;
    }

    public Grade of(UUID uuid) {
        double pct = percent(uuid);
        Grade best = grades.get(0);
        for (Grade g : grades) if (pct + 1e-9 >= g.percent()) best = g;
        return best;
    }

    public Grade byId(String id) {
        if (id == null) return null;
        for (Grade g : grades) if (g.id().equals(id)) return g;
        return null;
    }

    /** Morceau affiché devant le pseudo dans le chat. */
    public Component chatTag(UUID uuid) {
        if (!plugin.getConfig().getBoolean("grades.chat", true)) return Component.empty();
        String fmt = plugin.getConfig().getString("grades.chat-tag", "<grade> ");
        return plugin.text().parse(fmt, Placeholder.component("grade", display(uuid)));
    }

    /** Grade affiché : le grade automatique, ou celui choisi par une Légende (jamais Novice). */
    public Grade shown(UUID uuid) {
        Grade natural = of(uuid);
        if (!isTop(natural)) return natural;
        Profile p = plugin.profiles().get(uuid);
        Grade chosen = p == null ? null : byId(p.gradeChoice);
        return chosen != null && canChoose(chosen) ? chosen : natural;
    }

    /** Plus haut grade (Légende). */
    public boolean isTop(Grade g) {
        return !grades.isEmpty() && g == grades.get(grades.size() - 1);
    }

    /** Grades qu'une Légende peut afficher : tous sauf le premier (Novice). */
    public boolean canChoose(Grade g) {
        return grades.indexOf(g) > 0;
    }

    public List<Grade> choosable() {
        return grades.size() > 1 ? grades.subList(1, grades.size()) : List.of();
    }

    /** Choix du grade affiché (null = automatique). Renvoie false si le joueur n'est pas Légende. */
    public boolean choose(Player player, String id) {
        if (!isTop(of(player.getUniqueId()))) return false;
        Grade g = byId(id);
        if (id != null && (g == null || !canChoose(g))) return false;
        Profile p = plugin.profiles().get(player);
        p.gradeChoice = g == null || isTop(g) ? null : g.id();
        p.dirty();
        if (plugin.chat() != null) plugin.chat().refresh(player);
        return true;
    }

    public Component display(UUID uuid) {
        return plugin.text().parse(shown(uuid).display());
    }

    /** Grade suivant (ou null au plus haut). */
    public Grade next(Grade current) {
        int i = grades.indexOf(current);
        return i >= 0 && i + 1 < grades.size() ? grades.get(i + 1) : null;
    }

    /**
     * Met à jour le grade enregistré du joueur. Annonce la montée de grade,
     * sauf la toute première fois (joueurs existants au passage aux grades).
     */
    public void check(Player player) {
        Profile p = plugin.profiles().get(player);
        if (p == null) return;
        // Un ancien titre « grade » choisi à la main ne s'affiche plus
        if (p.title != null && ids().contains(p.title)) {
            p.title = null;
            p.dirty();
        }
        Grade now = of(player.getUniqueId());
        if (now.id().equals(p.grade)) return;
        Grade before = byId(p.grade);
        p.grade = now.id();
        p.dirty();
        if (plugin.chat() != null) plugin.chat().refresh(player);
        if (before == null || now.percent() <= before.percent()) return;

        Component name = plugin.text().parse(now.display());
        String msg = plugin.getConfig().getString("grades.announce",
                "<accent>✦</accent> <white><player></white> <gray>est maintenant</gray> <grade><gray> !</gray>");
        Bukkit.broadcast(plugin.text().parse(msg, Placeholder.unparsed("player", player.getName()), Placeholder.component("grade", name)));
        player.showTitle(net.kyori.adventure.title.Title.title(name, plugin.text().parse("<gray>Nouveau grade")));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        plugin.discord().send("Nouveau grade", player.getName() + " est maintenant " + now.display().replaceAll("<[^>]+>", "") + " !", 0xC79BFF, null);
    }
}
