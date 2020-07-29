package org.samo_lego.simpleauth.commands;

import com.google.gson.JsonObject;
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
import static org.samo_lego.simpleauth.SimpleAuth.THREADPOOL;
import static org.samo_lego.simpleauth.SimpleAuth.config;
import static org.samo_lego.simpleauth.utils.UuidConverter.convertUuid;


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
        else if(SimpleAuth.isAuthenticated(player)) {
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
            String hash = AuthHelper.hashPassword(pass1.toCharArray());
            // JSON object holding password (may hold some other info in the future)
            JsonObject playerdata = new JsonObject();
            playerdata.addProperty("password", hash);

            if (SimpleAuth.DB.registerUser(convertUuid(player), playerdata.toString())) {
                SimpleAuth.authenticatePlayer(player, new StringTextComponent(config.lang.registerSuccess));
                return;
            }
            player.sendMessage(new StringTextComponent(config.lang.alreadyRegistered), false);
        });
        return 0;
    }
}
