package com.holoprint.schematic;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * A tiny standalone NBT reader. It does not use Minecraft's NBT classes, so it
 * keeps working even when Mojang renames things between versions.
 *
 * Compounds become {@code Map<String,Object>}, lists become {@code List<Object>},
 * and numbers / strings / arrays become their plain Java types.
 */
public final class Nbt {
	private Nbt() {}

	private static final int MAX_DEPTH = 512;

	/** Reads a (usually gzipped) NBT file and returns its root compound. */
	public static Map<String, Object> read(byte[] data) throws IOException {
		InputStream in = new ByteArrayInputStream(data);
		if (data.length >= 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
			in = new GZIPInputStream(in);
		}
		DataInputStream din = new DataInputStream(new BufferedInputStream(in));
		int type = din.readUnsignedByte();
		if (type != 10) throw new IOException("Not an NBT compound (root tag type " + type + ")");
		din.readUTF(); // root name
		return readCompound(din, 0);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> readCompound(DataInputStream in, int depth) throws IOException {
		if (depth > MAX_DEPTH) throw new IOException("NBT nested too deep");
		Map<String, Object> map = new LinkedHashMap<>();
		while (true) {
			int type = in.readUnsignedByte();
			if (type == 0) return map;
			String name = in.readUTF();
			map.put(name, readPayload(in, type, depth + 1));
		}
	}

	private static Object readPayload(DataInputStream in, int type, int depth) throws IOException {
		switch (type) {
			case 1: return in.readByte();
			case 2: return in.readShort();
			case 3: return in.readInt();
			case 4: return in.readLong();
			case 5: return in.readFloat();
			case 6: return in.readDouble();
			case 7: {
				int len = checkedLength(in.readInt());
				byte[] b = new byte[len];
				in.readFully(b);
				return b;
			}
			case 8: return in.readUTF();
			case 9: {
				int elemType = in.readUnsignedByte();
				int len = checkedLength(in.readInt());
				List<Object> list = new ArrayList<>(Math.min(len, 4096));
				for (int i = 0; i < len; i++) list.add(readPayload(in, elemType, depth + 1));
				return list;
			}
			case 10: return readCompound(in, depth);
			case 11: {
				int len = checkedLength(in.readInt());
				int[] a = new int[len];
				for (int i = 0; i < len; i++) a[i] = in.readInt();
				return a;
			}
			case 12: {
				int len = checkedLength(in.readInt());
				long[] a = new long[len];
				for (int i = 0; i < len; i++) a[i] = in.readLong();
				return a;
			}
			default: throw new IOException("Unknown NBT tag type " + type);
		}
	}

	private static int checkedLength(int len) throws IOException {
		if (len < 0 || len > 256 * 1024 * 1024) throw new IOException("Bad NBT length " + len);
		return len;
	}

	// ---- small helpers for reading the tree ----

	@SuppressWarnings("unchecked")
	public static Map<String, Object> compound(Map<String, Object> m, String key) {
		Object o = m == null ? null : m.get(key);
		return o instanceof Map ? (Map<String, Object>) o : null;
	}

	@SuppressWarnings("unchecked")
	public static List<Object> list(Map<String, Object> m, String key) {
		Object o = m == null ? null : m.get(key);
		return o instanceof List ? (List<Object>) o : List.of();
	}

	public static int integer(Map<String, Object> m, String key, int def) {
		Object o = m == null ? null : m.get(key);
		return o instanceof Number n ? n.intValue() : def;
	}

	public static String string(Map<String, Object> m, String key, String def) {
		Object o = m == null ? null : m.get(key);
		return o instanceof String s ? s : def;
	}

	public static boolean has(Map<String, Object> m, String key) {
		return m != null && m.containsKey(key);
	}

	/** Quick check used before saving downloads. */
	public static boolean looksLikeNbt(byte[] data) {
		try {
			read(data);
			return true;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}
}
