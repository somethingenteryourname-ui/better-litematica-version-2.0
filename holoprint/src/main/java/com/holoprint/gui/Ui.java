package com.holoprint.gui;

import com.holoprint.config.HoloConfig;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Drawing helpers for HoloPrint's own look (glass panels, glow lines, chips, buttons). */
public final class Ui {
	private Ui() {}

	public static final int BG = 0x0A0E16;
	public static final int BG2 = 0x121827;
	public static final int TEXT = 0xFFEAF2FF;
	public static final int MUTED = 0xFF8A97AD;
	public static final int DIM = 0xFF5B6679;

	public static int a(int rgb, int alpha) {
		return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
	}

	public static int accent() {
		return HoloConfig.get().accentColor & 0xFFFFFF;
	}

	public static int accent2() {
		return HoloConfig.get().accent2Color & 0xFFFFFF;
	}

	public static int lerp(int c1, int c2, float t) {
		int r = (int) (((c1 >> 16) & 0xFF) + (((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t);
		int g = (int) (((c1 >> 8) & 0xFF) + (((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * t);
		int b = (int) ((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t);
		return (r << 16) | (g << 8) | b;
	}

	/** Horizontal gradient bar from accent to accent2. */
	public static void hGradient(GuiGraphics g, int x, int y, int w, int h, int c1, int c2, int alpha) {
		int steps = Math.max(1, Math.min(24, w / 4));
		for (int i = 0; i < steps; i++) {
			int sx = x + w * i / steps, ex = x + w * (i + 1) / steps;
			g.fill(sx, y, ex, y + h, a(lerp(c1, c2, i / (float) Math.max(1, steps - 1)), alpha));
		}
	}

	/** Dark glass panel with a faint border and bright corner brackets. */
	public static void panel(GuiGraphics g, int x, int y, int w, int h) {
		HoloConfig cfg = HoloConfig.get();
		g.fill(x, y, x + w, y + h, a(BG, cfg.panelOpacity));
		int border = a(accent(), 0x38);
		g.fill(x, y, x + w, y + 1, border);
		g.fill(x, y + h - 1, x + w, y + h, border);
		g.fill(x, y, x + 1, y + h, border);
		g.fill(x + w - 1, y, x + w, y + h, border);
		brackets(g, x, y, w, h, a(accent(), 0xFF), 7);
	}

	public static void brackets(GuiGraphics g, int x, int y, int w, int h, int c, int len) {
		g.fill(x, y, x + len, y + 1, c);
		g.fill(x, y, x + 1, y + len, c);
		g.fill(x + w - len, y, x + w, y + 1, c);
		g.fill(x + w - 1, y, x + w, y + len, c);
		g.fill(x, y + h - 1, x + len, y + h, c);
		g.fill(x, y + h - len, x + 1, y + h, c);
		g.fill(x + w - len, y + h - 1, x + w, y + h, c);
		g.fill(x + w - 1, y + h - len, x + w, y + h, c);
	}

	/** Flat card used for list rows. */
	public static void card(GuiGraphics g, int x, int y, int w, int h, boolean hover, boolean selected) {
		int bg = selected ? a(lerp(BG2, accent(), 0.18f), 0xF0) : hover ? a(lerp(BG2, accent(), 0.08f), 0xE8) : a(BG2, 0xD8);
		g.fill(x, y, x + w, y + h, bg);
		if (selected || hover) g.fill(x, y, x + 2, y + h, a(accent(), selected ? 0xFF : 0x90));
	}

	/** A button. style 0 = outline, 1 = filled accent, 2 = danger. */
	public static void button(GuiGraphics g, Font font, int x, int y, int w, int h, String label, boolean hover, int style, boolean enabled) {
		int acc = style == 2 ? 0xFF3355 : accent();
		if (!enabled) {
			g.fill(x, y, x + w, y + h, a(BG2, 0xB0));
			centered(g, font, label, x + w / 2, y + (h - 8) / 2, DIM);
			return;
		}
		if (style == 1) {
			hGradient(g, x, y, w, h, acc, style == 1 ? accent2() : acc, hover ? 0xFF : 0xD8);
			centered(g, font, label, x + w / 2, y + (h - 8) / 2, 0xFF05070C);
		} else {
			g.fill(x, y, x + w, y + h, hover ? a(acc, 0x40) : a(BG2, 0xE0));
			g.fill(x, y + h - 1, x + w, y + h, a(acc, hover ? 0xFF : 0x80));
			centered(g, font, label, x + w / 2, y + (h - 8) / 2, hover ? 0xFFFFFFFF : TEXT);
		}
	}

	/** Small rounded-looking tag. */
	public static int chip(GuiGraphics g, Font font, int x, int y, String text, int color, boolean filled) {
		int w = font.width(text) + 8;
		g.fill(x, y, x + w, y + 11, filled ? a(color, 0xD0) : a(color, 0x28));
		g.fill(x, y + 10, x + w, y + 11, a(color, 0xA0));
		g.drawString(font, text, x + 4, y + 2, filled ? 0xFF05070C : a(color, 0xFF), false);
		return w;
	}

	public static void centered(GuiGraphics g, Font font, String text, int cx, int y, int color) {
		g.drawString(font, text, cx - font.width(text) / 2, y, color, false);
	}

	public static void toggle(GuiGraphics g, int x, int y, boolean on, boolean hover) {
		int w = 22, h = 11;
		g.fill(x, y, x + w, y + h, on ? a(accent(), hover ? 0xFF : 0xC8) : a(0x2A3346, hover ? 0xFF : 0xD0));
		int kx = on ? x + w - 10 : x + 1;
		g.fill(kx, y + 1, kx + 9, y + h - 1, on ? 0xFF05070C : 0xFF8A97AD);
	}

	public static String formatCount(long n) {
		if (n >= 1_000_000) return String.format(java.util.Locale.ROOT, "%.1fM", n / 1_000_000.0);
		if (n >= 10_000) return (n / 1000) + "k";
		if (n >= 1000) return String.format(java.util.Locale.ROOT, "%.1fk", n / 1000.0);
		return Long.toString(n);
	}
}
