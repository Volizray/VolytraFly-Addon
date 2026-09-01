/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package com.volytrafly.modules.movement.volytrafly;

import com.mojang.blaze3d.platform.InputConstants;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
// IVec3d -> IVec3 for 26.1.2, same as the other Yarn->Mojang renames below
import meteordevelopment.meteorclient.mixininterface.IVec3;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.ChestSwap;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;

public class VolytraFly extends Module {
    private final SettingGroup sgAutopilot = settings.createGroup("Autopilot");
    private final SettingGroup sgMapping = settings.createGroup("Mapping");
    private final SettingGroup sgBuildingMode = settings.createGroup("Building Mode");
    private final SettingGroup sgPlayerAvoidance = settings.createGroup("Player Avoidance System");
    private final SettingGroup sgLanding = settings.createGroup("Landing");
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgInventory = settings.createGroup("Inventory");

    // Mapping

    public final Setting<Boolean> mappingMode = sgMapping.add(new BoolSetting.Builder()
        .name("mapping-mode")
        .description("Pauses the player's horizontal movement until nearby chunks are loaded")
        .defaultValue(false)
        .build()
    );

    public final Setting<Integer> mappingRenderRadius = sgMapping.add(new IntSetting.Builder()
        .name("render-radius")
        .description("How many chunks around you need to load before you start moving again.")
        .defaultValue(9)
        .min(0)
        .sliderMax(32)
        .visible(mappingMode::get)
        .build()
    );

    public final Setting<Boolean> mappingHideWarning = sgMapping.add(new BoolSetting.Builder()
        .name("hide-waiting-warning")
        .description("Toggle the warning message about waiting for chunks to load")
        .defaultValue(false)
        .visible(mappingMode::get)
        .build()
    );

    // Building Mode

    public final Setting<Boolean> buildingMode = sgBuildingMode.add(new BoolSetting.Builder()
        .name("building-mode")
        .description("Slows you down when blocks are nearby")
        .defaultValue(false)
        .build()
    );

    public final Setting<Double> buildingModeDistance = sgBuildingMode.add(new DoubleSetting.Builder()
        .name("building-mode-distance")
        .description("Distance from a block that triggers the slowdown")
        .defaultValue(15.0)
        .min(0.1)
        .sliderMax(30)
        .visible(buildingMode::get)
        .build()
    );

    public final Setting<Double> buildingModeMinSpeed = sgBuildingMode.add(new DoubleSetting.Builder()
        .name("building-mode-min-speed")
        .description("Speed you ease down to when near blocks")
        .defaultValue(0.7)
        .min(0)
        .sliderMax(1)
        .visible(buildingMode::get)
        .build()
    );

    // Player Avoidance System

    public final Setting<Boolean> playerAvoidance = sgPlayerAvoidance.add(new BoolSetting.Builder()
        .name("player-avoidance")
        .description("Distances you from other players when they get close")
        .defaultValue(false)
        .build()
    );

    public final Setting<Double> avoidanceRadius = sgPlayerAvoidance.add(new DoubleSetting.Builder()
        .name("radius")
        .description("Distance at which a player triggers avoidance")
        .defaultValue(30.0)
        .min(0)
        .sliderMax(50)
        .visible(playerAvoidance::get)
        .build()
    );

    public final Setting<Boolean> avoidanceIgnoreFriends = sgPlayerAvoidance.add(new BoolSetting.Builder()
        .name("ignore-friends")
        .description("Ignores players on your friends list")
        .defaultValue(true)
        .visible(playerAvoidance::get)
        .build()
    );

    public final Setting<Boolean> avoidWitherSkulls = sgPlayerAvoidance.add(new BoolSetting.Builder()
        .name("avoid-wither-skulls")
        .description("Also avoids wither skulls")
        .defaultValue(true)
        .visible(playerAvoidance::get)
        .build()
    );

    public final Setting<Double> witherSkullRadius = sgPlayerAvoidance.add(new DoubleSetting.Builder()
        .name("wither-skull-radius")
        .description("Distance at which a wither skull triggers avoidance")
        .defaultValue(10.0)
        .min(0)
        .sliderMax(50)
        .visible(() -> playerAvoidance.get() && avoidWitherSkulls.get())
        .build()
    );

    public final Setting<Boolean> avoidArrows = sgPlayerAvoidance.add(new BoolSetting.Builder()
        .name("avoid-arrows")
        .description("Also avoids arrows")
        .defaultValue(true)
        .visible(playerAvoidance::get)
        .build()
    );

    public final Setting<Double> arrowRadius = sgPlayerAvoidance.add(new DoubleSetting.Builder()
        .name("arrow-radius")
        .description("Distance at which an arrow triggers avoidance")
        .defaultValue(50.0)
        .min(0)
        .sliderMax(50)
        .visible(() -> playerAvoidance.get() && avoidArrows.get())
        .build()
    );

    public final Setting<Boolean> avoidBlocks = sgPlayerAvoidance.add(new BoolSetting.Builder()
        .name("avoid-blocks")
        .description("Also avoids nearby blocks while moving away from players and wither skulls")
        .defaultValue(true)
        .visible(playerAvoidance::get)
        .build()
    );

    public final Setting<Double> blockAvoidanceRadius = sgPlayerAvoidance.add(new DoubleSetting.Builder()
        .name("block-radius")
        .description("Distance at which a block triggers avoidance")
        .defaultValue(3.0)
        .min(0)
        .sliderMax(10)
        .visible(() -> playerAvoidance.get() && avoidBlocks.get())
        .build()
    );

    public final Setting<Boolean> avoidanceVerticalStep = sgPlayerAvoidance.add(new BoolSetting.Builder()
        .name("vertical-step")
        .description("Moves up or down if avoidance gets you stuck, then avoids normally again")
        .defaultValue(true)
        .visible(playerAvoidance::get)
        .build()
    );

    public final Setting<Integer> avoidanceStuckTicks = sgPlayerAvoidance.add(new IntSetting.Builder()
        .name("stuck-ticks")
        .description("How long you have to be stuck for vertical movement to kick in")
        .defaultValue(3)
        .min(1)
        .sliderMax(20)
        .visible(() -> playerAvoidance.get() && avoidanceVerticalStep.get())
        .build()
    );

    public final Setting<Boolean> avoidanceLateral = sgPlayerAvoidance.add(new BoolSetting.Builder()
        .name("sidestep")
        .description("Tries to move sideways to an incoming player rather than simply away")
        .defaultValue(true)
        .visible(playerAvoidance::get)
        .build()
    );

    // Landing

    public final Setting<Boolean> landGently = sgLanding.add(new BoolSetting.Builder()
        .name("anti-slam")
        .description("Slows you down when landing to prevent fall damage")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> landGentlyDistance = sgLanding.add(new DoubleSetting.Builder()
        .name("anti-slam-distance")
        .description("Distance from the ground where slowing begins")
        .defaultValue(20.0)
        .min(0.1)
        .sliderMax(30)
        .visible(landGently::get)
        .build()
    );

    public final Setting<Double> landGentlyMinDistance = sgLanding.add(new DoubleSetting.Builder()
        .name("anti-slam-min-distance")
        .description("Distance from the ground where speed reaches the minimum value")
        .defaultValue(0.5)
        .min(0)
        .sliderMax(5)
        .visible(landGently::get)
        .build()
    );

    public final Setting<Double> landGentlyMinSpeed = sgLanding.add(new DoubleSetting.Builder()
        .name("anti-slam-min-speed")
        .description("The speed to slow down to before landing")
        .defaultValue(0.2)
        .min(0)
        .sliderMax(1)
        .visible(landGently::get)
        .build()
    );

    // General

    public final Setting<Double> horizontalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("maximum-horizontal-speed")
        .description("The fastest horizontal speed will go (blocks per tick)")
        .defaultValue(14.999)
        .min(0)
        .build()
    );

    public final Setting<Double> verticalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("maximum-vertical-speed")
        .description("The fastest vertical speed will go (blocks per tick)")
        .defaultValue(29.999)
        .min(0)
        .build()
    );

    public final Setting<Double> startSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("minimum-horizontal-speed")
        .description("The speed you start at when moving horizontally, before acceleration kicks in")
        .min(0)
        .defaultValue(2.999)
        .build()
    );

    public final Setting<Double> verticalStartSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("minimum-vertical-speed")
        .description("The speed you start at when moving vertically, before acceleration kicks in")
        .min(0)
        .defaultValue(7.999)
        .build()
    );

    public final Setting<Double> accelerationPlateau = sgGeneral.add(new DoubleSetting.Builder()
        .name("horizontal-acceleration-plateau")
        .description("The horizontal speed where acceleration will tend to 0")
        .min(0.01)
        .defaultValue(14.999)
        .build()
    );

    public final Setting<Double> verticalAccelerationPlateau = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-acceleration-plateau")
        .description("The vertical speed where acceleration will tend to 0")
        .min(0.01)
        .defaultValue(29.999)
        .build()
    );

    public final Setting<Boolean> accelerateUpward = sgGeneral.add(new BoolSetting.Builder()
        .name("accelerate-upward")
        .description("Also accelerates upwards. Not recommended if vertical speed goes above 7.999")
        .defaultValue(false)
        .build()
    );

    public final Setting<Integer> accelerationDelay = sgGeneral.add(new IntSetting.Builder()
        .name("acceleration-delay")
        .description("Adds a slight delay before accelerating. 1 Tick is necessary to avoid getting stuck.")
        .min(0)
        .sliderMax(100)
        .defaultValue(1)
        .build()
    );

    public final Setting<Double> accelerationStep = sgGeneral.add(new DoubleSetting.Builder()
        .name("horizontal-acceleration-step")
        .description("How fast horizontal speed ramps up")
        .min(0.01)
        .max(5)
        .defaultValue(0.3)
        .build()
    );

    public final Setting<Double> verticalAccelerationStep = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-acceleration-step")
        .description("How fast vertical speed ramps up")
        .min(0.01)
        .max(5)
        .defaultValue(1.0)
        .build()
    );

    public final Setting<Boolean> limitMaxHeight = sgGeneral.add(new BoolSetting.Builder()
        .name("limit-max-height")
        .description("Stops you from flying above a set height")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> maxHeight = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-height")
        .description("The max height that you will be able to reach")
        .defaultValue(500.0)
        .min(-128)
        .sliderMax(500)
        .visible(limitMaxHeight::get)
        .build()
    );

    public final Setting<Boolean> autoTakeOff = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-take-off")
        .description("Takes off automatically without needing to double jump")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> stopInWater = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-in-water")
        .description("Stops flying when you touch water")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> dontGoIntoUnloadedChunks = sgGeneral.add(new BoolSetting.Builder()
        .name("no-unloaded-chunks")
        .description("Stops you from flying into unloaded chunks")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> noCrash = sgGeneral.add(new BoolSetting.Builder()
        .name("no-crash")
        .description("Stops you from flying into walls")
        .defaultValue(false)
        .build()
    );

    public final Setting<Integer> crashLookAhead = sgGeneral.add(new IntSetting.Builder()
        .name("crash-look-ahead")
        .description("Distance to look ahead for walls")
        .defaultValue(3)
        .range(1, 15)
        .sliderMin(1)
        .visible(noCrash::get)
        .build()
    );

    private final Setting<Boolean> instaDrop = sgGeneral.add(new BoolSetting.Builder()
        .name("insta-drop")
        .description("Instantly drops you out of flight")
        .defaultValue(false)
        .build()
    );

    public final Setting<Double> fallMultiplier = sgGeneral.add(new DoubleSetting.Builder()
        .name("fall-multiplier")
        .description("Multiplier for how fast you fall naturally")
        .defaultValue(0)
        .min(0)
        .build()
    );

    // Inventory

    public final Setting<Boolean> replace = sgInventory.add(new BoolSetting.Builder()
        .name("elytra-replace")
        .description("Replaces a broken elytra with a new one")
        .defaultValue(false)
        .build()
    );

    public final Setting<Integer> replaceDurability = sgInventory.add(new IntSetting.Builder()
        .name("replace-durability")
        .description("Durability left on the elytra before it's replaced")
        .defaultValue(2)
        .sliderRange(1, 500)
        .visible(replace::get)
        .build()
    );

    public final Setting<ChestSwapMode> chestSwap = sgInventory.add(new EnumSetting.Builder<ChestSwapMode>()
        .name("chest-swap")
        .description("Swaps to an elytra when toggling this module")
        .defaultValue(ChestSwapMode.Never)
        .build()
    );

    public final Setting<Boolean> autoReplenish = sgInventory.add(new BoolSetting.Builder()
        .name("replenish-fireworks")
        .description("Moves fireworks into a chosen hotbar slot")
        .defaultValue(false)
        .build()
    );

    public final Setting<Integer> replenishSlot = sgInventory.add(new IntSetting.Builder()
        .name("replenish-slot")
        .description("Hotbar slot to move fireworks into")
        .defaultValue(9)
        .range(1, 9)
        .sliderRange(1, 9)
        .visible(autoReplenish::get)
        .build()
    );

    // Autopilot

    public final Setting<Boolean> autoPilot = sgAutopilot.add(new BoolSetting.Builder()
        .name("auto-pilot")
        .description("Moves forward automatically while elytra flying")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> useFireworks = sgAutopilot.add(new BoolSetting.Builder()
        .name("use-fireworks")
        .description("Uses fireworks automatically at an interval")
        .defaultValue(false)
        .visible(autoPilot::get)
        .build()
    );

    public final Setting<Double> autoPilotFireworkDelay = sgAutopilot.add(new DoubleSetting.Builder()
        .name("firework-delay")
        .description("Seconds between automatic firework uses")
        .min(1)
        .defaultValue(8)
        .sliderMax(20)
        .visible(useFireworks::get)
        .build()
    );

    public final Setting<Double> autoPilotMinimumHeight = sgAutopilot.add(new DoubleSetting.Builder()
        .name("minimum-height")
        .description("Minimum height autopilot needs before it flies forward")
        .defaultValue(120)
        .min(-128)
        .sliderMax(260)
        .visible(autoPilot::get)
        .build()
    );

    // Flight state
    private boolean lastJumpPressed;
    private boolean incrementJumpTimer;
    private boolean lastForwardPressed;
    private int jumpTimer;
    private double velX, velY, velZ;
    private double ticksLeft;
    private Vec3 forward, right;
    private double acceleration;
    private boolean atMaxSpeed;
    private int accelerationDelayTicks;
    private double verticalAcceleration;
    private boolean atMaxVerticalSpeed;
    private int verticalAccelerationDelayTicks;
    private boolean mappingWaitingForChunks;

    // Building mode state
    private boolean buildingModeEngaged;
    private double buildingModeEntryHorizontalSpeed;
    private double buildingModeEntryVerticalSpeed;
    private int buildingModeTicksElapsed;
    private static final int BUILDING_MODE_SLOWDOWN_TICKS = 5; // ease to building-mode-min-speed within 1 second

    // Player avoidance state
    private boolean avoidanceSteering;
    private Vec3 avoidanceLateralDir;

    private int avoidanceStuckTicksCount;
    private static final int VERTICAL_STEP_TIMEOUT_TICKS = 40; // safety net in case a step never reaches 1 block
    private boolean verticalStepActive;
    private boolean verticalStepUp;
    private double verticalStepStartY;
    private int verticalStepTicks;

    public VolytraFly() {
        super(Categories.Movement, "volytra-fly", "Specifically designed to maximise elytrafly capabilities and speed on 6b6t");
    }

    @Override
    public void onActivate() {
        atMaxSpeed = false;
        lastJumpPressed = false;
        jumpTimer = 0;
        ticksLeft = 0;
        accelerationDelayTicks = 0;
        acceleration = startSpeed.get();

        atMaxVerticalSpeed = false;
        verticalAccelerationDelayTicks = 0;
        verticalAcceleration = verticalStartSpeed.get();

        buildingModeEngaged = false;

        Player player = mc.player;
        if (player == null) return;

        if ((chestSwap.get() == ChestSwapMode.Always || chestSwap.get() == ChestSwapMode.WaitForGround)
            && player.getItemBySlot(EquipmentSlot.CHEST).getItem() != Items.ELYTRA && isActive()) {
            swapToChestSwap();
        }
    }

    @Override
    public void onDeactivate() {
        mappingWaitingForChunks = false;

        if (autoPilot.get()) mc.options.keyUp.setDown(false);
        releaseAvoidance();
        releaseVerticalStep();

        Player player = mc.player;
        if (player == null) return;

        if (chestSwap.get() == ChestSwapMode.Always && player.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA) {
            swapToChestSwap();
        } else if (chestSwap.get() == ChestSwapMode.WaitForGround) {
            enableGroundListener();
        }

        if (player.isFallFlying() && instaDrop.get()) {
            enableInstaDropListener();
        }
    }

    /**
     * Swaps to an elytra via the ChestSwap module, if it's currently registered.
     */
    private void swapToChestSwap() {
        ChestSwap chestSwapModule = Modules.get().get(ChestSwap.class);
        if (chestSwapModule != null) chestSwapModule.swap();
    }

    @EventHandler
    @SuppressWarnings("unused")
    private void onPlayerMove(PlayerMoveEvent event) {
        Player player = mc.player;
        ClientLevel world = mc.level;
        if (player == null || world == null) return;

        if (!(player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER))) return;

        autoTakeoff();
        updatePlayerAvoidance();

        if (player.isFallFlying()) {
            velX = 0;
            velY = event.movement.y;
            velZ = 0;
            forward = Vec3.directionFromRotation(0, player.getYRot()).scale(0.1);
            right = Vec3.directionFromRotation(0, player.getYRot() + 90).scale(0.1);

            // Handle stopInWater
            if (player.isInWater() && stopInWater.get()) {
                ClientPacketListener networkHandler = mc.getConnection();
                if (networkHandler != null) {
                    networkHandler.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                }
                return;
            }

            handleFallMultiplier();
            handleAutopilot();

            handleAcceleration();
            handleVerticalAcceleration();
            handleHorizontalSpeed();
            handleVerticalSpeed();
            handleLandGently();
            handleBuildingMode();
            handleMaxHeight();

            int chunkX = (int) ((player.getX() + velX) / 16);
            int chunkZ = (int) ((player.getZ() + velZ) / 16);
            if (dontGoIntoUnloadedChunks.get()) {
                if (world.getChunkSource().hasChunk(chunkX, chunkZ)) {
                    ((IVec3) event.movement).meteor$set(velX, velY, velZ);
                } else {
                    // Don't reset acceleration/ramp state here - this fires every tick you're
                    // outrunning chunk loading, and a full zeroAcceleration() would stomp your
                    // ramped/held speed even though you never actually stopped moving. Just
                    // suppress this tick's horizontal movement.
                    ((IVec3) event.movement).meteor$set(0, velY, 0);
                }
            } else {
                ((IVec3) event.movement).meteor$set(velX, velY, velZ);
            }
        } else {
            mappingWaitingForChunks = false;

            if (lastForwardPressed) {
                mc.options.keyUp.setDown(false);
                lastForwardPressed = false;
            }
        }

        if (noCrash.get() && player.isFallFlying()) {
            Vec3 lookAheadPos = player.position().add(player.getDeltaMovement().normalize().scale(crashLookAhead.get()));
            ClipContext raycastContext = new ClipContext(player.position(), new Vec3(lookAheadPos.x, player.getY(), lookAheadPos.z), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
            BlockHitResult hitResult = world.clip(raycastContext);
            // clip() returns MISS instead of null, so check the type
            if (hitResult.getType() == HitResult.Type.BLOCK) {
                ((IVec3) event.movement).meteor$set(0, velY, 0);
            }
        }

    }

    @EventHandler
    @SuppressWarnings("unused")
    private void onTick(@SuppressWarnings("unused") TickEvent.Post event) {
        if (autoReplenish.get()) {
            FindItemResult fireworks = InvUtils.find(Items.FIREWORK_ROCKET);

            if (fireworks.found() && !fireworks.isHotbar()) {
                InvUtils.move().from(fireworks.slot()).toHotbar(replenishSlot.get() - 1);
            }
        }

        Player player = mc.player;
        if (replace.get() && player != null) {
            var chestStack = player.getItemBySlot(EquipmentSlot.CHEST);

            if (chestStack.getItem() == Items.ELYTRA) {
                if (chestStack.getMaxDamage() - chestStack.getDamageValue() <= replaceDurability.get()) {
                    FindItemResult elytra = InvUtils.find(stack -> stack.getMaxDamage() - stack.getDamageValue() > replaceDurability.get() && stack.getItem() == Items.ELYTRA);

                    InvUtils.move().from(elytra.slot()).toArmor(2);
                }
            }
        }
    }

    @EventHandler
    @SuppressWarnings("unused")
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundPlayerPositionPacket) zeroAcceleration();
    }

    /**
     * Warns the user that they have stopped to wait for chunks to load.
     */
    @EventHandler
    @SuppressWarnings("unused")
    private void onRender2D(Render2DEvent event) {
        if (!mappingMode.get() || !mappingWaitingForChunks || mappingHideWarning.get()) return;

        String text = "VolytraFly: Waiting for chunks to load (render radius: " + mappingRenderRadius.get() + ")";
        int orange = 0xFFFFA500;

        int x = (mc.getWindow().getGuiScaledWidth() - mc.font.width(text)) / 2;
        int y = mc.getWindow().getGuiScaledHeight() / 2 + 20;

        event.graphics.text(mc.font, text, x, y, orange, true);
    }

    private void autoTakeoff() {
        Player player = mc.player;
        if (player == null) return;

        if (incrementJumpTimer) jumpTimer++;

        boolean jumpPressed = mc.options.keyJump.isDown();

        if (autoTakeOff.get() && jumpPressed) {
            if (!lastJumpPressed && !player.isFallFlying()) {
                jumpTimer = 0;
                incrementJumpTimer = true;
            }

            if (jumpTimer >= 8) {
                jumpTimer = 0;
                incrementJumpTimer = false;
                player.setJumping(false);
                player.setSprinting(true);
                player.jumpFromGround();

                ClientPacketListener networkHandler = mc.getConnection();
                if (networkHandler != null) {
                    networkHandler.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                }
            }
        }

        lastJumpPressed = jumpPressed;
    }

    private void handleAutopilot() {
        Player player = mc.player;
        if (player == null || !player.isFallFlying()) return;

        // Don't fight the avoidance system's movement with a forced-forward autopilot.
        if (autoPilot.get() && !avoidanceSteering && player.getY() > autoPilotMinimumHeight.get()) {
            mc.options.keyUp.setDown(true);
            lastForwardPressed = true;
        }

        if (useFireworks.get()) {
            if (ticksLeft <= 0) {
                ticksLeft = autoPilotFireworkDelay.get() * 20;

                FindItemResult itemResult = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
                if (!itemResult.found()) return;

                MultiPlayerGameMode interactionManager = mc.gameMode;
                if (interactionManager == null) return;

                if (itemResult.isOffhand()) {
                    interactionManager.useItem(player, InteractionHand.OFF_HAND);
                    player.swing(InteractionHand.OFF_HAND);
                } else {
                    InvUtils.swap(itemResult.slot(), true);

                    interactionManager.useItem(player, InteractionHand.MAIN_HAND);
                    player.swing(InteractionHand.MAIN_HAND);

                    InvUtils.swapBack();
                }
            }
            ticksLeft--;
        }
    }

    private void handleHorizontalSpeed() {
        boolean a = false;
        boolean b = false;

        if (mc.options.keyUp.isDown()) {
            velX += forward.x * getSpeed() * 10;
            velZ += forward.z * getSpeed() * 10;
            a = true;
        } else if (mc.options.keyDown.isDown()) {
            velX -= forward.x * getSpeed() * 10;
            velZ -= forward.z * getSpeed() * 10;
            a = true;
        }

        if (mc.options.keyRight.isDown()) {
            velX += right.x * getSpeed() * 10;
            velZ += right.z * getSpeed() * 10;
            b = true;
        } else if (mc.options.keyLeft.isDown()) {
            velX -= right.x * getSpeed() * 10;
            velZ -= right.z * getSpeed() * 10;
            b = true;
        }

        if (a && b) {
            double diagonal = 1 / Math.sqrt(2);
            velX *= diagonal;
            velZ *= diagonal;
        }

        // Mapping mode: hold horizontal movement at 0 until every chunk within
        // mapping-render-radius has finished loading.
        if (mappingMode.get()) {
            mappingWaitingForChunks = !areChunksLoadedInRadius(mappingRenderRadius.get());

            if (mappingWaitingForChunks) {
                velX = 0;
                velZ = 0;

                // Keep the horizontal ramp at its start while waiting on chunks, so once
                // they finish loading you take off from start-speed again instead of picking up
                // wherever the ramp happened to be when the wait began.
                resetHorizontalAcceleration();
            }
        } else {
            mappingWaitingForChunks = false;
        }
    }

    /**
     * Checks whether every chunk within radius of the user has been loaded.
     */
    private boolean areChunksLoadedInRadius(int radius) {
        if (mc.player == null || mc.level == null) return false;

        int centerX = (int) Math.floor(mc.player.getX()) >> 4;
        int centerZ = (int) Math.floor(mc.player.getZ()) >> 4;
        int radiusSq = radius * radius;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radiusSq) continue;
                if (!mc.level.getChunkSource().hasChunk(centerX + dx, centerZ + dz)) return false;
            }
        }

        return true;
    }

    private void handleVerticalSpeed() {
        if (mc.options.keyJump.isDown()) velY += 0.5 * getVerticalSpeed();
        else if (mc.options.keyShift.isDown()) velY -= 0.5 * getVerticalSpeed();
    }

    private void handleFallMultiplier() {
        if (velY < 0) velY *= fallMultiplier.get();
        else if (velY > 0) velY = 0;
    }

    /**
     * Slows the user's speed when landing.
     * Even if a corner is about to catch the user, the user will be slowed down.
     */
    private void handleLandGently() {
        if (!landGently.get() || velY >= 0) return;

        double currentSpeed = -velY;
        double minSpeed = landGentlyMinSpeed.get();
        if (currentSpeed <= minSpeed) return; // already gentler than the floor - nothing to do

        double startDist = landGentlyDistance.get();
        double minDist = Math.min(landGentlyMinDistance.get(), startDist);

        double distanceToGround = distanceToGroundBelow(startDist);
        if (distanceToGround >= startDist) return; // nothing close enough to start slowing, anywhere under the hitbox

        double allowedSpeed;
        if (distanceToGround <= minDist) {
            allowedSpeed = minSpeed;
        } else {
            double t = (distanceToGround - minDist) / Math.max(startDist - minDist, 0.0001);
            allowedSpeed = minSpeed + t * (currentSpeed - minSpeed);
        }

        if (allowedSpeed < currentSpeed) velY = -allowedSpeed;
    }

    /**
     * Distance straight down to the nearest block, sampled across a 3x3 grid spanning the
     * player's horizontal hitbox footprint.
     */
    private double distanceToGroundBelow(double maxDistance) {
        Player player = mc.player;
        ClientLevel world = mc.level;
        if (player == null || world == null) return maxDistance;

        AABB box = player.getBoundingBox();
        double y = player.getY();

        double[] xs = {box.minX, (box.minX + box.maxX) / 2.0, box.maxX};
        double[] zs = {box.minZ, (box.minZ + box.maxZ) / 2.0, box.maxZ};

        double nearest = maxDistance;

        for (double x : xs) {
            for (double z : zs) {
                Vec3 start = new Vec3(x, y, z);
                Vec3 end = start.add(0, -maxDistance, 0);

                ClipContext raycastContext = new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
                BlockHitResult hitResult = world.clip(raycastContext);

                // same deal, clip() never returns null
                if (hitResult.getType() != HitResult.Type.BLOCK) continue;

                double distance = y - hitResult.getLocation().y;
                if (distance < nearest) nearest = distance;
            }
        }

        return nearest;
    }

    /**
     * Building mode eases you into its speed.
     * <p>
     * The moment a block comes within building-mode-distance, the cap starts at your current
     * speed and eases toward building-mode-min-speed.
     * <p>
     * The acceleration ramp is also reset every tick you're within range of blocks to prevent it from
     * silently climbing in the background.
     */
    private void handleBuildingMode() {
        if (!buildingMode.get() || !isNearAnyBlock(buildingModeDistance.get())) {
            buildingModeEngaged = false;
            return;
        }

        zeroAcceleration();

        double minSpeed = buildingModeMinSpeed.get();
        double verticalTarget = minSpeed * 1.5;

        double horizontalSpeed = Math.hypot(velX, velZ);
        double verticalSpeed = Math.abs(velY);

        if (!buildingModeEngaged) {
            // Just entered range this tick - remember the entry speed (never below the target)
            // so the ease-down curve below has a fixed start point to interpolate from.
            // Start the tick counter so it lands on target after BUILDING_MODE_SLOWDOWN_TICKS.
            buildingModeEntryHorizontalSpeed = Math.max(horizontalSpeed, minSpeed);
            buildingModeEntryVerticalSpeed = Math.max(verticalSpeed, verticalTarget);
            buildingModeTicksElapsed = 0;
            buildingModeEngaged = true;
        } else {
            buildingModeTicksElapsed++;
        }

        double t = Math.min(1.0, buildingModeTicksElapsed / (double) BUILDING_MODE_SLOWDOWN_TICKS);
        double buildingModeHorizontalCap = buildingModeEntryHorizontalSpeed + (minSpeed - buildingModeEntryHorizontalSpeed) * t;
        double buildingModeVerticalCap = buildingModeEntryVerticalSpeed + (verticalTarget - buildingModeEntryVerticalSpeed) * t;

        if (horizontalSpeed > buildingModeHorizontalCap) {
            double scale = buildingModeHorizontalCap / horizontalSpeed;
            velX *= scale;
            velZ *= scale;
        }

        if (verticalSpeed > buildingModeVerticalCap) {
            velY = Math.signum(velY) * buildingModeVerticalCap;
        }
    }

    /**
     * The player's bounding box is extended by distance on every axis and asks the world for block
     * collisions in that box.
     */
    private boolean isNearAnyBlock(double distance) {
        Player player = mc.player;
        ClientLevel world = mc.level;
        if (player == null || world == null) return false;

        AABB box = player.getBoundingBox().inflate(distance);
        return world.getBlockCollisions(player, box).iterator().hasNext();
    }

    /**
     * Hard-caps upward vertical speed so this tick's movement can't put you above max height.
     */
    private void handleMaxHeight() {
        if (!limitMaxHeight.get() || velY <= 0) return;

        Player player = mc.player;
        if (player == null) return;

        double limit = maxHeight.get();
        double currentY = player.getY();

        if (currentY >= limit) {
            velY = 0;
        } else if (currentY + velY > limit) {
            velY = limit - currentY;
        }
    }

    private void handleAcceleration() {
        boolean movementKeyPressed = mc.options.keyUp.isDown() || mc.options.keyDown.isDown()
            || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown();

        if (!movementKeyPressed) {
            // No movement key held - reset back to the start of the sequence. Nothing is
            // remembered between releases.
            resetHorizontalAcceleration();
            return;
        }

        if (atMaxSpeed) return; // Already at horizontal-speed - nothing left to do.

        if (accelerationDelayTicks < accelerationDelay.get()) {
            // Still in the delay window - hold at start-speed, don't ramp yet.
            accelerationDelayTicks++;
            return;
        }

        // Curve shape: gain per tick is steep at low speed and tapers toward zero as
        // acceleration approaches the plateau (acceleration-plateau setting).
        double plateau = accelerationPlateau.get();
        double remainingToPlateau = Math.max(0, plateau - acceleration);
        double gain = accelerationStep.get() * (remainingToPlateau / plateau);

        // The actual top speed is still horizontal-speed - the curve just shapes how you get there.
        acceleration = Math.min(acceleration + gain, horizontalSpeed.get());

        // Reached the configured top speed - lock in and stop accelerating.
        if (acceleration >= horizontalSpeed.get()) {
            atMaxSpeed = true;
        }
    }

    private void zeroAcceleration() {
        resetHorizontalAcceleration();

        atMaxVerticalSpeed = false;
        verticalAccelerationDelayTicks = 0;
        verticalAcceleration = verticalStartSpeed.get();
    }

    /**
     * Resets the horizontal acceleration ramp back to its starting point.
     */
    private void resetHorizontalAcceleration() {
        atMaxSpeed = false;
        accelerationDelayTicks = 0;
        acceleration = startSpeed.get();
    }

    private double getSpeed() {
        return acceleration;
    }

    /**
     * Mirrors handleAcceleration().
     * By default, this ramp only applies going downwards.
     */
    private void handleVerticalAcceleration() {
        boolean movingUp = mc.options.keyJump.isDown();
        boolean movingDown = mc.options.keyShift.isDown();

        if (!movingUp && !movingDown) {
            // No vertical key held - reset back to the start of the sequence. Nothing is
            // remembered between releases.
            atMaxVerticalSpeed = false;
            verticalAccelerationDelayTicks = 0;
            verticalAcceleration = verticalStartSpeed.get();
            return;
        }

        if (movingUp && !accelerateUpward.get()) {
            // Keep resetting to the start of the curve so a subsequent downward press (or an upward one,
            // if accelerate-upward gets turned on later) starts fresh from vertical-start-speed.
            atMaxVerticalSpeed = false;
            verticalAccelerationDelayTicks = 0;
            verticalAcceleration = verticalStartSpeed.get();
            return;
        }

        if (atMaxVerticalSpeed) return; // Already at vertical-speed, nothing left to do.

        if (verticalAccelerationDelayTicks < accelerationDelay.get()) {
            // Still in the delay window - hold at start-speed, don't ramp yet.
            verticalAccelerationDelayTicks++;
            return;
        }

        // Same ease-out curve shape as horizontal: gain per tick is steep at low speed and
        // tapers toward zero as acceleration approaches its own plateau.
        double plateau = verticalAccelerationPlateau.get();
        double remainingToPlateau = Math.max(0, plateau - verticalAcceleration);
        double gain = verticalAccelerationStep.get() * (remainingToPlateau / plateau);

        verticalAcceleration = Math.min(verticalAcceleration + gain, verticalSpeed.get());

        // Reached the configured top speed - lock in and stop accelerating.
        if (verticalAcceleration >= verticalSpeed.get()) {
            atMaxVerticalSpeed = true;
        }
    }

    private double getVerticalSpeed() {
        return verticalAcceleration;
    }

    // Player Avoidance System

    /**
     * Moves the user away from nearby players by driving this module's own flight pipeline
     * (WASD key state).
     * Behaves exactly like normal elytra fly movement.
     */
    private void updatePlayerAvoidance() {
        if (!playerAvoidance.get()) {
            releaseAvoidance();
            releaseVerticalStep();
            return;
        }

        if (isMovementKeyPhysicallyPressed() || isKeyPhysicallyPressed(mc.options.keyShift)) {
            // The user is actively moving horizontally or down - don't fight input. Sneak is singled out
            // (even with no WASD held) so deliberately going down is never interrupted by avoidance.
            // Any keys that avoidance had forced and the user isn't holding down get released so they don't stick.
            releaseAvoidanceExceptPhysical();
            releaseVerticalStep();
            return;
        }

        Vec3 away = findAvoidanceAwayVector();
        if (away == null) {
            releaseAvoidance();
            releaseVerticalStep();
            return;
        }

        Player player = mc.player;
        if (player == null || !player.isFallFlying()) return;

        if (verticalStepActive) {
            // A step maneuver already has full control - keep running it instead of fighting it
            // with the normal avoidance below.
            avoidanceSteering = true;
            stepVerticalStep();
            return;
        }

        // Don't touch yaw - instead figure out which WASD combo, relative to where the
        // player is currently looking, would carry them in the avoidance direction.
        steerTowardsDirection(away);
        avoidanceSteering = true;

        if (avoidanceVerticalStep.get()) updateAvoidanceStuckDetection();
    }

    /**
     * Uses WASD movement to direct the user, without touching yaw.
     */
    private void steerTowardsDirection(Vec3 dir) {
        Player player = mc.player;
        if (player == null) return;

        double targetYaw = Math.toDegrees(Math.atan2(-dir.x, dir.z));
        double relative = wrapDegrees(targetYaw - player.getYRot());

        // Snap to the nearest of the 8 directions a keyboard can express (N/NE/E/SE/S/SW/W/NW
        // relative to view direction) and press the corresponding key(s).
        int octant = ((int) Math.round(relative / 45.0) % 8 + 8) % 8;

        mc.options.keyUp.setDown(octant == 0 || octant == 1 || octant == 7);
        mc.options.keyDown.setDown(octant == 3 || octant == 4 || octant == 5);
        mc.options.keyRight.setDown(octant == 1 || octant == 2 || octant == 3);
        mc.options.keyLeft.setDown(octant == 5 || octant == 6 || octant == 7);
    }

    private Vec3 findAvoidanceAwayVector() {
        Player self = mc.player;
        ClientLevel world = mc.level;
        if (self == null || world == null) return null;

        double[] acc = {0, 0};
        boolean foundThreat = false;

        for (Player player : world.players()) {
            if (player == self) continue;
            if (avoidanceIgnoreFriends.get() && Friends.get().isFriend(player)) continue;

            if (accumulateThreat(player.position(), avoidanceRadius.get(), acc)) foundThreat = true;
        }

        if (avoidWitherSkulls.get() || avoidArrows.get()) {
            for (Entity entity : world.entitiesForRendering()) {
                if (avoidWitherSkulls.get() && entity instanceof WitherSkull) {
                    if (accumulateThreat(entity.position(), witherSkullRadius.get(), acc)) foundThreat = true;
                } else if (avoidArrows.get() && entity instanceof Arrow) {
                    if (accumulateThreat(entity.position(), arrowRadius.get(), acc)) foundThreat = true;
                }
            }
        }

        // Block avoidance is meant to avoid blocks while you're already dodging a
        // threat, not to run all the time.
        if (avoidBlocks.get() && isBlockAvoidanceTriggered()) {
            if (accumulateNearbyBlockThreats(blockAvoidanceRadius.get(), acc)) foundThreat = true;
        }

        if (!foundThreat) return null;

        Vec3 direction = new Vec3(acc[0], 0, acc[1]);
        if (direction.lengthSqr() == 0) return null;

        direction = direction.normalize();

        if (avoidanceLateral.get()) {
            direction = lateralize(direction);
        } else {
            avoidanceLateralDir = null;
        }

        return direction;
    }

    /**
     * Checks if a player is currently within the player avoidance radius.
     * Uses a plain distance check meaning that a player standing directly above or below
     * (sooo technically not in any horizontal direction) still counts as nearby.
     */
    private boolean isPlayerWithinAvoidanceRadius() {
        Player self = mc.player;
        ClientLevel world = mc.level;
        if (self == null || world == null) return false;

        double radius = avoidanceRadius.get();
        if (radius <= 0) return false;

        double radiusSq = radius * radius;

        for (Player player : world.players()) {
            if (player == self) continue;
            if (avoidanceIgnoreFriends.get() && Friends.get().isFriend(player)) continue;

            if (self.position().distanceToSqr(player.position()) < radiusSq) return true;
        }

        return false;
    }

    /**
     * Whether any wither skull is currently within the wither skull avoidance radius.
     */
    private boolean isWitherSkullWithinAvoidanceRadius() {
        if (!avoidWitherSkulls.get()) return false;

        Player self = mc.player;
        ClientLevel world = mc.level;
        if (self == null || world == null) return false;

        double radius = witherSkullRadius.get();
        if (radius <= 0) return false;

        double radiusSq = radius * radius;

        for (Entity entity : world.entitiesForRendering()) {
            if (!(entity instanceof WitherSkull)) continue;
            if (self.position().distanceToSqr(entity.position()) < radiusSq) return true;
        }

        return false;
    }

    /**
     * Whether block avoidance's trigger condition is met.
     */
    private boolean isBlockAvoidanceTriggered() {
        return isPlayerWithinAvoidanceRadius() || isWitherSkullWithinAvoidanceRadius();
    }

    /**
     * Rotates the radial avoidance direction 90 degrees to a sideways one.
     * Uses two near-perpendicular values (90 +/- 10 degrees) on each side
     * and keeps the one with a larger positive component back along 'away', so the
     * user gets pushed outward.
     */
    private Vec3 lateralize(Vec3 away) {
        Vec3 perpA = new Vec3(-away.z, 0, away.x);
        Vec3 perpB = new Vec3(away.z, 0, -away.x);

        boolean sideA;
        if (avoidanceLateralDir != null) {
            sideA = avoidanceLateralDir.dot(perpA) >= avoidanceLateralDir.dot(perpB);
        } else {
            Player player = mc.player;
            Vec3 vel = player != null ? player.getDeltaMovement() : Vec3.ZERO;
            Vec3 horizontalVel = new Vec3(vel.x, 0, vel.z);
            sideA = !(horizontalVel.lengthSqr() > 1.0E-4 && horizontalVel.dot(perpB) > horizontalVel.dot(perpA));
        }

        double baseAngle = sideA ? 90 : -90;
        Vec3 optionA = rotateHorizontal(away, baseAngle - 10);
        Vec3 optionB = rotateHorizontal(away, baseAngle + 10);
        Vec3 chosen = optionA.dot(away) >= optionB.dot(away) ? optionA : optionB;

        avoidanceLateralDir = chosen;
        return chosen;
    }

    /** Rotates a horizontal-only vector by the given angle around the Y axis. */
    private Vec3 rotateHorizontal(Vec3 v, double degrees) {
        double rad = Math.toRadians(degrees);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        return new Vec3(v.x * cos - v.z * sin, 0, v.x * sin + v.z * cos);
    }

    /**
     * Adds this threat's contribution to the accumulator, weighted so
     * closer threats push harder than ones near the edge of their radius.
     * Returns whether the threat was close enough to contribute at all.
     */
    private boolean accumulateThreat(Vec3 threatPos, double radius, double[] acc) {
        if (radius <= 0) return false;

        Player player = mc.player;
        if (player == null) return false;

        double distanceSq = player.position().distanceToSqr(threatPos);
        if (distanceSq >= radius * radius || distanceSq == 0) return false;

        Vec3 awayFromThreat = player.position().subtract(threatPos);
        awayFromThreat = new Vec3(awayFromThreat.x, 0, awayFromThreat.z);
        if (awayFromThreat.lengthSqr() == 0) return false;

        double weight = 1 - (Math.sqrt(distanceSq) / radius);
        awayFromThreat = awayFromThreat.normalize().scale(weight);

        acc[0] += awayFromThreat.x;
        acc[1] += awayFromThreat.z;
        return true;
    }

    /**
     * Scans every block position within radius blocks of the user, skipping anything below the
     * player's feet, and adds each remaining solid block's contribution (webs will count
     * as solid too) to the avoidance direction.
     */
    private boolean accumulateNearbyBlockThreats(double radius, double[] acc) {
        if (radius <= 0) return false;

        Player player = mc.player;
        ClientLevel world = mc.level;
        if (player == null || world == null) return false;

        boolean foundThreat = false;
        BlockPos center = player.blockPosition();
        int r = (int) Math.ceil(radius);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = 0; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = world.getBlockState(pos);

                    // Cobwebs report an empty collision shape (their slowdown is applied via
                    // entity collision, not physical collision).
                    boolean isCobweb = state.is(Blocks.COBWEB);
                    if (!isCobweb && state.getCollisionShape(world, pos).isEmpty()) continue;

                    Vec3 blockCenter = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    if (accumulateThreat(blockCenter, radius, acc)) foundThreat = true;
                }
            }
        }

        return foundThreat;
    }

    /**
     * Avoidance only moves horizontally (WASD) by default, so if the avoidance direction happens to
     * point into a wall, the player can end up holding a movement key against a solid block forever and never
     * actually get away.
     * This tries to prevent that issue: if the player stays horizontally collided for stuck-ticks in a row while
     * avoidance is actively steering, it starts a vertical step (see beginVerticalStep()) instead
     * of continuing to fight the wall with horizontal input.
     */
    private void updateAvoidanceStuckDetection() {
        Player player = mc.player;
        if (player == null) return;

        avoidanceStuckTicksCount = player.horizontalCollision ? avoidanceStuckTicksCount + 1 : 0;
        if (avoidanceStuckTicksCount < avoidanceStuckTicks.get()) return;

        beginVerticalStep();
    }

    /**
     * If stuck and unable to move horizontally, checks if a single block of clearance is above or below the user and
     * takes that vertical path until the user has moved about a block in that direction.
     * If both directions are blocked, does nothing and stays stuck, but will try again on the next tick.
     */
    private void beginVerticalStep() {
        Player player = mc.player;
        if (player == null) return;

        boolean upClear = hasVerticalClearance(1.0);
        boolean downClear = hasVerticalClearance(-1.0);

        if (upClear) {
            verticalStepUp = true;
        } else if (downClear) {
            verticalStepUp = false;
        } else {
            avoidanceStuckTicksCount = 0;
            return;
        }

        verticalStepActive = true;
        verticalStepStartY = player.getY();
        verticalStepTicks = 0;
        avoidanceStuckTicksCount = 0;
        releaseHorizontalKeys();
    }

    /**
     * Checks if a full block of space is open directly above or below
     * the player's current hitbox.
     */
    private boolean hasVerticalClearance(double dy) {
        Player player = mc.player;
        ClientLevel world = mc.level;
        if (player == null || world == null) return false;

        AABB box = player.getBoundingBox().move(0, dy, 0);
        return !world.getBlockCollisions(player, box).iterator().hasNext();
    }

    /**
     * Presses jump or sneak every tick until the player has moved roughly a block in that direction,
     * then switches back to normal avoidance movement.
     * A tick timeout protects against never quite reaching a full block.
     */
    private void stepVerticalStep() {
        Player player = mc.player;
        if (player == null) {
            releaseVerticalStep();
            return;
        }

        verticalStepTicks++;

        double traveled = Math.abs(player.getY() - verticalStepStartY);
        if (traveled >= 1.0 || verticalStepTicks > VERTICAL_STEP_TIMEOUT_TICKS) {
            releaseVerticalStep();
            return;
        }

        if (!isKeyPhysicallyPressed(mc.options.keyJump)) mc.options.keyJump.setDown(verticalStepUp);
        if (!isKeyPhysicallyPressed(mc.options.keyShift)) mc.options.keyShift.setDown(!verticalStepUp);
    }

    private void releaseVerticalStep() {
        avoidanceStuckTicksCount = 0;

        if (verticalStepActive) {
            if (!isKeyPhysicallyPressed(mc.options.keyJump)) mc.options.keyJump.setDown(false);
            if (!isKeyPhysicallyPressed(mc.options.keyShift)) mc.options.keyShift.setDown(false);
        }

        verticalStepActive = false;
        verticalStepTicks = 0;
    }

    /**
     * Releases the four horizontal movement keys, skipping any that the user is holding down physically.
     * Used when handing control over to a vertical step, which only needs jump/sneak while it runs.
     */
    private void releaseHorizontalKeys() {
        if (!isKeyPhysicallyPressed(mc.options.keyUp)) mc.options.keyUp.setDown(false);
        if (!isKeyPhysicallyPressed(mc.options.keyDown)) mc.options.keyDown.setDown(false);
        if (!isKeyPhysicallyPressed(mc.options.keyLeft)) mc.options.keyLeft.setDown(false);
        if (!isKeyPhysicallyPressed(mc.options.keyRight)) mc.options.keyRight.setDown(false);
    }

    private void releaseAvoidance() {
        avoidanceLateralDir = null;

        if (!avoidanceSteering) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        avoidanceSteering = false;
    }

    /**
     * Like releaseAvoidance(), but leaves alone any of the four movement keys the user is
     * holding down, so a manual override doesn't stomp their real input on the same tick it starts.
     */
    private void releaseAvoidanceExceptPhysical() {
        avoidanceLateralDir = null;

        if (!avoidanceSteering) return;

        if (!isKeyPhysicallyPressed(mc.options.keyUp)) mc.options.keyUp.setDown(false);
        if (!isKeyPhysicallyPressed(mc.options.keyDown)) mc.options.keyDown.setDown(false);
        if (!isKeyPhysicallyPressed(mc.options.keyLeft)) mc.options.keyLeft.setDown(false);
        if (!isKeyPhysicallyPressed(mc.options.keyRight)) mc.options.keyRight.setDown(false);

        avoidanceSteering = false;
    }

    private boolean isMovementKeyPhysicallyPressed() {
        return isKeyPhysicallyPressed(mc.options.keyUp)
            || isKeyPhysicallyPressed(mc.options.keyDown)
            || isKeyPhysicallyPressed(mc.options.keyLeft)
            || isKeyPhysicallyPressed(mc.options.keyRight);
    }

    // find the bound-key field by type instead of by name since it keeps getting renamed
    private static Field boundKeyField;
    private static boolean boundKeyFieldSearched = false;

    private static InputConstants.Key getBoundKey(KeyMapping binding) {
        if (!boundKeyFieldSearched) {
            boundKeyFieldSearched = true;

            // skip the static/final "default" field, we want the current binding
            for (Field field : KeyMapping.class.getDeclaredFields()) {
                if (field.getType() != InputConstants.Key.class) continue;

                int mods = field.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(mods)) continue;
                if (java.lang.reflect.Modifier.isFinal(mods)) continue;
                if (field.getName().toLowerCase().contains("default")) continue;

                field.setAccessible(true);
                boundKeyField = field;
                break;
            }
        }

        if (boundKeyField == null) return null;

        try {
            return (InputConstants.Key) boundKeyField.get(binding);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /**
     * Checks the actual hardware key state rather than KeyMapping.isDown().
     */
    private boolean isKeyPhysicallyPressed(KeyMapping binding) {
        InputConstants.Key key = getBoundKey(binding);
        if (key == null) return binding.isDown();
        if (key.getType() != InputConstants.Type.KEYSYM) return binding.isDown();

        return InputConstants.isKeyDown(mc.getWindow(), key.getValue());
    }

    /**
     * Wraps a degree value to the range (-180, 180].
     */
    private static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) wrapped -= 360.0;
        if (wrapped < -180.0) wrapped += 360.0;
        return wrapped;
    }

    //Ground
    private class StaticGroundListener {
        @EventHandler
        @SuppressWarnings("unused")
        private void chestSwapGroundListener(@SuppressWarnings("unused") PlayerMoveEvent event) {
            Player player = mc.player;
            if (player == null || !player.onGround()) return;

            if (player.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA) {
                swapToChestSwap();
                disableGroundListener();
            }
        }
    }

    private final StaticGroundListener staticGroundListener = new StaticGroundListener();

    protected void enableGroundListener() {
        MeteorClient.EVENT_BUS.subscribe(staticGroundListener);
    }

    protected void disableGroundListener() {
        MeteorClient.EVENT_BUS.unsubscribe(staticGroundListener);
    }

    //Drop
    private class StaticInstaDropListener {
        @EventHandler
        @SuppressWarnings("unused")
        private void onInstadropTick(@SuppressWarnings("unused") TickEvent.Post event) {
            LocalPlayer player = mc.player;
            if (player != null && player.isFallFlying()) {
                player.setDeltaMovement(Vec3.ZERO);
                player.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.StatusOnly(true, player.horizontalCollision));
            } else {
                disableInstaDropListener();
            }
        }
    }

    private final StaticInstaDropListener staticInstadropListener = new StaticInstaDropListener();

    protected void enableInstaDropListener() {
        MeteorClient.EVENT_BUS.subscribe(staticInstadropListener);
    }

    protected void disableInstaDropListener() {
        MeteorClient.EVENT_BUS.unsubscribe(staticInstadropListener);
    }

    public enum ChestSwapMode {
        Always,
        Never,
        WaitForGround
    }

}
