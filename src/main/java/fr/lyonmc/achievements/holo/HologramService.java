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
 * Types : serveur, bienvenue, succes, saison, equipes, fortune, missions, grades, podium, podium-saison.
 */
public final class HologramService {

    public static final List<String> TYPES = List.of("ecran", "serveur", "bienvenue", "succes", "saison", "equipes", "fortune",
            "missions", "grades", "podium", "podium-saison");
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
            case "serveur" -> {
                lines.add(Component.text("✦ LyonMC ✦", accent).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD));
                lines.add(Component.text("Survie 1.21.8", Text.DARK));
                lines.add(Component.empty());
                lines.add(kv("● En ligne", Bukkit.getOnlinePlayers().size() + " / " + Bukkit.getMaxPlayers(), TextColor.color(0x7CE38B)));
                lines.add(kv("Joueurs venus", Text.num(plugin.profiles().all().size()), accent));
                lines.add(kv("Succès débloqués", Text.num(plugin.stats().serverTotal()), accent));
                long seconds = 0;
                for (Profile p : plugin.profiles().all()) seconds += p.playtimeSeconds;
                lines.add(kv("Heures de jeu", Text.num(seconds / 3600), accent));
                lines.add(kv("Équipes", Text.num(plugin.teams().all().size()), accent));
                lines.add(kv("Objets à l'HDV", Text.num(plugin.auctions().active().size()), accent));
                PlayerStats top = plugin.stats().top(1);
                if (top != null) lines.add(kv("♛ Leader", top.name() + "  ★ " + top.count(), GOLD));
                lines.add(Component.empty());
                lines.add(Component.text(plugin.seasons().name(), accent).append(Component.text("  fin dans " + plugin.seasons().timeLeft(), Text.DARK)));
                if (plugin.module("boss")) {
                    lines.add(plugin.boss().active()
                            ? Component.text("⚔ Le boss est en cours !", TextColor.color(0xFF6B6B))
                            : Component.text("⚔ Prochain boss ", Text.GRAY).append(Component.text(plugin.boss().nextText(), Text.WHITE)));
                }
            }
            case "bienvenue" -> {
                List<String> custom = plugin.getConfig().getStringList("holograms.bienvenue");
                if (custom.isEmpty()) custom = List.of(
                        "<accent><bold>Bienvenue sur LyonMC</bold>",
                        "<gray>Survie entre potes, succès et saisons",
                        "",
                        "<white>/menu</white> <dark_gray>tout le serveur",
                        "<white>/rtp</white> <dark_gray>partir à l'aventure",
                        "<white>/home</white> <dark_gray>rentrer chez toi",
                        "<white>/back</white> <dark_gray>là où tu étais",
                        "<white>/missions</white> <dark_gray>missions du jour",
                        "<white>/ressources</white> <dark_gray>le monde à miner",
                        "<white>/equipe</white> <dark_gray>jouer en équipe",
                        "<white>/hdv</white> <dark_gray>l'hôtel des ventes",
                        "",
                        "<gray>Ton grade monte tout seul avec tes succès");
                for (String l : custom) lines.add(plugin.text().parse(l));
            }
            case "fortune" -> {
                lines.add(Component.text("⛁ Les plus riches ⛁", accent));
                List<Profile> r = plugin.economy().top();
                for (int i = 0; i < 10; i++) {
                    Profile p = i < r.size() ? r.get(i) : null;
                    lines.add(line(i + 1, p == null ? "-" : p.name, p == null ? "" : plugin.economy().format(p.coins)));
                }
            }
            case "missions" -> {
                lines.add(Component.text("✔ Missions terminées ✔", accent));
                List<Profile> r = new ArrayList<>(plugin.profiles().all());
                r.removeIf(p -> p.missionsDone <= 0 || p.name == null);
                r.sort(Comparator.comparingInt((Profile p) -> p.missionsDone).reversed());
                for (int i = 0; i < 10; i++) {
                    Profile p = i < r.size() ? r.get(i) : null;
                    lines.add(line(i + 1, p == null ? "-" : p.name, p == null ? "" : "✔ " + Text.num(p.missionsDone)));
                }
            }
            case "grades" -> {
                lines.add(Component.text("✦ Les grades ✦", accent));
                lines.add(Component.text("automatiques, selon tes succès", Text.DARK));
                Map<String, Integer> counts = new HashMap<>();
                for (PlayerStats s : plugin.stats().all()) counts.merge(plugin.grades().of(s.uuid()).id(), 1, Integer::sum);
                List<fr.lyonmc.achievements.rewards.GradeService.Grade> all = new ArrayList<>(plugin.grades().all());
                Collections.reverse(all);
                for (var g : all) {
                    int n = counts.getOrDefault(g.id(), 0);
                    lines.add(plugin.text().parse(g.display())
                            .append(Component.text("  " + (int) g.percent() + " %", Text.GRAY))
                            .append(Component.text("  · " + n + " joueur" + (n > 1 ? "s" : ""), Text.DARK)));
                }
            }
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

    private static Component kv(String key, String value, TextColor color) {
        return Component.text(key + "  ", Text.GRAY).append(Component.text(value, color == null ? Text.WHITE : color));
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
        if (h.type().equals("ecran")) {
            spawnScreen(h, base, out);
        } else if (h.type().startsWith("podium")) {
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

    // ───────────────────────────── Grand écran ─────────────────────────────

    private static final int SCREEN_PANELS = 4;

    /** Panneaux en plus fournis par d'autres plugins (ex. : COMMUNAUTE). */
    private List<fr.lyonmc.achievements.api.ScreenPanel> extraPanels() {
        List<fr.lyonmc.achievements.api.ScreenPanel> out = new ArrayList<>();
        for (var reg : Bukkit.getServicesManager().getRegistrations(fr.lyonmc.achievements.api.ScreenPanel.class)) {
            if (out.size() < 2) out.add(reg.getProvider());
        }
        return out;
    }
    /** Ligne barrée faite d'espaces : donne la même largeur à tous les panneaux. */
    private static final Component RULE = Component.text(" ".repeat(40), TextColor.color(0x3A4150))
            .decoration(net.kyori.adventure.text.format.TextDecoration.STRIKETHROUGH, true);

    private float screenScale() {
        return (float) Math.max(0.4, Math.min(4, plugin.getConfig().getDouble("holograms.ecran-taille", 1.0)));
    }

    /** Un mur d'écran tourné vers là où regardait celui qui l'a posé : titre, quatre panneaux, bas de page. */
    private void spawnScreen(Holo h, Location base, List<Entity> out) {
        float s = screenScale();
        float yaw = base.getYaw();
        Vector right = new Vector(Math.cos(Math.toRadians(yaw)), 0, Math.sin(Math.toRadians(yaw)));
        double step = 4.45 * s;
        double panelBottom = 1.0;
        double panelTop = panelBottom + 14 * 0.25 * s;
        int count = SCREEN_PANELS + extraPanels().size();
        out.add(fixed(base.clone().add(0, panelTop + 0.15 * s, 0), yaw, 2.4f * s, h.id() + ":header",
                TextDisplay.TextAlignment.CENTER, 0));
        for (int i = 0; i < count; i++) {
            double off = (i - (count - 1) / 2.0) * step;
            out.add(fixed(base.clone().add(right.clone().multiply(off)).add(0, panelBottom, 0), yaw, s, h.id() + ":p" + i,
                    TextDisplay.TextAlignment.LEFT, 0xCC0E1219));
        }
        out.add(fixed(base.clone().add(0, panelBottom - 0.42 * s, 0), yaw, 1.15f * s, h.id() + ":footer",
                TextDisplay.TextAlignment.CENTER, 0));
    }

    private TextDisplay fixed(Location l, float yaw, float scale, String tag, TextDisplay.TextAlignment align, int argb) {
        Location at = l.clone();
        at.setYaw(yaw);
        at.setPitch(0);
        return l.getWorld().spawn(at, TextDisplay.class, td -> {
            td.getPersistentDataContainer().set(key, PersistentDataType.STRING, tag);
            td.setPersistent(false);
            td.setBillboard(Display.Billboard.FIXED);
            td.setRotation(yaw, 0);
            td.setShadowed(true);
            td.setLineWidth(400);
            td.setAlignment(align);
            td.setBackgroundColor(Color.fromARGB(argb));
            td.setDefaultBackground(false);
            td.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
            td.setViewRange(2.5f);
        });
    }

    private static Component title(String text, TextColor color) {
        return Component.text(" " + text, color).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD);
    }

    private static Component row(String key, String value, TextColor color) {
        return Component.text(" " + key + "  ", Text.GRAY).append(Component.text(value, color));
    }

    private void refreshScreen(List<Entity> entities) {
        TextColor accent = plugin.text().accent();
        List<List<Component>> panels = new ArrayList<>();

        // 1. Le serveur
        List<Component> a = new ArrayList<>();
        a.add(title("● Le serveur", TextColor.color(0x7CE38B)));
        a.add(RULE);
        a.add(row("En ligne", Bukkit.getOnlinePlayers().size() + " / " + Bukkit.getMaxPlayers(), TextColor.color(0x7CE38B)));
        a.add(row("Joueurs venus", Text.num(plugin.profiles().all().size()), Text.WHITE));
        a.add(row("Succès débloqués", Text.num(plugin.stats().serverTotal()), Text.WHITE));
        long seconds = 0;
        for (Profile p : plugin.profiles().all()) seconds += p.playtimeSeconds;
        a.add(row("Heures de jeu", Text.num(seconds / 3600), Text.WHITE));
        a.add(row("Équipes", Text.num(plugin.teams().all().size()), Text.WHITE));
        a.add(row("Objets à l'HDV", Text.num(plugin.auctions().active().size()), Text.WHITE));
        a.add(Component.empty());
        a.add(Component.text(" Dernier exploit", accent));
        PlayerStats lg = plugin.stats().lastGlobal();
        var lgAdv = lg == null || lg.lastKey() == null ? null : plugin.registry().get(lg.lastKey());
        a.add(lg == null ? Component.text(" personne pour l'instant", Text.DARK)
                : Component.text(" " + lg.name() + " ", Text.WHITE).append(lgAdv == null ? Component.empty() : lgAdv.title().colorIfAbsent(Text.GRAY)));
        panels.add(a);

        // 2. Succès
        List<Component> b = new ArrayList<>();
        b.add(title("★ Top succès", GOLD));
        b.add(RULE);
        List<PlayerStats> r = plugin.stats().ranking();
        for (int i = 0; i < 9; i++) {
            PlayerStats p = i < r.size() ? r.get(i) : null;
            b.add(Component.text(" ").append(line(i + 1, p == null ? "-" : p.name(), p == null ? "" : "★ " + p.count())));
        }
        b.add(Component.text(" " + plugin.registry().total() + " succès à obtenir", Text.DARK));
        panels.add(b);

        // 3. Saison et boss
        List<Component> c = new ArrayList<>();
        c.add(title("✦ " + plugin.seasons().name(), accent));
        c.add(RULE);
        c.add(Component.text(" fin dans " + plugin.seasons().timeLeft(), Text.DARK));
        List<Profile> sr = plugin.seasons().ranking();
        for (int i = 0; i < 5; i++) {
            Profile p = i < sr.size() ? sr.get(i) : null;
            c.add(Component.text(" ").append(line(i + 1, p == null ? "-" : p.name, p == null ? "" : Text.num(plugin.seasons().points(p)) + " pts")));
        }
        c.add(Component.empty());
        if (plugin.module("boss")) {
            c.add(Component.text(" ⚔ Boss mondial", TextColor.color(0xFF6B6B)));
            c.add(plugin.boss().active() ? Component.text(" en cours, /boss pour y aller !", Text.WHITE)
                    : Component.text(" " + plugin.boss().nextText(), Text.WHITE));
        }
        panels.add(c);

        // 4. Fortune et métiers (ou missions si les métiers sont désactivés)
        List<Component> d = new ArrayList<>();
        d.add(title("⛁ Les plus riches", TextColor.color(0xFFD86B)));
        d.add(RULE);
        List<Profile> rich = plugin.economy().top();
        boolean jobs = plugin.jobs() != null && plugin.jobs().enabled();
        for (int i = 0; i < (jobs ? 4 : 5); i++) {
            Profile p = i < rich.size() ? rich.get(i) : null;
            d.add(Component.text(" ").append(line(i + 1, p == null ? "-" : p.name, p == null ? "" : plugin.economy().format(p.coins))));
        }
        d.add(Component.empty());
        if (jobs) {
            d.add(Component.text(" ⚒ Meilleurs métiers", TextColor.color(0x9FD8FF)));
            for (var job : plugin.jobs().all()) {
                List<Profile> top = plugin.jobs().top(job);
                Profile p = top.isEmpty() ? null : top.get(0);
                d.add(Component.text(" ").append(Component.text(job.symbol() + " ", job.color()))
                        .append(Component.text(job.name() + "  ", Text.GRAY))
                        .append(Component.text(p == null ? "-" : p.name, Text.WHITE))
                        .append(Component.text(p == null ? "" : "  niv. " + plugin.jobs().level(p, job.id()), accent)));
            }
        } else {
            d.add(Component.text(" ✔ Missions", TextColor.color(0x9FD8FF)));
            List<Profile> mi = new ArrayList<>(plugin.profiles().all());
            mi.removeIf(p -> p.missionsDone <= 0 || p.name == null);
            mi.sort(Comparator.comparingInt((Profile p) -> p.missionsDone).reversed());
            for (int i = 0; i < 3; i++) {
                Profile p = i < mi.size() ? mi.get(i) : null;
                d.add(Component.text(" ").append(line(i + 1, p == null ? "-" : p.name, p == null ? "" : "✔ " + Text.num(p.missionsDone))));
            }
        }
        panels.add(d);

        // Panneaux des autres plugins (communauté...)
        for (var extra : extraPanels()) {
            List<Component> e = new ArrayList<>();
            try {
                e.add(Component.text(" ").append(extra.title()).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD));
                e.add(RULE);
                for (Component line : extra.lines()) e.add(Component.text(" ").append(line));
            } catch (RuntimeException ex) {
                continue;
            }
            panels.add(e);
        }
        // Un panneau a été ajouté ou retiré depuis la pose de l'écran : on le reconstruit
        long shown = entities.stream().filter(en -> {
            String t = en.getPersistentDataContainer().get(key, PersistentDataType.STRING);
            return t != null && t.matches(".*:p\\d+");
        }).count();
        if (shown != panels.size()) {
            for (Entity en : entities) en.remove();
            return;
        }

        // Même hauteur pour tous (le texte part du bas)
        int max = 0;
        for (List<Component> p : panels) max = Math.max(max, p.size());
        for (List<Component> p : panels) while (p.size() < max) p.add(Component.empty());

        for (Entity e : entities) {
            if (!(e instanceof TextDisplay td)) continue;
            String tag = e.getPersistentDataContainer().get(key, PersistentDataType.STRING);
            if (tag == null) continue;
            if (tag.endsWith(":header")) {
                td.text(Component.text("✦ LYONMC ✦", accent).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD));
            } else if (tag.endsWith(":footer")) {
                td.text(Component.text("lyonmc.fr", accent).append(Component.text("   ·   la boussole pour jouer   ·   l'étoile pour le menu", Text.GRAY)));
            } else {
                int i = Integer.parseInt(tag.substring(tag.lastIndexOf(":p") + 2));
                if (i >= 0 && i < panels.size()) td.text(Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), panels.get(i)));
            }
        }
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
        if (h.type().equals("ecran")) {
            refreshScreen(entities);
        } else if (h.type().startsWith("podium")) {
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
