package com.holoprint.schematic;

import java.nio.file.Path;

/**
 * A loaded schematic in a simple, Minecraft-independent form.
 *
 * Every cell holds an index into {@link #palette}. Palette entries are block
 * state strings such as {@code minecraft:oak_stairs[facing=north,half=bottom]}.
 * Cell index = (y * sizeZ + z) * sizeX + x.
 */
public final class Schematic {
	public final String name;
	public final String author;
	public final String format;
	public final Path file;
	public final int sizeX, sizeY, sizeZ;
	public final String[] palette;
	public final int[] cells;
	/** true for palette entries that are air / structure void. */
	public final boolean[] paletteIsAir;

	public Schematic(String name, String author, String format, Path file,
					 int sizeX, int sizeY, int sizeZ, String[] palette, int[] cells) {
		this.name = name;
		this.author = author;
		this.format = format;
		this.file = file;
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.palette = palette;
		this.cells = cells;
		this.paletteIsAir = new boolean[palette.length];
		for (int i = 0; i < palette.length; i++) {
			this.paletteIsAir[i] = isAirName(palette[i]);
		}
	}

	public int index(int x, int y, int z) {
		return (y * sizeZ + z) * sizeX + x;
	}

	public int volume() {
		return sizeX * sizeY * sizeZ;
	}

	public int countNonAir() {
		int n = 0;
		for (int c : cells) if (!paletteIsAir[c]) n++;
		return n;
	}

	public static boolean isAirName(String state) {
		String id = state;
		int b = id.indexOf('[');
		if (b >= 0) id = id.substring(0, b);
		return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air")
				|| id.equals("minecraft:structure_void") || id.equals("air");
	}
}
