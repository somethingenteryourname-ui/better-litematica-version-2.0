package com.holoprint.placement;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.holoprint.config.HoloConfig;
import com.holoprint.online.DownloadIndex;
import com.holoprint.online.OnlineLibrary;
import com.holoprint.schematic.Schematic;
import com.holoprint.schematic.SchematicLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Share codes: a text code you can paste to a friend. It holds the build's
 * position and rotation, plus the schematic itself when it's small (or the
 * online post it came from), so their hologram lines up exactly with yours.
 */
public final class ShareCodes {
	private ShareCodes() {}

	private static final String PREFIX = "HOLOPRINT1.";
	private static final int EMBED_LIMIT = 48 * 1024;

	public static String encode(Placement p) throws Exception {
		JsonObject o = new JsonObject();
		o.addProperty("n", p.schematic.name);
		o.addProperty("f", p.schematic.file.getFileName().toString());
		String online = DownloadIndex.keyFor(p.schematic.file);
		if (online != null) o.addProperty("o", online);
		o.addProperty("x", p.origin.getX());
		o.addProperty("y", p.origin.getY());
		o.addProperty("z", p.origin.getZ());
		o.addProperty("r", p.rotation);
		o.addProperty("m", p.mirror);
		byte[] file = Files.exists(p.schematic.file) ? Files.readAllBytes(p.schematic.file) : null;
		if (file != null && file.length <= EMBED_LIMIT) o.addProperty("d", Base64.getEncoder().encodeToString(file));
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
			gz.write(o.toString().getBytes(StandardCharsets.UTF_8));
		}
		return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
	}

	public static boolean looksLikeCode(String s) {
		return s != null && s.trim().startsWith(PREFIX);
	}

	/** Decodes a code and places the build. Finishes on the game thread. */
	public static CompletableFuture<Placement> importCode(String code) {
		JsonObject o;
		try {
			String body = code.trim().substring(PREFIX.length());
			byte[] gz = Base64.getUrlDecoder().decode(body);
			try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
				byte[] json = in.readNBytes(4 * 1024 * 1024);
				o = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
			}
		} catch (Exception e) {
			return CompletableFuture.failedFuture(new RuntimeException("That isn't a valid HoloPrint code"));
		}
		final JsonObject data = o;
		String fileName = OnlineLibrary.safeFileName(data.has("f") ? data.get("f").getAsString() : "shared.litematic");

		CompletableFuture<Path> fileFuture = CompletableFuture.supplyAsync(() -> {
			try {
				if (data.has("d")) {
					byte[] bytes = Base64.getDecoder().decode(data.get("d").getAsString());
					if (!com.holoprint.schematic.Nbt.looksLikeNbt(bytes) || !SchematicLoader.isSupported(Path.of(fileName))) {
						throw new RuntimeException("The code doesn't contain a valid schematic");
					}
					Path dir = HoloConfig.schematicsDir().resolve("HoloPrint Shared");
					Files.createDirectories(dir);
					Path target = dir.resolve(fileName);
					if (Files.exists(target) && !Arrays.equals(Files.readAllBytes(target), bytes)) {
						target = dir.resolve(System.currentTimeMillis() + " " + fileName);
					}
					if (!Files.exists(target)) Files.write(target, bytes);
					return target;
				}
				Path local = findLocal(fileName);
				if (local != null) return local;
				return null;
			} catch (Exception e) {
				throw new RuntimeException("Couldn't save the shared file: " + e.getMessage(), e);
			}
		}).thenCompose(found -> {
			if (found != null) return CompletableFuture.completedFuture(found);
			if (data.has("o")) {
				String[] parts = data.get("o").getAsString().split("/", 2);
				if (parts.length == 2) {
					OnlineLibrary.Post post = new OnlineLibrary.Post(parts[1], parts[0], fileName, "?", "", 0, List.of(), List.of(), null, null);
					return OnlineLibrary.downloadBest(post);
				}
			}
			return CompletableFuture.failedFuture(new RuntimeException("You need the file \"" + fileName + "\" in your schematics folder"));
		});

		CompletableFuture<Placement> result = new CompletableFuture<>();
		fileFuture.thenApply(path -> {
			try {
				return SchematicLoader.load(path);
			} catch (Exception e) {
				throw new RuntimeException("Couldn't read the shared schematic: " + e.getMessage(), e);
			}
		}).whenComplete((Schematic s, Throwable err) -> Minecraft.getInstance().execute(() -> {
			if (err != null) {
				Throwable c = err.getCause() != null ? err.getCause() : err;
				result.completeExceptionally(c);
				return;
			}
			try {
				Placement p = new Placement(s, new BlockPos(data.get("x").getAsInt(), data.get("y").getAsInt(), data.get("z").getAsInt()));
				p.rotation = data.has("r") ? data.get("r").getAsInt() & 3 : 0;
				p.mirror = data.has("m") ? Math.floorMod(data.get("m").getAsInt(), 3) : 0;
				p.rebuild();
				PlacementManager.add(p);
				result.complete(p);
			} catch (RuntimeException e) {
				result.completeExceptionally(e);
			}
		}));
		return result;
	}

	private static Path findLocal(String fileName) {
		Path root = HoloConfig.schematicsDir();
		if (!Files.isDirectory(root)) return null;
		try (Stream<Path> s = Files.walk(root, 6)) {
			return s.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().equalsIgnoreCase(fileName))
					.findFirst().orElse(null);
		} catch (Exception e) {
			return null;
		}
	}
}
