package com.holoprint;

import java.util.List;
import java.util.Map;

import org.lwjgl.glfw.GLFW;

import com.holoprint.config.HoloConfig;
import com.holoprint.gui.HoloScreen;
import com.holoprint.gui.Toasts;
import com.holoprint.placement.Aim;
import com.holoprint.placement.ChestMemory;
import com.holoprint.placement.Placement;
import com.holoprint.placement.PlacementManager;
import com.holoprint.placement.Selection;
import com.holoprint.render.HologramRenderer;
import com.holoprint.render.HudOverlay;
import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class HoloPrintClient implements ClientModInitializer {
	public static final String MOD_ID = "holoprint";
	public static final String VERSION = "0.2.0";

	private static KeyMapping openMenu, toggleHolo, moveFwd, moveBack, moveLeft, moveRight, moveUp, moveDown,
			rotate, mirror, layerUp, layerDown, layerMode, grab, pick, afterView, corner1, corner2;

	/** Label + key, shown on the About page. */
	public static List<Map.Entry<String, KeyMapping>> KEYS = List.of();

	// ---- shared state read by the renderer / HUD
	public static Aim.Target lookTarget;
	public static boolean afterViewActive;
	public static boolean grabbing;
	public static BlockPos nextChest;

	private static double grabDistance = 8;
	private static boolean grabFree;
	private static boolean afterToggled;
	private static Aim.Target lastAutoPicked;
	private static BlockPos lastLookedBlock;
	private static BlockPos openContainerPos;
	private static int tickCounter;

	@Override
	public void onInitializeClient() {
		HoloConfig.load();
		KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));

		openMenu = key("open_menu", GLFW.GLFW_KEY_H, cat);
		toggleHolo = key("toggle_hologram", GLFW.GLFW_KEY_J, cat);
		moveFwd = key("move_forward", GLFW.GLFW_KEY_UP, cat);
		moveBack = key("move_back", GLFW.GLFW_KEY_DOWN, cat);
		moveLeft = key("move_left", GLFW.GLFW_KEY_LEFT, cat);
		moveRight = key("move_right", GLFW.GLFW_KEY_RIGHT, cat);
		moveUp = key("move_up", GLFW.GLFW_KEY_PAGE_UP, cat);
		moveDown = key("move_down", GLFW.GLFW_KEY_PAGE_DOWN, cat);
		rotate = key("rotate", GLFW.GLFW_KEY_R, cat);
		mirror = key("mirror", GLFW.GLFW_KEY_UNKNOWN, cat);
		layerUp = key("layer_up", GLFW.GLFW_KEY_KP_ADD, cat);
		layerDown = key("layer_down", GLFW.GLFW_KEY_KP_SUBTRACT, cat);
		layerMode = key("layer_mode", GLFW.GLFW_KEY_KP_MULTIPLY, cat);
		grab = key("grab", GLFW.GLFW_KEY_G, cat);
		pick = key("pick_block", GLFW.GLFW_KEY_V, cat);
		afterView = key("after_view", GLFW.GLFW_KEY_K, cat);
		corner1 = key("corner_1", GLFW.GLFW_KEY_KP_7, cat);
		corner2 = key("corner_2", GLFW.GLFW_KEY_KP_9, cat);

		KEYS = List.of(Map.entry("Open menu", openMenu), Map.entry("Show / hide", toggleHolo), Map.entry("Grab (hold)", grab),
				Map.entry("Pick block", pick), Map.entry("Finished view", afterView), Map.entry("Rotate", rotate),
				Map.entry("Mirror", mirror), Map.entry("Move forward", moveFwd), Map.entry("Move up", moveUp),
				Map.entry("Move down", moveDown), Map.entry("Layer up", layerUp), Map.entry("Layer down", layerDown),
				Map.entry("Layer mode", layerMode), Map.entry("Save corner 1", corner1), Map.entry("Save corner 2", corner2));

		ClientTickEvents.END_CLIENT_TICK.register(HoloPrintClient::onTick);
		WorldRenderEvents.BEFORE_TRANSLUCENT.register(HologramRenderer::render);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "hud"), HudOverlay::render);
	}

	private static KeyMapping key(String name, int code, KeyMapping.Category cat) {
		return KeyBindingHelper.registerKeyBinding(new KeyMapping("key.holoprint." + name, InputConstants.Type.KEYSYM, code, cat));
	}

	/** Called by the scroll mixin. Returns true if HoloPrint used the scroll (grab mode). */
	public static boolean onScroll(double amount) {
		Minecraft mc = Minecraft.getInstance();
		if (!grabbing || mc.screen != null || amount == 0) return false;
		grabDistance = Math.max(2, Math.min(64, grabDistance + Math.signum(amount)));
		grabFree = true;
		return true;
	}

	private static void onTick(Minecraft mc) {
		PlacementManager.tick(mc);
		tickCounter++;
		if (mc.player == null || mc.level == null) {
			grabbing = false;
			lookTarget = null;
			return;
		}
		HoloConfig cfg = HoloConfig.get();

		trackContainers(mc);

		while (openMenu.consumeClick()) mc.setScreen(new HoloScreen());
		while (toggleHolo.consumeClick()) {
			cfg.hologramVisible = !cfg.hologramVisible;
			cfg.save();
			Toasts.info(cfg.hologramVisible ? "Hologram on" : "Hologram off");
		}
		while (corner1.consumeClick()) {
			Selection.corner1 = aimedOrFeet(mc);
			Toasts.info("Corner 1: " + fmt(Selection.corner1));
		}
		while (corner2.consumeClick()) {
			Selection.corner2 = aimedOrFeet(mc);
			Toasts.info("Corner 2: " + fmt(Selection.corner2));
		}

		// finished-build ("after") view
		if (cfg.afterViewHold) {
			afterViewActive = afterView.isDown() && mc.screen == null;
			while (afterView.consumeClick()) { }
		} else {
			while (afterView.consumeClick()) {
				afterToggled = !afterToggled;
				Toasts.info(afterToggled ? "Showing finished build" : "Back to blueprint");
			}
			afterViewActive = afterToggled;
		}

		Placement p = PlacementManager.selected();
		handleGrab(mc, p, cfg);

		// what am I aiming at?
		lookTarget = Aim.find(mc, 7.0);
		while (pick.consumeClick()) {
			if (lookTarget != null) pickBlock(mc, lookTarget, true);
			else Toasts.info("Aim at a hologram block first");
		}
		if (cfg.autoPick && lookTarget != null && lookTarget.status() == Placement.MISSING) {
			if (lastAutoPicked == null || lastAutoPicked.placement() != lookTarget.placement() || lastAutoPicked.index() != lookTarget.index()) {
				pickBlock(mc, lookTarget, false);
			}
		}
		lastAutoPicked = lookTarget;

		if (tickCounter % 20 == 0) updateNextChest(mc, p, cfg);
		if (tickCounter % 100 == 0) ChestMemory.cleanupNear(mc);

		if (p == null) {
			for (KeyMapping k : new KeyMapping[]{moveFwd, moveBack, moveLeft, moveRight, moveUp, moveDown, rotate, mirror, layerUp, layerDown, layerMode}) {
				while (k.consumeClick()) { }
			}
			return;
		}

		Direction facing = mc.player.getDirection();
		boolean moved = false;
		while (moveFwd.consumeClick()) { step(p, facing, 1); moved = true; }
		while (moveBack.consumeClick()) { step(p, facing, -1); moved = true; }
		while (moveLeft.consumeClick()) { step(p, facing.getCounterClockWise(), 1); moved = true; }
		while (moveRight.consumeClick()) { step(p, facing.getClockWise(), 1); moved = true; }
		while (moveUp.consumeClick()) { p.move(0, 1, 0); moved = true; }
		while (moveDown.consumeClick()) { p.move(0, -1, 0); moved = true; }
		while (rotate.consumeClick()) { p.rotateClockwise(); moved = true; }
		while (mirror.consumeClick()) {
			p.cycleMirror();
			moved = true;
			Toasts.info("Mirror: " + Placement.mirrorName(p.mirror));
		}
		while (layerMode.consumeClick()) {
			p.layerMode = (p.layerMode + 1) % 3;
			moved = true;
			Toasts.info(Placement.layerModeName(p.layerMode));
		}
		while (layerUp.consumeClick()) { p.layer = Math.min(p.schematic.sizeY - 1, p.layer + 1); moved = true; }
		while (layerDown.consumeClick()) { p.layer = Math.max(0, p.layer - 1); moved = true; }
		if (moved) PlacementManager.save();
	}

	// ------------------------------------------------------------ grab mode

	private static void handleGrab(Minecraft mc, Placement p, HoloConfig cfg) {
		boolean down = grab.isDown() && mc.screen == null && p != null;
		while (grab.consumeClick()) { }
		if (down && !grabbing) {
			grabbing = true;
			grabFree = !cfg.grabSnap;
			Vec3 eye = mc.player.getEyePosition();
			double cx = (p.minX + p.maxX) / 2.0, cz = (p.minZ + p.maxZ) / 2.0;
			grabDistance = Math.max(3, Math.min(32, Math.sqrt((cx - eye.x) * (cx - eye.x) + (p.minY - eye.y) * (p.minY - eye.y) + (cz - eye.z) * (cz - eye.z))));
			Toasts.info("Grab: aim to move · scroll = distance");
		}
		if (!down && grabbing) {
			grabbing = false;
			if (p != null) {
				PlacementManager.save();
				Toasts.info("Dropped at " + fmt(p.origin));
			}
			return;
		}
		if (!grabbing || p == null) return;

		BlockPos anchor;
		HitResult hit = mc.hitResult;
		if (!grabFree && hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
			anchor = bhr.getBlockPos().relative(bhr.getDirection());
		} else {
			Vec3 point = mc.player.getEyePosition().add(mc.player.getLookAngle().scale(grabDistance));
			anchor = BlockPos.containing(point);
		}
		int w = p.maxX - p.minX, d = p.maxZ - p.minZ;
		p.moveTo(new BlockPos(anchor.getX() - w / 2, anchor.getY(), anchor.getZ() - d / 2));
	}

	// ------------------------------------------------------------ auto pick

	private static void pickBlock(Minecraft mc, Aim.Target t, boolean loud) {
		Item item = t.state().getBlock().asItem();
		String name = t.state().getBlock().getName().getString();
		if (item == Items.AIR) {
			if (loud) Toasts.info(name + " has no item");
			return;
		}
		Inventory inv = mc.player.getInventory();
		for (int i = 0; i < 9; i++) {
			if (inv.getItem(i).getItem() == item) {
				if (inv.getSelectedSlot() != i) inv.setSelectedSlot(i);
				return;
			}
		}
		if (!loud) return;
		for (int i = 9; i < 36 && i < inv.getContainerSize(); i++) {
			if (inv.getItem(i).getItem() == item) {
				Toasts.info(name + " is in your bag, not your hotbar");
				return;
			}
		}
		Toasts.error("You don't have " + name);
	}

	// ------------------------------------------------------------ chests

	private static void trackContainers(Minecraft mc) {
		if (mc.screen == null) {
			openContainerPos = null;
			if (mc.hitResult instanceof BlockHitResult bhr && mc.hitResult.getType() == HitResult.Type.BLOCK) {
				lastLookedBlock = bhr.getBlockPos().immutable();
			} else {
				lastLookedBlock = null;
			}
			return;
		}
		if (mc.screen instanceof AbstractContainerScreen<?> cs
				&& !(mc.screen instanceof InventoryScreen) && !(mc.screen instanceof CreativeModeInventoryScreen)) {
			if (openContainerPos == null && lastLookedBlock != null && mc.level.getBlockEntity(lastLookedBlock) != null) {
				openContainerPos = lastLookedBlock;
			}
			if (openContainerPos != null && tickCounter % 5 == 0) {
				if (ChestMemory.snapshot(openContainerPos, cs.getMenu(), mc.player.getInventory())) ChestMemory.save();
			}
		}
	}

	private static void updateNextChest(Minecraft mc, Placement p, HoloConfig cfg) {
		if (p == null || !cfg.showChestRoute || ChestMemory.chestCount() == 0) {
			nextChest = null;
			return;
		}
		var stops = ChestMemory.plan(HudOverlay.stillNeeded(p), mc.player.blockPosition());
		nextChest = stops.isEmpty() ? null : stops.get(0).pos();
	}

	// ------------------------------------------------------------ helpers

	private static BlockPos aimedOrFeet(Minecraft mc) {
		if (mc.hitResult instanceof BlockHitResult bhr && mc.hitResult.getType() == HitResult.Type.BLOCK) {
			return bhr.getBlockPos().immutable();
		}
		return mc.player.blockPosition();
	}

	public static String fmt(BlockPos p) {
		return p.getX() + ", " + p.getY() + ", " + p.getZ();
	}

	private static void step(Placement p, Direction d, int amount) {
		p.move(d.getStepX() * amount, 0, d.getStepZ() * amount);
	}
}
