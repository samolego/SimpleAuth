package org.samo_lego.simpleauth.mixin;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.container.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static org.samo_lego.simpleauth.SimpleAuth.*;

@Mixin(Slot.class)
public abstract class MixinSlot {
    // Denying item moving etc.
    @Inject(method = "canTakeItems", at = @At(value = "HEAD"), cancellable = true, remap = false)
    private void canTakeItems(PlayerEntity player, CallbackInfoReturnable<Boolean> cir) {
        if(!isAuthenticated((ServerPlayerEntity) player) && !config.experimental.allowItemMoving) {
            player.sendMessage(notAuthenticated(player), false);
            cir.setReturnValue(false);
        }
    }
}
