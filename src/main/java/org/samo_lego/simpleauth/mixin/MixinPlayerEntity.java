package org.samo_lego.simpleauth.mixin;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.World;
import org.samo_lego.simpleauth.SimpleAuth;
import org.samo_lego.simpleauth.storage.PlayerCache;
import org.samo_lego.simpleauth.utils.PlayerAuth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.samo_lego.simpleauth.SimpleAuth.*;

@Mixin(PlayerEntity.class)
public abstract class MixinPlayerEntity implements PlayerAuth {

    private final ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;

    // * 20 for 20 ticks in second
    private int kickTimer = config.main.kickTime * 20;

    private final MinecraftServer server = player.getServer();

    /**
     * Teleports player to spawn or last location that is recorded.
     * Last location means the location before de-authentication.
     *
     * @param hide whether to teleport player to spawn (provided in config) or last recorded position
     */
    @Override
    public void hidePosition(boolean hide) {
        assert server != null;

        PlayerCache cache = playerCacheMap.get(this.getFakeUuid());
        if (hide) {
            // Saving position
            cache.lastLocation.dimension = player.getServerWorld();
            cache.lastLocation.position = player.getPos();
            cache.lastLocation.yaw = player.yaw;
            cache.lastLocation.pitch = player.pitch;

            // Teleports player to spawn
            player.teleport(
                    server.getWorld(RegistryKey.of(Registry.DIMENSION, new ResourceLocation(config.worldSpawn.dimension))),
                    config.worldSpawn.x,
                    config.worldSpawn.y,
                    config.worldSpawn.z,
                    config.worldSpawn.yaw,
                    config.worldSpawn.pitch
            );
            return;
        }
        // Puts player to last cached position
        player.teleport(
                cache.lastLocation.dimension,
                cache.lastLocation.position.getX(),
                cache.lastLocation.position.getY(),
                cache.lastLocation.position.getZ(),
                cache.lastLocation.yaw,
                cache.lastLocation.pitch
        );
    }

    /**
     * Converts player uuid, to ensure player with "nAmE" and "NamE" get same uuid.
     * Both players are not allowed to play, since mod mimics Mojang behaviour.
     * of not allowing accounts with same names but different capitalization.
     *
     * @return converted UUID as string
     */
    @Override
    public String getFakeUuid() {
        // If server is in online mode online-mode UUIDs should be used
        assert server != null;
        if(server.isOnlineMode())
            return player.getUuidAsString();
        /*
            Lower case is used for Player and PlAyEr to get same UUID (for password storing)
            Mimicking Mojang behaviour, where players cannot set their name to
            ExAmple if Example is already taken.
        */
        String playername = player.getName().asString().toLowerCase();
        return PlayerEntity.getOfflinePlayerUuid(playername).toString();

    }

    @Override
    public boolean canSkipAuth() {
        return (isUsingMojangAccount() && config.main.premiumAutologin);
    }

    /**
     * Sets the authentication status of the player.
     *
     * @param authenticated whether player should be authenticated
     */
    @Override
    public void setAuthenticated(boolean authenticated) {
        PlayerCache playerCache;

        if(!playerCacheMap.containsKey(this.getFakeUuid())) {
            // First join
            playerCache = new PlayerCache(this.getFakeUuid(), player);
            playerCacheMap.put(this.getFakeUuid(), playerCache);
        }
        else {
            playerCache = playerCacheMap.get(this.getFakeUuid());
            if(this.isAuthenticated() == authenticated)
                return;
            playerCache.isAuthenticated = authenticated;
        }

        player.setInvulnerable(!authenticated && config.experimental.playerInvulnerable);
        player.setInvisible(!authenticated && config.experimental.playerInvisible);

        // Teleporting player (hiding / restoring position)
        if(config.main.spawnOnJoin)
            this.hidePosition(!authenticated);

        if(authenticated) {
            kickTimer = config.main.kickTime * 20;
            // Updating blocks if needed (if portal rescue action happened)
            if(playerCache.wasInPortal) {
                World world = player.getEntityWorld();
                BlockPos pos = player.getBlockPos();

                // Sending updates to portal blocks
                // This is technically not needed, but it cleans the "messed portal" on the client
                world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
                world.updateListeners(pos.up(), world.getBlockState(pos.up()), world.getBlockState(pos.up()), 3);
            }

            // Setting last air to player
            if(player.isSubmergedInWater())
                player.setAir(playerCache.lastAir);

            // In case player is in lava during authentication proccess
            if(!playerCache.wasOnFire)
                player.setFireTicks(0);
        }
    }

    /**
     * Gets the text which tells the player
     * to login or register, depending on account status.
     *
     * @return LiteralText with appropriate string (login or register)
     */
    @Override
    public ITextComponent getAuthMessage() {
        final PlayerCache cache = playerCacheMap.get(this.getFakeUuid());
        if(SimpleAuth.config.main.enableGlobalPassword || cache.isRegistered)
            return new StringTextComponent(
                    SimpleAuth.config.lang.notAuthenticated + "\n" + SimpleAuth.config.lang.loginRequired
            );
        return new StringTextComponent(
                SimpleAuth.config.lang.notAuthenticated+ "\n" + SimpleAuth.config.lang.registerRequired
        );
    }

    /**
     * Checks whether player is authenticated.
     *
     * @return false if player is not authenticated, otherwise true.
     */
    @Override
    public boolean isAuthenticated() {
        String uuid = this.getFakeUuid();
        return this.canSkipAuth() || playerCacheMap.containsKey(uuid) && playerCacheMap.get(uuid).isAuthenticated;
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void tick(CallbackInfo ci) {
        if(!this.isAuthenticated()) {
            // Checking player timer
            if(kickTimer <= 0 && player.networkHandler.getConnection().isOpen()) {
                player.networkHandler.disconnect(new StringTextComponent(config.lang.timeExpired));
            }
            else {
                // Sending authentication prompt every 10 seconds
                if(kickTimer % 200 == 0)
                    player.sendMessage(this.getAuthMessage(), false);
                kickTimer--;
            }
            ci.cancel();
        }
    }

    @Override
    public boolean isUsingMojangAccount() {
        return mojangAccountNamesCache.contains(player.getGameProfile().getName().toLowerCase());
    }
}