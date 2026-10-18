package fr.lyonmc.achievements.boss;

import fr.lyonmc.achievements.LyonAchievements;
import fr.lyonmc.achievements.core.Profile;
import fr.lyonmc.achievements.core.StoredLocation;
import fr.lyonmc.achievements.rewards.Bundle;
import fr.lyonmc.achievements.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Boss mondial : apparaît aux horaires prévus dans l'arène, capacités spéciales,
 * barre de vie, récompenses selon les dégâts infligés.
 */
public final class BossService implements Listener {

    private final LyonAchievements plugin;
    private final NamespacedKey bossKey;
    private final NamespacedKey minionKey;

    private LivingEntity boss;
    private BossBar bar;
    private final Map<UUID, Double> damage = new HashMap<>();
    private final Set<UUID> viewers = new HashSet<>();
    private final List<Entity> minions = new ArrayList<>();
    private long startedAt;
    private int tickCount;
    private final Set<String> firedKeys = new HashSet<>();

    public BossService(LyonAchievements plugin) {
        this.plugin = plugin;
        this.bossKey = new NamespacedKey(plugin, "boss");
        this.minionKey = new NamespacedKey(plugin, "boss_minion");
    }

    private ConfigurationSection cfg() {
        return plugin.configs().events().getConfigurationSection("boss");
    }

    public boolean active() {
        return boss != null && boss.isValid() && !boss.isDead();
    }

    public Location arena() {
        StoredLocation s = StoredLocation.parse(plugin.configs().state().getString("boss.arena"));
        return s == null ? null : s.toLocation();
    }

    public void setArena(Location l) {
        plugin.configs().state().set("boss.arena", StoredLocation.of(l).serialize());
        plugin.configs().saveState();
    }

    /** Supprime les restes d'un boss précédent (redémarrage). */
    public void cleanup() {
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE)
                        || e.getPersistentDataContainer().has(minionKey, PersistentDataType.BYTE)) e.remove();
            }
        }
    }

    // ───────────────────────────── Planning ─────────────────────────────

    private record Slot(DayOfWeek day, LocalTime time) {
    }

    private List<Slot> schedule() {
        List<Slot> out = new ArrayList<>();
        if (cfg() == null) return out;
        for (String s : cfg().getStringList("schedule")) {
            String[] p = s.trim().split("\\s+");
            if (p.length != 2) continue;
            try {
                out.add(new Slot(DayOfWeek.valueOf(p[0].toUpperCase(Locale.ROOT)), LocalTime.parse(p[1])));
            } catch (Exception e) {
                plugin.getLogger().warning("Horaire de boss invalide : " + s);
            }
        }
        return out;
    }

    /** Prochaine apparition prévue. */
    public ZonedDateTime next() {
        ZonedDateTime now = ZonedDateTime.now(plugin.text().zone());
        ZonedDateTime best = null;
        for (Slot s : schedule()) {
            ZonedDateTime c = now.with(TemporalAdjusters.nextOrSame(s.day())).with(s.time()).withSecond(0).withNano(0);
            if (c.isBefore(now)) c = c.plusWeeks(1);
            if (best == null || c.isBefore(best)) best = c;
        }
        return best;
    }

    public String nextText() {
        if (arena() == null) return "pas encore programmée (arène à définir)";
        ZonedDateTime n = next();
        if (n == null) return "aucune apparition prévue";
        long minutes = ChronoUnit.MINUTES.between(ZonedDateTime.now(plugin.text().zone()), n);
        String day = n.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.FRANCE);
        String when = day + " à " + n.toLocalTime().toString();
        if (minutes < 60) return "dans " + Math.max(1, minutes) + " min";
        if (minutes < 24 * 60) return "dans " + (minutes / 60) + " h " + (minutes % 60) + " min (" + when + ")";
        return when;
    }

    /** Appelé toutes les 20 secondes. */
    public void tickSchedule() {
        if (cfg() == null || !cfg().getBoolean("enabled", true) || !plugin.module("boss") || arena() == null) return;
        ZonedDateTime now = ZonedDateTime.now(plugin.text().zone()).withSecond(0).withNano(0);
        for (Slot s : schedule()) {
            ZonedDateTime at = now.with(TemporalAdjusters.nextOrSame(s.day())).with(s.time());
            if (at.isBefore(now)) at = at.plusWeeks(1);
            long minutes = ChronoUnit.MINUTES.between(now, at);
            String base = at.toLocalDate() + "T" + s.time();
            for (int w : cfg().getIntegerList("warnings-minutes")) {
                if (minutes == w && firedKeys.add(base + "-w" + w)) warn(w);
            }
            if (minutes == 0 && firedKeys.add(base + "-start") && !active()) start(null);
        }
        if (firedKeys.size() > 200) firedKeys.clear();
    }

    private void warn(int minutes) {
        Bukkit.broadcast(plugin.text().parse("<dark_gray>[<red>Boss</red>]</dark_gray> ").append(plugin.text().parse(cfg().getString("name", "Le Colosse")))
                .append(plugin.text().parse(" <gray>apparaît dans <white>" + minutes + " min</white>. "
                        + "<click:run_command:'/boss'><hover:show_text:'<gray>Rejoindre l'arène'><accent><u>/boss</u></accent></hover></click>")));
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 0.6f, 0.8f);
        if (minutes == cfg().getIntegerList("warnings-minutes").stream().mapToInt(i -> i).max().orElse(-1)) {
            plugin.discord().send("Boss mondial", "Le boss mondial apparaît dans " + minutes + " minutes sur LyonMC !", 0xFF6B6B, null);
        }
    }

    // ───────────────────────────── Combat ─────────────────────────────

    public boolean start(Location override) {
        if (active()) return false;
        Location at = override != null ? override : arena();
        if (at == null || at.getWorld() == null) {
            plugin.getLogger().warning("Boss : aucune arène définie (/boss arene).");
            return false;
        }
        EntityType type;
        try {
            type = EntityType.valueOf(cfg().getString("entity", "WITHER_SKELETON").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            type = EntityType.WITHER_SKELETON;
        }
        Class<? extends Entity> cls = type.getEntityClass();
        if (cls == null || !LivingEntity.class.isAssignableFrom(cls)) cls = WitherSkeleton.class;

        int nearby = 0;
        for (Player p : at.getWorld().getPlayers()) if (p.getLocation().distanceSquared(at) < 128 * 128) nearby++;
        int online = Math.max(nearby, Math.min(Bukkit.getOnlinePlayers().size(), 10));
        double health = Math.min(1024, cfg().getDouble("health-base", 400) + cfg().getDouble("health-per-player", 150) * Math.max(1, online));

        Component name = plugin.text().parse(cfg().getString("name", "Le Colosse"));
        Entity e = at.getWorld().spawn(at, cls, ent -> {
            LivingEntity le = (LivingEntity) ent;
            le.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 1);
            le.customName(name);
            le.setCustomNameVisible(true);
            le.setRemoveWhenFarAway(false);
            le.setPersistent(false);
            set(le, Attribute.MAX_HEALTH, health);
            set(le, Attribute.SCALE, cfg().getDouble("scale", 2.6));
            set(le, Attribute.ATTACK_DAMAGE, cfg().getDouble("damage", 12));
            set(le, Attribute.ARMOR, cfg().getDouble("armor", 12));
            set(le, Attribute.KNOCKBACK_RESISTANCE, 1.0);
            set(le, Attribute.FOLLOW_RANGE, 48);
            le.setHealth(health);
            if (le.getEquipment() != null) {
                le.getEquipment().setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.NETHERITE_SWORD));
                le.getEquipment().setItemInMainHandDropChance(0f);
                le.getEquipment().setHelmet(new org.bukkit.inventory.ItemStack(Material.NETHERITE_HELMET));
                le.getEquipment().setHelmetDropChance(0f);
            }
            le.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
        });
        boss = (LivingEntity) e;
        damage.clear();
        startedAt = System.currentTimeMillis();
        tickCount = 0;
        bar = BossBar.bossBar(name, 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);

        at.getWorld().strikeLightningEffect(at);
        at.getWorld().playSound(at, Sound.ENTITY_WITHER_SPAWN, 2f, 0.8f);
        Bukkit.broadcast(plugin.text().parse("<dark_gray>[<red>Boss</red>]</dark_gray> ").append(name)
                .append(plugin.text().parse(" <gray>est apparu ! Rejoins le combat : "
                        + "<click:run_command:'/boss'><hover:show_text:'<gray>Rejoindre l'arène'><accent><u>/boss</u></accent></hover></click>")));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(Title.title(name, Component.text("est apparu · /boss", Text.GRAY),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700))));
        }
        plugin.discord().send("Boss mondial", "Le boss mondial vient d'apparaître sur LyonMC !", 0xFF6B6B, null);
        return true;
    }

    private static void set(LivingEntity e, Attribute a, double value) {
        AttributeInstance inst = e.getAttribute(a);
        if (inst != null) inst.setBaseValue(value);
    }

    /** Appelé chaque seconde. */
    public void tick() {
        if (boss == null) return;
        if (!active()) {
            end(false, boss.isDead() ? null : "<gray>Le boss a disparu.");
            return;
        }
        tickCount++;
        long duration = cfg().getLong("duration-minutes", 15) * 60_000L;
        if (System.currentTimeMillis() - startedAt > duration) {
            end(false, "<gray>Le boss s'est enfui... Il reviendra plus fort.");
            return;
        }
        double max = maxHealth();
        float progress = (float) Math.max(0, Math.min(1, boss.getHealth() / max));
        bar.progress(progress);
        Set<UUID> now = new HashSet<>();
        for (Player p : boss.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(boss.getLocation()) < 64 * 64) {
                now.add(p.getUniqueId());
                if (viewers.add(p.getUniqueId())) p.showBossBar(bar);
            }
        }
        for (UUID u : new HashSet<>(viewers)) {
            if (!now.contains(u)) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) p.hideBossBar(bar);
                viewers.remove(u);
            }
        }
        if (boss instanceof Mob mob && (mob.getTarget() == null || !(mob.getTarget() instanceof Player))) {
            Player nearest = nearest(24);
            if (nearest != null) mob.setTarget(nearest);
        }
        boolean enraged = progress < 0.5f;
        int every = enraged ? 5 : 8;
        if (tickCount % every == 0) ability(enraged);
    }

    private double maxHealth() {
        AttributeInstance a = boss.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? boss.getHealth() : a.getValue();
    }

    private Player nearest(double radius) {
        Player best = null;
        double bestD = radius * radius;
        for (Player p : boss.getWorld().getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) continue;
            double d = p.getLocation().distanceSquared(boss.getLocation());
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private List<Player> around(double radius) {
        List<Player> out = new ArrayList<>();
        for (Player p : boss.getWorld().getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) continue;
            if (p.getLocation().distanceSquared(boss.getLocation()) < radius * radius) out.add(p);
        }
        return out;
    }

    private void ability(boolean enraged) {
        int choice = ThreadLocalRandom.current().nextInt(4);
        Location loc = boss.getLocation();
        World w = loc.getWorld();
        switch (choice) {
            case 0 -> { // Onde de choc
                w.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.6f);
                w.spawnParticle(Particle.EXPLOSION, loc, 6, 2, 0.5, 2, 0);
                for (Player p : around(9)) {
                    Vector push = p.getLocation().toVector().subtract(loc.toVector()).setY(0);
                    if (push.lengthSquared() < 0.01) push = new Vector(0.1, 0, 0);
                    p.setVelocity(push.normalize().multiply(1.6).setY(0.7));
                    p.damage(enraged ? 8 : 6, boss);
                }
                say("<gray>déclenche une onde de choc !");
            }
            case 1 -> { // Invocation
                int n = enraged ? 5 : 3;
                for (int i = 0; i < n; i++) {
                    Location l = loc.clone().add(ThreadLocalRandom.current().nextDouble(-4, 4), 0, ThreadLocalRandom.current().nextDouble(-4, 4));
                    Entity m = enraged ? w.spawn(l, WitherSkeleton.class, this::tagMinion) : w.spawn(l, Skeleton.class, this::tagMinion);
                    minions.add(m);
                    w.spawnParticle(Particle.LARGE_SMOKE, l, 10, 0.3, 0.6, 0.3, 0.01);
                }
                say("<gray>invoque des renforts !");
            }
            case 2 -> { // Foudre
                List<Player> targets = around(20);
                Collections.shuffle(targets);
                for (int i = 0; i < Math.min(enraged ? 4 : 2, targets.size()); i++) {
                    Player t = targets.get(i);
                    w.strikeLightningEffect(t.getLocation());
                    t.damage(enraged ? 7 : 5, boss);
                    t.setFireTicks(60);
                }
                say("<gray>appelle la foudre !");
            }
            default -> { // Bond vers le joueur le plus éloigné
                Player far = null;
                double best = 0;
                for (Player p : around(24)) {
                    double d = p.getLocation().distanceSquared(loc);
                    if (d > best) {
                        best = d;
                        far = p;
                    }
                }
                if (far != null) {
                    Vector dir = far.getLocation().toVector().subtract(loc.toVector());
                    boss.setVelocity(dir.multiply(0.18).setY(0.9));
                    w.playSound(loc, Sound.ENTITY_RAVAGER_ROAR, 1.5f, 0.8f);
                    say("<gray>bondit sur <white>" + far.getName() + "</white> !");
                }
            }
        }
        minions.removeIf(m -> !m.isValid());
    }

    private void tagMinion(Entity ent) {
        ent.getPersistentDataContainer().set(minionKey, PersistentDataType.BYTE, (byte) 1);
        ent.setPersistent(false);
        if (ent instanceof LivingEntity le) set(le, Attribute.MAX_HEALTH, 20);
    }

    private void say(String mini) {
        Component msg = plugin.text().parse(cfg().getString("name", "Le Colosse")).append(plugin.text().parse(" " + mini));
        for (Player p : around(48)) p.sendActionBar(msg);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (boss == null || !event.getEntity().equals(boss)) return;
        Player attacker = null;
        if (event.getDamager() instanceof Player p) attacker = p;
        else if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) attacker = p;
        else if (event.getDamager() instanceof Tameable tame && tame.getOwner() instanceof Player p) attacker = p;
        if (attacker == null) return;
        double dealt = Math.min(event.getFinalDamage(), boss.getHealth());
        damage.merge(attacker.getUniqueId(), dealt, Double::sum);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMinionTarget(EntityTargetEvent event) {
        // Les sbires n'attaquent pas le boss et le boss n'attaque pas ses sbires
        if (event.getTarget() == null) return;
        boolean fromMinion = event.getEntity().getPersistentDataContainer().has(minionKey, PersistentDataType.BYTE)
                || event.getEntity().getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE);
        boolean toAlly = event.getTarget().getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE)
                || event.getTarget().getPersistentDataContainer().has(minionKey, PersistentDataType.BYTE);
        if (fromMinion && toAlly) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead.getPersistentDataContainer().has(minionKey, PersistentDataType.BYTE)) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            return;
        }
        if (boss == null || !dead.equals(boss)) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        Player killer = dead.getKiller();
        Location loc = dead.getLocation();
        reward(killer);
        firework(loc);
        end(true, null);
    }

    private void reward(Player lastHit) {
        double total = 0;
        for (double d : damage.values()) total += d;
        List<Map.Entry<UUID, Double>> ranking = new ArrayList<>(damage.entrySet());
        ranking.sort(Map.Entry.<UUID, Double>comparingByValue().reversed());
        double min = cfg().getDouble("min-damage-percent", 2);

        Bukkit.broadcast(Component.empty());
        Bukkit.broadcast(plugin.text().parse("<dark_gray>[<red>Boss</red>]</dark_gray> ").append(plugin.text().parse(cfg().getString("name", "Le Colosse")))
                .append(plugin.text().parse(" <gray>a été vaincu !")));
        String[] medals = {"<#ffd86b>♛ 1</#ffd86b>", "<#c8c8c8>✧ 2</#c8c8c8>", "<#d29a6a>✧ 3</#d29a6a>"};
        StringBuilder discord = new StringBuilder();
        for (int i = 0; i < ranking.size(); i++) {
            UUID u = ranking.get(i).getKey();
            double pct = total <= 0 ? 0 : ranking.get(i).getValue() * 100 / total;
            Profile p = plugin.profiles().get(u);
            if (p == null) continue;
            if (i < 3) {
                Bukkit.broadcast(plugin.text().parse("  " + medals[i] + " <white>" + p.name + "</white> <dark_gray>"
                        + String.format(Locale.FRANCE, "%.0f", pct) + " % des dégâts"));
                discord.append(i + 1).append(". ").append(p.name).append(" — ").append(String.format(Locale.FRANCE, "%.0f", pct)).append(" %\n");
            }
            if (pct < min) continue;
            p.bossKills++;
            p.dirty();
            give(p, "boss.rewards.participation");
            if (i < 3) give(p, "boss.rewards.top" + (i + 1));
            plugin.custom().grantLater(p, "colosse");
            Player online = Bukkit.getPlayer(u);
            if (online != null) {
                plugin.msg(online, "<gray>Ta part : <white>" + String.format(Locale.FRANCE, "%.1f", pct) + " %</white> des dégâts.");
                plugin.rewards().check(online);
            }
        }
        if (lastHit != null) {
            Profile p = plugin.profiles().get(lastHit);
            p.bossLastHits++;
            p.dirty();
            give(p, "boss.rewards.last-hit");
            plugin.custom().grant(lastHit, "coup_de_grace");
            Bukkit.broadcast(plugin.text().parse("  <gray>Coup de grâce : <white>" + lastHit.getName()));
        }
        plugin.discord().send("Boss vaincu !", discord.isEmpty() ? "Le boss mondial a été vaincu." : discord.toString(), 0x7BD88F,
                ranking.isEmpty() ? null : ranking.get(0).getKey());
    }

    private void give(Profile p, String path) {
        Player online = Bukkit.getPlayer(p.uuid());
        Bundle b = Bundle.of(plugin.configs().events().getConfigurationSection(path));
        if (b.isEmpty()) return;
        if (online != null) plugin.rewards().grant(online, b);
        else {
            p.pendingBundles.add(path);
            p.dirty();
        }
    }

    private void firework(Location loc) {
        World w = loc.getWorld();
        for (int i = 0; i < 4; i++) {
            Location l = loc.clone().add(ThreadLocalRandom.current().nextDouble(-2, 2), 1, ThreadLocalRandom.current().nextDouble(-2, 2));
            w.spawn(l, Firework.class, fw -> {
                org.bukkit.inventory.meta.FireworkMeta meta = fw.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE)
                        .withColor(Color.fromRGB(0xFFD86B), Color.fromRGB(0xFF6B6B)).withFade(Color.WHITE).flicker(true).build());
                meta.setPower(1);
                fw.setFireworkMeta(meta);
            });
        }
    }

    public void end(boolean killed, String message) {
        if (bar != null) {
            for (UUID u : viewers) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) p.hideBossBar(bar);
            }
        }
        viewers.clear();
        for (Entity m : minions) if (m.isValid()) m.remove();
        minions.clear();
        if (boss != null && boss.isValid() && !killed) boss.remove();
        boss = null;
        bar = null;
        damage.clear();
        if (message != null) {
            Bukkit.broadcast(plugin.text().parse("<dark_gray>[<red>Boss</red>]</dark_gray> " + message));
        }
    }
}
