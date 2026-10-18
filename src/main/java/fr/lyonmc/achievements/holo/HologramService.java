package fr.lyonmc.achievements.holo;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Profile;
import fr.lyonmc.achievements.core.StoredLocation;
import fr.lyonmc.achievements.data.PlayerStats;
import fr.lyonmc.achievements.team.Team;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;

/**
 * Hologrammes de classement (TextDisplay) et podium avec les vraies têtes des joueurs (ItemDisplay).
 * Types : succes, saison, equipes, podium, podium-saison.
 */
public final class HologramService {

    public static final List<String> TYPES = List.of("succes", "saison", "equipes", "podium", "podium-saison");
    private static final TextColor GOLD = TextColor.color(0xFFD86B);
    private static final TextColor SILVER = TextColor.color(0xC8C8C8);
    private static final TextColor BRONZE = TextColor.color(0xD29A6A);

    private record Holo(String id, String type, StoredLocation location) {
    }

    private record Podium(String name, UUID uuid, String value) {
    }

    private final LyonAchievements plugin;
    private final NamespacedKey key;
    private final Map<String, Holo> holos = new LinkedHashMap<>();
    private final Map<String, List<Entity>> spawned = new HashMap<>();

    public HologramService(LyonAchievements plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "holo");
    }

    public void load() {
        removeAll();
        holos.clear();
        ConfigurationSection s = plugin.configs().state().getConfigurationSection("holograms");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            StoredLocation l = StoredLocation.parse(s.getString(id + ".location"));
            String type = s.getString(id + ".type", "succes");
            if (l != null && TYPES.contains(type)) holos.put(id, new Holo(id, type, l));
        }
    }

    private void persist() {
        plugin.configs().state().set("holograms", null);
        for (Holo h : holos.values()) {
            plugin.configs().state().set("holograms." + h.id() + ".type", h.type());
            plugin.configs().state().set("holograms." + h.id() + ".location", h.location().serialize());
        }
        plugin.configs().saveState();
    }

    public Set<String> ids() {
        return holos.keySet();
    }

    public String describe(String id) {
        Holo h = holos.get(id);
        return h == null ? "?" : h.type() + " · " + h.location().pretty();
    }

    public void create(String id, String type, Location at) {
        remove(id);
        holos.put(id, new Holo(id, type, StoredLocation.of(at)));
        persist();
        update();
    }

    public boolean remove(String id) {
        despawn(id);
        boolean removed = holos.remove(id) != null;
        if (removed) persist();
        return removed;
    }

    /** Supprime les entités d'hologrammes restées dans les mondes. */
    public void cleanup() {
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) if (e.getPersistentDataContainer().has(key, PersistentDataType.STRING)) e.remove();
        }
    }

    public void removeAll() {
        for (String id : new ArrayList<>(spawned.keySet())) despawn(id);
    }

    private void despawn(String id) {
        List<Entity> list = spawned.remove(id);
        if (list != null) for (Entity e : list) if (e.isValid()) e.remove();
    }

    /** Recrée / met à jour tous les hologrammes (appelé toutes les 20 secondes). */
    public void update() {
        for (Holo h : holos.values()) {
            Location l = h.location().toLocation();
            if (l == null || !l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) {
                despawn(h.id());
                continue;
            }
            List<Entity> list = spawned.get(h.id());
            boolean valid = list != null && !list.isEmpty();
            if (valid) for (Entity e : list) if (!e.isValid()) valid = false;
            if (!valid) {
                despawn(h.id());
                spawned.put(h.id(), spawn(h, l));
            } else {
                refresh(h, list);
            }
        }
    }

    // ───────────────────────────── Contenu ─────────────────────────────

    private Component board(String type) {
        TextColor accent = plugin.text().accent();
        List<Component> lines = new ArrayList<>();
        switch (type) {
            case "saison" -> {
                lines.add(Component.text("✦ " + plugin.seasons().name() + " ✦", accent));
                lines.add(Component.text("fin dans " + plugin.seasons().timeLeft(), Text.DARK));
                List<Profile> r = plugin.seasons().ranking();
                for (int i = 0; i < 10; i++) {
                    Profile p = i < r.size() ? r.get(i) : null;
                    lines.add(line(i + 1, p == null ? "-" : p.name, p == null ? "" : Text.num(plugin.seasons().points(p)) + " pts"));
                }
            }
            case "equipes" -> {
                lines.add(Component.text("✦ Classement des équipes ✦", accent));
                List<Team> top = plugin.teams().top();
                for (int i = 0; i < 5; i++) {
                    Team t = i < top.size() ? top.get(i) : null;
                    lines.add(line(i + 1, t == null ? "-" : "[" + t.tag + "] " + t.name, t == null ? "" : "★ " + Text.num(plugin.teams().achievements(t))));
                }
            }
            default -> {
                lines.add(Component.text("★ Classement des succès ★", accent));
                lines.add(Component.text(plugin.registry().total() + " succès à obtenir", Text.DARK));
                List<PlayerStats> r = plugin.stats().ranking();
                for (int i = 0; i < 10; i++) {
                    PlayerStats p = i < r.size() ? r.get(i) : null;
                    lines.add(line(i + 1, p == null ? "-" : p.name(), p == null ? "" : "★ " + p.count()));
                }
            }
        }
        lines.add(Component.text("lyonmc.fr", Text.DARK));
        return Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), lines);
    }

    private Component line(int pos, String name, String value) {
        TextColor c = pos == 1 ? GOLD : pos == 2 ? SILVER : pos == 3 ? BRONZE : Text.GRAY;
        return Component.text(pos + ". ", c).append(Component.text(name, Text.WHITE)).append(Component.text("  " + value, Text.DARK));
    }

    private List<Podium> podium(String type) {
        List<Podium> out = new ArrayList<>();
        if (type.equals("podium-saison")) {
            List<Profile> r = plugin.seasons().ranking();
            for (int i = 0; i < 3; i++) {
                Profile p = i < r.size() ? r.get(i) : null;
                out.add(p == null ? null : new Podium(p.name, p.uuid(), Text.num(plugin.seasons().points(p)) + " pts"));
            }
        } else {
            List<PlayerStats> r = plugin.stats().ranking();
            for (int i = 0; i < 3; i++) {
                PlayerStats p = i < r.size() ? r.get(i) : null;
                out.add(p == null ? null : new Podium(p.name(), p.uuid(), "★ " + p.count()));
            }
        }
        return out;
    }

    // ───────────────────────────── Entités ─────────────────────────────

    private List<Entity> spawn(Holo h, Location base) {
        List<Entity> out = new ArrayList<>();
        if (h.type().startsWith("podium")) {
            float yaw = base.getYaw();
            Vector right = new Vector(-Math.cos(Math.toRadians(yaw)), 0, -Math.sin(Math.toRadians(yaw)));
            double[][] pos = {{0, 1.6}, {-1.6, 1.1}, {1.6, 0.8}};
            out.add(text(base.clone().add(0, 3.0, 0), Component.empty(), h.id() + ":title"));
            for (int i = 0; i < 3; i++) {
                Location l = base.clone().add(right.clone().multiply(pos[i][0])).add(0, pos[i][1], 0);
                out.add(head(l, h.id() + ":head" + i));
                out.add(text(l.clone().add(0, -0.75, 0), Component.empty(), h.id() + ":label" + i));
            }
        } else {
            out.add(text(base.clone().add(0, 1.5, 0), Component.empty(), h.id() + ":board"));
        }
        refresh(h, out);
        return out;
    }

    private TextDisplay text(Location l, Component c, String tag) {
        return l.getWorld().spawn(l, TextDisplay.class, td -> {
            td.getPersistentDataContainer().set(key, PersistentDataType.STRING, tag);
            td.setPersistent(false);
            td.text(c);
            td.setBillboard(Display.Billboard.CENTER);
            td.setShadowed(true);
            td.setLineWidth(260);
            td.setAlignment(TextDisplay.TextAlignment.CENTER);
            td.setBackgroundColor(Color.fromARGB(90, 0, 0, 0));
            td.setViewRange(1.5f);
        });
    }

    private ItemDisplay head(Location l, String tag) {
        return l.getWorld().spawn(l, ItemDisplay.class, d -> {
            d.getPersistentDataContainer().set(key, PersistentDataType.STRING, tag);
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(1.1f, 1.1f, 1.1f), new AxisAngle4f()));
            d.setViewRange(1.5f);
        });
    }

    private void refresh(Holo h, List<Entity> entities) {
        if (h.type().startsWith("podium")) {
            List<Podium> top = podium(h.type());
            TextColor[] colors = {GOLD, SILVER, BRONZE};
            String[] labels = {"♛ 1", "✧ 2", "✧ 3"};
            for (Entity e : entities) {
                String tag = e.getPersistentDataContainer().get(key, PersistentDataType.STRING);
                if (tag == null) continue;
                if (tag.endsWith(":title") && e instanceof TextDisplay td) {
                    td.text(Component.text(h.type().equals("podium-saison") ? "✦ " + plugin.seasons().name() + " ✦" : "★ Podium des succès ★",
                            plugin.text().accent()));
                    continue;
                }
                int i = tag.charAt(tag.length() - 1) - '0';
                if (i < 0 || i > 2) continue;
                Podium p = top.get(i);
                if (tag.contains(":head") && e instanceof ItemDisplay d) {
                    d.setItemStack(p == null ? new org.bukkit.inventory.ItemStack(org.bukkit.Material.SKELETON_SKULL)
                            : plugin.heads().head(p.uuid(), p.name()));
                    if (p != null && !plugin.heads().hasSkin(p.uuid())) plugin.heads().load(p.uuid(), p.name(), null);
                } else if (tag.contains(":label") && e instanceof TextDisplay td) {
                    td.text(p == null ? Component.text(labels[i] + " · libre", Text.DARK)
                            : Component.text(labels[i] + "  ", colors[i]).append(Component.text(p.name(), Text.WHITE))
                            .append(Component.newline()).append(Component.text(p.value(), Text.GRAY)));
                }
            }
        } else {
            for (Entity e : entities) if (e instanceof TextDisplay td) td.text(board(h.type()));
        }
    }
}
