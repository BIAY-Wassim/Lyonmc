package fr.lyonmc.achievements.menu;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Items;
import fr.lyonmc.achievements.core.Profile;
import fr.lyonmc.achievements.data.PlayerStats;
import fr.lyonmc.achievements.team.Team;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** /menu : point d'entrée vers toutes les fonctionnalités. */
public final class HubMenu {

    private final LyonAchievements plugin;

    public HubMenu(LyonAchievements plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        Profile p = plugin.profiles().get(player);
        plugin.missions().ensure(p);
        PlayerStats s = plugin.stats().refreshOnline(player);
        TextColor accent = plugin.text().accent();
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text("LyonMC"));
        holder.inventory(inv);

        // Profil
        int rank = plugin.stats().rank(player.getUniqueId());
        int seasonRank = plugin.seasons().rank(player.getUniqueId());
        Team team = plugin.teams().teamOf(player.getUniqueId());
        List<Component> lore = new ArrayList<>();
        Component title = plugin.rewards().titleRaw(p);
        if (!title.equals(Component.empty())) lore.add(Component.text("Titre  ", Text.GRAY).append(title));
        lore.add(Items.kv("Rang", rank == 0 ? "non classé" : "#" + rank + " sur " + plugin.stats().ranking().size()));
        lore.add(Component.text("★ ", accent).append(Items.kv("Succès", s.count() + " / " + plugin.registry().total())));
        lore.add(plugin.text().bar(s.count(), plugin.registry().total(), 30));
        lore.add(Component.text("⛁ ", accent).append(Items.kv("Pièces", Text.num(p.coins))));
        lore.add(Component.text("✦ ", accent).append(Items.kv("Saison", Text.num(plugin.seasons().points(p)) + " pts"
                + (seasonRank > 0 ? "  (#" + seasonRank + ")" : ""))));
        lore.add(Component.text("✔ ", accent).append(Items.kv("Série de missions", plugin.missions().activeStreak(p) + " j")));
        if (team != null) lore.add(Component.text("Équipe  ", Text.GRAY).append(plugin.teams().tagComponent(team))
                .append(Component.text(team.name, Text.WHITE)));
        ItemStack head = plugin.heads().head(player.getUniqueId(), player.getName());
        inv.setItem(4, Items.decorate(head, plugin.rewards().coloredName(player.getUniqueId(), player.getName(), Text.WHITE)
                .decorate(TextDecoration.BOLD), lore, false));

        int done = 0;
        for (Profile.MissionSlot m : p.daily) if (m.done) done++;
        boolean daily = plugin.daily().canClaim(p);

        // ─── Rangée 0 : communauté (plugin COMMUNAUTE) ───
        communityRow(holder, inv);

        // ─── Rangée 1 : progresser ───
        section(inv, 18, Material.YELLOW_STAINED_GLASS_PANE, "<#ffd86b>Progresser", "Missions, succès, récompenses");
        button(holder, inv, 19, Material.WRITABLE_BOOK, "Missions", List.of(
                Items.line("3 missions par jour, 3 par semaine"),
                Items.kv("Aujourd'hui", done + " / " + p.daily.size()),
                Items.kv("Série", plugin.missions().activeStreak(p) + " jour(s)"),
                Items.dark("Commande : /missions")), done < p.daily.size(),
                pl -> plugin.missionMenu().open(pl));
        if (plugin.jobs().enabled()) {
            List<Component> jl = new ArrayList<>();
            jl.add(Items.line("Mineur, Bûcheron, Fermier, Chasseur,"));
            jl.add(Items.line("Pêcheur : des niveaux et des pièces"));
            jl.add(Items.line("en jouant normalement"));
            for (var job : plugin.jobs().all()) {
                jl.add(Component.text(" ").append(job.label()).append(Component.text("  niv. " + plugin.jobs().level(p, job.id()), Text.WHITE)));
            }
            jl.add(Items.dark("Commande : /metiers"));
            button(holder, inv, 20, Material.IRON_PICKAXE, "Métiers", jl, false, pl -> plugin.jobMenu().open(pl));
        }
        button(holder, inv, 21, Material.KNOWLEDGE_BOOK, "Succès", List.of(
                Items.line("Ta progression et ce qu'il te reste"),
                Items.kv("Obtenus", s.count() + " / " + plugin.registry().total()),
                Items.dark("Commande : /succes")), false,
                pl -> plugin.achievementsMenu().open(pl));
        button(holder, inv, 22, daily ? Material.CHEST : Material.BARREL, "Récompense du jour", List.of(
                Items.line(daily ? "Disponible : viens la chercher !" : "Déjà récupérée aujourd'hui"),
                Items.kv("Série", plugin.daily().currentStreak(p) + " jour(s)"),
                Items.dark("Commande : /recompense")), daily,
                pl -> plugin.rewardMenus().openDaily(pl));
        button(holder, inv, 23, Material.GOLD_INGOT, "Parcours de récompenses", List.of(
                Items.line("Des paliers qui se débloquent tout seuls"),
                Items.kv("Débloqués", p.claimed.size() + " / " + plugin.rewards().paliers().size()),
                Items.dark("Commande : /parcours")), false,
                pl -> plugin.rewardMenus().openPath(pl, 1));
        button(holder, inv, 24, Material.NETHER_STAR, plugin.seasons().name(), List.of(
                Items.line("Le classement du mois"),
                Items.kv("Fin dans", plugin.seasons().timeLeft()),
                plugin.seasons().isWeekend() ? Component.text("✦ Week-end : points bonus !", accent) : Items.dark("Commande : /saison")),
                plugin.seasons().isWeekend(), pl -> plugin.seasonMenu().open(pl));
        button(holder, inv, 25, Material.DIAMOND, "Classements", List.of(
                Items.line("Le podium des meilleurs joueurs"),
                Items.dark("Commande : /classement")), false,
                pl -> plugin.achievementsMenu().openTop(pl));
        button(holder, inv, 26, Material.WITHER_SKELETON_SKULL, "Boss mondial", List.of(
                Items.line(plugin.boss().active() ? "Le boss est en cours de combat !" : "Prochaine apparition :"),
                Items.line(plugin.boss().active() ? "Clique pour rejoindre l'arène" : plugin.boss().nextText()),
                Items.dark("Commande : /boss")),
                plugin.boss().active(), pl -> {
                    pl.closeInventory();
                    pl.performCommand("boss");
                });

        // ─── Rangée 2 : se déplacer ───
        travelRow(holder, inv, player, p);

        // ─── Rangée 3 : personnaliser et échanger ───
        section(inv, 36, Material.PURPLE_STAINED_GLASS_PANE, "<#c79bff>Style et échanges", "Grade, titres, cosmétiques, boutique");
        button(holder, inv, 37, Material.NAME_TAG, "Titres et grade", List.of(
                Component.text("Grade : ", Text.GRAY).append(plugin.grades().display(p.uuid())).decoration(TextDecoration.ITALIC, false),
                Items.line("Le titre à côté de ton pseudo"),
                Items.kv("Titres débloqués", plugin.rewards().unlockedTitles(p) + " / " + plugin.rewards().titles().size()),
                Items.dark("Commande : /titres")), false,
                pl -> plugin.rewardMenus().openTitles(pl, 1));
        button(holder, inv, 38, Material.BLAZE_POWDER, "Cosmétiques", List.of(
                Items.line("Couleur du pseudo et traînées"),
                Items.dark("Commande : /cosmetiques")), false,
                pl -> plugin.rewardMenus().openCosmetics(pl));
        button(holder, inv, 39, Material.EMERALD, "Boutique", List.of(
                Items.line("Titres, cosmétiques, homes en plus"),
                Items.kv("Solde", plugin.economy().format(p.coins)),
                Items.dark("Commande : /boutique")), false,
                pl -> plugin.rewardMenus().openShop(pl, 1));
        button(holder, inv, 40, Material.GOLD_BLOCK, "Hôtel des ventes", List.of(
                Items.line("Achète et vends entre joueurs"),
                Items.kv("Objets en vente", String.valueOf(plugin.auctions().active().size())),
                Items.dark("Commande : /hdv")), false,
                pl -> plugin.auctionCommand().open(pl, 1, false));
        button(holder, inv, 41, Material.WHITE_BANNER, "Équipe", List.of(
                Items.line(team == null ? "Crée ou rejoins une équipe" : "Ton équipe : " + team.name),
                Items.dark("Commande : /equipe")), false,
                pl -> plugin.teamCommand().openMenu(pl));
        button(holder, inv, 42, Material.FILLED_MAP, "Carte et site", List.of(
                Items.line("La carte du serveur en direct"),
                Items.line("et les classements sur lyonmc.fr"),
                Items.dark("Commande : /carte")), false, pl -> {
                    pl.closeInventory();
                    pl.performCommand("carte");
                });

        // ─── Bas ───
        if (hasCommand("aide")) {
            button(holder, inv, 45, Material.LECTERN, "Aide et guide", List.of(
                    Items.line("Tout comprendre sur LyonMC :"),
                    Items.line("commencer, gagner des pièces,"),
                    Items.line("protéger sa base, les règles..."),
                    Items.dark("Commande : /aide")), true, pl -> {
                        pl.closeInventory();
                        pl.performCommand("aide");
                    });
        }
        button(holder, inv, 47, Material.BOOK, "Toutes les commandes", List.of(
                Items.line("La liste des commandes, cliquables,"),
                Items.line("dans le chat")), false, pl -> {
                    pl.closeInventory();
                    sendCommands(pl);
                });
        button(holder, inv, 49, Material.BARRIER, "Fermer", List.of(), false, Player::closeInventory);
        PlayerStats stats = plugin.stats().get(player.getUniqueId());
        boolean sidebar = stats == null || stats.sidebar();
        button(holder, inv, 51, Material.COMPARATOR, "Scoreboard : " + (sidebar ? "affiché" : "masqué"), List.of(
                Items.line("Afficher ou masquer le scoreboard")), false, pl -> {
                    pl.performCommand("succes sidebar");
                    open(pl);
                });
        Items.fill(inv);
        player.openInventory(inv);
    }

    // ───────────────────────────── Communauté ─────────────────────────────

    private void communityRow(MenuHolder holder, Inventory inv) {
        if (!hasCommand("vote") && !hasCommand("premierspas") && !hasCommand("parrain") && !hasCommand("objectif")) return;
        section(inv, 9, Material.LIME_STAINED_GLASS_PANE, "<#9be37c>Communauté", "Voter, parrainer, jouer ensemble");
        int slot = 11;
        if (hasCommand("vote")) {
            button(holder, inv, slot++, Material.GOLDEN_APPLE, "Voter pour LyonMC", List.of(
                    Items.line("Une récompense à chaque vote,"),
                    Items.line("et le serveur monte dans les classements"),
                    Items.dark("Commande : /vote")), true, pl -> {
                        pl.closeInventory();
                        pl.performCommand("vote");
                    });
        }
        if (hasCommand("premierspas")) {
            button(holder, inv, slot++, Material.MAP, "Premiers pas", List.of(
                    Items.line("Les étapes pour bien commencer"),
                    Items.line("(avec des récompenses)"),
                    Items.dark("Commande : /premierspas")), false, pl -> {
                        pl.closeInventory();
                        pl.performCommand("premierspas");
                    });
        }
        if (hasCommand("parrain")) {
            button(holder, inv, slot++, Material.TOTEM_OF_UNDYING, "Parrainage", List.of(
                    Items.line("Invite tes amis : vous êtes"),
                    Items.line("récompensés tous les deux"),
                    Items.dark("Commande : /parrain")), false, pl -> {
                        pl.closeInventory();
                        pl.performCommand("parrain");
                    });
        }
        if (hasCommand("objectif")) {
            button(holder, inv, slot++, Material.BEACON, "Objectif de la semaine", List.of(
                    Items.line("Un défi pour tout le serveur,"),
                    Items.line("une récompense pour tous"),
                    Items.dark("Commande : /objectif")), false, pl -> {
                        pl.closeInventory();
                        pl.performCommand("objectif");
                    });
        }
        var boost = plugin.boosts().current();
        if (boost != null) {
            inv.setItem(slot, Items.item(Material.EXPERIENCE_BOTTLE, plugin.text().parse("<#ffd86b><bold>Bonus en cours !")
                    .decoration(TextDecoration.ITALIC, false), List.of(
                    Items.line("Pièces x" + fmt(boost.coins()) + ", points de saison x" + fmt(boost.season())),
                    Items.kv("Fin dans", Math.max(1, (boost.until() - System.currentTimeMillis()) / 60000) + " min")), true));
        }
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    // ───────────────────────────── Se déplacer ─────────────────────────────

    private static boolean hasCommand(String name) {
        return Bukkit.getPluginCommand(name) != null;
    }

    private static String where(org.bukkit.Location l) {
        return l == null || l.getWorld() == null ? "aucune" : l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    private void travelRow(MenuHolder holder, Inventory inv, Player player, Profile p) {
        section(inv, 27, Material.LIGHT_BLUE_STAINED_GLASS_PANE, "<#9fd8ff>Se déplacer", "Hub, survie, mine, homes...");
        boolean inHub = plugin.spawnHub() != null && plugin.spawnHub().inHub(player.getLocation());
        boolean inRes = plugin.resources().world() != null && player.getWorld().equals(plugin.resources().world());

        button(holder, inv, 28, Material.LODESTONE, "Hub", List.of(
                Items.line("Le spawn : stats, podium, menus"),
                inHub ? Component.text("✔ Tu y es", plugin.text().accent()) : Items.dark("Commande : /hub")), inHub, pl -> {
                    pl.closeInventory();
                    pl.performCommand("spawn");
                });
        if (hasCommand("survie")) {
            button(holder, inv, 29, Material.GRASS_BLOCK, "Retour en survie", List.of(
                    Items.line("Là où tu étais dans le monde survie,"),
                    Items.line("même après le hub ou la mine"),
                    Items.dark("Commande : /survie")), inHub || inRes, pl -> {
                        pl.closeInventory();
                        pl.performCommand("survie");
                    });
        } else {
            button(holder, inv, 29, Material.GRASS_BLOCK, "Aventure au hasard", List.of(
                    Items.line("Un endroit sûr au hasard du monde survie"),
                    Items.dark("Commande : /rtp")), false, pl -> {
                        pl.closeInventory();
                        pl.performCommand("rtp");
                    });
        }
        button(holder, inv, 30, Material.DIAMOND_PICKAXE, "La mine (monde ressources)", List.of(
                Items.line("Le monde où miner rapporte le plus :"),
                Items.line("plus de minerais, minage rapide,"),
                Items.line("pièces et trésors. Remis à neuf"),
                Items.line("chaque mois."),
                inRes ? Component.text("✔ Tu y es", plugin.text().accent()) : Items.dark("Commande : /mine")), inRes, pl -> {
                    pl.closeInventory();
                    plugin.resources().teleport(pl);
                });
        button(holder, inv, 31, Material.RED_BED, "Homes", List.of(
                Items.line("Tes maisons : clique pour y aller"),
                Items.kv("Emplacements", p.homes.size() + " / " + plugin.rewards().homesLimit(p)),
                Items.dark("Commandes : /home, /sethome <nom>")), false,
                pl -> plugin.teleportCommands().openHomes(pl));
        org.bukkit.Location back = plugin.teleporter().back(player.getUniqueId());
        button(holder, inv, 32, Material.RECOVERY_COMPASS, "Position précédente", List.of(
                Items.line("Revenir là où tu étais juste avant"),
                Items.line("(dernière téléportation ou mort)"),
                Items.kv("Position", where(back)),
                Items.dark("Commande : /back")), false, pl -> {
                    pl.closeInventory();
                    pl.performCommand("back");
                });
        button(holder, inv, 33, Material.ENDER_PEARL, "Aventure au hasard", List.of(
                Items.line("Un endroit sûr au hasard du monde survie"),
                Items.line("(une fois toutes les " + Math.max(1, plugin.getConfig().getLong("teleport.rtp.cooldown-seconds", 600) / 60) + " minutes)"),
                Items.dark("Commande : /rtp")), false, pl -> {
                    pl.closeInventory();
                    pl.performCommand("rtp");
                });
        button(holder, inv, 34, Material.LEAD, "Rejoindre un joueur", List.of(
                Items.line("Choisis un joueur connecté :"),
                Items.line("il devra accepter ta demande"),
                Items.dark("Commandes : /tpa <joueur>, /tpaccept")), false, this::openPlayers);
    }

    /** Liste des joueurs connectés pour envoyer une demande de téléportation. */
    private void openPlayers(Player player) {
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text("Rejoindre un joueur"));
        holder.inventory(inv);
        int slot = 0;
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(player) || slot >= 45) continue;
            ItemStack head = plugin.heads().head(other.getUniqueId(), other.getName());
            inv.setItem(slot, Items.decorate(head, plugin.rewards().coloredName(other.getUniqueId(), other.getName(), Text.WHITE)
                    .decorate(TextDecoration.BOLD), List.of(Items.line("Lui demander de te téléporter"),
                    Items.EMPTY, Items.action("Cliquer pour envoyer la demande", plugin.text().accent())), false));
            String name = other.getName();
            holder.action(slot, pl -> {
                pl.closeInventory();
                pl.performCommand("tpa " + name);
            });
            slot++;
        }
        if (slot == 0) {
            inv.setItem(22, Items.item(Material.GRAY_DYE, Component.text("Personne d'autre n'est connecté", Text.GRAY), List.of(), false));
        }
        inv.setItem(49, Items.item(Material.ARROW, Component.text("Retour au menu", Text.WHITE), List.of(), false));
        holder.action(49, this::open);
        Items.fill(inv);
        player.openInventory(inv);
    }

    /** Toutes les commandes, regroupées et cliquables, dans le chat. */
    private void sendCommands(Player p) {
        List<String[]> groups = new ArrayList<>();
        groups.add(new String[]{"Se déplacer", "hub|Le hub (spawn)", hasCommand("survie") ? "survie|Retour en survie" : "rtp|Aventure au hasard",
                "mine|La mine (monde ressources)", "home|Tes homes", "sethome maison|Poser un home ici", "back|Position précédente",
                "rtp|Aventure au hasard", "tpa |Rejoindre un joueur"});
        groups.add(new String[]{"Progresser", "missions|Missions du jour", "metiers|Tes métiers", "succes|Tes succès",
                "recompense|Récompense du jour", "parcours|Paliers", "saison|Saison du mois", "classement|Classements", "boss|Boss mondial"});
        List<String> community = new ArrayList<>();
        community.add("Communauté");
        if (hasCommand("vote")) community.add("vote|Voter pour le serveur");
        if (hasCommand("premierspas")) community.add("premierspas|Premiers pas");
        if (hasCommand("parrain")) community.add("parrain|Parrainage");
        if (hasCommand("objectif")) community.add("objectif|Objectif de la semaine");
        if (hasCommand("bienvenue")) community.add("bienvenue|Souhaiter la bienvenue au dernier arrivé");
        if (hasCommand("ami")) community.add("ami|Tes amis");
        if (hasCommand("msg")) community.add("msg |Message privé à un joueur");
        if (hasCommand("report")) community.add("report |Signaler un joueur ou un problème");
        if (hasCommand("aide")) community.add("aide|Le centre d'aide");
        if (hasCommand("regles")) community.add("regles|Le règlement");
        if (community.size() > 1) groups.add(community.toArray(new String[0]));
        groups.add(new String[]{"Style et échanges", "titres|Titres et grade", "cosmetiques|Cosmétiques", "boutique|Boutique", "hdv|Hôtel des ventes",
                "equipe|Équipe", "money|Ton solde", "pay |Donner des pièces"});
        p.sendMessage(Component.empty());
        p.sendMessage(plugin.text().parse("  <accent>✦</accent> <white><bold>Commandes de LyonMC</bold></white> <dark_gray>(clique pour lancer)"));
        for (String[] g : groups) {
            p.sendMessage(plugin.text().parse("  <accent>" + g[0]));
            for (int i = 1; i < g.length; i++) {
                String[] c = g[i].split("\\|");
                String cmd = "/" + c[0];
                String click = c[0].endsWith(" ") ? "suggest_command" : "run_command";
                p.sendMessage(plugin.text().parse("    <click:" + click + ":'" + cmd + "'><hover:show_text:'<gray>" + c[1] + "'><white>"
                        + cmd.trim() + "</white></hover></click> <dark_gray>" + c[1]));
            }
        }
        p.sendMessage(Component.empty());
    }

    private void section(Inventory inv, int slot, Material pane, String title, String sub) {
        inv.setItem(slot, Items.item(pane, plugin.text().parse("<bold>" + title).decoration(TextDecoration.ITALIC, false),
                List.of(Items.line(sub)), false));
    }

    private void button(MenuHolder holder, Inventory inv, int slot, Material icon, String name, List<Component> lore,
                        boolean glint, Consumer<Player> action) {
        List<Component> l = new ArrayList<>(lore);
        l.add(Items.EMPTY);
        l.add(Items.action("Cliquer pour ouvrir", plugin.text().accent()));
        inv.setItem(slot, Items.item(icon, Component.text(name, Text.WHITE).decorate(TextDecoration.BOLD), l, glint));
        holder.action(slot, action);
    }
}
