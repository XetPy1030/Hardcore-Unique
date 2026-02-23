package com.xetpy;

import net.fabricmc.api.ModInitializer;

import com.xetpy.config.TomorrowYouConfig;
import com.xetpy.event.TomorrowYouManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HardcoreUnique implements ModInitializer {
	public static final String MOD_ID = "hardcore-unique";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		TomorrowYouConfig config = TomorrowYouConfig.load();
		new TomorrowYouManager(config).register();
		LOGGER.info("Hardcore Unique initialized: TomorrowYou event is active");
	}
}