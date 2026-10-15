package com.holoprint.gui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.lwjgl.glfw.GLFW;

import com.holoprint.HoloPrintClient;
import com.holoprint.config.HoloConfig;
import com.holoprint.online.DownloadIndex;
import com.holoprint.online.OnlineLibrary;
import com.holoprint.online.ThumbCache;
import com.holoprint.placement.ChestMemory;
import com.holoprint.placement.Placement;
import com.holoprint.placement.PlacementManager;
import com.holoprint.placement.Selection;
import com.holoprint.placement.ShareCodes;
import com.holoprint.render.HudOverlay;
import com.holoprint.schematic.LibraryData;
import com.holoprint.schematic.LocalLibrary;
import com.holoprint.schematic.Schematic;
import com.holoprint.schematic.SchematicLoader;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

/**
 * HoloPrint's main menu. Everything is drawn by hand ("immediate mode"): each
 * frame we draw the UI and remember clickable areas, then clicks are matched
 * against those areas.
 */
public final class HoloScreen extends Screen {
	private enum Tab {
		LIBRARY("Library", "Your schematics"),
		BROWSE("Browse", "Find builds online"),
		BUILDS("Builds", "Placed holograms"),
		CREATE("Create", "Save your own builds"),
		MATERIALS("Materials", "What you still need"),
		SETTINGS("Settings", "Make it yours"),
		ABOUT("About", "Credits & keys");

		final String label, sub;

		Tab(String label, String sub) {
			this.label = label;
			this.sub = sub;
		}
	}

	// remembered between openings
	private static Tab lastTab = Tab.LIBRARY;
	private static String libraryFilter = "";
	private static String onlineQuery = "";
	private static String saveName = "My build";
	private static String libraryGroup = ""; // "" all, "*" favourites, else collection name
	private static List<LocalLibrary.Entry> libraryCache = null;
	private static OnlineLibrary.Page lastPage = null;
	private static int onlinePage = 1;
	private static Path selectedFile;

	private static final String[] CATEGORIES = {"Small Base", "Starter House", "Modern House", "Medieval", "Castle",
			"Farm", "Redstone", "Storage", "Statue", "Ship", "Tree", "Bridge", "Village", "Tower"};
	private static final String[] VERSIONS = {"", "1.21", "1.20", "1.19", "1.18", "1.17", "1.16"};
	private static final int[] SWATCHES = {0x29D8FF, 0x00E5FF, 0x2BFF88, 0xA8FF3E, 0xFFE14D, 0xFFA020, 0xFF3355,
			0xFF40E0, 0xB45CFF, 0x5B8CFF, 0xFFFFFF, 0x7A7A7A};

	private record Hit(int x, int y, int w, int h, Runnable action) {
		boolean contains(double mx, double my) {
			return mx >= x && my >= y && mx < x + w && my < y + h;
		}
	}

	private final List<Hit> hits = new ArrayList<>();
	private final Map<Tab, Double> scroll = new HashMap<>();
	private Tab tab = lastTab;
	private int listX, listY, listW, listH, listContentH;
	private boolean inList; // when true, clickable areas are clipped to the list
	private long openedAt;
	private boolean shiftDown;

	private EditBox libraryBox, searchBox, accentBox, accent2Box, collectionBox, saveBox;
	private boolean scanning;
	private boolean searching;
	private boolean saving;
	private String searchError;
	private int expandedPost = -1;
	private final Map<String, String> downloadState = new HashMap<>(); // key -> "…", "ok", or error

	// 3D preview
	private int p3x, p3y, p3w, p3h;
	private boolean dragging3d;
	private float dragYaw, dragPitch;
	private long lastDragTime;
	private long[] sortKeys = new long[0];
	private float[] projX = new float[0], projY = new float[0], projD = new float[0];

	// layout
	private int px, py, pw, ph, side, cx, cy, cw, ch;

	public HoloScreen() {
		super(Component.literal("HoloPrint"));
	}

	@Override
	protected void init() {
		openedAt = System.currentTimeMillis();
		int margin = Math.max(6, Math.min(28, width / 24));
		px = margin;
		py = margin;
		pw = width - margin * 2;
		ph = height - margin * 2;
		side = Math.max(86, Math.min(118, pw / 6));
		cx = px + side + 10;
		cy = py + 34;
		cw = pw - side - 20;
		ch = ph - 44;

		libraryBox = field(cx + 8, cy + 5, Math.min(200, cw - 140), libraryFilter, "Filter your schematics…", 128);
		libraryBox.setResponder(s -> libraryFilter = s);
		searchBox = field(cx + 8, cy + 5, Math.max(70, cw - 250), onlineQuery, "Search online (e.g. small base)…", 128);
		searchBox.setResponder(s -> onlineQuery = s);
		accentBox = field(0, 0, 54, hex(HoloConfig.get().accentColor), "", 7);
		accent2Box = field(0, 0, 54, hex(HoloConfig.get().accent2Color), "", 7);
		accentBox.setResponder(s -> parseHex(s, true));
		accent2Box.setResponder(s -> parseHex(s, false));
		collectionBox = field(0, 0, 80, "", "New collection", 32);
		saveBox = field(0, 0, 160, saveName, "Build name", 64);
		saveBox.setResponder(s -> saveName = s);
		updateWidgetVisibility();

		if (libraryCache == null) rescan();
	}

	private EditBox field(int x, int y, int w, String value, String hint, int max) {
		EditBox b = new EditBox(font, x, y, w, 12, Component.empty());
		b.setMaxLength(max);
		b.setBordered(false);
		b.setTextColor(0xFFFFFFFF);
		b.setValue(value);
		if (!hint.isEmpty()) b.setHint(Component.literal(hint));
		addRenderableWidget(b);
		return b;
	}

	private void updateWidgetVisibility() {
		libraryBox.visible = tab == Tab.LIBRARY;
		searchBox.visible = tab == Tab.BROWSE;
		accentBox.visible = tab == Tab.SETTINGS;
		accent2Box.visible = tab == Tab.SETTINGS;
		collectionBox.visible = false; // shown by the library preview when there's room
		saveBox.visible = tab == Tab.CREATE;
		for (EditBox b : new EditBox[]{libraryBox, searchBox, accentBox, accent2Box, collectionBox, saveBox}) {
			if (!b.visible && getFocused() == b) setFocused(null);
		}
	}

	private void switchTab(Tab t) {
		tab = t;
		lastTab = t;
		updateWidgetVisibility();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void removed() {
		HoloConfig.get().save();
		super.removed();
	}

	// =====================================================================
	// rendering
	// =====================================================================

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
		HoloConfig cfg = HoloConfig.get();
		g.fillGradient(0, 0, width, height, Ui.a(0x05070C, 0xB8), Ui.a(Ui.lerp(0x05070C, cfg.accent2Color, 0.15f), 0xC8));
		if (cfg.animatedUi) {
			int acc = Ui.accent();
			long t = System.currentTimeMillis();
			int gridAlpha = 0x0C;
			for (int x = (int) ((t / 80) % 32); x < width; x += 32) g.fill(x, 0, x + 1, height, Ui.a(acc, gridAlpha));
			for (int y = 0; y < height; y += 32) g.fill(0, y, width, y + 1, Ui.a(acc, gridAlpha));
			int scan = (int) ((t / 12) % (height + 60)) - 30;
			g.fillGradient(0, scan - 30, width, scan, 0x00000000, Ui.a(acc, 0x14));
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		hits.clear();
		inList = false;
		HoloConfig cfg = HoloConfig.get();
		float intro = cfg.animatedUi ? Math.min(1f, (System.currentTimeMillis() - openedAt) / 220f) : 1f;
		int slide = (int) ((1f - intro) * 12);

		Ui.panel(g, px, py + slide, pw, ph);
		renderSidebar(g, mouseX, mouseY, slide);
		renderHeader(g, slide);
		collectionBox.visible = false;

		switch (tab) {
			case LIBRARY -> renderLibrary(g, mouseX, mouseY);
			case BROWSE -> renderBrowse(g, mouseX, mouseY);
			case BUILDS -> renderBuilds(g, mouseX, mouseY);
			case CREATE -> renderCreate(g, mouseX, mouseY);
			case MATERIALS -> renderMaterials(g, mouseX, mouseY);
			case SETTINGS -> renderSettings(g, mouseX, mouseY);
			case ABOUT -> renderAbout(g, mouseX, mouseY);
		}
		if (!collectionBox.visible && getFocused() == collectionBox) setFocused(null);

		super.render(g, mouseX, mouseY, delta); // text boxes
		renderFooter(g);
		Toasts.render(g, width);
	}

	private void renderSidebar(GuiGraphics g, int mx, int my, int slide) {
		int x = px + 1, y = py + 1 + slide, w = side, h = ph - 2;
		g.fill(x, y, x + w, y + h, Ui.a(0x070A11, 0xC0));
		g.fill(x + w, y + 8, x + w + 1, y + h - 8, Ui.a(Ui.accent(), 0x30));

		long t = System.currentTimeMillis();
		float glow = HoloConfig.get().animatedUi ? 0.6f + 0.4f * (float) Math.sin(t / 420.0) : 1f;
		int lx = drawLogo(g, x + 10, y + 12);
		Ui.hGradient(g, x + 10, y + 23, lx - x - 11, 1, Ui.accent(), Ui.accent2(), (int) (0xFF * glow));
		g.drawString(font, "blueprints, upgraded", x + 10, y + 27, Ui.DIM, false);

		Tab[] tabs = Tab.values();
		int tabH = Math.max(16, Math.min(28, (h - 46 - 50) / tabs.length));
		boolean showSub = tabH >= 24;
		int ty = y + 42;
		for (Tab t2 : tabs) {
			boolean sel = t2 == tab;
			boolean hover = mx >= x && mx < x + w && my >= ty && my < ty + tabH - 2;
			if (sel) {
				Ui.hGradient(g, x, ty, w, tabH - 2, Ui.accent(), Ui.BG, 0x55);
				g.fill(x, ty, x + 3, ty + tabH - 2, Ui.a(Ui.accent(), 0xFF));
			} else if (hover) {
				g.fill(x, ty, x + w, ty + tabH - 2, Ui.a(Ui.accent(), 0x18));
			}
			int textY = showSub ? ty + 4 : ty + (tabH - 2 - 8) / 2;
			g.drawString(font, t2.label, x + 12, textY, sel ? 0xFFFFFFFF : hover ? Ui.TEXT : Ui.MUTED, false);
			if (showSub) g.drawString(font, font.plainSubstrByWidth(t2.sub, w - 16), x + 12, ty + 14, sel ? Ui.a(Ui.accent(), 0xE0) : Ui.DIM, false);
			final Tab target = t2;
			hits.add(new Hit(x, ty, w, tabH - 2, () -> switchTab(target)));
			ty += tabH;
		}

		Placement p = PlacementManager.selected();
		if (p != null && y + h - 44 > ty + 4) {
			int by = y + h - 44;
			g.drawString(font, "ACTIVE", x + 10, by, Ui.DIM, false);
			g.drawString(font, font.plainSubstrByWidth(p.name(), w - 20), x + 10, by + 10, Ui.TEXT, false);
			g.fill(x + 10, by + 22, x + w - 10, by + 25, 0xFF1C2333);
			g.fill(x + 10, by + 22, x + 10 + (int) ((w - 20) * p.progress()), by + 25, Ui.a(Ui.accent(), 0xFF));
			g.drawString(font, Math.round(p.progress() * 100) + "% built", x + 10, by + 28, Ui.MUTED, false);
		}
	}

	/** Gradient "HOLOPRINT" letters. Returns the x where the logo ends. */
	private int drawLogo(GuiGraphics g, int x, int y) {
		String logo = "HOLOPRINT";
		int lx = x;
		for (int i = 0; i < logo.length(); i++) {
			String c = String.valueOf(logo.charAt(i));
			int col = Ui.lerp(Ui.accent(), Ui.accent2(), i / (float) (logo.length() - 1));
			g.drawString(font, c, lx, y, Ui.a(col, 0xFF), true);
			lx += font.width(c) + 1;
		}
		return lx;
	}

	private void renderHeader(GuiGraphics g, int slide) {
		int x = cx, y = py + 10 + slide;
		g.drawString(font, tab.label.toUpperCase(Locale.ROOT), x, y, 0xFFFFFFFF, false);
		int lw = font.width(tab.label.toUpperCase(Locale.ROOT));
		g.drawString(font, "/ " + tab.sub, x + lw + 6, y, Ui.DIM, false);
		Ui.hGradient(g, x, y + 13, Math.min(cw, 160), 1, Ui.accent(), Ui.accent2(), 0xC0);
	}

	private void renderFooter(GuiGraphics g) {
		String hint = "H menu  ·  G grab  ·  V pick block  ·  K finished view  ·  Arrows move  ·  R rotate  ·  Tab next page";
		g.drawString(font, font.plainSubstrByWidth(hint, pw - side - 20), px + side + 10, py + ph - 11, Ui.a(0x5B6679, 0xC0), false);
	}

	// ---------------------------------------------------------------- list / click helpers

	private void beginList(GuiGraphics g, int x, int y, int w, int h) {
		listX = x;
		listY = y;
		listW = w;
		listH = h;
		inList = true;
		g.enableScissor(x, y, x + w, y + h);
	}

	private void endList(GuiGraphics g, int contentH) {
		g.disableScissor();
		inList = false;
		listContentH = contentH;
		double max = Math.max(0, contentH - listH);
		double s = Math.max(0, Math.min(max, scroll.getOrDefault(tab, 0.0)));
		scroll.put(tab, s);
		if (contentH > listH) {
			int barH = Math.max(18, (int) (listH * (listH / (double) contentH)));
			int barY = listY + (int) ((listH - barH) * (s / max));
			g.fill(listX + listW - 2, listY, listX + listW, listY + listH, Ui.a(0x1C2333, 0xC0));
			g.fill(listX + listW - 2, barY, listX + listW, barY + barH, Ui.a(Ui.accent(), 0xC0));
		}
	}

	private int scrollOffset() {
		return (int) Math.round(scroll.getOrDefault(tab, 0.0));
	}

	/** Adds a clickable area; inside a list it's clipped to the visible part. */
	private void addHit(int x, int y, int w, int h, Runnable r) {
		if (!inList) {
			hits.add(new Hit(x, y, w, h, r));
			return;
		}
		int y0 = Math.max(y, listY), y1 = Math.min(y + h, listY + listH);
		if (y1 > y0) hits.add(new Hit(x, y0, w, y1 - y0, r));
	}

	private boolean over(int mx, int my, int x, int y, int w, int h) {
		boolean in = mx >= x && my >= y && mx < x + w && my < y + h;
		return in && (!inList || my >= listY && my < listY + listH);
	}

	private void button(GuiGraphics g, int mx, int my, int x, int y, int w, int h, String label, int style, boolean enabled, Runnable action) {
		boolean hover = enabled && over(mx, my, x, y, w, h);
		Ui.button(g, font, x, y, w, h, label, hover, style, enabled);
		if (enabled) addHit(x, y, w, h, action);
	}

	private void searchField(GuiGraphics g, EditBox box) {
		int x = box.getX() - 5, y = box.getY() - 4, w = box.getWidth() + 10, h = 18;
		boolean focused = getFocused() == box;
		g.fill(x, y, x + w, y + h, Ui.a(0x070A11, 0xE8));
		g.fill(x, y + h - 1, x + w, y + h, Ui.a(Ui.accent(), focused ? 0xFF : 0x60));
	}

	private static String errorText(Throwable err) {
		Throwable c = err.getCause() != null ? err.getCause() : err;
		return c.getMessage() == null ? c.toString() : c.getMessage();
	}

	// =====================================================================
	// LIBRARY
	// =====================================================================

	private void rescan() {
		scanning = true;
		LocalLibrary.scan().thenAccept(list -> Minecraft.getInstance().execute(() -> {
			libraryCache = list;
			scanning = false;
		}));
	}

	private boolean inGroup(LocalLibrary.Entry e) {
		LibraryData lib = LibraryData.get();
		if (libraryGroup.isEmpty()) return true;
		if (libraryGroup.equals("*")) return lib.isFavorite(e.path());
		return lib.inCollection(libraryGroup, e.path());
	}

	private void renderLibrary(GuiGraphics g, int mx, int my) {
		LibraryData lib = LibraryData.get();
		if (!libraryGroup.isEmpty() && !libraryGroup.equals("*") && !lib.collections.containsKey(libraryGroup)) libraryGroup = "";
		searchField(g, libraryBox);
		int bx = libraryBox.getX() + libraryBox.getWidth() + 10;
		button(g, mx, my, bx, cy + 1, 58, 16, scanning ? "Scanning" : "Refresh", 0, !scanning, this::rescan);

		// group chips: All / Favourites / collections
		int chipX = cx, chipY = cy + 22;
		List<String[]> groups = new ArrayList<>();
		groups.add(new String[]{"", "All"});
		groups.add(new String[]{"*", "★ Favorites"});
		for (String c : lib.collections.keySet()) groups.add(new String[]{c, c});
		for (String[] gr : groups) {
			int w = font.width(gr[1]) + 8;
			if (chipX + w > cx + cw - 60) break;
			boolean active = libraryGroup.equals(gr[0]);
			Ui.chip(g, font, chipX, chipY, gr[1], active || over(mx, my, chipX, chipY, w, 11) ? Ui.accent() : Ui.accent2(), active);
			String id = gr[0];
			addHit(chipX, chipY, w, 11, () -> {
				libraryGroup = id;
				scroll.put(Tab.LIBRARY, 0.0);
			});
			chipX += w + 4;
		}
		if (!libraryGroup.isEmpty() && !libraryGroup.equals("*")) {
			String del = "Delete";
			int w = font.width(del) + 8;
			int dx = cx + cw - w;
			Ui.chip(g, font, dx, chipY, del, 0xFF3355, over(mx, my, dx, chipY, w, 11));
			String name = libraryGroup;
			addHit(dx, chipY, w, 11, () -> {
				lib.deleteCollection(name);
				libraryGroup = "";
				Toasts.info("Deleted collection " + name);
			});
		}

		int listW = Math.max(150, (int) (cw * 0.52f));
		int top = cy + 38;
		int h = ch - 42;
		List<LocalLibrary.Entry> all = libraryCache == null ? List.of() : libraryCache;
		String f = libraryFilter.toLowerCase(Locale.ROOT).trim();
		List<LocalLibrary.Entry> shown = new ArrayList<>();
		for (LocalLibrary.Entry e : all) {
			if (!inGroup(e)) continue;
			if (f.isEmpty() || e.name().toLowerCase(Locale.ROOT).contains(f) || e.folder().toLowerCase(Locale.ROOT).contains(f)) shown.add(e);
		}
		// favourites float to the top
		shown.sort((a, b) -> Boolean.compare(lib.isFavorite(b.path()), lib.isFavorite(a.path())));

		beginList(g, cx, top, listW, h);
		int rowH = HoloConfig.get().compactLists ? 20 : 26;
		int y = top - scrollOffset();
		if (shown.isEmpty()) {
			g.drawString(font, scanning ? "Looking for schematics…" : libraryGroup.isEmpty() ? "No schematics found." : "Nothing in this group yet.", cx + 8, top + 8, Ui.MUTED, false);
			g.drawString(font, "Use Browse to download some,", cx + 8, top + 22, Ui.DIM, false);
			g.drawString(font, "or drop files in .minecraft/schematics", cx + 8, top + 32, Ui.DIM, false);
		}
		for (LocalLibrary.Entry e : shown) {
			if (y + rowH >= top && y <= top + h) {
				boolean sel = e.path().equals(selectedFile);
				boolean hover = over(mx, my, cx, y, listW - 4, rowH - 2);
				Ui.card(g, cx, y, listW - 4, rowH - 2, hover, sel);
				int tagW = Ui.chip(g, font, cx + 6, y + (rowH - 2 - 11) / 2, e.ext(), extColor(e.ext()), false);
				int nameX = cx + 10 + tagW;
				int textMax = listW - (nameX - cx) - 64;
				g.drawString(font, font.plainSubstrByWidth(e.name(), textMax), nameX, y + (rowH > 20 ? 4 : 6), Ui.TEXT, false);
				if (rowH > 20 && !e.folder().isEmpty()) {
					g.drawString(font, font.plainSubstrByWidth(e.folder(), textMax), nameX, y + 14, Ui.DIM, false);
				}
				String size = (e.size() / 1024 + 1) + " KB";
				g.drawString(font, size, cx + listW - 24 - font.width(size), y + (rowH - 10) / 2, Ui.DIM, false);
				final Path p = e.path();
				// star
				boolean fav = lib.isFavorite(p);
				int sx = cx + listW - 18, sy = y + (rowH - 2 - 10) / 2;
				boolean starHover = over(mx, my, sx - 2, sy - 2, 13, 13);
				g.drawString(font, fav ? "★" : "☆", sx, sy + 1, fav ? 0xFFFFD23F : starHover ? Ui.TEXT : Ui.DIM, false);
				addHit(cx, y, listW - 4, rowH - 2, () -> {
					selectedFile = p;
					LocalLibrary.preview(p);
				});
				addHit(sx - 2, sy - 2, 13, 13, () -> lib.toggleFavorite(p)); // added last so it wins
			}
			y += rowH;
		}
		endList(g, shown.size() * rowH);

		renderLibraryPreview(g, mx, my, cx + listW + 8, top, cw - listW - 8, h);
	}

	private void renderLibraryPreview(GuiGraphics g, int mx, int my, int vx, int top, int vw, int h) {
		if (vw < 90) return;
		HoloConfig cfg = HoloConfig.get();
		Ui.panel(g, vx, top, vw, h);
		if (selectedFile == null) {
			Ui.centered(g, font, "Select a schematic", vx + vw / 2, top + h / 2 - 4, Ui.DIM);
			return;
		}
		CompletableFuture<LocalLibrary.Preview> fut = LocalLibrary.preview(selectedFile);
		LocalLibrary.Preview pv = fut.isDone() && !fut.isCompletedExceptionally() ? fut.join() : null;
		if (pv == null) {
			Ui.centered(g, font, "Reading…", vx + vw / 2, top + h / 2 - 4, Ui.MUTED);
			return;
		}
		if (pv.error() != null) {
			g.drawString(font, "Couldn't read this file:", vx + 8, top + 8, 0xFFFF3355, false);
			List<FormattedCharSequence> lines = font.split(Component.literal(pv.error()), vw - 16);
			for (int i = 0; i < Math.min(4, lines.size()); i++) g.drawString(font, lines.get(i), vx + 8, top + 20 + i * 10, Ui.MUTED, false);
			return;
		}
		Schematic s = pv.schematic();
		String viewLabel = cfg.preview3d ? "Map" : "3D";
		int vbw = font.width(viewLabel) + 8;
		g.drawString(font, font.plainSubstrByWidth(s.name, vw - 24 - vbw), vx + 8, top + 8, 0xFFFFFFFF, false);
		g.drawString(font, font.plainSubstrByWidth("by " + s.author + "  ·  " + s.format, vw - 16), vx + 8, top + 19, Ui.MUTED, false);
		Ui.chip(g, font, vx + vw - vbw - 6, top + 6, viewLabel, Ui.accent(), over(mx, my, vx + vw - vbw - 6, top + 6, vbw, 11));
		addHit(vx + vw - vbw - 6, top + 6, vbw, 11, () -> {
			cfg.preview3d = !cfg.preview3d;
			cfg.save();
		});

		// the bottom part (info, collections, button) needs about 96px
		int collectionsH = 26;
		int bottomNeeded = 22 + collectionsH + 34;
		int viewTop = top + 32;
		int viewH = Math.max(40, h - (viewTop - top) - bottomNeeded - 30);
		if (cfg.preview3d && pv.voxels() != null && pv.voxels().count() > 0) {
			p3x = vx + 6;
			p3y = viewTop;
			p3w = vw - 12;
			p3h = viewH;
			render3d(g, pv.voxels(), p3x, p3y, p3w, p3h);
			g.drawString(font, "drag to turn", p3x + 3, p3y + p3h - 10, Ui.a(0x8A97AD, 0x90), false);
		} else {
			p3w = 0;
			renderMap(g, pv, vx, vw, viewTop, viewH);
		}
		int iy = viewTop + viewH + 6;
		g.drawString(font, s.sizeX + " × " + s.sizeY + " × " + s.sizeZ + "   ·   " + Ui.formatCount(pv.blocks()) + " blocks", vx + 8, iy, Ui.TEXT, false);
		iy += 11;
		for (Map.Entry<String, Integer> en : pv.topBlocks()) {
			if (iy > top + h - bottomNeeded - 4) break;
			String cnt = Ui.formatCount(en.getValue());
			g.drawString(font, font.plainSubstrByWidth(en.getKey(), vw - 30 - font.width(cnt)), vx + 8, iy, Ui.MUTED, false);
			g.drawString(font, cnt, vx + vw - 8 - font.width(cnt), iy, Ui.DIM, false);
			iy += 10;
		}

		// collections
		LibraryData lib = LibraryData.get();
		int colY = top + h - 22 - collectionsH - 8;
		g.drawString(font, "COLLECTIONS", vx + 8, colY, Ui.DIM, false);
		int chipX = vx + 8, chipY = colY + 11;
		Path file = selectedFile;
		for (String c : lib.collections.keySet()) {
			int w = font.width(c) + 8;
			if (chipX + w > vx + vw - 104) break;
			boolean in = lib.inCollection(c, file);
			Ui.chip(g, font, chipX, chipY, c, Ui.accent(), in || over(mx, my, chipX, chipY, w, 11));
			addHit(chipX, chipY, w, 11, () -> lib.toggleInCollection(c, file));
			chipX += w + 4;
		}
		int nbx = vx + vw - 100;
		collectionBox.setX(nbx + 5);
		collectionBox.setY(chipY);
		collectionBox.setWidth(70);
		collectionBox.visible = true;
		searchField(g, collectionBox);
		button(g, mx, my, vx + vw - 22, chipY - 3, 16, 16, "+", 1, true, () -> {
			String name = collectionBox.getValue().trim();
			if (name.isEmpty()) {
				Toasts.info("Type a collection name first");
				return;
			}
			lib.createCollection(name);
			if (!lib.inCollection(name, file)) lib.toggleInCollection(name, file);
			collectionBox.setValue("");
			Toasts.success("Added to " + name);
		});

		boolean inWorld = Minecraft.getInstance().level != null;
		button(g, mx, my, vx + 8, top + h - 22, vw - 16, 16, inWorld ? "Place hologram here" : "Join a world to place", 1, inWorld, () -> {
			Placement p = PlacementManager.place(s);
			Toasts.success("Placed " + p.name());
			switchTab(Tab.BUILDS);
		});
	}

	private void renderMap(GuiGraphics g, LocalLibrary.Preview pv, int vx, int vw, int mapY, int maxH) {
		int mapMax = Math.min(vw - 16, maxH);
		if (pv.w() <= 0 || pv.h() <= 0 || mapMax <= 20) return;
		float scale = Math.min(mapMax / (float) pv.w(), mapMax / (float) pv.h());
		int mw = (int) (pv.w() * scale), mh = (int) (pv.h() * scale);
		int mxp = vx + (vw - mw) / 2;
		int myp = mapY + (maxH - mh) / 2;
		g.fill(mxp - 2, myp - 2, mxp + mw + 2, myp + mh + 2, Ui.a(0x05070C, 0xE0));
		for (int zz = 0; zz < pv.h(); zz++) {
			int y0 = myp + (int) (zz * scale), y1 = myp + (int) ((zz + 1) * scale);
			if (y1 <= y0) y1 = y0 + 1;
			for (int xx = 0; xx < pv.w(); xx++) {
				int c = pv.colors()[zz * pv.w() + xx];
				if (c == 0) continue;
				int x0 = mxp + (int) (xx * scale), x1 = mxp + (int) ((xx + 1) * scale);
				if (x1 <= x0) x1 = x0 + 1;
				g.fill(x0, y0, x1, y1, c);
			}
		}
		Ui.brackets(g, mxp - 2, myp - 2, mw + 4, mh + 4, Ui.a(Ui.accent(), 0xC0), 5);
	}

	/**
	 * Spinning voxel preview: every outside block is projected to the screen
	 * and drawn back-to-front as a small shaded square.
	 */
	private void render3d(GuiGraphics g, LocalLibrary.Voxels v, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, Ui.a(0x05070C, 0xD8));
		// floor grid glow
		for (int i = 1; i < 6; i++) g.fill(x, y + h * i / 6, x + w, y + h * i / 6 + 1, Ui.a(Ui.accent(), 0x0E));
		Ui.brackets(g, x, y, w, h, Ui.a(Ui.accent(), 0xC0), 6);

		long now = System.currentTimeMillis();
		boolean spin = !dragging3d && now - lastDragTime > 1500;
		if (spin && HoloConfig.get().animatedUi) dragYaw += 0.008f;
		float yaw = dragYaw;
		float pitch = Math.max(-1.3f, Math.min(1.3f, 0.45f + dragPitch));
		float cosY = (float) Math.cos(yaw), sinY = (float) Math.sin(yaw);
		float cosP = (float) Math.cos(pitch), sinP = (float) Math.sin(pitch);
		float scale = Math.min(w, h) * 0.46f / v.radius();
		float ccx = x + w / 2f, ccy = y + h / 2f;

		int n = v.count();
		if (sortKeys.length < n) {
			sortKeys = new long[n];
			projX = new float[n];
			projY = new float[n];
			projD = new float[n];
		}
		float minD = Float.MAX_VALUE, maxD = -Float.MAX_VALUE;
		for (int i = 0; i < n; i++) {
			float rx = v.x()[i] * cosY - v.z()[i] * sinY;
			float rz = v.x()[i] * sinY + v.z()[i] * cosY;
			float sy = v.y()[i] * cosP - rz * sinP;
			float d = v.y()[i] * sinP + rz * cosP;
			projX[i] = ccx + rx * scale;
			projY[i] = ccy - sy * scale;
			projD[i] = d;
			minD = Math.min(minD, d);
			maxD = Math.max(maxD, d);
		}
		float range = Math.max(0.001f, maxD - minD);
		for (int i = 0; i < n; i++) {
			int q = (int) ((projD[i] - minD) / range * 1_000_000);
			sortKeys[i] = ((long) q << 32) | i;
		}
		Arrays.sort(sortKeys, 0, n);
		int size = Math.max(1, Math.round(scale * 1.08f));
		int half = size / 2;
		g.enableScissor(x, y, x + w, y + h);
		for (int k = 0; k < n; k++) {
			int i = (int) (sortKeys[k] & 0xFFFFFFFFL);
			float shade = 0.5f + 0.5f * (projD[i] - minD) / range;
			int c = v.color()[i];
			int r = Math.min(255, (int) (((c >> 16) & 0xFF) * shade));
			int gg = Math.min(255, (int) (((c >> 8) & 0xFF) * shade));
			int b = Math.min(255, (int) ((c & 0xFF) * shade));
			int sx = (int) projX[i] - half, sy = (int) projY[i] - half;
			g.fill(sx, sy, sx + size, sy + size, 0xFF000000 | (r << 16) | (gg << 8) | b);
		}
		g.disableScissor();
	}

	private static int extColor(String ext) {
		return switch (ext) {
			case "litematic" -> 0x29D8FF;
			case "schem", "schematic" -> 0xB45CFF;
			case "nbt" -> 0xFFA020;
			default -> 0x8A97AD;
		};
	}

	// =====================================================================
	// BROWSE (online)
	// =====================================================================

	private void doSearch(int page) {
		if (searching) return;
		searching = true;
		searchError = null;
		expandedPost = -1;
		onlinePage = Math.max(1, page);
		scroll.put(Tab.BROWSE, 0.0);
		HoloConfig cfg = HoloConfig.get();
		OnlineLibrary.search(onlineQuery, cfg.lastSort, onlinePage, 24, cfg.versionFilter).whenComplete((res, err) -> Minecraft.getInstance().execute(() -> {
			searching = false;
			if (err != null) searchError = errorText(err);
			else lastPage = res;
		}));
	}

	private void renderBrowse(GuiGraphics g, int mx, int my) {
		HoloConfig cfg = HoloConfig.get();
		searchField(g, searchBox);
		int bx = searchBox.getX() + searchBox.getWidth() + 10;
		button(g, mx, my, bx, cy + 1, 56, 16, searching ? "…" : "Search", 1, !searching, () -> doSearch(1));
		button(g, mx, my, bx + 60, cy + 1, 90, 16, "Sort: " + cfg.lastSort, 0, true, () -> {
			int i = Arrays.asList(OnlineLibrary.SORTS).indexOf(cfg.lastSort);
			cfg.lastSort = OnlineLibrary.SORTS[(i + 1) % OnlineLibrary.SORTS.length];
			cfg.save();
			if (lastPage != null) doSearch(1);
		});
		String vLabel = "MC: " + (cfg.versionFilter.isEmpty() ? "any" : cfg.versionFilter);
		button(g, mx, my, bx + 154, cy + 1, 70, 16, vLabel, 0, true, () -> {
			int i = Arrays.asList(VERSIONS).indexOf(cfg.versionFilter);
			cfg.versionFilter = VERSIONS[(i + 1) % VERSIONS.length];
			cfg.save();
			if (lastPage != null) doSearch(1);
		});

		int chipX = cx, chipY = cy + 22;
		for (String cat : CATEGORIES) {
			int w = font.width(cat) + 8;
			if (chipX + w > cx + cw) break;
			boolean active = cat.equalsIgnoreCase(onlineQuery.trim());
			boolean hover = over(mx, my, chipX, chipY, w, 11);
			Ui.chip(g, font, chipX, chipY, cat, hover || active ? Ui.accent() : Ui.accent2(), active);
			addHit(chipX, chipY, w, 11, () -> {
				onlineQuery = cat;
				searchBox.setValue(cat);
				doSearch(1);
			});
			chipX += w + 4;
		}

		int top = cy + 40, h = ch - 64;
		beginList(g, cx, top, cw, h);
		int y = top - scrollOffset();
		int content = 0;
		if (searchError != null) {
			g.drawString(font, "Couldn't reach the schematic site:", cx + 8, top + 8, 0xFFFF3355, false);
			g.drawString(font, font.plainSubstrByWidth(searchError, cw - 16), cx + 8, top + 20, Ui.MUTED, false);
		} else if (lastPage == null) {
			g.drawString(font, searching ? "Searching…" : "Type something or pick a category above.", cx + 8, top + 8, Ui.MUTED, false);
			g.drawString(font, "Results come from MineMev and the sites it indexes.", cx + 8, top + 20, Ui.DIM, false);
		} else if (lastPage.posts().isEmpty()) {
			g.drawString(font, cfg.versionFilter.isEmpty() ? "Nothing found. Try fewer or different words."
					: "Nothing found for MC " + cfg.versionFilter + ". Try another version or 'any'.", cx + 8, top + 8, Ui.MUTED, false);
		} else {
			boolean thumbs = cfg.thumbnails;
			List<OnlineLibrary.Post> posts = lastPage.posts();
			for (int i = 0; i < posts.size(); i++) {
				OnlineLibrary.Post post = posts.get(i);
				boolean expanded = i == expandedPost;
				List<FormattedCharSequence> desc = expanded ? font.split(Component.literal(post.description().isBlank() ? "No description." : post.description()), cw - 24) : List.of();
				int descLines = Math.min(desc.size(), 6);
				int baseH = thumbs ? 46 : 34;
				int rowH = baseH + (expanded ? descLines * 10 + 24 : 0);
				if (y + rowH >= top && y <= top + h) {
					drawPost(g, mx, my, post, i, y, rowH, baseH, thumbs, expanded, desc, descLines);
				}
				y += rowH + 3;
				content += rowH + 3;
			}
		}
		endList(g, content);

		if (lastPage != null && searchError == null) {
			int py2 = top + h + 5;
			button(g, mx, my, cx, py2, 50, 14, "‹ Prev", 0, onlinePage > 1 && !searching, () -> doSearch(onlinePage - 1));
			String label = "Page " + onlinePage + (lastPage.totalPages() > 0 ? " of " + lastPage.totalPages() : "");
			Ui.centered(g, font, label, cx + cw / 2, py2 + 3, Ui.MUTED);
			boolean more = lastPage.totalPages() > 0 ? onlinePage < lastPage.totalPages() : lastPage.posts().size() >= 12;
			button(g, mx, my, cx + cw - 50, py2, 50, 14, "Next ›", 0, more && !searching, () -> doSearch(onlinePage + 1));
		}
	}

	private void drawPost(GuiGraphics g, int mx, int my, OnlineLibrary.Post post, int index, int y, int rowH, int baseH, boolean thumbs,
						  boolean expanded, List<FormattedCharSequence> desc, int descLines) {
		int w = cw - 6;
		boolean hover = over(mx, my, cx, y, w, rowH);
		Ui.card(g, cx, y, w, rowH, hover, expanded);
		String key = post.vendor() + "/" + post.uuid();
		Path saved = DownloadIndex.get(key);
		String state = downloadState.get(key);

		int textX = cx + 8;
		if (thumbs) {
			drawThumb(g, post.thumbUrl(), cx + 4, y + 3);
			textX = cx + 4 + ThumbCache.W * 2 + 8;
		}
		int btnW = 74;
		int textW = cx + w - btnW - 14 - textX;
		int ty = y + (thumbs ? 6 : 5);
		g.drawString(font, font.plainSubstrByWidth(post.title(), textW), textX, ty, 0xFFFFFFFF, false);
		String meta = "by " + post.author() + "  ·  ⬇ " + Ui.formatCount(Math.max(0, post.downloads()));
		if (!post.versions().isEmpty()) meta += "  ·  " + post.versions().get(0);
		if (!"minemev".equalsIgnoreCase(post.vendor())) meta += "  ·  " + post.vendor();
		g.drawString(font, font.plainSubstrByWidth(meta, textW), textX, ty + 11, Ui.MUTED, false);
		int tx = textX;
		for (String tag : post.tags()) {
			int tw = font.width(tag) + 8;
			if (tx + tw > textX + textW) break;
			Ui.chip(g, font, tx, ty + 21, tag, Ui.accent2(), false);
			tx += tw + 3;
		}

		addHit(cx, y, w - btnW - 12, baseH, () -> expandedPost = expandedPost == index ? -1 : index);

		int bx = cx + w - btnW - 6, by = y + (baseH - 16) / 2;
		if ("…".equals(state)) {
			button(g, mx, my, bx, by, btnW, 16, "Downloading", 0, false, () -> {});
		} else if (saved != null) {
			boolean inWorld = Minecraft.getInstance().level != null;
			button(g, mx, my, bx, by, btnW, 16, inWorld ? "✔ Place" : "✔ Saved", 1, inWorld, () -> placeFile(saved));
		} else {
			button(g, mx, my, bx, by, btnW, 16, state != null ? "Retry" : "Download", 1, true, () -> startDownload(post));
		}

		if (expanded) {
			int dy = y + baseH + 2;
			for (int i = 0; i < descLines; i++) {
				g.drawString(font, desc.get(i), cx + 12, dy, Ui.MUTED, false);
				dy += 10;
			}
			if (state != null && !"…".equals(state) && !"ok".equals(state)) {
				g.drawString(font, font.plainSubstrByWidth(state, w - 120), cx + 12, dy + 4, 0xFFFF3355, false);
			}
			if (post.pageUrl() != null && !post.pageUrl().isBlank()) {
				button(g, mx, my, cx + w - 100, dy + 1, 92, 14, "Copy page link", 0, true, () -> {
					Minecraft.getInstance().keyboardHandler.setClipboard(post.pageUrl());
					Toasts.info("Link copied");
				});
			}
		}
	}

	/** Pixel-art thumbnail (2x2 screen pixels per cell). */
	private void drawThumb(GuiGraphics g, String url, int x, int y) {
		int tw = ThumbCache.W * 2, th = ThumbCache.H * 2;
		g.fill(x, y, x + tw, y + th, Ui.a(0x05070C, 0xF0));
		ThumbCache.Thumb t = ThumbCache.get(url);
		if (t.pixels() != null) {
			int[] px2 = t.pixels();
			for (int yy = 0; yy < ThumbCache.H; yy++) {
				for (int xx = 0; xx < ThumbCache.W; xx++) {
					g.fill(x + xx * 2, y + yy * 2, x + xx * 2 + 2, y + yy * 2 + 2, px2[yy * ThumbCache.W + xx]);
				}
			}
		} else if (t.failed()) {
			// hologram-style placeholder
			for (int i = 0; i < 5; i++) g.fill(x + 6 + i * 11, y + th - 8 - i * 5, x + 14 + i * 11, y + th - 6, Ui.a(Ui.accent(), 0x50 + i * 20));
		} else {
			long tt = System.currentTimeMillis() / 90 % tw;
			g.fill(x + (int) tt, y, x + (int) tt + 6, y + th, Ui.a(Ui.accent(), 0x40));
		}
		Ui.brackets(g, x, y, tw, th, Ui.a(Ui.accent(), 0x90), 4);
	}

	private void startDownload(OnlineLibrary.Post post) {
		String key = post.vendor() + "/" + post.uuid();
		downloadState.put(key, "…");
		OnlineLibrary.downloadBest(post).whenComplete((path, err) -> Minecraft.getInstance().execute(() -> {
			if (err != null) {
				String msg = errorText(err);
				downloadState.put(key, msg);
				Toasts.error("Download failed: " + msg);
			} else {
				downloadState.put(key, "ok");
				libraryCache = null;
				rescan();
				Toasts.success("Saved " + path.getFileName());
			}
		}));
	}

	private void placeFile(Path file) {
		CompletableFuture.supplyAsync(() -> {
			try {
				return SchematicLoader.load(file);
			} catch (Exception e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		}).whenComplete((s, err) -> Minecraft.getInstance().execute(() -> {
			if (err != null) {
				Toasts.error("Couldn't load: " + errorText(err));
				return;
			}
			Placement p = PlacementManager.place(s);
			Toasts.success("Placed " + p.name());
			switchTab(Tab.BUILDS);
		}));
	}

	// =====================================================================
	// BUILDS (placements)
	// =====================================================================

	private void pasteShareCode() {
		String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
		if (!ShareCodes.looksLikeCode(clip)) {
			Toasts.error("Copy a HoloPrint code first (starts with HOLOPRINT1.)");
			return;
		}
		if (Minecraft.getInstance().level == null) {
			Toasts.error("Join a world first");
			return;
		}
		Toasts.info("Loading shared build…");
		ShareCodes.importCode(clip).whenComplete((p, err) -> Minecraft.getInstance().execute(() -> {
			if (err != null) Toasts.error(errorText(err));
			else {
				Toasts.success("Placed " + p.name() + " at " + HoloPrintClient.fmt(p.origin));
				libraryCache = null;
				rescan();
			}
		}));
	}

	private void renderBuilds(GuiGraphics g, int mx, int my) {
		List<Placement> all = PlacementManager.all();
		int listW = Math.max(130, (int) (cw * 0.38f));
		int top = cy + 4, h = ch - 26;
		beginList(g, cx, top, listW, h);
		int y = top - scrollOffset();
		if (all.isEmpty()) {
			g.drawString(font, "Nothing placed yet.", cx + 8, top + 8, Ui.MUTED, false);
			g.drawString(font, "Pick one in Library,", cx + 8, top + 20, Ui.DIM, false);
			g.drawString(font, "or paste a friend's code.", cx + 8, top + 30, Ui.DIM, false);
		}
		for (int i = 0; i < all.size(); i++) {
			Placement p = all.get(i);
			boolean sel = i == PlacementManager.selectedIndex();
			boolean hover = over(mx, my, cx, y, listW - 4, 30);
			Ui.card(g, cx, y, listW - 4, 30, hover, sel);
			g.drawString(font, font.plainSubstrByWidth(p.name(), listW - 40), cx + 8, y + 5, p.visible ? Ui.TEXT : Ui.DIM, false);
			g.fill(cx + 8, y + 18, cx + listW - 14, y + 21, 0xFF1C2333);
			g.fill(cx + 8, y + 18, cx + 8 + (int) ((listW - 22) * p.progress()), y + 21, Ui.a(Ui.accent(), 0xFF));
			final int idx = i;
			addHit(cx, y, listW - 4, 30, () -> PlacementManager.select(idx));
			y += 33;
		}
		endList(g, all.size() * 33);
		button(g, mx, my, cx, top + h + 4, listW - 4, 16, "Paste share code", 0, true, this::pasteShareCode);

		Placement p = PlacementManager.selected();
		int vx = cx + listW + 8, vw = cw - listW - 8, vh = ch - 8;
		Ui.panel(g, vx, top, vw, vh);
		if (p == null) {
			Ui.centered(g, font, "Select a build", vx + vw / 2, top + vh / 2 - 4, Ui.DIM);
			return;
		}
		int x = vx + 10, yy = top + 10;
		g.drawString(font, font.plainSubstrByWidth(p.name(), vw - 20), x, yy, 0xFFFFFFFF, false);
		yy += 12;
		g.drawString(font, font.plainSubstrByWidth("Origin " + HoloPrintClient.fmt(p.origin)
				+ "   Size " + p.sizeXRotated() + "×" + p.schematic.sizeY + "×" + p.sizeZRotated(), vw - 20), x, yy, Ui.MUTED, false);
		yy += 16;

		int sw = (vw - 20 - 9) / 4;
		stat(g, x, yy, sw, "Done", p.statCorrect, Ui.accent());
		stat(g, x + sw + 3, yy, sw, "Missing", p.statMissing, HoloConfig.get().colorMissing);
		stat(g, x + (sw + 3) * 2, yy, sw, "Wrong", p.statWrong, HoloConfig.get().colorWrongBlock);
		stat(g, x + (sw + 3) * 3, yy, sw, "Turned", p.statWrongState, HoloConfig.get().colorWrongState);
		yy += 32;

		g.drawString(font, "POSITION  (or hold G in-game to grab it)", x, yy, Ui.DIM, false);
		yy += 11;
		int b = 22;
		button(g, mx, my, x, yy, 70, 16, "Move to me", 1, true, () -> {
			if (Minecraft.getInstance().player != null) p.moveTo(Minecraft.getInstance().player.blockPosition());
			PlacementManager.save();
		});
		int ax = x + 76;
		nudge(g, mx, my, ax, yy, b, "-X", p, -1, 0, 0);
		nudge(g, mx, my, ax + b + 2, yy, b, "+X", p, 1, 0, 0);
		nudge(g, mx, my, ax + (b + 2) * 2, yy, b, "-Y", p, 0, -1, 0);
		nudge(g, mx, my, ax + (b + 2) * 3, yy, b, "+Y", p, 0, 1, 0);
		nudge(g, mx, my, ax + (b + 2) * 4, yy, b, "-Z", p, 0, 0, -1);
		nudge(g, mx, my, ax + (b + 2) * 5, yy, b, "+Z", p, 0, 0, 1);
		yy += 22;

		g.drawString(font, "TRANSFORM", x, yy, Ui.DIM, false);
		yy += 11;
		button(g, mx, my, x, yy, 86, 16, "Rotate ↻ " + (p.rotation * 90) + "°", 0, true, () -> { p.rotateClockwise(); PlacementManager.save(); });
		button(g, mx, my, x + 90, yy, 110, 16, "Mirror: " + Placement.mirrorName(p.mirror), 0, true, () -> { p.cycleMirror(); PlacementManager.save(); });
		yy += 22;

		g.drawString(font, "LAYERS", x, yy, Ui.DIM, false);
		yy += 11;
		button(g, mx, my, x, yy, 100, 16, Placement.layerModeName(p.layerMode), 0, true, () -> { p.layerMode = (p.layerMode + 1) % 3; PlacementManager.save(); });
		boolean lm = p.layerMode != 0;
		button(g, mx, my, x + 104, yy, 20, 16, "-", 0, lm, () -> { p.layer = Math.max(0, p.layer - 1); PlacementManager.save(); });
		Ui.centered(g, font, (p.layer + 1) + "/" + p.schematic.sizeY, x + 148, yy + 4, lm ? Ui.TEXT : Ui.DIM);
		button(g, mx, my, x + 172, yy, 20, 16, "+", 0, lm, () -> { p.layer = Math.min(p.schematic.sizeY - 1, p.layer + 1); PlacementManager.save(); });
		yy += 24;

		button(g, mx, my, x, yy, 64, 16, p.visible ? "Hide" : "Show", 0, true, () -> { p.visible = !p.visible; PlacementManager.save(); });
		button(g, mx, my, x + 68, yy, 64, 16, "Remove", 2, true, () -> PlacementManager.remove(p));
		button(g, mx, my, x + 136, yy, 110, 16, "Copy share code", 1, true, () -> {
			try {
				String code = ShareCodes.encode(p);
				Minecraft.getInstance().keyboardHandler.setClipboard(code);
				Toasts.success(code.length() > 2000 ? "Code copied (send it as a message or file)" : "Share code copied");
			} catch (Exception e) {
				Toasts.error("Couldn't make a code: " + e.getMessage());
			}
		});
	}

	private void stat(GuiGraphics g, int x, int y, int w, String label, int value, int color) {
		g.fill(x, y, x + w, y + 26, Ui.a(Ui.BG2, 0xE0));
		g.fill(x, y, x + w, y + 1, Ui.a(color, 0xC0));
		g.drawString(font, Ui.formatCount(value), x + 5, y + 5, Ui.a(color, 0xFF), false);
		g.drawString(font, label, x + 5, y + 15, Ui.DIM, false);
	}

	private void nudge(GuiGraphics g, int mx, int my, int x, int y, int w, String label, Placement p, int dx, int dy, int dz) {
		button(g, mx, my, x, y, w, 16, label, 0, true, () -> {
			int mul = shiftDown ? 10 : 1;
			p.move(dx * mul, dy * mul, dz * mul);
			PlacementManager.save();
		});
	}

	// =====================================================================
	// CREATE (save your own build)
	// =====================================================================

	private void renderCreate(GuiGraphics g, int mx, int my) {
		Minecraft mc = Minecraft.getInstance();
		boolean inWorld = mc.level != null && mc.player != null;
		int x = cx + 4, y = cy + 4;
		List<FormattedCharSequence> intro = font.split(Component.literal(
				"Mark two opposite corners of your build, name it, and save it as a .litematic. It shows up in your Library "
						+ "and also works in Litematica. Tip: Numpad 7 / Numpad 9 set the corners to the block you're aiming at."), cw - 8);
		for (FormattedCharSequence line : intro) {
			g.drawString(font, line, x, y, Ui.MUTED, false);
			y += 10;
		}
		y += 8;
		y = cornerRow(g, mx, my, x, y, "Corner 1", Selection.corner1, 0x2BFF88, true, inWorld);
		y = cornerRow(g, mx, my, x, y, "Corner 2", Selection.corner2, 0xFF5E3A, false, inWorld);
		y += 4;
		if (Selection.complete()) {
			long vol = Selection.volume();
			boolean tooBig = vol > Selection.MAX_VOLUME;
			g.drawString(font, "Size " + Selection.sizeX() + " × " + Selection.sizeY() + " × " + Selection.sizeZ()
					+ "   ·   " + Ui.formatCount(vol) + " blocks of space", x, y, tooBig ? 0xFFFF3355 : Ui.TEXT, false);
			if (tooBig) g.drawString(font, "Too big — the limit is " + Ui.formatCount(Selection.MAX_VOLUME), x, y + 10, 0xFFFF3355, false);
		} else {
			g.drawString(font, "Set both corners to see the size.", x, y, Ui.DIM, false);
		}
		y += 24;

		g.drawString(font, "NAME", x, y, Ui.DIM, false);
		y += 12;
		saveBox.setX(x + 5);
		saveBox.setY(y + 4);
		saveBox.setWidth(Math.min(200, cw - 140));
		searchField(g, saveBox);
		boolean can = inWorld && Selection.complete() && Selection.volume() <= Selection.MAX_VOLUME && !saving;
		button(g, mx, my, x + saveBox.getWidth() + 16, y, 110, 18, saving ? "Saving…" : "Save schematic", 1, can, this::saveSelection);
		y += 30;
		button(g, mx, my, x, y, 110, 16, "Clear corners", 0, Selection.corner1 != null || Selection.corner2 != null, () -> {
			Selection.corner1 = null;
			Selection.corner2 = null;
		});
		y += 26;
		g.drawString(font, "Blocks are copied from what your game has loaded. Chest contents and mobs aren't saved.", x, y, Ui.DIM, false);
	}

	private int cornerRow(GuiGraphics g, int mx, int my, int x, int y, String label, BlockPos pos, int color, boolean first, boolean inWorld) {
		g.fill(x, y + 2, x + 8, y + 10, Ui.a(color, 0xFF));
		g.drawString(font, label, x + 14, y + 2, Ui.TEXT, false);
		g.drawString(font, pos == null ? "not set" : HoloPrintClient.fmt(pos), x + 70, y + 2, pos == null ? Ui.DIM : Ui.a(Ui.accent(), 0xFF), false);
		int bx = x + 170;
		button(g, mx, my, bx, y - 2, 96, 16, "Aimed block", 0, inWorld, () -> setCorner(first, true));
		button(g, mx, my, bx + 100, y - 2, 70, 16, "My feet", 0, inWorld, () -> setCorner(first, false));
		return y + 22;
	}

	private void setCorner(boolean first, boolean aimed) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		BlockPos pos = mc.player.blockPosition();
		if (aimed && mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bhr
				&& mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
			pos = bhr.getBlockPos().immutable();
		}
		if (first) Selection.corner1 = pos;
		else Selection.corner2 = pos;
	}

	private void saveSelection() {
		Minecraft mc = Minecraft.getInstance();
		Selection.ExportResult res;
		try {
			String author = mc.player != null ? mc.player.getName().getString() : "itzrealmacer";
			res = Selection.capture(saveName, author);
		} catch (RuntimeException e) {
			Toasts.error(e.getMessage());
			return;
		}
		saving = true;
		final Selection.ExportResult result = res;
		CompletableFuture.supplyAsync(() -> {
			try {
				return Selection.write(result.schematic());
			} catch (Exception e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		}).whenComplete((path, err) -> Minecraft.getInstance().execute(() -> {
			saving = false;
			if (err != null) {
				Toasts.error("Couldn't save: " + errorText(err));
				return;
			}
			Toasts.success("Saved " + path.getFileName() + (result.unloaded() > 0 ? " (" + result.unloaded() + " unloaded blocks left empty)" : ""));
			selectedFile = path;
			libraryCache = null;
			rescan();
		}));
	}

	// =====================================================================
	// MATERIALS
	// =====================================================================

	private void renderMaterials(GuiGraphics g, int mx, int my) {
		Placement p = PlacementManager.selected();
		if (p == null) {
			g.drawString(font, "Select or place a build first.", cx + 8, cy + 8, Ui.MUTED, false);
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		BlockPos me = mc.player != null ? mc.player.blockPosition() : BlockPos.ZERO;
		Map<Block, int[]> mats = p.materials();
		Map<Item, Integer> inv = inventoryCounts();
		record Row(Item item, String name, int total, int left, int have, int inChests) {}
		List<Row> rows = new ArrayList<>();
		for (Map.Entry<Block, int[]> e : mats.entrySet()) {
			Item item = e.getKey().asItem();
			String name = item == Items.AIR ? e.getKey().getName().getString() : new ItemStack(item).getHoverName().getString();
			int chests = 0;
			if (item != Items.AIR) for (Map.Entry<BlockPos, Integer> c : ChestMemory.whereIs(item, me)) chests += c.getValue();
			rows.add(new Row(item, name, e.getValue()[0], e.getValue()[1], item == Items.AIR ? 0 : inv.getOrDefault(item, 0), chests));
		}
		rows.sort((a, b) -> b.left() != a.left() ? Integer.compare(b.left(), a.left()) : Integer.compare(b.total(), a.total()));

		g.drawString(font, font.plainSubstrByWidth(p.name(), cw - 210), cx, cy + 5, Ui.TEXT, false);
		button(g, mx, my, cx + cw - 200, cy + 1, 96, 16, "Copy as text", 0, true, () -> {
			StringBuilder sb = new StringBuilder("Materials for " + p.name() + "\n");
			for (Row r : rows) sb.append(r.name()).append(": ").append(r.left()).append(" left / ").append(r.total()).append(" total\n");
			Minecraft.getInstance().keyboardHandler.setClipboard(sb.toString());
			Toasts.info("Material list copied");
		});
		button(g, mx, my, cx + cw - 100, cy + 1, 100, 16, "Forget chests", 2, ChestMemory.chestCount() > 0, () -> {
			ChestMemory.clearWorld();
			Toasts.info("Forgot all remembered chests here");
		});

		// grab-next card
		int top = cy + 22;
		List<ChestMemory.Stop> stops = ChestMemory.plan(HudOverlay.stillNeeded(p), me);
		int cardH = 36;
		g.fill(cx, top, cx + cw, top + cardH, Ui.a(Ui.BG2, 0xE0));
		g.fill(cx, top, cx + 2, top + cardH, Ui.a(Ui.accent2(), 0xFF));
		if (ChestMemory.chestCount() == 0) {
			g.drawString(font, "GRAB NEXT", cx + 8, top + 5, Ui.DIM, false);
			g.drawString(font, "Open your chests once and HoloPrint remembers what's inside.", cx + 8, top + 17, Ui.MUTED, false);
		} else if (stops.isEmpty()) {
			g.drawString(font, "GRAB NEXT", cx + 8, top + 5, Ui.DIM, false);
			g.drawString(font, "None of your remembered chests have what you still need.", cx + 8, top + 17, Ui.MUTED, false);
		} else {
			ChestMemory.Stop s = stops.get(0);
			g.drawString(font, "GRAB NEXT  ·  chest at " + HoloPrintClient.fmt(s.pos()) + "  ·  " + Math.round(s.distance()) + "m away  ·  "
					+ stops.size() + " stop" + (stops.size() == 1 ? "" : "s") + " total", cx + 8, top + 5, Ui.a(Ui.accent2(), 0xFF), false);
			int ix = cx + 8;
			for (Map.Entry<Item, Integer> e : s.grab()) {
				String cnt = "×" + Ui.formatCount(e.getValue());
				if (ix + 20 + font.width(cnt) > cx + cw - 8) break;
				g.renderItem(new ItemStack(e.getKey()), ix, top + 16);
				g.drawString(font, cnt, ix + 17, top + 22, Ui.TEXT, false);
				ix += 22 + font.width(cnt);
			}
		}

		top += cardH + 6;
		int colLeft = cx + cw - 230, colTotal = cx + cw - 172, colHave = cx + cw - 118, colChest = cx + cw - 64;
		g.drawString(font, "LEFT", colLeft, top, Ui.DIM, false);
		g.drawString(font, "TOTAL", colTotal, top, Ui.DIM, false);
		g.drawString(font, "IN BAG", colHave, top, Ui.DIM, false);
		g.drawString(font, "CHESTS", colChest, top, Ui.DIM, false);
		top += 10;
		int h = cy + ch - top - 4;
		beginList(g, cx, top, cw, h);
		int y = top - scrollOffset();
		for (Row r : rows) {
			if (y + 20 >= top && y <= top + h) {
				boolean done = r.left() == 0;
				Ui.card(g, cx, y, cw - 6, 19, over(mx, my, cx, y, cw - 6, 19), false);
				if (r.item() != Items.AIR) g.renderItem(new ItemStack(r.item()), cx + 3, y + 1);
				g.drawString(font, font.plainSubstrByWidth(r.name(), colLeft - cx - 30), cx + 24, y + 6, done ? Ui.DIM : Ui.TEXT, false);
				g.drawString(font, done ? "✔" : Ui.formatCount(r.left()), colLeft, y + 6, done ? 0xFF2BFF88 : Ui.a(HoloConfig.get().colorMissing, 0xFF), false);
				g.drawString(font, Ui.formatCount(r.total()), colTotal, y + 6, Ui.MUTED, false);
				int haveCol = r.have() >= r.left() ? 0xFF2BFF88 : r.have() > 0 ? 0xFFFFA020 : Ui.DIM;
				g.drawString(font, Ui.formatCount(r.have()), colHave, y + 6, haveCol, false);
				g.drawString(font, r.inChests() > 0 ? Ui.formatCount(r.inChests()) : "–", colChest, y + 6, r.inChests() > 0 ? Ui.a(Ui.accent2(), 0xFF) : Ui.DIM, false);
			}
			y += 21;
		}
		endList(g, rows.size() * 21);
	}

	private static Map<Item, Integer> inventoryCounts() {
		Map<Item, Integer> m = new HashMap<>();
		var player = Minecraft.getInstance().player;
		if (player == null) return m;
		var inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack st = inv.getItem(i);
			if (!st.isEmpty()) m.merge(st.getItem(), st.getCount(), Integer::sum);
		}
		return m;
	}

	// =====================================================================
	// SETTINGS (scrolls)
	// =====================================================================

	private void renderSettings(GuiGraphics g, int mx, int my) {
		HoloConfig cfg = HoloConfig.get();
		int top = cy + 2, h = ch - 6;
		beginList(g, cx, top, cw, h);
		int off = scrollOffset();
		boolean twoCols = cw > 400;
		int colW = twoCols ? (cw - 24) / 2 : cw - 12;
		int x = cx + 4;
		int x2 = twoCols ? cx + colW + 16 : x;
		int y = top + 2 - off;

		// ---- column 1: theme + menu + HUD
		g.drawString(font, "THEME", x, y, Ui.DIM, false);
		y += 12;
		int tx = x;
		for (int i = 0; i < HoloConfig.PRESET_NAMES.length; i++) {
			String n = HoloConfig.PRESET_NAMES[i];
			int w = font.width(n) + 8;
			if (tx + w > x + colW) {
				tx = x;
				y += 14;
			}
			boolean act = cfg.themePreset == i && cfg.accentColor == HoloConfig.PRESETS[i][0];
			Ui.chip(g, font, tx, y, n, HoloConfig.PRESETS[i][0], act || over(mx, my, tx, y, w, 11));
			final int idx = i;
			addHit(tx, y, w, 11, () -> {
				cfg.applyPreset(idx);
				accentBox.setValue(hex(cfg.accentColor));
				accent2Box.setValue(hex(cfg.accent2Color));
				cfg.save();
			});
			tx += w + 4;
		}
		y += 20;
		y = hexRow(g, x, y, "Accent colour", cfg.accentColor, accentBox, top, h);
		y = hexRow(g, x, y, "Second colour", cfg.accent2Color, accent2Box, top, h);
		y += 4;
		g.drawString(font, "MENU", x, y, Ui.DIM, false);
		y += 12;
		y = stepper(g, mx, my, x, y, colW, "Panel opacity", Math.round(cfg.panelOpacity / 2.55f) + "%",
				() -> cfg.panelOpacity -= 15, () -> cfg.panelOpacity += 15);
		y = toggleRow(g, mx, my, x, y, colW, "Animated menu", cfg.animatedUi, () -> cfg.animatedUi = !cfg.animatedUi);
		y = toggleRow(g, mx, my, x, y, colW, "Compact lists", cfg.compactLists, () -> cfg.compactLists = !cfg.compactLists);
		y = toggleRow(g, mx, my, x, y, colW, "3D preview in Library", cfg.preview3d, () -> cfg.preview3d = !cfg.preview3d);
		y = toggleRow(g, mx, my, x, y, colW, "Thumbnails in Browse", cfg.thumbnails, () -> cfg.thumbnails = !cfg.thumbnails);
		y += 4;
		g.drawString(font, "HUD", x, y, Ui.DIM, false);
		y += 12;
		y = toggleRow(g, mx, my, x, y, colW, "Status HUD", cfg.hudEnabled, () -> cfg.hudEnabled = !cfg.hudEnabled);
		y = toggleRow(g, mx, my, x, y, colW, "Show aimed block", cfg.hudShowTarget, () -> cfg.hudShowTarget = !cfg.hudShowTarget);
		String[] corners = {"Top left", "Top right", "Bottom left", "Bottom right"};
		y = stepper(g, mx, my, x, y, colW, "HUD corner", corners[cfg.hudCorner], () -> cfg.hudCorner--, () -> cfg.hudCorner++);
		int col1End = y;

		// ---- column 2: hologram + helpers
		int hy = twoCols ? top + 2 - off : y + 8;
		g.drawString(font, "HOLOGRAM", x2, hy, Ui.DIM, false);
		hy += 12;
		hy = toggleRow(g, mx, my, x2, hy, colW, "Show hologram", cfg.hologramVisible, () -> cfg.hologramVisible = !cfg.hologramVisible);
		hy = stepper(g, mx, my, x2, hy, colW, "Style", cfg.renderMode == 0 ? "Holo boxes" : "Real blocks",
				() -> cfg.renderMode--, () -> cfg.renderMode++);
		hy = stepper(g, mx, my, x2, hy, colW, "Opacity", Math.round(cfg.hologramAlpha / 2.55f) + "%",
				() -> cfg.hologramAlpha -= 10, () -> cfg.hologramAlpha += 10);
		hy = stepper(g, mx, my, x2, hy, colW, "Render distance", cfg.renderRadius + " blocks",
				() -> cfg.renderRadius -= 8, () -> cfg.renderRadius += 8);
		hy = toggleRow(g, mx, my, x2, hy, colW, "Glowing edges", cfg.outlineEdges, () -> cfg.outlineEdges = !cfg.outlineEdges);
		hy = toggleRow(g, mx, my, x2, hy, colW, "Pulse + scan line", cfg.pulse, () -> cfg.pulse = !cfg.pulse);
		hy = toggleRow(g, mx, my, x2, hy, colW, "Show wrong blocks", cfg.showWrongBlocks, () -> cfg.showWrongBlocks = !cfg.showWrongBlocks);
		hy += 2;
		hy = swatchRow(g, mx, my, x2, hy, colW, "Missing", cfg.colorMissing, c -> cfg.colorMissing = c);
		hy = swatchRow(g, mx, my, x2, hy, colW, "Wrong block", cfg.colorWrongBlock, c -> cfg.colorWrongBlock = c);
		hy = swatchRow(g, mx, my, x2, hy, colW, "Wrong facing", cfg.colorWrongState, c -> cfg.colorWrongState = c);
		hy += 4;
		g.drawString(font, "BUILDING HELPERS", x2, hy, Ui.DIM, false);
		hy += 12;
		hy = toggleRow(g, mx, my, x2, hy, colW, "Auto-pick block you aim at", cfg.autoPick, () -> cfg.autoPick = !cfg.autoPick);
		hy = stepper(g, mx, my, x2, hy, colW, "Finished view key", cfg.afterViewHold ? "Hold" : "Toggle",
				() -> cfg.afterViewHold = !cfg.afterViewHold, () -> cfg.afterViewHold = !cfg.afterViewHold);
		hy = toggleRow(g, mx, my, x2, hy, colW, "Grab sticks to blocks", cfg.grabSnap, () -> cfg.grabSnap = !cfg.grabSnap);
		hy = toggleRow(g, mx, my, x2, hy, colW, "Outline next chest", cfg.showChestRoute, () -> cfg.showChestRoute = !cfg.showChestRoute);

		endList(g, Math.max(col1End, hy) + off - top + 8);
	}

	private int hexRow(GuiGraphics g, int x, int y, String label, int color, EditBox box, int listTop, int listHeight) {
		g.drawString(font, label, x, y + 3, Ui.TEXT, false);
		g.fill(x + 84, y, x + 98, y + 13, Ui.a(color, 0xFF));
		box.setX(x + 110);
		box.setY(y + 2);
		// only show the text box while it's inside the scroll area
		box.visible = y >= listTop && y + 14 <= listTop + listHeight;
		if (box.visible) searchField(g, box);
		else if (getFocused() == box) setFocused(null);
		return y + 18;
	}

	private int toggleRow(GuiGraphics g, int mx, int my, int x, int y, int w, String label, boolean on, Runnable flip) {
		boolean hover = over(mx, my, x, y - 2, w, 15);
		if (hover) g.fill(x - 3, y - 2, x + w, y + 13, Ui.a(Ui.accent(), 0x12));
		g.drawString(font, font.plainSubstrByWidth(label, w - 30), x, y + 2, Ui.TEXT, false);
		Ui.toggle(g, x + w - 26, y, on, hover);
		addHit(x, y - 2, w, 15, () -> {
			flip.run();
			HoloConfig.get().save();
		});
		return y + 16;
	}

	private int stepper(GuiGraphics g, int mx, int my, int x, int y, int w, String label, String value, Runnable dec, Runnable inc) {
		g.drawString(font, font.plainSubstrByWidth(label, w - 126), x, y + 2, Ui.TEXT, false);
		int bx = x + w - 120;
		button(g, mx, my, bx, y - 1, 16, 13, "‹", 0, true, () -> { dec.run(); HoloConfig.get().save(); });
		Ui.centered(g, font, value, bx + 60, y + 2, Ui.a(Ui.accent(), 0xFF));
		button(g, mx, my, bx + 104, y - 1, 16, 13, "›", 0, true, () -> { inc.run(); HoloConfig.get().save(); });
		return y + 16;
	}

	private int swatchRow(GuiGraphics g, int mx, int my, int x, int y, int w, String label, int current, java.util.function.IntConsumer set) {
		g.drawString(font, label, x, y + 2, Ui.TEXT, false);
		int sx = x + 76;
		int size = 11;
		for (int c : SWATCHES) {
			if (sx + size > x + w) break;
			boolean sel = (current & 0xFFFFFF) == c;
			g.fill(sx, y, sx + size, y + size, Ui.a(c, 0xFF));
			if (sel) Ui.brackets(g, sx - 2, y - 2, size + 4, size + 4, 0xFFFFFFFF, 3);
			final int col = c;
			addHit(sx, y, size, size, () -> {
				set.accept(col);
				HoloConfig.get().save();
			});
			sx += size + 3;
		}
		return y + 16;
	}

	private static String hex(int rgb) {
		return String.format("#%06X", rgb & 0xFFFFFF);
	}

	private void parseHex(String s, boolean first) {
		String t = s.trim().replace("#", "");
		if (t.length() != 6) return;
		try {
			int v = Integer.parseInt(t, 16);
			int cur = first ? HoloConfig.get().accentColor : HoloConfig.get().accent2Color;
			if ((cur & 0xFFFFFF) == v) return;
			if (first) HoloConfig.get().accentColor = v;
			else HoloConfig.get().accent2Color = v;
			HoloConfig.get().themePreset = -1;
		} catch (NumberFormatException ignored) {
		}
	}

	// =====================================================================
	// ABOUT / credits
	// =====================================================================

	private void renderAbout(GuiGraphics g, int mx, int my) {
		int top = cy + 2, h = ch - 6;
		beginList(g, cx, top, cw, h);
		int y = top + 6 - scrollOffset();
		long t = System.currentTimeMillis();

		// big animated logo
		String logo = "HOLOPRINT";
		int scaleW = 0;
		for (int i = 0; i < logo.length(); i++) scaleW += font.width(String.valueOf(logo.charAt(i))) + 3;
		int lx = cx + (cw - scaleW) / 2;
		for (int i = 0; i < logo.length(); i++) {
			String c = String.valueOf(logo.charAt(i));
			float wave = HoloConfig.get().animatedUi ? (float) Math.sin(t / 300.0 + i * 0.6) * 2f : 0f;
			int col = Ui.lerp(Ui.accent(), Ui.accent2(), i / (float) (logo.length() - 1));
			g.drawString(font, c, lx, y + 4 + (int) wave, Ui.a(col, 0xFF), true);
			lx += font.width(c) + 3;
		}
		y += 18;
		Ui.hGradient(g, cx + cw / 2 - 70, y, 140, 1, Ui.accent(), Ui.accent2(), 0xE0);
		y += 6;
		Ui.centered(g, font, "Version " + HoloPrintClient.VERSION + "  ·  Fabric 1.21.11", cx + cw / 2, y, Ui.MUTED);
		y += 18;

		g.drawString(font, "CREDITS", cx + 8, y, Ui.DIM, false);
		y += 12;
		g.drawString(font, "Created by", cx + 8, y, Ui.MUTED, false);
		g.drawString(font, "itzrealmacer", cx + 80, y, Ui.a(Ui.accent(), 0xFF), false);
		g.drawString(font, "(Joseph)", cx + 80 + font.width("itzrealmacer") + 5, y, Ui.TEXT, false);
		y += 12;
		g.drawString(font, "Online results", cx + 8, y, Ui.MUTED, false);
		g.drawString(font, "MineMev and the schematic sites it indexes", cx + 80, y, Ui.TEXT, false);
		y += 12;
		g.drawString(font, "Schematics", cx + 8, y, Ui.MUTED, false);
		g.drawString(font, "belong to their builders — credit them when you share", cx + 80, y, Ui.TEXT, false);
		y += 20;

		g.drawString(font, "KEYS  (change them in Options → Controls → HoloPrint)", cx + 8, y, Ui.DIM, false);
		y += 12;
		int colW = Math.max(150, (cw - 24) / 2);
		int i = 0;
		for (Map.Entry<String, KeyMapping> e : HoloPrintClient.KEYS) {
			int kx = cx + 8 + (i % 2) * colW;
			int ky = y + (i / 2) * 12;
			g.drawString(font, e.getKey(), kx, ky, Ui.MUTED, false);
			String keyName = e.getValue().getTranslatedKeyMessage().getString();
			g.drawString(font, keyName, kx + colW - 16 - font.width(keyName), ky, Ui.a(Ui.accent(), 0xFF), false);
			i++;
		}
		y += ((i + 1) / 2) * 12 + 10;

		g.drawString(font, "WHAT IT DOES", cx + 8, y, Ui.DIM, false);
		y += 12;
		String[] features = {
				"Loads .litematic, .schem and .nbt files from your schematics folder",
				"Search & one-click download builds online, with thumbnails and version filter",
				"Holograms that hide placed blocks and flag wrong ones",
				"Grab mode, auto-pick, finished-build preview and layer views",
				"Save your own builds as .litematic (works in Litematica too)",
				"Favorites, collections, chest memory and share codes",
		};
		for (String f : features) {
			g.fill(cx + 10, y + 3, cx + 13, y + 6, Ui.a(Ui.accent(), 0xFF));
			g.drawString(font, font.plainSubstrByWidth(f, cw - 30), cx + 18, y, Ui.TEXT, false);
			y += 11;
		}
		endList(g, y + scrollOffset() - top + 8);
	}

	// =====================================================================
	// input
	// =====================================================================

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		double mx = event.x(), my = event.y();
		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			for (int i = hits.size() - 1; i >= 0; i--) {
				Hit h = hits.get(i);
				if (h.contains(mx, my)) {
					setFocused(null);
					h.action().run();
					return true;
				}
			}
			if (tab == Tab.LIBRARY && p3w > 0 && mx >= p3x && my >= p3y && mx < p3x + p3w && my < p3y + p3h) {
				dragging3d = true;
				setFocused(null);
				return true;
			}
		}
		setFocused(null);
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging3d) {
			dragYaw += (float) dx * 0.02f;
			dragPitch += (float) dy * 0.015f;
			lastDragTime = System.currentTimeMillis();
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging3d) {
			dragging3d = false;
			lastDragTime = System.currentTimeMillis();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseX >= listX && mouseX < listX + listW && mouseY >= listY && mouseY < listY + listH) {
			double max = Math.max(0, listContentH - listH);
			double s = scroll.getOrDefault(tab, 0.0) - scrollY * 22;
			scroll.put(tab, Math.max(0, Math.min(max, s)));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (key == GLFW.GLFW_KEY_LEFT_SHIFT || key == GLFW.GLFW_KEY_RIGHT_SHIFT) shiftDown = true;
		if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && getFocused() == searchBox) {
			doSearch(1);
			return true;
		}
		if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && getFocused() == saveBox) {
			if (Selection.complete() && !saving) saveSelection();
			return true;
		}
		if (getFocused() == null && key == GLFW.GLFW_KEY_TAB) {
			Tab[] t = Tab.values();
			switchTab(t[(tab.ordinal() + 1) % t.length]);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_LEFT_SHIFT || event.key() == GLFW.GLFW_KEY_RIGHT_SHIFT) shiftDown = false;
		return super.keyReleased(event);
	}
}
