package me.grace;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class GraceTime extends JavaPlugin implements Listener {

    private final Map<UUID, Integer> remaining = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private File dataFile;
    private YamlConfiguration data;
    private int totalSeconds;
    private boolean blockAttacker;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        totalSeconds = getConfig().getInt("grace-minutes", 60) * 60;
        blockAttacker = getConfig().getBoolean("block-attacker-while-protected", true);

        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        Bukkit.getPluginManager().registerEvents(this, this);
        // Handle players already online (e.g. /reload)
        for (Player p : Bukkit.getOnlinePlayers()) load(p);

        // Tick once per second
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) unload(p);
        saveData();
    }

    // ---------- core logic ----------

    private void load(Player p) {
        UUID id = p.getUniqueId();
        // New players get the full grace time, returning players continue where they left off
        int secs = data.getInt("players." + id, totalSeconds);
        remaining.put(id, secs);
        if (secs > 0) {
            BossBar bar = BossBar.bossBar(barTitle(secs), progress(secs),
                    BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
            bars.put(id, bar);
            p.showBossBar(bar);
            if (!data.contains("players." + id)) {
                p.sendMessage(Component.text("You have " + format(secs)
                        + " of PvP grace time. Nobody can hurt you (and you can't hurt others).",
                        NamedTextColor.GREEN));
            }
        }
    }

    private void unload(Player p) {
        UUID id = p.getUniqueId();
        Integer secs = remaining.remove(id);
        if (secs != null) data.set("players." + id, secs);
        BossBar bar = bars.remove(id);
        if (bar != null) p.hideBossBar(bar);
    }

    private void tick() {
        Iterator<Map.Entry<UUID, Integer>> it = remaining.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p == null) { it.remove(); continue; }

            int secs = entry.getValue();
            if (secs <= 0) continue;

            secs--;
            entry.setValue(secs);
            BossBar bar = bars.get(entry.getKey());

            if (secs <= 0) {
                if (bar != null) p.hideBossBar(bar);
                bars.remove(entry.getKey());
                data.set("players." + entry.getKey(), 0);
                p.sendMessage(Component.text("Your PvP grace time is over. PvP is now ON!",
                        NamedTextColor.RED));
            } else if (bar != null) {
                bar.name(barTitle(secs));
                bar.progress(progress(secs));
                // Colour changes as time runs out
                if (secs <= 60) bar.color(BossBar.Color.RED);
                else if (secs <= 600) bar.color(BossBar.Color.YELLOW);
            }
        }
    }

    private boolean hasGrace(Player p) {
        return remaining.getOrDefault(p.getUniqueId(), 0) > 0;
    }

    private void setGrace(Player p, int seconds) {
        UUID id = p.getUniqueId();
        BossBar old = bars.remove(id);
        if (old != null) p.hideBossBar(old);
        remaining.put(id, Math.max(0, seconds));
        data.set("players." + id, Math.max(0, seconds));
        if (seconds > 0) {
            BossBar bar = BossBar.bossBar(barTitle(seconds), progress(seconds),
                    BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
            bars.put(id, bar);
            p.showBossBar(bar);
        }
    }

    private Component barTitle(int secs) {
        return Component.text("PvP Grace: ", NamedTextColor.GREEN)
                .append(Component.text(format(secs), NamedTextColor.WHITE));
    }

    private float progress(int secs) {
        return Math.max(0f, Math.min(1f, secs / (float) totalSeconds));
    }

    private String format(int secs) {
        int h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }

    // ---------- events ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        load(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        unload(e.getPlayer());
        saveData();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;

        Player attacker = null;
        if (e.getDamager() instanceof Player p) {
            attacker = p;
        } else if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            attacker = p; // arrows, tridents, snowballs, etc.
        }
        if (attacker == null || attacker.equals(victim)) return;

        if (hasGrace(victim)) {
            e.setCancelled(true);
            attacker.sendMessage(Component.text(victim.getName()
                    + " is still under PvP grace time.", NamedTextColor.RED));
        } else if (blockAttacker && hasGrace(attacker)) {
            e.setCancelled(true);
            attacker.sendMessage(Component.text(
                    "You can't attack players during your grace time. Use /grace skip to turn PvP on.",
                    NamedTextColor.RED));
        }
    }

    // ---------- commands ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Players only.");
                return true;
            }
            int secs = remaining.getOrDefault(p.getUniqueId(), 0);
            p.sendMessage(secs > 0
                    ? Component.text("Grace time left: " + format(secs), NamedTextColor.GREEN)
                    : Component.text("You have no grace time. PvP is ON.", NamedTextColor.RED));
            return true;
        }

        if (args[0].equalsIgnoreCase("skip") && sender instanceof Player p) {
            if (!hasGrace(p)) {
                p.sendMessage(Component.text("You have no grace time left.", NamedTextColor.RED));
            } else {
                setGrace(p, 0);
                p.sendMessage(Component.text("Grace time skipped. PvP is now ON for you!",
                        NamedTextColor.YELLOW));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("set")) {
            if (!sender.hasPermission("grace.admin")) {
                sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage(Component.text("Usage: /grace set <player> <minutes>", NamedTextColor.RED));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not online.", NamedTextColor.RED));
                return true;
            }
            try {
                int minutes = Integer.parseInt(args[2]);
                setGrace(target, minutes * 60);
                sender.sendMessage(Component.text("Set " + target.getName() + "'s grace time to "
                        + minutes + " minute(s).", NamedTextColor.GREEN));
            } catch (NumberFormatException ex) {
                sender.sendMessage(Component.text("Minutes must be a number.", NamedTextColor.RED));
            }
            return true;
        }

        sender.sendMessage(Component.text("Usage: /grace [skip|set <player> <minutes>]", NamedTextColor.RED));
        return true;
    }
}
