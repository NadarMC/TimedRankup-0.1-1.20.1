package net.nadar.timedrankup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.Agent;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.ProfileLookupCallback;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import net.minecraft.command.CommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.UserCache;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.util.*;

public class TimedRankup implements ModInitializer {

	private static final Logger LOGGER = LogManager.getLogger();
	private final Map<UUID, Long> playerPlaytimes = new HashMap<>();
	private static final String PLAYTIME_FILE_PATH = "config/TimedRankup/playtime.txt";
	private static final String CONFIG_FILE_PATH = "config/TimedRankup/timedrankup_ranks.json";
	private static final String EXCLUSION_CONFIG_FILE_PATH = "config/TimedRankup/exclusions.json";
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private List<RankConfig> rankConfigs;
	private ExclusionConfig exclusions;
	private final UserCache userCache;

	// Tab completion for player names
	private static final SuggestionProvider<ServerCommandSource> PLAYER_SUGGESTIONS = (context, builder) -> {
		MinecraftServer server = context.getSource().getServer();
		return CommandSource.suggestMatching(server.getPlayerManager().getPlayerList().stream().map(ServerPlayerEntity::getName).map(Text::getString), builder);
	};

	public TimedRankup() {
		// Instantiate GameProfileRepository (you might need to provide necessary dependencies)
		GameProfileRepository profileRepository = new GameProfileRepository() {
			@Override
			public void findProfilesByNames(String[] names, Agent agent, ProfileLookupCallback callback) {

			}
		};

		// Specify the directory for user cache data
		File cacheDirectory = new File("timedrankup");

		// Instantiate UserCache with the created objects
		userCache = new UserCache(profileRepository, cacheDirectory);
	}


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
		File exclusionFile = new File(EXCLUSION_CONFIG_FILE_PATH);
		if (!configFile.exists()) {
			generateDefaultConfig();
		}
		if (!exclusionFile.exists()) {
			generateDefaultExclusionConfig();
		}

		try (Reader reader = new FileReader(CONFIG_FILE_PATH);
			 Reader exclusionReader = new FileReader(EXCLUSION_CONFIG_FILE_PATH)) {
			Config config = gson.fromJson(reader, Config.class);
			ExclusionConfig exclusionConfig = gson.fromJson(exclusionReader, ExclusionConfig.class);
			if (config != null && config.ranks != null) {
				rankConfigs = config.ranks;
			}
			if (exclusionConfig != null) {
				exclusions = exclusionConfig;
			}
		} catch (IOException e) {
			LOGGER.error("Error reading config files: {}", e.getMessage());
		}
	}

	private void generateDefaultConfig() {
		List<RankConfig> defaultRanks = List.of(
				new RankConfig("Newbie", 3600),
				new RankConfig("Regular", 10800),
				new RankConfig("Veteran", 21600)
		);

		Config defaultConfig = new Config(defaultRanks); // Exclusions will be added later

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

	private void generateDefaultExclusionConfig() {
		List<String> defaultExclusions = List.of("Admins", "Moderators"); // Example excluded groups
		ExclusionConfig defaultExclusionConfig = new ExclusionConfig(defaultExclusions);

		File exclusionFile = new File(EXCLUSION_CONFIG_FILE_PATH);
		File parentDirectory = exclusionFile.getParentFile();
		if (!parentDirectory.exists()) {
			boolean success = parentDirectory.mkdirs();
			if (!success) {
				LOGGER.error("Failed to create directories for exclusion config file.");
				return;
			}
		}

		try (BufferedWriter writer = new BufferedWriter(new FileWriter(EXCLUSION_CONFIG_FILE_PATH))) {
			gson.toJson(defaultExclusionConfig, writer);
			LOGGER.info("Default exclusion configuration file generated: {}", EXCLUSION_CONFIG_FILE_PATH);
		} catch (IOException e) {
			LOGGER.error("Error generating default exclusion config file: {}", e.getMessage());
		}
	}

	private static final long SAVE_INTERVAL_SECONDS = 15; // Update every 15 seconds
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
			// Check if the player belongs to any excluded group
			if (isPlayerExcluded(player)) {
				return;
			}
			UUID playerId = player.getUuid();
			MinecraftServer server = player.getServer();
			assert server != null;
			ServerCommandSource source = server.getCommandSource().withLevel(2).withOutput(new SystemOutCommandOutput(System.out));

			String currentRank = getCurrentRank(player.getName().getString());

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
									//LOGGER.error("Error granting rank to player {}: {}", player.getName().getString(), e.getMessage());
								}
							}
						}
					}
				} else {
					//LOGGER.info("Player {} has reached the maximum rank: {}", player.getName().getString(), maxRank);
				}
			} else {
				//LOGGER.error("Failed to retrieve current rank for player {}.", player.getName().getString());
			}
		} else {
			//LOGGER.error("Player object is null.");
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

		User user = luckPerms.getUserManager().getUser(playerName);
		if (user == null) {
			LOGGER.error("User '{}' not found.", playerName);
			return null;
		}

		List<Group> inheritedGroups = (List<Group>) user.getInheritedGroups(QueryOptions.nonContextual());
		for (Group group : inheritedGroups) {
			String groupName = group.getName();
			for (RankConfig rankConfig : rankConfigs) {
				if (rankConfig.name.equalsIgnoreCase(groupName)) {
					return groupName;
				}
			}
		}

		long playerPlaytime = playerPlaytimes.getOrDefault(user.getUniqueId(), 0L);
		for (RankConfig rankConfig : rankConfigs) {
			if (playerPlaytime >= rankConfig.playtimeThreshold) {
				return rankConfig.name;
			}
		}

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
							.then(
									LiteralArgumentBuilder.<ServerCommandSource>literal("forceupgrade")
											.executes(context -> forceUpgrade(context.getSource()))
							)
							.then(
									LiteralArgumentBuilder.<ServerCommandSource>literal("listranks")
											.executes(context -> listRanks(context.getSource()))
							)
							.then(
									LiteralArgumentBuilder.<ServerCommandSource>literal("removerank")
											.then(
													RequiredArgumentBuilder.<ServerCommandSource, String>argument("name", StringArgumentType.word())
															.executes(context -> removeRank(context.getSource(), StringArgumentType.getString(context, "name")))
											)
							)
			);

			dispatcher.register(
					LiteralArgumentBuilder.<ServerCommandSource>literal("playtime")
							.executes(context -> viewOwnPlaytime(context.getSource()))
							.then(
									RequiredArgumentBuilder.<ServerCommandSource, String>argument("player", StringArgumentType.word())
											.suggests(PLAYER_SUGGESTIONS) // Tab completion for player names
											.executes(context -> viewPlayerPlaytime(context.getSource(), StringArgumentType.getString(context, "player")))
							)
			);
		});
	}
	private void saveConfig() {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(CONFIG_FILE_PATH))) {
			gson.toJson(new Config(rankConfigs), writer);
			LOGGER.info("Configuration file updated: {}", CONFIG_FILE_PATH);
		} catch (IOException e) {
			LOGGER.error("Error saving configuration file: {}", e.getMessage());
		}
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

	private int removeRank(ServerCommandSource source, String name) {
		try {
			RankConfig removedRank = null;
			for (RankConfig rankConfig : rankConfigs) {
				if (rankConfig.name.equals(name)) {
					removedRank = rankConfig;
					break;
				}
			}
			if (removedRank != null) {
				rankConfigs.remove(removedRank);
				saveConfig();
				source.sendFeedback(() -> Text.of("Rank removed: " + name), true);
				return 1;
			} else {
				source.sendFeedback(() -> Text.of("Rank not found: " + name), false);
				return 0;
			}
		} catch (Exception e) {
			LOGGER.error("Error executing removeRank command: {}", e.getMessage());
			source.sendFeedback(() -> Text.of("An unexpected error occurred while executing the command. Please check server logs for details."), false);
			return 0;
		}
	}

	private int listRanks(ServerCommandSource source) {
		try {
			if (!rankConfigs.isEmpty()) {
				source.sendFeedback(() -> Text.of("Ranks:"), false);
				for (RankConfig rankConfig : rankConfigs) {
					source.sendFeedback(() -> Text.of("- " + rankConfig.name + " - Playtime Threshold: " + rankConfig.playtimeThreshold + " seconds"), false); // Update message to include seconds
				}
			} else {
				source.sendFeedback(() -> Text.of("No ranks configured."), false);
			}
			return 1;
		} catch (Exception e) {
			LOGGER.error("Error executing listRanks command: {}", e.getMessage());
			source.sendFeedback(() -> Text.of("An unexpected error occurred while executing the command. Please check server logs for details."), false);
			return 0;
		}
	}
	private void grantRank(ServerPlayerEntity player) {
	}

	private int forceUpgrade(ServerCommandSource source) {
		try {
			MinecraftServer server = source.getServer();
			if (server != null) {
				server.getPlayerManager().getPlayerList().forEach(this::grantRank);
				source.sendFeedback(() -> Text.of("Forced rank upgrade process executed for all online players."), true);
				return 1;
			} else {
				LOGGER.error("Server is not available");
				source.sendFeedback(() -> Text.of("An unexpected error occurred while executing the command. Please check server logs for details."), false);
				return 0;
			}
		} catch (Exception e) {
			LOGGER.error("Error executing forceUpgrade command: {}", e.getMessage());
			source.sendFeedback(() -> Text.of("An unexpected error occurred while executing the command. Please check server logs for details."), false);
			return 0;
		}
	}

	private int viewOwnPlaytime(ServerCommandSource source) {
		if (source.getEntity() instanceof ServerPlayerEntity) {
			ServerPlayerEntity player = (ServerPlayerEntity) source.getEntity();
			UUID playerId = player.getUuid();
			long playtime = playerPlaytimes.getOrDefault(playerId, 0L);
			source.sendFeedback(() -> Text.of("Your total playtime: " + formatPlaytime(playtime)), false);
			return 1;
		} else {
			source.sendFeedback(() -> Text.of("This command can only be executed by players."), false);
			return 0;
		}
	}

	private int viewPlayerPlaytime(ServerCommandSource source, String playerName) {
		Optional<GameProfile> playerId = userCache.findByName(playerName);
		if (playerId.isPresent()) {
			long playtime = playerPlaytimes.getOrDefault(playerId, 0L);
			source.sendFeedback(() -> Text.of(playerName + "'s total playtime: " + formatPlaytime(playtime)), false);
			return 1;
		} else {
			source.sendFeedback(() -> Text.of("Player not found: " + playerName), false);
			return 0;
		}
	}

	private String formatPlaytime(long playtimeInSeconds) {
		long hours = playtimeInSeconds / 3600;
		long minutes = (playtimeInSeconds % 3600) / 60;
		long seconds = playtimeInSeconds % 60;
		return String.format("%d hours, %d minutes, %d seconds", hours, minutes, seconds);
	}

	private boolean isPlayerExcluded(ServerPlayerEntity player) {
		if (exclusions == null || exclusions.excludedGroups == null || exclusions.excludedGroups.isEmpty()) {
			return false; // No exclusions configured
		}

		LuckPerms luckPerms = LuckPermsProvider.get();
		if (luckPerms == null) {
			LOGGER.error("LuckPerms is not initialized.");
			return false; // Unable to determine exclusion status
		}

		User user = luckPerms.getUserManager().getUser(player.getName().getString());
		if (user == null) {
			LOGGER.error("User '{}' not found.", player.getName().getString());
			return false; // Unable to determine exclusion status
		}

		List<Group> inheritedGroups = (List<Group>) user.getInheritedGroups(QueryOptions.nonContextual());
		for (Group group : inheritedGroups) {
			if (exclusions.excludedGroups.contains(group.getName())) {
				return true; // Player belongs to an excluded group
			}
		}

		return false; // Player does not belong to any excluded group
	}

	// Model classes for configuration
	private static class RankConfig {
		private String name;
		private int playtimeThreshold;

		public RankConfig(String name, int playtimeThreshold) {
			this.name = name;
			this.playtimeThreshold = playtimeThreshold;
		}
	}

	private static class Config {
		private List<RankConfig> ranks;

		public Config(List<RankConfig> ranks) {
			this.ranks = ranks;
		}
	}

	private static class ExclusionConfig {
		private List<String> excludedGroups;

		public ExclusionConfig(List<String> excludedGroups) {
			this.excludedGroups = excludedGroups;
		}
	}
}
