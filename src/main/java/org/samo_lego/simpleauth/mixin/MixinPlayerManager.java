package org.samo_lego.simpleauth.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.management.PlayerList;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import org.samo_lego.simpleauth.event.AuthEventHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

@Mixin(PlayerList.class)
public abstract class MixinPlayerManager {

    // Method for kicking player for
    @Inject(method = "Lnet/minecraft/server/management/PlayerList;checkCanJoin(Ljava/net/SocketAddress;Lcom/mojang/authlib/GameProfile;)Lnet/minecraft/util/text/ITextComponent;", at = @At("HEAD"), cancellable = true, remap = false)
    private void checkCanJoin(SocketAddress socketAddress, GameProfile profile, CallbackInfoReturnable<ITextComponent> cir) {
        // Getting the player that is trying to join the server
        PlayerList manager = (PlayerList) (Object) this;

        StringTextComponent returnText = (StringTextComponent) AuthEventHandler.checkCanPlayerJoinServer(profile, manager);

        if(returnText != null) {
            // Canceling player joining with the returnText message
            cir.setReturnValue(returnText);
        }
    }
}
