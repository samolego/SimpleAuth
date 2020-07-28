package org.samo_lego.simpleauth;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.command.CommandSource;
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
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.server.FMLServerAboutToStartEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppedEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.iq80.leveldb.WriteBatch;
import org.samo_lego.simpleauth.commands.*;
import org.samo_lego.simpleauth.storage.AuthConfig;
import org.samo_lego.simpleauth.storage.PlayerCache;
import org.samo_lego.simpleauth.storage.SimpleAuthDatabase;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.iq80.leveldb.impl.Iq80DBFactory.bytes;
import static org.samo_lego.simpleauth.utils.SimpleLogger.logError;
import static org.samo_lego.simpleauth.utils.SimpleLogger.logInfo;
import static org.samo_lego.simpleauth.utils.UuidConverter.convertUuid;


@Mod(SimpleAuth.MODID)
public class SimpleAuth {

	public static final String MODID = "simpleauth";

    public static SimpleAuthDatabase DB = new SimpleAuthDatabase();

	public static final ExecutorService THREADPOOL = Executors.newCachedThreadPool();

	private static final Timer TIMER = new Timer();

	// HashMap of players that are not authenticated
	// Rather than storing all the authenticated players, we just store ones that are not authenticated
	// It stores some data as well, e.g. login tries and user password
	public static HashMap<String, PlayerCache> deauthenticatedUsers = new HashMap<>();

	// Boolean for easier checking if player is authenticated
	public static boolean isAuthenticated(ServerPlayerEntity player) {
		String uuid = convertUuid(player);
		return !deauthenticatedUsers.containsKey(uuid) || deauthenticatedUsers.get(uuid).wasAuthenticated;
	}

	// Getting game directory
	public static final Path gameDirectory = FMLPaths.GAMEDIR.get();

	// Server properties
	public static Properties serverProp = new Properties();

	// Mod config
	public static AuthConfig config;

	// Registering
	public SimpleAuth() {
		MinecraftForge.EVENT_BUS.register(this);
	}

	@SubscribeEvent
	public void onInitializeServer(FMLServerAboutToStartEvent event) {
		// Info I guess :D
		logInfo("SimpleAuth mod by samo_lego.");
		// The support on discord was great! I really appreciate your help.
		logInfo("This mod wouldn't exist without the great Fabric and Forge communities!");

		// Creating data directory (database and config files are stored there)
		File file = new File(gameDirectory + "/mods/SimpleAuth/leveldbStore");
		if (!file.exists() && !file.mkdirs())
		    throw new RuntimeException("[SimpleAuth] Error creating directory!");
		// Loading config
		config = AuthConfig.load(new File(gameDirectory + "/mods/SimpleAuth/config.json"));
		// Connecting to db
		DB.openConnection();

		try {
			serverProp.load(new FileReader(gameDirectory + "/server.properties"));
		} catch (IOException e) {
			logError("Error while reading server properties: " + e.getMessage());
		}

	}

	// Registering the commands
	@SubscribeEvent
	public void registerCommands(RegisterCommandsEvent event) {
		CommandDispatcher<CommandSource> dispatcher = event.getDispatcher();

		RegisterCommand.registerCommand(dispatcher);
		LoginCommand.registerCommand(dispatcher);
		LogoutCommand.registerCommand(dispatcher);
		AccountCommand.registerCommand(dispatcher);
		AuthCommand.registerCommand(dispatcher);
	}

	@SubscribeEvent
	public void onStopServer(FMLServerStoppedEvent event) {
		logInfo("Shutting down SimpleAuth.");

		WriteBatch batch = DB.getLevelDBStore().createWriteBatch();
		// Writing coords of de-authenticated players to database
		deauthenticatedUsers.forEach((uuid, playerCache) -> {
			JsonObject data = new JsonObject();
			data.addProperty("password", playerCache.password);

			JsonObject lastLocation = new JsonObject();
			lastLocation.addProperty("dim", playerCache.lastDim);
			lastLocation.addProperty("x", playerCache.lastX);
			lastLocation.addProperty("y", playerCache.lastY);
			lastLocation.addProperty("z", playerCache.lastZ);

			data.addProperty("lastLocation", lastLocation.toString());

			batch.put(bytes("UUID:" + uuid), bytes("data:" + data.toString()));
		});
		try {
			// Writing and closing batch
			DB.getLevelDBStore().write(batch);
			batch.close();
		} catch (IOException e) {
			logError("Error saving player data! " + e.getMessage());
		}

		// Closing DB connection
		DB.close();
	}

	// Getting not authenticated text
	public static ITextComponent notAuthenticated(PlayerEntity player) {
        final PlayerCache cache = deauthenticatedUsers.get(convertUuid(player));
        if(config.main.enableGlobalPassword || cache.isRegistered)
			return new StringTextComponent(
			        config.lang.notAuthenticated + "\n" + config.lang.loginRequired
            );
		return new StringTextComponent(
		        config.lang.notAuthenticated+ "\n" + config.lang.registerRequired
        );
	}

	// Authenticates player and sends the message
	public static void authenticatePlayer(ServerPlayerEntity player, ITextComponent msg) {
		PlayerCache playerCache = deauthenticatedUsers.get(convertUuid(player));
		// Teleporting player back
		if(config.main.spawnOnJoin)
			teleportPlayer(player, false);

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

		deauthenticatedUsers.remove(convertUuid(player));

		// Player no longer needs to be invisible and invulnerable
		player.setInvulnerable(false);
		player.setInvisible(false);
		player.sendMessage(msg, false);
	}

	// De-authenticates player
	public static void deauthenticatePlayer(ServerPlayerEntity player) {
		if(DB.isClosed())
			return;

		// Marking player as not authenticated
		String uuid = convertUuid(player);
		deauthenticatedUsers.put(uuid, new PlayerCache(uuid, player));

		// Teleporting player to spawn to hide its position
		if(config.main.spawnOnJoin)
			teleportPlayer(player, true);

		// Player is now not authenticated
		player.sendMessage(notAuthenticated(player), false);

		// Setting the player to be invisible to mobs and also invulnerable
		player.setInvulnerable(config.experimental.playerInvulnerable);
		player.setInvisible(config.experimental.playerInvisible);

		// Timer should start only if player has joined, not left the server (in case os session)
		if(player.networkHandler.getConnection().isOpen())
			TIMER.schedule(new TimerTask() {
				@Override
				public void run() {
					// Kicking player if not authenticated
					if(!isAuthenticated(player) && player.networkHandler.getConnection().isOpen())
						player.networkHandler.disconnect(new StringTextComponent(config.lang.timeExpired));
				}
			}, config.main.delay * 1000);
		Timer timer = new Timer();
		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				// Kicking player if not authenticated
				if(!SimpleAuth.isAuthenticated(player) && player.networkHandler.getConnection().isOpen())
					player.networkHandler.disconnect(new StringTextComponent(config.lang.timeExpired));
			}
		}, config.main.delay * 1000);
	}


	// Teleports player to spawn or last location when authenticating
	public static void teleportPlayer(ServerPlayerEntity player, boolean toSpawn) {
		MinecraftServer server = player.getServer();
		if(server == null || config.worldSpawn.dimension == null)
			return;

		if (toSpawn) {
			// Teleports player to spawn
			//field_239699_ae_ --> DIMENSION
			player.teleport(
					Objects.requireNonNull(server.getWorld(RegistryKey.of(Registry.field_239699_ae_, new ResourceLocation(config.worldSpawn.dimension)))),
					config.worldSpawn.x,
					config.worldSpawn.y,
					config.worldSpawn.z,
					90,
					90
			);
			return;
		}
		PlayerCache cache = deauthenticatedUsers.get(convertUuid(player));
		// Puts player to last cached position
		try {
			player.teleport(
					Objects.requireNonNull(server.getWorld(RegistryKey.of(Registry.field_239699_ae_, new ResourceLocation(cache.lastDim)))),
					cache.lastX,
					cache.lastY,
					cache.lastZ,
					0,
					0
			);
		} catch (Error e) {
			player.sendMessage(new StringTextComponent(config.lang.corruptedPlayerData), false);
			logError("Couldn't teleport player " + player.getName().asString());
			logError(
				String.format("Last recorded position is X: %s, Y: %s, Z: %s in dimension %s",
				cache.lastX,
				cache.lastY,
				cache.lastZ,
				cache.lastDim
			));
		}
	}
}