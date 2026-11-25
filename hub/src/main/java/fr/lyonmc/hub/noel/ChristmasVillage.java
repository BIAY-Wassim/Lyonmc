package fr.lyonmc.hub.noel;

import fr.lyonmc.hub.npc.NpcService;
import fr.lyonmc.hub.player.HubPlayers;
import fr.lyonmc.hub.world.HubWorlds;
import fr.lyonmc.hub.world.SchematicPaster;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.logging.Level;

/**
 * Le village de Noël (happychristmas.schem), dans son propre monde vide (« noel » par défaut, à part du hub),
 * avec juste à côté l'arène de la bataille de boules de neige : les joueurs ont la vue sur le village en jouant.
 * /noel pour y aller. Le monde compte comme le hub (inventaire de survie mis de côté, mode aventure).
 */
public final class ChristmasVillage implements CommandExecutor, Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<#ff6b6b>❄</#ff6b6b> <dark_gray>·</dark_gray> ";
    /** Repères dans happychristmas.schem (depuis son coin) : arrivée face au château, attente près de la fontaine, arène. */
    private static final double[] SPAWN = {84.5, 77, 120.5, 90};
    private static final double[] LOBBY = {105.5, 76, 124.5, -90};
    private static final double[] NPC = {107.5, 75, 121.5, 90};
    private static final int[] ARENA = {150, 74, 120};
    /** Coin du village par défaut : l'arrivée tombe au centre du monde (0, 65, 0). */
    private static final String ORIGIN = "-84 -12 -120";
    /** Emplacement de la version 1.9.0 (dans le monde du hub, à côté du hub médiéval). */
    private static final String OLD_ORIGIN = "125 -12 -120";
    public static final String NPC_ID = "noel-bataille";
    /** PNJ du hub qui emmène à l'arène. */
    public static final String HUB_NPC_ID = "noel-arene";
    /** Skin de Père Noël (texture signée par Mojang, via mineskin.org « Santa »). */
    private static final String SANTA_VALUE = "ewogICJ0aW1lc3RhbXAiIDogMTc2NjI0NzcyMzQ3OSwKICAicHJvZmlsZUlkIiA6ICJkYzNhNmI2MTAyOWY0Y2Y2OGU3MzFlOWI1" +
            "Zjg0ZWJlYyIsCiAgInByb2ZpbGVOYW1lIiA6ICIxaWxvIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVy" +
            "ZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJl" +
            "LzFmY2Y3NGI5NmMyOWU4M2JkMTFiMDUyZjUzYWYzNmIyYTRiNThmMDMwYWVmNGQ3M2FmOGNjMWU2NmMzYzc5MGEiLAogICAgICAi" +
            "bWV0YWRhdGEiIDogewogICAgICAgICJtb2RlbCIgOiAic2xpbSIKICAgICAgfQogICAgfQogIH0KfQ==";
    private static final String SANTA_SIGNATURE = "KMARUpfdw/WQSyMl/3nsKFeqt2efmJClpf7gygggR47d78yauyPBlsLxbK1RySPoR4aByECBAltMtGc9za8NwDS6i9UrbwkT2dMH" +
            "aNP0ax3YEvxkQIVspINHDqrdCNrIIGB81wz5nUX/3A0XQUq2tnTiOd4CjdXbS+4zY9N9sgxmu32cBoC7I01xp/H+USEYHqqqBbaN" +
            "XMmyutAEBMqN4G6ytVRCGrSZ9D3OJW6FGWkmSGcJuRqAoV0jasXm3TZadDAzAEbZkLjIAN0a1uhyjgB8YXc+Q9CD/oJ2oNx88/8z" +
            "uUV6zQyi6X5NwTJtZNdiJSxWkMPv0m0aDnJjqQNQCLV/xc9AH2xPes2MHVwxQp1vwhrTD5OH/rdCPaQX9NPVVKHMrVpYNTVflvZY" +
            "11jvqE56wLBp7nOErAN4RKj1au9a74dmQfig2uzK4KVGKDVIW6NG93q5DbnKfYy59CXnqg9m/T+xtQ0MzfHyTQoXolYGGtYfmZT9" +
            "RedNHWWr9BimfT/2QFqIDiPVy2Cs5xRWvxEcTQrnDzzleZuCXBgjG04/s0StZ4UWfnHYDTO1axmY+VMGJw/ONINLfI3+mjfEfes5" +
            "jZ94dtWDDF8aLTgAzkKiwqJ8Au5RhvJ5iA0jtdexBqYEzQfSxOYaqfQgEz97XleYav8ZrXZj5O3fhtykM8E=";

    private final JavaPlugin plugin;
    private final HubWorlds worlds;
    private final HubPlayers players;
    private final NpcService npcs;
    private final SchematicPaster paster;
    private final SnowBattle battle;
    private final File stateFile;
    private final YamlConfiguration state;
    private boolean busy;

    public ChristmasVillage(JavaPlugin plugin, HubWorlds worlds, HubPlayers players, NpcService npcs, SchematicPaster paster) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.players = players;
        this.npcs = npcs;
        this.paster = paster;
        this.stateFile = new File(plugin.getDataFolder(), "noel.yml");
        this.state = YamlConfiguration.loadConfiguration(stateFile);
        this.battle = new SnowBattle(plugin, worlds, players, this);
        migrateConfig();
    }

    /** 1.9.0 -> 1.9.1 : le village passe dans son propre monde (centré sur 0, 0). */
    private void migrateConfig() {
        var cfg = plugin.getConfig();
        if (!cfg.isSet("noel") || cfg.isSet("noel.monde")) return;
        cfg.set("noel.monde", "noel");
        cfg.setComments("noel.monde", List.of("Monde à part pour le village et la bataille (monde vide créé par /lyonhub noel installer)"));
        if (OLD_ORIGIN.equals(cfg.getString("noel.origine"))) {
            cfg.set("noel.origine", ORIGIN);
            cfg.setComments("noel.origine", List.of("Coin du village (x y z) : l'arrivée tombe au centre du monde"));
        }
        plugin.saveConfig();
        plugin.getLogger().info("Village de Noël : il a maintenant son propre monde (noel.monde dans config.yml).");
    }

    public SnowBattle battle() {
        return battle;
    }

    private void msg(CommandSender to, String mini) {
        to.sendMessage(MM.deserialize(PREFIX + mini));
    }

    private void saveState() {
        try {
            state.save(stateFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Impossible d'enregistrer noel.yml", e);
        }
    }

    /** Nom du monde du village (« noel.monde » dans la config). */
    public String worldName() {
        String n = plugin.getConfig().getString("noel.monde", "noel");
        return n == null || n.isBlank() ? "noel" : n.trim();
    }

    public boolean isVillageWorld(World w) {
        return w != null && w.getName().equals(worldName());
    }

    public World world() {
        return Bukkit.getWorld(worldName());
    }

    public boolean installed() {
        return state.getBoolean("installe", false) && worldName().equals(state.getString("monde", "")) && world() != null;
    }

    /** Ouvert aux joueurs : installé et « noel.actif » dans la config. */
    public boolean open() {
        return installed() && plugin.getConfig().getBoolean("noel.actif", true);
    }

    private static int[] parse(String s, String def) {
        try {
            String[] p = s.trim().split("[\\s,;]+");
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
        } catch (RuntimeException e) {
            return def == null ? null : parse(def, null);
        }
    }

    /** Coin du village (x y z) : « noel.origine » dans la config. */
    private int[] origin() {
        String s = plugin.getConfig().getString("noel.origine", ORIGIN);
        int[] o = parse(s == null ? ORIGIN : s, null);
        if (o == null) {
            plugin.getLogger().warning("config.yml : noel.origine invalide (" + s + "), format « x y z »");
            return parse(ORIGIN, null);
        }
        return o;
    }

    private Location rel(double[] r) {
        World w = world();
        if (w == null) return null;
        int[] o = origin();
        return new Location(w, o[0] + r[0], o[1] + r[1], o[2] + r[2], (float) r[3], 0);
    }

    private Location stored(String key) {
        String s = state.getString(key, "");
        if (s.isEmpty()) return null;
        String[] p = s.split(";");
        World w = Bukkit.getWorld(p[0]);
        if (w == null || p.length < 6 || !isVillageWorld(w)) return null;
        try {
            return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]), Float.parseFloat(p[4]), Float.parseFloat(p[5]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void store(String key, Location l) {
        state.set(key, l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch());
        saveState();
    }

    /** Arrivée de /noel. */
    public Location spawn() {
        Location l = stored("spawn");
        return l != null ? l : rel(SPAWN);
    }

    /** Point d'attente de la bataille (et retour des éliminés), face à l'arène. */
    public Location lobby() {
        Location l = stored("attente");
        return l != null ? l : rel(LOBBY);
    }

    /** Centre de l'étage du haut de l'arène, ou null si le village n'est pas installé. */
    Location arenaCenter() {
        if (!installed()) return null;
        String s = plugin.getConfig().getString("noel.bataille.centre", "");
        World w = world();
        if (s != null && !s.isBlank()) {
            int[] c = parse(s, null);
            if (c != null) return new Location(w, c[0], c[1], c[2]);
            plugin.getLogger().warning("config.yml : noel.bataille.centre invalide (" + s + "), format « x y z »");
        }
        int[] o = origin();
        return new Location(w, o[0] + ARENA[0], o[1] + ARENA[1], o[2] + ARENA[2]);
    }

    /** Au démarrage : l'arène est prête et le site a ses chiffres ; le texte des PNJ suit la bataille en direct. */
    public void startup() {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (installed()) battle.rebuild();
            battle.exportAsync();
        }, 60L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::updateNpcLabels, 80L, 40L);
    }

    private void updateNpcLabels() {
        List<String> lines = null;
        for (String id : List.of(NPC_ID, HUB_NPC_ID)) {
            NpcService.Npc n = npcs.get(id);
            if (n == null) continue;
            if (lines == null) lines = battle.npcLabel();
            npcs.setLiveLabel(n, lines);
        }
    }

    // ───────────────────────────── Le monde du village ─────────────────────────────

    /** Personne ne prend de dégâts ni ne perd de faim au village (le vide ramène à l'arrivée). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !isVillageWorld(p.getWorld())) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID) return; // géré par le retour au spawn du hub
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && isVillageWorld(p.getWorld()) && e.getFoodLevel() < p.getFoodLevel()) e.setCancelled(true);
    }

    // ───────────────────────────── /noel ─────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            msg(sender, "<gray>À faire en jeu.");
            return true;
        }
        if (!open()) {
            msg(p, installed() ? "<gray>L'arène de Noël est fermée pour cette année. À bientôt !"
                    : "<gray>L'arène de Noël n'est pas encore installée.");
            return true;
        }
        Location l = spawn();
        if (l == null) return true;
        if (battle.playing(p)) battle.leave(p);
        p.teleportAsync(l).thenAccept(ok -> {
            if (!ok || !p.isOnline()) return;
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.4f);
            msg(p, "<white>Bienvenue à l'arène de Noël !</white> <gray>La bataille de boules de neige est derrière toi, après la fontaine : "
                    + "<click:run_command:'/bataille'><hover:show_text:'<gray>Rejoindre la bataille'><white><u>/bataille</u></white></hover></click>"
                    + (Bukkit.getPluginCommand("calendrier") != null
                    ? "<gray>. Et ta case du jour : <click:run_command:'/calendrier'><hover:show_text:\"<gray>Calendrier de l'Avent\"><white><u>/calendrier</u></white></hover></click>" : "")
                    + "<gray>. Retour au hub : <click:run_command:'/hub'><white><u>/hub</u></white></click>");
        });
        return true;
    }

    // ───────────────────────────── /lyonhub noel ─────────────────────────────

    public void admin(Player p, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "installer", "install" -> install(p, args);
            case "retirer", "enlever" -> removeOld(p, args);
            case "spawn" -> {
                if (!isVillageWorld(p.getWorld())) {
                    msg(p, "<gray>Va d'abord dans le monde de l'arène (<white>/noel</white>).");
                    return;
                }
                store("spawn", p.getLocation());
                p.getWorld().setSpawnLocation(p.getLocation());
                msg(p, "<gray>Arrivée de <white>/noel</white> placée ici.");
            }
            case "attente" -> {
                if (!isVillageWorld(p.getWorld())) {
                    msg(p, "<gray>Va d'abord dans le monde de l'arène (<white>/noel</white>).");
                    return;
                }
                store("attente", p.getLocation());
                msg(p, "<gray>Point d'attente de la bataille placé ici (les éliminés y reviennent).");
            }
            case "pnj" -> {
                String skin = args.length > 2 ? args[2] : null;
                createNpc(NPC_ID, p.getLocation(), skin);
                msg(p, "<gray>PNJ de la bataille placé ici (" + skinText(skin) + ").");
            }
            case "pnj-hub", "pnjhub" -> {
                if (isVillageWorld(p.getWorld())) {
                    msg(p, "<gray>Ce PNJ se met au hub : va au spawn du hub (<white>/hub</white>), puis retape la commande.");
                    return;
                }
                String skin = args.length > 2 ? args[2] : null;
                createNpc(HUB_NPC_ID, p.getLocation(), skin);
                msg(p, "<green>PNJ de l'arène de Noël placé ici</green> <gray>(" + skinText(skin) + "). Un clic : le joueur part dans le monde "
                        + worldName() + " et entre dans la file ; la partie démarre dès qu'ils sont " + Math.max(2, plugin.getConfig().getInt("noel.bataille.joueurs-min", 2)) + ".");
                if (!installed()) p.sendMessage(MM.deserialize("  <gray>N'oublie pas d'installer l'arène : <white>/lyonhub noel installer"));
            }
            case "arene", "arène" -> {
                if (!installed()) {
                    msg(p, "<gray>Installe d'abord l'arène : <white>/lyonhub noel installer");
                    return;
                }
                if (battle.running()) {
                    msg(p, "<gray>Attends la fin de la partie en cours.");
                    return;
                }
                battle.removeArena();
                battle.rebuild();
                msg(p, "<gray>Arène refaite (centre : <white>" + describe(arenaCenter()) + "</white>).");
            }
            default -> {
                msg(p, "<white><bold>Arène de Noël</bold></white> <dark_gray>(monde " + worldName() + ")</dark_gray> "
                        + (installed() ? (open() ? "<green>ouvert" : "<red>fermé</red> <dark_gray>(noel.actif: false)") : "<gray>pas installé"));
                for (String line : List.of(
                        "<white>/lyonhub noel installer</white> <dark_gray>créer le monde du village et y coller happychristmas.schem et l'arène",
                        "<white>/lyonhub noel spawn</white> <dark_gray>arrivée de /noel ici",
                        "<white>/lyonhub noel attente</white> <dark_gray>point d'attente de la bataille ici",
                        "<white>/lyonhub noel pnj-hub [skin]</white> <dark_gray>PNJ « Arène de Noël » au hub (clic : on part jouer)",
                        "<white>/lyonhub noel pnj [skin]</white> <dark_gray>PNJ de la bataille ici, dans le monde de l'arène",
                        "<white>/lyonhub noel arene</white> <dark_gray>refaire l'arène (après un changement de config)",
                        "<white>/lyonhub noel retirer <monde></white> <dark_gray>enlever un village collé dans un autre monde (ex. hub)",
                        "<white>/bataille lancer</white> <dark_gray>lancer une partie tout de suite (essai)")) {
                    p.sendMessage(MM.deserialize("  " + line));
                }
            }
        }
    }

    private static String describe(Location l) {
        return l == null ? "?" : l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    /** PNJ de l'arène : skin de Père Noël (ou le skin d'un joueur), clic = /bataille, texte avec les chiffres en direct. */
    private void createNpc(String id, Location at, String skin) {
        String name = skin != null && !skin.isBlank() ? skin : plugin.getConfig().getString("noel.pnj-skin", "");
        NpcService.Npc n = name == null || name.isBlank()
                ? npcs.createTextured(id, "Père Noël", SANTA_VALUE, SANTA_SIGNATURE, "bataille", at)
                : npcs.createFixed(id, name, "bataille", at);
        npcs.setLabel(n, battle.npcLabel());
    }

    private String skinText(String skin) {
        String name = skin != null && !skin.isBlank() ? skin : plugin.getConfig().getString("noel.pnj-skin", "");
        return name == null || name.isBlank() ? "skin de Père Noël" : "skin de <white>" + name + "</white>";
    }

    private SchematicPaster.Schem loadSchem(Player p) {
        String name = plugin.getConfig().getString("noel.schematic", "happychristmas");
        try {
            return paster.load(name);
        } catch (IOException | RuntimeException e) {
            msg(p, "<red>Le village est introuvable.</red> <gray>Mets <white>" + name + ".schem</white> dans <white>plugins/HUB/schematics/</white> puis relance.");
            return null;
        }
    }

    private void install(Player p, String[] args) {
        String mode = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
        boolean confirm = mode.equals("confirmer") || mode.equals("forcer");
        boolean force = mode.equals("forcer");
        String name = plugin.getConfig().getString("noel.schematic", "happychristmas");
        if (!confirm) {
            int[] o = origin();
            msg(p, "<white><bold>Arène de Noël</bold>");
            p.sendMessage(MM.deserialize("  <gray>Crée le monde <white>" + worldName() + "</white> (vide, à part du hub) et y colle le village (<white>" + name
                    + ".schem</white>), avec l'arène de la bataille de boules de neige juste à côté (vue sur le village) et un PNJ pour jouer."));
            p.sendMessage(MM.deserialize("  <gray>Emplacement : coin en <white>" + o[0] + " " + o[1] + " " + o[2] + "</white> <dark_gray>(noel.monde et noel.origine dans config.yml)"));
            p.sendMessage(MM.deserialize("  <gray>Le hub n'est pas touché. Les joueurs y vont avec <white>/noel</white> et reviennent avec <white>/hub</white>."));
            p.sendMessage(MM.deserialize("  <gray>Pour lancer : <white><click:run_command:'/lyonhub noel installer confirmer'><u>/lyonhub noel installer confirmer</u></click></white>"
                    + " <dark_gray>(le PNJ a un skin de Père Noël ; ajoute un pseudo à la fin pour un autre skin)"));
            return;
        }
        if (busy || paster.busy()) {
            msg(p, "<gray>Une installation ou un collage est déjà en cours.");
            return;
        }
        SchematicPaster.Schem s = loadSchem(p);
        if (s == null) return;
        String wn = worldName();
        if (worlds.names().indexOf(wn) == 0) {
            msg(p, "<red>noel.monde est le monde du hub</red> <gray>: choisis un autre nom dans plugins/HUB/config.yml (ex. noel).");
            return;
        }
        World w = Bukkit.getWorld(wn);
        if (w == null) {
            msg(p, "<gray>Création du monde vide <white>" + wn + "</white>…");
            w = worlds.create(wn);
        }
        if (w == null) {
            msg(p, "<red>Impossible de créer le monde " + wn + ".");
            return;
        }
        int[] o = origin();
        if (o[1] < w.getMinHeight() || o[1] + s.height() > w.getMaxHeight()) {
            msg(p, "<red>Le village dépasse les limites de hauteur du monde</red> <gray>: change le y de noel.origine.");
            return;
        }
        String skin = args.length > 3 ? args[3] : null;
        boolean already = match(w, s, o) >= 0.85;
        if (!already && !force && builtColumns(w, s, o) > 3) {
            msg(p, "<red>Il y a déjà des constructions à cet endroit du monde " + wn + "</red> <gray>(x " + o[0] + " à " + (o[0] + s.width())
                    + ", z " + o[2] + " à " + (o[2] + s.length()) + ").");
            p.sendMessage(MM.deserialize("  <gray>Change <white>noel.origine</white> dans plugins/HUB/config.yml (puis <white>/lyonhub reload</white>),"
                    + " ou colle quand même par-dessus : <white><click:suggest_command:'/lyonhub noel installer forcer'><u>/lyonhub noel installer forcer</u></click>"));
            return;
        }
        // Ancienne installation dans un autre monde (1.9.0 : dans le monde du hub)
        String before = state.getBoolean("installe", false) ? state.getString("monde", "") : "";
        if (!before.isEmpty() && !before.equals(wn)) {
            state.set("ancien.monde", before);
            if (!state.isSet("ancien.origine")) state.set("ancien.origine", state.getString("origine", OLD_ORIGIN));
        }
        World world = w;
        if (already) {
            finish(p, world, s, skin, true);
            return;
        }
        busy = true;
        msg(p, "<gray>Collage de la map de Noël dans le monde <white>" + wn + "</white>… <dark_gray>(une à deux minutes, le serveur continue de tourner)");
        Location at = new Location(w, o[0] - s.offsetX(), o[1] - s.offsetY(), o[2] - s.offsetZ());
        try {
            paster.paste(p, s, at, worlds.hasStartPlatform(w), () -> {
                try {
                    finish(p, world, s, skin, false);
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.SEVERE, "Installation du village de Noël", e);
                    if (p.isOnline()) msg(p, "<red>Erreur pendant la mise en place : " + e.getMessage());
                } finally {
                    busy = false;
                }
            });
        } catch (RuntimeException e) {
            busy = false;
            plugin.getLogger().log(Level.SEVERE, "Collage du village de Noël", e);
            msg(p, "<red>Erreur pendant le collage : " + e.getMessage());
        }
    }

    private void finish(Player p, World w, SchematicPaster.Schem s, String skin, boolean already) {
        int[] o = origin();
        state.set("installe", true);
        state.set("monde", w.getName());
        state.set("origine", o[0] + " " + o[1] + " " + o[2]);
        saveState();
        // La petite plateforme de départ du monde vide n'a plus de raison d'être
        if (worlds.hasStartPlatform(w)) {
            for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
                if (w.getBlockAt(x, 64, z).getType() == Material.SMOOTH_STONE && !covered(s, o, x, 64, z)) w.getBlockAt(x, 64, z).setType(Material.AIR, false);
            }
        }
        battle.removeArena();
        battle.rebuild();
        Location npc = rel(NPC);
        if (npc != null) createNpc(NPC_ID, npc, skin);
        Location sp = spawn();
        if (sp != null) w.setSpawnLocation(sp);
        w.setTime(6000);
        battle.exportAsync();
        plugin.getLogger().info("Village de Noël " + (already ? "déjà en place : arène et PNJ refaits" : "installé") + " dans le monde " + w.getName()
                + ". Arène : " + describe(arenaCenter()));
        if (!p.isOnline()) return;
        if (sp != null) p.teleportAsync(sp);
        msg(p, "<green>Arène de Noël " + (already ? "déjà en place : étages et PNJ refaits" : "installée") + " dans le monde " + w.getName() + " !</green>"
                + " <gray>Les joueurs y vont avec <white>/noel</white> (et le menu du hub), et reviennent avec <white>/hub</white>."
                + " La bataille : PNJ près de la fontaine ou <white>/bataille</white>.");
        p.sendMessage(MM.deserialize("  <gray>Le PNJ du hub (Père Noël, clic pour jouer) : va au spawn du hub et tape <white>/lyonhub noel pnj-hub"));
        String old = state.getString("ancien.monde", "");
        if (!old.isEmpty() && Bukkit.getWorld(old) != null) {
            p.sendMessage(MM.deserialize("  <gray>L'ancien village est encore dans le monde <white>" + old + "</white> : "
                    + "<click:suggest_command:'/lyonhub noel retirer " + old + "'><white><u>/lyonhub noel retirer " + old + "</u></white></click> pour l'enlever."));
        }
    }

    private static boolean covered(SchematicPaster.Schem s, int[] o, int x, int y, int z) {
        int sx = x - o[0], sy = y - o[1], sz = z - o[2];
        if (sx < 0 || sy < 0 || sz < 0 || sx >= s.width() || sy >= s.height() || sz >= s.length()) return false;
        int id = s.blocks()[(sy * s.length() + sz) * s.width() + sx];
        BlockData bd = id < s.palette().length ? s.palette()[id] : null;
        return bd != null && !bd.getMaterial().isAir();
    }

    /** /lyonhub noel retirer <monde> : enlève un village collé ailleurs (seuls les blocs du village et l'arène sont retirés). */
    private void removeOld(Player p, String[] args) {
        String wn = args.length > 2 ? args[2] : state.getString("ancien.monde", "");
        World w = wn.isEmpty() ? null : Bukkit.getWorld(wn);
        if (w == null) {
            msg(p, "<gray>Usage : <white>/lyonhub noel retirer <monde></white> <dark_gray>(le monde où un ancien village a été collé, ex. hub)");
            return;
        }
        if (isVillageWorld(w)) {
            msg(p, "<gray>C'est le monde du village actuel : rien n'est retiré.");
            return;
        }
        if (busy || paster.busy()) {
            msg(p, "<gray>Une installation ou un collage est déjà en cours.");
            return;
        }
        SchematicPaster.Schem s = loadSchem(p);
        if (s == null) return;
        int[] o = parse(wn.equals(state.getString("ancien.monde", "")) ? state.getString("ancien.origine", OLD_ORIGIN) : OLD_ORIGIN, OLD_ORIGIN);
        if (match(w, s, o) < 0.5) {
            msg(p, "<gray>Pas de village de Noël trouvé dans le monde <white>" + wn + "</white> (coin " + o[0] + " " + o[1] + " " + o[2] + ").");
            return;
        }
        // L'arène qui était à côté (réglages actuels de la config)
        var c = plugin.getConfig();
        new SnowArena(w, o[0] + ARENA[0], o[1] + ARENA[1], o[2] + ARENA[2], c.getInt("noel.bataille.rayon", 14), c.getInt("noel.bataille.etages", 3),
                c.getInt("noel.bataille.ecart", 7), c.getInt("noel.bataille.relief", 2), true).clear();
        busy = true;
        msg(p, "<gray>Retrait de l'ancien village dans le monde <white>" + wn + "</white>…");
        paster.unpaste(s, w, o[0], o[1], o[2], removed -> {
            busy = false;
            state.set("ancien", null);
            saveState();
            plugin.getLogger().info("Ancien village de Noël retiré du monde " + wn + " : " + removed + " blocs.");
            if (p.isOnline()) msg(p, "<green>Ancien village retiré du monde " + wn + "</green> <gray>(" + removed + " blocs).");
        });
    }

    /** Part du village déjà présente dans le monde (0 à 1), sur 500 blocs pris au hasard. */
    private static double match(World w, SchematicPaster.Schem s, int[] o) {
        Random r = new Random(7);
        int tested = 0, same = 0;
        for (int tries = 0; tries < 40000 && tested < 500; tries++) {
            int x = r.nextInt(s.width()), y = r.nextInt(s.height()), z = r.nextInt(s.length());
            int id = s.blocks()[(y * s.length() + z) * s.width() + x];
            BlockData bd = id < s.palette().length ? s.palette()[id] : null;
            if (bd == null || bd.getMaterial().isAir()) continue;
            int wy = o[1] + y;
            if (wy < w.getMinHeight() || wy >= w.getMaxHeight()) continue;
            tested++;
            if (w.getBlockAt(o[0] + x, wy, o[2] + z).getType() == bd.getMaterial()) same++;
        }
        return tested == 0 ? 0 : same / (double) tested;
    }

    /** Colonnes déjà construites là où le village (et l'arène) doivent aller (la plateforme de départ ne compte pas). */
    private int builtColumns(World w, SchematicPaster.Schem s, int[] o) {
        int built = 0;
        int x1 = Math.max(o[0] + s.width(), o[0] + ARENA[0] + 20); // l'arène est à l'est du village
        for (int x = o[0]; x < x1; x += 4) {
            for (int z = o[2]; z < o[2] + s.length(); z += 4) {
                if (Math.abs(x) <= 4 && Math.abs(z) <= 4 && w.getBlockAt(x, 64, z).getType() == Material.SMOOTH_STONE) continue;
                if (w.getHighestBlockYAt(x, z) > w.getMinHeight()) built++;
            }
        }
        return built;
    }
}
