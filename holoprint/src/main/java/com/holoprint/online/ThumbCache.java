package com.holoprint.online;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small pixel-art thumbnails for online results. Images are shrunk to a tiny
 * grid of colours and drawn as coloured squares, which matches the hologram
 * look and avoids juggling GPU textures. PNG, JPEG and GIF work; formats Java
 * can't read (like WebP) just show a placeholder.
 */
public final class ThumbCache {
	private ThumbCache() {}

	public static final int W = 32, H = 20;

	/** pixels == null while loading; failed == true if it couldn't be loaded. */
	public record Thumb(int[] pixels, boolean failed) {}

	private static final Thumb LOADING = new Thumb(null, false);
	private static final Thumb FAILED = new Thumb(null, true);
	private static final Map<String, Thumb> cache = new ConcurrentHashMap<>();

	public static Thumb get(String url) {
		if (url == null || url.isBlank()) return FAILED;
		Thumb t = cache.get(url);
		if (t != null) return t;
		if (cache.size() > 400) cache.clear();
		cache.put(url, LOADING);
		OnlineLibrary.fetchImage(url).whenComplete((bytes, err) -> {
			if (err != null || bytes == null) {
				cache.put(url, FAILED);
				return;
			}
			cache.put(url, decode(bytes));
		});
		return LOADING;
	}

	private static Thumb decode(byte[] bytes) {
		try {
			int[] px = ThumbDecoder.decode(bytes, W, H);
			return px == null ? FAILED : new Thumb(px, false);
		} catch (Throwable t) {
			// includes a Java install without image support (the decoder class then fails to load)
			return FAILED;
		}
	}
}
