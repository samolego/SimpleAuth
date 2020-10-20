package org.samo_lego.simpleauth.mixin;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.container.Slot;
import org.samo_lego.simpleauth.utils.PlayerAuth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static org.samo_lego.simpleauth.SimpleAuth.config;

@Mixin(Slot.class)
public abstract class MixinSlot {
    // Denying item moving etc.
    @Inject(method = "canTakeItems", at = @At(value = "HEAD"), cancellable = true, remap = false)
    private void canTakeItems(PlayerEntity player, CallbackInfoReturnable<Boolean> cir) {
        if(!((PlayerAuth) player).isAuthenticated() && !config.experimental.allowItemMoving) {
            player.sendMessage(((PlayerAuth) player).getAuthMessage(), false);
            cir.setReturnValue(false);
        }
    }
}
