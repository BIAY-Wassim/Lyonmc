package fr.lyonmc.hub.npc;

import com.destroystokyo.paper.event.player.PlayerUseUnknownEntityEvent;
import com.mojang.authlib.GameProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PNJ du hub : podium (le joueur classé à cette place, avec son vrai skin) ou PNJ fixe
 * (skin d'un pseudo, commande au clic). Les PNJ sont envoyés par paquets aux joueurs proches.
 */
public final class NpcService implements Listener {

    public static final class Npc {
        final String id;
        String type;          // podium | skin
        String board;         // podium : succes, saison, fortune, missions
        int rank;             // podium : 1, 2, 3...
        String skinName;      // skin : pseudo dont on prend le skin
        String command;       // skin : commande lancée au clic
        List<String> label = new ArrayList<>();
        String world;
        double x, y, z;
        float yaw, pitch;

        final int entityId = Bukkit.getUnsafe().nextEntityId();
        final UUID profileId = UUID.randomUUID();
        final String profileName;
        GameProfile profile;   // null = rien à montrer
        String texture;
        Rankings.Entry occupant;
        final Set<UUID> viewers = new HashSet<>();
        final Set<UUID> looking = new HashSet<>();
        TextDisplay labelEntity;
        String labelText = "";

        Npc(String id) {
            this.id = id;
            this.profileName = "npc" + Integer.toHexString(id.hashCode() & 0xFFFFFF);
        }

        Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }

        void place(Location l) {
            world = l.getWorld().getName();
            x = l.getX();
            y = l.getY();
            z = l.getZ();
            yaw = l.getYaw();
            pitch = 0;
        }

        public String id() {
            return id;
        }

        public String describe() {
            String where = String.format(Locale.ROOT, "%s %.0f %.0f %.0f", world, x, y, z);
            return type.equals("podium") ? "podium " + board + " #" + rank + " · " + where
                    : "skin " + skinName + (command == null || command.isEmpty() ? "" : " · /" + command) + " · " + where;
        }
    }

    private final JavaPlugin plugin;
    private final SkinService skins;
    private final Rankings rankings = new Rankings();
    private final NamespacedKey labelKey;
    private final File file;
    private final Map<String, Npc> npcs = new LinkedHashMap<>();
    private final Set<UUID> teamSent = new HashSet<>();
    private final Map<UUID, Long> notBefore = new HashMap<>();
    private final Map<UUID, Long> clickCooldown = new HashMap<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final MiniMessage mm = MiniMessage.miniMessage();

    public NpcService(JavaPlugin plugin, SkinService skins) {
        this.plugin = plugin;
        this.skins = skins;
        this.labelKey = new NamespacedKey(plugin, "label");
        this.file = new File(plugin.getDataFolder(), "pnj.yml");
    }

    // ───────────────────────────── Données ─────────────────────────────

    public void load() {
        despawnAll();
        npcs.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = y.getConfigurationSection("pnj");
        if (s != null) {
            for (String id : s.getKeys(false)) {
                ConfigurationSection c = s.getConfigurationSection(id);
                if (c == null) continue;
                Npc n = new Npc(id);
                n.type = c.getString("type", "podium");
                n.board = c.getString("classement", "succes");
                n.rank = c.getInt("place", 1);
                n.skinName = c.getString("skin");
                n.command = c.getString("commande", "");
                n.label = new ArrayList<>(c.getStringList("texte"));
                n.world = c.getString("monde", "world");
                n.x = c.getDouble("x");
                n.y = c.getDouble("y");
                n.z = c.getDouble("z");
                n.yaw = (float) c.getDouble("yaw");
                n.pitch = (float) c.getDouble("pitch");
                npcs.put(id, n);
            }
        }
        for (Npc n : npcs.values()) if (n.type.equals("skin")) loadFixedSkin(n);
        refreshRankings();
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Npc n : npcs.values()) {
            String p = "pnj." + n.id + ".";
            y.set(p + "type", n.type);
            if (n.type.equals("podium")) {
                y.set(p + "classement", n.board);
                y.set(p + "place", n.rank);
            } else {
                y.set(p + "skin", n.skinName);
                y.set(p + "commande", n.command);
                y.set(p + "texte", n.label);
            }
            y.set(p + "monde", n.world);
            y.set(p + "x", n.x);
            y.set(p + "y", n.y);
            y.set(p + "z", n.z);
            y.set(p + "yaw", (double) n.yaw);
            y.set(p + "pitch", (double) n.pitch);
        }
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Impossible d'enregistrer pnj.yml : " + e.getMessage());
        }
    }

    /** Supprime tous les PNJ d'un monde (avant d'installer le hub tout fait). */
    public int removeInWorld(String world) {
        int n = 0;
        for (Npc npc : new ArrayList<>(npcs.values())) {
            if (world.equals(npc.world) && remove(npc.id)) n++;
        }
        return n;
    }

    public Collection<Npc> all() {
        return npcs.values();
    }

    public Npc get(String id) {
        return npcs.get(id.toLowerCase(Locale.ROOT));
    }

    public Npc createPodium(String id, String board, int rank, Location at) {
        remove(id);
        Npc n = new Npc(id);
        n.type = "podium";
        n.board = board;
        n.rank = rank;
        n.place(at);
        npcs.put(id, n);
        save();
        refreshRankings();
        return n;
    }

    public Npc createFixed(String id, String skinName, String command, Location at) {
        remove(id);
        Npc n = new Npc(id);
        n.type = "skin";
        n.skinName = skinName;
        n.command = command == null ? "" : command;
        n.label = new ArrayList<>(List.of("<white><bold>" + skinName));
        n.place(at);
        npcs.put(id, n);
        save();
        loadFixedSkin(n);
        return n;
    }

    public void move(Npc n, Location at) {
        hideAll(n);
        removeLabel(n);
        n.place(at);
        save();
    }

    public void setLabel(Npc n, List<String> lines) {
        n.label = new ArrayList<>(lines);
        save();
        updateLabel(n);
    }

    public void setCommand(Npc n, String command) {
        n.command = command == null ? "" : command;
        save();
    }

    public boolean remove(String id) {
        Npc n = npcs.remove(id);
        if (n == null) return false;
        hideAll(n);
        removeLabel(n);
        save();
        return true;
    }

    // ───────────────────────────── Tâches ─────────────────────────────

    public void start() {
        stop();
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::tickVisibility, 20L, 10L));
        // Les PNJ restent immobiles, tournés comme on les a posés ; ceux du podium ont une aura selon leur place
        if (plugin.getConfig().getBoolean("aura", true)) {
            tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::tickAura, 25L, 2L));
        }
        long refresh = Math.max(10, plugin.getConfig().getLong("refresh-seconds", 60)) * 20L;
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::refreshRankings, refresh, refresh));
    }

    public void stop() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
    }

    /** Supprime les textes restés dans les mondes (arrêt brutal). */
    public void cleanup() {
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntitiesByClass(TextDisplay.class)) {
                if (e.getPersistentDataContainer().has(labelKey, PersistentDataType.STRING)) e.remove();
            }
        }
    }

    public void despawnAll() {
        for (Npc n : npcs.values()) {
            hideAll(n);
            removeLabel(n);
        }
    }

    private Path rankingsFile() {
        String p = plugin.getConfig().getString("classements", "siteweb/data/classements.json");
        Path path = Path.of(p);
        if (!path.isAbsolute()) {
            File root = plugin.getDataFolder().getAbsoluteFile().getParentFile().getParentFile();
            path = root.toPath().resolve(p);
        }
        return path;
    }

    public void refreshRankings() {
        boolean podium = npcs.values().stream().anyMatch(n -> n.type.equals("podium"));
        if (!podium) return;
        Path path = rankingsFile();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = rankings.read(path);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!ok) {
                    plugin.getLogger().warning("Classements introuvables (" + path + ") : vérifie l'export du site de LyonAchievements.");
                }
                for (Npc n : npcs.values()) if (n.type.equals("podium")) applyRanking(n);
            });
        });
    }

    private void applyRanking(Npc n) {
        Rankings.Entry e = rankings.at(n.board, n.rank);
        Rankings.Entry before = n.occupant;
        n.occupant = e;
        if (e == null) {
            hideAll(n);
            n.profile = null;
            n.texture = null;
            updateLabel(n);
            return;
        }
        boolean samePlayer = before != null && before.name().equals(e.name());
        if (!samePlayer) {
            // Nouveau joueur à cette place : on montre tout de suite son pseudo, le skin suit
            SkinService.Skin cached = skins.cached(e.uuid(), e.name());
            setProfile(n, cached == null ? null : cached.texture(), cached == null ? null : cached.signature(), true);
        }
        updateLabel(n);
        skins.get(e.uuid(), e.name(), skin -> {
            if (n.occupant == null || !n.occupant.name().equals(e.name()) || skin == null) return;
            setProfile(n, skin.texture(), skin.signature(), false);
        });
    }

    private void loadFixedSkin(Npc n) {
        if (n.profile == null) setProfile(n, null, null, true);
        if (n.skinName == null || n.skinName.isEmpty()) return;
        SkinService.Skin cached = skins.cached(null, n.skinName);
        if (cached != null) setProfile(n, cached.texture(), cached.signature(), false);
        skins.get(null, n.skinName, skin -> {
            if (skin != null && npcs.get(n.id) == n) setProfile(n, skin.texture(), skin.signature(), false);
        });
        updateLabel(n);
    }

    /** Change le skin du PNJ et le renvoie aux joueurs qui le voient. */
    private void setProfile(Npc n, String texture, String signature, boolean force) {
        String t = texture == null ? "" : texture;
        if (!force && n.profile != null && t.equals(n.texture)) return;
        hideAll(n);
        n.texture = t;
        n.profile = NpcPackets.profile(n.profileId, n.profileName, texture, signature);
    }

    // ───────────────────────────── Affichage ─────────────────────────────

    private void tickVisibility() {
        double vd = plugin.getConfig().getDouble("view-distance", 48);
        double vd2 = vd * vd;
        long now = System.currentTimeMillis();
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        for (Npc n : npcs.values()) {
            Location l = n.location();
            ensureLabel(n, l);
            for (Player p : online) {
                boolean should = n.profile != null && l != null && p.getWorld().equals(l.getWorld())
                        && p.getLocation().distanceSquared(l) <= vd2 && now >= notBefore.getOrDefault(p.getUniqueId(), 0L);
                boolean shown = n.viewers.contains(p.getUniqueId());
                if (should && !shown) show(n, p, l);
                else if (!should && shown) hide(n, p);
            }
        }
    }

    private void show(Npc n, Player p, Location l) {
        try {
            if (teamSent.add(p.getUniqueId())) NpcPackets.sendTeam(p);
            NpcPackets.addToTeam(p, n.profileName);
            NpcPackets.show(p, n.entityId, n.profile, l);
            n.viewers.add(p.getUniqueId());
            n.looking.remove(p.getUniqueId());
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Impossible d'afficher le PNJ " + n.id, e);
            n.profile = null;
        }
    }

    private void hide(Npc n, Player p) {
        n.viewers.remove(p.getUniqueId());
        n.looking.remove(p.getUniqueId());
        if (p.isOnline()) NpcPackets.hide(p, n.entityId, n.profileId);
    }

    private void hideAll(Npc n) {
        for (UUID u : new ArrayList<>(n.viewers)) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) hide(n, p);
        }
        n.viewers.clear();
        n.looking.clear();
    }

    private int auraTick;

    /** Aura du podium : or pour le 1er, argent pour le 2e, bronze pour le 3e. */
    private void tickAura() {
        auraTick++;
        for (Npc n : npcs.values()) {
            if (!n.type.equals("podium") || n.occupant == null || n.viewers.isEmpty() || n.rank > 3) continue;
            Location l = n.location();
            if (l == null) continue;
            org.bukkit.Color color = switch (n.rank) {
                case 1 -> org.bukkit.Color.fromRGB(255, 205, 70);
                case 2 -> org.bukkit.Color.fromRGB(215, 225, 235);
                default -> org.bukkit.Color.fromRGB(215, 140, 80);
            };
            float size = n.rank == 1 ? 1.3f : 1.0f;
            org.bukkit.Particle.DustOptions dust = new org.bukkit.Particle.DustOptions(color, size);
            // Deux spirales qui montent autour du joueur
            double t = auraTick * 0.25;
            double h = (auraTick % 40) / 40.0 * 2.1;
            for (int k = 0; k < 2; k++) {
                double ang = t + k * Math.PI;
                l.getWorld().spawnParticle(org.bukkit.Particle.DUST,
                        l.getX() + Math.cos(ang) * 0.75, l.getY() + h, l.getZ() + Math.sin(ang) * 0.75, 1, 0, 0, 0, 0, dust);
            }
            // Anneau au sol
            if (auraTick % 4 == 0) {
                for (int k = 0; k < 8; k++) {
                    double ang = k * Math.PI / 4 + t / 3;
                    l.getWorld().spawnParticle(org.bukkit.Particle.DUST,
                            l.getX() + Math.cos(ang) * 0.9, l.getY() + 0.1, l.getZ() + Math.sin(ang) * 0.9, 1, 0, 0, 0, 0, dust);
                }
            }
            // Étincelles en plus pour le 1er
            if (n.rank == 1 && auraTick % 6 == 0) {
                l.getWorld().spawnParticle(org.bukkit.Particle.END_ROD, l.getX(), l.getY() + 2.3, l.getZ(), 1, 0.35, 0.1, 0.35, 0.01);
            }
        }
    }

    // ───────────────────────────── Texte au-dessus ─────────────────────────────

    private List<String> labelLines(Npc n) {
        if (!n.type.equals("podium")) return n.label;
        return plugin.getConfig().getStringList(n.occupant == null ? "labels.empty" : "labels.podium");
    }

    private String rankTag(int rank) {
        return switch (rank) {
            case 1 -> "<#ffd86b>♛ 1er</#ffd86b>";
            case 2 -> "<#c8c8c8>✧ 2e</#c8c8c8>";
            case 3 -> "<#d29a6a>✧ 3e</#d29a6a>";
            default -> "<gray>#" + rank + "</gray>";
        };
    }

    private Component labelComponent(Npc n) {
        List<String> lines = labelLines(n);
        if (lines.isEmpty()) return null;
        Rankings.Board b = Rankings.board(n.board == null ? "" : n.board);
        TagResolver r = TagResolver.resolver(
                Placeholder.parsed("rank", rankTag(n.rank)),
                Placeholder.unparsed("board", b == null ? "" : b.title()),
                Placeholder.unparsed("player", n.occupant == null ? "" : n.occupant.name()),
                Placeholder.unparsed("value", n.occupant == null || b == null ? ""
                        : String.format(Locale.FRANCE, "%,d", n.occupant.value()).replace(' ', ' ').replace(' ', ' ') + " " + b.unit()));
        List<Component> out = new ArrayList<>();
        for (String s : lines) out.add(mm.deserialize(s, r));
        return Component.join(JoinConfiguration.newlines(), out);
    }

    private void updateLabel(Npc n) {
        Component c = labelComponent(n);
        String key = c == null ? "" : mm.serialize(c);
        n.labelText = key;
        if (c == null) {
            removeLabel(n);
        } else if (n.labelEntity != null && n.labelEntity.isValid()) {
            n.labelEntity.text(c);
        }
    }

    private void ensureLabel(Npc n, Location l) {
        if (l == null || n.labelText.isEmpty() && labelComponent(n) == null) return;
        if (n.labelEntity != null && n.labelEntity.isValid()) return;
        if (!l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) return;
        Component c = labelComponent(n);
        if (c == null) return;
        n.labelEntity = l.getWorld().spawn(l.clone().add(0, 2.05, 0), TextDisplay.class, td -> {
            td.getPersistentDataContainer().set(labelKey, PersistentDataType.STRING, n.id);
            td.setPersistent(false);
            td.text(c);
            td.setBillboard(Display.Billboard.CENTER);
            td.setShadowed(true);
            td.setAlignment(TextDisplay.TextAlignment.CENTER);
            td.setBackgroundColor(Color.fromARGB(70, 0, 0, 0));
            td.setLineWidth(200);
        });
    }

    private void removeLabel(Npc n) {
        if (n.labelEntity != null && n.labelEntity.isValid()) n.labelEntity.remove();
        n.labelEntity = null;
    }

    // ───────────────────────────── Évènements ─────────────────────────────

    private void reset(Player p, long delayMs) {
        for (Npc n : npcs.values()) {
            n.viewers.remove(p.getUniqueId());
            n.looking.remove(p.getUniqueId());
        }
        notBefore.put(p.getUniqueId(), System.currentTimeMillis() + delayMs);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        teamSent.remove(e.getPlayer().getUniqueId());
        reset(e.getPlayer(), 1500);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        for (Npc n : npcs.values()) {
            n.viewers.remove(u);
            n.looking.remove(u);
        }
        teamSent.remove(u);
        notBefore.remove(u);
        clickCooldown.remove(u);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        reset(e.getPlayer(), 1000);
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        reset(e.getPlayer(), 1000);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        reset(e.getPlayer(), 1000);
    }

    @EventHandler
    public void onClick(PlayerUseUnknownEntityEvent e) {
        Npc hit = null;
        for (Npc n : npcs.values()) if (n.entityId == e.getEntityId()) hit = n;
        if (hit == null) return;
        Player p = e.getPlayer();
        Npc n = hit;
        Runnable action = () -> {
            long now = System.currentTimeMillis();
            if (now < clickCooldown.getOrDefault(p.getUniqueId(), 0L)) return;
            clickCooldown.put(p.getUniqueId(), now + 700);
            String cmd;
            if (n.type.equals("podium")) {
                if (n.occupant == null) return;
                cmd = plugin.getConfig().getString("podium-command", "succes <player>").replace("<player>", n.occupant.name());
            } else {
                cmd = n.command == null ? "" : n.command.replace("<player>", p.getName());
            }
            if (cmd.isBlank()) return;
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            if (cmd.startsWith("console:")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.substring(8).trim());
            } else {
                p.performCommand(cmd);
            }
        };
        if (Bukkit.isPrimaryThread()) action.run();
        else Bukkit.getScheduler().runTask(plugin, action);
    }
}
