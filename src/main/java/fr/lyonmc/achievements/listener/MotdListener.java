package fr.lyonmc.achievements.listener;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import fr.lyonmc.achievements.data.AdvancementRegistry;
import fr.lyonmc.achievements.data.StatsService;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * MOTD de la liste des serveurs (2 lignes, la 2e change à chaque rafraîchissement)
 * et texte affiché au survol du nombre de joueurs.
 * Tout est préparé sur le thread principal ; le ping ne fait que lire le cache.
 */
public final class MotdListener implements Listener {

    private static final UUID NIL = new UUID(0L, 0L);
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final JavaPlugin plugin;
    private final AdvancementRegistry registry;
    private final StatsService stats;
    private final Text text;

    private volatile Component line1 = Component.empty();
    private volatile List<Component> line2 = List.of();
    private volatile List<String> hover = List.of();
    private volatile List<String> onlineNames = List.of();

    public MotdListener(JavaPlugin plugin, AdvancementRegistry registry, StatsService stats, Text text) {
        this.plugin = plugin;
        this.registry = registry;
        this.stats = stats;
        this.text = text;
    }

    /** Thread principal : prépare les textes. */
    public void refresh() {
        TagResolver r = text.resolver(registry, stats, null, null);
        boolean center = plugin.getConfig().getBoolean("motd.center", true);
        int width = plugin.getConfig().getInt("motd.width", 264);
        Component l1 = text.parse(plugin.getConfig().getString("motd.line1", ""), r);
        line1 = center ? center(l1, width) : l1;

        List<Component> l2 = new ArrayList<>();
        for (String s : plugin.getConfig().getStringList("motd.line2")) {
            Component c = text.parse(s, r);
            l2.add(center ? center(c, width) : c);
        }
        line2 = List.copyOf(l2);

        List<String> h = new ArrayList<>();
        for (String s : plugin.getConfig().getStringList("motd.hover")) {
            String legacy = LEGACY.serialize(text.parse(s, r));
            h.add(legacy.isEmpty() ? " " : legacy);
        }
        hover = List.copyOf(h);

        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
        onlineNames = List.copyOf(names);
    }

    // ───────────── Centrage (largeurs de la police par défaut de Minecraft) ─────────────

    private static Component center(Component c, int width) {
        int w = width(c, false);
        int spaces = Math.max(0, (width - w) / 2 / 4); // un espace = 4 px
        return Component.text(" ".repeat(spaces)).append(c);
    }

    private static int width(Component c, boolean parentBold) {
        net.kyori.adventure.text.format.TextDecoration.State st = c.decoration(net.kyori.adventure.text.format.TextDecoration.BOLD);
        boolean bold = st == net.kyori.adventure.text.format.TextDecoration.State.TRUE
                || (st == net.kyori.adventure.text.format.TextDecoration.State.NOT_SET && parentBold);
        int w = 0;
        if (c instanceof net.kyori.adventure.text.TextComponent t) {
            String content = t.content();
            for (int i = 0; i < content.length(); i++) {
                char ch = content.charAt(i);
                w += charWidth(ch) + (bold && ch != ' ' ? 1 : 0);
            }
        }
        for (Component child : c.children()) w += width(child, bold);
        return w;
    }

    private static int charWidth(char ch) {
        if (ch == ' ') return 4;
        if ("!,.:;|i'".indexOf(ch) >= 0) return ch == '\'' || ch == 'l' ? 3 : 2;
        if (ch == 'l' || ch == '`') return 3;
        if ("It[]".indexOf(ch) >= 0) return 4;
        if ("fk<>\"()*{}".indexOf(ch) >= 0) return 5;
        if (ch == '@' || ch == '~') return 7;
        if (ch < 128) return 6;
        return 6; // symboles et petites capitales (approximation)
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPing(PaperServerListPingEvent event) {
        if (!plugin.getConfig().getBoolean("motd.enabled", true)) return;

        List<Component> l2 = line2;
        Component second = l2.isEmpty() ? Component.empty() : l2.get(ThreadLocalRandom.current().nextInt(l2.size()));
        event.motd(Component.text().append(line1).append(Component.newline()).append(second).build());

        List<String> h = hover;
        if (h.isEmpty()) return;
        event.setHidePlayers(false);
        List<PaperServerListPingEvent.ListedPlayerInfo> listed = event.getListedPlayers();
        listed.clear();
        for (String line : h) listed.add(new PaperServerListPingEvent.ListedPlayerInfo(line, NIL));

        if (plugin.getConfig().getBoolean("motd.show-online-players", true)) {
            List<String> names = onlineNames;
            if (!names.isEmpty()) {
                listed.add(new PaperServerListPingEvent.ListedPlayerInfo(" ", NIL));
                listed.add(new PaperServerListPingEvent.ListedPlayerInfo("§7Connectés :", NIL));
                int max = 8;
                for (int i = 0; i < Math.min(max, names.size()); i++) {
                    listed.add(new PaperServerListPingEvent.ListedPlayerInfo("§8 • §f" + names.get(i), NIL));
                }
                if (names.size() > max) {
                    listed.add(new PaperServerListPingEvent.ListedPlayerInfo("§8 et " + (names.size() - max) + " autres", NIL));
                }
            }
        }
    }
}
