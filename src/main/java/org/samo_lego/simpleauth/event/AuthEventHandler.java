package org.samo_lego.simpleauth.event;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.block.Blocks;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.play.server.SChangeBlockPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.samo_lego.simpleauth.storage.PlayerCache;
import org.samo_lego.simpleauth.utils.PlayerAuth;

import static net.minecraftforge.eventbus.api.EventPriority.HIGHEST;
import static org.samo_lego.simpleauth.SimpleAuth.config;
import static org.samo_lego.simpleauth.SimpleAuth.playerCacheMap;

/**
 * This class will take care of actions players try to do,
 * and cancel them if they aren't authenticated
 */
@Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class AuthEventHandler {

    // Player joining the server
    @SubscribeEvent(priority = HIGHEST)
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        ServerPlayerEntity player = (ServerPlayerEntity) event.getPlayer();
        // Checking if session is still valid
        String uuid = ((PlayerAuth) player).getFakeUuid();
        PlayerCache playerCache = playerCacheMap.getOrDefault(uuid, null);

        if (playerCache != null) {
            if (
                playerCache.isAuthenticated &&
                playerCache.validUntil >= System.currentTimeMillis() &&
                player.getIp().equals(playerCache.lastIp)
            ) {
                ((PlayerAuth) player).setAuthenticated(true);
                return;
            }
            player.setInvulnerable(config.experimental.playerInvulnerable);
            player.setInvisible(config.experimental.playerInvisible);

            // Invalidating session
            playerCache.isAuthenticated = false;
            if(config.main.spawnOnJoin)
                ((PlayerAuth) player).hidePosition(true);
        }
        else {
            ((PlayerAuth) player).setAuthenticated(false);
            playerCache = playerCacheMap.get(uuid);
            playerCache.wasOnFire = false;
        }


        // Tries to rescue player from nether portal
        //field_150427_aO --> NETHER_PORTAL
        //field_150350_a --> AIR
        if(config.main.tryPortalRescue && player.getBlockState().getBlock().equals(Blocks.NETHER_PORTAL)) {
            BlockPos pos = player.getBlockPos();

            // Teleporting player to the middle of the block
            player.teleport(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);

            // Faking portal blocks to be air
            SChangeBlockPacket feetPacket = new SChangeBlockPacket(pos, Blocks.AIR.getDefaultState());
            player.networkHandler.sendPacket(feetPacket);

            SChangeBlockPacket headPacket = new SChangeBlockPacket(pos.up(), Blocks.AIR.getDefaultState());
            player.networkHandler.sendPacket(headPacket);
        }
    }

    @SubscribeEvent(priority = HIGHEST)
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        ServerPlayerEntity player = (ServerPlayerEntity) event.getPlayer();

        // Setting that player was actually authenticated before leaving
        PlayerCache playerCache = playerCacheMap.get(convertUuid(player));
        if(playerCache == null)
            return;
        String uuid = ((PlayerAuth) player).getFakeUuid();

        if(((PlayerAuth) player).isAuthenticated()) {
            playerCache.lastIp = player.getIp();
            playerCache.lastAir = player.getAir();
            playerCache.wasOnFire = player.isOnFire();
            playerCache.wasInPortal = player.getBlockState().getBlock().equals(Blocks.NETHER_PORTAL);

            // Setting the session expire time
            if(config.main.sessionTimeoutTime != -1)
                playerCache.validUntil = System.currentTimeMillis() + config.main.sessionTimeoutTime * 1000;
        }
        else {
            ((PlayerAuth) player).hidePosition(false);
        }
    }

    // Player chatting
    @SubscribeEvent(priority = HIGHEST)
    public static void onPlayerChat(ServerChatEvent event) {
        ServerPlayerEntity player = event.getPlayer();
        if(!isAuthenticated(player) && !config.experimental.allowChat) {
            player.sendMessage(((PlayerAuth) player).getAuthMessage(), false);
            event.setCanceled(true);
        }
    }

    // Player commands
    @SubscribeEvent(priority = HIGHEST)
    public static void onPlayerCommand(CommandEvent event) throws CommandSyntaxException {
        try {
            String name = event.getParseResults().getContext().getNodes().get(0).getNode().getName();
            CommandSource src = event.getParseResults().getContext().getSource();

            if(src.getEntity() instanceof ServerPlayerEntity) {
                ServerPlayerEntity player = src.getPlayer();
                if(
                        !isAuthenticated(player) &&
                                !name.startsWith("login") &&
                                !name.startsWith("register")
                ) {
                    player.sendMessage(notAuthenticated(player), false);
                    event.setCanceled(true);
                }
            }
        } catch (Error ignored) { }
    }

    // Player movement
    @SubscribeEvent(priority = HIGHEST)
    public static void onPlayerMove(TickEvent.PlayerTickEvent event) {
        ServerPlayerEntity player = (ServerPlayerEntity) event.player;
        boolean auth = ((PlayerAuth) player).isAuthenticated();
        if(!auth && config.main.allowFalling && !player.isOnGround() && !player.isInsideWaterOrBubbleColumn()) {
            if(player.isInvulnerable())
                player.setInvulnerable(false);
        }
        // Otherwise movement should be disabled
        else if(!auth && !config.experimental.allowMovement) {
            if(!player.isInvulnerable())
                player.setInvulnerable(true);
            player.networkHandler.requestTeleport(player.getX(), player.getY(), player.getZ(), player.yaw, player.pitch);
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
        if(!((PlayerAuth) player).isAuthenticated() && !config.experimental.allowBlockUse) {
            player.sendMessage(((PlayerAuth) player).getAuthMessage(), false);
            event.setCanceled(true);
        }
    }

    // Punching a block
    @SubscribeEvent(priority = HIGHEST)
    public static void onAttackBlock(PlayerInteractEvent.LeftClickBlock event) {
        PlayerEntity player = event.getPlayer();
        if(!((PlayerAuth) player).isAuthenticated() && !config.experimental.allowBlockPunch) {
            player.sendMessage(((PlayerAuth) player).getAuthMessage(), false);
            event.setCanceled(true);
        }
    }

    // Using an item
    @SubscribeEvent(priority = HIGHEST)
    public static void onUseItem(PlayerInteractEvent.RightClickItem event) {
        PlayerEntity player = event.getPlayer();
        if(!((PlayerAuth) player).isAuthenticated() && !config.experimental.allowItemUse) {
            player.sendMessage(((PlayerAuth) player).getAuthMessage(), false);
            event.setCanceled(true);
        }
    }

    // Dropping an item
    @SubscribeEvent(priority = HIGHEST)
    public static void onDropItem(ItemTossEvent event) {
        PlayerEntity player = event.getPlayer();
        if(!((PlayerAuth) player).isAuthenticated() && !config.experimental.allowItemDrop) {
            player.sendMessage(((PlayerAuth) player).getAuthMessage(), false);
            event.setCanceled(true);
            player.inventory.insertStack(event.getEntityItem().getStack());
        }
    }

    // Attacking an entity
    @SubscribeEvent(priority = HIGHEST)
    public static void onAttackEntity(AttackEntityEvent event) {
        PlayerEntity player = event.getPlayer();
        if(!((PlayerAuth) player).isAuthenticated() && !config.experimental.allowEntityPunch) {
            player.sendMessage(((PlayerAuth) player).getAuthMessage(), false);
            event.setCanceled(true);
        }
    }

    // Interacting with entity
    @SubscribeEvent(priority = HIGHEST)
    public static void onUseEntity(PlayerInteractEvent.EntityInteract event) {
        PlayerEntity player = event.getPlayer();        if(!((PlayerAuth) player).isAuthenticated() && !config.main.allowEntityInteract) {
            player.sendMessage(((PlayerAuth) player).getAuthMessage(), false);
            event.setCanceled(true);
        }
    }
}