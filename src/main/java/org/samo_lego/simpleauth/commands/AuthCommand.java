package org.samo_lego.simpleauth.commands;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.command.argument.BlockPosArgument;
import net.minecraft.command.argument.DimensionArgument;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.StringTextComponent;
import org.samo_lego.simpleauth.SimpleAuth;
import org.samo_lego.simpleauth.storage.AuthConfig;
import org.samo_lego.simpleauth.storage.PlayerCache;
import org.samo_lego.simpleauth.utils.AuthHelper;

import java.io.File;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static org.samo_lego.simpleauth.SimpleAuth.*;
import static org.samo_lego.simpleauth.utils.SimpleLogger.logInfo;

public class AuthCommand {

    /**
     * Registers the "/auth" command
     * @param dispatcher
     */
    public static void registerCommand(CommandDispatcher<CommandSource> dispatcher) {
        dispatcher.register(Commands.literal("auth")
            .requires(source -> source.hasPermissionLevel(4))
            .then(Commands.literal("reload")
                .executes( ctx -> reloadConfig((CommandSource) ctx.getSource().getEntity()))
            )
            .then(Commands.literal("setGlobalPassword")
                    .then(Commands.argument("password", word())
                            .executes( ctx -> setGlobalPassword(
                                    (CommandSource) ctx.getSource(),
                                    getString(ctx, "password")
                            ))
                    )
            )
            .then(Commands.literal("setSpawn")
                    .executes( ctx -> setSpawn(
                        ctx.getSource(),
                        ctx.getSource().getEntityOrThrow().getEntityWorld().getRegistryKey().getValue(),
                        ctx.getSource().getEntityOrThrow().getX(),
                        ctx.getSource().getEntityOrThrow().getY(),
                        ctx.getSource().getEntityOrThrow().getZ()
                    ))
                    .then(Commands.argument("dimension", DimensionArgument.dimension())
                            .then(Commands.argument("position", BlockPosArgument.blockPos())
                                .executes(ctx -> setSpawn(
                                        ctx.getSource(),
                                        DimensionArgument.getDimensionArgument(ctx, "dimension").getRegistryKey().getValue(),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "position").getX(),
                                        // +1 to not spawn player in ground
                                        BlockPosArgument.getLoadedBlockPos(ctx, "position").getY() + 1,
                                        BlockPosArgument.getLoadedBlockPos(ctx, "position").getZ()
                                )
                            )
                        )
                    )
            )
            .then(Commands.literal("remove")
                .then(Commands.argument("uuid", word())
                    .executes( ctx -> removeAccount(
                            ctx.getSource(),
                            getString(ctx, "uuid")
                    ))
                )
            )
            .then(Commands.literal("register")
                .then(Commands.argument("uuid", word())
                    .then(Commands.argument("password", word())
                        .executes( ctx -> registerUser(
                                (CommandSource) ctx.getSource(),
                                getString(ctx, "uuid"),
                                getString(ctx, "password")
                        ))
                    )
                )
            )
            .then(Commands.literal("update")
                .then(Commands.argument("uuid", word())
                    .then(Commands.argument("password", word())
                        .executes( ctx -> updatePass(
                                ctx.getSource(),
                                getString(ctx, "uuid"),
                                getString(ctx, "password")
                        ))
                    )
                )
            )
        );
    }

    /**
     * Reloads the config file.
     *
     * @param sender executioner of the command
     * @return 0
     */
    public static int reloadConfig(Entity sender) {
        config = AuthConfig.load(new File("./mods/SimpleAuth/config.json"));

        if(sender != null)
            ((PlayerEntity) sender).sendMessage(new StringTextComponent(config.lang.configurationReloaded), false);
        else
            logInfo(config.lang.configurationReloaded);
        return 1;
    }

    /**
     * Sets global password.
     *
     * @param source executioner of the command
     * @param password password that will be set
     * @return 0
     */
    private static int setGlobalPassword(CommandSource source, String password) {
        // Getting the player who send the command
        Entity sender = source.getEntity();
        // Different thread to avoid lag spikes
        THREADPOOL.submit(() -> {
            // Writing the global pass to config
            config.main.globalPassword = AuthHelper.hashPassword(password.toCharArray());
            config.main.enableGlobalPassword = true;
            config.save(new File("./mods/SimpleAuth/config.json"));
        });

        if(sender != null)
            ((PlayerEntity) sender).sendMessage(new StringTextComponent(config.lang.globalPasswordSet), false);
        else
            logInfo(config.lang.globalPasswordSet);
        return 1;
    }

    /**
     * Sets {@link org.samo_lego.simpleauth.storage.AuthConfig.MainConfig.WorldSpawn global spawn}.
     *
     * @param source executioner of the command
     * @param world world id of global spawn
     * @param x x coordinate of the global spawn
     * @param y y coordinate of the global spawn
     * @param z z coordinate of the global spawn
     * @return 0
     */
    private static int setSpawn(CommandSource source, ResourceLocation world, double x, double y, double z) {
        // Setting config values and saving
        config.worldSpawn.dimension = String.valueOf(world);
        config.worldSpawn.x = x;
        config.worldSpawn.y = y;
        config.worldSpawn.z = z;
        config.main.spawnOnJoin = true;
        config.save(new File("./mods/SimpleAuth/config.json"));

        // Getting sender
        Entity sender = source.getEntity();
        if(sender != null)
            ((PlayerEntity) sender).sendMessage(new StringTextComponent(config.lang.worldSpawnSet), false);
        else
            logInfo(config.lang.worldSpawnSet);
        return 1;
    }

    /**
     * Deletes (unregisters) player's account.
     *
     * @param source executioner of the command
     * @param uuid uuid of the player to delete account for
     * @return 0
     */
    private static int removeAccount(CommandSource source, String uuid) {
        Entity sender = source.getEntity();
        THREADPOOL.submit(() -> {
            DB.deleteUserData(uuid);
            SimpleAuth.playerCacheMap.put(uuid, new PlayerCache(uuid, null));
        });

        if(sender != null)
            ((PlayerEntity) sender).sendMessage(new StringTextComponent(config.lang.userdataDeleted), false);
        else
            logInfo(config.lang.userdataDeleted);
        return 1; // Success
    }

    /**
     * Creates account for player.
     *
     * @param source executioner of the command
     * @param uuid uuid of the player to create account for
     * @param password new password for the player account
     * @return 0
     */
    private static int registerUser(CommandSource source, String uuid, String password) {
        // Getting the player who send the command
        Entity sender = source.getEntity();

        THREADPOOL.submit(() -> {
            PlayerCache playerCache;
            if(playerCacheMap.containsKey(uuid)) {
                playerCache = playerCacheMap.get(uuid);
            }
            else {
                playerCache = new PlayerCache(uuid, null);
            }

            playerCacheMap.put(uuid, playerCache);
            playerCacheMap.get(uuid).password = AuthHelper.hashPassword(password.toCharArray());
            playerCacheMap.get(uuid).isRegistered = true;

            if (sender != null)
                ((PlayerEntity) sender).sendMessage(new StringTextComponent(config.lang.userdataUpdated), false);
            else
                logInfo(config.lang.userdataUpdated);
        });
        return 0;
    }

    /**
     * Force-updates the player's password.
     *
     * @param source executioner of the command
     * @param uuid uuid of the player to update data for
     * @param password new password for the player
     * @return 0
     */
    private static int updatePass(CommandSource source, String uuid, String password) {
        // Getting the player who send the command
        Entity sender = source.getEntity();

        THREADPOOL.submit(() -> {
            PlayerCache playerCache;
            if(playerCacheMap.containsKey(uuid)) {
                playerCache = playerCacheMap.get(uuid);
            }
            else {
                playerCache = new PlayerCache(uuid, null);
            }

            playerCacheMap.put(uuid, playerCache);
            if(!playerCacheMap.get(uuid).isRegistered) {
                if (sender != null)
                    ((PlayerEntity) sender).sendMessage(new LiteralText(config.lang.userNotRegistered), false);
                else
                    logInfo(config.lang.userNotRegistered);
                return;
            }
            playerCacheMap.get(uuid).password = AuthHelper.hashPassword(password.toCharArray());

            if (sender != null)
                ((PlayerEntity) sender).sendMessage(new StringTextComponent(config.lang.userdataUpdated), false);
            else
                logInfo(config.lang.userdataUpdated);
        });
        return 0;
    }
}
