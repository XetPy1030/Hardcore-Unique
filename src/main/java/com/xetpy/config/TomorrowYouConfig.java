package com.xetpy.config;

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

public final class TomorrowYouConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE_PATH = FabricLoader.getInstance().getConfigDir().resolve("hardcore-unique.tomorrow-you.json");

	public double wakeEventChance = 0.25D;
	public int targetMinDistance = 80;
	public int targetMaxDistance = 400;
	public int spawnRadius = 24;
	public int vanishRadius = 3;
	public int attackRadius = 12;
	public int attackCooldownTicks = 30;
	public int xpLevelsPerHit = 1;
	public int tomorrowTriggerRadius = 5;
	public int tomorrowBranchDurationTicks = 120;
	public int tomorrowXpPulseTicks = 20;
	public int tomorrowXpDrainPerPulse = 1;
	public int compassRequiredLevels = 10;
	public int compassUnstableTicks = -1;
	public int compassChaosUpdateTicks = 20;
	public double compassRewardChance = 1.0D;
	public boolean updateExistingCompass = true;
	public boolean debugVerboseLogs = false;
	public int maxEncountersPerPlayer = 1;
	public int cooldownMinutes = 120;
	public boolean debugForceEvent = false;

	public static TomorrowYouConfig load() {
		if (Files.exists(FILE_PATH)) {
			try (Reader reader = Files.newBufferedReader(FILE_PATH)) {
				TomorrowYouConfig config = GSON.fromJson(reader, TomorrowYouConfig.class);
				if (config == null) {
					config = new TomorrowYouConfig();
				}
				config.sanitize();
				return config;
			} catch (IOException | JsonParseException exception) {
				HardcoreUnique.LOGGER.error("Failed to read config, using defaults", exception);
			}
		}

		TomorrowYouConfig defaults = new TomorrowYouConfig();
		defaults.sanitize();
		defaults.save();
		return defaults;
	}

	public void save() {
		try {
			Files.createDirectories(FILE_PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE_PATH)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException exception) {
			HardcoreUnique.LOGGER.error("Failed to write config", exception);
		}
	}

	private void sanitize() {
		wakeEventChance = clamp(wakeEventChance, 0.0D, 1.0D);
		targetMinDistance = Math.max(16, targetMinDistance);
		targetMaxDistance = Math.max(targetMinDistance + 1, targetMaxDistance);
		spawnRadius = Math.max(8, spawnRadius);
		vanishRadius = Math.max(1, vanishRadius);
		attackRadius = Math.max(vanishRadius + 1, attackRadius);
		attackCooldownTicks = Math.max(10, attackCooldownTicks);
		xpLevelsPerHit = Math.max(1, xpLevelsPerHit);
		tomorrowTriggerRadius = Math.max(2, tomorrowTriggerRadius);
		tomorrowBranchDurationTicks = Math.max(40, tomorrowBranchDurationTicks);
		tomorrowXpPulseTicks = Math.max(5, tomorrowXpPulseTicks);
		tomorrowXpDrainPerPulse = Math.max(1, tomorrowXpDrainPerPulse);
		compassRequiredLevels = Math.max(0, compassRequiredLevels);
		if (compassUnstableTicks != -1) {
			compassUnstableTicks = Math.max(40, compassUnstableTicks);
		}
		compassChaosUpdateTicks = Math.max(1, compassChaosUpdateTicks);
		compassRewardChance = clamp(compassRewardChance, 0.0D, 1.0D);
		maxEncountersPerPlayer = Math.max(1, maxEncountersPerPlayer);
		cooldownMinutes = Math.max(1, cooldownMinutes);
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
