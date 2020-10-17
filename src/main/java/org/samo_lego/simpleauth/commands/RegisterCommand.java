package org.samo_lego.simpleauth.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.text.StringTextComponent;
import org.samo_lego.simpleauth.storage.PlayerCache;
import org.samo_lego.simpleauth.utils.PlayerAuth;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static org.samo_lego.simpleauth.SimpleAuth.*;
import static org.samo_lego.simpleauth.utils.AuthHelper.hashPassword;


public class RegisterCommand {

    public static void registerCommand(CommandDispatcher<CommandSource> dispatcher) {

        // Registering the "/register" command
        dispatcher.register(Commands.literal("register")
            .then(Commands.argument("password", word())
                .then(Commands.argument("passwordAgain", word())
                    .executes( ctx -> register((CommandSource) ctx.getSource(), getString(ctx, "password"), getString(ctx, "passwordAgain")))
            ))
        .executes(ctx -> {
            ctx.getSource().getPlayer().sendMessage(new StringTextComponent(config.lang.enterPassword), false);
            return 0;
        }));
    }

    // Method called for hashing the password & writing to DB
    private static int register(CommandSource source, String pass1, String pass2) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        if(config.main.enableGlobalPassword) {
            player.sendMessage(new StringTextComponent(config.lang.loginRequired), false);
            return 0;
        }
        else if(((PlayerAuth) player).isAuthenticated()) {
            player.sendMessage(new StringTextComponent(config.lang.alreadyAuthenticated), false);
            return 0;
        }
        else if(!pass1.equals(pass2)) {
            player.sendMessage(new StringTextComponent(config.lang.matchPassword), false);
            return 0;
        }
        // Different thread to avoid lag spikes
        THREADPOOL.submit(() -> {
            if(pass1.length() < config.main.minPasswordChars) {
                player.sendMessage(new StringTextComponent(
                        String.format(config.lang.minPasswordChars, config.main.minPasswordChars)
                ), false);
                return;
            }
            else if(pass1.length() > config.main.maxPasswordChars && config.main.maxPasswordChars != -1) {
                player.sendMessage(new StringTextComponent(
                        String.format(config.lang.maxPasswordChars, config.main.maxPasswordChars)
                ), false);
                return;
            }

            PlayerCache playerCache = playerCacheMap.get(((PlayerAuth) player).getFakeUuid());
            if (!playerCache.isRegistered) {
                ((PlayerAuth) player).setAuthenticated(true);
                player.sendMessage(new StringTextComponent(config.lang.registerSuccess), false);
                playerCache.password = hashPassword(pass1.toCharArray());
                playerCache.isRegistered = true;
                return;
            }
            player.sendMessage(new StringTextComponent(config.lang.alreadyRegistered), false);
        });
        return 0;
    }
}
