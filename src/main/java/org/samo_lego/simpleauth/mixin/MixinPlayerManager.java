package org.samo_lego.simpleauth.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.management.PlayerList;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.samo_lego.simpleauth.SimpleAuth.config;

@Mixin(PlayerList.class)
public abstract class MixinPlayerManager {

    // Kicking player if there's a player with the same name already online
    @Inject(method = "checkCanJoin", at = @At("HEAD"), cancellable = true, remap = false)
    private void checkCanJoin(SocketAddress socketAddress, GameProfile profile, CallbackInfoReturnable<ITextComponent> cir) {
        // Getting the player that is trying to join the server
        PlayerList manager = (PlayerList) (Object) this;

        // Getting the player
        String incomingPlayerUsername = profile.getName();
        PlayerEntity onlinePlayer = manager.getPlayer(incomingPlayerUsername);

        // Checking if player username is valid
        String regex = config.main.usernameRegex;

        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(incomingPlayerUsername);

        if(onlinePlayer != null && config.experimental.disableAnotherLocationKick) {
            // Player needs to be kicked, since there's already a player with that name
            // playing on the server
            cir.setReturnValue(new StringTextComponent(
                    String.format(
                            config.lang.playerAlreadyOnline, onlinePlayer.getName().asString()
                    )
            ));
        }
        else if(!matcher.matches()) {
            cir.setReturnValue(new StringTextComponent(
                    String.format(
                            config.lang.disallowedUsername, regex
                    )
            ));
        }

    }
}
