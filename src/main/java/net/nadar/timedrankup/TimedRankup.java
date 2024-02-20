package net.nadar.timedrankup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.group.GroupManager;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.query.QueryOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.util.*;
import java.util.function.Supplier;

public class TimedRankup implements ModInitializer {

	private static final Logger LOGGER = LogManager.getLogger();
	private final Map<UUID, Long> playerPlaytimes = new HashMap<>();
	private static final String PLAYTIME_FILE_PATH = "config/TimedRankup/playtime.txt";
	private static final String CONFIG_FILE_PATH = "config/TimedRankup/timedrankup_ranks.json";
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private List<RankConfig> rankConfigs;

	@Override
	public void onInitialize() {
		// Check and load config
		loadConfig();

		// Register server tick event listener
		ServerTickEvents.START_SERVER_TICK.register(this::onServerTick);

		// Register commands
		registerCommands();
	}

	private void loadConfig() {
		File configFile = new File(CONFIG_FILE_PATH);
		if (!configFile.exists()) {
			generateDefaultConfig();
		}

		try (Reader reader = new FileReader(CONFIG_FILE_PATH)) {
			Config config = gson.fromJson(reader, Config.class);
			if (config != null && config.ranks != null) {
				rankConfigs = config.ranks;
			}
		} catch (IOException e) {
			LOGGER.error("Error reading config file: {}", e.getMessage());
		}
	}

	private void generateDefaultConfig() {
		List<RankConfig> defaultRanks = List.of(
				new RankConfig("Newbie", 3600),
				new RankConfig("Regular", 10800),
				new RankConfig("Veteran", 21600)
		);

		Config defaultConfig = new Config(defaultRanks);

		File configFile = new File(CONFIG_FILE_PATH);
		File parentDirectory = configFile.getParentFile();
		if (!parentDirectory.exists()) {
			boolean success = parentDirectory.mkdirs();
			if (!success) {
				LOGGER.error("Failed to create directories for config file.");
				return;
			}
		}

		try (BufferedWriter writer = new BufferedWriter(new FileWriter(CONFIG_FILE_PATH))) {
			gson.toJson(defaultConfig, writer);
			LOGGER.info("Default configuration file generated: {}", CONFIG_FILE_PATH);
		} catch (IOException e) {
			LOGGER.error("Error generating default config file: {}", e.getMessage());
		}
	}

	private static final long SAVE_INTERVAL_SECONDS = 10; // Save every 10 seconds
	private long lastSaveTime = 0;

	private void onServerTick(MinecraftServer server) {
		long currentTime = System.currentTimeMillis() / 1000; // Convert current time to seconds

		// Check if it's time to save playtime data based on the interval
		if (currentTime - lastSaveTime >= SAVE_INTERVAL_SECONDS) {
			// Update playtime for all online players
			server.getPlayerManager().getPlayerList().forEach(player -> {
				UUID playerId = player.getUuid();
				long currentPlayerTime = playerPlaytimes.getOrDefault(playerId, 0L);
				playerPlaytimes.put(playerId, currentPlayerTime + SAVE_INTERVAL_SECONDS);

				// Check if the player's playtime meets the threshold for a rank and grant the rank if so
				grantRank(player, currentPlayerTime + SAVE_INTERVAL_SECONDS);
			});

			// Save playtime data to file
			savePlaytimeToFile();

			// Update last save time
			lastSaveTime = currentTime;
		}
	}

	private final Map<UUID, Set<String>> playersAlreadyUpgraded = new HashMap<>();
	private void grantRank(ServerPlayerEntity player, long playtime) {
		if (player != null) {
			UUID playerId = player.getUuid();
			MinecraftServer server = player.getServer();
			assert server != null;
			ServerCommandSource source = server.getCommandSource().withLevel(2).withOutput(new SystemOutCommandOutput(System.out));

			String currentRank = getCurrentRank(player.getName().getString());
			LOGGER.info("Player {}: Current rank: {}", player.getName().getString(), currentRank);
			LOGGER.info("Player {}: Current playtime: {}", player.getName().getString(), playtime);

			String maxRank = getMaxRank(); // Dynamically determine the maximum rank

			if (currentRank != null) {
				// Check if the player has reached the maximum rank
				if (!currentRank.equalsIgnoreCase(maxRank)) {
					// Get the index of the current rank in the rankConfigs list
					int currentRankIndex = -1;
					for (int i = 0; i < rankConfigs.size(); i++) {
						if (rankConfigs.get(i).name.equalsIgnoreCase(currentRank)) {
							currentRankIndex = i;
							break;
						}
					}
					// Iterate through rank configurations to find subsequent upgrades
					if (currentRankIndex != -1) {
						for (int i = currentRankIndex + 1; i < rankConfigs.size(); i++) {
							RankConfig rankConfig = rankConfigs.get(i);
							if (playtime >= rankConfig.playtimeThreshold && !hasPlayerAlreadyUpgraded(playerId, rankConfig.name)) {
								try {
									String command = "lp user " + player.getName().getString() + " parent set " + rankConfig.name;
									runConsoleCommand(server, command);
									LOGGER.info("Player {} has been granted the rank: {}", player.getName().getString(), rankConfig.name);
									playerPlaytimes.put(playerId, playtime); // Update player's playtime
									markPlayerAsUpgraded(playerId, rankConfig.name); // Mark rank as granted for this player
									break; // Exit the loop after granting the rank
								} catch (Exception e) {
									LOGGER.error("Error granting rank to player {}: {}", player.getName().getString(), e.getMessage());
								}
							}
						}
					}
				} else {
					LOGGER.info("Player {} has reached the maximum rank: {}", player.getName().getString(), maxRank);
				}
			} else {
				LOGGER.error("Failed to retrieve current rank for player {}.", player.getName().getString());
			}
		} else {
			LOGGER.error("Player object is null.");
		}
	}

	private boolean hasPlayerAlreadyUpgraded(UUID playerId, String rankName) {
		// Check if the player has already been granted the specified rank
		return playersAlreadyUpgraded.getOrDefault(playerId, new HashSet<>()).contains(rankName);
	}

	private void markPlayerAsUpgraded(UUID playerId, String rankName) {
		// Mark the specified rank as granted for the player
		playersAlreadyUpgraded.computeIfAbsent(playerId, k -> new HashSet<>()).add(rankName);
	}


	private String getMaxRank() {
		String maxRank = null;
		long maxThreshold = Long.MIN_VALUE;

		for (RankConfig rankConfig : rankConfigs) {
			if (rankConfig.playtimeThreshold > maxThreshold) {
				maxThreshold = rankConfig.playtimeThreshold;
				maxRank = rankConfig.name;
			}
		}

		return maxRank;
	}

	private String getCurrentRank(String playerName) {
		LuckPerms luckPerms = LuckPermsProvider.get();
		if (luckPerms == null) {
			LOGGER.error("LuckPerms is not initialized.");
			return null;
		}

		LOGGER.info("Retrieving current rank for player: {}", playerName);
		User user = luckPerms.getUserManager().getUser(playerName);
		if (user == null) {
			LOGGER.error("User '{}' not found.", playerName);
			return null;
		}

		LOGGER.info("User '{}' found.", playerName);

		// Check if player's LuckPerms groups correspond to any rank
		List<Group> inheritedGroups = (List<Group>) user.getInheritedGroups(QueryOptions.nonContextual());
		for (Group group : inheritedGroups) {
			String groupName = group.getName();
			LOGGER.info("Checking inherited group: {}", groupName);
			for (RankConfig rankConfig : rankConfigs) {
				if (rankConfig.name.equalsIgnoreCase(groupName)) {
					LOGGER.info("Match found. Rank: {}", groupName);
					return groupName;
				}
			}
			LOGGER.info("Does group '{}' match any rank name? false", groupName);
		}

		// Assign the "Pioneer" rank if the player's playtime exceeds its threshold
		long playerPlaytime = playerPlaytimes.getOrDefault(user.getUniqueId(), 0L);
		for (RankConfig rankConfig : rankConfigs) {
			if (playerPlaytime >= rankConfig.playtimeThreshold) {
				LOGGER.info("Player {} has exceeded playtime threshold for rank: {}", playerName, rankConfig.name);
				return rankConfig.name;
			}
		}

		LOGGER.error("No matching rank found for player: {}", playerName);
		return null;
	}


	private void runConsoleCommand(MinecraftServer server, String command) {
		if (server != null) {
			ServerCommandSource source = server.getCommandSource();
			try {
				server.getCommandManager().getDispatcher().execute(command, source);
				LOGGER.info("Executed command successfully: {}", command);
			} catch (CommandSyntaxException e) {
				LOGGER.error("Error executing command: {}", e.getMessage());
			}
		} else {
			LOGGER.error("Server is not available");
		}
	}

	private void savePlaytimeToFile() {
		File playtimeFile = new File(PLAYTIME_FILE_PATH);
		File parentDirectory = playtimeFile.getParentFile();
		if (!parentDirectory.exists()) {
			boolean success = parentDirectory.mkdirs();
			if (!success) {
				LOGGER.error("Failed to create directories for playtime file.");
				return;
			}
		}

		try (BufferedWriter writer = new BufferedWriter(new FileWriter(playtimeFile))) {
			for (Map.Entry<UUID, Long> entry : playerPlaytimes.entrySet()) {
				UUID playerId = entry.getKey();
				long playtime = entry.getValue();
				writer.write(playerId.toString() + "," + playtime);
				writer.newLine();
			}
		} catch (IOException e) {
			LOGGER.error("Error saving playtime data to file: {}", e.getMessage());
		}
	}

	private void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, dedicated, none) -> {
			dispatcher.register(
					LiteralArgumentBuilder.<ServerCommandSource>literal("timedrankup")
							.requires(source -> source.hasPermissionLevel(2))
							.then(
									LiteralArgumentBuilder.<ServerCommandSource>literal("addrank")
											.then(
													RequiredArgumentBuilder.<ServerCommandSource, String>argument("name", StringArgumentType.word())
															.then(
																	RequiredArgumentBuilder.<ServerCommandSource, Integer>argument("playtime", IntegerArgumentType.integer())
																			.executes(context -> addRank(context.getSource(), StringArgumentType.getString(context, "name"), IntegerArgumentType.getInteger(context, "playtime")))
															)
											)
							)
							.then(
									LiteralArgumentBuilder.<ServerCommandSource>literal("updaterank")
											.then(
													RequiredArgumentBuilder.<ServerCommandSource, String>argument("oldName", StringArgumentType.word())
															.then(
																	RequiredArgumentBuilder.<ServerCommandSource, String>argument("newName", StringArgumentType.word())
																			.then(
																					RequiredArgumentBuilder.<ServerCommandSource, Integer>argument("playtime", IntegerArgumentType.integer())
																							.executes(context -> updateRank(context.getSource(), StringArgumentType.getString(context, "oldName"), StringArgumentType.getString(context, "newName"), IntegerArgumentType.getInteger(context, "playtime")))
																			)
															)
											)
							)
			);

			dispatcher.register(
					LiteralArgumentBuilder.<ServerCommandSource>literal("playtime")
							.executes(context -> viewOwnPlaytime(context.getSource()))
							.then(
									RequiredArgumentBuilder.<ServerCommandSource, String>argument("player", StringArgumentType.word())
											.executes(context -> viewPlayerPlaytime(context.getSource(), StringArgumentType.getString(context, "player")))
							)
			);
		});
	}

	private int addRank(ServerCommandSource source, String name, int playtime) {
		try {
			RankConfig newRank = new RankConfig(name, playtime); // No need to convert, as playtime is in seconds
			rankConfigs.add(newRank);
			saveConfig();
			source.sendFeedback(() -> Text.of("Rank added: " + name + " with playtime threshold: " + playtime + " seconds"), true); // Update message to include seconds
			return 1;
		} catch (Exception e) {
			LOGGER.error("Error executing addRank command: {}", e.getMessage());
			source.sendFeedback(() -> Text.of("An unexpected error occurred while executing the command. Please check server logs for details."), false);
			return 0;
		}
	}

	private int updateRank(ServerCommandSource source, String oldName, String newName, int playtime) {
		try {
			for (RankConfig rankConfig : rankConfigs) {
				if (rankConfig.name.equals(oldName)) {
					rankConfig.name = newName; // Update the name
					rankConfig.playtimeThreshold = playtime; // Update the playtime threshold
					saveConfig();
					source.sendFeedback(() -> Text.of("Rank updated: " + oldName + " renamed to " + newName + " with new playtime threshold: " + playtime + " seconds"), true); // Update message to include seconds
					return 1;
				}
			}
			source.sendFeedback(() -> Text.of("Rank not found: " + oldName), false);
			return 0;
		} catch (Exception e) {
			LOGGER.error("Error executing updateRank command: {}", e.getMessage());
			source.sendFeedback(() -> Text.of("An unexpected error occurred while executing the command. Please check server logs for details."), false);
			return 0;
		}
	}

	private void saveConfig() {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(CONFIG_FILE_PATH))) {
			gson.toJson(new Config(rankConfigs), writer);
			LOGGER.info("Configuration file updated: {}", CONFIG_FILE_PATH);
		} catch (IOException e) {
			LOGGER.error("Error saving configuration file: {}", e.getMessage());
		}
	}

	private int viewOwnPlaytime(ServerCommandSource source) {
		UUID playerId = Objects.requireNonNull(source.getPlayer()).getUuid();
		long playtime = playerPlaytimes.getOrDefault(playerId, 0L);
		source.sendFeedback(formatPlaytimeMessage(source.getPlayer().getName().getString(), playtime), false);
		return 1;
	}

	private int viewPlayerPlaytime(ServerCommandSource source, String playerName) {
		MinecraftServer server = source.getServer();
		ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
		if (player != null) {
			UUID playerId = player.getUuid();
			long playtime = playerPlaytimes.getOrDefault(playerId, 0L);
			source.sendFeedback(formatPlaytimeMessage(playerName, playtime), false);
			return 1;
		} else {
			source.sendFeedback(formatErrorMessage(), true);
			return 0;
		}
	}

	private Supplier<Text> formatPlaytimeMessage(String playerName, long playtime) {
		long seconds = playtime;
		long minutes = seconds / 60;
		long hours = minutes / 60;

		minutes %= 60;
		seconds %= 60;
		hours %= 24;

		long days = playtime / (60 * 60 * 24);

		final long finalHours = hours;
		final long finalMinutes = minutes;
		final long finalSeconds = seconds;

		return () -> Text.of(playerName + " has played for " + days + " days, " + finalHours + " hours, " + finalMinutes + " minutes, and " + finalSeconds + " seconds.");
	}

	private Supplier<Text> formatErrorMessage() {
		return () -> Text.of("Player not found.");
	}

	private static class Config {
		List<RankConfig> ranks;

		Config(List<RankConfig> ranks) {
			this.ranks = ranks;
		}
	}

	private static class RankConfig {
		String name;
		int playtimeThreshold;

		RankConfig(String name, int playtimeThreshold) {
			this.name = name;
			this.playtimeThreshold = playtimeThreshold;
		}
	}
}
