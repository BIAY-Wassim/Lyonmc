package fr.lyonmc.hub.world;

import fr.lyonmc.hub.npc.NpcService;
import fr.lyonmc.hub.player.HubPlayers;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * /lyonhub installer : construit le hub tout fait de LyonMC (place centrale, grand écran, podiums, cerisiers,
 * parkour long et difficile), puis pose le spawn, les hologrammes, les PNJ et règle le parkour.
 */
public final class HubBuilder {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<#e8c872>✦</#e8c872> <dark_gray>·</dark_gray> ";

    private final JavaPlugin plugin;
    private final HubWorlds worlds;
    private final NpcService npcs;
    private final HubPlayers players;
    private final NamespacedKey decoKey;
    private boolean busy;

    public HubBuilder(JavaPlugin plugin, HubWorlds worlds, NpcService npcs, HubPlayers players) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.npcs = npcs;
        this.players = players;
        this.decoKey = new NamespacedKey(plugin, "deco");
    }

    private void msg(Player p, String mini) {
        p.sendMessage(MM.deserialize(PREFIX + mini));
    }

    /** /lyonhub installer [confirmer] [pseudo du skin des PNJ] */
    public void command(Player p, String[] args) {
        boolean confirm = args.length > 1 && args[1].equalsIgnoreCase("confirmer");
        if (!confirm) {
            msg(p, "<white><bold>Hub tout fait de LyonMC</bold></white>");
            p.sendMessage(MM.deserialize("  <gray>Place centrale avec grand écran, podium des succès et des métiers, hologrammes,"));
            p.sendMessage(MM.deserialize("  <gray>PNJ (jouer, aide, parkour), cerisiers et un parkour long et difficile (3 étapes)."));
            p.sendMessage(MM.deserialize("  <red>Attention :</red> <gray>tout ce qui est construit autour du centre du monde du hub est remplacé,"));
            p.sendMessage(MM.deserialize("  <gray>ainsi que ses hologrammes et ses PNJ. Les joueurs et leurs inventaires ne sont pas touchés."));
            p.sendMessage(MM.deserialize("  <gray>Pour lancer : <white><click:run_command:'/lyonhub installer confirmer'><u>/lyonhub installer confirmer</u></click></white>"
                    + " <dark_gray>(ajoute un pseudo à la fin pour choisir le skin des PNJ)"));
            return;
        }
        if (busy) {
            msg(p, "<gray>Une installation est déjà en cours.");
            return;
        }
        World w = worlds.isHub(p.getWorld()) ? p.getWorld() : null;
        if (w == null) {
            String name = worlds.names().isEmpty() ? "hub" : worlds.names().get(0);
            w = Bukkit.getWorld(name);
            if (w == null) w = worlds.create(name);
        }
        if (w == null) {
            msg(p, "<red>Impossible de trouver ou de créer le monde du hub.");
            return;
        }
        String skin = args.length > 2 ? args[2] : p.getName();
        busy = true;
        HubPlan plan = HubPlan.build();
        World world = w;
        // Le joueur vole au-dessus du chantier pendant les travaux
        boolean couldFly = p.getAllowFlight();
        p.teleport(new Location(w, 0.5, HubPlan.FLOOR_Y + 30, 0.5, 180, 30));
        p.setAllowFlight(true);
        p.setFlying(true);
        msg(p, "<gray>Installation du hub dans <white>" + w.getName() + "</white> : nettoyage de la zone…");
        clear(world, () -> {
            msg(p, "<gray>Construction : <white>" + String.format("%,d", plan.blocks().size()).replace(',', ' ') + "</white> blocs…");
            place(world, plan.blocks(), () -> {
                try {
                    setup(p, world, plan, skin);
                } catch (RuntimeException e) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "Installation du hub", e);
                    msg(p, "<red>Erreur pendant la mise en place : " + e.getMessage());
                } finally {
                    busy = false;
                    if (p.isOnline()) {
                        p.setFlying(false);
                        p.setAllowFlight(couldFly || p.getGameMode() == org.bukkit.GameMode.CREATIVE || p.getGameMode() == org.bukkit.GameMode.SPECTATOR);
                    }
                }
            });
        });
    }

    // ───────────────────────────── Nettoyage et construction ─────────────────────────────

    private void clear(World w, Runnable then) {
        List<int[]> columns = new ArrayList<>();
        for (int x = HubPlan.MIN_X; x <= HubPlan.MAX_X; x++) for (int z = HubPlan.MIN_Z; z <= HubPlan.MAX_Z; z++) columns.add(new int[]{x, z});
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                int end = Math.min(columns.size(), i + 700);
                for (; i < end; i++) {
                    int x = columns.get(i)[0], z = columns.get(i)[1];
                    int top = Math.min(HubPlan.MAX_Y, w.getHighestBlockYAt(x, z));
                    for (int y = top; y >= HubPlan.MIN_Y; y--) {
                        Block b = w.getBlockAt(x, y, z);
                        if (!b.getType().isAir()) b.setType(Material.AIR, false);
                    }
                }
                if (i >= columns.size()) {
                    cancel();
                    // Objets posés, cadres, porte-armures et anciens textes du hub
                    for (Entity e : w.getEntities()) {
                        Location l = e.getLocation();
                        boolean inZone = l.getX() >= HubPlan.MIN_X && l.getX() <= HubPlan.MAX_X + 1 && l.getZ() >= HubPlan.MIN_Z && l.getZ() <= HubPlan.MAX_Z + 1;
                        if (e.getPersistentDataContainer().has(decoKey, PersistentDataType.BYTE)
                                || inZone && (e instanceof Hanging || e instanceof ArmorStand || e instanceof Item)) {
                            e.remove();
                        }
                    }
                    then.run();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void place(World w, List<HubPlan.Block> blocks, Runnable then) {
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                int end = Math.min(blocks.size(), i + 2500);
                for (; i < end; i++) {
                    HubPlan.Block b = blocks.get(i);
                    BlockData data;
                    try {
                        data = Bukkit.createBlockData(b.data());
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Bloc inconnu dans le plan du hub : " + b.data());
                        continue;
                    }
                    w.getBlockAt(b.x(), b.y(), b.z()).setBlockData(data, false);
                }
                if (i >= blocks.size()) {
                    cancel();
                    then.run();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ───────────────────────────── Spawn, hologrammes, PNJ, parkour ─────────────────────────────

    private Location at(World w, HubPlan.Spot s) {
        return new Location(w, s.x(), s.y(), s.z(), s.yaw(), 0);
    }

    private void setup(Player p, World w, HubPlan plan, String skin) {
        w.setSpawnLocation(0, HubPlan.FLOOR_Y + 1, 0);
        w.setTime(6000);
        int oldNpcs = npcs.removeInWorld(w.getName());
        int oldHolos = removeHolograms(w.getName());
        boolean lyon = Bukkit.getPluginManager().isPluginEnabled("LyonAchievements");

        int holos = 0, npcCount = 0, labels = 0;
        for (HubPlan.Spot s : plan.spots) {
            Location l = at(w, s);
            switch (s.kind()) {
                case "holo" -> {
                    if (!lyon) continue;
                    p.teleport(l);
                    p.performCommand("lmc holo creer " + s.arg() + " " + s.id());
                    holos++;
                }
                case "podium" -> {
                    String[] a = s.arg().split(":");
                    npcs.createPodium(s.id(), a[0], Integer.parseInt(a[1]), l);
                    npcCount++;
                }
                case "npc" -> {
                    String[] a = s.arg().split("\\|", 2);
                    NpcService.Npc n = npcs.createFixed(s.id(), skin, a[0], l);
                    if (a.length > 1) npcs.setLabel(n, Arrays.asList(a[1].split("\\|")));
                    npcCount++;
                }
                case "label" -> {
                    label(w, l, s.id());
                    labels++;
                }
                default -> {
                }
            }
        }
        // Parkour : départ, étapes, arrivée et classement
        List<Block> steps = new ArrayList<>();
        for (int[] c : plan.checkpoints) steps.add(w.getBlockAt(c[0], c[1], c[2]));
        players.parkour().setup(w.getBlockAt(plan.start[0], plan.start[1], plan.start[2]), steps,
                w.getBlockAt(plan.finish[0], plan.finish[1], plan.finish[2]),
                new Location(w, plan.board[0], plan.board[1], plan.board[2]));

        // Spawn du serveur (LyonAchievements) : au centre, tourné vers le grand écran
        HubPlan.Spot spawn = plan.spots.stream().filter(s -> s.kind().equals("spawn")).findFirst().orElse(null);
        if (spawn != null) {
            p.teleport(at(w, spawn));
            if (lyon) p.performCommand("setspawn");
        }
        msg(p, "<green>Hub installé !</green> <gray>" + holos + " hologrammes, " + npcCount + " PNJ, parkour de "
                + plan.checkpoints.size() + " étapes" + (oldNpcs + oldHolos > 0 ? " <dark_gray>(" + oldNpcs + " anciens PNJ et " + oldHolos + " anciens hologrammes retirés)" : "") + "<gray>.");
        p.sendMessage(MM.deserialize("  <gray>Tu es au spawn. Les classements des podiums se remplissent en une minute."));
        p.sendMessage(MM.deserialize("  <gray>Essaie le parkour : <white><click:run_command:'/parkour'><u>/parkour</u></click></white>"));
    }

    /** Petit texte flottant (départ, étapes et arrivée du parkour). */
    private void label(World w, Location l, String text) {
        String[] lines = text.split("\\|");
        StringBuilder sb = new StringBuilder("<#9fffe0><bold>" + lines[0] + "</bold>");
        for (int i = 1; i < lines.length; i++) sb.append("\n").append(lines[i]);
        w.spawn(l, TextDisplay.class, td -> {
            td.text(MM.deserialize(sb.toString()));
            td.setBillboard(Display.Billboard.CENTER);
            td.setDefaultBackground(false);
            td.setBackgroundColor(Color.fromARGB(90, 0, 0, 0));
            td.setShadowed(true);
            td.setPersistent(true);
            td.getPersistentDataContainer().set(decoKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    /** Retire les hologrammes de LyonAchievements posés dans ce monde. */
    private int removeHolograms(String world) {
        Plugin la = Bukkit.getPluginManager().getPlugin("LyonAchievements");
        if (la == null || !la.isEnabled()) return 0;
        int n = 0;
        try {
            Object hs = la.getClass().getMethod("holograms").invoke(la);
            @SuppressWarnings("unchecked")
            Set<String> ids = (Set<String>) hs.getClass().getMethod("ids").invoke(hs);
            for (String id : new ArrayList<>(ids)) {
                String d = (String) hs.getClass().getMethod("describe", String.class).invoke(hs, id);
                if (d.contains("· " + world + " ")) {
                    hs.getClass().getMethod("remove", String.class).invoke(hs, id);
                    n++;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("Anciens hologrammes non retirés : " + e.getMessage());
        }
        return n;
    }

    public boolean busy() {
        return busy;
    }
}
