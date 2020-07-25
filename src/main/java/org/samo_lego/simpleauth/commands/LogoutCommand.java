package org.samo_lego.simpleauth.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.text.StringTextComponent;
import org.samo_lego.simpleauth.SimpleAuth;

import static org.samo_lego.simpleauth.SimpleAuth.config;

public class LogoutCommand {

    public static void registerCommand(CommandDispatcher<CommandSource> dispatcher) {
        // Registering the "/logout" command
        dispatcher.register(Commands.literal("logout")
                .executes(ctx -> logout(ctx.getSource())) // Tries to deauthenticate user
        );
    }

    private static int logout(CommandSource serverCommandSource) throws CommandSyntaxException {
        ServerPlayerEntity player = serverCommandSource.getPlayer();
        SimpleAuth.deauthenticatePlayer(player);
        player.sendMessage(new StringTextComponent(config.lang.successfulLogout), false);
        return 1;
    }
}
