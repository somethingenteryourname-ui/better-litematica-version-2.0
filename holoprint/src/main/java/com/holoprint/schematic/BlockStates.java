package com.holoprint.schematic;

import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Turns palette strings like "minecraft:oak_stairs[facing=north]" into real block states. */
public final class BlockStates {
	private BlockStates() {}

	public static BlockState parse(String text) {
		try {
			int b = text.indexOf('[');
			String id = b >= 0 ? text.substring(0, b) : text;
			Identifier key = Identifier.parse(id);
			Block block = BuiltInRegistries.BLOCK.getValue(key);
			if (block == null) return Blocks.AIR.defaultBlockState();
			BlockState state = block.defaultBlockState();
			if (b < 0) return state;
			String inner = text.substring(b + 1, text.endsWith("]") ? text.length() - 1 : text.length());
			for (String part : inner.split(",")) {
				int eq = part.indexOf('=');
				if (eq <= 0) continue;
				Property<?> prop = block.getStateDefinition().getProperty(part.substring(0, eq).trim());
				if (prop != null) state = with(state, prop, part.substring(eq + 1).trim());
			}
			return state;
		} catch (RuntimeException e) {
			// Unknown / modded block that isn't installed: show nothing for it.
			return Blocks.AIR.defaultBlockState();
		}
	}

	private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> prop, String value) {
		Optional<T> v = prop.getValue(value);
		return v.isPresent() ? state.setValue(prop, v.get()) : state;
	}

	public static String idOf(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}
}
