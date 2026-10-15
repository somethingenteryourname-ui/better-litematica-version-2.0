package com.holoprint.online;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;

/** Remembers which online posts you already downloaded, so the browser can mark them. */
public final class DownloadIndex {
	private static final Gson GSON = new Gson();
	private static Map<String, String> map;

	private DownloadIndex() {}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("holoprint").resolve("downloads.json");
	}

	private static synchronized Map<String, String> data() {
		if (map == null) {
			map = new HashMap<>();
			try {
				if (Files.exists(file())) {
					Map<String, String> m = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8),
							new TypeToken<Map<String, String>>() {}.getType());
					if (m != null) map.putAll(m);
				}
			} catch (Exception ignored) {
			}
		}
		return map;
	}

	public static synchronized void mark(String key, Path saved) {
		data().put(key, saved.toString());
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(map), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
		}
	}

	/** The online "vendor/uuid" a local file was downloaded from, or null. */
	public static synchronized String keyFor(Path file) {
		String f = file.toString();
		for (Map.Entry<String, String> e : data().entrySet()) {
			if (e.getValue().equals(f)) return e.getKey();
		}
		return null;
	}

	/** Returns the saved file if it was downloaded and still exists. */
	public static synchronized Path get(String key) {
		String s = data().get(key);
		if (s == null) return null;
		Path p = Path.of(s);
		return Files.exists(p) ? p : null;
	}
}
