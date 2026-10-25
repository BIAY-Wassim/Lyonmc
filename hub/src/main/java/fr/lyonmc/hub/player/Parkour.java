package fr.lyonmc.hub.player;

import fr.lyonmc.hub.world.HubWorlds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Parkour du hub : départ, étapes dans l'ordre, arrivée, chrono à l'écran, meilleurs temps, récompense au premier passage
 * et classement affiché dans le hub. Se configure avec /lyonhub parkour (en se plaçant sur les plaques).
 */
public final class Parkour implements Listener, CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static final class Run {
        final long start = System.currentTimeMillis();
        int next;
        Location last;
        double lastY;

        Run(Location at) {
            last = at.clone();
            lastY = at.getY();
        }
    }

    private record Point(String world, int x, int y, int z) {
        boolean is(Block b) {
            return b.getX() == x && b.getY() == y && b.getZ() == z && b.getWorld().getName().equals(world);
        }

        Location center() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x + 0.5, y, z + 0.5);
        }

        String serialize() {
            return world + ";" + x + ";" + y + ";" + z;
        }

        static Point of(Block b) {
            return new Point(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        }

        static Point parse(String s) {
            if (s == null) return null;
            String[] p = s.split(";");
            try {
                return new Point(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private final JavaPlugin plugin;
    private final HubWorlds worlds;
    private final HubPlayers players;
    private final File file;
    private final YamlConfiguration data;
    private final NamespacedKey boardKey;
    private Point start, finish;
    private final List<Point> checkpoints = new ArrayList<>();
    private final Map<UUID, Run> runs = new HashMap<>();
    private final Set<UUID> ownTeleport = new HashSet<>();

    Parkour(JavaPlugin plugin, HubWorlds worlds, HubPlayers players) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.players = players;
        this.file = new File(plugin.getDataFolder(), "parkour.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        this.boardKey = new NamespacedKey(plugin, "parkour-classement");
        load();
    }

    private void load() {
        start = Point.parse(data.getString("points.depart"));
        finish = Point.parse(data.getString("points.arrivee"));
        checkpoints.clear();
        for (String s : data.getStringList("points.etapes")) {
            Point p = Point.parse(s);
            if (p != null) checkpoints.add(p);
        }
    }

    private void save() {
        data.set("points.depart", start == null ? null : start.serialize());
        data.set("points.arrivee", finish == null ? null : finish.serialize());
        List<String> cps = new ArrayList<>();
        for (Point p : checkpoints) cps.add(p.serialize());
        data.set("points.etapes", cps);
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Impossible d'enregistrer parkour.yml : " + e.getMessage());
        }
    }

    /** Installe un parkour complet (hub tout fait) : départ, étapes, arrivée et classement affiché. */
    public void setup(Block startBlock, List<Block> steps, Block finishBlock, Location boardAt) {
        for (UUID u : new ArrayList<>(runs.keySet())) {
            Player o = Bukkit.getPlayer(u);
            if (o != null) stop(o, null);
        }
        start = Point.of(startBlock);
        finish = Point.of(finishBlock);
        checkpoints.clear();
        for (Block b : steps) checkpoints.add(Point.of(b));
        if (boardAt != null) {
            removeBoards(boardAt.getWorld());
            data.set("classement", boardAt.getWorld().getName() + ";" + boardAt.getX() + ";" + boardAt.getY() + ";" + boardAt.getZ() + ";" + boardAt.getYaw());
        }
        save();
        updateBoard();
    }

    public boolean ready() {
        return start != null && finish != null;
    }

    private ConfigurationSection cfg() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("parkour");
        if (c == null && plugin.getConfig().getDefaults() != null) c = plugin.getConfig().getDefaults().getConfigurationSection("parkour");
        return c;
    }

    private boolean enabled() {
        ConfigurationSection c = cfg();
        return c == null || c.getBoolean("enabled", true);
    }

    static String time(long ms) {
        long m = ms / 60000;
        double s = (ms % 60000) / 1000.0;
        return (m > 0 ? m + ":" + String.format(Locale.FRANCE, "%05.2f", s) : String.format(Locale.FRANCE, "%.2f", s)) + " s";
    }

    // ───────────────────────────── Administration ─────────────────────────────

    /** /lyonhub parkour <depart|etape|arrivee|classement|effacer|info> */
    public void admin(Player p, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        Block b = p.getLocation().getBlock();
        if (!sub.isEmpty() && !sub.equals("info") && !worlds.isHub(p.getWorld())) {
            p.sendMessage(MM.deserialize("<gray>Le parkour se construit dans le monde du hub."));
            return;
        }
        switch (sub) {
            case "depart" -> {
                start = Point.of(b);
                save();
                p.sendMessage(MM.deserialize("<gray>Départ du parkour placé ici. Ajoute les étapes avec <white>/lyonhub parkour etape</white>, puis l'arrivée."));
            }
            case "etape" -> {
                checkpoints.add(Point.of(b));
                save();
                p.sendMessage(MM.deserialize("<gray>Étape <white>" + checkpoints.size() + "</white> placée ici."));
            }
            case "arrivee", "arrivée" -> {
                finish = Point.of(b);
                save();
                p.sendMessage(MM.deserialize("<gray>Arrivée du parkour placée ici. Le parkour est prêt !"));
            }
            case "classement" -> {
                Location l = p.getLocation().clone().add(0, 1.6, 0);
                data.set("classement", l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw());
                removeBoards(l.getWorld());
                save();
                updateBoard();
                p.sendMessage(MM.deserialize("<gray>Classement du parkour affiché ici (mis à jour tout seul)."));
            }
            case "effacer" -> {
                start = null;
                finish = null;
                checkpoints.clear();
                for (UUID u : new ArrayList<>(runs.keySet())) {
                    Player o = Bukkit.getPlayer(u);
                    if (o != null) stop(o, null);
                }
                save();
                p.sendMessage(MM.deserialize("<gray>Points du parkour effacés <dark_gray>(les meilleurs temps sont gardés)."));
            }
            case "temps-reset" -> {
                data.set("temps", null);
                save();
                updateBoard();
                p.sendMessage(MM.deserialize("<gray>Meilleurs temps du parkour effacés."));
            }
            default -> {
                p.sendMessage(MM.deserialize("<white><bold>Parkour</bold></white> <dark_gray>· " + (ready() ? "<green>prêt" : "<red>pas encore prêt")
                        + "</dark_gray> <gray>(" + checkpoints.size() + " étape(s))"));
                for (String l : List.of(
                        "<white>/lyonhub parkour depart</white> <dark_gray>debout sur la plaque de départ",
                        "<white>/lyonhub parkour etape</white> <dark_gray>sur chaque plaque d'étape, dans l'ordre",
                        "<white>/lyonhub parkour arrivee</white> <dark_gray>sur la plaque d'arrivée",
                        "<white>/lyonhub parkour classement</white> <dark_gray>afficher les meilleurs temps ici",
                        "<white>/lyonhub parkour effacer</white> <dark_gray>tout recommencer (les temps restent)",
                        "<white>/lyonhub parkour temps-reset</white> <dark_gray>effacer les meilleurs temps")) {
                    p.sendMessage(MM.deserialize("  " + l));
                }
            }
        }
    }

    // ───────────────────────────── /parkour ─────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "top", "classement" -> sendTop(p);
            case "quitter", "stop" -> {
                if (runs.containsKey(p.getUniqueId())) stop(p, "<gray>Parkour quitté.");
                else p.sendMessage(MM.deserialize("<gray>Tu n'es pas en train de faire le parkour."));
            }
            default -> goToStart(p);
        }
        return true;
    }

    /** Emmène au départ du parkour (depuis le hub). */
    public void goToStart(Player p) {
        if (!ready() || !enabled()) {
            p.sendMessage(MM.deserialize("<gray>Le parkour n'est pas encore ouvert."));
            return;
        }
        if (!worlds.isHub(p.getWorld())) {
            p.sendMessage(MM.deserialize("<gray>Le parkour est au hub : <white><click:run_command:'/hub'><u>/hub</u></click></white> d'abord."));
            return;
        }
        Location l = start.center();
        if (l == null) return;
        l.setYaw(p.getLocation().getYaw());
        teleport(p, l);
        long best = best(p.getUniqueId());
        p.sendMessage(MM.deserialize("<#9fffe0>✦</#9fffe0> <gray>Avance sur la plaque pour lancer le chrono."
                + (best > 0 ? " Ton record : <white>" + time(best) + "</white>." : "")));
    }

    private void sendTop(Player p) {
        List<String[]> top = top(10);
        p.sendMessage(MM.deserialize("<#9fffe0>✦</#9fffe0> <white><bold>Parkour · meilleurs temps</bold>"));
        if (top.isEmpty()) p.sendMessage(MM.deserialize("  <dark_gray>Personne ne l'a encore fini : à toi de jouer !"));
        for (int i = 0; i < top.size(); i++) {
            p.sendMessage(MM.deserialize("  <white>" + (i + 1) + ".</white> <gray>" + top.get(i)[0] + "</gray> <dark_gray>·</dark_gray> <#9fffe0>" + top.get(i)[1]));
        }
        long best = best(p.getUniqueId());
        if (best > 0) p.sendMessage(MM.deserialize("  <dark_gray>Ton record : " + time(best)));
    }

    // ───────────────────────────── Partie ─────────────────────────────

    private void begin(Player p, Location at) {
        boolean restart = runs.containsKey(p.getUniqueId());
        runs.put(p.getUniqueId(), new Run(at));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, restart ? 1.2f : 1.6f);
        p.showTitle(net.kyori.adventure.title.Title.title(MM.deserialize("<#9fffe0><bold>Parkour"),
                MM.deserialize(checkpoints.isEmpty() ? "<gray>Atteins l'arrivée !" : "<gray>" + checkpoints.size() + " étape(s) puis l'arrivée"),
                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(100), java.time.Duration.ofMillis(1200), java.time.Duration.ofMillis(300))));
        if (!restart) giveItems(p);
    }

    /** Objets du parkour à la place de ceux du hub (les objets reçus au hub ne sont jamais effacés). */
    private void giveItems(Player p) {
        PlayerInventory inv = p.getInventory();
        players.removeHubItems(p);
        players.placeHubItem(p, 3, players.hubItem(Material.HEAVY_WEIGHTED_PRESSURE_PLATE, "<#9fffe0><bold>Dernière étape</bold> <gray>(clic droit)",
                List.of("<gray>Revenir à la dernière étape atteinte"), "parkour:etape"));
        players.placeHubItem(p, 4, players.hubItem(Material.OAK_DOOR, "<white><bold>Recommencer</bold> <gray>(clic droit)",
                List.of("<gray>Retour au départ, chrono remis à zéro"), "parkour:recommencer"));
        players.placeHubItem(p, 5, players.hubItem(Material.RED_BED, "<red><bold>Quitter le parkour</bold> <gray>(clic droit)",
                List.of("<gray>Retrouver les objets du hub"), "parkour:quitter"));
        inv.setHeldItemSlot(3);
    }

    /** Actions des objets du parkour. */
    public void action(Player p, String what) {
        Run r = runs.get(p.getUniqueId());
        switch (what) {
            case "etape" -> {
                if (r != null) back(p, r);
            }
            case "recommencer" -> {
                Location l = start == null ? null : start.center();
                if (l == null) return;
                runs.remove(p.getUniqueId());
                l.setYaw(p.getLocation().getYaw());
                teleport(p, l);
                giveItems(p);
                p.sendMessage(MM.deserialize("<gray>Retour au départ : avance sur la plaque pour relancer le chrono."));
            }
            case "quitter" -> stop(p, "<gray>Parkour quitté.");
            case "aller" -> goToStart(p);
            default -> {
            }
        }
    }

    private void back(Player p, Run r) {
        p.setFallDistance(0);
        teleport(p, r.last);
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.4f, 1.6f);
    }

    /** Fin de partie (abandon, téléportation...) : les objets du hub reviennent. */
    private void stop(Player p, String message) {
        runs.remove(p.getUniqueId());
        if (message != null) p.sendMessage(MM.deserialize(message));
        if (worlds.isHub(p.getWorld()) && !players.building(p)) {
            players.removeHubItems(p);
            players.giveHubItems(p);
        }
    }

    private void finish(Player p, Run r) {
        long ms = System.currentTimeMillis() - r.start;
        runs.remove(p.getUniqueId());
        ConfigurationSection c = cfg();
        long min = c == null ? 0 : c.getLong("temps-minimum-secondes", 0) * 1000L;
        if (ms < min) {
            p.sendMessage(MM.deserialize("<gray>Temps impossible (" + time(ms) + ") : il n'est pas enregistré."));
            stop(p, null);
            return;
        }
        String k = "temps." + p.getUniqueId();
        long before = data.getLong(k + ".ms", 0);
        boolean first = !data.getBoolean(k + ".fini", false);
        boolean record = before == 0 || ms < before;
        List<String[]> topBefore = top(1);
        data.set(k + ".pseudo", p.getName());
        data.set(k + ".fini", true);
        data.set(k + ".essais", data.getInt(k + ".essais") + 1);
        if (record) data.set(k + ".ms", ms);
        save();

        p.showTitle(net.kyori.adventure.title.Title.title(MM.deserialize("<#9fffe0><bold>" + time(ms)),
                MM.deserialize(record ? (before == 0 ? "<white>Parkour terminé !" : "<#ffd86b>Nouveau record personnel !") : "<gray>Record : " + time(before)),
                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(150), java.time.Duration.ofMillis(2500), java.time.Duration.ofMillis(600))));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
        p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 40, 0.5, 0.8, 0.5, 0.2);
        firework(p.getLocation());
        p.sendMessage(MM.deserialize("<#9fffe0>✦</#9fffe0> <gray>Parkour terminé en <white>" + time(ms) + "</white>"
                + (record && before > 0 ? " <#ffd86b>(record personnel, avant " + time(before) + ")" : "") + "<gray>."));

        // Meilleur temps du serveur
        boolean serverRecord = topBefore.isEmpty() || ms < parseMs(topBefore.get(0)[2]);
        if (serverRecord && record) {
            Bukkit.broadcast(MM.deserialize("<#9fffe0>✦</#9fffe0> <white>" + p.getName() + "</white> <gray>bat le record du parkour du hub en <#9fffe0>" + time(ms) + "</#9fffe0> !"));
        }
        if (first && c != null) {
            for (String cmd : c.getStringList("recompense-commandes")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
            }
            String msg = c.getString("message-premiere-fois", "");
            if (!msg.isEmpty()) p.sendMessage(MM.deserialize(msg));
        }
        stop(p, null);
        updateBoard();
        // Retour au départ après quelques secondes (l'arrivée est souvent tout en haut)
        if (c == null || c.getBoolean("retour-apres-arrivee", true)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Location l = start == null ? null : start.center();
                if (l == null || !p.isOnline() || running(p) || !worlds.isHub(p.getWorld())) return;
                l.setYaw(p.getLocation().getYaw());
                teleport(p, l);
            }, 100L);
        }
    }

    private static long parseMs(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    private void firework(Location l) {
        Firework fw = l.getWorld().spawn(l.clone().add(0, 1, 0), Firework.class, f -> {
            FireworkMeta m = f.getFireworkMeta();
            m.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(Color.fromRGB(0x9FFFE0), Color.fromRGB(0xFFD86B))
                    .withFade(Color.WHITE).trail(true).build());
            m.setPower(0);
            f.setFireworkMeta(m);
            f.getPersistentDataContainer().set(boardKey, PersistentDataType.BYTE, (byte) 1);
        });
        Bukkit.getScheduler().runTaskLater(plugin, fw::detonate, 2L);
    }

    private void teleport(Player p, Location l) {
        ownTeleport.add(p.getUniqueId());
        p.teleportAsync(l).whenComplete((ok, err) -> Bukkit.getScheduler().runTask(plugin, () -> ownTeleport.remove(p.getUniqueId())));
    }

    // ───────────────────────────── Temps ─────────────────────────────

    public long best(UUID u) {
        return data.getLong("temps." + u + ".ms", 0);
    }

    /** [pseudo, temps lisible, ms] triés du plus rapide au plus lent. */
    List<String[]> top(int n) {
        List<String[]> out = new ArrayList<>();
        ConfigurationSection t = data.getConfigurationSection("temps");
        if (t == null) return out;
        for (String k : t.getKeys(false)) {
            long ms = t.getLong(k + ".ms", 0);
            if (ms > 0) out.add(new String[]{t.getString(k + ".pseudo", "?"), time(ms), String.valueOf(ms)});
        }
        out.sort((a, b) -> Long.compare(Long.parseLong(a[2]), Long.parseLong(b[2])));
        return out.size() > n ? out.subList(0, n) : out;
    }

    // ───────────────────────────── Classement affiché ─────────────────────────────

    private Location boardLocation() {
        String s = data.getString("classement");
        if (s == null) return null;
        String[] p = s.split(";");
        try {
            World w = Bukkit.getWorld(p[0]);
            return w == null ? null : new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]), Float.parseFloat(p[4]), 0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void removeBoards(World w) {
        for (Entity e : w.getEntitiesByClass(TextDisplay.class)) {
            if (e.getPersistentDataContainer().has(boardKey, PersistentDataType.BYTE)) e.remove();
        }
    }

    /** Toutes les 10 secondes (et après chaque arrivée). */
    public void updateBoard() {
        Location l = boardLocation();
        if (l == null || !l.isChunkLoaded()) return;
        StringBuilder sb = new StringBuilder("<#9fffe0><bold>Parkour du hub</bold>\n<gray>Meilleurs temps\n");
        List<String[]> top = top(10);
        if (top.isEmpty()) sb.append("\n<dark_gray>Personne ne l'a encore fini");
        for (int i = 0; i < top.size(); i++) {
            String color = i == 0 ? "<#ffd86b>" : i == 1 ? "<#d8d8d8>" : i == 2 ? "<#d29a6a>" : "<gray>";
            sb.append("\n").append(color).append(i + 1).append(". <white>").append(top.get(i)[0]).append("</white> <dark_gray>·</dark_gray> ")
                    .append(color).append(top.get(i)[1]);
        }
        sb.append("\n\n<dark_gray>/parkour pour essayer");
        Component text = MM.deserialize(sb.toString());
        TextDisplay found = null;
        for (Entity e : l.getWorld().getNearbyEntities(l, 2, 3, 2)) {
            if (e instanceof TextDisplay td && td.getPersistentDataContainer().has(boardKey, PersistentDataType.BYTE)) {
                if (found == null) found = td;
                else td.remove();
            }
        }
        if (found == null) {
            found = l.getWorld().spawn(l, TextDisplay.class, td -> {
                td.setBillboard(Display.Billboard.VERTICAL);
                td.setDefaultBackground(false);
                td.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
                td.setShadowed(true);
                td.setPersistent(true);
                td.getPersistentDataContainer().set(boardKey, PersistentDataType.BYTE, (byte) 1);
            });
        }
        found.text(text);
    }

    // ───────────────────────────── Évènements ─────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!ready() || !enabled()) return;
        Location to = e.getTo();
        Block b = to.getBlock();
        Player p = e.getPlayer();
        Run r = runs.get(p.getUniqueId());
        if (r != null && to.getY() < r.lastY - (cfg() == null ? 8 : cfg().getDouble("chute-max", 8))) {
            back(p, r);
            return;
        }
        if (e.getFrom().getBlock().equals(b)) return;
        if (!worlds.isHub(b.getWorld()) || players.building(p)) return;
        if (start.is(b)) {
            begin(p, to);
            return;
        }
        if (r == null) return;
        if (r.next < checkpoints.size() && checkpoints.get(r.next).is(b)) {
            r.next++;
            r.last = to.clone();
            r.lastY = to.getY();
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f + 0.05f * r.next);
            p.sendMessage(MM.deserialize("<#9fffe0>✦</#9fffe0> <gray>Étape <white>" + r.next + "/" + checkpoints.size() + "</white> en <white>"
                    + time(System.currentTimeMillis() - r.start) + "</white>"));
            return;
        }
        for (int i = r.next + 1; i < checkpoints.size(); i++) {
            if (checkpoints.get(i).is(b)) {
                p.sendActionBar(MM.deserialize("<red>Tu as sauté une étape : passe d'abord par l'étape " + (r.next + 1)));
                return;
            }
        }
        if (finish.is(b)) {
            if (r.next >= checkpoints.size()) finish(p, r);
            else p.sendActionBar(MM.deserialize("<red>Il te manque " + (checkpoints.size() - r.next) + " étape(s) avant l'arrivée"));
        }
    }

    /** Toutes les 2 ticks : le chrono au-dessus de la barre d'objets. */
    public void tick() {
        if (runs.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Run> e : runs.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            Run r = e.getValue();
            p.sendActionBar(MM.deserialize("<#9fffe0>⏱ " + time(now - r.start) + "</#9fffe0>"
                    + (checkpoints.isEmpty() ? "" : " <dark_gray>·</dark_gray> <gray>étape " + r.next + "/" + checkpoints.size())));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (!runs.containsKey(p.getUniqueId()) || ownTeleport.contains(p.getUniqueId())) return;
        // Une autre téléportation (commande, perle...) annule la partie
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (runs.containsKey(p.getUniqueId())) stop(p, "<gray>Parkour annulé (téléportation).");
        });
    }

    @EventHandler
    public void onFly(PlayerToggleFlightEvent e) {
        if (e.isFlying() && runs.containsKey(e.getPlayer().getUniqueId())) stop(e.getPlayer(), "<gray>Parkour annulé : pas de vol pendant le parkour.");
    }

    @EventHandler
    public void onGlide(EntityToggleGlideEvent e) {
        if (e.isGliding() && e.getEntity() instanceof Player p && runs.containsKey(p.getUniqueId())) stop(p, "<gray>Parkour annulé : pas d'élytres pendant le parkour.");
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        runs.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        runs.remove(e.getPlayer().getUniqueId());
        ownTeleport.remove(e.getPlayer().getUniqueId());
    }

    public boolean running(Player p) {
        return runs.containsKey(p.getUniqueId());
    }
}
