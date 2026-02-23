package com.xetpy.event;

import com.xetpy.HardcoreUnique;
import com.xetpy.config.TomorrowYouConfig;
import com.xetpy.state.TomorrowYouState;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
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
	private static final List<String> ENDING_LINES = List.of("Не ходи сюда завтра.", "Ты уже был здесь.");

	private final TomorrowYouConfig config;
	private final TomorrowYouState state;
	private final Map<UUID, Boolean> sleepingCache = new HashMap<>();
	private final Map<UUID, Integer> attackCooldowns = new HashMap<>();
	private final Map<UUID, Integer> presenceSoundCooldowns = new HashMap<>();

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
		}
	}

	private void handleWakeTrigger(ServerPlayer player) {
		boolean isSleeping = player.isSleeping();
		boolean wasSleeping = sleepingCache.getOrDefault(player.getUUID(), false);
		sleepingCache.put(player.getUUID(), isSleeping);
		if (isSleeping || !wasSleeping) {
			return;
		}

		ServerLevel world = player.level();
		TomorrowYouState.PlayerTimelineData data = state.getOrCreatePlayerData(player.getUUID());
		if (data.activeEvent != null || data.completedEncounters >= config.maxEncountersPerPlayer) {
			return;
		}

		long gameTime = world.getGameTime();
		long cooldownTicks = config.cooldownMinutes * 60L * 20L;
		if (!config.debugForceEvent && data.lastTriggerGameTime != Long.MIN_VALUE && gameTime - data.lastTriggerGameTime < cooldownTicks) {
			return;
		}

		boolean passChance = config.debugForceEvent || world.random.nextDouble() <= config.wakeEventChance;
		if (!passChance) {
			return;
		}

		TomorrowYouState.ActiveEvent event = createEvent(player);
		data.activeEvent = event;
		data.lastTriggerGameTime = gameTime;
		state.save();
		giveOrDrop(player, createCoordinatesNote(event.targetX, event.targetY, event.targetZ));
		player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.6F, 0.55F);
		HardcoreUnique.LOGGER.info("Triggered TomorrowYou event for {}", player.getName().getString());
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
			data.activeEvent = null;
			attackCooldowns.remove(player.getUUID());
			presenceSoundCooldowns.remove(player.getUUID());
			state.save();
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
			player.playSound(SoundEvents.ENDERMAN_TELEPORT, 0.8F, 0.9F);
			player.playSound(SoundEvents.ITEM_PICKUP, 0.5F, 0.7F);
			giveOrDrop(player, createEndingNote(event.frozenInventory));
			echo.discard();
			data.activeEvent = null;
			data.completedEncounters += 1;
			attackCooldowns.remove(player.getUUID());
			presenceSoundCooldowns.remove(player.getUUID());
			state.save();
			return;
		}

		int pulseCooldown = presenceSoundCooldowns.getOrDefault(player.getUUID(), 0);
		if (distanceToEcho <= config.attackRadius + 5) {
			if (pulseCooldown <= 0) {
				float pitch = 0.7F + (playerWorld.random.nextFloat() * 0.25F);
				player.playSound(SoundEvents.WARDEN_HEARTBEAT, 0.55F, pitch);
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
					player.playSound(SoundEvents.PLAYER_HURT_DROWN, 0.6F, 1.4F);
					player.playSound(SoundEvents.WARDEN_HEARTBEAT, 0.7F, 0.6F);
				}
				attackCooldowns.put(player.getUUID(), config.attackCooldownTicks);
			} else {
				attackCooldowns.put(player.getUUID(), cooldown - 1);
			}
		}
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
		echo.playSound(SoundEvents.ENDERMAN_TELEPORT, 0.7F, 0.6F);
		event.echoEntityUuid = echo.getUUID();
		state.save();
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
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Странная записка"));
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal("Координаты: " + x + " " + y + " " + z).withStyle(ChatFormatting.GRAY));
		lines.add(Component.literal("Подпись: твоей рукой.").withStyle(ChatFormatting.DARK_GRAY));
		stack.set(DataComponents.LORE, new ItemLore(lines));
		return stack;
	}

	private ItemStack createEndingNote(List<String> frozenInventory) {
		ItemStack stack = new ItemStack(Items.PAPER);
		String phrase = ENDING_LINES.get((int) (Math.random() * ENDING_LINES.size()));
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(phrase).withStyle(ChatFormatting.LIGHT_PURPLE));
		List<Component> lines = new ArrayList<>();
		if (!frozenInventory.isEmpty()) {
			lines.add(Component.literal("Эхо помнит:").withStyle(ChatFormatting.GRAY));
			for (int i = 0; i < Math.min(4, frozenInventory.size()); i++) {
				lines.add(Component.literal(frozenInventory.get(i)).withStyle(ChatFormatting.DARK_GRAY));
			}
		}
		stack.set(DataComponents.LORE, new ItemLore(lines));
		return stack;
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
