package fr.lyonmc.communaute;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Messages privés (/msg, /r), /ignorer et /espion pour le staff. */
public final class Messages implements Listener {

    private static final TextColor PM = TextColor.color(0xC9B3FF);

    private final Communaute plugin;
    private final Map<UUID, UUID> last = new ConcurrentHashMap<>();
    private final Set<UUID> spies = ConcurrentHashMap.newKeySet();

    Messages(Communaute plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("messages.enabled", true);
    }

    static boolean staff(Player p) {
        return p.hasPermission("communaute.staff");
    }

    // ───────────────────────────── /msg et /r ─────────────────────────────

    void msg(Player from, String[] args) {
        if (!enabled()) return;
        if (args.length < 2) {
            plugin.msg(from, "<gray>Usage : <white>/msg <joueur> <message></white> <dark_gray>(puis /r pour répondre)");
            return;
        }
        Player to = Bukkit.getPlayerExact(args[0]);
        if (to == null) to = Bukkit.getPlayer(args[0]);
        if (to == null) {
            plugin.msg(from, "<gray>" + args[0] + " n'est pas connecté.");
            return;
        }
        send(from, to, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
    }

    void reply(Player from, String[] args) {
        if (!enabled()) return;
        if (args.length == 0) {
            plugin.msg(from, "<gray>Usage : <white>/r <message></white> <dark_gray>(répond à la dernière conversation)");
            return;
        }
        UUID u = last.get(from.getUniqueId());
        Player to = u == null ? null : Bukkit.getPlayer(u);
        if (to == null) {
            plugin.msg(from, "<gray>Personne à qui répondre (ou ton correspondant s'est déconnecté).");
            return;
        }
        send(from, to, String.join(" ", args));
    }

    private void send(Player from, Player to, String text) {
        if (to.equals(from)) {
            plugin.msg(from, "<gray>Tu te parles à toi-même ? Écris plutôt à un autre joueur.");
            return;
        }
        PlayerData target = plugin.store().get(to);
        boolean bypass = staff(from);
        if (!bypass) {
            if (target.ignored.contains(from.getUniqueId())) {
                plugin.msg(from, "<gray>" + to.getName() + " ne reçoit pas tes messages.");
                return;
            }
            if (target.pmMode.equals("personne") || target.pmMode.equals("amis") && !plugin.friends().areFriends(to.getUniqueId(), from.getUniqueId())) {
                plugin.msg(from, "<gray>" + to.getName() + " n'accepte les messages privés " + (target.pmMode.equals("amis") ? "que de ses amis." : "de personne."));
                return;
            }
        }
        if (plugin.store().get(from).ignored.contains(to.getUniqueId())) {
            plugin.msg(from, "<gray>Tu ignores " + to.getName() + " : <white>/ignorer " + to.getName() + "</white> pour lui écrire.");
            return;
        }
        Component body = Component.text(text, PM);
        Component reply = Component.text().hoverEvent(HoverEvent.showText(Component.text("Clique pour répondre", TextColor.color(0x9A9A9A))))
                .clickEvent(ClickEvent.suggestCommand("/msg " + from.getName() + " ")).build();
        from.sendMessage(plugin.parse("<dark_gray>[<gray>Toi</gray> → <white>" + to.getName() + "</white>]</dark_gray> ").append(body)
                .clickEvent(ClickEvent.suggestCommand("/msg " + to.getName() + " ")));
        to.sendMessage(reply.append(plugin.parse("<dark_gray>[<white>" + from.getName() + "</white> → <gray>Toi</gray>]</dark_gray> ")).append(body));
        to.playSound(to.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.6f);
        last.put(from.getUniqueId(), to.getUniqueId());
        last.put(to.getUniqueId(), from.getUniqueId());
        for (UUID s : spies) {
            Player spy = Bukkit.getPlayer(s);
            if (spy == null || spy.equals(from) || spy.equals(to)) continue;
            spy.sendMessage(plugin.parse("<dark_gray>[Espion] " + from.getName() + " → " + to.getName() + " : ").append(Component.text(text, TextColor.color(0x7A7A7A))));
        }
        if (plugin.getConfig().getBoolean("messages.journal", true)) plugin.getLogger().info("[MP] " + from.getName() + " -> " + to.getName() + " : " + text);
    }

    // ───────────────────────────── /ignorer ─────────────────────────────

    void ignore(Player p, String[] args) {
        PlayerData me = plugin.store().get(p);
        if (args.length == 0) {
            if (me.ignored.isEmpty()) {
                plugin.msg(p, "<gray>Tu n'ignores personne. <white>/ignorer <joueur></white> : ne plus voir ses messages.");
                return;
            }
            List<String> names = new ArrayList<>();
            for (UUID u : me.ignored) {
                PlayerData d = plugin.store().get(u);
                names.add(d == null || d.name == null ? u.toString().substring(0, 8) : d.name);
            }
            plugin.msg(p, "<gray>Tu ignores : <white>" + String.join(", ", names) + "</white> <dark_gray>(/ignorer <joueur> pour arrêter)");
            return;
        }
        PlayerData other = plugin.friends().known(args[0]);
        if (other == null) {
            plugin.msg(p, "<gray>Joueur inconnu : " + args[0]);
            return;
        }
        if (other.uuid.equals(p.getUniqueId())) {
            plugin.msg(p, "<gray>Tu ne peux pas t'ignorer toi-même.");
            return;
        }
        Player online = Bukkit.getPlayer(other.uuid);
        if (online != null && staff(online)) {
            plugin.msg(p, "<gray>On ne peut pas ignorer un membre du staff.");
            return;
        }
        if (me.ignored.remove(other.uuid)) {
            plugin.msg(p, "<gray>Tu n'ignores plus <white>" + other.name + "</white>.");
        } else {
            me.ignored.add(other.uuid);
            plugin.msg(p, "<gray>Tu ignores <white>" + other.name + "</white> : tu ne verras plus ses messages. <dark_gray>(/ignorer " + other.name + " pour annuler)");
        }
    }

    /** Les messages de ceux qu'on ignore n'apparaissent pas dans notre chat. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        UUID from = e.getPlayer().getUniqueId();
        if (staff(e.getPlayer())) return;
        e.viewers().removeIf(a -> a instanceof Player v && !v.getUniqueId().equals(from) && ignores(v.getUniqueId(), from));
    }

    private boolean ignores(UUID viewer, UUID from) {
        PlayerData d = plugin.store().get(viewer);
        return d != null && d.ignored.contains(from);
    }

    // ───────────────────────────── /espion ─────────────────────────────

    void spy(Player p) {
        if (!p.hasPermission("communaute.espion")) {
            plugin.msg(p, "<gray>Tu n'as pas la permission.");
            return;
        }
        if (spies.remove(p.getUniqueId())) plugin.msg(p, "<gray>Mode espion <red>désactivé</red>.");
        else {
            spies.add(p.getUniqueId());
            plugin.msg(p, "<gray>Mode espion <green>activé</green> : tu vois les messages privés des joueurs.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        last.remove(e.getPlayer().getUniqueId());
        spies.remove(e.getPlayer().getUniqueId());
    }
}
