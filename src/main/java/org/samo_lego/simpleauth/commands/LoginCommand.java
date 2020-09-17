package org.samo_lego.simpleauth.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.text.StringTextComponent;
import org.samo_lego.simpleauth.utils.AuthHelper;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static org.samo_lego.simpleauth.SimpleAuth.*;
import static org.samo_lego.simpleauth.utils.UuidConverter.convertUuid;

public class LoginCommand {

    public static void registerCommand(CommandDispatcher<CommandSource> dispatcher) {
        // Registering the "/login" command
        dispatcher.register(Commands.literal("login")
                .then(Commands.argument("password", word())
                        .executes(ctx -> login(ctx.getSource(), getString(ctx, "password")) // Tries to authenticate user
                        ))
                .executes(ctx -> {
                    ctx.getSource().getPlayer().sendMessage(new StringTextComponent(config.lang.enterPassword), false);
                    return 0;
                }));
    }

    // Method called for checking the password
    private static int login(CommandSource source, String pass) throws CommandSyntaxException {
        // Getting the player who send the command
        ServerPlayerEntity player = source.getPlayer();
        String uuid = convertUuid(player);
        if (isAuthenticated(player)) {
            player.sendMessage(new StringTextComponent(config.lang.alreadyAuthenticated), false);
            return 0;
        }
        // Putting rest of the command in different thread to avoid lag spikes
        THREADPOOL.submit(() -> {
            int maxLoginTries = config.main.maxLoginTries;
            int passwordResult = AuthHelper.checkPassword(uuid, pass.toCharArray());

            if(playerCacheMap.get(uuid).loginTries >= maxLoginTries && maxLoginTries != -1) {
                player.networkHandler.disconnect(new StringTextComponent(config.lang.loginTriesExceeded));
                return;
            }
            else if(passwordResult == 1) {
                authenticatePlayer(player, new StringTextComponent(config.lang.successfullyAuthenticated));
                return;
            }
            else if(passwordResult == -1) {
                player.sendMessage(new StringTextComponent(config.lang.registerRequired), false);
                return;
            }
            // Kicking the player out
            else if(maxLoginTries == 1) {
                player.networkHandler.disconnect(new StringTextComponent(config.lang.wrongPassword));
                return;
            }
            // Sending wrong pass message
            player.sendMessage(new StringTextComponent(config.lang.wrongPassword), false);
            // ++ the login tries
            playerCacheMap.get(uuid).loginTries += 1;
        });
        return 0;
    }
}
