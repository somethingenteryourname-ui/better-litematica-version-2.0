package com.holoprint.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.holoprint.HoloPrintClient;

import net.minecraft.client.MouseHandler;

/**
 * Lets the mouse wheel change the grab distance while grab mode is held.
 * Marked optional (require = 0): if a future version renames the method, the
 * game still starts and only scroll-in-grab-mode stops working.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true, require = 0)
	private void holoprint$onScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
		if (HoloPrintClient.onScroll(vertical)) ci.cancel();
	}
}
