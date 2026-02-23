package com.xetpy.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.xetpy.HardcoreUnique;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TomorrowYouState {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE_PATH = FabricLoader.getInstance().getConfigDir().resolve("hardcore-unique.tomorrow-you-state.json");

	private final Map<UUID, PlayerTimelineData> players = new HashMap<>();

	public static TomorrowYouState load() {
		if (Files.exists(FILE_PATH)) {
			try (Reader reader = Files.newBufferedReader(FILE_PATH)) {
				SerializableState loaded = GSON.fromJson(reader, SerializableState.class);
				return fromSerializable(loaded);
			} catch (IOException | JsonParseException exception) {
				HardcoreUnique.LOGGER.error("Failed to read TomorrowYou state file, creating a new one", exception);
			}
		}
		return new TomorrowYouState();
	}

	public synchronized void save() {
		try {
			Files.createDirectories(FILE_PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE_PATH)) {
				GSON.toJson(toSerializable(), writer);
			}
		} catch (IOException exception) {
			HardcoreUnique.LOGGER.error("Failed to write TomorrowYou state file", exception);
		}
	}

	public synchronized PlayerTimelineData getOrCreatePlayerData(UUID playerId) {
		return players.computeIfAbsent(playerId, ignored -> new PlayerTimelineData());
	}

	public synchronized void clearActiveEvent(UUID playerId) {
		PlayerTimelineData data = getOrCreatePlayerData(playerId);
		data.activeEvent = null;
	}

	public synchronized void addHistory(UUID playerId, EncounterRecord record) {
		PlayerTimelineData data = getOrCreatePlayerData(playerId);
		data.history.add(record);
	}

	private SerializableState toSerializable() {
		SerializableState serializableState = new SerializableState();
		for (Map.Entry<UUID, PlayerTimelineData> entry : players.entrySet()) {
			SerializablePlayerData serializablePlayerData = new SerializablePlayerData();
			serializablePlayerData.playerId = entry.getKey().toString();
			serializablePlayerData.completedEncounters = entry.getValue().completedEncounters;
			serializablePlayerData.lastTriggerGameTime = entry.getValue().lastTriggerGameTime;
			for (EncounterRecord historyRecord : entry.getValue().history) {
				serializablePlayerData.history.add(historyRecord.copy());
			}
			if (entry.getValue().activeEvent != null) {
				serializablePlayerData.activeEvent = entry.getValue().activeEvent.copy();
			}
			serializableState.players.add(serializablePlayerData);
		}
		return serializableState;
	}

	private static TomorrowYouState fromSerializable(SerializableState serializableState) {
		TomorrowYouState state = new TomorrowYouState();
		if (serializableState == null || serializableState.players == null) {
			return state;
		}
		for (SerializablePlayerData serializablePlayerData : serializableState.players) {
			if (serializablePlayerData.playerId == null) {
				continue;
			}
			UUID playerId;
			try {
				playerId = UUID.fromString(serializablePlayerData.playerId);
			} catch (IllegalArgumentException ignored) {
				continue;
			}

			PlayerTimelineData playerData = new PlayerTimelineData();
			playerData.completedEncounters = Math.max(0, serializablePlayerData.completedEncounters);
			playerData.lastTriggerGameTime = serializablePlayerData.lastTriggerGameTime;
			if (serializablePlayerData.history != null) {
				for (EncounterRecord historyRecord : serializablePlayerData.history) {
					playerData.history.add(historyRecord.copy());
				}
			}
			if (serializablePlayerData.activeEvent != null) {
				playerData.activeEvent = serializablePlayerData.activeEvent.copy();
			}
			state.players.put(playerId, playerData);
		}
		return state;
	}

	public static final class PlayerTimelineData {
		public int completedEncounters;
		public long lastTriggerGameTime = Long.MIN_VALUE;
		public ActiveEvent activeEvent;
		public List<EncounterRecord> history = new ArrayList<>();
	}

	public static final class ActiveEvent {
		public String worldKey = "minecraft:overworld";
		public int targetX;
		public int targetY = 64;
		public int targetZ;
		public long createdAt;
		public long createdDay;
		public boolean firstVisitDone;
		public boolean tomorrowBranchResolved;
		public String resolvedOutcome = "pending";
		public UUID echoEntityUuid;
		public String playerNameAtCreation = "Unknown";
		public String mainHandItem = "minecraft:air";
		public String offHandItem = "minecraft:air";
		public int mainHandCount = 0;
		public int offHandCount = 0;
		public String headArmor = "minecraft:air";
		public String chestArmor = "minecraft:air";
		public String legsArmor = "minecraft:air";
		public String feetArmor = "minecraft:air";
		public List<String> frozenInventory = new ArrayList<>();

		private ActiveEvent copy() {
			ActiveEvent copy = new ActiveEvent();
			copy.worldKey = worldKey;
			copy.targetX = targetX;
			copy.targetY = targetY;
			copy.targetZ = targetZ;
			copy.createdAt = createdAt;
			copy.createdDay = createdDay;
			copy.firstVisitDone = firstVisitDone;
			copy.tomorrowBranchResolved = tomorrowBranchResolved;
			copy.resolvedOutcome = resolvedOutcome;
			copy.echoEntityUuid = echoEntityUuid;
			copy.playerNameAtCreation = playerNameAtCreation;
			copy.mainHandItem = mainHandItem;
			copy.offHandItem = offHandItem;
			copy.mainHandCount = mainHandCount;
			copy.offHandCount = offHandCount;
			copy.headArmor = headArmor;
			copy.chestArmor = chestArmor;
			copy.legsArmor = legsArmor;
			copy.feetArmor = feetArmor;
			copy.frozenInventory = new ArrayList<>(frozenInventory);
			return copy;
		}
	}

	public static final class EncounterRecord {
		public String worldKey = "minecraft:overworld";
		public int x;
		public int y;
		public int z;
		public long createdDay;
		public long resolvedDay;
		public String outcome = "unknown";
		public boolean gotCompass;

		private EncounterRecord copy() {
			EncounterRecord copy = new EncounterRecord();
			copy.worldKey = worldKey;
			copy.x = x;
			copy.y = y;
			copy.z = z;
			copy.createdDay = createdDay;
			copy.resolvedDay = resolvedDay;
			copy.outcome = outcome;
			copy.gotCompass = gotCompass;
			return copy;
		}
	}

	private static final class SerializableState {
		List<SerializablePlayerData> players = new ArrayList<>();
	}

	private static final class SerializablePlayerData {
		String playerId;
		int completedEncounters;
		long lastTriggerGameTime = Long.MIN_VALUE;
		ActiveEvent activeEvent;
		List<EncounterRecord> history = new ArrayList<>();
	}
}
