package com.holoprint.placement;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.holoprint.config.HoloConfig;
import com.holoprint.gui.Toasts;
import com.holoprint.schematic.Schematic;
import com.holoprint.schematic.SchematicLoader;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Holds every placement for the world you're in. Placements are remembered per
 * server / singleplayer world and dimension in config/holoprint/placements.json.
 */
public final class PlacementManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final List<Placement> placements = new ArrayList<>();
	private static int selected = -1;
	private static String worldKey = null;
	private static Level lastLevel = null;

	private PlacementManager() {}

	public static String worldKey() {
		return worldKey;
	}

	/** Adds an already-built placement (used by share codes). */
	public static void add(Placement p) {
		placements.add(p);
		selected = placements.size() - 1;
		save();
	}

	public static List<Placement> all() {
		return placements;
	}

	public static Placement selected() {
		return selected >= 0 && selected < placements.size() ? placements.get(selected) : null;
	}

	public static int selectedIndex() {
		return selected;
	}

	public static void select(int i) {
		selected = i >= 0 && i < placements.size() ? i : -1;
	}

	public static Placement place(Schematic s) {
		Minecraft mc = Minecraft.getInstance();
		BlockPos at = mc.player != null ? mc.player.blockPosition() : BlockPos.ZERO;
		Placement p = new Placement(s, at);
		placements.add(p);
		selected = placements.size() - 1;
		save();
		return p;
	}

	public static void remove(Placement p) {
		int i = placements.indexOf(p);
		if (i < 0) return;
		placements.remove(i);
		if (selected >= placements.size()) selected = placements.size() - 1;
		save();
	}

	/** Called every client tick. Handles world changes and checks blocks a bit at a time. */
	public static void tick(Minecraft mc) {
		if (mc.level != lastLevel) {
			if (lastLevel != null) save();
			lastLevel = mc.level;
			placements.clear();
			selected = -1;
			worldKey = mc.level == null ? null : computeWorldKey(mc);
			if (worldKey != null) loadForWorld();
		}
		if (mc.level == null) return;
		for (Placement p : placements) {
			if (!p.visible) continue;
			int budget = Math.max(2000, Math.min(40000, p.count / 10));
			p.scan(mc.level, budget);
		}
	}

	private static String computeWorldKey(Minecraft mc) {
		String where;
		if (mc.getSingleplayerServer() != null) {
			where = "sp:" + mc.getSingleplayerServer().getWorldData().getLevelName();
		} else if (mc.getCurrentServer() != null) {
			where = "mp:" + mc.getCurrentServer().ip;
		} else {
			where = "unknown";
		}
		return where + "|" + mc.level.dimension();
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("holoprint").resolve("placements.json");
	}

	public static void save() {
		if (worldKey == null) return;
		try {
			JsonObject root = readRoot();
			JsonArray arr = new JsonArray();
			Path base = HoloConfig.schematicsDir();
			for (Placement p : placements) {
				JsonObject o = new JsonObject();
				Path f = p.schematic.file;
				o.addProperty("file", f.startsWith(base) ? base.relativize(f).toString() : f.toString());
				o.addProperty("x", p.origin.getX());
				o.addProperty("y", p.origin.getY());
				o.addProperty("z", p.origin.getZ());
				o.addProperty("rotation", p.rotation);
				o.addProperty("mirror", p.mirror);
				o.addProperty("visible", p.visible);
				o.addProperty("layerMode", p.layerMode);
				o.addProperty("layer", p.layer);
				arr.add(o);
			}
			if (arr.isEmpty()) root.remove(worldKey);
			else root.add(worldKey, arr);
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(root), StandardCharsets.UTF_8);
		} catch (Exception e) {
			System.err.println("[HoloPrint] Couldn't save placements: " + e.getMessage());
		}
	}

	private static JsonObject readRoot() {
		try {
			if (Files.exists(file())) {
				return JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
			}
		} catch (Exception ignored) {
		}
		return new JsonObject();
	}

	private static void loadForWorld() {
		JsonObject root = readRoot();
		if (!root.has(worldKey) || !root.get(worldKey).isJsonArray()) return;
		Path base = HoloConfig.schematicsDir();
		for (var el : root.getAsJsonArray(worldKey)) {
			try {
				JsonObject o = el.getAsJsonObject();
				Path f = Path.of(o.get("file").getAsString());
				if (!f.isAbsolute()) f = base.resolve(f);
				if (!Files.exists(f)) continue;
				Schematic s = SchematicLoader.load(f);
				Placement p = new Placement(s, new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()));
				p.rotation = o.get("rotation").getAsInt() & 3;
				p.mirror = Math.floorMod(o.get("mirror").getAsInt(), 3);
				p.visible = !o.has("visible") || o.get("visible").getAsBoolean();
				p.layerMode = o.has("layerMode") ? Math.floorMod(o.get("layerMode").getAsInt(), 3) : 0;
				p.layer = o.has("layer") ? o.get("layer").getAsInt() : 0;
				p.rebuild();
				placements.add(p);
			} catch (Exception e) {
				System.err.println("[HoloPrint] Skipped a saved placement: " + e.getMessage());
			}
		}
		if (!placements.isEmpty()) {
			selected = 0;
			Toasts.info("Restored " + placements.size() + " placement" + (placements.size() == 1 ? "" : "s"));
		}
	}
}
