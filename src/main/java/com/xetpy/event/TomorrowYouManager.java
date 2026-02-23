package com.xetpy.event;

import com.xetpy.HardcoreUnique;
import com.xetpy.config.TomorrowYouConfig;
import com.xetpy.state.TomorrowYouState;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class TomorrowYouManager {
	private static final String NOTE_TITLE = "Странная записка";
	private static final String COMPASS_TITLE = "Компас эха";

	private static final String TAG_UNIQUE = "tomorrowYouUnique";
	private static final String TAG_OWNER_UUID = "ownerUuid";
	private static final String TAG_OWNER_NAME = "ownerName";
	private static final String TAG_ISSUED_AT_GAME_TIME = "issuedAtGameTime";
	private static final String TAG_ISSUED_AT_DAY = "issuedAtDay";
	private static final String TAG_ARTIFACT_ID = "artifactId";
	private static final String TAG_COMPASS_MODE = "compassMode";
	private static final String TAG_CALIBRATE_AT = "calibrateAtGameTime";
	private static final String TAG_LAST_CHAOS_UPDATE = "lastChaosUpdateGameTime";
	private static final String TAG_TARGET_WORLD = "targetWorld";
	private static final String TAG_TARGET_X = "targetX";
	private static final String TAG_TARGET_Y = "targetY";
	private static final String TAG_TARGET_Z = "targetZ";

	private final TomorrowYouConfig config;
	private final TomorrowYouState state;
	private final Map<UUID, Boolean> sleepingCache = new HashMap<>();
	private final Map<UUID, Integer> attackCooldowns = new HashMap<>();
	private final Map<UUID, Integer> presenceSoundCooldowns = new HashMap<>();
	private final Map<UUID, Integer> tomorrowProgressTicks = new HashMap<>();
	private final Map<UUID, Integer> tomorrowXpPulseCooldowns = new HashMap<>();

	public TomorrowYouManager(TomorrowYouConfig config) {
		this.config = config;
		this.state = TomorrowYouState.load();
	}

	public void register() {
		ServerTickEvents.START_SERVER_TICK.register(this::onServerTick);
	}

	private void onServerTick(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			handleWakeTrigger(player);
			handleActiveEvent(player);
			tickOwnedCompass(player);
		}
	}

	private void handleWakeTrigger(ServerPlayer player) {
		boolean isSleeping = player.isSleeping();
		boolean wasSleeping = sleepingCache.getOrDefault(player.getUUID(), false);
		sleepingCache.put(player.getUUID(), isSleeping);
		if (config.debugVerboseLogs) {
			HardcoreUnique.LOGGER.info(
				"[TomorrowYou] wake check player={}, isSleeping={}, wasSleeping={}, debugForceEvent={}",
				player.getName().getString(),
				isSleeping,
				wasSleeping,
				config.debugForceEvent
			);
		}

		// In normal mode event can only start right after waking up.
		// In debugForceEvent mode we allow triggering while awake for easier debugging.
		if (isSleeping || (!config.debugForceEvent && !wasSleeping)) {
			if (config.debugVerboseLogs) {
				HardcoreUnique.LOGGER.info("[TomorrowYou] skip: not a wake transition");
			}
			return;
		}

		ServerLevel world = player.level();
		TomorrowYouState.PlayerTimelineData data = state.getOrCreatePlayerData(player.getUUID());
		if (data.activeEvent != null || data.completedEncounters >= config.maxEncountersPerPlayer) {
			if (config.debugVerboseLogs) {
				HardcoreUnique.LOGGER.info(
					"[TomorrowYou] skip: activeEvent={}, completed={}, max={}",
					data.activeEvent != null,
					data.completedEncounters,
					config.maxEncountersPerPlayer
				);
			}
			return;
		}

		long gameTime = world.getGameTime();
		long cooldownTicks = config.cooldownMinutes * 60L * 20L;
		if (!config.debugForceEvent && data.lastTriggerGameTime != Long.MIN_VALUE && gameTime - data.lastTriggerGameTime < cooldownTicks) {
			if (config.debugVerboseLogs) {
				HardcoreUnique.LOGGER.info(
					"[TomorrowYou] skip: cooldown active, elapsed={}, required={}",
					gameTime - data.lastTriggerGameTime,
					cooldownTicks
				);
			}
			return;
		}

		double roll = world.random.nextDouble();
		boolean passChance = config.debugForceEvent || roll <= config.wakeEventChance;
		if (config.debugVerboseLogs) {
			HardcoreUnique.LOGGER.info(
				"[TomorrowYou] chance check: roll={}, chance={}, pass={}",
				roll,
				config.wakeEventChance,
				passChance
			);
		}
		if (!passChance) {
			return;
		}

		TomorrowYouState.ActiveEvent event = createEvent(player);
		data.activeEvent = event;
		data.lastTriggerGameTime = gameTime;
		state.save();

		giveOrDrop(player, createCoordinatesNote(event.targetX, event.targetY, event.targetZ));
		playForPlayer(player, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.9F, 0.7F);
		HardcoreUnique.LOGGER.info(
			"Triggered TomorrowYou event for {} at {} {} {}",
			player.getName().getString(),
			event.targetX,
			event.targetY,
			event.targetZ
		);
	}

	private TomorrowYouState.ActiveEvent createEvent(ServerPlayer player) {
		TomorrowYouState.ActiveEvent event = new TomorrowYouState.ActiveEvent();
		ServerLevel world = player.level();
		BlockPos target = generateTarget(world, player.blockPosition());
		event.worldKey = world.dimension().identifier().toString();
		event.targetX = target.getX();
		event.targetY = target.getY();
		event.targetZ = target.getZ();
		event.createdAt = world.getGameTime();
		event.createdDay = world.getGameTime() / 24000L;
		event.firstVisitDone = false;
		event.tomorrowBranchResolved = false;
		event.resolvedOutcome = "pending";

		event.playerNameAtCreation = player.getName().getString();
		event.mainHandItem = itemId(player.getMainHandItem().getItem());
		event.offHandItem = itemId(player.getOffhandItem().getItem());
		event.mainHandCount = player.getMainHandItem().getCount();
		event.offHandCount = player.getOffhandItem().getCount();
		event.headArmor = itemId(player.getItemBySlot(EquipmentSlot.HEAD).getItem());
		event.chestArmor = itemId(player.getItemBySlot(EquipmentSlot.CHEST).getItem());
		event.legsArmor = itemId(player.getItemBySlot(EquipmentSlot.LEGS).getItem());
		event.feetArmor = itemId(player.getItemBySlot(EquipmentSlot.FEET).getItem());

		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (!stack.isEmpty()) {
				event.frozenInventory.add(stack.getHoverName().getString() + " x" + stack.getCount());
			}
		}
		return event;
	}

	private void handleActiveEvent(ServerPlayer player) {
		ServerLevel playerWorld = player.level();
		TomorrowYouState.PlayerTimelineData data = state.getOrCreatePlayerData(player.getUUID());
		TomorrowYouState.ActiveEvent event = data.activeEvent;
		if (event == null) {
			return;
		}
		if (player.isDeadOrDying()) {
			finalizeEncounter(player, data, event, "player_died", false);
			return;
		}
		if (player.experienceLevel <= 0) {
			finalizeEncounter(player, data, event, "no_levels_escape", false);
			return;
		}

		Identifier worldId = Identifier.tryParse(event.worldKey);
		MinecraftServer server = player.level().getServer();
		if (worldId == null || server == null) {
			return;
		}

		ResourceKey<Level> eventWorldKey = ResourceKey.create(Registries.DIMENSION, worldId);
		ServerLevel eventWorld = server.getLevel(eventWorldKey);
		if (eventWorld == null || !playerWorld.dimension().equals(eventWorld.dimension())) {
			return;
		}

		BlockPos targetPos = new BlockPos(event.targetX, event.targetY, event.targetZ);
		if (!event.firstVisitDone) {
			handleFirstVisit(player, data, event, eventWorld, targetPos);
			return;
		}

		handleTomorrowVisit(player, data, event, targetPos);
	}

	private void handleFirstVisit(ServerPlayer player, TomorrowYouState.PlayerTimelineData data, TomorrowYouState.ActiveEvent event, ServerLevel eventWorld, BlockPos targetPos) {
		double distToTarget = player.position().distanceTo(Vec3.atCenterOf(targetPos));
		if (distToTarget <= config.spawnRadius) {
			ensureEchoSpawned(eventWorld, targetPos, event);
		}

		ArmorStand echo = findEcho(eventWorld, event.echoEntityUuid);
		if (echo == null) {
			return;
		}

		double distanceToEcho = player.position().distanceTo(echo.position());
		if (distanceToEcho <= config.vanishRadius) {
			eventWorld.sendParticles(ParticleTypes.SMOKE, echo.getX(), echo.getY(0.6D), echo.getZ(), 40, 0.4D, 0.8D, 0.4D, 0.02D);
			playForPlayer(player, SoundEvents.ENDERMAN_TELEPORT, 1.0F, 0.95F);
			giveOrDrop(player, createTomorrowWarningNote());

			echo.discard();
			event.echoEntityUuid = null;
			event.firstVisitDone = true;
			event.resolvedOutcome = "first_visit_done";
			attackCooldowns.remove(player.getUUID());
			presenceSoundCooldowns.remove(player.getUUID());
			state.save();
			return;
		}

		int pulseCooldown = presenceSoundCooldowns.getOrDefault(player.getUUID(), 0);
		if (distanceToEcho <= config.attackRadius + 5) {
			if (pulseCooldown <= 0) {
				float pitch = 0.7F + (eventWorld.random.nextFloat() * 0.25F);
				playForPlayer(player, SoundEvents.WARDEN_HEARTBEAT, 1.0F, pitch);
				presenceSoundCooldowns.put(player.getUUID(), 35);
			} else {
				presenceSoundCooldowns.put(player.getUUID(), pulseCooldown - 1);
			}
		} else {
			presenceSoundCooldowns.remove(player.getUUID());
		}

		if (distanceToEcho <= config.attackRadius) {
			int cooldown = attackCooldowns.getOrDefault(player.getUUID(), 0);
			if (cooldown <= 0) {
				int levelLoss = Math.min(config.xpLevelsPerHit, player.experienceLevel);
				if (levelLoss > 0) {
					player.giveExperienceLevels(-levelLoss);
					playForPlayer(player, SoundEvents.PLAYER_HURT_DROWN, 1.0F, 1.2F);
					playForPlayer(player, SoundEvents.WARDEN_HEARTBEAT, 1.0F, 0.65F);
				}
				attackCooldowns.put(player.getUUID(), config.attackCooldownTicks);
			} else {
				attackCooldowns.put(player.getUUID(), cooldown - 1);
			}
		}
	}

	private void handleTomorrowVisit(ServerPlayer player, TomorrowYouState.PlayerTimelineData data, TomorrowYouState.ActiveEvent event, BlockPos targetPos) {
		long currentDay = player.level().getGameTime() / 24000L;
		if (currentDay < event.createdDay + 1) {
			return;
		}

		double distToTarget = player.position().distanceTo(Vec3.atCenterOf(targetPos));
		if (distToTarget > config.tomorrowTriggerRadius) {
			tomorrowProgressTicks.remove(player.getUUID());
			tomorrowXpPulseCooldowns.remove(player.getUUID());
			return;
		}

		int progress = tomorrowProgressTicks.getOrDefault(player.getUUID(), 0) + 1;
		tomorrowProgressTicks.put(player.getUUID(), progress);

		player.level().sendParticles(ParticleTypes.PORTAL, player.getX(), player.getY() + 0.8D, player.getZ(), 8, 0.35D, 0.6D, 0.35D, 0.03D);
		if (progress % 20 == 0) {
			playForPlayer(player, SoundEvents.WARDEN_HEARTBEAT, 1.0F, 0.75F);
		}

		int pulseCooldown = tomorrowXpPulseCooldowns.getOrDefault(player.getUUID(), 0);
		if (pulseCooldown <= 0) {
			int levelLoss = Math.min(config.tomorrowXpDrainPerPulse, player.experienceLevel);
			if (levelLoss > 0) {
				player.giveExperienceLevels(-levelLoss);
				playForPlayer(player, SoundEvents.PLAYER_HURT_DROWN, 0.8F, 1.0F);
			}
			player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 0, false, true, true));
			player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 80, 0, false, true, true));
			if (player.level().random.nextFloat() < 0.45F) {
				player.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 80, 0, false, true, true));
			}
			tomorrowXpPulseCooldowns.put(player.getUUID(), config.tomorrowXpPulseTicks);
		} else {
			tomorrowXpPulseCooldowns.put(player.getUUID(), pulseCooldown - 1);
		}

		if (progress < config.tomorrowBranchDurationTicks) {
			return;
		}

		boolean chancePassed = player.level().random.nextDouble() <= config.compassRewardChance;
		boolean enoughLevels = player.experienceLevel >= config.compassRequiredLevels;
		boolean gotCompass = chancePassed && enoughLevels;
		if (gotCompass) {
			if (config.compassRequiredLevels > 0) {
				player.giveExperienceLevels(-config.compassRequiredLevels);
			}
			grantCompassArtifact(player, event, currentDay);
		} else {
			playForPlayer(player, SoundEvents.PLAYER_HURT_DROWN, 0.7F, 0.8F);
		}

		giveOrDrop(player, createAlreadyHereNote(gotCompass, enoughLevels));
		playForPlayer(player, SoundEvents.ENDERMAN_TELEPORT, 1.0F, 0.9F);
		finalizeEncounter(player, data, event, gotCompass ? "tomorrow_branch_compass" : "tomorrow_branch_no_compass", gotCompass);
	}

	private void ensureEchoSpawned(ServerLevel world, BlockPos targetPos, TomorrowYouState.ActiveEvent event) {
		if (findEcho(world, event.echoEntityUuid) != null || !world.hasChunkAt(targetPos)) {
			return;
		}

		ArmorStand echo = EntityType.ARMOR_STAND.create(world, EntitySpawnReason.EVENT);
		if (echo == null) {
			return;
		}

		echo.setPos(targetPos.getX() + 0.5D, targetPos.getY(), targetPos.getZ() + 0.5D);
		echo.setCustomName(Component.literal(event.playerNameAtCreation + " (Эхо)").withStyle(ChatFormatting.DARK_AQUA));
		echo.setCustomNameVisible(true);
		echo.setNoGravity(true);
		echo.setInvulnerable(true);
		equipEchoFromSnapshot(echo, event);
		world.addFreshEntity(echo);
		world.playSound(null, echo.getX(), echo.getY(), echo.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 0.7F);

		event.echoEntityUuid = echo.getUUID();
		state.save();
	}

	private void grantCompassArtifact(ServerPlayer player, TomorrowYouState.ActiveEvent event, long issuedAtDay) {
		ItemStack existing = findOwnedCompass(player);
		if (!existing.isEmpty()) {
			if (config.updateExistingCompass) {
				applyCompassMeta(existing, player, event, issuedAtDay);
				playForPlayer(player, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 0.8F);
			}
			return;
		}

		ItemStack compass = new ItemStack(Items.COMPASS);
		applyCompassMeta(compass, player, event, issuedAtDay);
		giveOrDrop(player, compass);
		playForPlayer(player, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 0.8F);
	}

	private void applyCompassMeta(ItemStack compass, ServerPlayer player, TomorrowYouState.ActiveEvent event, long issuedAtDay) {
		CustomData customData = compass.get(DataComponents.CUSTOM_DATA);
		CompoundTag tag = customData == null ? new CompoundTag() : customData.copyTag();

		String previousArtifactId = tag.getStringOr(TAG_ARTIFACT_ID, "");
		tag.putBoolean(TAG_UNIQUE, true);
		tag.putString(TAG_OWNER_UUID, player.getUUID().toString());
		tag.putString(TAG_OWNER_NAME, player.getName().getString());
		tag.putLong(TAG_ISSUED_AT_GAME_TIME, player.level().getGameTime());
		tag.putLong(TAG_ISSUED_AT_DAY, issuedAtDay);
		tag.putString(TAG_ARTIFACT_ID, previousArtifactId.isEmpty() ? UUID.randomUUID().toString() : previousArtifactId);
		tag.putString(TAG_COMPASS_MODE, "chaotic");
		long calibrateAt = config.compassUnstableTicks < 0
			? Long.MAX_VALUE
			: player.level().getGameTime() + config.compassUnstableTicks;
		tag.putLong(TAG_CALIBRATE_AT, calibrateAt);
		tag.putLong(TAG_LAST_CHAOS_UPDATE, Long.MIN_VALUE);
		tag.putString(TAG_TARGET_WORLD, event.worldKey);
		tag.putInt(TAG_TARGET_X, event.targetX);
		tag.putInt(TAG_TARGET_Y, event.targetY);
		tag.putInt(TAG_TARGET_Z, event.targetZ);
		compass.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

		compass.set(DataComponents.CUSTOM_NAME, Component.literal(COMPASS_TITLE).withStyle(ChatFormatting.AQUA));
		updateCompassLore(compass, player.getName().getString(), issuedAtDay, new BlockPos(event.targetX, event.targetY, event.targetZ), false);
	}

	private ItemStack findOwnedCompass(ServerPlayer player) {
		String ownerUuid = player.getUUID().toString();
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (isOwnedCompass(stack, ownerUuid)) {
				return stack;
			}
		}
		if (isOwnedCompass(player.getItemBySlot(EquipmentSlot.OFFHAND), ownerUuid)) {
			return player.getItemBySlot(EquipmentSlot.OFFHAND);
		}
		return ItemStack.EMPTY;
	}

	private boolean isOwnedCompass(ItemStack stack, String ownerUuid) {
		if (stack.isEmpty() || !isCompassLike(stack.getItem())) {
			return false;
		}

		CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
		if (customData == null) {
			return false;
		}
		CompoundTag tag = customData.copyTag();
		return tag.getBooleanOr(TAG_UNIQUE, false) && ownerUuid.equals(tag.getStringOr(TAG_OWNER_UUID, ""));
	}

	private void tickOwnedCompass(ServerPlayer player) {
		ItemStack compass = findOwnedCompass(player);
		if (compass.isEmpty()) {
			return;
		}

		CustomData customData = compass.get(DataComponents.CUSTOM_DATA);
		if (customData == null) {
			return;
		}
		CompoundTag tag = customData.copyTag();

		String mode = tag.getStringOr(TAG_COMPASS_MODE, "chaotic");
		if ("calibrated".equals(mode)) {
			return;
		}

		long gameTime = player.level().getGameTime();
		long calibrateAt = tag.getLongOr(TAG_CALIBRATE_AT, Long.MAX_VALUE);

		if (gameTime >= calibrateAt) {
			Identifier targetWorldId = Identifier.tryParse(tag.getStringOr(TAG_TARGET_WORLD, player.level().dimension().identifier().toString()));
			if (targetWorldId == null) {
				return;
			}

			ResourceKey<Level> targetWorld = ResourceKey.create(Registries.DIMENSION, targetWorldId);
			BlockPos targetPos = new BlockPos(
				tag.getIntOr(TAG_TARGET_X, player.blockPosition().getX()),
				tag.getIntOr(TAG_TARGET_Y, player.blockPosition().getY()),
				tag.getIntOr(TAG_TARGET_Z, player.blockPosition().getZ())
			);
			setCompassTarget(compass, targetWorld, targetPos, false);
			tag.putString(TAG_COMPASS_MODE, "calibrated");
			compass.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

			updateCompassLore(
				compass,
				tag.getStringOr(TAG_OWNER_NAME, player.getName().getString()),
				tag.getLongOr(TAG_ISSUED_AT_DAY, gameTime / 24000L),
				targetPos,
				true
			);
			playForPlayer(player, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.85F, 1.15F);
			return;
		}

		long lastChaosUpdate = tag.getLongOr(TAG_LAST_CHAOS_UPDATE, Long.MIN_VALUE);
		if (lastChaosUpdate != Long.MIN_VALUE && gameTime - lastChaosUpdate < config.compassChaosUpdateTicks) {
			return;
		}

		int dx = player.level().random.nextInt(129) - 64;
		int dz = player.level().random.nextInt(129) - 64;
		int y = Math.max(player.level().getMinY(), Math.min(player.level().getMaxY(), player.blockPosition().getY()));
		BlockPos randomTarget = new BlockPos(player.blockPosition().getX() + dx, y, player.blockPosition().getZ() + dz);
		setCompassTarget(compass, player.level().dimension(), randomTarget, false);
		tag.putLong(TAG_LAST_CHAOS_UPDATE, gameTime);
		compass.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
	}

	private void setCompassTarget(ItemStack compass, ResourceKey<Level> dimension, BlockPos pos, boolean tracked) {
		compass.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.of(GlobalPos.of(dimension, pos)), tracked));
	}

	private void updateCompassLore(ItemStack compass, String ownerName, long issuedAtDay, BlockPos target, boolean calibrated) {
		List<Component> lore = new ArrayList<>();
		lore.add(Component.literal("Владелец: " + ownerName).withStyle(ChatFormatting.GRAY));
		lore.add(Component.literal("День выдачи: " + issuedAtDay).withStyle(ChatFormatting.DARK_GRAY));
		lore.add(Component.literal("Точка эха: " + target.getX() + " " + target.getY() + " " + target.getZ()).withStyle(ChatFormatting.DARK_GRAY));
		lore.add(
			Component.literal(
				calibrated
					? "Состояние: откалиброван, ведет к цели."
					: "Состояние: нестабилен."
			).withStyle(ChatFormatting.DARK_PURPLE)
		);
		compass.set(DataComponents.LORE, new ItemLore(lore));
	}

	private void finalizeEncounter(ServerPlayer player, TomorrowYouState.PlayerTimelineData data, TomorrowYouState.ActiveEvent event, String outcome, boolean gotCompass) {
		event.tomorrowBranchResolved = true;
		event.resolvedOutcome = outcome;

		TomorrowYouState.EncounterRecord record = new TomorrowYouState.EncounterRecord();
		record.worldKey = event.worldKey;
		record.x = event.targetX;
		record.y = event.targetY;
		record.z = event.targetZ;
		record.createdDay = event.createdDay;
		record.resolvedDay = player.level().getGameTime() / 24000L;
		record.outcome = outcome;
		record.gotCompass = gotCompass;
		state.addHistory(player.getUUID(), record);

		data.completedEncounters += 1;
		data.activeEvent = null;

		attackCooldowns.remove(player.getUUID());
		presenceSoundCooldowns.remove(player.getUUID());
		tomorrowProgressTicks.remove(player.getUUID());
		tomorrowXpPulseCooldowns.remove(player.getUUID());
		discardEchoIfPresent(player.level(), event);
		state.save();
	}

	private void discardEchoIfPresent(ServerLevel world, TomorrowYouState.ActiveEvent event) {
		ArmorStand echo = findEcho(world, event.echoEntityUuid);
		if (echo != null) {
			echo.discard();
		}
		event.echoEntityUuid = null;
	}

	private ArmorStand findEcho(ServerLevel world, UUID uuid) {
		if (uuid == null) {
			return null;
		}
		Entity entity = world.getEntity(uuid);
		if (entity instanceof ArmorStand armorStand && armorStand.isAlive()) {
			return armorStand;
		}
		return null;
	}

	private void playForPlayer(ServerPlayer player, net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
		ServerLevel world = player.level();
		world.playSound(null, player.getX(), player.getY(), player.getZ(), sound, SoundSource.PLAYERS, volume, pitch);
	}

	private void equipEchoFromSnapshot(ArmorStand echo, TomorrowYouState.ActiveEvent event) {
		echo.setItemSlot(EquipmentSlot.MAINHAND, stackFromId(event.mainHandItem, event.mainHandCount));
		echo.setItemSlot(EquipmentSlot.OFFHAND, stackFromId(event.offHandItem, event.offHandCount));
		echo.setItemSlot(EquipmentSlot.HEAD, stackFromId(event.headArmor, 1));
		echo.setItemSlot(EquipmentSlot.CHEST, stackFromId(event.chestArmor, 1));
		echo.setItemSlot(EquipmentSlot.LEGS, stackFromId(event.legsArmor, 1));
		echo.setItemSlot(EquipmentSlot.FEET, stackFromId(event.feetArmor, 1));
	}

	private BlockPos generateTarget(ServerLevel world, BlockPos origin) {
		double angle = world.random.nextDouble() * Math.PI * 2.0D;
		int distance = config.targetMinDistance + world.random.nextInt(config.targetMaxDistance - config.targetMinDistance + 1);
		int rawX = origin.getX() + (int) Math.round(Math.cos(angle) * distance);
		int rawZ = origin.getZ() + (int) Math.round(Math.sin(angle) * distance);
		int y = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, rawX, rawZ);
		return new BlockPos(rawX, y, rawZ);
	}

	private ItemStack createCoordinatesNote(int x, int y, int z) {
		ItemStack stack = new ItemStack(Items.PAPER);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(NOTE_TITLE).withStyle(ChatFormatting.GOLD));
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal("Координаты: " + x + " " + y + " " + z).withStyle(ChatFormatting.GRAY));
		lines.add(Component.literal("Подпись: твоей рукой.").withStyle(ChatFormatting.DARK_GRAY));
		stack.set(DataComponents.LORE, new ItemLore(lines));
		return stack;
	}

	private ItemStack createTomorrowWarningNote() {
		ItemStack stack = new ItemStack(Items.PAPER);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(NOTE_TITLE).withStyle(ChatFormatting.DARK_PURPLE));
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal("Не ходи сюда завтра.").withStyle(ChatFormatting.LIGHT_PURPLE));
		lines.add(Component.literal("Но если придешь — время ответит.").withStyle(ChatFormatting.DARK_GRAY));
		stack.set(DataComponents.LORE, new ItemLore(lines));
		return stack;
	}

	private ItemStack createAlreadyHereNote(boolean gotCompass, boolean enoughLevels) {
		ItemStack stack = new ItemStack(Items.PAPER);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(NOTE_TITLE).withStyle(ChatFormatting.DARK_PURPLE));
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal("Ты уже был здесь.").withStyle(ChatFormatting.LIGHT_PURPLE));
		if (gotCompass) {
			lines.add(Component.literal("Эхо оставило тебе ориентир.").withStyle(ChatFormatting.GRAY));
			lines.add(Component.literal("Цена: " + config.compassRequiredLevels + " уровней.").withStyle(ChatFormatting.DARK_GRAY));
			lines.add(Component.literal("Стрелка ведет себя странно и непредсказуемо.").withStyle(ChatFormatting.DARK_GRAY));
		} else if (!enoughLevels) {
			lines.add(Component.literal("Недостаточно уровней: нужно " + config.compassRequiredLevels + ".").withStyle(ChatFormatting.GRAY));
		} else {
			lines.add(Component.literal("В этот раз эхо взяло больше, чем отдало.").withStyle(ChatFormatting.GRAY));
		}
		stack.set(DataComponents.LORE, new ItemLore(lines));
		return stack;
	}

	private boolean isCompassLike(Item item) {
		return item == Items.COMPASS || item == Items.RECOVERY_COMPASS;
	}

	private void giveOrDrop(ServerPlayer player, ItemStack stack) {
		if (!player.getInventory().add(stack)) {
			player.drop(stack, false);
		}
	}

	private String itemId(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	private ItemStack stackFromId(String rawId, int amount) {
		Identifier id = Identifier.tryParse(rawId);
		if (id == null) {
			return ItemStack.EMPTY;
		}
		Optional<Holder.Reference<Item>> item = BuiltInRegistries.ITEM.get(id);
		if (item.isEmpty()) {
			return ItemStack.EMPTY;
		}
		return new ItemStack(item.get().value(), Math.max(1, amount));
	}
}
