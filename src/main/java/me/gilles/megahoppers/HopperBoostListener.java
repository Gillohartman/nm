package me.gilles.megahoppers;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;

/**
 * Makes hoppers move {@code multiplier} items per transfer cycle instead of 1.
 *
 * Approach (dupe- and void-safe): we do NOT cancel the vanilla transfer. Vanilla still moves its
 * single item on its normal ~8-tick schedule, keeping all of its own accounting and slot rules
 * correct. We simply piggyback and move up to {@code multiplier - 1} extra items of the same type
 * alongside it, leaving one slot of space free so vanilla's own move still succeeds. So a x16
 * multiplier means 16 items per vanilla cycle = 16x throughput, and because vanilla owns its item
 * there is no way for our code to duplicate or destroy items.
 *
 * Furnace correctness for the extra items mirrors vanilla:
 *   - hopper below a furnace only pulls from the RESULT slot (finished product);
 *   - hopper on TOP inserts into the INPUT slot, never the output;
 *   - hopper on the SIDE inserts into the FUEL slot (fuel items only).
 */
public final class HopperBoostListener implements Listener {

    private static final int INPUT = 0, FUEL = 1, RESULT = 2;
    private static final int[] ALL = new int[0]; // sentinel: "every slot"

    private final MegaHoppersPlugin plugin;
    private final Set<String> disabledWorlds = new HashSet<>();

    private boolean enabled;
    private int multiplier;
    private boolean enforceFurnace;
    private boolean warned;

    public HopperBoostListener(MegaHoppersPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var c = plugin.getConfig();
        this.enabled = c.getBoolean("hoppers.enabled", true);
        this.multiplier = Math.max(1, c.getInt("hoppers.multiplier", 16));
        this.enforceFurnace = c.getBoolean("hoppers.enforce-furnace-slots", true);
        disabledWorlds.clear();
        for (String w : c.getStringList("hoppers.disabled-worlds")) disabledWorlds.add(w.toLowerCase());
    }

    public int getMultiplier() {
        return multiplier;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        if (!enabled) return;
        int extra = multiplier - 1;
        if (extra <= 0) return; // multiplier 1 == vanilla, nothing to add
        if (event.getInitiator().getType() != InventoryType.HOPPER) return;

        if (!disabledWorlds.isEmpty()) {
            World world = worldOf(event);
            if (world != null && disabledWorlds.contains(world.getName().toLowerCase())) return;
        }

        try {
            int moved = moveExtra(event, extra);
            if (moved > 0 && event.getItem().getType() == Material.REDSTONE
                    && event.getDestination().getHolder() instanceof org.bukkit.block.Hopper destHop) {
                plugin.chunkLoaders().scheduleEvaluate(destHop.getBlock());
            }
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("Hopper boost hit an error, extra items skipped this cycle: " + t);
            }
        }
    }

    private World worldOf(InventoryMoveItemEvent event) {
        Location l = event.getDestination().getLocation();
        if (l == null) l = event.getSource().getLocation();
        return l == null ? null : l.getWorld();
    }

    /** Move up to {@code extra} additional items of the moved type, leaving 1 slot free for vanilla. */
    private int moveExtra(InventoryMoveItemEvent event, int extra) {
        Inventory src = event.getSource();
        Inventory dst = event.getDestination();
        ItemStack template = event.getItem();
        if (template == null || template.getType().isAir()) return 0;

        int[] ss = sourceSlots(src);
        int[] ds = destSlots(src, dst, template);
        if (ds == null) return 0; // cannot legally place (e.g. non-fuel into a furnace fuel slot)

        int available = countIn(src, template, ss);
        if (available <= 0) return 0;
        int space = spaceIn(dst, template, ds) - 1; // reserve room so vanilla's own 1-item move succeeds
        if (space <= 0) return 0;

        int n = Math.min(extra, Math.min(available, space));
        if (n <= 0) return 0;

        int removed = removeIn(src, template, n, ss);
        int added = addIn(dst, template, removed, ds);
        if (added < removed) {
            addIn(src, template, removed - added, ss); // void-safe: return anything that didn't fit
        }
        return added;
    }

    /** Which source slots may be pulled from. Covers furnaces, smokers and blast furnaces alike. */
    private int[] sourceSlots(Inventory src) {
        if (enforceFurnace && src instanceof FurnaceInventory) return new int[]{RESULT}; // output only
        if (src instanceof BrewerInventory) return new int[]{0, 1, 2};                    // finished potions
        return ALL;
    }

    /** Which destination slots may be pushed into, or null if the item cannot be placed at all. */
    private int[] destSlots(Inventory src, Inventory dst, ItemStack template) {
        if (enforceFurnace && dst instanceof FurnaceInventory furnace) {
            BlockFace facing = BlockFace.DOWN;
            if (src.getHolder() instanceof org.bukkit.block.Hopper hop) facing = hopperFacing(hop);
            if (facing == BlockFace.DOWN) return new int[]{INPUT};   // hopper on top -> smelting slot, never output
            if (furnace.isFuel(template)) return new int[]{FUEL};    // hopper on side -> fuel slot (fuel only)
            return null;                                             // side hopper, non-fuel item -> nothing
        }
        if (dst instanceof BrewerInventory) {
            Material m = template.getType();
            if (m == Material.BLAZE_POWDER) return new int[]{4};
            if (isPotion(m)) return new int[]{0, 1, 2};
            return new int[]{3};
        }
        return ALL;
    }

    private static boolean isPotion(Material m) {
        return m == Material.POTION || m == Material.SPLASH_POTION || m == Material.LINGERING_POTION;
    }

    private BlockFace hopperFacing(org.bukkit.block.Hopper hopperState) {
        BlockData bd = hopperState.getBlock().getBlockData();
        if (bd instanceof org.bukkit.block.data.type.Hopper h) return h.getFacing();
        return BlockFace.DOWN;
    }

    // ---- slot-scoped inventory helpers (scope: ALL sentinel = whole inventory) ----

    private int[] scope(Inventory inv, int[] slots) {
        if (slots.length > 0) return slots;
        int size = inv.getSize();
        int[] all = new int[size];
        for (int i = 0; i < size; i++) all[i] = i;
        return all;
    }

    private int countIn(Inventory inv, ItemStack template, int[] slots) {
        int count = 0, size = inv.getSize();
        for (int i : scope(inv, slots)) {
            if (i >= size) continue;
            ItemStack it = inv.getItem(i);
            if (it != null && it.isSimilar(template)) count += it.getAmount();
        }
        return count;
    }

    private int spaceIn(Inventory inv, ItemStack template, int[] slots) {
        int max = template.getMaxStackSize(), space = 0, size = inv.getSize();
        for (int i : scope(inv, slots)) {
            if (i >= size) continue;
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) space += max;
            else if (it.isSimilar(template)) space += Math.max(0, max - it.getAmount());
        }
        return space;
    }

    private int removeIn(Inventory inv, ItemStack template, int n, int[] slots) {
        int remaining = n, size = inv.getSize();
        for (int i : scope(inv, slots)) {
            if (remaining <= 0) break;
            if (i >= size) continue;
            ItemStack it = inv.getItem(i);
            if (it != null && it.isSimilar(template)) {
                int take = Math.min(remaining, it.getAmount());
                int left = it.getAmount() - take;
                if (left <= 0) inv.setItem(i, null);
                else {
                    it.setAmount(left);
                    inv.setItem(i, it);
                }
                remaining -= take;
            }
        }
        return n - remaining;
    }

    private int addIn(Inventory inv, ItemStack template, int n, int[] slots) {
        int remaining = n, max = template.getMaxStackSize(), size = inv.getSize();
        int[] sc = scope(inv, slots);
        for (int i : sc) { // stack onto existing matching items first
            if (remaining <= 0) break;
            if (i >= size) continue;
            ItemStack it = inv.getItem(i);
            if (it != null && it.isSimilar(template)) {
                int add = Math.min(remaining, max - it.getAmount());
                if (add > 0) {
                    it.setAmount(it.getAmount() + add);
                    inv.setItem(i, it);
                    remaining -= add;
                }
            }
        }
        for (int i : sc) { // then fill empty slots
            if (remaining <= 0) break;
            if (i >= size) continue;
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) {
                int add = Math.min(remaining, max);
                ItemStack put = template.clone();
                put.setAmount(add);
                inv.setItem(i, put);
                remaining -= add;
            }
        }
        return n - remaining;
    }
}
