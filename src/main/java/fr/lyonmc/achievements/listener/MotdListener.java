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
        line1 = text.parse(plugin.getConfig().getString("motd.line1", ""), r);

        List<Component> l2 = new ArrayList<>();
        for (String s : plugin.getConfig().getStringList("motd.line2")) l2.add(text.parse(s, r));
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
