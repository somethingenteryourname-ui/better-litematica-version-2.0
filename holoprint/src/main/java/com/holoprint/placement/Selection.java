package com.holoprint.placement;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.holoprint.config.HoloConfig;
import com.holoprint.online.OnlineLibrary;
import com.holoprint.schematic.Schematic;
import com.holoprint.schematic.SchematicWriter;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** The box you mark in the world to save your own build as a schematic. */
public final class Selection {
	private Selection() {}

	public static BlockPos corner1, corner2;
	public static final long MAX_VOLUME = 4L * 1024 * 1024;

	public static boolean complete() {
		return corner1 != null && corner2 != null;
	}

	public static int minX() { return Math.min(corner1.getX(), corner2.getX()); }
	public static int minY() { return Math.min(corner1.getY(), corner2.getY()); }
	public static int minZ() { return Math.min(corner1.getZ(), corner2.getZ()); }
	public static int sizeX() { return Math.abs(corner1.getX() - corner2.getX()) + 1; }
	public static int sizeY() { return Math.abs(corner1.getY() - corner2.getY()) + 1; }
	public static int sizeZ() { return Math.abs(corner1.getZ() - corner2.getZ()) + 1; }

	public static long volume() {
		return complete() ? (long) sizeX() * sizeY() * sizeZ() : 0;
	}

	public record ExportResult(Schematic schematic, int unloaded) {}

	/**
	 * Copies the blocks inside the selection from the world you can see.
	 * Must run on the game thread. Chests' contents and entities aren't saved.
	 */
	public static ExportResult capture(String name, String author) {
		Level level = Minecraft.getInstance().level;
		if (level == null || !complete()) throw new IllegalStateException("Mark both corners first");
		if (volume() > MAX_VOLUME) throw new IllegalStateException("Selection is too big (max " + MAX_VOLUME + " blocks)");
		int sx = sizeX(), sy = sizeY(), sz = sizeZ();
		int ox = minX(), oy = minY(), oz = minZ();
		Map<BlockState, Integer> ids = new HashMap<>();
		List<String> palette = new ArrayList<>();
		palette.add("minecraft:air");
		int[] cells = new int[sx * sy * sz];
		int unloaded = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = 0; y < sy; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					pos.set(ox + x, oy + y, oz + z);
					if (!level.hasChunkAt(pos)) {
						unloaded++;
						continue;
					}
					BlockState st = level.getBlockState(pos);
					if (st.isAir()) continue;
					Integer id = ids.get(st);
					if (id == null) {
						palette.add(stateToString(st));
						id = palette.size() - 1;
						ids.put(st, id);
					}
					cells[(y * sz + z) * sx + x] = id;
				}
			}
		}
		String clean = name == null || name.isBlank() ? "My build" : name.trim();
		Path file = HoloConfig.schematicsDir().resolve("HoloPrint Saves").resolve(OnlineLibrary.safeFileName(clean + ".litematic"));
		Schematic s = new Schematic(clean, author, "Litematic", file, sx, sy, sz, palette.toArray(new String[0]), cells);
		return new ExportResult(s, unloaded);
	}

	/** Writes the captured build to disk (call off the game thread). Never overwrites an existing file. */
	public static Path write(Schematic s) throws java.io.IOException {
		Path target = s.file;
		int n = 2;
		String base = s.name;
		while (java.nio.file.Files.exists(target)) {
			target = target.resolveSibling(OnlineLibrary.safeFileName(base + " (" + n++ + ").litematic"));
		}
		SchematicWriter.writeLitematic(s, "Saved with HoloPrint", target);
		return target;
	}

	/**
	 * "minecraft:oak_stairs[facing=north,half=bottom]". Built from BlockState's
	 * toString(), whose "Block{id}[props]" format has been stable for years.
	 */
	public static String stateToString(BlockState st) {
		String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
		String txt = st.toString();
		int b = txt.indexOf('[');
		if (b < 0 || !txt.endsWith("]")) return id;
		return id + txt.substring(b);
	}
}
