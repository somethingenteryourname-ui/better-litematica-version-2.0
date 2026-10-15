package com.holoprint.schematic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Loads .litematic (Litematica), .schem / .schematic (Sponge v1-v3) and
 * .nbt (vanilla structure block) files into a {@link Schematic}.
 */
public final class SchematicLoader {
	private SchematicLoader() {}

	/** Hard safety cap so a huge or broken file can't freeze the game. */
	public static final long MAX_VOLUME = 64L * 1024 * 1024;

	public static boolean isSupported(Path p) {
		String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
		return n.endsWith(".litematic") || n.endsWith(".schem") || n.endsWith(".schematic") || n.endsWith(".nbt");
	}

	public static Schematic load(Path file) throws IOException {
		byte[] data = Files.readAllBytes(file);
		Map<String, Object> root = Nbt.read(data);
		String baseName = stripExt(file.getFileName().toString());

		if (Nbt.has(root, "Regions")) return loadLitematic(root, file, baseName);
		if (Nbt.has(root, "Schematic")) return loadSponge(Nbt.compound(root, "Schematic"), file, baseName);
		if (Nbt.has(root, "Palette") || Nbt.has(root, "Blocks") && Nbt.has(root, "Width")) {
			if (Nbt.has(root, "Palette") || Nbt.compound(root, "Blocks") != null) return loadSponge(root, file, baseName);
			throw new IOException("Old MCEdit .schematic files (pre-1.13) aren't supported yet");
		}
		if (Nbt.has(root, "size") && (Nbt.has(root, "palette") || Nbt.has(root, "palettes"))) {
			return loadVanilla(root, file, baseName);
		}
		throw new IOException("Unrecognised schematic format");
	}

	// ------------------------------------------------------------------ Litematica

	private static Schematic loadLitematic(Map<String, Object> root, Path file, String baseName) throws IOException {
		Map<String, Object> meta = Nbt.compound(root, "Metadata");
		String name = Nbt.string(meta, "Name", baseName);
		String author = Nbt.string(meta, "Author", "?");
		Map<String, Object> regions = Nbt.compound(root, "Regions");
		if (regions == null || regions.isEmpty()) throw new IOException("Litematic has no regions");

		record Region(int minX, int minY, int minZ, int sx, int sy, int sz, String[] palette, long[] states) {}
		List<Region> list = new ArrayList<>();
		int gMinX = Integer.MAX_VALUE, gMinY = Integer.MAX_VALUE, gMinZ = Integer.MAX_VALUE;
		int gMaxX = Integer.MIN_VALUE, gMaxY = Integer.MIN_VALUE, gMaxZ = Integer.MIN_VALUE;

		for (Object o : regions.values()) {
			if (!(o instanceof Map)) continue;
			@SuppressWarnings("unchecked") Map<String, Object> r = (Map<String, Object>) o;
			Map<String, Object> pos = Nbt.compound(r, "Position");
			Map<String, Object> size = Nbt.compound(r, "Size");
			int px = Nbt.integer(pos, "x", 0), py = Nbt.integer(pos, "y", 0), pz = Nbt.integer(pos, "z", 0);
			int sx = Nbt.integer(size, "x", 0), sy = Nbt.integer(size, "y", 0), sz = Nbt.integer(size, "z", 0);
			if (sx == 0 || sy == 0 || sz == 0) continue;
			int minX = sx < 0 ? px + sx + 1 : px;
			int minY = sy < 0 ? py + sy + 1 : py;
			int minZ = sz < 0 ? pz + sz + 1 : pz;
			sx = Math.abs(sx); sy = Math.abs(sy); sz = Math.abs(sz);

			List<Object> pal = Nbt.list(r, "BlockStatePalette");
			String[] palette = new String[pal.size()];
			for (int i = 0; i < pal.size(); i++) palette[i] = paletteEntryToString(pal.get(i));
			Object st = r.get("BlockStates");
			long[] states = st instanceof long[] l ? l : new long[0];

			list.add(new Region(minX, minY, minZ, sx, sy, sz, palette, states));
			gMinX = Math.min(gMinX, minX); gMinY = Math.min(gMinY, minY); gMinZ = Math.min(gMinZ, minZ);
			gMaxX = Math.max(gMaxX, minX + sx - 1); gMaxY = Math.max(gMaxY, minY + sy - 1); gMaxZ = Math.max(gMaxZ, minZ + sz - 1);
		}
		if (list.isEmpty()) throw new IOException("Litematic regions are empty");

		int SX = gMaxX - gMinX + 1, SY = gMaxY - gMinY + 1, SZ = gMaxZ - gMinZ + 1;
		checkVolume(SX, SY, SZ);
		PaletteBuilder pb = new PaletteBuilder();
		int[] cells = new int[SX * SY * SZ]; // 0 = air
		for (Region rg : list) {
			int[] remap = new int[rg.palette.length];
			for (int i = 0; i < remap.length; i++) remap[i] = pb.id(rg.palette[i]);
			int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, rg.palette.length - 1)));
			long mask = (1L << bits) - 1L;
			long volume = (long) rg.sx * rg.sy * rg.sz;
			if (rg.states.length * 64L < volume * bits) throw new IOException("Litematic block data is truncated");
			for (int y = 0; y < rg.sy; y++) {
				for (int z = 0; z < rg.sz; z++) {
					for (int x = 0; x < rg.sx; x++) {
						long idx = ((long) y * rg.sz + z) * rg.sx + x;
						int v = (int) readPacked(rg.states, idx, bits, mask);
						if (v < 0 || v >= remap.length) continue;
						int gid = remap[v];
						if (gid == 0) continue;
						int gx = rg.minX - gMinX + x, gy = rg.minY - gMinY + y, gz = rg.minZ - gMinZ + z;
						cells[(gy * SZ + gz) * SX + gx] = gid;
					}
				}
			}
		}
		return new Schematic(name, author, "Litematic", file, SX, SY, SZ, pb.toArray(), cells);
	}

	/** Litematica packs values tightly, so one value can span two longs. */
	static long readPacked(long[] arr, long index, int bits, long mask) {
		long startOffset = index * bits;
		int startArr = (int) (startOffset >> 6);
		int endArr = (int) (((index + 1) * bits - 1) >> 6);
		int startBit = (int) (startOffset & 63);
		if (startArr == endArr) {
			return (arr[startArr] >>> startBit) & mask;
		}
		int endOffset = 64 - startBit;
		return ((arr[startArr] >>> startBit) | (arr[endArr] << endOffset)) & mask;
	}

	// ------------------------------------------------------------------ Sponge

	private static Schematic loadSponge(Map<String, Object> root, Path file, String baseName) throws IOException {
		int version = Nbt.integer(root, "Version", 2);
		int sx = Nbt.integer(root, "Width", 0) & 0xFFFF;
		int sy = Nbt.integer(root, "Height", 0) & 0xFFFF;
		int sz = Nbt.integer(root, "Length", 0) & 0xFFFF;
		checkVolume(sx, sy, sz);

		Map<String, Object> paletteMap;
		Object dataObj;
		if (version >= 3) {
			Map<String, Object> blocks = Nbt.compound(root, "Blocks");
			if (blocks == null) throw new IOException("Sponge v3 schematic has no Blocks");
			paletteMap = Nbt.compound(blocks, "Palette");
			dataObj = blocks.get("Data");
		} else {
			paletteMap = Nbt.compound(root, "Palette");
			dataObj = root.get("BlockData");
		}
		if (paletteMap == null || !(dataObj instanceof byte[] data)) throw new IOException("Sponge schematic is missing block data");

		int maxId = 0;
		for (Object v : paletteMap.values()) if (v instanceof Number n) maxId = Math.max(maxId, n.intValue());
		if (maxId > 1_000_000) throw new IOException("Palette too large");
		String[] local = new String[maxId + 1];
		for (Map.Entry<String, Object> e : paletteMap.entrySet()) {
			if (e.getValue() instanceof Number n && n.intValue() >= 0) local[n.intValue()] = e.getKey();
		}
		PaletteBuilder pb = new PaletteBuilder();
		int[] remap = new int[local.length];
		for (int i = 0; i < local.length; i++) remap[i] = local[i] == null ? 0 : pb.id(normalize(local[i]));

		Map<String, Object> meta = Nbt.compound(root, "Metadata");
		String name = Nbt.string(meta, "Name", baseName);
		String author = Nbt.string(meta, "Author", "?");

		int[] cells = new int[sx * sy * sz];
		int pos = 0;
		int total = cells.length;
		for (int i = 0; i < total; i++) {
			int value = 0, shift = 0, b;
			do {
				if (pos >= data.length) throw new IOException("Sponge block data is truncated");
				b = data[pos++];
				value |= (b & 0x7F) << shift;
				shift += 7;
				if (shift > 35) throw new IOException("Bad varint in block data");
			} while ((b & 0x80) != 0);
			// Sponge order is x + z*W + y*W*L, which matches our (y*sz+z)*sx+x.
			cells[i] = value >= 0 && value < remap.length ? remap[value] : 0;
		}
		return new Schematic(name, author, "Sponge v" + version, file, sx, sy, sz, pb.toArray(), cells);
	}

	// ------------------------------------------------------------------ Vanilla structure

	private static Schematic loadVanilla(Map<String, Object> root, Path file, String baseName) throws IOException {
		List<Object> size = Nbt.list(root, "size");
		if (size.size() < 3) throw new IOException("Structure has no size");
		int sx = ((Number) size.get(0)).intValue(), sy = ((Number) size.get(1)).intValue(), sz = ((Number) size.get(2)).intValue();
		checkVolume(sx, sy, sz);

		List<Object> pal = Nbt.list(root, "palette");
		if (pal.isEmpty()) {
			List<Object> palettes = Nbt.list(root, "palettes");
			if (!palettes.isEmpty() && palettes.get(0) instanceof List<?> first) {
				@SuppressWarnings("unchecked") List<Object> f = (List<Object>) first;
				pal = f;
			}
		}
		PaletteBuilder pb = new PaletteBuilder();
		int[] remap = new int[pal.size()];
		for (int i = 0; i < pal.size(); i++) remap[i] = pb.id(paletteEntryToString(pal.get(i)));

		int[] cells = new int[sx * sy * sz];
		for (Object o : Nbt.list(root, "blocks")) {
			if (!(o instanceof Map)) continue;
			@SuppressWarnings("unchecked") Map<String, Object> b = (Map<String, Object>) o;
			List<Object> p = Nbt.list(b, "pos");
			if (p.size() < 3) continue;
			int x = ((Number) p.get(0)).intValue(), y = ((Number) p.get(1)).intValue(), z = ((Number) p.get(2)).intValue();
			int s = Nbt.integer(b, "state", -1);
			if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz || s < 0 || s >= remap.length) continue;
			cells[(y * sz + z) * sx + x] = remap[s];
		}
		return new Schematic(baseName, Nbt.string(root, "author", "?"), "Structure", file, sx, sy, sz, pb.toArray(), cells);
	}

	// ------------------------------------------------------------------ helpers

	private static void checkVolume(int sx, int sy, int sz) throws IOException {
		if (sx <= 0 || sy <= 0 || sz <= 0) throw new IOException("Schematic has an empty size");
		if ((long) sx * sy * sz > MAX_VOLUME) throw new IOException("Schematic is too big (" + sx + "x" + sy + "x" + sz + ")");
	}

	/** Turns a {Name, Properties} compound into "name[k=v,...]" with sorted keys. */
	private static String paletteEntryToString(Object o) {
		if (!(o instanceof Map)) return "minecraft:air";
		@SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
		String name = Nbt.string(m, "Name", "minecraft:air");
		if (!name.contains(":")) name = "minecraft:" + name;
		Map<String, Object> props = Nbt.compound(m, "Properties");
		if (props == null || props.isEmpty()) return name;
		StringBuilder sb = new StringBuilder(name).append('[');
		boolean first = true;
		for (Map.Entry<String, Object> e : new TreeMap<>(props).entrySet()) {
			if (!first) sb.append(',');
			first = false;
			sb.append(e.getKey()).append('=').append(String.valueOf(e.getValue()));
		}
		return sb.append(']').toString();
	}

	/** Adds the minecraft: namespace and sorts properties so equal states compare equal. */
	static String normalize(String state) {
		String s = state.trim();
		int b = s.indexOf('[');
		String id = b >= 0 ? s.substring(0, b) : s;
		if (!id.contains(":")) id = "minecraft:" + id;
		if (b < 0) return id;
		String inner = s.substring(b + 1, s.endsWith("]") ? s.length() - 1 : s.length());
		if (inner.isBlank()) return id;
		TreeMap<String, String> props = new TreeMap<>();
		for (String part : inner.split(",")) {
			int eq = part.indexOf('=');
			if (eq > 0) props.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
		}
		StringBuilder sb = new StringBuilder(id).append('[');
		boolean first = true;
		for (Map.Entry<String, String> e : props.entrySet()) {
			if (!first) sb.append(',');
			first = false;
			sb.append(e.getKey()).append('=').append(e.getValue());
		}
		return sb.append(']').toString();
	}

	private static String stripExt(String n) {
		int dot = n.lastIndexOf('.');
		return dot > 0 ? n.substring(0, dot) : n;
	}

	/** Palette where id 0 is always air. */
	private static final class PaletteBuilder {
		private final Map<String, Integer> ids = new HashMap<>();
		private final List<String> list = new ArrayList<>();

		PaletteBuilder() {
			list.add("minecraft:air");
			ids.put("minecraft:air", 0);
		}

		int id(String state) {
			if (Schematic.isAirName(state)) return 0;
			return ids.computeIfAbsent(state, s -> {
				list.add(s);
				return list.size() - 1;
			});
		}

		String[] toArray() {
			return list.toArray(new String[0]);
		}
	}
}
