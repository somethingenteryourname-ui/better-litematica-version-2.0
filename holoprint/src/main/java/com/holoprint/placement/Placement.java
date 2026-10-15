package com.holoprint.placement;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import com.holoprint.schematic.BlockStates;
import com.holoprint.schematic.Schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One schematic placed in the world: where it is, how it's turned, and the
 * pre-computed world position + block state of every non-air block in it.
 */
public final class Placement {
	public static final byte UNKNOWN = 0, CORRECT = 1, MISSING = 2, WRONG_BLOCK = 3, WRONG_STATE = 4;

	public final Schematic schematic;
	private final BlockState[] palette;

	public BlockPos origin;
	public int rotation;  // 0..3 clockwise quarter turns
	public int mirror;    // 0 none, 1 left-right, 2 front-back
	public boolean visible = true;
	public int layerMode; // 0 all, 1 single layer, 2 all layers up to
	public int layer;

	// computed
	public int count;
	public int[] wx, wy, wz;
	public int[] localY;
	public BlockState[] state;
	public byte[] faces;      // bit per face that should be drawn (D,U,N,S,W,E)
	public byte[] status;
	public int minX, minY, minZ, maxX, maxY, maxZ;
	private int[] grid;
	private static final long GRID_LIMIT = 8L * 1024 * 1024;
	private int scanCursor;

	// stats (refreshed each full scan)
	public int statCorrect, statMissing, statWrong, statWrongState;

	public Placement(Schematic schematic, BlockPos origin) {
		this.schematic = schematic;
		this.origin = origin;
		this.palette = new BlockState[schematic.palette.length];
		for (int i = 0; i < palette.length; i++) palette[i] = BlockStates.parse(schematic.palette[i]);
		rebuild();
	}

	public String name() {
		return schematic.name;
	}

	public int sizeXRotated() {
		return (rotation & 1) == 0 ? schematic.sizeX : schematic.sizeZ;
	}

	public int sizeZRotated() {
		return (rotation & 1) == 0 ? schematic.sizeZ : schematic.sizeX;
	}

	public Rotation mcRotation() {
		return switch (rotation & 3) {
			case 1 -> Rotation.CLOCKWISE_90;
			case 2 -> Rotation.CLOCKWISE_180;
			case 3 -> Rotation.COUNTERCLOCKWISE_90;
			default -> Rotation.NONE;
		};
	}

	public Mirror mcMirror() {
		return switch (mirror) {
			case 1 -> Mirror.LEFT_RIGHT;
			case 2 -> Mirror.FRONT_BACK;
			default -> Mirror.NONE;
		};
	}

	/** Recomputes world positions, rotated states and visible faces. Call after any move/rotate/mirror. */
	public void rebuild() {
		Schematic s = schematic;
		int sx = s.sizeX, sy = s.sizeY, sz = s.sizeZ;
		int n = s.countNonAir();
		count = n;
		wx = new int[n]; wy = new int[n]; wz = new int[n]; localY = new int[n];
		state = new BlockState[n];
		status = new byte[n];
		faces = new byte[n];
		scanCursor = 0;

		int rx = sizeXRotated(), rz = sizeZRotated();
		long vol = (long) rx * sy * rz;
		boolean[] occ = new boolean[(int) vol];
		// grid lets us find "which schematic block is at this spot" fast (used for aiming at holograms)
		grid = vol <= GRID_LIMIT ? new int[(int) vol] : null;
		int[] tx = new int[n], tz = new int[n];
		Rotation rot = mcRotation();
		Mirror mir = mcMirror();
		Map<BlockState, BlockState> cache = new IdentityHashMap<>();

		int k = 0;
		for (int y = 0; y < sy; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					int p = s.cells[s.index(x, y, z)];
					if (s.paletteIsAir[p] || palette[p].isAir()) continue;
					if (k >= n) break;
					// mirror first, then rotate (same order vanilla structures use)
					int mx = x, mz = z;
					if (mirror == 1) mz = sz - 1 - z;
					else if (mirror == 2) mx = sx - 1 - x;
					int ox, oz;
					switch (rotation & 3) {
						case 1 -> { ox = sz - 1 - mz; oz = mx; }
						case 2 -> { ox = sx - 1 - mx; oz = sz - 1 - mz; }
						case 3 -> { ox = mz; oz = sx - 1 - mx; }
						default -> { ox = mx; oz = mz; }
					}
					tx[k] = ox; tz[k] = oz; localY[k] = y;
					occ[(y * rz + oz) * rx + ox] = true;
					if (grid != null) grid[(y * rz + oz) * rx + ox] = k + 1;
					BlockState base = palette[p];
					state[k] = cache.computeIfAbsent(base, b -> b.mirror(mir).rotate(rot));
					k++;
				}
			}
		}
		count = k;

		for (int i = 0; i < k; i++) {
			int x = tx[i], y = localY[i], z = tz[i];
			wx[i] = origin.getX() + x;
			wy[i] = origin.getY() + y;
			wz[i] = origin.getZ() + z;
			int f = 0;
			if (y == 0 || !occ[((y - 1) * rz + z) * rx + x]) f |= 1;
			if (y == sy - 1 || !occ[((y + 1) * rz + z) * rx + x]) f |= 2;
			if (z == 0 || !occ[(y * rz + z - 1) * rx + x]) f |= 4;
			if (z == rz - 1 || !occ[(y * rz + z + 1) * rx + x]) f |= 8;
			if (x == 0 || !occ[(y * rz + z) * rx + x - 1]) f |= 16;
			if (x == rx - 1 || !occ[(y * rz + z) * rx + x + 1]) f |= 32;
			faces[i] = (byte) f;
		}
		minX = origin.getX(); minY = origin.getY(); minZ = origin.getZ();
		maxX = minX + rx; maxY = minY + sy; maxZ = minZ + rz;
		statCorrect = statMissing = statWrong = statWrongState = 0;
		if (layer >= sy) layer = sy - 1;
		if (layer < 0) layer = 0;
	}

	public boolean layerVisible(int ly) {
		return switch (layerMode) {
			case 1 -> ly == layer;
			case 2 -> ly <= layer;
			default -> true;
		};
	}

	public static String layerModeName(int m) {
		return switch (m) {
			case 1 -> "Single layer";
			case 2 -> "Layers up to";
			default -> "All layers";
		};
	}

	public void move(int dx, int dy, int dz) {
		moveTo(origin.offset(dx, dy, dz));
	}

	/** Moving only shifts positions (much cheaper than a full rebuild), then re-checks blocks. */
	public void moveTo(BlockPos pos) {
		int dx = pos.getX() - origin.getX(), dy = pos.getY() - origin.getY(), dz = pos.getZ() - origin.getZ();
		if (dx == 0 && dy == 0 && dz == 0) return;
		origin = pos;
		for (int i = 0; i < count; i++) {
			wx[i] += dx;
			wy[i] += dy;
			wz[i] += dz;
			status[i] = UNKNOWN;
		}
		minX += dx; maxX += dx; minY += dy; maxY += dy; minZ += dz; maxZ += dz;
		scanCursor = 0;
		statCorrect = statMissing = statWrong = statWrongState = 0;
	}

	/** Index of the schematic block at a world position, or -1. */
	public int indexAt(int x, int y, int z) {
		if (grid == null || x < minX || y < minY || z < minZ || x >= maxX || y >= maxY || z >= maxZ) return -1;
		int rx = maxX - minX, rz = maxZ - minZ;
		return grid[((y - minY) * rz + (z - minZ)) * rx + (x - minX)] - 1;
	}

	public void rotateClockwise() {
		rotation = (rotation + 1) & 3;
		rebuild();
	}

	public void cycleMirror() {
		mirror = (mirror + 1) % 3;
		rebuild();
	}

	public static String mirrorName(int m) {
		return switch (m) {
			case 1 -> "Left/Right";
			case 2 -> "Front/Back";
			default -> "None";
		};
	}

	// ---------------- verification against the real world

	/** Scans up to {@code budget} blocks, continuing where the last call stopped. */
	public void scan(net.minecraft.world.level.Level level, int budget) {
		if (count == 0) return;
		int scanned = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		while (scanned < budget) {
			if (scanCursor >= count) {
				scanCursor = 0;
				recount();
				if (budget >= count) break;
			}
			int i = scanCursor++;
			scanned++;
			pos.set(wx[i], wy[i], wz[i]);
			if (!level.hasChunkAt(pos)) {
				status[i] = UNKNOWN;
				continue;
			}
			BlockState actual = level.getBlockState(pos);
			BlockState want = state[i];
			if (actual == want) status[i] = CORRECT;
			else if (actual.isAir() || actual.canBeReplaced()) status[i] = MISSING;
			else if (actual.getBlock() == want.getBlock()) status[i] = WRONG_STATE;
			else status[i] = WRONG_BLOCK;
		}
	}

	private void recount() {
		int c = 0, m = 0, w = 0, ws = 0;
		for (int i = 0; i < count; i++) {
			switch (status[i]) {
				case CORRECT -> c++;
				case MISSING -> m++;
				case WRONG_BLOCK -> w++;
				case WRONG_STATE -> ws++;
				default -> m++;
			}
		}
		statCorrect = c; statMissing = m; statWrong = w; statWrongState = ws;
	}

	public float progress() {
		return count == 0 ? 1f : statCorrect / (float) count;
	}

	/** Block -> how many are needed in total, and how many are still not placed correctly. */
	public Map<net.minecraft.world.level.block.Block, int[]> materials() {
		Map<net.minecraft.world.level.block.Block, int[]> map = new HashMap<>();
		for (int i = 0; i < count; i++) {
			int[] v = map.computeIfAbsent(state[i].getBlock(), b -> new int[2]);
			v[0]++;
			if (status[i] != CORRECT) v[1]++;
		}
		return map;
	}
}
