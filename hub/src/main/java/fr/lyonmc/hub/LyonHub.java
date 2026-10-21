package fr.lyonmc.hub;

import fr.lyonmc.hub.npc.NpcService;
import fr.lyonmc.hub.npc.Rankings;
import fr.lyonmc.hub.npc.SkinService;
import fr.lyonmc.hub.world.HubWorlds;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** LyonHub : le monde du hub et les PNJ du podium avec les vrais skins des joueurs. */
public final class LyonHub extends JavaPlugin implements TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<#e8c872>✦</#e8c872> <dark_gray>·</dark_gray> ";

    private HubWorlds worlds;
    private SkinService skins;
    private NpcService npcs;
    private fr.lyonmc.hub.world.SchematicPaster paster;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        worlds = new HubWorlds(this);
        paster = new fr.lyonmc.hub.world.SchematicPaster(this);
        paster.folder();
        worlds.loadExisting();
        skins = new SkinService(this);
        npcs = new NpcService(this, skins);
        npcs.cleanup();
        npcs.load();
        npcs.start();
        getServer().getPluginManager().registerEvents(worlds, this);
        getServer().getPluginManager().registerEvents(npcs, this);
        var cmd = getCommand("lyonhub");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getLogger().info("LyonHub activé : " + npcs.all().size() + " PNJ.");
    }

    @Override
    public void onDisable() {
        if (npcs != null) {
            npcs.stop();
            npcs.despawnAll();
        }
        if (skins != null) skins.save();
    }

    private void msg(CommandSender to, String mini) {
        to.sendMessage(MM.deserialize(PREFIX + mini));
    }

    private static String clean(String id) {
        return id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        Player p = sender instanceof Player pl ? pl : null;
        switch (sub) {
            case "monde", "world" -> {
                if (p == null) return true;
                String name = args.length > 1 ? args[1] : "hub";
                World w = Bukkit.getWorld(name);
                if (w == null) {
                    msg(sender, "<gray>Création du monde vide <white>" + name + "</white>…");
                    w = worlds.create(name);
                }
                if (w == null) {
                    msg(sender, "<red>Impossible de créer le monde " + name + ".");
                    return true;
                }
                p.teleportAsync(w.getSpawnLocation().add(0.5, 0, 0.5));
                msg(sender, "<gray>Tu es dans le monde <white>" + w.getName() + "</white>. Construis le hub, puis tape <white>/setspawn</white> à l'endroit où les joueurs doivent arriver.");
            }
            case "podium" -> {
                if (p == null) return true;
                Rankings.Board b = args.length > 1 ? Rankings.board(args[1]) : null;
                int rank = args.length > 2 ? parse(args[2]) : 0;
                if (b == null || rank < 1 || rank > 10) {
                    msg(sender, "<gray>Usage : <white>/lyonhub podium <succes|saison|fortune|missions> <1|2|3> [id]");
                    msg(sender, "<gray>Place-toi sur la marche du podium, regarde vers les joueurs, puis tape la commande.");
                    return true;
                }
                String id = clean(args.length > 3 ? args[3] : b.id() + "-" + rank);
                npcs.createPodium(id, b.id(), rank, p.getLocation());
                msg(sender, "<gray>PNJ <white>" + id + "</white> créé : <white>" + rank + (rank == 1 ? "er" : "e") + "</white> du classement <white>" + b.title() + "</white>.");
            }
            case "pnj", "npc" -> {
                if (p == null) return true;
                if (args.length < 3) {
                    msg(sender, "<gray>Usage : <white>/lyonhub pnj <id> <pseudo du skin> [commande au clic]");
                    msg(sender, "<gray>Exemple : <white>/lyonhub pnj jouer wassdead rtp");
                    return true;
                }
                String id = clean(args[1]);
                String cmd = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "";
                npcs.createFixed(id, args[2], cmd, p.getLocation());
                msg(sender, "<gray>PNJ <white>" + id + "</white> créé avec le skin de <white>" + args[2] + "</white>"
                        + (cmd.isEmpty() ? "." : ", clic : <white>/" + cmd + "</white>."));
            }
            case "texte", "text" -> {
                NpcService.Npc n = args.length > 2 ? npcs.get(args[1]) : null;
                if (n == null) {
                    msg(sender, "<gray>Usage : <white>/lyonhub texte <id> <texte></white> <dark_gray>(| pour aller à la ligne, « aucun » pour enlever)");
                    return true;
                }
                String t = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                npcs.setLabel(n, t.equalsIgnoreCase("aucun") ? List.of() : Arrays.asList(t.split("\\|")));
                msg(sender, "<gray>Texte du PNJ modifié.");
            }
            case "commande", "command" -> {
                NpcService.Npc n = args.length > 1 ? npcs.get(args[1]) : null;
                if (n == null) {
                    msg(sender, "<gray>Usage : <white>/lyonhub commande <id> [commande]</white> <dark_gray>(vide pour enlever)");
                    return true;
                }
                npcs.setCommand(n, String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                msg(sender, "<gray>Commande du PNJ modifiée.");
            }
            case "deplacer", "déplacer", "move" -> {
                NpcService.Npc n = args.length > 1 ? npcs.get(args[1]) : null;
                if (p == null || n == null) {
                    msg(sender, "<gray>Usage : <white>/lyonhub deplacer <id>");
                    return true;
                }
                npcs.move(n, p.getLocation());
                msg(sender, "<gray>PNJ déplacé ici.");
            }
            case "suppr", "supprimer", "delete" -> {
                if (args.length < 2 || !npcs.remove(clean(args[1]))) msg(sender, "<gray>PNJ introuvable.");
                else msg(sender, "<gray>PNJ supprimé.");
            }
            case "liste", "list" -> {
                msg(sender, "<gray>PNJ : " + (npcs.all().isEmpty() ? "aucun" : ""));
                for (NpcService.Npc n : npcs.all()) {
                    sender.sendMessage(MM.deserialize("  <white>" + n.describe().replaceFirst(" ", "</white> <dark_gray>")));
                }
            }
            case "coller", "paste" -> {
                if (p == null) return true;
                if (args.length < 2) {
                    List<String> files = paster.available();
                    msg(sender, "<gray>Usage : <white>/lyonhub coller <fichier></white>");
                    msg(sender, "<gray>Fichiers dans plugins/LyonHub/schematics : <white>" + (files.isEmpty() ? "aucun" : String.join(", ", files)));
                    return true;
                }
                if (paster.busy()) {
                    msg(sender, "<gray>Un collage est déjà en cours.");
                    return true;
                }
                fr.lyonmc.hub.world.SchematicPaster.Schem s;
                try {
                    s = paster.load(args[1]);
                } catch (java.io.IOException e) {
                    msg(sender, "<red>" + e.getMessage());
                    return true;
                }
                boolean confirm = args.length > 2 && args[2].equalsIgnoreCase("confirmer");
                if (!confirm) {
                    msg(sender, "<gray>Schematic <white>" + s.name() + "</white> : <white>" + s.width() + " × " + s.height() + " × " + s.length()
                            + "</white> blocs, <white>" + String.format(Locale.FRANCE, "%,d", s.nonAir()) + "</white> blocs à poser.");
                    msg(sender, "<gray>Il sera collé autour de toi : la place centrale arrive sous tes pieds. Aucune annulation possible : fais-le dans le monde vide du hub.");
                    msg(sender, "<gray>Pour lancer : <white><click:run_command:'/lyonhub coller " + s.name() + " confirmer'><u>/lyonhub coller " + s.name() + " confirmer</u></click>");
                    return true;
                }
                msg(sender, "<gray>Collage lancé, le serveur continue de tourner pendant ce temps…");
                paster.paste(p, s, worlds.hasStartPlatform(p.getWorld()));
            }
            case "reload" -> {
                reloadConfig();
                npcs.load();
                npcs.start();
                msg(sender, "<gray>LyonHub rechargé.");
            }
            default -> {
                msg(sender, "<white><bold>LyonHub</bold></white>");
                for (String line : List.of(
                        "<white>/lyonhub monde [nom]</white> <dark_gray>créer / aller dans le monde vide du hub",
                        "<white>/lyonhub podium <classement> <place> [id]</white> <dark_gray>PNJ du podium (vrai skin du joueur)",
                        "<white>/lyonhub pnj <id> <pseudo> [commande]</white> <dark_gray>PNJ avec un skin et une commande au clic",
                        "<white>/lyonhub texte <id> <texte></white> <dark_gray>texte au-dessus d'un PNJ",
                        "<white>/lyonhub commande <id> [commande]</white> <dark_gray>commande au clic",
                        "<white>/lyonhub deplacer <id></white> <dark_gray>déplacer un PNJ ici",
                        "<white>/lyonhub suppr <id></white> <dark_gray>supprimer un PNJ",
                        "<white>/lyonhub coller <fichier></white> <dark_gray>coller une construction (.schem) autour de toi",
                        "<white>/lyonhub liste</white> <dark_gray>· <white>/lyonhub reload")) {
                    sender.sendMessage(MM.deserialize("  " + line));
                }
            }
        }
        return true;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("monde", "coller", "podium", "pnj", "texte", "commande", "deplacer", "suppr", "liste", "reload"));
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "podium" -> Rankings.BOARDS.forEach(b -> out.add(b.id()));
                case "texte", "commande", "deplacer", "suppr" -> npcs.all().forEach(n -> out.add(n.id()));
                case "monde" -> out.add("hub");
                case "coller" -> out.addAll(paster.available());
                default -> {
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("podium")) {
            out.addAll(List.of("1", "2", "3"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("pnj")) {
            Bukkit.getOnlinePlayers().forEach(pl -> out.add(pl.getName()));
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
