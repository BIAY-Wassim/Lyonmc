package fr.lyonmc.hub.noel;

import fr.lyonmc.hub.npc.NpcService;
import fr.lyonmc.hub.player.HubPlayers;
import fr.lyonmc.hub.world.HubWorlds;
import fr.lyonmc.hub.world.SchematicPaster;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.logging.Level;

/**
 * Le village de Noël du hub (happychristmas.schem), collé à côté du hub médiéval, avec à côté l'arène
 * de la bataille de boules de neige (les joueurs ont la vue sur le village en jouant). /noel pour y aller.
 */
public final class ChristmasVillage implements CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<#ff6b6b>❄</#ff6b6b> <dark_gray>·</dark_gray> ";
    /** Repères dans happychristmas.schem (depuis son coin) : arrivée face au château, attente près de la fontaine, arène. */
    private static final double[] SPAWN = {84.5, 77, 120.5, 90};
    private static final double[] LOBBY = {105.5, 76, 124.5, -90};
    private static final double[] NPC = {107.5, 75, 121.5, 90};
    private static final int[] ARENA = {150, 74, 120};
    public static final String NPC_ID = "noel-bataille";

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

    public boolean installed() {
        return state.getBoolean("installe", false) && world() != null;
    }

    /** Ouvert aux joueurs : installé et « noel.actif » dans la config. */
    public boolean open() {
        return installed() && plugin.getConfig().getBoolean("noel.actif", true);
    }

    public World world() {
        String name = state.getString("monde", "");
        World w = name.isEmpty() ? null : Bukkit.getWorld(name);
        if (w != null) return w;
        return worlds.names().isEmpty() ? null : Bukkit.getWorld(worlds.names().get(0));
    }

    /** Coin du village (x y z) : « noel.origine » dans la config. */
    private int[] origin() {
        String s = plugin.getConfig().getString("noel.origine", "125 -12 -120");
        try {
            String[] p = s.trim().split("[\\s,;]+");
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
        } catch (RuntimeException e) {
            plugin.getLogger().warning("config.yml : noel.origine invalide (" + s + "), format « x y z »");
            return new int[]{125, -12, -120};
        }
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
        if (w == null || p.length < 6) return null;
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
            try {
                String[] p = s.trim().split("[\\s,;]+");
                return new Location(w, Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("config.yml : noel.bataille.centre invalide (" + s + "), format « x y z »");
            }
        }
        int[] o = origin();
        return new Location(w, o[0] + ARENA[0], o[1] + ARENA[1], o[2] + ARENA[2]);
    }

    /** Au démarrage : l'arène est prête et le site a ses chiffres. */
    public void startup() {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (installed()) battle.rebuild();
            battle.exportAsync();
        }, 60L);
    }

    // ───────────────────────────── /noel ─────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            msg(sender, "<gray>À faire en jeu.");
            return true;
        }
        if (!open()) {
            msg(p, installed() ? "<gray>Le village de Noël est fermé pour cette année. À bientôt !"
                    : "<gray>Le village de Noël n'est pas encore installé.");
            return true;
        }
        Location l = spawn();
        if (l == null) return true;
        if (battle.playing(p)) battle.leave(p);
        p.teleportAsync(l).thenAccept(ok -> {
            if (!ok || !p.isOnline()) return;
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.4f);
            msg(p, "<white>Bienvenue au village de Noël !</white> <gray>La bataille de boules de neige est derrière toi, après la fontaine : "
                    + "<click:run_command:'/bataille'><hover:show_text:'<gray>Rejoindre la bataille'><white><u>/bataille</u></white></hover></click>"
                    + (Bukkit.getPluginCommand("calendrier") != null
                    ? "<gray>. Et ta case du jour : <click:run_command:'/calendrier'><hover:show_text:\"<gray>Calendrier de l'Avent\"><white><u>/calendrier</u></white></hover></click>" : "")
                    + "<gray>.");
        });
        return true;
    }

    // ───────────────────────────── /lyonhub noel ─────────────────────────────

    public void admin(Player p, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "installer", "install" -> install(p, args);
            case "spawn" -> {
                store("spawn", p.getLocation());
                msg(p, "<gray>Arrivée de <white>/noel</white> placée ici.");
            }
            case "attente" -> {
                store("attente", p.getLocation());
                msg(p, "<gray>Point d'attente de la bataille placé ici (les éliminés y reviennent).");
            }
            case "pnj" -> {
                String skin = args.length > 2 ? args[2] : p.getName();
                createNpc(p.getLocation(), skin);
                msg(p, "<gray>PNJ de la bataille placé ici (skin de <white>" + skin + "</white>).");
            }
            case "arene", "arène" -> {
                if (!installed()) {
                    msg(p, "<gray>Installe d'abord le village : <white>/lyonhub noel installer");
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
                msg(p, "<white><bold>Village de Noël</bold></white> " + (installed() ? (open() ? "<green>ouvert" : "<red>fermé</red> <dark_gray>(noel.actif: false)") : "<gray>pas installé"));
                for (String line : List.of(
                        "<white>/lyonhub noel installer</white> <dark_gray>coller le village (happychristmas.schem) et l'arène",
                        "<white>/lyonhub noel spawn</white> <dark_gray>arrivée de /noel ici",
                        "<white>/lyonhub noel attente</white> <dark_gray>point d'attente de la bataille ici",
                        "<white>/lyonhub noel pnj [skin]</white> <dark_gray>PNJ « Bataille de neige » ici",
                        "<white>/lyonhub noel arene</white> <dark_gray>refaire l'arène (après un changement de config)",
                        "<white>/bataille lancer</white> <dark_gray>lancer une partie tout de suite (essai)")) {
                    p.sendMessage(MM.deserialize("  " + line));
                }
            }
        }
    }

    private static String describe(Location l) {
        return l == null ? "?" : l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    private void createNpc(Location at, String skin) {
        NpcService.Npc n = npcs.createFixed(NPC_ID, skin, "bataille", at);
        npcs.setLabel(n, List.of("<#9fd3ff><bold>Bataille de neige", "<gray>Clic pour jouer"));
    }

    private void install(Player p, String[] args) {
        String mode = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
        boolean confirm = mode.equals("confirmer") || mode.equals("forcer");
        boolean force = mode.equals("forcer");
        String name = plugin.getConfig().getString("noel.schematic", "happychristmas");
        if (!confirm) {
            msg(p, "<white><bold>Village de Noël</bold>");
            p.sendMessage(MM.deserialize("  <gray>Colle le village (<white>" + name + ".schem</white>) à côté du hub, avec l'arène de la bataille"
                    + " de boules de neige juste à côté (vue sur le village) et un PNJ pour jouer."));
            int[] o = origin();
            p.sendMessage(MM.deserialize("  <gray>Emplacement : coin en <white>" + o[0] + " " + o[1] + " " + o[2] + "</white> <dark_gray>(noel.origine dans config.yml)"));
            p.sendMessage(MM.deserialize("  <gray>Rien n'est effacé : si quelque chose est déjà construit à cet endroit, l'installation s'arrête."));
            p.sendMessage(MM.deserialize("  <gray>Pour lancer : <white><click:run_command:'/lyonhub noel installer confirmer'><u>/lyonhub noel installer confirmer</u></click></white>"
                    + " <dark_gray>(ajoute un pseudo à la fin pour choisir le skin du PNJ)"));
            return;
        }
        if (busy || paster.busy()) {
            msg(p, "<gray>Une installation ou un collage est déjà en cours.");
            return;
        }
        SchematicPaster.Schem s;
        try {
            s = paster.load(name);
        } catch (IOException | RuntimeException e) {
            msg(p, "<red>Le village est introuvable.</red> <gray>Mets <white>" + name + ".schem</white> dans <white>plugins/HUB/schematics/</white> puis relance.");
            return;
        }
        World w = worlds.isHub(p.getWorld()) ? p.getWorld() : (worlds.names().isEmpty() ? null : Bukkit.getWorld(worlds.names().get(0)));
        if (w == null) {
            msg(p, "<red>Le monde du hub est introuvable.</red> <gray>Crée-le d'abord (<white>/lyonhub monde</white>).");
            return;
        }
        int[] o = origin();
        if (o[1] < w.getMinHeight() || o[1] + s.height() > w.getMaxHeight()) {
            msg(p, "<red>Le village dépasse les limites de hauteur du monde</red> <gray>: change le y de noel.origine.");
            return;
        }
        String skin = args.length > 3 ? args[3] : p.getName();
        double match = match(w, s, o);
        boolean already = match >= 0.85;
        if (!already && !force) {
            int built = builtColumns(w, s, o);
            if (built > 3) {
                msg(p, "<red>Il y a déjà des constructions à cet endroit</red> <gray>(x " + o[0] + " à " + (o[0] + s.width()) + ", z " + o[2] + " à " + (o[2] + s.length()) + ").");
                p.sendMessage(MM.deserialize("  <gray>Change <white>noel.origine</white> dans plugins/HUB/config.yml (puis <white>/lyonhub reload</white>),"
                        + " ou colle quand même par-dessus : <white><click:suggest_command:'/lyonhub noel installer forcer'><u>/lyonhub noel installer forcer</u></click>"));
                return;
            }
        }
        state.set("monde", w.getName());
        if (already) {
            // Village déjà collé : on refait juste l'arène et le PNJ
            finish(p, w, skin, true);
            return;
        }
        busy = true;
        msg(p, "<gray>Collage du village de Noël… <dark_gray>(une à deux minutes, le serveur continue de tourner)");
        Location at = new Location(w, o[0] - s.offsetX(), o[1] - s.offsetY(), o[2] - s.offsetZ());
        try {
            paster.paste(p, s, at, false, () -> {
                try {
                    finish(p, w, skin, false);
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

    private void finish(Player p, World w, String skin, boolean already) {
        state.set("installe", true);
        state.set("monde", w.getName());
        saveState();
        battle.removeArena();
        battle.rebuild();
        Location npc = rel(NPC);
        if (npc != null) createNpc(npc, skin);
        battle.exportAsync();
        plugin.getLogger().info("Village de Noël " + (already ? "déjà en place : arène et PNJ refaits." : "installé.") + " Arène : " + describe(arenaCenter()));
        if (!p.isOnline()) return;
        Location l = spawn();
        if (l != null) p.teleportAsync(l);
        msg(p, "<green>Village de Noël " + (already ? "déjà en place : arène et PNJ refaits" : "installé") + " !</green> <gray>Les joueurs y vont avec <white>/noel</white>"
                + " (et le menu du hub). La bataille : PNJ près de la fontaine ou <white>/bataille</white>.");
        p.sendMessage(MM.deserialize("  <gray>Pour un PNJ « Village de Noël » au spawn du hub : <white>/lyonhub pnj noel <skin> noel"));
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
            tested++;
            if (w.getBlockAt(o[0] + x, o[1] + y, o[2] + z).getType() == bd.getMaterial()) same++;
        }
        return tested == 0 ? 0 : same / (double) tested;
    }

    /** Colonnes déjà construites là où le village (et l'arène) doivent aller. */
    private int builtColumns(World w, SchematicPaster.Schem s, int[] o) {
        int built = 0;
        int x1 = Math.max(o[0] + s.width(), o[0] + ARENA[0] + 20); // l'arène est à l'est du village
        for (int x = o[0]; x < x1; x += 4) {
            for (int z = o[2]; z < o[2] + s.length(); z += 4) {
                if (w.getHighestBlockYAt(x, z) > w.getMinHeight()) built++;
            }
        }
        return built;
    }
}
