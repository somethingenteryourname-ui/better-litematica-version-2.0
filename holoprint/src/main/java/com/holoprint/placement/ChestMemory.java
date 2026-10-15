package com.holoprint.placement;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

/**
 * Remembers what was inside every chest / barrel / shulker you opened, so the
 * Materials page can say where to grab things. Purely client-side: it only
 * reads what the game already shows you when you open a container.
 */
public final class ChestMemory {
	private ChestMemory() {}

	private static final Gson GSON = new GsonBuilder().create();
	/** world key -> chest position (as long) -> item id -> count */
	private static Map<String, Map<Long, Map<String, Integer>>> data;

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("holoprint").resolve("chests.json");
	}

	private static Map<Long, Map<String, Integer>> world() {
		if (data == null) {
			data = new HashMap<>();
			try {
				if (Files.exists(file())) {
					Map<String, Map<Long, Map<String, Integer>>> m = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8),
							new TypeToken<Map<String, Map<Long, Map<String, Integer>>>>() {}.getType());
					if (m != null) data.putAll(m);
				}
			} catch (Exception e) {
				System.err.println("[HoloPrint] Couldn't read chests.json: " + e.getMessage());
			}
		}
		String key = PlacementManager.worldKey();
		if (key == null) return new HashMap<>();
		return data.computeIfAbsent(key, k -> new HashMap<>());
	}

	public static void save() {
		if (data == null) return;
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(data), StandardCharsets.UTF_8);
		} catch (Exception e) {
			System.err.println("[HoloPrint] Couldn't save chests.json: " + e.getMessage());
		}
	}

	/** Reads the container part of an open menu (not your own inventory). */
	public static boolean snapshot(BlockPos pos, AbstractContainerMenu menu, Inventory playerInv) {
		Map<String, Integer> items = new LinkedHashMap<>();
		boolean any = false;
		for (Slot slot : menu.slots) {
			if (slot.container == playerInv) continue;
			any = true;
			ItemStack st = slot.getItem();
			if (st.isEmpty()) continue;
			items.merge(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), st.getCount(), Integer::sum);
		}
		if (!any) return false;
		Map<String, Integer> old = world().put(pos.asLong(), items);
		return old == null || !old.equals(items);
	}

	public static void forget(BlockPos pos) {
		if (world().remove(pos.asLong()) != null) save();
	}

	public static int chestCount() {
		return world().size();
	}

	public static void clearWorld() {
		world().clear();
		save();
	}

	public record Stop(BlockPos pos, double distance, List<Map.Entry<Item, Integer>> grab) {
		public int total() {
			int t = 0;
			for (Map.Entry<Item, Integer> e : grab) t += e.getValue();
			return t;
		}
	}

	/**
	 * Plans a route: nearest chests first, taking from each only what you still need.
	 * {@code needed} = item -> how many more you need (after what you carry).
	 */
	public static List<Stop> plan(Map<Item, Integer> needed, BlockPos from) {
		Map<Item, Integer> left = new HashMap<>(needed);
		List<Map.Entry<Long, Map<String, Integer>>> chests = new ArrayList<>(world().entrySet());
		chests.sort((a, b) -> Double.compare(dist(BlockPos.of(a.getKey()), from), dist(BlockPos.of(b.getKey()), from)));
		List<Stop> stops = new ArrayList<>();
		for (Map.Entry<Long, Map<String, Integer>> chest : chests) {
			List<Map.Entry<Item, Integer>> grab = new ArrayList<>();
			for (Map.Entry<String, Integer> it : chest.getValue().entrySet()) {
				Item item = itemById(it.getKey());
				if (item == null) continue;
				int need = left.getOrDefault(item, 0);
				if (need <= 0) continue;
				int take = Math.min(need, it.getValue());
				left.put(item, need - take);
				grab.add(Map.entry(item, take));
			}
			if (!grab.isEmpty()) {
				BlockPos p = BlockPos.of(chest.getKey());
				stops.add(new Stop(p, Math.sqrt(dist(p, from)), grab));
			}
		}
		return stops;
	}

	/** Where (and how many) of one item you've seen in chests, nearest first. */
	public static List<Map.Entry<BlockPos, Integer>> whereIs(Item item, BlockPos from) {
		String id = BuiltInRegistries.ITEM.getKey(item).toString();
		List<Map.Entry<BlockPos, Integer>> out = new ArrayList<>();
		for (Map.Entry<Long, Map<String, Integer>> chest : world().entrySet()) {
			Integer c = chest.getValue().get(id);
			if (c != null && c > 0) out.add(Map.entry(BlockPos.of(chest.getKey()), c));
		}
		out.sort((a, b) -> Double.compare(dist(a.getKey(), from), dist(b.getKey(), from)));
		return out;
	}

	private static double dist(BlockPos a, BlockPos b) {
		double dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
		return dx * dx + dy * dy + dz * dz;
	}

	private static final Map<String, Item> ITEM_CACHE = new HashMap<>();

	private static Item itemById(String id) {
		return ITEM_CACHE.computeIfAbsent(id, k -> {
			try {
				Item it = BuiltInRegistries.ITEM.getValue(Identifier.parse(k));
				return it == Items.AIR ? null : it;
			} catch (RuntimeException e) {
				return null;
			}
		});
	}

	/** Called each tick: drops remembered chests that you can see are gone. */
	public static void cleanupNear(Minecraft mc) {
		if (mc.level == null || mc.player == null) return;
		List<Long> gone = new ArrayList<>();
		for (Long l : world().keySet()) {
			BlockPos p = BlockPos.of(l);
			if (!mc.level.hasChunkAt(p)) continue;
			if (dist(p, mc.player.blockPosition()) > 32 * 32) continue;
			if (mc.level.getBlockEntity(p) == null) gone.add(l);
		}
		if (!gone.isEmpty()) {
			for (Long l : gone) world().remove(l);
			save();
		}
	}

	/** Used by the material list to show the block's own item. */
	public static Item itemFor(Block block) {
		return block.asItem();
	}
}
