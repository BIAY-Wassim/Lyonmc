package fr.lyonmc.hub.world;

import fr.lyonmc.hub.npc.NpcService;
import fr.lyonmc.hub.player.HubPlayers;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * /lyonhub installer : colle le hub médiéval (hub-medieval.schem), y ajoute les podiums, puis pose le spawn,
 * les hologrammes et les PNJ.
 */
public final class HubBuilder {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<#e8c872>✦</#e8c872> <dark_gray>·</dark_gray> ";

    private final JavaPlugin plugin;
    private final HubWorlds worlds;
    private final NpcService npcs;
    private final HubPlayers players;
    private final SchematicPaster paster;
    private final NamespacedKey decoKey;
    private boolean busy;
    /** Décalage du hub déjà en place par rapport au point habituel (0, 65, 0). */
    private int dx, dy, dz;

    public HubBuilder(JavaPlugin plugin, HubWorlds worlds, NpcService npcs, HubPlayers players, SchematicPaster paster) {
        this.paster = paster;
        this.plugin = plugin;
        this.worlds = worlds;
        this.npcs = npcs;
        this.players = players;
        this.decoKey = new NamespacedKey(plugin, "deco");
    }

    private void msg(Player p, String mini) {
        p.sendMessage(MM.deserialize(PREFIX + mini));
    }

    /** Cherche le hub médiéval dans plugins/HUB/schematics (même s'il a été renommé). */
    private SchematicPaster.Schem findSchem() {
        List<String> names = new ArrayList<>(paster.available());
        names.remove(HubPlan.SCHEM);
        names.add(0, HubPlan.SCHEM);
        for (String n : names) {
            try {
                SchematicPaster.Schem s = paster.load(n);
                if (s.width() == HubPlan.SCHEM_W && s.height() == HubPlan.SCHEM_H && s.length() == HubPlan.SCHEM_L) return s;
            } catch (java.io.IOException | RuntimeException ignored) {
                // pas ce fichier
            }
        }
        return null;
    }

    /**
     * Part du hub médiéval qui correspond au monde si son point de référence est en (ax, ay, az) :
     * on compare 600 blocs pris au hasard autour de la place.
     */
    private double match(World w, SchematicPaster.Schem s, int ax, int ay, int az) {
        int bx = ax + s.offsetX(), by = ay + s.offsetY(), bz = az + s.offsetZ();
        java.util.Random r = new java.util.Random(42);
        int tested = 0, same = 0;
        for (int tries = 0; tries < 20000 && tested < 600; tries++) {
            int sx = -s.offsetX() + r.nextInt(121) - 60, sz = -s.offsetZ() + r.nextInt(121) - 60, sy = r.nextInt(s.height());
            if (sx < 0 || sz < 0 || sx >= s.width() || sz >= s.length()) continue;
            int id = s.blocks()[(sy * s.length() + sz) * s.width() + sx];
            BlockData bd = id < s.palette().length ? s.palette()[id] : null;
            if (bd == null || bd.getMaterial().isAir()) continue;
            int y = by + sy;
            if (y < w.getMinHeight() || y >= w.getMaxHeight()) return 0;
            tested++;
            if (w.getBlockAt(bx + sx, y, bz + sz).getType() == bd.getMaterial()) same++;
        }
        return tested == 0 ? 0 : same / (double) tested;
    }

    /** /lyonhub installer [confirmer|recoller] [pseudo du skin des PNJ] */
    public void command(Player p, String[] args) {
        String mode = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "";
        boolean confirm = mode.equals("confirmer"), repaste = mode.equals("recoller");
        if (!confirm && !repaste) {
            msg(p, "<white><bold>Hub médiéval de LyonMC, tout prêt</bold></white>");
            p.sendMessage(MM.deserialize("  <gray>Ajoute au hub médiéval : le spawn sur la grande place, le grand écran, les hologrammes,"));
            p.sendMessage(MM.deserialize("  <gray>le podium des succès et des métiers et les PNJ (jouer, aide)."));
            p.sendMessage(MM.deserialize("  <gray>Si ton hub médiéval est déjà collé, il est gardé tel quel. Sinon, il est collé au centre du monde du hub."));
            p.sendMessage(MM.deserialize("  <gray>Les anciens hologrammes et PNJ du hub sont remplacés. Les joueurs et leurs inventaires ne sont pas touchés."));
            p.sendMessage(MM.deserialize("  <gray>Pour lancer : <white><click:run_command:'/lyonhub installer confirmer'><u>/lyonhub installer confirmer</u></click></white>"
                    + " <dark_gray>(ajoute un pseudo à la fin pour choisir le skin des PNJ)"));
            return;
        }
        if (busy || paster.busy()) {
            msg(p, "<gray>Une installation ou un collage est déjà en cours.");
            return;
        }
        SchematicPaster.Schem schem = findSchem();
        if (schem == null) {
            msg(p, "<red>Le hub médiéval est introuvable.</red> <gray>Mets le fichier <white>hub-medieval.schem</white> dans"
                    + " <white>plugins/HUB/schematics/</white> puis relance la commande.");
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
        HubPlan plan = HubPlan.build();
        World world = w;

        // Le hub médiéval est-il déjà là ? D'abord à sa place habituelle, puis là où se tient le joueur
        // (s'il est revenu à l'endroit d'où il l'avait collé).
        if (!repaste) {
            List<int[]> anchors = new ArrayList<>();
            anchors.add(new int[]{HubPlan.ANCHOR_X, HubPlan.ANCHOR_Y, HubPlan.ANCHOR_Z});
            if (p.getWorld().equals(w)) anchors.add(new int[]{p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ()});
            int[] found = null;
            for (int[] a : anchors) {
                if (match(w, schem, a[0], a[1], a[2]) >= 0.85) {
                    found = a;
                    break;
                }
            }
            if (found == null) {
                boolean empty = match(w, schem, HubPlan.ANCHOR_X, HubPlan.ANCHOR_Y, HubPlan.ANCHOR_Z) < 0.05 && emptyAround(w);
                if (!empty) {
                    msg(p, "<gray>Je ne retrouve pas ton hub médiéval à sa place habituelle.");
                    p.sendMessage(MM.deserialize("  <white>1.</white> <gray>Va exactement à l'endroit où tu étais quand tu l'as collé, puis retape"
                            + " <white>/lyonhub installer confirmer</white> : il sera gardé tel quel."));
                    p.sendMessage(MM.deserialize("  <white>2.</white> <gray>Ou recolle-le proprement au centre : <white><click:run_command:'/lyonhub installer recoller'><u>/lyonhub installer recoller</u></click></white>"
                            + " <red>(efface tout à moins de 150 blocs du centre du hub)"));
                    return;
                }
                repaste = true; // monde du hub vide : on colle le hub
            } else {
                dx = found[0] - HubPlan.ANCHOR_X;
                dy = found[1] - HubPlan.ANCHOR_Y;
                dz = found[2] - HubPlan.ANCHOR_Z;
                busy = true;
                msg(p, "<gray>Ton hub médiéval est déjà en place : je le garde et j'ajoute les podiums, l'écran, les hologrammes et les PNJ…");
                place(world, plan.blocks(), () -> {
                    try {
                        setup(p, world, plan, skin);
                    } catch (RuntimeException e) {
                        plugin.getLogger().log(java.util.logging.Level.SEVERE, "Installation du hub", e);
                        msg(p, "<red>Erreur pendant la mise en place : " + e.getMessage());
                    } finally {
                        busy = false;
                    }
                });
                return;
            }
        }

        dx = dy = dz = 0;
        busy = true;
        // Le joueur vole au-dessus du chantier pendant les travaux
        boolean couldFly = p.getAllowFlight();
        p.teleport(new Location(w, 0.5, 140, 0.5, 90, 40));
        p.setAllowFlight(true);
        p.setFlying(true);
        msg(p, "<gray>Installation du hub dans <white>" + w.getName() + "</white> : nettoyage de la zone…");
        Runnable finish = () -> {
            busy = false;
            if (p.isOnline()) {
                p.setFlying(false);
                p.setAllowFlight(couldFly || p.getGameMode() == org.bukkit.GameMode.CREATIVE || p.getGameMode() == org.bukkit.GameMode.SPECTATOR);
            }
        };
        clear(world, () -> {
            msg(p, "<gray>Collage du hub médiéval (" + schem.name() + ")… <dark_gray>(une à deux minutes)");
            Location anchor = new Location(world, HubPlan.ANCHOR_X, HubPlan.ANCHOR_Y, HubPlan.ANCHOR_Z);
            try {
                paster.paste(p, schem, anchor, false, () -> place(world, plan.blocks(), () -> {
                    try {
                        setup(p, world, plan, skin);
                    } catch (RuntimeException e) {
                        plugin.getLogger().log(java.util.logging.Level.SEVERE, "Installation du hub", e);
                        msg(p, "<red>Erreur pendant la mise en place : " + e.getMessage());
                    } finally {
                        finish.run();
                    }
                }));
            } catch (RuntimeException e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Collage du hub", e);
                msg(p, "<red>Erreur pendant le collage : " + e.getMessage());
                finish.run();
            }
        });
    }

    /** Presque rien de construit autour du centre (monde du hub tout neuf, avec sa plateforme de départ). */
    private boolean emptyAround(World w) {
        int blocks = 0;
        for (int x = -40; x <= 40; x += 4) for (int z = -40; z <= 40; z += 4) if (w.getHighestBlockYAt(x, z) > w.getMinHeight()) blocks++;
        return blocks <= 9;
    }

    // ───────────────────────────── Nettoyage et construction ─────────────────────────────

    private void clear(World w, Runnable then) {
        int minY = Math.max(w.getMinHeight(), HubPlan.MIN_Y);
        new BukkitRunnable() {
            int x = HubPlan.MIN_X, z = HubPlan.MIN_Z;

            @Override
            public void run() {
                long deadline = System.nanoTime() + 25_000_000L;
                while (x <= HubPlan.MAX_X && System.nanoTime() < deadline) {
                    int top = Math.min(HubPlan.MAX_Y, w.getHighestBlockYAt(x, z));
                    for (int y = top; y >= minY; y--) {
                        Block b = w.getBlockAt(x, y, z);
                        if (!b.getType().isAir()) b.setType(Material.AIR, false);
                    }
                    if (++z > HubPlan.MAX_Z) {
                        z = HubPlan.MIN_Z;
                        x++;
                    }
                }
                if (x > HubPlan.MAX_X) {
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
                    w.getBlockAt(b.x() + dx, b.y() + dy, b.z() + dz).setBlockData(data, false);
                }
                if (i >= blocks.size()) {
                    cancel();
                    then.run();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ───────────────────────────── Spawn, hologrammes, PNJ ─────────────────────────────

    private Location at(World w, HubPlan.Spot s) {
        return new Location(w, s.x() + dx, s.y() + dy, s.z() + dz, s.yaw(), 0);
    }

    private void setup(Player p, World w, HubPlan plan, String skin) {
        w.setSpawnLocation(HubPlan.ANCHOR_X + dx, HubPlan.ANCHOR_Y + dy, HubPlan.ANCHOR_Z + dz);
        w.setTime(6000);
        int oldNpcs = npcs.removeInWorld(w.getName());
        int oldHolos = removeHolograms(w.getName());
        boolean lyon = Bukkit.getPluginManager().isPluginEnabled("LyonAchievements");

        int holos = 0, npcCount = 0;
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
                default -> {
                }
            }
        }
        // Pas de parkour dans ce hub : on retire celui d'avant (points et classement)
        players.parkour().clear(w);

        // Spawn du serveur (LyonAchievements) : au centre, tourné vers le grand écran
        HubPlan.Spot spawn = plan.spots.stream().filter(s -> s.kind().equals("spawn")).findFirst().orElse(null);
        if (spawn != null) {
            p.teleport(at(w, spawn));
            if (lyon) p.performCommand("setspawn");
        }
        msg(p, "<green>Hub médiéval installé !</green> <gray>" + holos + " hologrammes, " + npcCount + " PNJ" + (oldNpcs + oldHolos > 0 ? " <dark_gray>(" + oldNpcs + " anciens PNJ et " + oldHolos + " anciens hologrammes retirés)" : "") + "<gray>.");
        p.sendMessage(MM.deserialize("  <gray>Tu es au spawn. Les classements des podiums se remplissent en une minute."));
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
