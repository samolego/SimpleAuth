package org.samo_lego.simpleauth.storage;

import com.google.gson.*;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.event.entity.player.PlayerEvent;

import java.util.Objects;

import static org.samo_lego.simpleauth.SimpleAuth.DB;
import static org.samo_lego.simpleauth.SimpleAuth.config;
import static org.samo_lego.simpleauth.utils.SimpleLogger.logInfo;

/**
 * Class used for storing the non-authenticated player's cache
 */
public class PlayerCache {
    /**
     * Whether player is registered.
     */
    public boolean isRegistered;
    /**
     * Whether player is authenticated.
     * Used for {@link org.samo_lego.simpleauth.event.AuthEventHandler#onPlayerJoin(PlayerEvent.PlayerLoggedInEvent) session validation}.
     */
    public boolean isAuthenticated;
    /**
     * Hashed password of player.
     */
    public String password;
    /**
     * Stores how many times player has tried to login.
     */
    public int loginTries;
    /**
     * Last recorded IP of player.
     * Used for {@link org.samo_lego.simpleauth.event.AuthEventHandler#onPlayerJoin(PlayerEvent.PlayerLoggedInEvent) sessions}.
     */
    public String lastIp;
    /**
     * Time until session is valid.
     */
    public long validUntil;

    /**
     * Player stats before de-authentication.
     */
    public int lastAir;
    public boolean wasOnFire;
    public boolean wasInPortal;

    /**
     * Last recorded position before de-authentication.
     */
    public static class LastLocation {
        public ServerWorld dimension;
        public Vector3d position;
        public float yaw;
        public float pitch;
    }

    public PlayerCache.LastLocation lastLocation = new PlayerCache.LastLocation();


    private static final Gson gson = new Gson();

    public PlayerCache(String uuid, ServerPlayerEntity player) {
        if(DB.isClosed())
            return;

        if(player != null) {
            if(config.experimental.debugMode)
                logInfo("Creating cache for " + player.getName().asString());
            this.lastIp = player.getIp();

            this.lastAir = player.getAir();
            this.wasOnFire = player.isOnFire();

            // Setting position cache
            this.lastLocation.dimension = player.getServerWorld();
            this.lastLocation.position = player.getPos();
            this.lastLocation.yaw = player.yaw;
            this.lastLocation.pitch = player.pitch;

            this.wasInPortal = player.getBlockState().getBlock().equals(Blocks.NETHER_PORTAL);
        }
        else {
            this.wasOnFire = false;
            this.wasInPortal = false;
            this.lastAir = 300;
        }

        String data = DB.getData(uuid);
        if(!data.isEmpty()) {
            // Getting (hashed) password
            JsonObject json = gson.fromJson(data, JsonObject.class);
            JsonElement passwordElement = json.get("password");
            if(passwordElement instanceof JsonNull) {
                if(player != null) {
                    player.sendMessage(new StringTextComponent(config.lang.corruptedPlayerData), false);
                }

                if(config.experimental.debugMode)
                    logInfo("Password for " + uuid + " is null! Marking as not registered.");
                this.password = "";
                this.isRegistered = false;
            }
            else {
                this.password = passwordElement.getAsString();
                this.isRegistered = !this.password.isEmpty();
            }


            // DEPRECATED, UGLY
            if(config.main.spawnOnJoin) {
                try {
                    JsonElement lastLoc = json.get("lastLocation");
                    if (lastLoc != null) {
                        /* Getting DB coords */
                        JsonObject lastLocation = gson.fromJson(lastLoc.getAsString(), JsonObject.class);
                        assert player != null;
                        this.lastLocation.dimension = Objects.requireNonNull(player.getServer()).getWorld(RegistryKey.of(Registry.DIMENSION, new ResourceLocation(
                                lastLocation.get("dim").isJsonNull() ? config.worldSpawn.dimension : lastLocation.get("dim").getAsString())));

                        this.lastLocation.position = new Vector3d(
                                lastLocation.get("x").isJsonNull() ? config.worldSpawn.x : lastLocation.get("x").getAsDouble(),
                                lastLocation.get("y").isJsonNull() ? config.worldSpawn.y : lastLocation.get("y").getAsDouble(),
                                lastLocation.get("z").isJsonNull() ? config.worldSpawn.z : lastLocation.get("z").getAsDouble()
                        );
                        this.lastLocation.yaw = lastLocation.get("yaw") == null ? 90 : lastLocation.get("yaw").getAsFloat();
                        this.lastLocation.pitch = lastLocation.get("pitch") == null ? 0 : lastLocation.get("pitch").getAsFloat();

                    }
                } catch (JsonSyntaxException ignored) {
                    // Player didn't have any coords in db to tp to
                }
            }
        }
        else {
            this.isRegistered = false;
            this.password = "";
        }
        this.isAuthenticated = false;
        this.loginTries = 0;

        if(config.experimental.debugMode)
            logInfo("Cache created. Registered: " + this.isRegistered + ", hashed password: " + this.password + ".");
    }
}
