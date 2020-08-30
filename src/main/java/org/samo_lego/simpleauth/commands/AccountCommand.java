package org.samo_lego.simpleauth.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.text.StringTextComponent;
import org.samo_lego.simpleauth.SimpleAuth;
import org.samo_lego.simpleauth.utils.AuthHelper;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;
import static org.samo_lego.simpleauth.SimpleAuth.*;
import static org.samo_lego.simpleauth.SimpleAuth.THREADPOOL;
import static org.samo_lego.simpleauth.SimpleAuth.config;
import static org.samo_lego.simpleauth.utils.UuidConverter.convertUuid;

public class AccountCommand {

    public static void registerCommand(CommandDispatcher<CommandSource> dispatcher) {
        // Registering the "/account" command
        dispatcher.register(Commands.literal("account")
            .then(Commands.literal("unregister")
                .executes(ctx -> {
                    ctx.getSource().getPlayer().sendMessage(
                            new StringTextComponent(config.lang.enterPassword),
                            false
                    );
                    return 1;
                })
                .then(Commands.argument("password", word())
                        .executes( ctx -> unregister(
                                ctx.getSource(),
                                getString(ctx, "password")
                                )
                        )
                )
            )
            .then(Commands.literal("changePassword")
                .then(Commands.argument("old password", word())
                    .executes(ctx -> {
                        ctx.getSource().getPlayer().sendMessage(
                                new StringTextComponent(config.lang.enterNewPassword),
                                false);
                        return 1;
                    })
                    .then(Commands.argument("new password", word())
                            .executes( ctx -> changePassword(
                                    (CommandSource) ctx.getSource(),
                                        getString(ctx, "old password"),
                                        getString(ctx, "new password")
                                        )
                                )
                        )
                    )
                )
        );
    }

    // Method called for checking the password and then removing user's account from db
    private static int unregister(CommandSource source, String pass) throws CommandSyntaxException {
        // Getting the player who send the command
        ServerPlayerEntity player = source.getPlayer();

        if (config.main.enableGlobalPassword) {
            player.sendMessage(
                    new StringTextComponent(config.lang.cannotUnregister),
                    false
            );
            return 0;
        }

        // Different thread to avoid lag spikes
        THREADPOOL.submit(() -> {
            if (AuthHelper.checkPass(convertUuid(player), pass.toCharArray()) == 1) {
                DB.deleteUserData(convertUuid(player));
                player.sendMessage(
                        new StringTextComponent(config.lang.accountDeleted),
                        false
                );
                SimpleAuth.deauthenticatePlayer(player);
                return;
            }
            player.sendMessage(
                    new StringTextComponent(config.lang.wrongPassword),
                    false
            );
        });
        return 0;
    }

    // Method called for checking the password and then changing it
    private static int changePassword(CommandSource source, String oldPass, String newPass) throws CommandSyntaxException {
        // Getting the player who send the command
        ServerPlayerEntity player = source.getPlayer();

        if (config.main.enableGlobalPassword) {
            player.sendMessage(
                    new StringTextComponent(config.lang.cannotChangePassword),
                    false
            );
            return 0;
        }
        // Different thread to avoid lag spikes
        THREADPOOL.submit(() -> {
            if (AuthHelper.checkPass(convertUuid(player), oldPass.toCharArray()) == 1) {
                if (newPass.length() < config.main.minPasswordChars) {
                    player.sendMessage(new StringTextComponent(
                            String.format(config.lang.minPasswordChars, config.main.minPasswordChars)
                    ), false);
                    return;
                }
                else if (newPass.length() > config.main.maxPasswordChars && config.main.maxPasswordChars != -1) {
                    player.sendMessage(new StringTextComponent(
                            String.format(config.lang.maxPasswordChars, config.main.maxPasswordChars)
                    ), false);
                    return;
                }
                // Changing password in playercache
                playerCacheMap.get(convertUuid(player)).password = AuthHelper.hashPassword(newPass.toCharArray());
                player.sendMessage(
                        new StringTextComponent(config.lang.passwordUpdated),
                        false
                );
            }
            else
                player.sendMessage(
                    new StringTextComponent(config.lang.wrongPassword),
                    false
                );
        });
        return 0;
    }
}
