package me.modguard;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ModGuard extends JavaPlugin implements Listener {

    private static class Session {
        final List<Map.Entry<String, String>> pending;
        final List<String> found = new ArrayList<>();
        final Location loc;
        final Vector3i pos;
        int index = 0;
        List<Map.Entry<String, String>> batch = List.of();
        volatile boolean awaiting = false;
        BukkitTask timeout;

        Session(List<Map.Entry<String, String>> pending, Location loc) {
            this.pending = pending;
            this.loc = loc;
            this.pos = new Vector3i(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        }
    }

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private PacketListenerAbstract packetListener;

    private String kickMessage;
    private int checkDelay;
    private boolean skipBedrock;
    private boolean brandEnabled;
    private List<String> blockedBrands;
    private boolean keybindEnabled;
    private boolean kickNoResponse;
    private int responseTimeout;
    private final Map<String, String> blockedKeybinds = new LinkedHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);

        packetListener = new PacketListenerAbstract(PacketListenerPriority.HIGH) {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                if (event.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;
                if (event.getUser() == null || event.getUser().getUUID() == null) return;
                Session s = sessions.get(event.getUser().getUUID());
                if (s == null || !s.awaiting) return;

                WrapperPlayClientUpdateSign w = new WrapperPlayClientUpdateSign(event);
                if (!w.getBlockPosition().equals(s.pos)) return;

                event.setCancelled(true);
                String[] lines = w.getTextLines();
                UUID id = event.getUser().getUUID();
                getServer().getScheduler().runTask(ModGuard.this, () -> handleResponse(id, lines));
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(packetListener);
    }

    @Override
    public void onDisable() {
        getServer().getScheduler().cancelTasks(this);
        if (packetListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
        }
        sessions.clear();
    }

    private void loadSettings() {
        reloadConfig();
        var c = getConfig();
        kickMessage = c.getString("kick-message", "&cRestricted mods: {mods}");
        checkDelay = Math.max(1, c.getInt("check-delay-ticks", 15));
        skipBedrock = c.getBoolean("skip-bedrock", true);
        brandEnabled = c.getBoolean("brand-check.enabled", true);
        blockedBrands = new ArrayList<>();
        for (String b : c.getStringList("brand-check.blocked-brands")) blockedBrands.add(b.toLowerCase());
        keybindEnabled = c.getBoolean("keybind-check.enabled", true);
        kickNoResponse = c.getBoolean("keybind-check.kick-on-no-response", false);
        responseTimeout = Math.max(20, c.getInt("keybind-check.response-timeout-ticks", 80));
        blockedKeybinds.clear();
        var sec = c.getConfigurationSection("keybind-check.blocked-keybinds");
        if (sec != null) {
            for (String name : sec.getKeys(false)) {
                String key = sec.getString(name);
                if (key != null && !key.isBlank()) blockedKeybinds.put(name, key.trim());
            }
        }
    }

    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        getServer().getScheduler().runTaskLater(this, () -> runChecks(p), checkDelay);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        endSession(e.getPlayer().getUniqueId(), e.getPlayer());
    }

    private boolean exempt(Player p) {
        if (p.hasPermission("modguard.bypass")) return true;
        return skipBedrock && p.getUniqueId().getMostSignificantBits() == 0L;
    }

    private void runChecks(Player p) {
        if (!p.isOnline() || exempt(p)) return;

        // Layer 1: brand
        if (brandEnabled) {
            String brand = p.getClientBrandName();
            if (brand != null) {
                String lower = brand.toLowerCase();
                for (String bad : blockedBrands) {
                    if (lower.contains(bad)) {
                        kick(p, List.of(brand));
                        return;
                    }
                }
            }
        }

        // Layer 2: keybind detection
        if (keybindEnabled && !blockedKeybinds.isEmpty()) {
            startKeybindCheck(p);
        }
    }

    // ------------------------------------------------------------------
    // Keybind check
    // ------------------------------------------------------------------

    private void startKeybindCheck(Player p) {
        Location loc = p.getLocation();
        loc.setY(p.getWorld().getMinHeight());
        Session s = new Session(new ArrayList<>(blockedKeybinds.entrySet()), loc);
        sessions.put(p.getUniqueId(), s);
        sendBatch(p, s);
    }

    private void sendBatch(Player p, Session s) {
        int end = Math.min(s.index + 4, s.pending.size());
        s.batch = new ArrayList<>(s.pending.subList(s.index, end));

        BlockData data = Material.OAK_SIGN.createBlockData();
        Sign sign = (Sign) data.createBlockState();
        SignSide side = sign.getSide(Side.FRONT);
        for (int i = 0; i < 4; i++) {
            if (i < s.batch.size()) side.line(i, Component.keybind(s.batch.get(i).getValue()));
            else side.line(i, Component.empty());
        }

        p.sendBlockChange(s.loc, data);
        p.sendBlockUpdate(s.loc, sign);

        // open the editor, then close it so the client sends back the resolved text
        getServer().getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline() || sessions.get(p.getUniqueId()) != s) return;
            s.awaiting = true;
            PacketEvents.getAPI().getPlayerManager().sendPacket(p,
                    new WrapperPlayServerOpenSignEditor(s.pos, true));
            getServer().getScheduler().runTaskLater(this, () -> {
                if (!p.isOnline() || sessions.get(p.getUniqueId()) != s) return;
                PacketEvents.getAPI().getPlayerManager().sendPacket(p,
                        new WrapperPlayServerCloseWindow(0));
            }, 2L);
        }, 2L);

        s.timeout = getServer().getScheduler().runTaskLater(this, () -> {
            if (sessions.get(p.getUniqueId()) != s || !s.awaiting) return;
            endSession(p.getUniqueId(), p);
            if (kickNoResponse && p.isOnline()) kick(p, List.of("unverifiable client"));
        }, responseTimeout);
    }

    private void handleResponse(UUID id, String[] lines) {
        Session s = sessions.get(id);
        Player p = getServer().getPlayer(id);
        if (s == null || p == null || !s.awaiting) return;
        s.awaiting = false;
        if (s.timeout != null) s.timeout.cancel();

        for (int i = 0; i < s.batch.size() && i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            String key = s.batch.get(i).getValue();
            // Not installed -> client echoes the key id. Installed -> a key label.
            if (!line.isEmpty() && !line.equals(key)) {
                s.found.add(s.batch.get(i).getKey());
            }
        }

        s.index += s.batch.size();
        if (s.index < s.pending.size()) {
            sendBatch(p, s);
            return;
        }

        List<String> found = new ArrayList<>(s.found);
        endSession(id, p);
        if (!found.isEmpty()) kick(p, found);
    }

    /** Remove the session and restore the real block at the fake sign location. */
    private void endSession(UUID id, Player p) {
        Session s = sessions.remove(id);
        if (s == null) return;
        if (s.timeout != null) s.timeout.cancel();
        if (p != null && p.isOnline()) {
            p.sendBlockChange(s.loc, s.loc.getBlock().getBlockData());
        }
    }

    private void kick(Player p, List<String> mods) {
        String msg = kickMessage.replace("{mods}", String.join(", ", mods));
        Component c = LegacyComponentSerializer.legacyAmpersand().deserialize(msg);
        getLogger().info(p.getName() + " kicked for restricted mods: " + String.join(", ", mods));
        p.kick(c);
    }

    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            sender.sendMessage("ModGuard config reloaded.");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("check")) {
            Player t = getServer().getPlayerExact(args[1]);
            if (t == null) {
                sender.sendMessage("Player not found.");
                return true;
            }
            sender.sendMessage("Brand of " + t.getName() + ": " + t.getClientBrandName());
            runChecks(t);
            sender.sendMessage("Check started.");
            return true;
        }
        sender.sendMessage("Usage: /modguard reload | /modguard check <player>");
        return true;
    }
}
