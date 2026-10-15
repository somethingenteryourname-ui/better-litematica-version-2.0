package com.holoprint.schematic;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Writes a {@link Schematic} as a .litematic file, so saved builds also open in
 * Litematica. Has its own tiny NBT writer (no Minecraft classes needed).
 */
public final class SchematicWriter {
	private SchematicWriter() {}

	/** Data version for Minecraft 1.21.11. */
	public static final int DATA_VERSION = 4671;

	public static void writeLitematic(Schematic s, String description, Path target) throws IOException {
		Map<String, Object> root = new LinkedHashMap<>();
		long now = System.currentTimeMillis();
		int total = s.countNonAir();

		Map<String, Object> size = xyz(s.sizeX, s.sizeY, s.sizeZ);
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("Name", s.name);
		meta.put("Author", s.author);
		meta.put("Description", description == null ? "" : description);
		meta.put("RegionCount", 1);
		meta.put("TotalVolume", s.volume());
		meta.put("TotalBlocks", total);
		meta.put("TimeCreated", now);
		meta.put("TimeModified", now);
		meta.put("EnclosingSize", size);

		// palette: air must be entry 0 for Litematica
		List<Object> palette = new ArrayList<>();
		for (String st : s.palette) palette.add(paletteEntry(st));

		int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, s.palette.length - 1)));
		long volume = s.volume();
		long[] packed = new long[(int) ((volume * bits + 63) / 64)];
		for (long i = 0; i < volume; i++) writePacked(packed, i, bits, s.cells[(int) i]);

		Map<String, Object> region = new LinkedHashMap<>();
		region.put("Position", xyz(0, 0, 0));
		region.put("Size", xyz(s.sizeX, s.sizeY, s.sizeZ));
		region.put("BlockStatePalette", new TypedList(10, palette));
		region.put("BlockStates", packed);
		region.put("TileEntities", new TypedList(10, new ArrayList<>()));
		region.put("Entities", new TypedList(10, new ArrayList<>()));
		region.put("PendingBlockTicks", new TypedList(10, new ArrayList<>()));
		region.put("PendingFluidTicks", new TypedList(10, new ArrayList<>()));

		Map<String, Object> regions = new LinkedHashMap<>();
		regions.put(s.name.isBlank() ? "Main" : s.name, region);

		root.put("MinecraftDataVersion", DATA_VERSION);
		root.put("Version", 6);
		root.put("SubVersion", 1);
		root.put("Metadata", meta);
		root.put("Regions", regions);

		if (target.toAbsolutePath().getParent() != null) Files.createDirectories(target.toAbsolutePath().getParent());
		Path tmp = target.resolveSibling(target.getFileName() + ".part");
		Files.write(tmp, toGzippedNbt(root));
		Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	/** Inverse of SchematicLoader.readPacked (values may span two longs). */
	static void writePacked(long[] arr, long index, int bits, long value) {
		long startOffset = index * bits;
		int startArr = (int) (startOffset >> 6);
		int endArr = (int) (((index + 1) * bits - 1) >> 6);
		int startBit = (int) (startOffset & 63);
		long mask = (1L << bits) - 1L;
		value &= mask;
		arr[startArr] = arr[startArr] & ~(mask << startBit) | value << startBit;
		if (startArr != endArr) {
			int endOffset = 64 - startBit;
			int j1 = bits - endOffset;
			arr[endArr] = arr[endArr] >>> j1 << j1 | value >> endOffset;
		}
	}

	private static Map<String, Object> xyz(int x, int y, int z) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("x", x);
		m.put("y", y);
		m.put("z", z);
		return m;
	}

	private static Map<String, Object> paletteEntry(String state) {
		Map<String, Object> m = new LinkedHashMap<>();
		int b = state.indexOf('[');
		m.put("Name", b >= 0 ? state.substring(0, b) : state);
		if (b >= 0) {
			Map<String, Object> props = new LinkedHashMap<>();
			String inner = state.substring(b + 1, state.endsWith("]") ? state.length() - 1 : state.length());
			for (String part : inner.split(",")) {
				int eq = part.indexOf('=');
				if (eq > 0) props.put(part.substring(0, eq), part.substring(eq + 1));
			}
			if (!props.isEmpty()) m.put("Properties", props);
		}
		return m;
	}

	// ------------------------------------------------------------- NBT writing

	/** A list with an explicit element type (needed for empty lists). */
	public record TypedList(int type, List<Object> items) {}

	public static byte[] toGzippedNbt(Map<String, Object> root) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
			out.writeByte(10);
			out.writeUTF("");
			writeCompound(out, root);
		}
		return bytes.toByteArray();
	}

	private static int typeOf(Object o) {
		if (o instanceof Byte) return 1;
		if (o instanceof Short) return 2;
		if (o instanceof Integer) return 3;
		if (o instanceof Long) return 4;
		if (o instanceof Float) return 5;
		if (o instanceof Double) return 6;
		if (o instanceof byte[]) return 7;
		if (o instanceof String) return 8;
		if (o instanceof TypedList || o instanceof List) return 9;
		if (o instanceof Map) return 10;
		if (o instanceof int[]) return 11;
		if (o instanceof long[]) return 12;
		throw new IllegalArgumentException("Can't write " + o.getClass());
	}

	@SuppressWarnings("unchecked")
	private static void writeCompound(DataOutputStream out, Map<String, Object> m) throws IOException {
		for (Map.Entry<String, Object> e : m.entrySet()) {
			if (e.getValue() == null) continue;
			out.writeByte(typeOf(e.getValue()));
			out.writeUTF(e.getKey());
			writePayload(out, e.getValue());
		}
		out.writeByte(0);
	}

	@SuppressWarnings("unchecked")
	private static void writePayload(DataOutputStream out, Object o) throws IOException {
		switch (typeOf(o)) {
			case 1 -> out.writeByte((Byte) o);
			case 2 -> out.writeShort((Short) o);
			case 3 -> out.writeInt((Integer) o);
			case 4 -> out.writeLong((Long) o);
			case 5 -> out.writeFloat((Float) o);
			case 6 -> out.writeDouble((Double) o);
			case 7 -> { byte[] b = (byte[]) o; out.writeInt(b.length); out.write(b); }
			case 8 -> out.writeUTF((String) o);
			case 9 -> {
				List<Object> items;
				int type;
				if (o instanceof TypedList t) { items = t.items(); type = t.type(); }
				else { items = (List<Object>) o; type = items.isEmpty() ? 0 : typeOf(items.get(0)); }
				out.writeByte(items.isEmpty() && !(o instanceof TypedList) ? 0 : type);
				out.writeInt(items.size());
				for (Object it : items) writePayload(out, it);
			}
			case 10 -> writeCompound(out, (Map<String, Object>) o);
			case 11 -> { int[] a = (int[]) o; out.writeInt(a.length); for (int v : a) out.writeInt(v); }
			case 12 -> { long[] a = (long[]) o; out.writeInt(a.length); for (long v : a) out.writeLong(v); }
			default -> throw new IOException("bad type");
		}
	}
}
