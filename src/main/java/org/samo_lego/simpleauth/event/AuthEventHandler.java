package org.samo_lego.simpleauth.event;

import com.mojang.authlib.GameProfile;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.play.server.SChangeBlockPacket;
import net.minecraft.server.management.PlayerList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.samo_lego.simpleauth.mixin.BlockUpdateS2CPacketAccessor;
import org.samo_lego.simpleauth.storage.PlayerCache;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.minecraftforge.eventbus.api.EventPriority.HIGHEST;
import static org.samo_lego.simpleauth.SimpleAuth.*;
import static org.samo_lego.simpleauth.utils.UuidConverter.convertUuid;

/**
 * This class will take care of actions players try to do,
 * and cancel them if they aren't authenticated
 */
@Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class AuthEventHandler {

    // Player pre-join
    // Returns text as a reason for disconnect or null to pass
    public static ITextComponent checkCanPlayerJoinServer(GameProfile profile, PlayerList manager) {
        // Getting the player
        String incomingPlayerUsername = profile.getName();
        PlayerEntity onlinePlayer = manager.getPlayer(incomingPlayerUsername);

        // Checking if player username is valid
        String regex = config.main.usernameRegex;

        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(incomingPlayerUsername);

        if(onlinePlayer != null && config.experimental.disableAnotherLocationKick) {
            // Player needs to be kicked, since there's already a player with that name
            // playing on the server
            return new StringTextComponent(
                    String.format(
                            config.lang.playerAlreadyOnline, onlinePlayer.getName().asString()
                    )
            );
        }
        else if(!matcher.matches()) {
            return new StringTextComponent(
                    String.format(
                            config.lang.disallowedUsername, regex
                    )
            );
        }
        return null;
    }


    // Player joining the server
    @SubscribeEvent(priority = HIGHEST)
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        ServerPlayerEntity player = (ServerPlayerEntity) event.getPlayer();
        // Checking if session is still valid
        String uuid = convertUuid(player);
        PlayerCache playerCache = deauthenticatedUsers.getOrDefault(uuid, null);

        if (playerCache != null) {
            if (
                playerCache.wasAuthenticated &&
                playerCache.validUntil >= System.currentTimeMillis() &&
                player.getIp().equals(playerCache.lastIp)
            ) {
                deauthenticatedUsers.remove(uuid); // Makes player authenticated
                return;
            }
            // Invalidating session
            playerCache.wasAuthenticated = false;
            player.sendMessage(notAuthenticated(player), false);
        }
        else {
            deauthenticatePlayer(player);
            playerCache = deauthenticatedUsers.get(uuid);
        }

        if(config.main.spawnOnJoin)
            teleportPlayer(player, true);


        // Tries to rescue player from nether portal
        //field_150427_aO --> NETHER_PORTAL
        //field_150350_a --> AIR
        if(config.main.tryPortalRescue && player.getBlockState().getBlock().equals(Blocks.field_150427_aO)) {
            BlockPos pos = player.getBlockPos();

            // Faking portal blocks to be air

            SChangeBlockPacket feetPacket = new SChangeBlockPacket();
            ((BlockUpdateS2CPacketAccessor) feetPacket).setState(Blocks.field_150350_a.getDefaultState());
            ((BlockUpdateS2CPacketAccessor) feetPacket).setBlockPos(pos);
            player.networkHandler.sendPacket(feetPacket);

            SChangeBlockPacket headPacket = new SChangeBlockPacket();
            ((BlockUpdateS2CPacketAccessor) headPacket).setState(Blocks.field_150350_a.getDefaultState());
            ((BlockUpdateS2CPacketAccessor) headPacket).setBlockPos(pos.up());
            player.networkHandler.sendPacket(headPacket);

            // Teleporting player to the middle of the block
            player.teleport(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            playerCache.wasInPortal = true;
        }
    }

    @SubscribeEvent(priority = HIGHEST)
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        ServerPlayerEntity player = (ServerPlayerEntity) event.getPlayer();
        if(!isAuthenticated(player) || config.main.sessionTimeoutTime == -1)
            return;

        // Starting session
        // Putting player to deauthenticated player map
        deauthenticatePlayer(player);
        
        // Setting that player was actually authenticated before leaving
        PlayerCache playerCache = deauthenticatedUsers.get(convertUuid(player));
        if(playerCache == null)
            return;

        playerCache.wasAuthenticated = true;
        // Setting the session expire time
        playerCache.validUntil = System.currentTimeMillis() + config.main.sessionTimeoutTime * 1000;
    }

    // Player chatting
    @SubscribeEvent(priority = HIGHEST)
    public static void onPlayerChat(ServerChatEvent event) {
        ServerPlayerEntity player = event.getPlayer();
        // Getting the message to then be able to check it
        String msg = event.getMessage();
        if(
            !isAuthenticated((ServerPlayerEntity) player) &&
            !msg.startsWith("/login") &&
            !msg.startsWith("/register") &&
            (!config.experimental.allowChat || msg.startsWith("/"))
        ) {
            player.sendMessage(notAuthenticated(player), false);
            event.setCanceled(true);
        }
    }

    // Player movement
    @SubscribeEvent(priority = HIGHEST)
    public static void onPlayerMove(PlayerEvent event) {
        PlayerEntity player = event.getPlayer();
        if(!isAuthenticated((ServerPlayerEntity) player) && !config.experimental.allowMovement) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = HIGHEST)
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        PlayerEntity player = event.getPlayer();
        if(!isAuthenticated((ServerPlayerEntity) player) && !config.experimental.allowMovement) {
            event.setCanceled(true);
        }
    }

    // Using a block (right-click function)
    @SubscribeEvent(priority = HIGHEST)
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock event) {
        PlayerEntity player = event.getPlayer();
        if(!isAuthenticated((ServerPlayerEntity) player) && !config.experimental.allowBlockUse) {
            player.sendMessage(notAuthenticated(player), false);
            event.setCanceled(true);
        }
    }

    // Punching a block
    @SubscribeEvent(priority = HIGHEST)
    public static void onAttackBlock(PlayerInteractEvent.LeftClickBlock event) {
        PlayerEntity player = event.getPlayer();
        if(!isAuthenticated((ServerPlayerEntity) player) && !config.experimental.allowBlockPunch) {
            player.sendMessage(notAuthenticated(player), false);
            event.setCanceled(true);
        }
    }

    // Using an item
    @SubscribeEvent(priority = HIGHEST)
    public static void onUseItem(PlayerInteractEvent.RightClickItem event) {
        PlayerEntity player = event.getPlayer();
        if(!isAuthenticated((ServerPlayerEntity) player) && !config.experimental.allowItemUse) {
            player.sendMessage(notAuthenticated(player), false);
            //fail
        }
    }

    // Dropping an item
    @SubscribeEvent(priority = HIGHEST)
    public static void onDropItem(ItemTossEvent event) {
        PlayerEntity player = event.getPlayer();
        if(!isAuthenticated((ServerPlayerEntity) player) && !config.experimental.allowItemDrop) {
            player.sendMessage(notAuthenticated(player), false);
            event.setCanceled(true);
        }
    }
    // Changing inventory (item moving etc.)
    @SubscribeEvent(priority = HIGHEST)
    public static void onTakeItem(PlayerContainerEvent event) {
        PlayerEntity player = event.getPlayer();
        if(!isAuthenticated((ServerPlayerEntity) player) && !config.experimental.allowItemMoving) {
            player.sendMessage(notAuthenticated(player), false);
            event.setCanceled(true);
        }
    }

    // Attacking an entity
    @SubscribeEvent(priority = HIGHEST)
    public static void onAttackEntity(AttackEntityEvent event) {
        PlayerEntity player = event.getPlayer();
        if(!isAuthenticated((ServerPlayerEntity) player) && !config.experimental.allowEntityPunch) {
            player.sendMessage(notAuthenticated(player), false);
            event.setCanceled(true);
        }
    }

    // Interacting with entity
    @SubscribeEvent(priority = HIGHEST)
    public static void onUseEntity(PlayerInteractEvent.EntityInteract event) {
        PlayerEntity player = event.getPlayer();        if(!isAuthenticated((ServerPlayerEntity) player) && !config.main.allowEntityInteract) {
            player.sendMessage(notAuthenticated(player), false);
            event.setCanceled(true);
        }
    }
}