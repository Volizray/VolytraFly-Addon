/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package com.volytrafly.modules.movement.volytrafly;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.meteor.KeyInputEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.ChestSwap;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import com.volytrafly.hud.VolytraAssistantHud;
import com.mojang.blaze3d.platform.InputConstants;
import com.volytrafly.hud.ElytraFuelHud;
import com.volytrafly.hud.HudLayout;
import com.volytrafly.hud.ModeLightsHud;
import com.volytrafly.hud.SpeedometerHud;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

public class VolytraFly extends Module {
    private final SettingGroup sgAutopilot = settings.createGroup("Autopilot");
    private final SettingGroup sgHighway = settings.createGroup("Highway Mode");
    private final SettingGroup sgMapping = settings.createGroup("Mapping");
    private final SettingGroup sgBuildingMode = settings.createGroup("Building Mode");
    private final SettingGroup sgBypassMode = settings.createGroup("Bypass Mode");
    private final SettingGroup sgPlayerAvoidance = settings.createGroup("Player Avoidance System");
    private final SettingGroup sgLanding = settings.createGroup("Landing");
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgInventory = settings.createGroup("Inventory");
    private final SettingGroup sgVisuals = settings.createGroup("Visuals");

    // Mapping

    public final Setting<Boolean> mappingMode = sgMapping.add(new BoolSetting.Builder()
        .name("mapping-mode")
        .description("Pauses the player's horizontal movement until nearby chunks are loaded")
        .defaultValue(false)
        .build()
    );

    public final Setting<Keybind> mappingModeKeybind = sgMapping.add(new KeybindSetting.Builder()
        .name("mapping-mode-keybind")
        .description("Key to toggle Mapping Mode")
        .defaultValue(Keybind.none())
        .build()
    );

    public final Setting<Integer> mappingRenderRadius = sgMapping.add(new IntSetting.Builder()
        .name("render-radius")
        .description("How many chunks around you need to load before you start moving again")
        .defaultValue(9)
        .min(0)
        .sliderMax(32)
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

    public final Setting<Keybind> buildingModeKeybind = sgBuildingMode.add(new KeybindSetting.Builder()
        .name("building-mode-keybind")
        .description("Key to toggle Building Mode")
        .defaultValue(Keybind.none())
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

    // Bypass Mode

    public final Setting<Boolean> bypassMode = sgBypassMode.add(new BoolSetting.Builder()
        .name("bypass-mode")
        .description("Switches out acceleration with additive flight to behave more like normal elytra flight")
        .defaultValue(false)
        .build()
    );

    public final Setting<Keybind> bypassModeKeybind = sgBypassMode.add(new KeybindSetting.Builder()
        .name("bypass-mode-keybind")
        .description("Key to toggle Bypass Mode")
        .defaultValue(Keybind.none())
        .build()
    );

    // Player Avoidance System

    public final Setting<Boolean> playerAvoidance = sgPlayerAvoidance.add(new BoolSetting.Builder()
        .name("player-avoidance")
        .description("Distances you from other players when they get close")
        .defaultValue(false)
        .build()
    );

    public final Setting<Keybind> playerAvoidanceKeybind = sgPlayerAvoidance.add(new KeybindSetting.Builder()
        .name("player-avoidance-keybind")
        .description("Key to toggle Player Avoidance.")
        .defaultValue(Keybind.none())
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
        .defaultValue(false)
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

    public final Setting<Boolean> avoidanceLateral = sgPlayerAvoidance.add(new BoolSetting.Builder()
        .name("sidestep")
        .description("Tries to move sideways to an incoming player rather than simply away")
        .defaultValue(true)
        .visible(playerAvoidance::get)
        .build()
    );

    // Landing

    public final Setting<Boolean> antiSlam = sgLanding.add(new BoolSetting.Builder()
        .name("anti-slam")
        .description("Slows you down when landing to prevent fall damage")
        .defaultValue(true)
        .build()
    );

    public final Setting<Keybind> antiSlamKeybind = sgLanding.add(new KeybindSetting.Builder()
        .name("anti-slam-keybind")
        .description("Key to toggle anti-slam")
        .defaultValue(Keybind.none())
        .build()
    );

    public final Setting<Double> antiSlamDistance = sgLanding.add(new DoubleSetting.Builder()
        .name("anti-slam-distance")
        .description("Distance from the ground where slowing begins")
        .defaultValue(10.0)
        .min(0.1)
        .sliderMax(30)
        .visible(antiSlam::get)
        .build()
    );

    public final Setting<Double> antiSlamMinDistance = sgLanding.add(new DoubleSetting.Builder()
        .name("anti-slam-min-distance")
        .description("Distance from the ground where speed reaches the minimum value")
        .defaultValue(0.5)
        .min(0)
        .sliderMax(5)
        .visible(antiSlam::get)
        .build()
    );

    public final Setting<Double> antiSlamMinSpeed = sgLanding.add(new DoubleSetting.Builder()
        .name("anti-slam-min-speed")
        .description("The speed to slow down to before landing")
        .defaultValue(0.2)
        .min(0)
        .sliderMax(1)
        .visible(antiSlam::get)
        .build()
    );

    // General

    public final Setting<Double> startSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("minimum-horizontal-speed")
        .description("The speed you start at when moving horizontally, before acceleration kicks in")
        .min(0)
        .defaultValue(2.999)
        .build()
    );

    public final Setting<Double> horizontalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("maximum-horizontal-speed")
        .description("The fastest horizontal speed will go (blocks per tick)")
        .defaultValue(14.999)
        .min(0)
        .build()
    );

    public final Setting<Double> accelerationPlateau = sgGeneral.add(new DoubleSetting.Builder()
        .name("horizontal-acceleration-plateau")
        .description("The horizontal speed where acceleration will tend to 0")
        .min(0.01)
        .defaultValue(14.999)
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

    public final Setting<Double> verticalStartSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("minimum-vertical-speed")
        .description("The speed you start at when moving vertically, before acceleration kicks in")
        .min(0)
        .defaultValue(5.999)
        .build()
    );

    public final Setting<Double> verticalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("maximum-vertical-speed")
        .description("The fastest vertical speed will go (blocks per tick)")
        .defaultValue(5.999)
        .min(0)
        .build()
    );

    public final Setting<Double> verticalAccelerationPlateau = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-acceleration-plateau")
        .description("The vertical speed where acceleration will tend to 0")
        .min(0.01)
        .defaultValue(29.999)
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

    public final Setting<Integer> accelerationDelay = sgGeneral.add(new IntSetting.Builder()
        .name("acceleration-delay")
        .description("Adds a slight delay before accelerating. 1 Tick is necessary to avoid getting stuck.")
        .min(0)
        .sliderMax(100)
        .defaultValue(1)
        .build()
    );

    public final Setting<Double> fallMultiplier = sgGeneral.add(new DoubleSetting.Builder()
        .name("fall-multiplier")
        .description("Multiplier for how fast you fall naturally")
        .defaultValue(0)
        .min(0)
        .build()
    );

    public final Setting<Boolean> accelerateUpward = sgGeneral.add(new BoolSetting.Builder()
        .name("accelerate-upward")
        .description("Also accelerates upwards. Not recommended if vertical speed goes above 5.999")
        .defaultValue(false)
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

    public final Setting<Boolean> rubberbandSpeedCap = sgGeneral.add(new BoolSetting.Builder()
        .name("rubberband-speed-cap")
        .description("Checks if rubberbanding is happening frequently near this value. If it is, it'll limit your speed for a little while for smoother flight.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> rubberbandSpeedCapThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("rubberband-speed-cap-threshold")
        .description("The speed you think rubberbands are happening frequently at and to limit your speed to if so")
        .defaultValue(200.0)
        .min(0)
        .sliderMax(400)
        .visible(rubberbandSpeedCap::get)
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

    public final Setting<Keybind> autoPilotKeybind = sgAutopilot.add(new KeybindSetting.Builder()
        .name("auto-pilot-keybind")
        .description("Key to toggle Autopilot.")
        .defaultValue(Keybind.none())
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

    public final Setting<Boolean> highwayMode = sgHighway.add(new BoolSetting.Builder()
        .name("highway-mode")
        .description("Uses a mixture of mob pathfinding and custom navigation to find highways and travel along them")
        .defaultValue(false)
        .build()
    );

    public final Setting<Keybind> highwayModeKeybind = sgHighway.add(new KeybindSetting.Builder()
        .name("highway-mode-keybind")
        .description("Key to toggle Highway Mode")
        .defaultValue(Keybind.none())
        .build()
    );

    public final Setting<HighwayDirection> highwayDirection = sgHighway.add(new EnumSetting.Builder<HighwayDirection>()
        .name("highway-direction")
        .description("Which way to fly along the highway, relative to the world centre (0, 0)")
        .defaultValue(HighwayDirection.AwayFromCentre)
        .visible(highwayMode::get)
        .build()
    );

    public final Setting<Intelligence> highwayIntelligence = sgHighway.add(new EnumSetting.Builder<Intelligence>()
        .name("intelligence")
        .description("How smart Highway Mode is when finding paths. Higher intelligence will use more CPU power.")
        .defaultValue(Intelligence.High)
        .visible(highwayMode::get)
        .build()
    );

    // Visuals

    public final Setting<Boolean> autoArrangeHud = sgVisuals.add(new BoolSetting.Builder()
        .name("auto-arrange-HUD")
        .description("Arranges the HUD neatly on the screen")
        .defaultValue(true)
        .visible(this::isVisualsOn)
        .build()
    );

    public final Setting<Boolean> speedometer = sgVisuals.add(new BoolSetting.Builder()
        .name("speedometer")
        .description("Shows a speedometer HUD while VolytraFly is on")
        .defaultValue(true)
        .onChanged(enabled -> { if (enabled && Utils.canUpdate()) SpeedometerHud.ensureAdded(); })
        .build()
    );

    public final Setting<Double> speedometerScale = sgVisuals.add(new DoubleSetting.Builder()
        .name("speedometer-scale")
        .description("Size of the speedometer")
        .defaultValue(HudLayout.defaultSpeedometerScale())
        .min(0.3)
        .sliderRange(0.5, 10)
        .visible(speedometer::get)
        .build()
    );

    public final Setting<Boolean> modeLights = sgVisuals.add(new BoolSetting.Builder()
        .name("switches")
        .description("Shows a panel while VolytraFly is on, with lights for each toggleable mode")
        .defaultValue(true)
        .onChanged(enabled -> { if (enabled && Utils.canUpdate()) ModeLightsHud.ensureAdded(); })
        .build()
    );

    public final Setting<Boolean> fuelGauge = sgVisuals.add(new BoolSetting.Builder()
        .name("fuel-gauge")
        .description("Shows a gauge next to the speedometer for elytra durability")
        .defaultValue(true)
        .onChanged(enabled -> { if (enabled && Utils.canUpdate()) ElytraFuelHud.ensureAdded(); })
        .build()
    );

    public final Setting<Double> fuelGaugeScale = sgVisuals.add(new DoubleSetting.Builder()
        .name("fuel-gauge-scale")
        .description("Size of the fuel gauge")
        .defaultValue(HudLayout.defaultFuelGaugeScale())
        .min(0.3)
        .sliderRange(0.5, 3)
        .visible(fuelGauge::get)
        .build()
    );

    public final Setting<Boolean> volytraAssistant = sgVisuals.add(new BoolSetting.Builder()
        .name("volytra-assistant")
        .description("A friendly assistant to explain what VolytraFly is doing while you fly")
        .defaultValue(true)
        .onChanged(enabled -> { if (enabled && Utils.canUpdate()) VolytraAssistantHud.ensureAdded(); })
        .build()
    );

    public final Setting<Boolean> futureSight = sgVisuals.add(new BoolSetting.Builder()
        .name("future-sight")
        .description("Creates targets showing where a player will be in the next second")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> futureSightIgnoreFriends = sgVisuals.add(new BoolSetting.Builder()
        .name("future-sight-ignore-friends")
        .description("Targets won't show on friends")
        .defaultValue(true)
        .visible(futureSight::get)
        .build()
    );

    public final Setting<Boolean> crtOverlay = sgVisuals.add(new BoolSetting.Builder()
        .name("CRT-overlay")
        .description("Keeps a retro CRT look on the visuals")
        .defaultValue(true)
        .visible(this::isVisualsOn)
        .build()
    );

    public final Setting<SettingColor> speedometerColor = sgVisuals.add(new ColorSetting.Builder()
        .name("primary-colour")
        .description("Main colour of the speedometer, fuel gauge, switches and assistant")
        .defaultValue(new SettingColor(255, 128, 0, 255))
        .visible(this::isVisualsOn)
        .build()
    );

    public final Setting<SettingColor> greenColor = sgVisuals.add(new ColorSetting.Builder()
        .name("secondary-colour")
        .description("The colour the speedometer fades into at the top of its range, and the fuel gauge fades into as elytra runs low")
        .defaultValue(new SettingColor(0, 255, 70, 255))
        .visible(this::isVisualsOn)
        .build()
    );

    // A rubberband within this percentage above rubberband-speed-cap-threshold counts as "at the line"
    private static final double RUBBERBAND_CAP_TOLERANCE_PERCENT = 5.0;
    private static final double RUBBERBAND_CAP_DURATION_SECONDS = 10.0;

    // After a rubberband, the ramp is rewound this many ticks' worth of steps
    private static final int SPEED_PRESERVATION_REWIND_TICKS = 15;

    // How many ticks avoidance has to be stuck against a wall before it steps up or down
    private static final int AVOIDANCE_STUCK_TICKS = 3;

    // The closest, in blocks, Highway Mode lets the hitbox get to any block
    private static final double HIGHWAY_BLOCK_CLEARANCE = 0.1;

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
    // Where the player was, and how they were told to move, on the previous glide tick - used to
    // tell whether a horizontal collision actually costs speed - see resetRampOnCollision()
    private Vec3 lastGlidePos;
    private double lastGlideIntendedSpeed;
    private int accelerationDelayTicks;
    private int bypassAccelerationDelayTicks;
    private double verticalAcceleration;
    private boolean atMaxVerticalSpeed;
    private int verticalAccelerationDelayTicks;
    private boolean mappingWaitingForChunks;
    private int rubberbandCapTicksLeft;
    private double rubberbandCapSpeed;
    // How many qualifying rubberbands, within how long of each other, it takes before the cap
    // actually kicks in - see onPacketReceive
    private static final int RUBBERBAND_CAP_TRIGGER_COUNT = 3;
    private static final long RUBBERBAND_CAP_TRIGGER_WINDOW_NANOS = 3_000_000_000L;
    // Real-time (nanoTime) timestamps of recent qualifying rubberbands, oldest first
    private final ArrayDeque<Long> rubberbandQualifyingTimestamps = new ArrayDeque<>();

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

    // Highway mode state
    // Locked world-aligned travel direction
    private Vec3 highwayAxis;
    // The height the run was armed at
    private double highwayCruiseY = Double.NaN;
    // A throwaway parrot whose BirdNavigation does the pathfinding, and the world it was made
    // for - see highwayPathMob()
    private Parrot highwayPathMob;
    private ClientLevel highwayPathMobWorld;
    // The route the navigator last found
    private final ArrayList<Vec3> highwayPathNodes = new ArrayList<>();
    private int highwayPathIndex;
    // Where that route was heading, for steering straight at when there's no route
    private Vec3 highwayGoal;
    private int highwayTicksSinceRepath;
    // The point driveHighwayMode() wants this tick's movement aimed at
    private Vec3 highwaySteerTarget;
    // The height Highway Mode is trying to hold this tick
    private double highwayTargetY = Double.NaN;
    // Stuck detection
    private Vec3 highwayLastPos;
    // Within this many blocks it counts as arrived
    private static final double HIGHWAY_CENTRE_RADIUS = 1.0;
    private int highwayStuckTicks;
    private int highwayMovingTicks;
    private int highwayEscalation;
    private static final double HIGHWAY_STUCK_MOVE_EPSILON = 0.05;
    private static final double HIGHWAY_NODE_HEIGHT = 0.2;
    // However the clearance is set, the hitbox itself is never let closer than this to a block
    private static final double HIGHWAY_HARD_MARGIN = 0.02;
    // Height errors up to this are closed with a single straight vertical step
    private static final double HIGHWAY_HEIGHT_BAND = 0.3;
    // How many route nodes ahead pickHighwayWaypoint() will consider aiming at
    private static final int HIGHWAY_MAX_LOOKAHEAD_NODES = 48;

    // Volytra Assistant state - see assistantSay(). The HUD reads these from the render thread
    private static final String[] ASSISTANT_HAPPY_ENDINGS = {"~", "^^", "<3", ":3"};
    private static final String[] ASSISTANT_SORRY_ENDINGS = {"T~T", "...", ":<"};
    // A message stays up at least this long before an ordinary one may replace it
    private static final long ASSISTANT_HAPPY_HOLD_MS = 1500;
    private static final long ASSISTANT_SORRY_HOLD_MS = 3000;
    // Counts position corrections (rubberbands) from the server
    private final AtomicInteger positionCorrections = new AtomicInteger();

    public int getPositionCorrections() {
        return positionCorrections.get();
    }

    private volatile String assistantKey;
    private volatile String assistantText = "";
    private volatile boolean assistantNegative;
    private long assistantSinceMs;
    // Last time Highway Mode or avoidance dodge was actually flying the player
    private long assistantLastActiveMs;
    private String assistantLastEnding = "";
    private final Random assistantRandom = new Random();

    // General-flight situations the assistant comments on, most pressing first
    private enum AssistantSituation {
        CHUNKS, NO_CRASH, UNLOADED_CHUNKS, RUBBERBAND_CAP, AVOIDANCE, BUILDING, LANDING, HEIGHT_LIMIT
    }
    private static final long ASSISTANT_SITUATION_LINGER_MS = 600;
    // Once a general message is up it stays at least this long, so a brief one can still be read
    private static final long ASSISTANT_GENERAL_MIN_SHOW_MS = 2500;
    private final AtomicLongArray assistantSituationSeen =
        new AtomicLongArray(AssistantSituation.values().length);

    public VolytraFly() {
        super(Categories.Movement, "volytra-fly", "Specifically designed to maximise elytrafly capabilities and speed on 6b6t");
    }

    // Visuals helpers (kept as methods, not lambdas in the field initializers above, since those
    // initializers run in declaration order and a lambda there can't forward-reference a sibling
    // Visuals field; a method body has no such restriction).

    /** True when any of the visuals is switched on (used by the settings all of them share). */
    private boolean isVisualsOn() {
        return speedometer.get() || fuelGauge.get() || modeLights.get() || futureSight.get() || volytraAssistant.get();
    }

    // The toggles above only add their HUD element when switched in-game: at startup the config
    // loads before the HUD system does. (The elements are also made sure of each time the module
    // turns on - see onActivate())

    /** Makes sure the HUD element for each visual that is switched on exists. */
    private void ensureVisualsAdded() {
        if (!Utils.canUpdate()) return;
        if (speedometer.get()) SpeedometerHud.ensureAdded();
        if (modeLights.get()) ModeLightsHud.ensureAdded();
        if (volytraAssistant.get()) VolytraAssistantHud.ensureAdded();
        if (fuelGauge.get()) ElytraFuelHud.ensureAdded();
    }

    @Override
    public void onActivate() {
        cancelPendingActions();
        mappingWaitingForChunks = false;
        Player player = mc.player;

        ensureVisualsAdded();

        resetAssistant();

        atMaxSpeed = false;
        lastJumpPressed = false;
        jumpTimer = 0;
        ticksLeft = 0;
        accelerationDelayTicks = 0;
        bypassAccelerationDelayTicks = 0;
        lastGlidePos = null;
        lastGlideIntendedSpeed = 0;

        // Carry over real horizontal speed instead of snapping back to minimum-horizontal-speed,
        // so turning VolytraFly on while already moving picks up the ramp from
        // wherever your actual speed already was rather than restarting it from the floor
        double currentHorizontalSpeed = player != null
            ? Math.hypot(player.getDeltaMovement().x, player.getDeltaMovement().z)
            : 0;
        acceleration = Math.max(startSpeed.get(), Math.min(currentHorizontalSpeed, horizontalSpeed.get()));
        if (acceleration >= horizontalSpeed.get()) atMaxSpeed = true;

        resetVerticalAcceleration();

        rubberbandCapTicksLeft = 0;
        rubberbandQualifyingTimestamps.clear();

        buildingModeEngaged = false;

        resetHighwayState();

        if (player == null) return;

        if ((chestSwap.get() == ChestSwapMode.Always || chestSwap.get() == ChestSwapMode.WaitForGround)
            && player.getItemBySlot(EquipmentSlot.CHEST).getItem() != Items.ELYTRA && isActive()) {
            swapToChestSwap();
        }
    }

    @Override
    public void onDeactivate() {
        cancelPendingActions();
        mappingWaitingForChunks = false;
        resetAssistant();

        if (autoPilot.get() || highwayMode.get()) mc.options.keyUp.setDown(false);
        releaseAvoidance();
        releaseVerticalStep();
        releaseHighwayKeys();
        resetHighwayState();
        highwayPathMob = null;
        highwayPathMobWorld = null;

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

    @SuppressWarnings("unused") // called by the event bus
    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        Player player = mc.player;
        ClientLevel world = mc.level;
        if (player == null || world == null) return;

        if (!(player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER))) return;

        autoTakeoff();
        updatePlayerAvoidance();

        if (player.isFallFlying()) {
            boolean bypass = bypassMode.get();
            if (rubberbandCapTicksLeft > 0) assistantFlag(AssistantSituation.RUBBERBAND_CAP);

            if (bypass) {
                // Preserve the real horizontal velocity instead of resetting it, then accelerate
                // it using the exact same curve and settings as the General section's
                // handleAcceleration()
                double horizontalSpeedNow = Math.hypot(event.movement.x, event.movement.z);
                if (horizontalSpeedNow > 1.0E-4) {
                    double newSpeed;

                    if (bypassAccelerationDelayTicks < accelerationDelay.get()) {
                        bypassAccelerationDelayTicks++;
                        newSpeed = horizontalSpeedNow;
                    } else {
                        newSpeed = nextSpeedFor(horizontalSpeedNow, accelerationStep.get(), accelerationPlateau.get(), horizontalSpeedCap());
                    }

                    double scale = newSpeed / horizontalSpeedNow;
                    velX = event.movement.x * scale;
                    velZ = event.movement.z * scale;
                } else {
                    // No horizontal motion yet to scale up (e.g. dropped straight into gliding) -
                    // leave it as-is rather than inventing a direction; bypass picks up once
                    // there's real horizontal velocity to build on.
                    bypassAccelerationDelayTicks = 0;
                    velX = event.movement.x;
                    velZ = event.movement.z;
                }
            } else {
                velX = 0;
                velZ = 0;
            }
            velY = event.movement.y;

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

            if (!bypass) {
                resetRampOnCollision(player);
                handleAcceleration();
                handleHorizontalSpeed();
            }
            applyHighwaySteering();
            handleVerticalAcceleration();
            handleVerticalSpeed();
            limitHighwayVerticalStep();
            applyHighwaySlopeCoupling();
            handleAntiSlam();
            handleBuildingMode();
            handleMaxHeight();
            applyHighwayClearanceGuard(player);

            // Apply Mapping to the final movement, including Bypass and Highway steering.
            handleMappingMode();

            // Chunk coordinates must floor toward negative infinity, just like block coordinates.
            int chunkX = (int) Math.floor((player.getX() + velX) / 16);
            int chunkZ = (int) Math.floor((player.getZ() + velZ) / 16);
            if (dontGoIntoUnloadedChunks.get() && !world.getChunkSource().hasChunk(chunkX, chunkZ)) {
                // Don't reset acceleration/ramp state here - this fires every tick you're
                // outrunning chunk loading, and a full zeroAcceleration() would stomp your
                // ramped/held speed even though you never actually stopped moving. Just
                // suppress this tick's horizontal movement.
                assistantFlag(AssistantSituation.UNLOADED_CHUNKS);
                ((IVec3) event.movement).meteor$set(0, velY, 0);
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
            ClipContext raycastContext = new ClipContext(player.position(), new Vec3(lookAheadPos.x(), player.getY(), lookAheadPos.z()), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
            BlockHitResult hitResult = world.clip(raycastContext);
            if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK) {
                assistantFlag(AssistantSituation.NO_CRASH);
                ((IVec3) event.movement).meteor$set(0, velY, 0);
            }
        }

        if (player.isFallFlying()) {
            lastGlidePos = player.position();
            lastGlideIntendedSpeed = Math.hypot(event.movement.x, event.movement.z);
        } else {
            lastGlidePos = null;
            lastGlideIntendedSpeed = 0;
        }

    }

    @SuppressWarnings("unused") // called by the event bus
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (rubberbandCapTicksLeft > 0) rubberbandCapTicksLeft--;

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

    @SuppressWarnings("unused") // called by the event bus
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundPlayerPositionPacket) {
            positionCorrections.incrementAndGet();
            Player player = mc.player;
            ClientLevel world = mc.level;

            // Only ever step in mid-flight. The position packets sent on joining a server, respawning or
            // changing dimension are what place you in the world in the first place, so they must go through.
            boolean inFlight = player != null && world != null && player.isFallFlying();

            // Regardless of whether this correction is preserved or reset below, check whether it
            // happened right at the speed some servers reliably rubberband you at
            if (inFlight && rubberbandSpeedCap.get()) {
                double speedBps = Math.hypot(player.getDeltaMovement().x, player.getDeltaMovement().z) * 20;
                double thresholdBps = rubberbandSpeedCapThreshold.get();
                double toleranceBps = thresholdBps * (RUBBERBAND_CAP_TOLERANCE_PERCENT / 100.0);

                if (speedBps >= thresholdBps && speedBps <= thresholdBps + toleranceBps) {
                    long now = System.nanoTime();
                    rubberbandQualifyingTimestamps.addLast(now);
                    while (!rubberbandQualifyingTimestamps.isEmpty()
                        && now - rubberbandQualifyingTimestamps.peekFirst() > RUBBERBAND_CAP_TRIGGER_WINDOW_NANOS) {
                        rubberbandQualifyingTimestamps.pollFirst();
                    }

                    if (rubberbandQualifyingTimestamps.size() >= RUBBERBAND_CAP_TRIGGER_COUNT) {
                        rubberbandCapTicksLeft = (int) Math.round(RUBBERBAND_CAP_DURATION_SECONDS * 20);
                        rubberbandCapSpeed = thresholdBps / 20;
                        // Needs a fresh run of qualifying rubberbands to trigger again.
                        rubberbandQualifyingTimestamps.clear();
                    }
                }
            }

            if (inFlight) {
                // The packet goes through, so the server's position is applied and you stay in sync with it
                int rewindTicks = SPEED_PRESERVATION_REWIND_TICKS;
                double keptSpeed = rewindSpeedFor(acceleration, accelerationStep.get(), accelerationPlateau.get(), startSpeed.get(), rewindTicks);

                if (keptSpeed < acceleration) {
                    acceleration = keptSpeed;
                    atMaxSpeed = false;
                    accelerationDelayTicks = 0;

                    verticalAcceleration = rewindSpeedFor(verticalAcceleration, verticalAccelerationStep.get(), verticalAccelerationPlateau.get(), verticalStartSpeed.get(), rewindTicks);
                    atMaxVerticalSpeed = false;
                    verticalAccelerationDelayTicks = 0;
                }

                return;
            }

            zeroAcceleration();
        }
    }

    /**
     * Warns the user that they have stopped to wait for chunks to load.
     */
    @SuppressWarnings("unused") // called by the event bus
    @EventHandler
    private void onRender2D(Render2DEvent event) {
        renderMappingWarning(event);
    }

    private void renderMappingWarning(Render2DEvent event) {
        if (!mappingMode.get() || !mappingWaitingForChunks) return;
        // The Volytra Assistant says this itself when it's showing; only fall back to plain
        // on-screen text when it isn't, so the warning is never lost.
        if (volytraAssistant.get()) return;

        String text = "VolytraFly: Waiting for chunks to load (render radius: " + mappingRenderRadius.get() + ")";
        int orange = 0xFFFFA500;

        int x = (mc.getWindow().getGuiScaledWidth() - mc.font.width(text)) / 2;
        int y = mc.getWindow().getGuiScaledHeight() / 2 + 20;

        event.graphics.text(mc.font, text, x, y, orange, true);
    }

    /**
     * Lets each mode's own keybind (set in its settings group) toggle that mode on/off
     * independently, without needing to open the module's settings screen.
     */
    @SuppressWarnings("unused") // called by the event bus
    @EventHandler
    private void onKeyEvent(KeyInputEvent event) {
        if (event.action != KeyAction.Press) return;

        if (autoPilotKeybind.get().matches(event.input)) autoPilot.set(!autoPilot.get());
        if (highwayModeKeybind.get().matches(event.input)) highwayMode.set(!highwayMode.get());
        if (mappingModeKeybind.get().matches(event.input)) mappingMode.set(!mappingMode.get());
        if (buildingModeKeybind.get().matches(event.input)) buildingMode.set(!buildingMode.get());
        if (bypassModeKeybind.get().matches(event.input)) bypassMode.set(!bypassMode.get());
        if (playerAvoidanceKeybind.get().matches(event.input)) playerAvoidance.set(!playerAvoidance.get());
        if (antiSlamKeybind.get().matches(event.input)) antiSlam.set(!antiSlam.get());
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

        // Only unarm the locked axis when the mode itself is off - not just because avoidance is
        // briefly steering instead, so a dodge doesn't cost the run its heading.
        if (!highwayMode.get()) resetHighwayState();

        // Reset every tick regardless of which branch below runs, so a target Highway Mode picked
        // on some earlier tick never lingers and silently redirects plain autopilot's or
        // avoidance's own movement once Highway Mode itself isn't the one driving. Only
        // driveHighwayMode() sets it back.
        highwaySteerTarget = null;
        highwayTargetY = Double.NaN;

        // Don't fight the avoidance system's movement - it's already driving the WASD keys
        // itself this tick, so leave them alone entirely rather than releasing or overwriting them.
        if (!avoidanceSteering) {
            if (highwayMode.get()) {
                driveHighwayMode(player);
            } else {
                releaseHighwayKeys();
                if (autoPilot.get() && player.getY() > autoPilotMinimumHeight.get()) {
                    mc.options.keyUp.setDown(true);
                    lastForwardPressed = true;
                }
            }
        } else if (highwayMode.get()) {
            assistantMarkActive();
            assistantSay("avoiding", false, false, "Wuh-oh, a player! I'm making a detour");
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

    /**
     * Drives Highway Mode for one tick. Highway Mode locks onto the nearest world highway line
     * and then flies it with the same pathfinding vanilla mobs use
     */
    private void driveHighwayMode(Player player) {
        ClientLevel world = mc.level;
        if (world == null) {
            releaseHighwayKeys();
            return;
        }

        assistantMarkActive();
        if (highwayAxis == null) armHighwayAxis(player);
        Vec3 pos = player.position();

        // Heading towards the centre, stop once there: hold at the origin at cruise height
        if (highwayDirection.get() == HighwayDirection.TowardsCentre
            && Math.hypot(pos.x, pos.z) < HIGHWAY_CENTRE_RADIUS) {
            highwayStuckTicks = 0;
            highwayMovingTicks = 0;
            highwayEscalation = 0;
            highwayLastPos = pos;

            Vec3 centre = new Vec3(0, Double.isNaN(highwayCruiseY) ? pos.y : highwayCruiseY, 0);
            assistantSay("centre", false, false, "We've reached the centre");

            highwaySteerTarget = centre;
            holdHighwayDrivingKeys();
            applyHighwayVerticalHold(player, centre.y);
            return;
        }

        // Stuck detection: barely moved at all for highway-stuck-timeout while trying to. Every
        // time that trips, the next route is planned to a goal off to the side / above first
        // (further out each time), and the count only relaxes again after a stretch of real
        // progress, so it can't loop into the same obstacle forever.
        if (highwayLastPos != null && !mappingWaitingForChunks) {
            if (pos.distanceTo(highwayLastPos) < HIGHWAY_STUCK_MOVE_EPSILON) {
                highwayStuckTicks++;
                highwayMovingTicks = 0;
            } else {
                highwayStuckTicks = 0;
                highwayMovingTicks++;
                if (highwayMovingTicks >= 60) highwayEscalation = 0;
            }
        }
        highwayLastPos = pos;
        if (highwayStuckTicks >= Math.round(highwayIntelligence.get().stuckTimeout * 20)) {
            highwayStuckTicks = 0;
            highwayEscalation = Math.min(highwayEscalation + 1, 6);
            highwayPathNodes.clear(); // forces a fresh route this tick

            if (highwayEscalation == 1) {
                assistantSay("stuck1", true, false, "We're a lil' stuck", "I'll try and get us out");
            } else if (highwayEscalation == 2) {
                assistantSay("stuck2", true, false, "We're still stuck", "Lemme try a lil' harder to get us free");
            } else {
                assistantSay("stuck3", true, false, "Aww no luck!", "I'll try to think of a more creative way out");
            }
        }

        // Ask the navigator for a new route on a fixed cadence (the player covers a lot of
        // ground per tick, so a route goes stale fast), or straight away if there's none left.
        highwayTicksSinceRepath++;
        boolean routeLeft = highwayPathNodes.size() >= 2 && highwayPathIndex < highwayPathNodes.size() - 1;
        if (!routeLeft || highwayTicksSinceRepath >= highwayIntelligence.get().repathInterval) {
            computeHighwayPath(world, pos);
        }

        Vec3 target = null;
        if (highwayPathNodes.size() >= 2) {
            advanceHighwayPathIndex(pos);
            target = pickHighwayWaypoint(player, world, pos);
        }
        if (target == null) {
            // No route (nothing reachable yet, or every candidate goal was walled off): keep
            // heading at the goal in a straight line
            target = highwayGoal != null ? highwayGoal : goalOnHighway(pos, 0, 0);

            if (highwayPathMob == null) {
                assistantSay("nopathfinder", true, false, "I'm having a lil' trouble navigating", "I'll head straight for the highway instead");
            } else {
                assistantSay("noroute", true, false, "I can't see a clear route", "We're just gonna head straight for the highway and hope for the best");
            }
        } else {
            updateHighwayAssistant(pos, target);
        }

        highwaySteerTarget = target;
        holdHighwayDrivingKeys();
        applyHighwayVerticalHold(player, target.y);
    }

    /**
     * The throwaway parrot whose navigation does the pathfinding
     */
    private Parrot highwayPathMob(ClientLevel world) {
        if (highwayPathMob == null || highwayPathMobWorld != world) {
            try {
                highwayPathMob = new Parrot(EntityType.PARROT, world);
                highwayPathMobWorld = world;
            } catch (RuntimeException e) {
                highwayPathMob = null;
                highwayPathMobWorld = null;
            }
        }
        return highwayPathMob;
    }

    /**
     * A goal point for the navigator: highway-path-range blocks (less a small margin, since
     * the navigator ignores anything at or past its follow range) towards the highway line
     */
    private Vec3 goalOnHighway(Vec3 pos, double lateralOffset, double heightOffset) {
        Vec3 axis = highwayAxis;
        Vec3 lateral = new Vec3(-axis.z, 0, axis.x);
        double lat = pos.x * lateral.x + pos.z * lateral.z;
        double along = pos.x * axis.x + pos.z * axis.z;

        double range = highwayIntelligence.get().pathRange - 2;
        double ahead = Math.abs(lat) < 2.0 ? range : Math.min(range, Math.abs(lat) + 8.0);

        // The highway lines all pass through the world origin, so a point on one is just
        // axis * distance-along (plus any deliberate sideways offset).
        // Heading towards the centre, the highway ends there: never aim past the origin.
        double reach = along + ahead;
        if (highwayDirection.get() == HighwayDirection.TowardsCentre) reach = Math.min(reach, 0);
        double gx = axis.x * reach + lateral.x * lateralOffset;
        double gz = axis.z * reach + lateral.z * lateralOffset;

        double dx = gx - pos.x, dz = gz - pos.z;
        double dist = Math.hypot(dx, dz);
        if (dist > range) {
            gx = pos.x + dx / dist * range;
            gz = pos.z + dz / dist * range;
        }

        double cruiseY = Double.isNaN(highwayCruiseY) ? pos.y : highwayCruiseY;
        ClientLevel world = mc.level;
        double gy = cruiseY + heightOffset;
        if (world != null) gy = Math.max(world.getMinY() + 2, Math.min(world.getMinY() + world.getHeight() - 3, gy));
        return new Vec3(gx, gy, gz);
    }

    /**
     * Asks the mob navigator for a route to the goal and stores it as highwayPathNodes
     */
    private void computeHighwayPath(ClientLevel world, Vec3 pos) {
        highwayTicksSinceRepath = 0;

        Parrot mob = highwayPathMob(world);
        if (mob == null) return;

        // Follow range is how far from the start the navigator will look; the goal is kept
        // inside it (see goalOnHighway()).
        AttributeInstance followRange = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (followRange != null && followRange.getBaseValue() != highwayIntelligence.get().pathRange) {
            followRange.setBaseValue(highwayIntelligence.get().pathRange);
        }

        mob.setPos(pos.x, pos.y, pos.z);
        PathNavigation navigation = mob.getNavigation();

        ArrayList<Vec3> goals = new ArrayList<>(6);
        double step = 6.0 * Math.max(1, highwayEscalation);
        double firstSide = (highwayEscalation % 2 == 1) ? 1 : -1;
        if (highwayEscalation == 0) goals.add(goalOnHighway(pos, 0, 0));
        goals.add(goalOnHighway(pos, firstSide * step, 0));
        goals.add(goalOnHighway(pos, -firstSide * step, 0));
        goals.add(goalOnHighway(pos, 0, step));
        if (highwayEscalation > 0) goals.add(goalOnHighway(pos, 0, 0));

        for (Vec3 goal : goals) {
            Path path;
            try {
                path = navigation.createPath(BlockPos.containing(goal.x, goal.y, goal.z), 1);
            } catch (RuntimeException e) {
                continue;
            }
            if (path == null || path.getNodeCount() < 2) continue;

            // A route that just ends where it started (goal sealed inside solid rock, nothing
            // closer reachable) isn't a route.
            BlockPos end = path.getNodePos(path.getNodeCount() - 1);
            Vec3 endPos = new Vec3(end.getX() + 0.5, end.getY() + HIGHWAY_NODE_HEIGHT, end.getZ() + 0.5);
            if (endPos.distanceTo(pos) < 2.0) continue;

            ArrayList<Vec3> nodes = new ArrayList<>(path.getNodeCount());
            for (int i = 0; i < path.getNodeCount(); i++) {
                BlockPos node = path.getNodePos(i);
                nodes.add(new Vec3(node.getX() + 0.5, node.getY() + HIGHWAY_NODE_HEIGHT, node.getZ() + 0.5));
            }

            // The navigator thinks any block that isn't a full cube (ender chests, slabs, stairs,
            // fences, ...) can be flown through, so its route can pass straight through cells the
            // player physically can't. Bend the route around those, or try the next goal.
            Player player = mc.player;
            if (player != null) {
                if (!repairHighwayPath(world, player, nodes)) continue;
                if (nodes.getLast().distanceTo(pos) < 2.0) continue;
            }

            highwayPathNodes.clear();
            highwayPathNodes.addAll(nodes);
            highwayPathIndex = 0;
            highwayGoal = goal;
            return;
        }
    }

    // Route repair (see repairHighwayPath()): how far around a blocked stretch of the route to look
    // for a way past it, and a cap on how many cells that search may visit.
    private static final int HIGHWAY_DETOUR_RADIUS = 3;
    private static final int HIGHWAY_DETOUR_MAX_CELLS = 4000;

    /**
     * Fixes up a route from the mob navigator so every node of it is somewhere the user's real
     * hitbox fits
     */
    private boolean repairHighwayPath(ClientLevel world, Player player, ArrayList<Vec3> nodes) {
        double loose = HIGHWAY_HARD_MARGIN;
        double strict = HIGHWAY_BLOCK_CLEARANCE - 1.0E-4; // just under the clearance, and always above loose

        int n = nodes.size();
        boolean[] free = new boolean[n];
        boolean[] bad = new boolean[n];
        boolean anyBad = false;
        free[0] = true; // wherever the player is now, they're already there
        for (int i = 1; i < n; i++) free[i] = highwayPointFree(world, player, nodes.get(i), loose);
        for (int i = 1; i < n; i++) {
            bad[i] = !free[i] || (free[i - 1] && highwayLineBlocked(world, player, nodes.get(i - 1), nodes.get(i), loose));
            anyBad |= bad[i];
        }
        if (!anyBad) return true;

        ArrayList<Vec3> out = new ArrayList<>(n + 8);
        int i = 0;
        while (i < n) {
            if (!bad[i]) {
                out.add(nodes.get(i));
                i++;
                continue;
            }

            int j = i;
            while (j < n && bad[j]) j++; // bad run is [i, j)
            if (j == n) break; // the route ends inside a block: stop short of it

            List<BlockPos> detour = highwayDetour(world, player, highwayNodeCell(out.getLast()), highwayNodeCell(nodes.get(j)), strict);
            if (detour == null) detour = highwayDetour(world, player, highwayNodeCell(out.getLast()), highwayNodeCell(nodes.get(j)), loose);
            if (detour == null) return false;

            for (BlockPos cell : detour) {
                out.add(new Vec3(cell.getX() + 0.5, cell.getY() + HIGHWAY_NODE_HEIGHT, cell.getZ() + 0.5));
            }
            i = j;
        }

        if (out.size() < 2) return false;
        nodes.clear();
        nodes.addAll(out);
        return true;
    }

    /** The route cell a node sits in (the inverse of how nodes are made from cells). */
    private static BlockPos highwayNodeCell(Vec3 node) {
        return new BlockPos((int) Math.floor(node.x), (int) Math.round(node.y - HIGHWAY_NODE_HEIGHT), (int) Math.floor(node.z));
    }

    /** Whether the hitbox, grown by margin, fits at this point without touching any block. */
    private boolean highwayPointFree(ClientLevel world, Player player, Vec3 p, double margin) {
        return highestBlockingY(world, highwayHitboxBox(player, p.x, p.y, p.z, margin), null) == Integer.MIN_VALUE;
    }

    /** Like highwaySweepClear(), but inverted: true if the hitbox, grown by margin, hits a block along the line. Nothing is ignored. */
    private boolean highwayLineBlocked(ClientLevel world, Player player, Vec3 from, Vec3 to, double margin) {
        Vec3 delta = to.subtract(from);
        int steps = Math.max(1, (int) Math.ceil(delta.length() / 0.5));
        for (int k = 1; k <= steps; k++) {
            double t = (double) k / steps;
            if (!highwayPointFree(world, player, from.add(delta.scale(t)), margin)) return true;
        }
        return false;
    }

    /**
     * Search through free cells near a blocked stretch of route
     */
    private List<BlockPos> highwayDetour(ClientLevel world, Player player, BlockPos from, BlockPos to, double margin) {
        int minX = Math.min(from.getX(), to.getX()) - HIGHWAY_DETOUR_RADIUS, maxX = Math.max(from.getX(), to.getX()) + HIGHWAY_DETOUR_RADIUS;
        int minY = Math.max(world.getMinY(), Math.min(from.getY(), to.getY()) - HIGHWAY_DETOUR_RADIUS);
        int maxY = Math.min(world.getMinY() + world.getHeight() - 1, Math.max(from.getY(), to.getY()) + HIGHWAY_DETOUR_RADIUS);
        int minZ = Math.min(from.getZ(), to.getZ()) - HIGHWAY_DETOUR_RADIUS, maxZ = Math.max(from.getZ(), to.getZ()) + HIGHWAY_DETOUR_RADIUS;

        HashMap<BlockPos, BlockPos> cameFrom = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        cameFrom.put(from, from);
        queue.add(from);

        while (!queue.isEmpty() && cameFrom.size() < HIGHWAY_DETOUR_MAX_CELLS) {
            BlockPos cur = queue.poll();
            Vec3 curPoint = new Vec3(cur.getX() + 0.5, cur.getY() + HIGHWAY_NODE_HEIGHT, cur.getZ() + 0.5);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos next = cur.offset(dx, dy, dz);
                        if (next.getX() < minX || next.getX() > maxX || next.getY() < minY || next.getY() > maxY
                            || next.getZ() < minZ || next.getZ() > maxZ || cameFrom.containsKey(next)) continue;

                        Vec3 nextPoint = new Vec3(next.getX() + 0.5, next.getY() + HIGHWAY_NODE_HEIGHT, next.getZ() + 0.5);
                        if (!highwayPointFree(world, player, nextPoint, margin)) continue;
                        if (highwayLineBlocked(world, player, curPoint, nextPoint, margin)) continue;

                        cameFrom.put(next, cur);
                        if (next.equals(to)) {
                            LinkedList<BlockPos> cells = new LinkedList<>();
                            for (BlockPos c = cameFrom.get(next); !c.equals(from); c = cameFrom.get(c)) cells.addFirst(c);
                            return cells;
                        }
                        queue.add(next);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Moves highwayPathIndex forward
     */
    private void advanceHighwayPathIndex(Vec3 pos) {
        int last = highwayPathNodes.size() - 1;
        while (highwayPathIndex < last) {
            Vec3 cur = highwayPathNodes.get(highwayPathIndex);
            Vec3 seg = highwayPathNodes.get(highwayPathIndex + 1).subtract(cur);
            double len2 = seg.lengthSqr();
            if (len2 < 1.0E-6 || pos.subtract(cur).dot(seg) / len2 >= 1.0) highwayPathIndex++;
            else break;
        }
    }

    /**
     * Picks the point to aim at
     */
    private Vec3 pickHighwayWaypoint(Player player, ClientLevel world, Vec3 pos) {
        int first = highwayPathIndex + 1;
        if (first >= highwayPathNodes.size()) return null;
        int last = Math.min(highwayPathNodes.size() - 1, highwayPathIndex + HIGHWAY_MAX_LOOKAHEAD_NODES);

        AABB startBox = highwayHitboxBox(player, pos.x, pos.y, pos.z, HIGHWAY_BLOCK_CLEARANCE);
        for (int k = last; k > first; k--) {
            Vec3 candidate = highwayPathNodes.get(k);
            if (highwayRouteClear(world, player, pos, candidate, startBox)) return candidate;
        }
        return highwayPathNodes.get(first);
    }

    /**
     * Whether flying to the candidate waypoint is clear the way it will actually be flown
     */
    private boolean highwayRouteClear(ClientLevel world, Player player, Vec3 from, Vec3 to, AABB startBox) {
        if (Math.abs(to.y - from.y) > HIGHWAY_HEIGHT_BAND) return highwaySweepClear(world, player, from, to, startBox);

        Vec3 above = new Vec3(from.x, to.y, from.z);
        return highwaySweepClear(world, player, from, above, startBox)
            && highwaySweepClear(world, player, above, to, startBox);
    }

    /**
     * Whether the player's clearance-grown hitbox can travel the straight line from one point to
     * another without touching a block it wasn't already touching at the start
     */
    private boolean highwaySweepClear(ClientLevel world, Player player, Vec3 from, Vec3 to, AABB startBox) {
        Vec3 delta = to.subtract(from);
        int steps = Math.max(1, (int) Math.ceil(delta.length() / 0.5));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            AABB box = highwayHitboxBox(player, from.x + delta.x * t, from.y + delta.y * t, from.z + delta.z * t, HIGHWAY_BLOCK_CLEARANCE);
            if (highestBlockingY(world, box, startBox) != Integer.MIN_VALUE) return false;
        }
        return true;
    }

    /**
     * Deliberately not steerTowardsDirection() here
     */
    private void holdHighwayDrivingKeys() {
        if (!isKeyPhysicallyPressed(mc.options.keyUp)) mc.options.keyUp.setDown(true);
        if (!isKeyPhysicallyPressed(mc.options.keyDown)) mc.options.keyDown.setDown(false);
        if (!isKeyPhysicallyPressed(mc.options.keyLeft)) mc.options.keyLeft.setDown(false);
        if (!isKeyPhysicallyPressed(mc.options.keyRight)) mc.options.keyRight.setDown(false);
        lastForwardPressed = mc.options.keyUp.isDown();
    }

    private void applyHighwayVerticalHold(Player player, double targetY) {
        highwayTargetY = targetY;

        double verticalError = targetY - player.getY();
        mc.options.keyJump.setDown(verticalError > HIGHWAY_HEIGHT_BAND);
        mc.options.keyShift.setDown(verticalError < -HIGHWAY_HEIGHT_BAND);
        if (Math.abs(verticalError) <= HIGHWAY_HEIGHT_BAND) {
            // Close enough that no climb or dive is needed
            velY = verticalError;
        }
    }

    /**
     * Repoints this tick's horizontal speed at highwaySteerTarget
     */
    private void applyHighwaySteering() {
        if (highwaySteerTarget == null || mc.player == null) return;

        double speed = Math.hypot(velX, velZ);
        if (speed < 1.0E-6) return;

        Vec3 pos = mc.player.position();
        double dx = highwaySteerTarget.x - pos.x;
        double dz = highwaySteerTarget.z - pos.z;
        double horizontalDistance = Math.hypot(dx, dz);
        if (horizontalDistance < 1.0E-3) {
            // Target is straight above or below - only the vertical movement is needed.
            velX = 0;
            velZ = 0;
            return;
        }

        double step = Math.min(speed, horizontalDistance);
        velX = dx / horizontalDistance * step;
        velZ = dz / horizontalDistance * step;
    }

    /**
     * Keeps the horizontal step in proportion to the vertical one
     */
    private void applyHighwaySlopeCoupling() {
        if (highwaySteerTarget == null || mc.player == null) return;

        Vec3 pos = mc.player.position();
        double dy = highwaySteerTarget.y - pos.y;
        if (Math.abs(dy) <= HIGHWAY_HEIGHT_BAND || Math.abs(velY) < 1.0E-6) return;

        double horizontalSpeed = Math.hypot(velX, velZ);
        if (horizontalSpeed < 1.0E-6) return;

        double horizontalDistance = Math.hypot(highwaySteerTarget.x - pos.x, highwaySteerTarget.z - pos.z);
        double share = Math.min(1.0, Math.abs(velY) / Math.abs(dy));
        double allowed = horizontalDistance * share;
        if (allowed < horizontalSpeed) {
            double scale = allowed / horizontalSpeed;
            velX *= scale;
            velZ *= scale;
        }
    }

    // Volytra Assistant

    /**
     * What the Volytra Assistant should be saying right now
     */
    public String getAssistantText() {
        boolean highway = highwayMode.get();
        Player player = mc.player;
        boolean flying = player != null && player.isFallFlying();
        long now = System.currentTimeMillis();

        AssistantSituation situation = flying ? currentAssistantSituation(highway) : null;
        if (situation != null) {
            // Leftover Highway Mode text
            boolean stale = !highway && assistantKey != null && !assistantKey.startsWith("g-");
            assistantSayGeneral(situation, stale);
        } else if (!highway) {
            // Nothing special going on and Highway Mode isn't speaking
            boolean holdingSituation = assistantKey != null
                && assistantKey.startsWith("g-") && !assistantKey.startsWith(ASSISTANT_BASELINE_PREFIX)
                && now - assistantSinceMs < ASSISTANT_GENERAL_MIN_SHOW_MS;
            if (!holdingSituation) {
                boolean stale = assistantKey != null && !assistantKey.startsWith("g-");
                assistantSayBaseline(flying, stale);
            }
        }

        if (highway && now - assistantLastActiveMs > 1000) {
            // Not flying the highway at the moment
            assistantSay("idle", false, true, "Hey there - ooh you've got highway mode on", "Just take off and I'll guide us along the highway");
        }

        String text = assistantText;
        return text.isEmpty() ? null : text;
    }

    private static final String ASSISTANT_BASELINE_PREFIX = "g-base-";

    /**
     * What the assistant says in general flight when nothing in particular is going on
     */
    private void assistantSayBaseline(boolean flying, boolean force) {
        if (!flying) {
            assistantSay(ASSISTANT_BASELINE_PREFIX + "ready", false, force,
                "Hey there", "Just take off and I'll keep an eye on some stuff for ya");
        } else if (autoPilot.get()) {
            assistantSay(ASSISTANT_BASELINE_PREFIX + "autopilot", false, force,
                "I'll take control for now", "Sit back and enjoy the flight");
        } else if (mappingMode.get()) {
            assistantSay(ASSISTANT_BASELINE_PREFIX + "mapping", false, force,
                "Mapping Mode is on", "I'll stop us periodically to let chunks load");
        } else if (buildingMode.get()) {
            assistantSay(ASSISTANT_BASELINE_PREFIX + "building", false, force,
                "Building Mode is on", "I'm gonna slow us down when we get near blocks");
        } else {
            assistantSay(ASSISTANT_BASELINE_PREFIX + "flying", false, force,
                "Flying smoothly");
        }
    }

    public boolean isAssistantSorry() {
        return assistantNegative;
    }

    /**
     * Marks a general situation as going on right now
     */
    private void assistantFlag(AssistantSituation situation) {
        assistantSituationSeen.set(situation.ordinal(), System.currentTimeMillis());
    }

    /**
     * The most pressing general situation still going on
     */
    private AssistantSituation currentAssistantSituation(boolean highway) {
        long now = System.currentTimeMillis();
        for (AssistantSituation situation : AssistantSituation.values()) {
            if (situation == AssistantSituation.CHUNKS && highway) continue;
            if (now - assistantSituationSeen.get(situation.ordinal()) < ASSISTANT_SITUATION_LINGER_MS) return situation;
        }
        return null;
    }

    private void assistantSayGeneral(AssistantSituation situation, boolean force) {
        String key = "g-" + situation.name();
        switch (situation) {
            case CHUNKS -> assistantSay(key + mappingRenderRadius.get(), false, force,
                "Waiting for chunks to load", "Render radius is " + mappingRenderRadius.get());
            case NO_CRASH -> assistantSay(key, false, force,
                "Uh-oh, a threat! I'll try and keep us safe");
            case UNLOADED_CHUNKS -> assistantSay(key, false, force,
                "The chunks ahead haven't loaded yet, I'll stop us until they load");
            case RUBBERBAND_CAP -> assistantSay(key, false, force,
                "The server's speed limit might have changed briefly~ I'll cap our speed for a lil' bit to match and then speed up again");
            case AVOIDANCE -> assistantSay(key, false, force,
                "A threat is pretty close, so I'll distance us a lil' bit");
            case BUILDING -> assistantSay(key, false, force,
                "Blocks detected nearby! Slowing us down");
            case LANDING -> assistantSay(key, false, force,
                "Easing our descent to avoid damage");
            case HEIGHT_LIMIT -> assistantSay(key, false, force,
                "We've reached the height limit");
        }
    }

    private void resetAssistant() {
        for (int i = 0; i < assistantSituationSeen.length(); i++) assistantSituationSeen.set(i, 0);
        assistantKey = null;
        assistantText = "";
        assistantNegative = false;
        assistantLastActiveMs = 0;
    }

    private void assistantMarkActive() {
        assistantLastActiveMs = System.currentTimeMillis();
    }

    /**
     * Sets what the assistant says
     */
    private void assistantSay(String key, boolean negative, boolean force, String... sentences) {
        if (key.equals(assistantKey)) return;

        long now = System.currentTimeMillis();
        if (!force && assistantKey != null) {
            long hold = assistantNegative ? ASSISTANT_SORRY_HOLD_MS : ASSISTANT_HAPPY_HOLD_MS;
            boolean urgent = negative && !assistantNegative;
            if (!urgent && now - assistantSinceMs < hold) return;
        }

        StringBuilder sb = new StringBuilder();
        for (String sentence : sentences) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(sentence).append(assistantEnding(negative));
        }

        assistantKey = key;
        assistantNegative = negative;
        assistantSinceMs = now;
        assistantText = sb.toString();
    }

    /**
     * A random sign-off from the cheerful or apologetic set
     */
    private String assistantEnding(boolean negative) {
        String[] pool = negative ? ASSISTANT_SORRY_ENDINGS : ASSISTANT_HAPPY_ENDINGS;
        String ending;
        int guard = 0;
        do {
            ending = pool[assistantRandom.nextInt(pool.length)];
        } while (ending.equals(assistantLastEnding) && ++guard < 10);
        assistantLastEnding = ending;

        boolean attached = ending.equals("~") || ending.equals("...");
        return attached ? ending : " " + ending;
    }

    /**
     * Compass name for a highway direction
     */
    private static String highwayHeadingName(Vec3 axis) {
        String ns = axis.z < -0.1 ? "north" : axis.z > 0.1 ? "south" : "";
        String ew = axis.x > 0.1 ? "east" : axis.x < -0.1 ? "west" : "";
        if (ns.isEmpty()) return ew;
        if (ew.isEmpty()) return ns;
        return ns + "-" + ew;
    }

    /**
     * Works out which move Highway Mode is making this tick and has the assistant say so
     */
    private void updateHighwayAssistant(Vec3 pos, Vec3 target) {
        Vec3 axis = highwayAxis;
        if (axis == null) return;

        // A general situation (see getAssistantText()) is being announced instead.
        if (currentAssistantSituation(true) != null) return;

        if (mappingWaitingForChunks) {
            assistantSay("chunks", false, false, "Waiting for chunks ahead to load", "One moment");
            return;
        }

        double dy = target.y - pos.y;
        if (dy > 1.5) {
            assistantSay("climbing", false, false, "Something's in the way", "I'll try and take us over the top");
            return;
        }
        if (dy < -1.5) {
            assistantSay("descending", false, false, "Returning us to cruising height");
            return;
        }

        Vec3 lateral = new Vec3(-axis.z, 0, axis.x);
        double lat = pos.x * lateral.x + pos.z * lateral.z;
        double targetLat = target.x * lateral.x + target.z * lateral.z;

        if (Math.abs(lat) > 3.0) {
            assistantSay("merging", false, false, "Merging onto the " + highwayHeadingName(axis) + " highway");
        } else if (Math.abs(targetLat - lat) > 2.5) {
            assistantSay("swerving", false, false, "Ah! There's an obstacle - manoeuvring around it");
        } else {
            assistantSay("cruising", false, false, "Cruising along the " + highwayHeadingName(axis) + " highway");
        }
    }

    /** The direction of whichever of the four highway lines (both axes and both diagonals) is nearest to (x, z). */
    private static Vec3 nearestHighwayDirection(double x, double z) {
        double distToXAxis = Math.abs(z);                     // line z = 0
        double distToZAxis = Math.abs(x);                     // line x = 0
        double distToDiag1 = Math.abs(z - x) / Math.sqrt(2);   // line z = x
        double distToDiag2 = Math.abs(z + x) / Math.sqrt(2);   // line z = -x

        double nearest = Math.min(Math.min(distToXAxis, distToZAxis), Math.min(distToDiag1, distToDiag2));
        if (nearest == distToXAxis) return new Vec3(1, 0, 0);
        if (nearest == distToZAxis) return new Vec3(0, 0, 1);
        if (nearest == distToDiag1) return new Vec3(1, 0, 1);
        return new Vec3(1, 0, -1);
    }

    /**
     * Picks whichever of the world highway lines through the origin the user's current position is
     * perpendicularly closest to, and locks in to travel there
     */
    private void armHighwayAxis(Player player) {
        double x = player.getX();
        double z = player.getZ();

        Vec3 dir = nearestHighwayDirection(x, z);

        double dot = x * dir.x + z * dir.z;
        boolean awayFromCentre = highwayDirection.get() == HighwayDirection.AwayFromCentre;
        double sign = (dot >= 0) == awayFromCentre ? 1 : -1;

        highwayAxis = new Vec3(dir.x * sign, 0, dir.z * sign).normalize();
        highwayCruiseY = player.getY();

        assistantSay("armed", false, true, "Locked onto the " + highwayHeadingName(highwayAxis) + " highway", "Here we go");
    }

    /**
     * Forgets everything Highway Mode is holding on to
     */
    private void resetHighwayState() {
        highwayAxis = null;
        highwayCruiseY = Double.NaN;
        highwayGoal = null;
        highwaySteerTarget = null;
        highwayTargetY = Double.NaN;
        highwayPathNodes.clear();
        highwayPathIndex = 0;
        highwayTicksSinceRepath = 0;
        highwayStuckTicks = 0;
        highwayMovingTicks = 0;
        highwayEscalation = 0;
        highwayLastPos = null;
    }

    /**
     * The Y of the highest block whose collision shape overlaps box
     */
    private int highestBlockingY(ClientLevel world, AABB box, AABB ignoreBox) {
        int top = Integer.MIN_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int maxX = (int) Math.floor(box.maxX), maxY = (int) Math.floor(box.maxY), maxZ = (int) Math.floor(box.maxZ);
        for (int bx = (int) Math.floor(box.minX); bx <= maxX; bx++) {
            for (int by = (int) Math.floor(box.minY) - 1; by <= maxY; by++) { // one lower: fences and walls stand taller than their cell
                for (int bz = (int) Math.floor(box.minZ); bz <= maxZ; bz++) {
                    pos.set(bx, by, bz);
                    var shape = world.getBlockState(pos).getCollisionShape(world, pos);
                    if (shape.isEmpty()) continue;
                    AABB blockBox = shape.bounds().move(pos);
                    if (!box.intersects(blockBox)) continue;
                    if (ignoreBox != null && ignoreBox.intersects(blockBox)) continue;
                    top = Math.max(top, by);
                }
            }
        }
        return top;
    }

    /**
     * Keeps the vertical step from overshooting the height Highway Mode is holding
     */
    private void limitHighwayVerticalStep() {
        if (Double.isNaN(highwayTargetY) || !highwayMode.get() || mc.player == null) return;

        double error = highwayTargetY - mc.player.getY();
        if (velY > 0) velY = Math.min(velY, Math.max(0, error));
        else if (velY < 0) velY = Math.max(velY, Math.min(0, error));
    }

    /**
     * Last line of defence for highway-block-clearance
     */
    private void applyHighwayClearanceGuard(Player player) {
        if (!highwayMode.get() || highwayAxis == null) return;
        ClientLevel world = mc.level;
        if (world == null) return;
        if (Math.abs(velX) + Math.abs(velY) + Math.abs(velZ) < 1.0E-6) return;

        double x = player.getX(), y = player.getY(), z = player.getZ();
        AABB start = highwayHitboxBox(player, x, y, z, HIGHWAY_BLOCK_CLEARANCE);
        AABB hardStart = highwayHitboxBox(player, x, y, z, HIGHWAY_HARD_MARGIN);
        double horizontalBefore = Math.hypot(velX, velZ);

        double allowedY = clampHighwayAxis(world, player, x, y, z, 1, velY, start, hardStart);
        y += allowedY;
        double allowedX = clampHighwayAxis(world, player, x, y, z, 0, velX, start, hardStart);
        x += allowedX;
        double allowedZ = clampHighwayAxis(world, player, x, y, z, 2, velZ, start, hardStart);

        velX = allowedX;
        velY = allowedY;
        velZ = allowedZ;

        // A hit that costs speed drops the ramp to what was kept
        double horizontalAfter = Math.hypot(velX, velZ);
        if (horizontalAfter < horizontalBefore - 1.0E-3) {
            double kept = Math.max(startSpeed.get(), horizontalAfter);
            if (kept < acceleration) acceleration = kept;
            atMaxSpeed = acceleration >= horizontalSpeed.get();
            accelerationDelayTicks = 0;
        }
    }

    /**
     * How far along one axis the player can move from before the clearance-grown hitbox reaches a new block
     */
    private double clampHighwayAxis(ClientLevel world, Player player, double x, double y, double z, int axis, double delta, AABB start, AABB hardStart) {
        if (Math.abs(delta) < 1.0E-9) return delta;

        int steps = Math.max(1, (int) Math.ceil(Math.abs(delta) / 0.25));
        for (int i = 1; i <= steps; i++) {
            if (!highwayAxisMoveSafe(world, player, x, y, z, axis, delta * i / steps, start, hardStart)) {
                double lo = (double) (i - 1) / steps, hi = (double) i / steps;
                for (int k = 0; k < 6; k++) {
                    double mid = (lo + hi) / 2;
                    if (highwayAxisMoveSafe(world, player, x, y, z, axis, delta * mid, start, hardStart)) lo = mid;
                    else hi = mid;
                }
                return delta * lo;
            }
        }
        return delta;
    }

    private boolean highwayAxisMoveSafe(ClientLevel world, Player player, double x, double y, double z, int axis, double move, AABB start, AABB hardStart) {
        double nx = x + (axis == 0 ? move : 0), ny = y + (axis == 1 ? move : 0), nz = z + (axis == 2 ? move : 0);
        if (highestBlockingY(world, highwayHitboxBox(player, nx, ny, nz, HIGHWAY_BLOCK_CLEARANCE), start) != Integer.MIN_VALUE) return false;

        // Blocks the user is already inside the clearance of are skipped above so they can still move along them
        return highestBlockingY(world, highwayHitboxBox(player, nx, ny, nz, HIGHWAY_HARD_MARGIN), hardStart) == Integer.MIN_VALUE;
    }

    /**
     * The player's hitbox with its feet at (x, y, z), grown on every side by margin. Grown by
     * HIGHWAY_BLOCK_CLEARANCE, anything it touches is closer to the user than the clearance.
     */
    private AABB highwayHitboxBox(Player player, double x, double y, double z, double margin) {
        AABB base = player.getBoundingBox();
        double halfWidth = (base.maxX - base.minX) / 2;
        double halfDepth = (base.maxZ - base.minZ) / 2;
        double height = base.maxY - base.minY;
        return new AABB(x - halfWidth, y, z - halfDepth, x + halfWidth, y + height, z + halfDepth).inflate(margin);
    }

    /**
     * Releases the keys Highway Mode drives (WASD plus jump/sneak), skipping any the user is
     * holding down physically, mirroring releaseHorizontalKeys()/releaseVerticalStep().
     */
    private void releaseHighwayKeys() {
        releaseHorizontalKeys();
        if (!isKeyPhysicallyPressed(mc.options.keyJump)) mc.options.keyJump.setDown(false);
        if (!isKeyPhysicallyPressed(mc.options.keyShift)) mc.options.keyShift.setDown(false);
    }

    private void handleHorizontalSpeed() {
        boolean a = false;
        boolean b = false;

        if (mc.options.keyUp.isDown()) {
            velX += forward.x * acceleration * 10;
            velZ += forward.z * acceleration * 10;
            a = true;
        } else if (mc.options.keyDown.isDown()) {
            velX -= forward.x * acceleration * 10;
            velZ -= forward.z * acceleration * 10;
            a = true;
        }

        if (mc.options.keyRight.isDown()) {
            velX += right.x * acceleration * 10;
            velZ += right.z * acceleration * 10;
            b = true;
        } else if (mc.options.keyLeft.isDown()) {
            velX -= right.x * acceleration * 10;
            velZ -= right.z * acceleration * 10;
            b = true;
        }

        if (a && b) {
            double diagonal = 1 / Math.sqrt(2);
            velX *= diagonal;
            velZ *= diagonal;
        }
    }

    /** Holds horizontal movement until nearby chunks load, independently of the flight mode. */
    private void handleMappingMode() {
        mappingWaitingForChunks = mappingMode.get() && !areChunksLoadedInRadius(mappingRenderRadius.get());
        if (mappingWaitingForChunks) {
            assistantFlag(AssistantSituation.CHUNKS);
            velX = 0;
            velZ = 0;
            // Resume the normal ramp from its floor; also clear Bypass's acceleration delay.
            resetHorizontalAcceleration();
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
        if (mc.options.keyJump.isDown()) velY += 0.5 * verticalAcceleration;
        else if (mc.options.keyShift.isDown()) velY -= 0.5 * verticalAcceleration;
    }

    private void handleFallMultiplier() {
        if (velY < 0) velY *= fallMultiplier.get();
        else if (velY > 0) velY = 0;
    }

    /**
     * Slows the user's speed when landing.
     * Even if a corner is about to catch the user, the user will be slowed down.
     */
    private void handleAntiSlam() {
        if (!antiSlam.get() || velY >= 0) return;

        double currentSpeed = -velY;
        double minSpeed = antiSlamMinSpeed.get();
        if (currentSpeed <= minSpeed) return; // already gentler than the floor - nothing to do

        double startDist = antiSlamDistance.get();
        double minDist = Math.min(antiSlamMinDistance.get(), startDist);

        double distanceToGround = distanceToGroundBelow(startDist);
        if (distanceToGround >= startDist) return; // nothing close enough to start slowing, anywhere under the hitbox

        double allowedSpeed;
        if (distanceToGround <= minDist) {
            allowedSpeed = minSpeed;
        } else {
            double t = (distanceToGround - minDist) / Math.max(startDist - minDist, 0.0001);
            allowedSpeed = minSpeed + t * (currentSpeed - minSpeed);
        }

        if (allowedSpeed < currentSpeed) {
            velY = -allowedSpeed;
            assistantFlag(AssistantSituation.LANDING);
        }
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

                if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) continue;

                double distance = y - hitResult.getLocation().y;
                if (distance < nearest) nearest = distance;
            }
        }

        return nearest;
    }

    /**
     * Building mode eases you into its speed.
     * The moment a block comes within building-mode-distance, the cap starts at your current
     * speed and eases toward building-mode-min-speed.
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

        assistantFlag(AssistantSituation.BUILDING);

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
            assistantFlag(AssistantSituation.HEIGHT_LIMIT);
        } else if (currentY + velY > limit) {
            velY = limit - currentY;
            assistantFlag(AssistantSituation.HEIGHT_LIMIT);
        }
    }

    private boolean isMovementKeyPressed() {
        return mc.options.keyUp.isDown() || mc.options.keyDown.isDown()
            || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown();
    }

    /**
     * If last tick's move ran into a block and clearly cost horizontal speed, pulls the ramp back
     * down to the speed the player really kept (floored at minimum-horizontal-speed) and restarts
     * the acceleration delay.
     */
    private void resetRampOnCollision(Player player) {
        if (lastGlidePos == null) return;
        if (!player.horizontalCollision) return;
        if (lastGlideIntendedSpeed < 1.0E-3) return;

        Vec3 now = player.position();
        double moved = Math.hypot(now.x - lastGlidePos.x, now.z - lastGlidePos.z);
        // Any real loss of speed counts (head-on or glancing) - only float noise is ignored.
        if (moved >= lastGlideIntendedSpeed - 1.0E-3) return;

        double kept = Math.max(startSpeed.get(), moved);
        if (kept < acceleration) acceleration = kept;
        atMaxSpeed = acceleration >= horizontalSpeed.get();
        accelerationDelayTicks = 0;
    }

    private void handleAcceleration() {
        if (!isMovementKeyPressed()) {
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
        // acceleration approaches the plateau (acceleration-plateau setting). The actual top
        // speed is still horizontal-speed - the curve just shapes how you get there. While a
        // rubberband speed cap is in effect, the top speed is temporarily pulled down further,
        // and atMaxSpeed is deliberately not latched, so acceleration resumes towards the real
        // horizontal-speed on its own once the cap expires.
        double cap = horizontalSpeedCap();
        acceleration = nextSpeedFor(acceleration, accelerationStep.get(), accelerationPlateau.get(), cap);

        // Reached the configured top speed - lock in and stop accelerating. Never latches while
        // only the temporary rubberband cap (not the real horizontal-speed) has been reached.
        if (acceleration >= horizontalSpeed.get()) {
            atMaxSpeed = true;
        }
    }

    /**
     * The plateau-curve gain for a single tick at currentSpeed: steep at low speed, tapering to
     * zero as currentSpeed approaches plateau. Shared by nextSpeedFor() (to advance the ramp
     * forward) and by Speed Preservation's rewind (to pull it back by the same amount it would
     * have gained going forward), so every place acceleration is touched uses one curve shape.
     */
    private static double curveGain(double currentSpeed, double step, double plateau) {
        if (plateau <= 0) return 0;
        double remainingToPlateau = Math.max(0, plateau - currentSpeed);
        return step * (remainingToPlateau / plateau);
    }

    /**
     * Advances currentSpeed by one tick along the plateau curve (step/plateau), capped at cap.
     * The single formula behind handleAcceleration(), handleVerticalAcceleration() and the
     * bypass block in onPlayerMove() - all three used to duplicate this curve independently.
     */
    private static double nextSpeedFor(double currentSpeed, double step, double plateau, double cap) {
        return Math.min(currentSpeed + curveGain(currentSpeed, step, plateau), cap);
    }

    /**
     * Rewinds currentSpeed back along the plateau curve by rewind-ticks worth of steps.
     * Each tick's loss is recomputed from the speed reached so far, mirroring how the
     * curve would have been climbed forward tick by tick. Used by Speed Preservation's rewind.
     */
    private static double rewindSpeedFor(double currentSpeed, double step, double plateau, double floor, int ticks) {
        double speed = currentSpeed;
        for (int i = 0; i < ticks && speed > floor; i++) {
            speed = Math.max(floor, speed - curveGain(speed, step, plateau));
        }
        return speed;
    }

    private void zeroAcceleration() {
        resetHorizontalAcceleration();
        resetVerticalAcceleration();
    }

    /**
     * Resets the vertical acceleration ramp back to its starting point.
     */
    private void resetVerticalAcceleration() {
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
        bypassAccelerationDelayTicks = 0;
    }

    /**
     * The horizontal speed (blocks per tick) to accelerate towards right now: normally
     * maximum-horizontal-speed, but temporarily pulled down to the rubberband-speed-cap-threshold
     * while a rubberband speed cap is in effect.
     */
    private double horizontalSpeedCap() {
        double cap = horizontalSpeed.get();
        if (rubberbandCapTicksLeft > 0) cap = Math.min(cap, rubberbandCapSpeed);
        return cap;
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
            resetVerticalAcceleration();
            return;
        }

        if (movingUp && !accelerateUpward.get()) {
            // Keep resetting to the start of the curve so a subsequent downward press (or an upward one,
            // if accelerate-upward gets turned on later) starts fresh from vertical-start-speed.
            resetVerticalAcceleration();
            return;
        }

        if (atMaxVerticalSpeed) return; // Already at vertical-speed, nothing left to do.

        if (verticalAccelerationDelayTicks < accelerationDelay.get()) {
            // Still in the delay window - hold at start-speed, don't ramp yet.
            verticalAccelerationDelayTicks++;
            return;
        }

        // Same plateau curve as horizontal, via the shared nextSpeedFor() - gain per tick is
        // steep at low speed and tapers toward zero as acceleration approaches its own plateau.
        verticalAcceleration = nextSpeedFor(verticalAcceleration, verticalAccelerationStep.get(), verticalAccelerationPlateau.get(), verticalSpeed.get());

        // Reached the configured top speed - lock in and stop accelerating.
        if (verticalAcceleration >= verticalSpeed.get()) {
            atMaxVerticalSpeed = true;
        }
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
            releaseAvoidance();
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
            assistantFlag(AssistantSituation.AVOIDANCE);
            stepVerticalStep();
            return;
        }

        // Don't touch yaw - instead figure out which WASD combo, relative to where the
        // player is currently looking, would carry them in the avoidance direction.
        steerTowardsDirection(away);
        avoidanceSteering = true;
        assistantFlag(AssistantSituation.AVOIDANCE);

        updateAvoidanceStuckDetection();
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
     * Whether block avoidance's trigger condition is met: a player is within the avoidance radius,
     * or a wither skull is within the wither skull radius. Uses a plain distance check, so a player
     * standing directly above or below (not in any horizontal direction) still counts as nearby.
     */
    private boolean isBlockAvoidanceTriggered() {
        Player self = mc.player;
        ClientLevel world = mc.level;
        if (self == null || world == null) return false;

        Vec3 selfPos = self.position();

        double radius = avoidanceRadius.get();
        if (radius > 0) {
            double radiusSq = radius * radius;
            for (Player player : world.players()) {
                if (player == self) continue;
                if (avoidanceIgnoreFriends.get() && Friends.get().isFriend(player)) continue;
                if (selfPos.distanceToSqr(player.position()) < radiusSq) return true;
            }
        }

        double skullRadius = witherSkullRadius.get();
        if (avoidWitherSkulls.get() && skullRadius > 0) {
            double skullRadiusSq = skullRadius * skullRadius;
            for (Entity entity : world.entitiesForRendering()) {
                if (entity instanceof WitherSkull && selfPos.distanceToSqr(entity.position()) < skullRadiusSq) return true;
            }
        }

        return false;
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
        if (avoidanceStuckTicksCount < AVOIDANCE_STUCK_TICKS) return;

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

    /**
     * Stops avoidance steering. Any of the four movement keys the user is holding down are left
     * alone, so a manual override doesn't stomp their real input.
     */
    private void releaseAvoidance() {
        avoidanceLateralDir = null;

        if (!avoidanceSteering) return;
        releaseHorizontalKeys();
        avoidanceSteering = false;
    }

    private boolean isMovementKeyPhysicallyPressed() {
        return isKeyPhysicallyPressed(mc.options.keyUp)
            || isKeyPhysicallyPressed(mc.options.keyDown)
            || isKeyPhysicallyPressed(mc.options.keyLeft)
            || isKeyPhysicallyPressed(mc.options.keyRight);
    }

    /**
     * Checks the actual hardware key state rather than KeyBinding.isPressed().
     */
    private boolean isKeyPhysicallyPressed(KeyMapping binding) {
        if (mc.getWindow() == null) return binding.isDown();

        InputConstants.Key key = InputConstants.getKey(binding.saveString());
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

    /** Deferred landing/drop actions belong only to the session that scheduled them. */
    private void cancelPendingActions() {
        disableGroundListener();
        disableInstaDropListener();
    }

    //Ground
    private ClientLevel groundListenerWorld;
    private class StaticGroundListener {
        @SuppressWarnings("unused") // called by the event bus
        @EventHandler
        private void chestSwapGroundListener(PlayerMoveEvent event) {
            Player player = mc.player;
            if (isActive() || player == null || mc.level != groundListenerWorld) {
                disableGroundListener();
                return;
            }
            if (!player.onGround()) return;

            // Landing completes the action even if the player has already changed their chest item.
            disableGroundListener();
            if (player.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA) {
                swapToChestSwap();
            }
        }

        @EventHandler
        private void onGameLeft(GameLeftEvent event) {
            disableGroundListener();
        }
    }

    private final StaticGroundListener staticGroundListener = new StaticGroundListener();

    protected void enableGroundListener() {
        disableGroundListener();
        if (mc.player == null || mc.level == null) return;
        groundListenerWorld = mc.level;
        MeteorClient.EVENT_BUS.subscribe(staticGroundListener);
    }

    protected void disableGroundListener() {
        MeteorClient.EVENT_BUS.unsubscribe(staticGroundListener);
        groundListenerWorld = null;
    }

    //Drop
    private ClientLevel instaDropListenerWorld;
    private class StaticInstaDropListener {
        @SuppressWarnings("unused") // called by the event bus
        @EventHandler
        private void onInstadropTick(TickEvent.Post event) {
            LocalPlayer player = mc.player;
            if (isActive() || player == null || mc.level != instaDropListenerWorld) {
                disableInstaDropListener();
                return;
            }
            if (player.isFallFlying()) {
                player.setDeltaMovement(0, 0, 0);
                player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(true, player.horizontalCollision));
            } else {
                disableInstaDropListener();
            }
        }

        @EventHandler
        private void onGameLeft(GameLeftEvent event) {
            disableInstaDropListener();
        }
    }

    private final StaticInstaDropListener staticInstadropListener = new StaticInstaDropListener();

    protected void enableInstaDropListener() {
        disableInstaDropListener();
        if (mc.player == null || mc.level == null) return;
        instaDropListenerWorld = mc.level;
        MeteorClient.EVENT_BUS.subscribe(staticInstadropListener);
    }

    protected void disableInstaDropListener() {
        MeteorClient.EVENT_BUS.unsubscribe(staticInstadropListener);
        instaDropListenerWorld = null;
    }

    public enum ChestSwapMode {
        Always,
        Never,
        WaitForGround
    }

    public enum Intelligence {
        Low(8, 20, 5.0),
        Medium(64, 8, 3.0),
        High(256, 1, 1.0);

        final int pathRange;
        final int repathInterval;
        final double stuckTimeout;

        Intelligence(int pathRange, int repathInterval, double stuckTimeout) {
            this.pathRange = pathRange;
            this.repathInterval = repathInterval;
            this.stuckTimeout = stuckTimeout;
        }
    }

    public enum HighwayDirection {
        AwayFromCentre,
        TowardsCentre
    }

}
