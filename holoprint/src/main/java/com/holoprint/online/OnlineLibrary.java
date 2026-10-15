package com.holoprint.online;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.holoprint.config.HoloConfig;
import com.holoprint.schematic.Nbt;

/**
 * Searches public schematic sites through the MineMev API (which also covers
 * several other sites) and downloads files straight into your schematics folder.
 * Everything runs off the game thread.
 */
public final class OnlineLibrary {
	private OnlineLibrary() {}

	public static final String API = "https://www.minemev.com/api";
	public static final String[] SORTS = {"popular", "newest", "downloads", "oldest"};
	private static final String USER_AGENT = "HoloPrint/0.1 (Minecraft Fabric mod)";
	private static final long MAX_DOWNLOAD = 64L * 1024 * 1024;

	private static final ExecutorService POOL = Executors.newFixedThreadPool(3, r -> {
		Thread t = new Thread(r, "HoloPrint-Net");
		t.setDaemon(true);
		return t;
	});

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(12))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	public record Post(String uuid, String vendor, String title, String author, String description,
					   int downloads, List<String> tags, List<String> versions, String pageUrl, String thumbUrl) {
		/** Loose match: "1.21" matches "1.21.4", "1.21.11" etc. */
		public boolean supportsVersion(String v) {
			if (v == null || v.isBlank() || versions.isEmpty()) return true;
			for (String s : versions) {
				if (s.equals(v) || s.startsWith(v + ".") || s.startsWith(v + "-") || s.startsWith(v + " ")) return true;
			}
			return false;
		}
	}

	public record FileEntry(String name, String url, long size, String type) {
		public boolean isSchematic() {
			String n = name.toLowerCase(Locale.ROOT);
			return n.endsWith(".litematic") || n.endsWith(".schem") || n.endsWith(".schematic") || n.endsWith(".nbt");
		}
	}

	public record Page(List<Post> posts, int page, int totalPages) {}

	public static CompletableFuture<Page> search(String query, String sort, int page, int pageSize) {
		return search(query, sort, page, pageSize, null);
	}

	/**
	 * Search with an optional Minecraft version filter. The filter is sent to the
	 * site, and if the site ignores or misunderstands it we also filter here.
	 */
	public static CompletableFuture<Page> search(String query, String sort, int page, int pageSize, String version) {
		if (version == null || version.isBlank()) return searchRaw(query, sort, page, pageSize, null);
		return searchRaw(query, sort, page, pageSize, version).thenCompose(res -> {
			List<Post> keep = new ArrayList<>();
			for (Post p : res.posts()) if (p.supportsVersion(version)) keep.add(p);
			if (!keep.isEmpty() || res.posts().isEmpty() && page > 1) {
				return CompletableFuture.completedFuture(new Page(keep, res.page(), res.totalPages()));
			}
			// site returned nothing usable for that filter: search without it and filter ourselves
			return searchRaw(query, sort, page, pageSize * 2, null).thenApply(r2 -> {
				List<Post> k2 = new ArrayList<>();
				for (Post p : r2.posts()) if (p.supportsVersion(version)) k2.add(p);
				return new Page(k2, r2.page(), r2.totalPages());
			});
		});
	}

	private static CompletableFuture<Page> searchRaw(String query, String sort, int page, int pageSize, String version) {
		return CompletableFuture.supplyAsync(() -> {
			StringBuilder url = new StringBuilder(API).append("/search?clean_uuid=1");
			if (query != null && !query.isBlank()) url.append("&search=").append(enc(query.trim()));
			if (sort != null && !sort.isBlank()) url.append("&sort=").append(enc(sort));
			url.append("&page=").append(Math.max(1, page));
			url.append("&pagesize=").append(pageSize);
			if (version != null && !version.isBlank()) url.append("&versions=").append(enc(version));
			JsonObject root = JsonParser.parseString(getString(url.toString())).getAsJsonObject();
			List<Post> posts = new ArrayList<>();
			JsonArray arr = root.has("posts") && root.get("posts").isJsonArray() ? root.getAsJsonArray("posts") : new JsonArray();
			for (JsonElement el : arr) {
				if (!el.isJsonObject()) continue;
				JsonObject o = el.getAsJsonObject();
				posts.add(new Post(
						str(o, "uuid"), orDefault(str(o, "vendor"), "minemev"),
						orDefault(str(o, "post_name", "postName"), "Untitled"),
						orDefault(str(o, "User", "user"), "unknown"),
						orDefault(str(o, "description"), ""),
						integer(o, "downloads"), strList(o, "tags"), strList(o, "versions"),
						str(o, "url_redirect", "urlRedirect"), thumbOf(o)));
			}
			int total = root.has("total_pages") ? safeInt(root.get("total_pages")) : -1;
			return new Page(posts, Math.max(1, page), total);
		}, POOL);
	}

	public static CompletableFuture<List<FileEntry>> files(Post post) {
		return CompletableFuture.supplyAsync(() -> {
			String url = API + "/files/" + enc(post.vendor()) + "/" + enc(post.uuid());
			JsonElement root = JsonParser.parseString(getString(url));
			List<FileEntry> out = new ArrayList<>();
			JsonArray arr = root.isJsonArray() ? root.getAsJsonArray()
					: root.isJsonObject() && root.getAsJsonObject().has("files") ? root.getAsJsonObject().getAsJsonArray("files") : new JsonArray();
			for (JsonElement el : arr) {
				if (!el.isJsonObject()) continue;
				JsonObject o = el.getAsJsonObject();
				String fileUrl = str(o, "file");
				if (fileUrl == null || fileUrl.isBlank()) continue;
				String name = orDefault(str(o, "default_file_name", "defaultFileName"), fileUrl.substring(fileUrl.lastIndexOf('/') + 1));
				long size = o.has("file_size") ? safeLong(o.get("file_size")) : o.has("fileSize") ? safeLong(o.get("fileSize")) : -1;
				out.add(new FileEntry(name, fileUrl, size, orDefault(str(o, "file_type", "fileType"), "")));
			}
			return out;
		}, POOL);
	}

	/** Picks the best file of a post (litematic first) and downloads it. Returns the saved path. */
	public static CompletableFuture<Path> downloadBest(Post post) {
		return files(post).thenCompose(list -> {
			FileEntry best = null;
			int bestScore = -1;
			for (FileEntry f : list) {
				String n = f.name().toLowerCase(Locale.ROOT);
				int score = n.endsWith(".litematic") ? 4 : n.endsWith(".schem") ? 3 : n.endsWith(".nbt") ? 2 : n.endsWith(".schematic") ? 1 : -1;
				if (score > bestScore) {
					bestScore = score;
					best = f;
				}
			}
			if (best == null) {
				return CompletableFuture.failedFuture(new RuntimeException("No schematic file in this post (it may be a world download)"));
			}
			return download(post, best);
		});
	}

	public static CompletableFuture<Path> download(Post post, FileEntry file) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				String url = file.url();
				if (url.startsWith("//")) url = "https:" + url;
				else if (url.startsWith("/")) url = "https://www.minemev.com" + url;
				byte[] data = getBytes(encodePathTail(url));
				if (!Nbt.looksLikeNbt(data)) throw new RuntimeException("Downloaded file isn't a valid schematic");
				Path dir = HoloConfig.get().downloadsDir();
				Files.createDirectories(dir);
				Path target = uniquePath(dir, safeFileName(file.name()));
				Path tmp = dir.resolve(target.getFileName() + ".part");
				Files.write(tmp, data);
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
				DownloadIndex.mark(post.vendor() + "/" + post.uuid(), target);
				return target;
			} catch (IOException e) {
				throw new RuntimeException("Couldn't save the file: " + e.getMessage(), e);
			}
		}, POOL);
	}

	// ------------------------------------------------------------- http helpers

	private static String getString(String url) {
		return new String(getBytes(url), StandardCharsets.UTF_8);
	}

	private static byte[] getBytes(String url) {
		try {
			URI uri = URI.create(url);
			if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
				throw new RuntimeException("Unsupported link");
			}
			HttpRequest req = HttpRequest.newBuilder(uri)
					.timeout(Duration.ofSeconds(30))
					.header("User-Agent", USER_AGENT)
					.GET().build();
			HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
			int code = res.statusCode();
			if (code == 429) throw new RuntimeException("Too many requests, wait a moment");
			if (code == 404) throw new RuntimeException("Not found on the server");
			if (code >= 400) throw new RuntimeException("Server error (HTTP " + code + ")");
			byte[] body = res.body();
			if (body.length > MAX_DOWNLOAD) throw new RuntimeException("File is too large");
			return body;
		} catch (IOException e) {
			throw new RuntimeException("Network error: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Cancelled", e);
		}
	}

	/** File names in links can contain spaces etc.; re-encode only the last part. */
	private static String encodePathTail(String url) {
		int slash = url.lastIndexOf('/');
		if (slash < 0) return url;
		String tail = URLDecoder.decode(url.substring(slash + 1), StandardCharsets.UTF_8);
		return url.substring(0, slash + 1) + URLEncoder.encode(tail, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	public static String safeFileName(String name) {
		String n = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
		if (n.startsWith(".")) n = "_" + n;
		if (n.isEmpty()) n = "schematic.litematic";
		if (n.length() > 120) {
			int dot = n.lastIndexOf('.');
			String ext = dot > 0 ? n.substring(dot) : "";
			n = n.substring(0, 120 - ext.length()) + ext;
		}
		return n;
	}

	private static Path uniquePath(Path dir, String name) {
		Path p = dir.resolve(name);
		if (!Files.exists(p)) return p;
		int dot = name.lastIndexOf('.');
		String base = dot > 0 ? name.substring(0, dot) : name;
		String ext = dot > 0 ? name.substring(dot) : "";
		for (int i = 2; i < 1000; i++) {
			p = dir.resolve(base + " (" + i + ")" + ext);
			if (!Files.exists(p)) return p;
		}
		return dir.resolve(base + " " + System.currentTimeMillis() + ext);
	}

	private static String str(JsonObject o, String... keys) {
		for (String k : keys) {
			if (o.has(k) && o.get(k).isJsonPrimitive()) return o.get(k).getAsString();
		}
		return null;
	}

	private static String thumbOf(JsonObject o) {
		String t = str(o, "thumbnail_url", "thumbnailUrl");
		if (t != null && !t.isBlank()) return t;
		List<String> imgs = strList(o, "images");
		return imgs.isEmpty() ? null : imgs.get(0);
	}

	/** Downloads an image for thumbnails (runs on the network threads). */
	public static CompletableFuture<byte[]> fetchImage(String url) {
		return CompletableFuture.supplyAsync(() -> {
			String u = url;
			if (u.startsWith("//")) u = "https:" + u;
			else if (u.startsWith("/")) u = "https://www.minemev.com" + u;
			return getBytes(u);
		}, POOL);
	}

	private static String orDefault(String s, String def) {
		return s == null || s.isBlank() ? def : s;
	}

	private static int integer(JsonObject o, String key) {
		return o.has(key) ? safeInt(o.get(key)) : 0;
	}

	private static int safeInt(JsonElement e) {
		try {
			return e.getAsInt();
		} catch (RuntimeException ex) {
			return -1;
		}
	}

	private static long safeLong(JsonElement e) {
		try {
			return e.getAsLong();
		} catch (RuntimeException ex) {
			return -1;
		}
	}

	private static List<String> strList(JsonObject o, String key) {
		List<String> out = new ArrayList<>();
		if (o.has(key) && o.get(key).isJsonArray()) {
			for (JsonElement el : o.getAsJsonArray(key)) {
				if (el.isJsonPrimitive()) out.add(el.getAsString());
			}
		}
		return out;
	}
}
