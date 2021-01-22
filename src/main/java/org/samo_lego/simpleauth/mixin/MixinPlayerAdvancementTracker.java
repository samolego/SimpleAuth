package org.samo_lego.simpleauth.mixin;

import net.minecraft.advancements.AdvancementManager;
import net.minecraft.advancements.PlayerAdvancements;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import org.samo_lego.simpleauth.utils.PlayerAuth;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;

import static org.samo_lego.simpleauth.SimpleAuth.config;

@Mixin(PlayerAdvancements.class)
public class MixinPlayerAdvancementTracker {

    @Mutable
    @Shadow
    @Final
    private File advancementFile;

    @Shadow
    private ServerPlayerEntity owner;

    @Inject(method = "load",  at = @At("HEAD"))
    private void startMigratingOfflineAdvancements(AdvancementManager advancementLoader, CallbackInfo ci) {
        if(config.main.premiumAutologin && ((PlayerAuth) this.owner).isUsingMojangAccount() && !this.advancementFile.isFile()) {
            // Migrate
            String playername = owner.getGameProfile().getName();
            this.advancementFile = new File(this.advancementFile.getParent(), PlayerEntity.getOfflinePlayerUuid(playername).toString() + ".json");
        }
    }

    @Inject(method = "load",  at = @At("TAIL"))
    private void endMigratingOfflineAdvancements(AdvancementManager advancementLoader, CallbackInfo ci) {
        if(config.main.premiumAutologin && ((PlayerAuth) this.owner).isUsingMojangAccount()) {
            this.advancementFile = new File(this.advancementFile.getParent(), owner.getUuid() + ".json");
        }
    }
}
