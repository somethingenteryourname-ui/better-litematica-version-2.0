package com.holoprint.gui;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.holoprint.config.HoloConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Small slide-in notifications drawn by HoloPrint itself (on the HUD and in menus). */
public final class Toasts {
	private record Toast(String text, int color, long born) {}

	private static final List<Toast> toasts = new ArrayList<>();
	private static final long LIFE = 3500;

	private Toasts() {}

	public static synchronized void info(String text) {
		add(text, 0);
	}

	public static synchronized void success(String text) {
		add(text, 0x2BFF88);
	}

	public static synchronized void error(String text) {
		add(text, 0xFF3355);
	}

	private static void add(String text, int color) {
		toasts.add(new Toast(text, color, System.currentTimeMillis()));
		while (toasts.size() > 5) toasts.remove(0);
	}

	public static synchronized void render(GuiGraphics g, int screenWidth) {
		if (toasts.isEmpty()) return;
		Font font = Minecraft.getInstance().font;
		long now = System.currentTimeMillis();
		int y = 8;
		Iterator<Toast> it = toasts.iterator();
		while (it.hasNext()) {
			Toast t = it.next();
			long age = now - t.born;
			if (age > LIFE) {
				it.remove();
				continue;
			}
			float in = Math.min(1f, age / 180f);
			float out = Math.min(1f, (LIFE - age) / 300f);
			float a = Math.min(in, out);
			int accent = t.color != 0 ? t.color : HoloConfig.get().accentColor;
			int w = font.width(t.text) + 20;
			int x = screenWidth - (int) (w * Math.min(1f, in * 1.2f)) - 8;
			int alpha = (int) (220 * a);
			g.fill(x, y, x + w, y + 18, (alpha << 24) | 0x0B0F17);
			g.fill(x, y, x + 2, y + 18, ((int) (255 * a) << 24) | accent);
			g.fill(x, y + 17, x + (int) (w * (1f - age / (float) LIFE)), y + 18, ((int) (160 * a) << 24) | accent);
			g.drawString(font, t.text, x + 10, y + 5, ((int) (255 * a) << 24) | 0xFFFFFF, false);
			y += 22;
		}
	}
}
