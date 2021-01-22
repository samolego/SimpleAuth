package org.samo_lego.simpleauth.mixin;

import com.mojang.authlib.GameProfile;
import io.netty.util.AttributeKey;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.login.ServerLoginNetHandler;
import net.minecraft.network.login.client.CLoginStartPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraftforge.fml.network.FMLHandshakeHandler;
import net.minecraftforge.fml.network.FMLNetworkConstants;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.samo_lego.simpleauth.SimpleAuth.*;
import static org.samo_lego.simpleauth.SimpleAuth.mojangAccountNamesCache;
import static org.samo_lego.simpleauth.utils.SimpleLogger.logError;

@Mixin(ServerLoginNetHandler.class)
public abstract class MixinServerLoginNetworkHandler {


    @Shadow private int loginTicks;
    @Shadow private GameProfile profile;
    @Shadow @Final public NetworkManager connection;
    @Shadow private ServerPlayerEntity player;
    /**
     * Fake state of current player.
     */
    @Unique
    private boolean acceptCrackedPlayer = false;

    private AttributeKey<FMLHandshakeHandler> FML_HANDSHAKE_HANDLER = AttributeKey.valueOf("fml:handshake");

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void preTick(CallbackInfo ci) {
        if (this.acceptCrackedPlayer && config.main.premiumAutologin) {
            boolean negotiationComplete = connection.channel().attr(FML_HANDSHAKE_HANDLER).get().tickServer();

            if (negotiationComplete) ((ServerLoginNetHandler) (Object) this).acceptPlayer();

            if (this.loginTicks++ == 600)
                ((ServerLoginNetHandler) (Object) this).disconnect(new TranslationTextComponent("multiplayer.disconnect.slow_login"));
            ci.cancel();
        }
    }

    /**
     * Checks whether the player has purchased an account.
     * If so, server is presented as online, and continues as in normal-online mode.
     * Otherwise, player is marked as ready to be accepted into the game.
     * @param packet
     * @param ci
     */
    @Inject(
            method = "onHello",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/login/client/CLoginStartPacket;getProfile()Lcom/mojang/authlib/GameProfile;",
                    shift = At.Shift.AFTER
            ),
            cancellable = true
    )
    private void checkPremium(CLoginStartPacket packet, CallbackInfo ci) {
        if(config.main.premiumAutologin) {
            try {
                String playername = packet.getProfile().getName().toLowerCase();
                Pattern pattern = Pattern.compile("^[a-z0-9_]{3,16}$");
                Matcher matcher = pattern.matcher(playername);
                if(playerCacheMap.containsKey(PlayerEntity.getOfflinePlayerUuid(playername).toString()) || !matcher.matches() || config.main.forcedOfflinePlayers.contains(playername)) {
                    // Player definitely doesn't have a mojang account
                    this.acceptCrackedPlayer = true;

                    this.profile = packet.getProfile();
                    ci.cancel();
                }
                else if(!mojangAccountNamesCache.contains(playername))  {
                    // Checking account status from API
                    HttpsURLConnection httpsURLConnection = (HttpsURLConnection) new URL("https://api.mojang.com/users/profiles/minecraft/" + playername).openConnection();
                    httpsURLConnection.setRequestMethod("GET");
                    httpsURLConnection.setConnectTimeout(5000);
                    httpsURLConnection.setReadTimeout(5000);

                    int response = httpsURLConnection.getResponseCode();
                    if (response == HttpURLConnection.HTTP_OK) {
                        // Player has a Mojang account
                        httpsURLConnection.disconnect();


                        // Caches the request
                        mojangAccountNamesCache.add(playername);
                        // Authentication continues in original method
                    }
                    else if(response == HttpURLConnection.HTTP_NO_CONTENT) {
                        // Player doesn't have a Mojang account
                        httpsURLConnection.disconnect();
                        this.acceptCrackedPlayer = true;

                        this.profile = packet.getProfile();
                        ci.cancel();
                    }
                }
            } catch (IOException e) {
                logError(e.getMessage());
            }
        }
    }

}
