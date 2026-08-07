package me.gilles.megahoppers;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Redstone-hopper chunkloader.
 *
 * Build two hoppers facing INTO each other (horizontally adjacent, pointing at one another) and
 * drop redstone dust into either hopper. That pair force-loads exactly one chunk (via a plugin
 * chunk ticket). Remove the redstone, break a hopper, or turn them so they no longer face each
 * other and the chunk is released.
 */
public final class ChunkLoaderManager {

    private final MegaHoppersPlugin plugin;
    private final Map<String, Loader> loaders = new HashMap<>();

    private boolean enabled;
    private boolean requireRedstone;
    private int validateInterval;
    private BukkitTask validateTask;

    public ChunkLoaderManager(MegaHoppersPlugin plugin) {
        this.plugin = plugin;
        readConfig();
    }

    private void readConfig() {
        var c = plugin.getConfig();
        this.enabled = c.getBoolean("chunkloader.enabled", true);
        this.requireRedstone = c.getBoolean("chunkloader.require-redstone", true);
        this.validateInterval = Math.max(20, c.getInt("chunkloader.validate-interval-ticks", 40));
    }

    public void start() {
        if (!enabled) return;
        long p = validateInterval;
        validateTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::validateAll, p, p);
        // Re-validate any chunk tickets Paper restored from a previous run, once worlds are loaded.
        plugin.getServer().getScheduler().runTaskLater(plugin, this::restore, 60L);
    }

    public void reload() {
        boolean wasEnabled = enabled;
        readConfig();
        if (!enabled && wasEnabled) clearAll();
        if (validateTask != null) {
            validateTask.cancel();
            validateTask = null;
        }
        start();
    }

    public void shutdown() {
        if (validateTask != null) {
            validateTask.cancel();
            validateTask = null;
        }
        // Leave chunk tickets in place; Paper persists them and restore() re-validates on next enable.
    }

    public int activeCount() {
        return loaders.size();
    }

    public List<String> describeActive() {
        List<String> out = new ArrayList<>();
        for (Loader l : loaders.values()) {
            World w = Bukkit.getWorld(l.world);
            out.add((w == null ? "?" : w.getName()) + " chunk [" + l.cx + ", " + l.cz + "]");
        }
        return out;
    }

    /** Evaluate a hopper on the next tick (safe to call from inside events). */
    public void scheduleEvaluate(Block block) {
        if (!enabled) return;
        Location loc = block.getLocation();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (loc.getWorld() != null) evaluate(loc.getBlock());
        });
    }

    /** Core rule: is this hopper part of a valid facing pair with redstone? Activate or release. */
    public void evaluate(Block block) {
        if (!enabled) return;
        Location loc = block.getLocation();
        if (block.getType() != Material.HOPPER) {
            deactivateInvolving(loc);
            return;
        }

        BlockFace facing = facingOf(block);
        if (facing == null || facing == BlockFace.DOWN) { // a downward hopper cannot face another one
            deactivateInvolving(loc);
            return;
        }

        Block partner = block.getRelative(facing);
        if (partner.getType() != Material.HOPPER) {
            deactivateInvolving(loc);
            return;
        }

        BlockFace pf = facingOf(partner);
        if (pf == null || !partner.getRelative(pf).equals(block)) { // partner must face back
            deactivateInvolving(loc);
            return;
        }

        boolean redstone = !requireRedstone || hasRedstone(block) || hasRedstone(partner);
        String id = pairId(block.getLocation(), partner.getLocation());
        if (redstone) activate(id, block.getLocation(), partner.getLocation());
        else deactivate(id);
    }

    private void activate(String id, Location a, Location b) {
        if (loaders.containsKey(id)) return; // already loaded
        Location primary = min(a, b);
        World w = primary.getWorld();
        if (w == null) return;
        int cx = primary.getBlockX() >> 4;
        int cz = primary.getBlockZ() >> 4;
        w.addPluginChunkTicket(cx, cz, plugin);
        loaders.put(id, new Loader(w.getUID(), cx, cz, a.clone(), b.clone()));
        notifyNear(primary, "§aChunkloader on §7— chunk [" + cx + ", " + cz + "] stays loaded.");
    }

    private void deactivate(String id) {
        Loader l = loaders.remove(id);
        if (l == null) return;
        boolean shared = loaders.values().stream()
                .anyMatch(o -> o.world.equals(l.world) && o.cx == l.cx && o.cz == l.cz);
        World w = Bukkit.getWorld(l.world);
        if (!shared && w != null) {
            w.removePluginChunkTicket(l.cx, l.cz, plugin);
            notifyNear(new Location(w, (l.cx << 4) + 8, 70, (l.cz << 4) + 8),
                    "§7Chunkloader off — chunk [" + l.cx + ", " + l.cz + "] can unload.");
        }
    }

    public void deactivateInvolving(Location loc) {
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, Loader> e : loaders.entrySet()) {
            Loader l = e.getValue();
            if (sameBlock(l.a, loc) || sameBlock(l.b, loc)) ids.add(e.getKey());
        }
        for (String id : ids) deactivate(id);
    }

    private void validateAll() {
        for (String id : new ArrayList<>(loaders.keySet())) {
            Loader l = loaders.get(id);
            if (l == null) continue;
            World w = Bukkit.getWorld(l.world);
            if (w == null) continue;
            if (!validPair(w.getBlockAt(l.a), w.getBlockAt(l.b))) deactivate(id);
        }
    }

    /** Snapshot of the currently active chunkloaders, for the in-game map view. */
    public List<LoaderInfo> activeLoaders() {
        List<LoaderInfo> out = new ArrayList<>();
        for (Loader l : loaders.values()) {
            out.add(new LoaderInfo(Bukkit.getWorld(l.world), l.cx, l.cz, l.a.clone(), l.b.clone()));
        }
        return out;
    }

    /** Public, immutable view of one chunkloader. */
    public static final class LoaderInfo {
        public final World world;
        public final int cx, cz;
        public final Location a, b;

        public LoaderInfo(World world, int cx, int cz, Location a, Location b) {
            this.world = world;
            this.cx = cx;
            this.cz = cz;
            this.a = a;
            this.b = b;
        }
    }

    private boolean validPair(Block a, Block b) {
        if (a.getType() != Material.HOPPER || b.getType() != Material.HOPPER) return false;
        BlockFace fa = facingOf(a), fb = facingOf(b);
        if (fa == null || fb == null) return false;
        if (!a.getRelative(fa).equals(b) || !b.getRelative(fb).equals(a)) return false;
        return !requireRedstone || hasRedstone(a) || hasRedstone(b);
    }

    /** After a restart, Paper re-adds our chunk tickets; rebuild records from the loaded chunks. */
    private void restore() {
        for (World w : Bukkit.getWorlds()) {
            Collection<Chunk> ours = w.getPluginChunkTickets().getOrDefault(plugin, Collections.emptyList());
            for (Chunk c : new ArrayList<>(ours)) {
                for (BlockState bs : c.getTileEntities(false)) {
                    if (bs instanceof org.bukkit.block.Hopper) evaluate(bs.getBlock());
                }
                if (!hasRecordForChunk(w.getUID(), c.getX(), c.getZ())) {
                    w.removePluginChunkTicket(c.getX(), c.getZ(), plugin); // stale ticket, drop it
                }
            }
        }
    }

    private void clearAll() {
        for (Loader l : new ArrayList<>(loaders.values())) {
            World w = Bukkit.getWorld(l.world);
            if (w != null) w.removePluginChunkTicket(l.cx, l.cz, plugin);
        }
        loaders.clear();
    }

    private boolean hasRecordForChunk(UUID world, int cx, int cz) {
        return loaders.values().stream().anyMatch(l -> l.world.equals(world) && l.cx == cx && l.cz == cz);
    }

    // ---- helpers ----

    private BlockFace facingOf(Block b) {
        BlockData bd = b.getBlockData();
        if (bd instanceof org.bukkit.block.data.type.Hopper h) return h.getFacing();
        return null;
    }

    private boolean hasRedstone(Block b) {
        BlockState st = b.getState(false);
        if (st instanceof org.bukkit.block.Hopper hop) return hop.getInventory().contains(Material.REDSTONE);
        return false;
    }

    private void notifyNear(Location center, String msg) {
        World w = center.getWorld();
        if (w == null) return;
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(center) <= 256) p.sendMessage(msg);
        }
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getWorld() != null && a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    private static Location min(Location a, Location b) {
        if (a.getBlockX() != b.getBlockX()) return a.getBlockX() < b.getBlockX() ? a : b;
        if (a.getBlockY() != b.getBlockY()) return a.getBlockY() < b.getBlockY() ? a : b;
        if (a.getBlockZ() != b.getBlockZ()) return a.getBlockZ() < b.getBlockZ() ? a : b;
        return a;
    }

    private static String pairId(Location a, Location b) {
        Location lo = min(a, b);
        Location hi = (lo == a) ? b : a;
        UUID world = a.getWorld() != null ? a.getWorld().getUID() : new UUID(0, 0);
        return world + "|" + lo.getBlockX() + "," + lo.getBlockY() + "," + lo.getBlockZ()
                + "|" + hi.getBlockX() + "," + hi.getBlockY() + "," + hi.getBlockZ();
    }

    private static final class Loader {
        final UUID world;
        final int cx, cz;
        final Location a, b;

        Loader(UUID world, int cx, int cz, Location a, Location b) {
            this.world = world;
            this.cx = cx;
            this.cz = cz;
            this.a = a;
            this.b = b;
        }
    }
}
