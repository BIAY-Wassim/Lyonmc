package fr.lyonmc.communaute;

import fr.lyonmc.achievements.api.ScreenPanel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayList;
import java.util.List;

/** Panneau « Communauté » du grand écran du hub (LyonAchievements). */
final class Panel implements ScreenPanel {

    private static final TextColor GRAY = TextColor.color(0xAAAAAA);
    private static final TextColor WHITE = TextColor.color(0xFFFFFF);
    private static final TextColor DARK = TextColor.color(0x555555);
    private static final TextColor GREEN = TextColor.color(0x9BE37C);
    private static final TextColor ORANGE = TextColor.color(0xFFB35C);
    private static final TextColor GOLD = TextColor.color(0xFFD86B);

    private final Communaute plugin;

    Panel(Communaute plugin) {
        this.plugin = plugin;
    }

    @Override
    public Component title() {
        return Component.text("✦ Communauté", GREEN);
    }

    @Override
    public List<Component> lines() {
        List<Component> out = new ArrayList<>();
        Goals g = plugin.goals();
        out.add(Component.text("Objectif de la semaine", ORANGE));
        out.add(Component.text(g.currentName(), WHITE));
        if (g.target() > 0) {
            int p = Math.min(g.progress(), g.target());
            int pct = (int) ((long) p * 100 / g.target());
            out.add(Component.text(Communaute.num(p) + " / " + Communaute.num(g.target()), GRAY)
                    .append(Component.text("  " + (g.done() ? "atteint !" : pct + " %"), g.done() ? GREEN : WHITE)));
        }
        out.add(Component.empty());
        Votes v = plugin.votes();
        out.add(Component.text("Vote party  ", GRAY).append(Component.text(v.partyCount() + " / " + v.partyTarget(), WHITE)));
        String month = plugin.month();
        List<PlayerData> top = v.top(month, 3);
        if (!top.isEmpty()) {
            out.add(Component.text("Top voteurs du mois", GRAY));
            for (int i = 0; i < top.size(); i++) {
                out.add(Component.text((i + 1) + ". ", i == 0 ? GOLD : GRAY).append(Component.text(top.get(i).name, WHITE))
                        .append(Component.text("  " + top.get(i).votesIn(month), DARK)));
            }
        }
        out.add(Component.empty());
        var boost = plugin.lyon().boosts().current();
        out.add(boost != null ? Component.text("Heure bonus en cours !", GOLD)
                : Component.text("Heure bonus  ", GRAY).append(Component.text(plugin.happyHours().nextText(), WHITE)));
        out.add(Component.text("/vote  ·  /parrain  ·  /objectif", DARK));
        return out;
    }
}
