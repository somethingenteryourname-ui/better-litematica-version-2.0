package com.holoprint.placement;

import com.holoprint.config.HoloConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Works out which hologram block you're looking at (walks the ray block by block). */
public final class Aim {
	private Aim() {}

	public record Target(Placement placement, int index) {
		public BlockState state() {
			return placement.state[index];
		}

		public BlockPos pos() {
			return new BlockPos(placement.wx[index], placement.wy[index], placement.wz[index]);
		}

		public byte status() {
			return placement.status[index];
		}
	}

	public static Target find(Minecraft mc, double reach) {
		if (mc.player == null || mc.level == null || !HoloConfig.get().hologramVisible) return null;
		if (PlacementManager.all().isEmpty()) return null;
		Vec3 eye = mc.player.getEyePosition();
		Vec3 dir = mc.player.getLookAngle();
		double ox = eye.x, oy = eye.y, oz = eye.z;
		double dx = dir.x, dy = dir.y, dz = dir.z;

		int x = (int) Math.floor(ox), y = (int) Math.floor(oy), z = (int) Math.floor(oz);
		int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
		double tDeltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
		double tDeltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
		double tDeltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
		double tMaxX = dx == 0 ? Double.MAX_VALUE : (dx > 0 ? (x + 1 - ox) : (ox - x)) * tDeltaX;
		double tMaxY = dy == 0 ? Double.MAX_VALUE : (dy > 0 ? (y + 1 - oy) : (oy - y)) * tDeltaY;
		double tMaxZ = dz == 0 ? Double.MAX_VALUE : (dz > 0 ? (z + 1 - oz) : (oz - z)) * tDeltaZ;

		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		double t = 0;
		boolean first = true;
		while (t <= reach) {
			for (Placement p : PlacementManager.all()) {
				if (!p.visible) continue;
				int idx = p.indexAt(x, y, z);
				if (idx >= 0 && p.status[idx] != Placement.CORRECT && p.layerVisible(p.localY[idx])) {
					return new Target(p, idx);
				}
			}
			if (!first) {
				pos.set(x, y, z);
				BlockState real = mc.level.getBlockState(pos);
				if (!real.isAir() && !real.canBeReplaced()) return null; // a real block is in the way
			}
			first = false;
			if (tMaxX < tMaxY && tMaxX < tMaxZ) {
				x += stepX;
				t = tMaxX;
				tMaxX += tDeltaX;
			} else if (tMaxY < tMaxZ) {
				y += stepY;
				t = tMaxY;
				tMaxY += tDeltaY;
			} else {
				z += stepZ;
				t = tMaxZ;
				tMaxZ += tDeltaZ;
			}
		}
		return null;
	}
}
