package com.holoprint.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/** Every customisable option. Saved to config/holoprint.json. */
public final class HoloConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static HoloConfig instance;

	// ---------------- UI look
	public int accentColor = 0x00E5FF;      // main glow colour
	public int accent2Color = 0xB45CFF;     // secondary colour used in gradients
	public int panelOpacity = 225;          // 0-255
	public boolean animatedUi = true;       // scanlines + pulse
	public boolean compactLists = false;
	public int themePreset = 0;

	// ---------------- Hologram
	public boolean hologramVisible = true;
	public int renderMode = 0;              // 0 = holo boxes, 1 = real block models
	public int renderRadius = 48;
	public int hologramAlpha = 90;          // 0-255 for the face fill
	public boolean outlineEdges = true;
	public boolean pulse = true;
	public boolean showWrongBlocks = true;
	public boolean showExtraBlocks = false;
	public int colorMissing = 0x29D8FF;
	public int colorWrongBlock = 0xFF3355;
	public int colorWrongState = 0xFFA020;
	public int colorExtra = 0xFF40E0;

	// ---------------- HUD
	public boolean hudEnabled = true;
	public int hudCorner = 0;               // 0 TL, 1 TR, 2 BL, 3 BR

	// ---------------- Building helpers
	public boolean autoPick = false;        // switch hotbar slot when you look at a hologram block
	public boolean afterViewHold = true;    // finished-build view: hold key (true) or toggle (false)
	public boolean grabSnap = true;         // grab mode sticks to the block you look at
	public boolean showChestRoute = true;   // outline the next chest to grab from
	public boolean hudShowTarget = true;    // HUD line for the block you're aiming at

	// ---------------- Online
	public String downloadSubfolder = "HoloPrint Downloads";
	public String lastSort = "popular";
	public String versionFilter = "";
	public boolean thumbnails = true;

	// ---------------- Library
	public boolean preview3d = true;

	public static HoloConfig get() {
		if (instance == null) load();
		return instance;
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("holoprint.json");
	}

	public static void load() {
		try {
			Path f = file();
			if (Files.exists(f)) {
				instance = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), HoloConfig.class);
			}
		} catch (Exception e) {
			System.err.println("[HoloPrint] Couldn't read config, using defaults: " + e.getMessage());
		}
		if (instance == null) instance = new HoloConfig();
		instance.clamp();
	}

	public void save() {
		clamp();
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			System.err.println("[HoloPrint] Couldn't save config: " + e.getMessage());
		}
	}

	private void clamp() {
		panelOpacity = Math.max(60, Math.min(255, panelOpacity));
		renderRadius = Math.max(8, Math.min(256, renderRadius));
		hologramAlpha = Math.max(15, Math.min(230, hologramAlpha));
		hudCorner = Math.floorMod(hudCorner, 4);
		renderMode = Math.floorMod(renderMode, 2);
		if (downloadSubfolder == null || downloadSubfolder.isBlank()) downloadSubfolder = "HoloPrint Downloads";
		if (lastSort == null) lastSort = "popular";
		if (versionFilter == null) versionFilter = "";
	}

	/** The schematics folder, shared with Litematica so files show up in both. */
	public static Path schematicsDir() {
		return FabricLoader.getInstance().getGameDir().resolve("schematics");
	}

	public Path downloadsDir() {
		String safe = downloadSubfolder.replaceAll("[\\\\/:*?\"<>|]", "_");
		return schematicsDir().resolve(safe);
	}

	// ---------------- theme presets: name, accent, accent2
	public static final String[] PRESET_NAMES = {"Cyan Holo", "Synthwave", "Emerald", "Solar", "Blood Moon", "Ice", "Mono"};
	public static final int[][] PRESETS = {
			{0x00E5FF, 0xB45CFF},
			{0xFF3CAC, 0x784BA0},
			{0x2BFF88, 0x00B3A4},
			{0xFFB000, 0xFF5E3A},
			{0xFF2E4C, 0x7A0BC0},
			{0xA8E9FF, 0x5B8CFF},
			{0xE6E6E6, 0x7A7A7A},
	};

	public void applyPreset(int idx) {
		themePreset = Math.floorMod(idx, PRESETS.length);
		accentColor = PRESETS[themePreset][0];
		accent2Color = PRESETS[themePreset][1];
	}
}
