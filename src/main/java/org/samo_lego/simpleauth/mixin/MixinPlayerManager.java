package org.samo_lego.simpleauth.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.management.PlayerList;
import net.minecraft.stats.ServerStatisticsManager;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import org.samo_lego.simpleauth.utils.PlayerAuth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.io.File;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.samo_lego.simpleauth.SimpleAuth.config;

@Mixin(PlayerList.class)
public abstract class MixinPlayerManager {

    // Kicking player if there's a player with the same name already online
    @Inject(method = "checkCanJoin", at = @At("HEAD"), cancellable = true)
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

        if(onlinePlayer != null && config.experimental.preventAnotherLocationKick) {
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
    @ModifyVariable(
            method = "createStatHandler",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/PlayerEntity;getName()Lnet/minecraft/util/text/ITextComponent;"
            ),
            ordinal = 1
    )
    private File migrateOfflineStats(File file, PlayerEntity player) {
        if(config.main.premiumAutologin && ((PlayerAuth) player).isUsingMojangAccount()) {
            String playername = player.getGameProfile().getName();
            file = new File(file.getParent(), PlayerEntity.getOfflinePlayerUuid(playername) + ".json");
        }
        return file;
    }

    @Inject(
            method = "createStatHandler",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
            ),
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void migrateOfflineStats(PlayerEntity player, CallbackInfoReturnable<ServerStatisticsManager> cir, UUID uUID, ServerStatisticsManager serverStatHandler, File serverStatsDir, File playerStatFile) {
        File onlineFile = new File(serverStatsDir, uUID + ".json");
        if(config.main.premiumAutologin && ((PlayerAuth) player).isUsingMojangAccount() && !onlineFile.exists()) {
            ((ServerStatHandlerAccessor) serverStatHandler).setFile(onlineFile);
        }
    }

}
