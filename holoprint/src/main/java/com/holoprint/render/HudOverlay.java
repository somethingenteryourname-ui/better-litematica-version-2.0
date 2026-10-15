package com.holoprint.render;

import java.util.HashMap;
import java.util.Map;

import com.holoprint.HoloPrintClient;
import com.holoprint.config.HoloConfig;
import com.holoprint.gui.Toasts;
import com.holoprint.placement.Aim;
import com.holoprint.placement.Placement;
import com.holoprint.placement.PlacementManager;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

/** Status card: active build, progress, layer, aimed block and next chest. */
public final class HudOverlay {
	private HudOverlay() {}

	public static void render(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		int sw = g.guiWidth(), sh = g.guiHeight();
		if (mc.screen == null) Toasts.render(g, sw);

		HoloConfig cfg = HoloConfig.get();
		if (mc.options.hideGui) return;
		renderModeBanner(g, mc, sw);

		Placement p = PlacementManager.selected();
		if (!cfg.hudEnabled || p == null) return;

		Font font = mc.font;
		Aim.Target target = cfg.hudShowTarget ? HoloPrintClient.lookTarget : null;
		boolean chest = HoloPrintClient.nextChest != null;
		int w = 160;
		int h = 46 + (target != null ? 20 : 0) + (chest ? 11 : 0);
		int pad = 6;
		int x = (cfg.hudCorner & 1) == 0 ? pad : sw - w - pad;
		int y = cfg.hudCorner < 2 ? pad : sh - h - pad;
		int accent = cfg.accentColor & 0xFFFFFF;

		g.fill(x, y, x + w, y + h, 0xB00A0E16);
		g.fill(x, y, x + w, y + 1, 0xFF000000 | accent);
		g.fill(x, y, x + 1, y + 8, 0xFF000000 | accent);
		g.fill(x + w - 1, y, x + w, y + 8, 0xFF000000 | accent);

		g.drawString(font, font.plainSubstrByWidth(p.name(), w - 12), x + 6, y + 5, 0xFFFFFFFF, false);

		float prog = p.progress();
		int barW = w - 12;
		g.fill(x + 6, y + 18, x + 6 + barW, y + 22, 0xFF1C2333);
		g.fill(x + 6, y + 18, x + 6 + (int) (barW * prog), y + 22, 0xFF000000 | accent);
		g.drawString(font, Math.round(prog * 100) + "%  " + p.statCorrect + "/" + p.count, x + 6, y + 25, 0xFFB8C4D9, false);

		String line2 = p.layerMode == 0 ? "All layers" : "Layer " + (p.layer + 1) + "/" + p.schematic.sizeY;
		if (!p.visible) line2 = "Hidden";
		int wrong = p.statWrong + p.statWrongState;
		g.drawString(font, line2, x + 6, y + 35, 0xFF8A97AD, false);
		if (wrong > 0) {
			String ws = wrong + " wrong";
			g.drawString(font, ws, x + w - 6 - font.width(ws), y + 35, 0xFF000000 | cfg.colorWrongBlock, false);
		}
		int ly = y + 47;
		if (target != null) {
			Block block = target.state().getBlock();
			Item item = block.asItem();
			if (item != Items.AIR) g.renderItem(new ItemStack(item), x + 4, ly);
			String what = switch (target.status()) {
				case Placement.WRONG_BLOCK -> "Replace with";
				case Placement.WRONG_STATE -> "Turn to match";
				default -> "Place";
			};
			g.drawString(font, what, x + 23, ly + 1, 0xFF8A97AD, false);
			g.drawString(font, font.plainSubstrByWidth(block.getName().getString(), w - 30), x + 23, ly + 10, 0xFFFFFFFF, false);
			ly += 20;
		}
		if (chest) {
			var c = HoloPrintClient.nextChest;
			g.drawString(font, "Next chest: " + c.getX() + " " + c.getY() + " " + c.getZ(), x + 6, ly, 0xFF000000 | (cfg.accent2Color & 0xFFFFFF), false);
		}
	}

	private static void renderModeBanner(GuiGraphics g, Minecraft mc, int sw) {
		String text = null;
		if (HoloPrintClient.grabbing) text = "GRAB MODE  ·  aim to move  ·  scroll = distance";
		else if (HoloPrintClient.afterViewActive) text = "FINISHED BUILD PREVIEW";
		if (text == null || mc.screen != null) return;
		int w = mc.font.width(text) + 16;
		int x = (sw - w) / 2, y = 30;
		g.fill(x, y, x + w, y + 14, 0xC00A0E16);
		g.fill(x, y + 13, x + w, y + 14, 0xFF000000 | (HoloConfig.get().accentColor & 0xFFFFFF));
		g.drawString(mc.font, text, x + 8, y + 3, 0xFFFFFFFF, false);
	}

	/** Item -> how many more you still need beyond what you carry. */
	public static Map<Item, Integer> stillNeeded(Placement p) {
		Map<Item, Integer> need = new HashMap<>();
		for (Map.Entry<Block, int[]> e : p.materials().entrySet()) {
			Item it = e.getKey().asItem();
			if (it == Items.AIR || e.getValue()[1] <= 0) continue;
			need.merge(it, e.getValue()[1], Integer::sum);
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			Inventory inv = mc.player.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack st = inv.getItem(i);
				if (st.isEmpty()) continue;
				need.computeIfPresent(st.getItem(), (k, v) -> v - st.getCount() > 0 ? v - st.getCount() : null);
			}
		}
		return need;
	}
}
