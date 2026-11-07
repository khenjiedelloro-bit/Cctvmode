package com.example.cctvmode;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class CctvModePlugin extends JavaPlugin implements Listener {

    /** Vanilla command permissions granted ONLY while in CCTV mode. */
    private static final List<String> TEMP_PERMS = List.of(
            "minecraft.command.teleport",
            "minecraft.command.ban-ip",
            "minecraft.command.pardon",
            "minecraft.command.pardon-ip",
            "minecraft.command.banlist",
            "minecraft.command.kick"
    );

    private record Session(Location origin, PermissionAttachment attachment) {}

    private final Map<UUID, Session> active = new HashMap<>();
    private final Set<UUID> muted = new HashSet<>();
    private File mutesFile;
    private NamespacedKey flagKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        flagKey = new NamespacedKey(this, "in_cctv");
        mutesFile = new File(getDataFolder(), "mutes.yml");
        loadMutes();
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(active.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) exitCctv(p);
        }
        saveMutes();
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase()) {
            case "cctvmode" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Only players can use this.");
                    return true;
                }
                if (active.containsKey(p.getUniqueId())) {
                    exitCctv(p);
                    msg(p, "CCTV mode OFF. You are a normal player again.", NamedTextColor.YELLOW);
                } else {
                    enterCctv(p);
                    msg(p, "CCTV mode ON. Use /tp, /ban, /kick, /mute, /unmute, /warn.", NamedTextColor.GREEN);
                }
            }
            case "ban" -> {
                // Outside CCTV mode (console, normal ops) fall back to the vanilla /ban.
                if (sender instanceof Player p && active.containsKey(p.getUniqueId())) {
                    handleBan(p, args);
                } else {
                    Bukkit.dispatchCommand(sender, "minecraft:ban " + String.join(" ", args));
                }
            }
            case "mute", "unmute", "warn" -> {
                if (!(sender instanceof Player p) || !active.containsKey(p.getUniqueId())) {
                    sender.sendMessage(Component.text("You must be in /cctvmode to use this.", NamedTextColor.RED));
                    return true;
                }
                handleModCommand(p, cmd.getName().toLowerCase(), args);
            }
        }
        return true;
    }

    private static final java.util.regex.Pattern DURATION = java.util.regex.Pattern.compile("^(\\d{1,6})([smhdw])$");

    /** /ban <player> [reason...] [duration]  e.g. /ban Steve killaura 7d  (no duration = permanent) */
    private void handleBan(Player staff, String[] args) {
        if (args.length < 1) {
            msg(staff, "Usage: /ban <player> [reason] [duration e.g. 30m, 12h, 7d, 2w, perm]", NamedTextColor.RED);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            msg(staff, "Player not found (must be online).", NamedTextColor.RED);
            return;
        }
        if (target.equals(staff)) {
            msg(staff, "You can't ban yourself.", NamedTextColor.RED);
            return;
        }

        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        Date expires = null;
        String durationText = "Permanent";

        if (rest.length > 0) {
            String last = rest[rest.length - 1].toLowerCase();
            if (last.equals("perm") || last.equals("permanent")) {
                rest = Arrays.copyOf(rest, rest.length - 1);
            } else {
                java.util.regex.Matcher m = DURATION.matcher(last);
                if (m.matches()) {
                    long amount = Long.parseLong(m.group(1));
                    char unit = m.group(2).charAt(0);
                    long millis = amount * switch (unit) {
                        case 's' -> 1_000L;
                        case 'm' -> 60_000L;
                        case 'h' -> 3_600_000L;
                        case 'd' -> 86_400_000L;
                        default -> 604_800_000L; // w
                    };
                    expires = new Date(System.currentTimeMillis() + millis);
                    String name = switch (unit) {
                        case 's' -> "second";
                        case 'm' -> "minute";
                        case 'h' -> "hour";
                        case 'd' -> "day";
                        default -> "week";
                    };
                    durationText = amount + " " + name + (amount == 1 ? "" : "s");
                    rest = Arrays.copyOf(rest, rest.length - 1);
                }
            }
        }
        String reason = rest.length > 0 ? String.join(" ", rest) : "No reason given";

        // Public announcement
        Bukkit.broadcast(
                Component.text(target.getName() + " got banned by " + staff.getName(), NamedTextColor.RED)
                        .appendNewline().append(Component.text("Reason: " + reason, NamedTextColor.GRAY))
                        .appendNewline().append(Component.text("Duration: " + durationText, NamedTextColor.GRAY)));

        target.ban(reason, expires, staff.getName());
        getLogger().info(staff.getName() + " banned " + target.getName() + " (" + reason + ", " + durationText + ")");
    }

    private void handleModCommand(Player staff, String type, String[] args) {
        if (args.length < 1) {
            msg(staff, "Usage: /" + type + " <player>" + (type.equals("unmute") ? "" : " [reason]"), NamedTextColor.RED);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            msg(staff, "Player not found (must be online).", NamedTextColor.RED);
            return;
        }
        String reason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "No reason given";

        switch (type) {
            case "mute" -> {
                muted.add(target.getUniqueId());
                saveMutes();
                msg(target, "You have been muted. Reason: " + reason, NamedTextColor.RED);
                msg(staff, "Muted " + target.getName() + ".", NamedTextColor.GREEN);
            }
            case "unmute" -> {
                muted.remove(target.getUniqueId());
                saveMutes();
                msg(target, "You have been unmuted.", NamedTextColor.GREEN);
                msg(staff, "Unmuted " + target.getName() + ".", NamedTextColor.GREEN);
            }
            case "warn" -> {
                msg(target, "WARNING from staff: " + reason, NamedTextColor.RED);
                msg(staff, "Warned " + target.getName() + ".", NamedTextColor.GREEN);
            }
        }
        getLogger().info(staff.getName() + " used /" + type + " on " + target.getName() + " (" + reason + ")");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1 && !cmd.getName().equalsIgnoreCase("cctvmode")) {
            String start = args[0].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(start))
                    .toList();
        }
        return List.of();
    }

    // ---------------------------------------------------------------- cctv mode

    private void enterCctv(Player p) {
        PermissionAttachment att = p.addAttachment(this);
        TEMP_PERMS.forEach(perm -> att.setPermission(perm, true));
        active.put(p.getUniqueId(), new Session(p.getLocation(), att));
        p.getPersistentDataContainer().set(flagKey, PersistentDataType.BYTE, (byte) 1);
        p.setGameMode(GameMode.SPECTATOR);
        p.updateCommands();
    }

    private void exitCctv(Player p) {
        Session s = active.remove(p.getUniqueId());
        if (s != null) {
            p.removeAttachment(s.attachment());
            p.setGameMode(GameMode.SURVIVAL);
            if (getConfig().getBoolean("return-to-original-location", true)) {
                p.teleport(s.origin());
            }
        }
        p.getPersistentDataContainer().remove(flagKey);
        p.updateCommands();
    }

    // ---------------------------------------------------------------- events

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        exitCctv(e.getPlayer());
    }

    /** If the server crashed while someone was in CCTV mode, reset them on join. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!active.containsKey(p.getUniqueId())
                && p.getPersistentDataContainer().has(flagKey, PersistentDataType.BYTE)) {
            p.setGameMode(GameMode.SURVIVAL);
            p.getPersistentDataContainer().remove(flagKey);
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        if (muted.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            msg(e.getPlayer(), "You are muted.", NamedTextColor.RED);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void msg(Player p, String text, NamedTextColor color) {
        p.sendMessage(Component.text(text, color));
    }

    private void loadMutes() {
        if (!mutesFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(mutesFile);
        for (String s : yml.getStringList("muted")) {
            try { muted.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
        }
    }

    private void saveMutes() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("muted", muted.stream().map(UUID::toString).toList());
        try {
            getDataFolder().mkdirs();
            yml.save(mutesFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save mutes: " + ex.getMessage());
        }
    }
}
