package com.holoprint.render;

import org.joml.Matrix4f;

import com.holoprint.HoloPrintClient;
import com.holoprint.config.HoloConfig;
import com.holoprint.placement.Aim;
import com.holoprint.placement.Selection;
import com.holoprint.placement.Placement;
import com.holoprint.placement.PlacementManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the holographic blueprint. Only faces that touch the outside of the
 * build are drawn, blocks that are already placed correctly are hidden, and
 * anything past the render radius is skipped, which keeps it fast.
 */
public final class HologramRenderer {
	private HologramRenderer() {}

	private static final int MAX_QUADS = 160_000;
	private static final int FULL_BRIGHT = 15728880;
	private static final float INSET = 0.012f;
	private static final float EDGE = 0.055f;

	private static int quads;

	public static void render(WorldRenderContext ctx) {
		HoloConfig cfg = HoloConfig.get();
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;

		Vec3 cam = ctx.worldState().cameraRenderState.pos;
		PoseStack ps = ctx.matrices();
		quads = 0;
		float time = (System.currentTimeMillis() % 100_000L) / 1000f;
		Placement sel = PlacementManager.selected();

		VertexConsumer vc = ctx.consumers().getBuffer(RenderTypes.debugFilledBox());
		Matrix4f m = ps.last().pose();

		drawSelection(vc, m, cam, cfg, time);
		if (HoloPrintClient.nextChest != null && cfg.showChestRoute) {
			BlockPos c = HoloPrintClient.nextChest;
			float pulse = 0.6f + 0.4f * (float) Math.sin(time * 4);
			outline(vc, m, c.getX() - 0.03f, c.getY() - 0.03f, c.getZ() - 0.03f, 1.06f, 1.06f, 1.06f, cam,
					((int) (0xD0 * pulse) << 24) | (cfg.accent2Color & 0xFFFFFF), 0.05f);
		}

		if (!cfg.hologramVisible || PlacementManager.all().isEmpty()) return;

		for (Placement p : PlacementManager.all()) {
			if (!p.visible) continue;
			drawBounds(vc, m, p, cam, p == sel, time, cfg);
			if (HoloPrintClient.afterViewActive && p == sel) {
				drawRealBlocks(ctx, p, cam, cfg, true);
				vc = ctx.consumers().getBuffer(RenderTypes.debugFilledBox());
			} else if (cfg.renderMode == 1) {
				drawRealBlocks(ctx, p, cam, cfg, false);
				vc = ctx.consumers().getBuffer(RenderTypes.debugFilledBox());
			} else {
				drawHoloBlocks(vc, m, p, cam, time, cfg);
			}
		}

		Aim.Target t = HoloPrintClient.lookTarget;
		if (t != null && !HoloPrintClient.afterViewActive) {
			int c = (0xF0 << 24) | 0xFFFFFF;
			outline(vc, m, t.placement().wx[t.index()] - 0.01f, t.placement().wy[t.index()] - 0.01f, t.placement().wz[t.index()] - 0.01f,
					1.02f, 1.02f, 1.02f, cam, c, 0.03f);
		}
	}

	/** The save-area box (Create page), with a marker on each corner. */
	private static void drawSelection(VertexConsumer vc, Matrix4f m, Vec3 cam, HoloConfig cfg, float time) {
		BlockPos a = Selection.corner1, b = Selection.corner2;
		if (a == null && b == null) return;
		int col = (0xE0 << 24) | 0xFFD23F;
		if (a != null) outline(vc, m, a.getX(), a.getY(), a.getZ(), 1, 1, 1, cam, (0xFF << 24) | 0x2BFF88, 0.06f);
		if (b != null) outline(vc, m, b.getX(), b.getY(), b.getZ(), 1, 1, 1, cam, (0xFF << 24) | 0xFF5E3A, 0.06f);
		if (a != null && b != null) {
			int x0 = Math.min(a.getX(), b.getX()), y0 = Math.min(a.getY(), b.getY()), z0 = Math.min(a.getZ(), b.getZ());
			int x1 = Math.max(a.getX(), b.getX()) + 1, y1 = Math.max(a.getY(), b.getY()) + 1, z1 = Math.max(a.getZ(), b.getZ()) + 1;
			outline(vc, m, x0 - 0.02f, y0 - 0.02f, z0 - 0.02f, x1 - x0 + 0.04f, y1 - y0 + 0.04f, z1 - z0 + 0.04f, cam, col, 0.07f);
			if (cfg.pulse) {
				float h = y1 - y0;
				float sy = (float) (y0 + (time * 0.4f % 1f) * h - cam.y);
				int sc = (0x26 << 24) | 0xFFD23F;
				face(vc, m, 1, (float) (x0 - cam.x), sy - 0.02f, (float) (z0 - cam.z), (float) (x1 - cam.x), sy, (float) (z1 - cam.z), sc);
				face(vc, m, 0, (float) (x0 - cam.x), sy - 0.02f, (float) (z0 - cam.z), (float) (x1 - cam.x), sy, (float) (z1 - cam.z), sc);
			}
		}
	}

	/** 12-edge wire box in world space. */
	private static void outline(VertexConsumer vc, Matrix4f m, float wx, float wy, float wz, float sx, float sy, float sz, Vec3 cam, int c, float t) {
		float x0 = (float) (wx - cam.x), y0 = (float) (wy - cam.y), z0 = (float) (wz - cam.z);
		float x1 = x0 + sx, y1 = y0 + sy, z1 = z0 + sz;
		edgeBox(vc, m, x0, y0, z0, x1, y0 + t, z0 + t, c);
		edgeBox(vc, m, x0, y1 - t, z0, x1, y1, z0 + t, c);
		edgeBox(vc, m, x0, y0, z1 - t, x1, y0 + t, z1, c);
		edgeBox(vc, m, x0, y1 - t, z1 - t, x1, y1, z1, c);
		edgeBox(vc, m, x0, y0, z0, x0 + t, y1, z0 + t, c);
		edgeBox(vc, m, x1 - t, y0, z0, x1, y1, z0 + t, c);
		edgeBox(vc, m, x0, y0, z1 - t, x0 + t, y1, z1, c);
		edgeBox(vc, m, x1 - t, y0, z1 - t, x1, y1, z1, c);
		edgeBox(vc, m, x0, y0, z0, x0 + t, y0 + t, z1, c);
		edgeBox(vc, m, x1 - t, y0, z0, x1, y0 + t, z1, c);
		edgeBox(vc, m, x0, y1 - t, z0, x0 + t, y1, z1, c);
		edgeBox(vc, m, x1 - t, y1 - t, z0, x1, y1, z1, c);
	}

	private static void drawHoloBlocks(VertexConsumer vc, Matrix4f m, Placement p, Vec3 cam, float time, HoloConfig cfg) {
		double r = cfg.renderRadius;
		double r2 = r * r;
		int baseAlpha = cfg.hologramAlpha;
		boolean edges = cfg.outlineEdges;
		boolean pulse = cfg.pulse;

		for (int i = 0; i < p.count; i++) {
			byte st = p.status[i];
			if (st == Placement.CORRECT) continue;
			if (!p.layerVisible(p.localY[i])) continue;
			int color;
			switch (st) {
				case Placement.WRONG_BLOCK -> { if (!cfg.showWrongBlocks) continue; color = cfg.colorWrongBlock; }
				case Placement.WRONG_STATE -> { if (!cfg.showWrongBlocks) continue; color = cfg.colorWrongState; }
				default -> color = cfg.colorMissing;
			}
			double dx = p.wx[i] + 0.5 - cam.x, dy = p.wy[i] + 0.5 - cam.y, dz = p.wz[i] + 0.5 - cam.z;
			if (dx * dx + dy * dy + dz * dz > r2) continue;
			if (quads > MAX_QUADS) return;

			int faces = p.faces[i];
			// wrong blocks sit inside a real block, so show all their faces
			if (st == Placement.WRONG_BLOCK || st == Placement.WRONG_STATE) faces = 63;
			if (faces == 0) continue;

			float a = baseAlpha;
			if (pulse) a *= 0.72f + 0.28f * (float) Math.sin(time * 2.6f + p.wy[i] * 0.45f);
			int fillA = clamp((int) a);
			int edgeA = clamp((int) (a * 2.2f + 40));
			int fill = (fillA << 24) | (color & 0xFFFFFF);
			int edge = (edgeA << 24) | brighten(color);

			float x0 = (float) (p.wx[i] - cam.x) + INSET, y0 = (float) (p.wy[i] - cam.y) + INSET, z0 = (float) (p.wz[i] - cam.z) + INSET;
			float x1 = x0 + 1 - 2 * INSET, y1 = y0 + 1 - 2 * INSET, z1 = z0 + 1 - 2 * INSET;
			for (int f = 0; f < 6; f++) {
				if ((faces & (1 << f)) == 0) continue;
				face(vc, m, f, x0, y0, z0, x1, y1, z1, fill);
				if (edges) faceBorder(vc, m, f, x0, y0, z0, x1, y1, z1, edge);
			}
		}
	}

	/**
	 * Draws real block models. In "finished build" mode every block still to be
	 * placed is drawn full size so you see the end result; otherwise missing
	 * blocks are drawn slightly smaller so they read as a preview.
	 */
	private static void drawRealBlocks(WorldRenderContext ctx, Placement p, Vec3 cam, HoloConfig cfg, boolean finished) {
		Minecraft mc = Minecraft.getInstance();
		PoseStack ps = ctx.matrices();
		double r = Math.min(cfg.renderRadius, finished ? 64 : 32);
		double r2 = r * r;
		int limit = finished ? 16000 : 6000;
		int drawn = 0;
		for (int i = 0; i < p.count; i++) {
			byte st = p.status[i];
			if (st == Placement.CORRECT) continue;
			if (!finished && st != Placement.MISSING && st != Placement.UNKNOWN) continue;
			if (!p.layerVisible(p.localY[i]) || p.faces[i] == 0) continue;
			double dx = p.wx[i] + 0.5 - cam.x, dy = p.wy[i] + 0.5 - cam.y, dz = p.wz[i] + 0.5 - cam.z;
			if (dx * dx + dy * dy + dz * dz > r2) continue;
			if (++drawn > limit) break;
			ps.pushPose();
			if (finished) {
				// wrong blocks get drawn a hair bigger so they cover the real one
				float grow = st == Placement.MISSING || st == Placement.UNKNOWN ? 0f : 0.004f;
				ps.translate(p.wx[i] - cam.x - grow, p.wy[i] - cam.y - grow, p.wz[i] - cam.z - grow);
				ps.scale(1 + grow * 2, 1 + grow * 2, 1 + grow * 2);
			} else {
				ps.translate(p.wx[i] - cam.x + 0.1, p.wy[i] - cam.y + 0.1, p.wz[i] - cam.z + 0.1);
				ps.scale(0.8f, 0.8f, 0.8f);
			}
			try {
				mc.getBlockRenderer().renderSingleBlock(p.state[i], ps, ctx.consumers(), FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
			} catch (RuntimeException ignored) {
				// some blocks (e.g. block entities) can't be drawn this way; skip them
			}
			ps.popPose();
		}
	}

	/** Bounding box frame, plus a scan plane sweeping up through the selected build. */
	private static void drawBounds(VertexConsumer vc, Matrix4f m, Placement p, Vec3 cam, boolean selected, float time, HoloConfig cfg) {
		float x0 = (float) (p.minX - cam.x), y0 = (float) (p.minY - cam.y), z0 = (float) (p.minZ - cam.z);
		float x1 = (float) (p.maxX - cam.x), y1 = (float) (p.maxY - cam.y), z1 = (float) (p.maxZ - cam.z);
		int color = selected ? cfg.accentColor : cfg.accent2Color;
		int a = selected ? 0xE0 : 0x80;
		int c = (a << 24) | (color & 0xFFFFFF);
		float t = selected ? 0.08f : 0.05f;
		// 12 edges as thin boxes
		edgeBox(vc, m, x0, y0, z0, x1, y0 + t, z0 + t, c);
		edgeBox(vc, m, x0, y1 - t, z0, x1, y1, z0 + t, c);
		edgeBox(vc, m, x0, y0, z1 - t, x1, y0 + t, z1, c);
		edgeBox(vc, m, x0, y1 - t, z1 - t, x1, y1, z1, c);
		edgeBox(vc, m, x0, y0, z0, x0 + t, y1, z0 + t, c);
		edgeBox(vc, m, x1 - t, y0, z0, x1, y1, z0 + t, c);
		edgeBox(vc, m, x0, y0, z1 - t, x0 + t, y1, z1, c);
		edgeBox(vc, m, x1 - t, y0, z1 - t, x1, y1, z1, c);
		edgeBox(vc, m, x0, y0, z0, x0 + t, y0 + t, z1, c);
		edgeBox(vc, m, x1 - t, y0, z0, x1, y0 + t, z1, c);
		edgeBox(vc, m, x0, y1 - t, z0, x0 + t, y1, z1, c);
		edgeBox(vc, m, x1 - t, y1 - t, z0, x1, y1, z1, c);

		if (selected && cfg.pulse) {
			float h = y1 - y0;
			float sy = y0 + (time * 0.35f % 1f) * h;
			int sc = (0x30 << 24) | (cfg.accentColor & 0xFFFFFF);
			face(vc, m, 1, x0, sy - 0.02f, z0, x1, sy, z1, sc);
			face(vc, m, 0, x0, sy - 0.02f, z0, x1, sy, z1, sc);
		}
		// current layer marker
		if (selected && p.layerMode != 0) {
			float ly = (float) (p.minY + p.layer - cam.y);
			int lc = (0x22 << 24) | (cfg.accent2Color & 0xFFFFFF);
			face(vc, m, 0, x0, ly, z0, x1, ly + 0.01f, z1, lc);
		}
	}

	private static void edgeBox(VertexConsumer vc, Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1, int c) {
		for (int f = 0; f < 6; f++) face(vc, m, f, x0, y0, z0, x1, y1, z1, c);
	}

	/** The four thin strips around the edge of one face. */
	private static void faceBorder(VertexConsumer vc, Matrix4f m, int f, float x0, float y0, float z0, float x1, float y1, float z1, int c) {
		float e = EDGE, o = 0.002f;
		switch (f) {
			case 0, 1 -> { // bottom / top: strips along x and z, in the face plane
				float y = f == 0 ? y0 - o : y1 + o;
				float ya = f == 0 ? y : y - 0.0001f, yb = f == 0 ? y + 0.0001f : y;
				face(vc, m, f, x0, ya, z0, x1, yb, z0 + e, c);
				face(vc, m, f, x0, ya, z1 - e, x1, yb, z1, c);
				face(vc, m, f, x0, ya, z0 + e, x0 + e, yb, z1 - e, c);
				face(vc, m, f, x1 - e, ya, z0 + e, x1, yb, z1 - e, c);
			}
			case 2, 3 -> { // north / south
				float z = f == 2 ? z0 - o : z1 + o;
				face(vc, m, f, x0, y0, z, x1, y0 + e, z, c);
				face(vc, m, f, x0, y1 - e, z, x1, y1, z, c);
				face(vc, m, f, x0, y0 + e, z, x0 + e, y1 - e, z, c);
				face(vc, m, f, x1 - e, y0 + e, z, x1, y1 - e, z, c);
			}
			default -> { // west / east
				float x = f == 4 ? x0 - o : x1 + o;
				face(vc, m, f, x, y0, z0, x, y0 + e, z1, c);
				face(vc, m, f, x, y1 - e, z0, x, y1, z1, c);
				face(vc, m, f, x, y0 + e, z0, x, y1 - e, z0 + e, c);
				face(vc, m, f, x, y0 + e, z1 - e, x, y1 - e, z1, c);
			}
		}
	}

	/**
	 * One face of a box, using the same vertex order as Fabric's own filled-box
	 * test renderer. f: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east.
	 */
	private static void face(VertexConsumer vc, Matrix4f m, int f, float x0, float y0, float z0, float x1, float y1, float z1, int c) {
		quads++;
		switch (f) {
			case 0 -> {
				vc.addVertex(m, x0, y0, z1).setColor(c);
				vc.addVertex(m, x1, y0, z1).setColor(c);
				vc.addVertex(m, x1, y0, z0).setColor(c);
				vc.addVertex(m, x0, y0, z0).setColor(c);
			}
			case 1 -> {
				vc.addVertex(m, x0, y1, z0).setColor(c);
				vc.addVertex(m, x1, y1, z0).setColor(c);
				vc.addVertex(m, x1, y1, z1).setColor(c);
				vc.addVertex(m, x0, y1, z1).setColor(c);
			}
			case 2 -> {
				vc.addVertex(m, x0, y0, z0).setColor(c);
				vc.addVertex(m, x1, y0, z0).setColor(c);
				vc.addVertex(m, x1, y1, z0).setColor(c);
				vc.addVertex(m, x0, y1, z0).setColor(c);
			}
			case 3 -> {
				vc.addVertex(m, x1, y0, z1).setColor(c);
				vc.addVertex(m, x0, y0, z1).setColor(c);
				vc.addVertex(m, x0, y1, z1).setColor(c);
				vc.addVertex(m, x1, y1, z1).setColor(c);
			}
			case 4 -> {
				vc.addVertex(m, x0, y0, z1).setColor(c);
				vc.addVertex(m, x0, y0, z0).setColor(c);
				vc.addVertex(m, x0, y1, z0).setColor(c);
				vc.addVertex(m, x0, y1, z1).setColor(c);
			}
			default -> {
				vc.addVertex(m, x1, y0, z0).setColor(c);
				vc.addVertex(m, x1, y0, z1).setColor(c);
				vc.addVertex(m, x1, y1, z1).setColor(c);
				vc.addVertex(m, x1, y1, z0).setColor(c);
			}
		}
	}

	private static int clamp(int a) {
		return Math.max(0, Math.min(255, a));
	}

	private static int brighten(int rgb) {
		int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
		r = r + (255 - r) / 3; g = g + (255 - g) / 3; b = b + (255 - b) / 3;
		return (r << 16) | (g << 8) | b;
	}
}
