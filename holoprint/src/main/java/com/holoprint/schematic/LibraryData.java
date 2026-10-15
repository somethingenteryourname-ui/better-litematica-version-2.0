package com.holoprint.schematic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.holoprint.config.HoloConfig;

import net.fabricmc.loader.api.FabricLoader;

/** Favourites and named collections for the Library. Saved in config/holoprint/library.json. */
public final class LibraryData {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static LibraryData instance;

	public Set<String> favorites = new LinkedHashSet<>();
	public Map<String, List<String>> collections = new LinkedHashMap<>();

	public static LibraryData get() {
		if (instance == null) {
			try {
				Path f = file();
				if (Files.exists(f)) instance = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), LibraryData.class);
			} catch (Exception e) {
				System.err.println("[HoloPrint] Couldn't read library.json: " + e.getMessage());
			}
			if (instance == null) instance = new LibraryData();
			if (instance.favorites == null) instance.favorites = new LinkedHashSet<>();
			if (instance.collections == null) instance.collections = new LinkedHashMap<>();
		}
		return instance;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("holoprint").resolve("library.json");
	}

	public void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (Exception e) {
			System.err.println("[HoloPrint] Couldn't save library.json: " + e.getMessage());
		}
	}

	/** Paths are stored relative to the schematics folder so they survive moving .minecraft. */
	public static String key(Path p) {
		Path base = HoloConfig.schematicsDir();
		return (p.startsWith(base) ? base.relativize(p) : p).toString().replace('\\', '/');
	}

	public boolean isFavorite(Path p) {
		return favorites.contains(key(p));
	}

	public void toggleFavorite(Path p) {
		String k = key(p);
		if (!favorites.remove(k)) favorites.add(k);
		save();
	}

	public boolean inCollection(String name, Path p) {
		List<String> l = collections.get(name);
		return l != null && l.contains(key(p));
	}

	public void toggleInCollection(String name, Path p) {
		List<String> l = collections.computeIfAbsent(name, n -> new ArrayList<>());
		String k = key(p);
		if (!l.remove(k)) l.add(k);
		save();
	}

	public void createCollection(String name) {
		collections.putIfAbsent(name, new ArrayList<>());
		save();
	}

	public void deleteCollection(String name) {
		collections.remove(name);
		save();
	}
}
