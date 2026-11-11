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
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.ChestSwap;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import com.volytrafly.hud.VolytraAssistantHud;
import com.volytrafly.hud.ElytraFuelHud;
import com.volytrafly.hud.HudLayout;
import com.volytrafly.hud.ModeLightsHud;
import com.volytrafly.hud.SpeedometerHud;
import com.volytrafly.autonav.AutonavPlanner;
import com.volytrafly.autonav.ButtonSetting;
import com.volytrafly.autonav.XaeroMapClicks;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.tags.FluidTags;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

public class VolytraFly extends Module {
    private final SettingGroup sgAutopilot = settings.createGroup("Autopilot");
    private final SettingGroup sgAutohighway = settings.createGroup("Autohighway");
    private final SettingGroup sgAutonav = settings.createGroup("Autonav");
    private final SettingGroup sgOverdrive = settings.createGroup("Overdrive");
    private final SettingGroup sgMapping = settings.createGroup("Mapping");
    private final SettingGroup sgBuildingMode = settings.createGroup("Building Mode");
    private final SettingGroup sgBypassMode = settings.createGroup("Bypass Mode");
    private final SettingGroup sgPlayerAvoidance = settings.createGroup("Player Avoidance System");
    private final SettingGroup sgLanding = settings.createGroup("Landing");
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgInventory = settings.createGroup("Inventory");
    private final SettingGroup sgVisuals = settings.createGroup("Visuals");
    private final SettingGroup sgKeybinds = settings.createGroup("Keybinds");

    // Mapping

    public final Setting<Boolean> mappingMode = sgMapping.add(new BoolSetting.Builder()
        .name("mapping-mode")
        .description("Pauses the player's horizontal movement until nearby chunks are loaded")
        .defaultValue(false)
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

    // Player Avoidance System

    public final Setting<Boolean> playerAvoidance = sgPlayerAvoidance.add(new BoolSetting.Builder()
        .name("player-avoidance")
        .description("Distances you from threats when they get close")
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

    public final Setting<Set<EntityType<?>>> otherAvoidedEntities = sgPlayerAvoidance.add(new EntityTypeListSetting.Builder()
        .name("other-entities")
        .description("Extra entities to avoid")
        .filter(type -> !isAvoidanceBuiltInEntity(type))
        .visible(playerAvoidance::get)
        .build()
    );

    public final Setting<Double> otherEntitiesRadius = sgPlayerAvoidance.add(new DoubleSetting.Builder()
        .name("other-entities-radius")
        .description("Distance at which the extra entities trigger avoidance")
        .defaultValue(10.0)
        .min(0)
        .sliderMax(50)
        .visible(() -> playerAvoidance.get() && !otherAvoidedEntities.get().isEmpty())
        .build()
    );

    public final Setting<Boolean> avoidBlocks = sgPlayerAvoidance.add(new BoolSetting.Builder()
        .name("avoid-blocks")
        .description("Also avoids nearby blocks while moving away from threats")
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
        .description("Tries to move sideways to an incoming threat rather than just away")
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
        .description("The speed you start at when moving horizontally (before acceleration starts)")
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
        .description("The speed you start at when moving vertically, (before acceleration starts)")
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

    public final Setting<Boolean> autohighway = sgAutohighway.add(new BoolSetting.Builder()
        .name("autohighway")
        .description("Uses a mixture of mob pathfinding and custom navigation to find highways and travel along them")
        .defaultValue(false)
        .build()
    );

    public final Setting<HighwayDirection> highwayDirection = sgAutohighway.add(new EnumSetting.Builder<HighwayDirection>()
        .name("highway-direction")
        .description("Which way to fly along the highway, relative to the world centre (0, 0)")
        .defaultValue(HighwayDirection.AwayFromCentre)
        .visible(autohighway::get)
        .build()
    );

    public final Setting<Intelligence> highwayIntelligence = sgAutohighway.add(new EnumSetting.Builder<Intelligence>()
        .name("intelligence")
        .description("How smart Autohighway is when finding paths. Higher intelligence will use more CPU power.")
        .defaultValue(Intelligence.High)
        .visible(autohighway::get)
        .build()
    );

    // Overdrive

    public final Setting<Boolean> overdrive = sgOverdrive.add(new BoolSetting.Builder()
        .name("overdrive")
        .description("Applies dark magic to force near-instant acceleration to max speed")
        .defaultValue(false)
        .build()
    );

    // Autonav

    public final Setting<Boolean> autonav = sgAutonav.add(new BoolSetting.Builder()
        .name("autonav")
        .description("Input coords or click on a position in Xaero's world map and autonav will take you there!")
        .defaultValue(false)
        .build()
    );

    public final Setting<Integer> autonavGotoX = sgAutonav.add(new IntSetting.Builder()
        .name("x-coords")
        .description("X coord to fly to")
        .defaultValue(0)
        .min(-30000000)
        .max(30000000)
        .noSlider()
        .visible(autonav::get)
        .build()
    );

    public final Setting<Integer> autonavGotoZ = sgAutonav.add(new IntSetting.Builder()
        .name("z-coords")
        .description("Z coord to fly to")
        .defaultValue(0)
        .min(-30000000)
        .max(30000000)
        .noSlider()
        .visible(autonav::get)
        .build()
    );

    public final ButtonSetting autonavGo = sgAutonav.add(new ButtonSetting(
        "go", "Start flying to the coords input above", "Go", autonav::get));

    public final Setting<MapClickButton> autonavButton = sgAutonav.add(new EnumSetting.Builder<MapClickButton>()
        .name("click-button")
        .description("Which button to use when selecting coords on Xaero's world map")
        .defaultValue(MapClickButton.Middle)
        .visible(autonav::get)
        .build()
    );

    public final Setting<Boolean> autonavCloseMap = sgAutonav.add(new BoolSetting.Builder()
        .name("close-map-on-click")
        .description("Closes the map once you've selected a location")
        .defaultValue(true)
        .visible(autonav::get)
        .build()
    );

    public final Setting<Intelligence> autonavIntelligence = sgAutonav.add(new EnumSetting.Builder<Intelligence>()
        .name("intelligence")
        .description("How well autonav plots its route around things. Higher intelligence uses more CPU power")
        .defaultValue(Intelligence.High)
        .visible(autonav::get)
        .build()
    );

    public final Setting<Boolean> autonavAvoidLava = sgAutonav.add(new BoolSetting.Builder()
        .name("avoid-lava")
        .description("Autonav will try to plot its way around lava")
        .defaultValue(true)
        .visible(autonav::get)
        .build()
    );

    public final Setting<Boolean> autonavAvoidWater = sgAutonav.add(new BoolSetting.Builder()
        .name("avoid-water")
        .description("Autonav will try to plot its way around water")
        .defaultValue(false)
        .visible(autonav::get)
        .build()
    );

    public final Setting<Double> autonavArrivalRadius = sgAutonav.add(new DoubleSetting.Builder()
        .name("arrival-radius")
        .description("How far from the coords is considered an acceptable point to end the route")
        .defaultValue(30.0)
        .min(1.0)
        .sliderMax(50.0)
        .visible(autonav::get)
        .build()
    );

    public final Setting<Boolean> autonavPrioritiseHighways = sgAutonav.add(new BoolSetting.Builder()
        .name("prioritise-highways")
        .description("Autonav will identify highways that can get you to your location quicker")
        .defaultValue(false)
        .visible(autonav::get)
        .build()
    );

    // Visuals

    public final Setting<Boolean> autoArrangeHud = sgVisuals.add(new BoolSetting.Builder()
        .name("auto-arrange-HUD")
        .description("Arranges the HUD on the screen for you")
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
        .description("Shows a panel with lights for each toggleable mode")
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
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> futureSightIgnoreFriends = sgVisuals.add(new BoolSetting.Builder()
        .name("future-sight-ignore-friends")
        .description("Targets won't show on friends")
        .defaultValue(true)
        .visible(futureSight::get)
        .build()
    );

    public final Setting<Boolean> autonavRoute = sgVisuals.add(new BoolSetting.Builder()
        .name("autonav-route")
        .description("Shows autonav's plotted route")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> crtOverlay = sgVisuals.add(new BoolSetting.Builder()
        .name("CRT-overlay")
        .description("Keeps a retro CRT look on the visuals")
        .defaultValue(false)
        .visible(this::isVisualsOn)
        .build()
    );

    public final Setting<SettingColor> speedometerColor = sgVisuals.add(new ColorSetting.Builder()
        .name("primary-colour")
        .description("Main colour of VolytraFly's visuals")
        .defaultValue(new SettingColor(255, 128, 0, 255))
        .visible(this::isVisualsOn)
        .build()
    );

    public final Setting<SettingColor> greenColor = sgVisuals.add(new ColorSetting.Builder()
        .name("secondary-colour")
        .description("Secondary colour of VolytraFly's visuals")
        .defaultValue(new SettingColor(0, 255, 70, 255))
        .visible(this::isVisualsOn)
        .build()
    );

    // Keybinds

    public final Setting<Keybind> mappingModeKeybind = sgKeybinds.add(new KeybindSetting.Builder()
        .name("mapping-mode-keybind")
        .description("Key to toggle Mapping Mode")
        .defaultValue(Keybind.none())
        .build()
    );

    public final Setting<Keybind> buildingModeKeybind = sgKeybinds.add(new KeybindSetting.Builder()
        .name("building-mode-keybind")
        .description("Key to toggle Building Mode")
        .defaultValue(Keybind.none())
        .build()
    );

    public final Setting<Keybind> bypassModeKeybind = sgKeybinds.add(new KeybindSetting.Builder()
        .name("bypass-mode-keybind")
        .description("Key to toggle Bypass Mode")
        .defaultValue(Keybind.none())
        .build()
    );

    public final Setting<Keybind> playerAvoidanceKeybind = sgKeybinds.add(new KeybindSetting.Builder()
        .name("player-avoidance-keybind")
        .description("Key to toggle Player Avoidance.")
        .defaultValue(Keybind.none())
        .build()
    );

    public final Setting<Keybind> antiSlamKeybind = sgKeybinds.add(new KeybindSetting.Builder()
        .name("anti-slam-keybind")
        .description("Key to toggle anti-slam")
        .defaultValue(Keybind.none())
        .build()
    );

    public final Setting<Keybind> autoPilotKeybind = sgKeybinds.add(new KeybindSetting.Builder()
        .name("auto-pilot-keybind")
        .description("Key to toggle Autopilot.")
        .defaultValue(Keybind.none())
        .build()
    );

    public final Setting<Keybind> autohighwayKeybind = sgKeybinds.add(new KeybindSetting.Builder()
        .name("autohighway-keybind")
        .description("Key to toggle Autohighway")
        .defaultValue(Keybind.none())
        .build()
    );

    public final Setting<Keybind> overdriveKeybind = sgKeybinds.add(new KeybindSetting.Builder()
        .name("overdrive-keybind")
        .description("Key to toggle Overdrive")
        .defaultValue(Keybind.none())
        .build()
    );

    public final Setting<Keybind> autonavKeybind = sgKeybinds.add(new KeybindSetting.Builder()
        .name("autonav-keybind")
        .description("Key to toggle Autonav")
        .defaultValue(Keybind.none())
        .build()
    );

    public final Setting<Keybind> autonavCancelKeybind = sgKeybinds.add(new KeybindSetting.Builder()
        .name("cancel-keybind")
        .description("Key to cancel autonav")
        .defaultValue(Keybind.none())
        .visible(autonav::get)
        .build()
    );

    // A rubberband within this percentage above rubberband-speed-cap-threshold counts as "at the line"
    private static final double RUBBERBAND_CAP_TOLERANCE_PERCENT = 5.0;
    private static final double RUBBERBAND_CAP_DURATION_SECONDS = 10.0;

    // After a rubberband, how many ticks' worth of acceleration to take off the speed (no longer a user setting)
    private static final int RUBBERBAND_REWIND_TICKS = 15;

    // Ticks to hold the current speed before accelerating again (no longer a user setting). Applied
    // on take-off, after speed-costing hits and each time VolytraFly is switched back on - see onActivate()
    private static final int ACCELERATION_DELAY_TICKS = 1;

    // How many ticks avoidance has to be stuck against a wall before it steps up or down
    private static final int AVOIDANCE_STUCK_TICKS = 3;

    // The closest Autohighway lets the hitbox get to any block
    private static final double HIGHWAY_BLOCK_CLEARANCE = 0.1;

    // The hitbox as the server sees it (a hair smaller, so resting flush against a block isn't a hit).
    // A block the player is merely close to is waived from the clearance checks, so it can be slid
    // along, but never from a check against the real hitbox: otherwise a wall one block thick, once
    // the player is up against it, looks like open air to every check and Autonav flies into it
    private static final double HIGHWAY_TRUE_MARGIN = -0.001;

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
    // Where the player was and how they move on the previous glide tick - used to
    // tell whether a horizontal collision actually costs speed - see resetRampOnCollision()
    private Vec3 lastGlidePos;
    private double lastGlideIntendedSpeed;
    // Horizontal distance this module has actually told the player to move, summed over the
    // current game tick (so overdrive extra ticks are included) and the finished total
    // of the last game tick for the speedometer to fall back on during a rubberband
    private double intendedDistanceAccum;
    private volatile double intendedDistanceLastTick;
    private int accelerationDelayTicks;
    private int bypassAccelerationDelayTicks;
    private double verticalAcceleration;
    private boolean atMaxVerticalSpeed;
    private int verticalAccelerationDelayTicks;
    private boolean mappingWaitingForChunks;
    // The ramp counts as topped out at this fraction of maximum-horizontal-speed (the plateau curve only
    // ever creeps towards the top speed, so waiting for an exact match would never finish)
    // Overdrive runs this many flight ticks per game tick
    private static final double OVERDRIVE_MULTIPLIER = 50.0;
    private static final double OVERDRIVE_OFF_FRACTION = 0.995;
    // Once the Overdrive has used up its extra ticks it stays off until speed is back at or below this
    private static final double OVERDRIVE_REARM_SPEED = 2.999;
    // True once the ramp has topped out; cleared when speed is back at or below OVERDRIVE_REARM_SPEED
    private boolean overdriveSpent;

    // True only while the Overdrive is running one of its extra player ticks
    private boolean overdriveExtraTick;

    // True while the player's hitbox is inside a cobweb; Bypass Mode is forced on for as long as it is
    private boolean inCobweb;

    // Elytra Replace: whether the player was gliding on the previous game tick, so an elytra that
    // broke (and took the glide with it) is still recognised as a mid-flight swap
    private boolean glidingLastTick;
    // Game ticks left in which a freshly swapped-in elytra still has to be re-opened mid-air
    private int reglideTicksLeft;
    // How long to keep trying to re-open the new elytra after a mid-flight swap
    private static final int REGLIDE_WINDOW_TICKS = 10;

    // Autonav / Autohighway take-off after an accidental landing
    // Autohighway was flying and hasn't been deliberately landed since, so touching down is an accident
    private boolean highwayRelaunchArmed;
    private int relaunchCooldown;
    private int relaunchPause;
    private int relaunchAttempts;
    private int relaunchGlidingTicks;
    // Rubberband-loop breaker
    private int unstickTicksLeft;
    private Vec3 unstickAnchor;
    private int unstickWindowTicks;
    private static final double UNSTICK_MIN_SPEED = 1.0;
    private static final double UNSTICK_RADIUS = 16.0;      // blocks: not getting further than this from one spot...
    private static final int UNSTICK_WINDOW_TICKS = 60;     // ...for this long
    private static final int UNSTICK_SLOW_TICKS = 80;       // is a loop: stay slow this long

    // With max-height off: how far above its starting height a route may go once Autonav is already at or above the build limit
    private static final int MAP_ABOVE_LIMIT_ROOM = 32;

    private static final int RELAUNCH_RETRY_TICKS = 2;
    private static final int RELAUNCH_MAX_ATTEMPTS = 15;
    private static final int RELAUNCH_PAUSE_TICKS = 60;
    private static final int RELAUNCH_STABLE_TICKS = 20;

    // Leftover fraction of an extra tick, so a multiplier like 2.5 averages out correctly
    private double overdriveCarry;
    // Bypass Mode has no atMaxSpeed latch, so it records whether its ramp is still climbing
    private boolean bypassRamping;
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

    // Autohighway state
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
    // Route searches run on a background thread so the (expensive) A* never stalls the game thread.
    // The generation is bumped whenever the search state is reset, so a late result from before the
    // reset is thrown away instead of applied.
    private ExecutorService highwayPathExecutor;
    private HighwayPathRequest highwayPathRequest;
    private final AtomicInteger highwayPathGeneration = new AtomicInteger();
    // The point driveAutohighway() wants this tick's movement aimed at
    private Vec3 highwaySteerTarget;
    // The height Autohighway is trying to hold this tick
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
    // Only the nearest part of a route is ever used before it's recomputed (see above), so routes are
    // cut to this many nodes. That keeps the (costly) route validation proportional to what's
    // used, not to the search range
    private static final int HIGHWAY_MAX_ROUTE_NODES = 96;
    // A route with at least this many nodes still ahead is "healthy" and is recomputed less often
    private static final int HIGHWAY_HEALTHY_ROUTE_NODES = 60;
    private static final int HIGHWAY_HEALTHY_REPATH_FACTOR = 3;

    // Autonav state
    // True from the moment a destination is picked until it's cancelled. Read by the HUD thread too
    private volatile boolean autonavActive;
    private volatile int autonavDestX, autonavDestZ;
    // The destination as a point (block centre) and the height straight-line flight is held at
    private double mapDestX, mapDestZ, mapCruiseY;
    // The point Autonav is flying to this tick: the destination, or a point on the highway being joined / followed
    private double mapGoalX, mapGoalZ, mapGoalRadius;
    // Prioritise Highways. A highway is a line through the origin: the X and Z axes or the diagonals
    private static final int MAP_HIGHWAY_NONE = 0, MAP_HIGHWAY_JOINING = 1, MAP_HIGHWAY_FOLLOWING = 2;
    private int mapHighwayPhase;
    // The direction being travelled along the highway (a unit vector) and where it is joined
    private double mapHighwayUx, mapHighwayUz, mapHighwayEntryX, mapHighwayEntryZ;
    // Ticks before a highway is looked for again (after one ended, or none was found)
    private int mapHighwayCooldown;
    private static final double MAP_HIGHWAY_DIAGONAL = Math.sqrt(0.5);
    private static final double[][] MAP_HIGHWAY_DIRECTIONS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1},
        {MAP_HIGHWAY_DIAGONAL, MAP_HIGHWAY_DIAGONAL}, {-MAP_HIGHWAY_DIAGONAL, -MAP_HIGHWAY_DIAGONAL},
        {MAP_HIGHWAY_DIAGONAL, -MAP_HIGHWAY_DIAGONAL}, {-MAP_HIGHWAY_DIAGONAL, MAP_HIGHWAY_DIAGONAL}
    };
    // One direction of each of the four highway lines: the X axis, the Z axis and the two diagonals
    private static final int[] MAP_HIGHWAY_LINES = {0, 2, 4, 6};
    // A highway is only joined if it stays within 45 degrees of the destination for at least this far
    private static final double MAP_HIGHWAY_MIN_FOLLOW = 96.0;
    // Counts as having joined the highway this close to its line
    private static final double MAP_HIGHWAY_JOIN_DISTANCE = 4.0;
    // While following, the point flown to is this far ahead along the highway
    private static final double MAP_HIGHWAY_LOOKAHEAD = 128.0;
    private static final double MAP_HIGHWAY_GOAL_RADIUS = 3.0;
    private static final int MAP_HIGHWAY_COOLDOWN_TICKS = 100;
    private static final int MAP_HIGHWAY_CHECK_TICKS = 10;
    // False: flying the straight line. True: following a planned route round something in the way
    private boolean mapDetouring;
    // How many ticks in a row the straight line has been clear, to stop flapping between the two
    private int mapClearStreak;
    private static final int MAP_STRAIGHT_CONFIRM_TICKS = 3;
    // After getting stuck the line has to stay clear for longer before it's trusted again
    private static final int MAP_STRAIGHT_CONFIRM_TICKS_AFTER_STUCK = 20;
    private final ArrayList<Vec3> mapRouteNodes = new ArrayList<>();
    private int mapRouteIndex;
    private int mapTicksSinceRepath;
    // The last search found nothing: every cell reachable from here is sealed off
    private boolean mapNoRoute;
    // The current route ends on a cell touching an unloaded chunk, so it's out of date once that chunk has loaded
    private boolean mapRouteEndsAtFrontier;
    // How many ticks in a row Autonav has been detouring with no route to follow yet (the search is still running)
    private int mapAwaitingPlanTicks;
    // Stuck detection leaves a search this long to finish before it counts the wait as being stuck
    private static final int MAP_AWAITING_PLAN_GRACE_TICKS = 60;
    // While a route is being worked out, Autonav stops this far short of whatever is in the straight line's way
    private static final double MAP_OBSTACLE_STOP_DISTANCE = 8.0;
    private ExecutorService mapRouteExecutor;
    private MapRouteRequest mapRouteRequest;
    private final AtomicInteger mapRouteGeneration = new AtomicInteger();
    private Vec3 mapLastPos;
    private int mapStuckTicks;
    private int mapMovingTicks;
    private int mapEscalation;
    private final XaeroMapClicks mapClicks = new XaeroMapClicks();
    // Extra room asked of every route cell once Autonav has got stuck (the cell and its neighbours must be clear)
    private static final double MAP_FAT_MARGIN = 0.4;
    // How far from lava / water the route planner and the straight-line check keep the player's hitbox
    private static final double MAP_LAVA_MARGIN = 1.5;
    private static final double MAP_WATER_MARGIN = 0.75;
    // The closest the movement guard lets the hitbox get to an avoided fluid
    private static final double MAP_FLUID_HARD_MARGIN = 0.15;
    // Set by the movement guard each tick: the player is already in lava / water it is set to avoid.
    // The guard then lets it move on through that fluid, or it could never get out
    private boolean mapInLava, mapInWater;
    // Getting out of blocks or a fluid: how far from the player the way out may be searched for, how
    // many cells that search may look at, and what passing through such a cell costs it
    private static final int MAP_ESCAPE_RADIUS = 16;
    private static final int MAP_ESCAPE_MAX_NODES = 30_000;
    private static final int MAP_ESCAPE_SOLID_COST = 12;
    private static final int MAP_ESCAPE_FLUID_COST = 3;

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
    // Last time Autohighway or avoidance dodge was actually flying the player
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
        return speedometer.get() || fuelGauge.get() || modeLights.get() || futureSight.get() || autonavRoute.get() || volytraAssistant.get();
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
        trackedWorld = mc.level;

        Player player = mc.player;

        ensureVisualsAdded();

        resetAssistant();

        atMaxSpeed = false;
        lastJumpPressed = false;
        jumpTimer = 0;
        ticksLeft = 0;
        // Start the acceleration delay afresh: the carried-over speed below is held until it has passed
        accelerationDelayTicks = 0;
        bypassAccelerationDelayTicks = 0;
        lastGlidePos = null;
        lastGlideIntendedSpeed = 0;
        intendedDistanceAccum = 0;
        intendedDistanceLastTick = 0;
        overdriveCarry = 0;
        overdriveSpent = false;
        bypassRamping = false;

        // Carry over real horizontal speed instead of snapping back to minimum-horizontal-speed,
        // so turning VolytraFly on while already moving picks up the ramp from
        // wherever your actual speed already was rather than restarting it from the floor
        double currentHorizontalSpeed = player != null
            ? Math.hypot(player.getDeltaMovement().x(), player.getDeltaMovement().z())
            : 0;
        acceleration = Math.max(minHorizontalSpeed(), Math.min(currentHorizontalSpeed, horizontalSpeed.get()));
        if (acceleration >= horizontalSpeed.get()) atMaxSpeed = true;

        resetVerticalAcceleration();

        rubberbandCapTicksLeft = 0;
        rubberbandQualifyingTimestamps.clear();
        unstickTicksLeft = 0;
        unstickAnchor = null;

        buildingModeEngaged = false;

        resetHighwayState();
        resetAutonavState();

        glidingLastTick = false;
        reglideTicksLeft = 0;
        inCobweb = false;

        if (player == null) return;

        if ((chestSwap.get() == ChestSwapMode.Always || chestSwap.get() == ChestSwapMode.WaitForGround)
            && player.getItemBySlot(EquipmentSlot.CHEST).getItem() != Items.ELYTRA && isActive()) {
            swapToChestSwap();
        }
    }

    @Override
    public void onDeactivate() {
        // Clear anything left from an earlier session; new ones are armed below
        cancelPendingActions();
        mappingWaitingForChunks = false;
        resetAssistant();

        if (autoPilot.get() || autohighway.get() || autonavActive) mc.options.keyUp.setDown(false);
        releaseAvoidance();
        releaseVerticalStep();
        releaseHighwayKeys();
        resetHighwayState();
        highwayPathMob = null;
        highwayPathMobWorld = null;
        if (highwayPathExecutor != null) {
            highwayPathExecutor.shutdownNow();
            highwayPathExecutor = null;
        }
        resetAutonavState();
        if (mapRouteExecutor != null) {
            mapRouteExecutor.shutdownNow();
            mapRouteExecutor = null;
        }

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
    private void onGameLeft(GameLeftEvent event) {
        cancelPendingActions();
        trackedWorld = null;
        // A destination or locked highway from the world just left means nothing in the next one, and
        // Autonav's relaunch would otherwise jump and glide towards it as soon as you rejoin
        resetAutonavState();
        resetHighwayState();
        unstickTicksLeft = 0;
        unstickAnchor = null;
        relaunchCooldown = 0;
        relaunchPause = 0;
        relaunchAttempts = 0;
        relaunchGlidingTicks = 0;
        highwayRelaunchArmed = false;
        reglideTicksLeft = 0;
    }

    @SuppressWarnings("unused") // called by the event bus
    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        Player player = mc.player;
        ClientLevel world = mc.level;

        if (world != trackedWorld) {
            if (trackedWorld != null) {
                cancelPendingActions();
                // Coordinates and highway lines belong to the dimension they were set in
                if (autonavActive) cancelAutonav();
                resetHighwayState();
            }
            trackedWorld = world;
        }
        if (player == null || world == null) return;

        if (!(player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER))) return;

        autoTakeoff();
        updatePlayerAvoidance();
        inCobweb = isInCobweb(player, world);

        if (player.isFallFlying()) {
            boolean bypass = isBypassActive();
            if (rubberbandCapTicksLeft > 0) assistantFlag(AssistantSituation.RUBBERBAND_CAP);

            bypassRamping = false;

            if (bypass) {
                // Preserve the real horizontal velocity instead of resetting it, then accelerate
                // it using the exact same curve and settings as the General section's
                // handleAcceleration()
                double horizontalSpeedNow = Math.hypot(event.movement.x(), event.movement.z());
                if (horizontalSpeedNow > 1.0E-4) {
                    double newSpeed;

                    if (bypassAccelerationDelayTicks < ACCELERATION_DELAY_TICKS) {
                        bypassAccelerationDelayTicks++;
                        newSpeed = horizontalSpeedNow;
                    } else {
                        double cap = horizontalSpeedCap();
                        newSpeed = nextSpeedFor(horizontalSpeedNow, accelerationStep.get(), accelerationPlateau.get(), cap);
                        bypassRamping = newSpeed < cap * OVERDRIVE_OFF_FRACTION;
                    }

                    double scale = newSpeed / horizontalSpeedNow;
                    velX = event.movement.x() * scale;
                    velZ = event.movement.z() * scale;
                } else {
                    // No horizontal motion yet to scale up (e.g. dropped straight into gliding) -
                    // leave it as-is rather than inventing a direction; bypass picks up once
                    // there's real horizontal velocity to build on.
                    bypassAccelerationDelayTicks = 0;
                    velX = event.movement.x();
                    velZ = event.movement.z();
                }
            } else {
                velX = 0;
                velZ = 0;
            }
            velY = event.movement.y();

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
            // Overdrive extra ticks are horizontal-only: the vertical ramp must not advance in them
            if (!overdriveExtraTick) handleVerticalAcceleration();
            handleVerticalSpeed();
            limitHighwayVerticalStep();
            applyHighwaySlopeCoupling();
            handleAntiSlam();
            handleBuildingMode();
            handleMaxHeight();
            applyHighwayClearanceGuard(player);

            // No vertical movement at all in an Overdrive extra tick, so vertical speed per game tick is
            // exactly what it would be with the timer off
            if (overdriveExtraTick) velY = 0;

            // After every steering step, so no mode can move the player while chunks load
            handleMappingMode();

            int chunkX = (int) Math.floor((player.getX() + velX) / 16.0);
            int chunkZ = (int) Math.floor((player.getZ() + velZ) / 16.0);
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
            bypassRamping = false;

            if (lastForwardPressed) {
                mc.options.keyUp.setDown(false);
                lastForwardPressed = false;
            }
        }

        if (noCrash.get() && player.isFallFlying()) {
            Vec3 lookAheadPos = player.position().add(player.getDeltaMovement().normalize().scale(crashLookAhead.get()));
            ClipContext raycastContext = new ClipContext(player.position(), new Vec3(lookAheadPos.x(), player.getY(), lookAheadPos.z()), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
            BlockHitResult hitResult = world.clip(raycastContext);
            if (hitResult.getType() == HitResult.Type.BLOCK) {
                assistantFlag(AssistantSituation.NO_CRASH);
                ((IVec3) event.movement).meteor$set(0, velY, 0);
            }
        }

        if (player.isFallFlying()) {
            lastGlidePos = player.position();
            lastGlideIntendedSpeed = Math.hypot(event.movement.x(), event.movement.z());
            intendedDistanceAccum += lastGlideIntendedSpeed;
        } else {
            lastGlidePos = null;
            lastGlideIntendedSpeed = 0;
        }

    }

    @SuppressWarnings("unused") // called by the event bus
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (rubberbandCapTicksLeft > 0) rubberbandCapTicksLeft--;

        pollMapClick();

        if (autoReplenish.get()) {
            FindItemResult fireworks = InvUtils.find(Items.FIREWORK_ROCKET);

            if (fireworks.found() && !fireworks.isHotbar()) {
                InvUtils.move().from(fireworks.slot()).toHotbar(replenishSlot.get() - 1);
            }
        }

        Player player = mc.player;
        if (player != null) {
            boolean glidingNow = player.isFallFlying();
            // Gliding now, or gliding a tick ago and just lost it (an elytra breaking ends the glide)
            boolean wasFlying = glidingNow || glidingLastTick;

            if (replace.get()) {
                var chestStack = player.getItemBySlot(EquipmentSlot.CHEST);
                boolean wearingElytra = chestStack.getItem() == Items.ELYTRA;

                // Either the worn elytra is about to give out, or it already broke mid-flight and the
                // chest slot is now empty
                boolean needsReplacing = wearingElytra
                    ? chestStack.getMaxDamage() - chestStack.getDamageValue() <= replaceDurability.get()
                    : wasFlying && chestStack.isEmpty();

                if (needsReplacing) {
                    FindItemResult elytra = InvUtils.find(stack -> stack.getMaxDamage() - stack.getDamageValue() > replaceDurability.get() && stack.getItem() == Items.ELYTRA);

                    if (elytra.found()) {
                        InvUtils.move().from(elytra.slot()).toArmor(2);
                        // The swap (or the break before it) drops the player out of the glide, which would
                        // otherwise end Autopilot, Autohighway and Autonav on the spot. Open the new
                        // elytra again so they carry on.
                        if (wasFlying) reglideTicksLeft = REGLIDE_WINDOW_TICKS;
                    }
                }
            }

            continueGlideAfterSwap(player);
            relaunchAfterLanding(player);
            updateUnstick(player);
            glidingLastTick = player.isFallFlying();
        }

        runOverdrive();

        intendedDistanceLastTick = intendedDistanceAccum;
        intendedDistanceAccum = 0;
    }

    /** The minimum horizontal speed in use right now: the setting, or 1 while breaking a rubberband loop. */
    private double minHorizontalSpeed() {
        return unstickTicksLeft > 0 ? Math.min(UNSTICK_MIN_SPEED, startSpeed.get()) : startSpeed.get();
    }

    /** The minimum vertical speed in use right now: the setting, or 1 while breaking a rubberband loop. */
    private double minVerticalSpeed() {
        return unstickTicksLeft > 0 ? Math.min(UNSTICK_MIN_SPEED, verticalStartSpeed.get()) : verticalStartSpeed.get();
    }

    /**
     * Watches for being stuck while Autonav or Autohighway is flying: the player hasn't got further
     * than UNSTICK_RADIUS from where they were UNSTICK_WINDOW_TICKS ago (arriving doesn't count).
     * When that happens both minimum speeds drop to 1 for UNSTICK_SLOW_TICKS and the ramp restarts
     * from there, so the way round a block in the way can be flown slowly enough for the server
     * to accept it.
     */
    private void updateUnstick(Player player) {
        if (unstickTicksLeft > 0) {
            unstickTicksLeft--;
            unstickAnchor = null;
            return;
        }

        boolean autonavFlying = autonavActive && autonav.get()
            && Math.hypot(mapDestX - player.getX(), mapDestZ - player.getZ()) > autonavArrivalRadius.get();
        if (!player.isFallFlying() || !(autonavFlying || autohighway.get())) {
            unstickAnchor = null;
            return;
        }

        Vec3 pos = player.position();
        if (unstickAnchor == null || pos.distanceTo(unstickAnchor) > UNSTICK_RADIUS) {
            unstickAnchor = pos;
            unstickWindowTicks = 0;
            return;
        }

        if (++unstickWindowTicks < UNSTICK_WINDOW_TICKS) return;

        unstickAnchor = pos;
        unstickWindowTicks = 0;
        unstickTicksLeft = UNSTICK_SLOW_TICKS;
        zeroAcceleration();
        assistantMarkActive();
        assistantSay("unstick", true, false, "We're stuck", "Slowing right down for a moment");
    }

    /** The highest Y Autonav and Autohighway may aim for: the max-height setting, or none when it's off. */
    private double flightCeiling() {
        return limitMaxHeight.get() ? maxHeight.get() : Double.POSITIVE_INFINITY;
    }

    /** flightCeiling() as a block Y for the route searches. */
    private int flightCeilingBlock() {
        return limitMaxHeight.get() ? (int) Math.floor(maxHeight.get()) : 100_000;
    }

    /**
     * If Autonav is on its way somewhere, or Autohighway was flying, and the player ends up on the
     * ground (or falling out of the glide) before arriving, it takes off again so the trip carries on.
     * Autohighway only does this after it has actually been flying, so having it switched on while
     * standing around or walking doesn't launch you.
     */
    private void relaunchAfterLanding(Player player) {
        if (relaunchCooldown > 0) relaunchCooldown--;
        if (relaunchPause > 0) relaunchPause--;

        if (!autohighway.get()) highwayRelaunchArmed = false;

        if (player.isFallFlying()) {
            // Only count the take-off as having worked once the glide has held for a moment
            if (++relaunchGlidingTicks >= RELAUNCH_STABLE_TICKS) relaunchAttempts = 0;
            // Autohighway has been flying: from here on, touching down is an accident
            if (autohighway.get()) highwayRelaunchArmed = true;
            return;
        }
        relaunchGlidingTicks = 0;

        boolean autonavMode = autonavActive && autonav.get();
        boolean highwayMode = autohighway.get() && highwayRelaunchArmed;

        if ((!autonavMode && !highwayMode) || mc.level == null || mc.screen != null || mc.isPaused()) {
            relaunchAttempts = 0;
            return;
        }
        if (!player.isAlive() || player.isPassenger() || player.getAbilities().flying
            || player.isInWater() || player.isInLava()
            || !player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER)) return;
        if (isMovementKeyPhysicallyPressed() || isKeyPhysicallyPressed(mc.options.keyShift)) {
            // Walking or sneaking on the ground by choice: Autohighway's relaunch stands down until
            // the next flight (Autonav keeps its destination, so it carries on as before)
            if (!autonavMode && player.onGround()) highwayRelaunchArmed = false;
            return;
        }
        if (autonavMode) {
            // Landing at the destination is arriving, not an accident
            if (Math.hypot(mapDestX - player.getX(), mapDestZ - player.getZ()) <= autonavArrivalRadius.get()) return;
        } else if (highwayDirection.get() == HighwayDirection.TowardsCentre
            && Math.hypot(player.getX(), player.getZ()) < HIGHWAY_CENTRE_RADIUS) {
            // Landing at the centre is arriving too
            return;
        }
        if (relaunchPause > 0) return;

        assistantMarkActive();
        if (relaunchAttempts >= RELAUNCH_MAX_ATTEMPTS) {
            relaunchAttempts = 0;
            relaunchPause = RELAUNCH_PAUSE_TICKS;
            assistantSay(autonavMode ? "map-relaunch-fail" : "highway-relaunch-fail", true, false,
                "I can't seem to take off here", "I'll keep trying in a moment");
            return;
        }

        ClientPacketListener networkHandler = mc.getConnection();
        if (networkHandler == null) return;

        if (player.onGround()) {
            if (relaunchCooldown > 0) return;
            releaseHighwayKeys();
            resetAutonavRamp();
            assistantSay(autonavMode ? "map-relaunch" : "highway-relaunch", true, false,
                "Whoops, we touched down", "Taking off again");
            player.setJumping(false);
            player.setSprinting(true);
            player.jumpFromGround();
            networkHandler.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            relaunchAttempts++;
            relaunchCooldown = RELAUNCH_RETRY_TICKS;
        } else if (player.tryToStartFallFlying()) {
            // In the air without gliding: open the elytra, the same as pressing jump up there
            networkHandler.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            relaunchAttempts++;
        }
    }

    /**
     * After an elytra was swapped in mid-flight, opens it again so the flight carries on instead of
     * the player dropping to the ground.
     */
    private void continueGlideAfterSwap(Player player) {
        if (reglideTicksLeft <= 0) return;
        reglideTicksLeft--;

        if (player.onGround() || player.isInWater()) {
            reglideTicksLeft = 0;
            return;
        }
        if (player.isFallFlying()) return;
        if (!player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER)) return;

        if (player.tryToStartFallFlying()) {
            ClientPacketListener networkHandler = mc.getConnection();
            if (networkHandler != null) {
                networkHandler.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            }
        }
    }

    /**
     * The horizontal distance (blocks per game tick) this module moved the player last game tick,
     * counting every overdrive extra tick.
     */
    public double getIntendedDistanceLastTick() {
        return intendedDistanceLastTick;
    }

    /**
     * A heavily modified form of Meteor's timer that manages to break the standard acceleration curve. Overdrive won't
     * activate when autonav or autohighway is steering since it reduces stability
     */
    private boolean autonavPathfinding() {
        return autonavActive && autonav.get();
    }

    private boolean autohighwayPathfinding() {
        return autohighway.get();
    }

    private void runOverdrive() {
        LocalPlayer player = mc.player;

        updateOverdriveSpent(player);

        if (!overdrive.get() || player == null || mc.level == null || mc.getConnection() == null
            || mc.isPaused() || autonavPathfinding() || autohighwayPathfinding() || unstickTicksLeft > 0
            || isHorizontalRampStalled(player)) {
            overdriveCarry = 0;
            return;
        }

        // This game tick already ran the player once; the multiplier says how many ticks in total
        overdriveCarry += OVERDRIVE_MULTIPLIER - 1.0;
        int extraTicks = (int) overdriveCarry;
        overdriveCarry -= extraTicks;

        // Real-time speed budget: how far the player has gone this game tick (the tick that just
        // ran), which must never pass the top speed.
        double budget = horizontalSpeedCap();
        double movedThisGameTick = lastGlideIntendedSpeed;

        for (int i = 0; i < extraTicks; i++) {
            if (isHorizontalRampStalled(player)) {
                overdriveCarry = 0;
                // The ramp topped out during the extra ticks: the timer has done all it can
                if (overdriveSpeed() >= horizontalSpeed.get() * OVERDRIVE_OFF_FRACTION) overdriveSpent = true;
                break;
            }

            double currentSpeed = isBypassActive() ? lastGlideIntendedSpeed : acceleration;
            double nextTickSpeed = nextSpeedFor(currentSpeed, accelerationStep.get(), accelerationPlateau.get(), budget);
            if (movedThisGameTick + nextTickSpeed > budget) {
                overdriveCarry = 0;
                break;
            }

            // The world normally counts the player's ticks. oldPos is left alone on purpose, so the
            // camera keeps smoothing across the whole distance flown this game tick.
            double savedVelocityY = player.getDeltaMovement().y();
            // The position packet is sent from inside tick(), so the state is checked before it runs
            if (!isOverdrivePacketSane(player)) {
                // Invalid state: skip the tick and stop the timer for this tick, silently
                overdriveCarry = 0;
                break;
            }
            overdriveExtraTick = true;
            try {
                player.tickCount++;
                player.tick();
            } finally {
                overdriveExtraTick = false;
            }
            Vec3 after = player.getDeltaMovement();
            player.setDeltaMovement(after.x(), savedVelocityY, after.z());
            movedThisGameTick += lastGlideIntendedSpeed;
        }
    }

    private double overdriveSpeed() {
        return isBypassActive() ? lastGlideIntendedSpeed : acceleration;
    }

    /**
     * Tracks whether the Overdrive has finished the ramp.
     */
    private void updateOverdriveSpent(Player player) {
        if (player == null || !player.isFallFlying()) {
            overdriveSpent = false;
            return;
        }

        double speed = overdriveSpeed();
        double top = horizontalSpeed.get();

        if (speed <= OVERDRIVE_REARM_SPEED) overdriveSpent = false;
        else if (top > 0 && speed >= top * OVERDRIVE_OFF_FRACTION) overdriveSpent = true;
    }

    /**
     * Whether the horizontal speed ramp is NOT currently on its way up to its top speed.
     */
    private boolean isHorizontalRampStalled(Player player) {
        if (!player.isAlive() || !player.isFallFlying()) return true;
        if (noMovementKeyPressed()) return true;
        if (mappingWaitingForChunks || buildingModeEngaged || rubberbandCapTicksLeft > 0) return true;

        if (overdriveSpent) return true;

        if (isBypassActive()) return !bypassRamping;

        if (atMaxSpeed) return true;
        if (accelerationDelayTicks < ACCELERATION_DELAY_TICKS) return true;
        return acceleration >= horizontalSpeed.get() * OVERDRIVE_OFF_FRACTION;
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
                double speedBps = Math.hypot(player.getDeltaMovement().x(), player.getDeltaMovement().z()) * 20;
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
                int rewindTicks = RUBBERBAND_REWIND_TICKS;
                double keptSpeed = rewindSpeedFor(acceleration, accelerationStep.get(), accelerationPlateau.get(), minHorizontalSpeed(), rewindTicks);

                if (keptSpeed < acceleration) {
                    acceleration = keptSpeed;
                    atMaxSpeed = false;
                    accelerationDelayTicks = 0;

                    verticalAcceleration = rewindSpeedFor(verticalAcceleration, verticalAccelerationStep.get(), verticalAccelerationPlateau.get(), minVerticalSpeed(), rewindTicks);
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
     * Whether Bypass Mode is in effect right now: either switched on in the settings, or forced on
     * because the player is stuck in a cobweb, until they are out of it.
     */
    private boolean isBypassActive() {
        return bypassMode.get() || inCobweb;
    }

    /** Whether any part of the player's hitbox is inside a cobweb. */
    private static boolean isInCobweb(Player player, ClientLevel world) {
        AABB box = player.getBoundingBox();
        int minX = (int) Math.floor(box.minX + 1.0E-5), maxX = (int) Math.floor(box.maxX - 1.0E-5);
        int minY = (int) Math.floor(box.minY + 1.0E-5), maxY = (int) Math.floor(box.maxY - 1.0E-5);
        int minZ = (int) Math.floor(box.minZ + 1.0E-5), maxZ = (int) Math.floor(box.maxZ - 1.0E-5);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (world.getBlockState(pos.set(x, y, z)).is(Blocks.COBWEB)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Flips a boolean setting from its keybind and says so in chat, the same way toggling the
     * module itself does (respecting Meteor's chat feedback option).
     */
    private void toggleWithFeedback(Setting<Boolean> setting, String label) {
        setting.set(!setting.get());
        sendToggleFeedback(label, setting.get());
    }

    private void sendToggleFeedback(String label, boolean on) {
        if (!Config.get().chatFeedback.get() || !chatFeedback) return;
        info("Toggled (highlight)%s(default) %s(default).", label, on ? ChatFormatting.GREEN + "on" : ChatFormatting.RED + "off");
    }

    /**
     * Lets each mode's own keybind (set in its settings group) toggle that mode on/off
     * independently, without needing to open the module's settings screen.
     */
    @SuppressWarnings("unused") // called by the event bus
    @EventHandler
    private void onKeyEvent(KeyInputEvent event) {
        if (event.action != KeyAction.Press) return;

        if (autoPilotKeybind.get().matches(event.input)) toggleWithFeedback(autoPilot, "Autopilot");
        if (autohighwayKeybind.get().matches(event.input)) toggleWithFeedback(autohighway, "Autohighway");
        if (mappingModeKeybind.get().matches(event.input)) toggleWithFeedback(mappingMode, "Mapping Mode");
        if (buildingModeKeybind.get().matches(event.input)) toggleWithFeedback(buildingMode, "Building Mode");
        if (bypassModeKeybind.get().matches(event.input)) toggleWithFeedback(bypassMode, "Bypass Mode");
        if (playerAvoidanceKeybind.get().matches(event.input)) toggleWithFeedback(playerAvoidance, "Player Avoidance");
        if (antiSlamKeybind.get().matches(event.input)) toggleWithFeedback(antiSlam, "Anti Slam");
        if (overdriveKeybind.get().matches(event.input)) toggleWithFeedback(overdrive, "Overdrive");
        if (autonavKeybind.get().matches(event.input)) {
            toggleWithFeedback(autonav, "Autonav");
            if (!autonav.get() && autonavActive) cancelAutonav();
        }
        if (autonavActive && autonavCancelKeybind.get().matches(event.input)) {
            cancelAutonav();
            if (Config.get().chatFeedback.get() && chatFeedback) info("(highlight)Autonav(default) cancelled.");
        }
    }

    /** The server disconnects on NaN/infinite or huge movement values, so check before sending. */
    private static boolean isOverdrivePacketSane(Player p) {
        double x = p.getX(), y = p.getY(), z = p.getZ();
        float yaw = p.getYRot(), pitch = p.getXRot();
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
            && Float.isFinite(yaw) && Float.isFinite(pitch)
            && Math.abs(x) < 3.0E7 && Math.abs(z) < 3.0E7 && Math.abs(y) < 2.0E7;
    }

    private static void swingHand(Player player, InteractionHand hand) {
        player.swing(hand, false);
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

        // Overdrive extra ticks only repeat the flight maths.
        if (overdriveExtraTick) return;

        // Only unarm the locked axis when the mode itself is off - not just because avoidance is
        // briefly steering instead, so a dodge doesn't cost the run its heading.
        if (!autohighway.get()) resetHighwayState();

        // Reset every tick regardless of which branch below runs
        highwaySteerTarget = null;
        highwayTargetY = Double.NaN;

        // Don't fight the avoidance system's movement - it's already driving the WASD keys
        // itself this tick, so leave them alone entirely rather than releasing or overwriting them.
        if (!avoidanceSteering) {
            if (autonavActive && autonav.get()) {
                driveAutonav(player);
            } else if (autohighway.get()) {
                driveAutohighway(player);
            } else {
                releaseHighwayKeys();
                if (autoPilot.get() && player.getY() > autoPilotMinimumHeight.get()) {
                    mc.options.keyUp.setDown(true);
                    lastForwardPressed = true;
                }
            }
        } else if (autohighway.get() || autonavActive) {
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
                    swingHand(player, InteractionHand.OFF_HAND);
                } else {
                    InvUtils.swap(itemResult.slot(), true);

                    interactionManager.useItem(player, InteractionHand.MAIN_HAND);
                    swingHand(player, InteractionHand.MAIN_HAND);

                    InvUtils.swapBack();
                }
            }
            ticksLeft--;
        }
    }

    /**
     * Drives Autohighway for one tick. Autohighway locks onto the nearest world highway line
     * and then flies it with the same pathfinding vanilla mobs use
     */
    private void driveAutohighway(Player player) {
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
            && Math.hypot(pos.x(), pos.z()) < HIGHWAY_CENTRE_RADIUS) {
            highwayStuckTicks = 0;
            highwayMovingTicks = 0;
            highwayEscalation = 0;
            highwayLastPos = pos;

            Vec3 centre = new Vec3(0, Double.isNaN(highwayCruiseY) ? pos.y() : highwayCruiseY, 0);
            assistantSay("centre", false, false, "We've reached the centre");

            highwaySteerTarget = centre;
            holdHighwayDrivingKeys();
            applyHighwayVerticalHold(player, centre.y());
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
            highwayPathGeneration.incrementAndGet(); // and any search still running was for the old situation
            highwayPathRequest = null;

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
        applyHighwayPathResult(world, pos);
        highwayTicksSinceRepath++;
        boolean routeLeft = highwayPathNodes.size() >= 2 && highwayPathIndex < highwayPathNodes.size() - 1;
        int interval = highwayIntelligence.get().repathInterval;
        // A long, still-unspent route doesn't need refreshing on every tick of the cadence
        boolean routeHealthy = routeLeft
            && highwayPathNodes.size() - 1 - highwayPathIndex >= HIGHWAY_HEALTHY_ROUTE_NODES
            && highwayEscalation == 0;
        boolean due = highwayTicksSinceRepath >= (routeHealthy ? interval * HIGHWAY_HEALTHY_REPATH_FACTOR : interval);
        if (!routeLeft || due) {
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
        applyHighwayVerticalHold(player, target.y());
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
        Vec3 lateral = new Vec3(-axis.z(), 0, axis.x());
        double lat = pos.x() * lateral.x() + pos.z() * lateral.z();
        double along = pos.x() * axis.x() + pos.z() * axis.z();

        double range = highwayIntelligence.get().pathRange - 2;
        double ahead = Math.abs(lat) < 2.0 ? range : Math.min(range, Math.abs(lat) + 8.0);

        // The highway lines all pass through the world origin, so a point on one is just
        // axis * distance-along (plus any deliberate sideways offset).
        // Heading towards the centre, the highway ends there: never aim past the origin.
        double reach = along + ahead;
        if (highwayDirection.get() == HighwayDirection.TowardsCentre) reach = Math.min(reach, 0);
        double gx = axis.x() * reach + lateral.x() * lateralOffset;
        double gz = axis.z() * reach + lateral.z() * lateralOffset;

        double dx = gx - pos.x(), dz = gz - pos.z();
        double dist = Math.hypot(dx, dz);
        if (dist > range) {
            gx = pos.x() + dx / dist * range;
            gz = pos.z() + dz / dist * range;
        }

        double cruiseY = Double.isNaN(highwayCruiseY) ? pos.y() : highwayCruiseY;
        ClientLevel world = mc.level;
        double gy = cruiseY + heightOffset;
        if (world != null) gy = Math.max(world.getMinY() + 2, Math.min(flightCeiling(), gy));
        return new Vec3(gx, gy, gz);
    }

    /**
     * One route search handed to the background thread. The worker fills in goalIndex and nodes
     * (the first goal that gave a usable route) and then sets done.
     */
    private static final class HighwayPathRequest {
        final int generation;
        final Parrot mob;
        final Vec3 start;
        final int range;
        final List<Vec3> goals;
        final int firstGoal;
        volatile boolean done;
        int goalIndex = -1;
        ArrayList<Vec3> nodes;

        HighwayPathRequest(int generation, Parrot mob, Vec3 start, int range, List<Vec3> goals, int firstGoal) {
            this.generation = generation;
            this.mob = mob;
            this.start = start;
            this.range = range;
            this.goals = goals;
            this.firstGoal = firstGoal;
        }
    }

    /**
     * Asks the mob navigator (on a background thread) for a route to the goal. The result is picked
     * up by applyHighwayPathResult() on a later tick.
     */
    private void computeHighwayPath(ClientLevel world, Vec3 pos) {
        highwayTicksSinceRepath = 0;

        // Still searching for the current situation: let it finish
        if (highwayPathRequest != null && !highwayPathRequest.done && highwayPathRequest.generation == highwayPathGeneration.get()) return;

        Parrot mob = highwayPathMob(world);
        if (mob == null) return;

        ArrayList<Vec3> goals = new ArrayList<>(6);
        double step = 6.0 * Math.max(1, highwayEscalation);
        double firstSide = (highwayEscalation % 2 == 1) ? 1 : -1;
        if (highwayEscalation == 0) goals.add(goalOnHighway(pos, 0, 0));
        goals.add(goalOnHighway(pos, firstSide * step, 0));
        goals.add(goalOnHighway(pos, -firstSide * step, 0));
        goals.add(goalOnHighway(pos, 0, step));
        if (highwayEscalation > 0) goals.add(goalOnHighway(pos, 0, 0));

        submitHighwayPath(new HighwayPathRequest(highwayPathGeneration.get(), mob, pos, highwayIntelligence.get().pathRange, goals, 0));
    }

    private void submitHighwayPath(HighwayPathRequest request) {
        if (highwayPathExecutor == null) {
            highwayPathExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "VolytraFly-Pathfinder");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1); // never compete with the render thread
                return thread;
            });
        }

        highwayPathRequest = request;
        try {
            highwayPathExecutor.execute(() -> runHighwayPathSearch(request));
        } catch (RejectedExecutionException e) {
            highwayPathRequest = null;
        }
    }

    /** Runs on the background thread: the mob navigator's A*, for each goal in turn until one works. */
    private void runHighwayPathSearch(HighwayPathRequest request) {
        try {
            Parrot mob = request.mob;

            // Follow range is how far from the start the navigator will look; the goal is kept
            // inside it (see goalOnHighway()).
            AttributeInstance followRange = mob.getAttribute(Attributes.FOLLOW_RANGE);
            if (followRange != null && followRange.getBaseValue() != request.range) {
                followRange.setBaseValue(request.range);
            }

            mob.setPos(request.start.x(), request.start.y(), request.start.z());
            PathNavigation navigation = mob.getNavigation();

            for (int i = request.firstGoal; i < request.goals.size(); i++) {
                if (request.generation != highwayPathGeneration.get()) return; // outdated: don't waste time

                Vec3 goal = request.goals.get(i);
                Path path;
                try {
                    path = navigation.createPath(BlockPos.containing(goal.x(), goal.y(), goal.z()), 1);
                } catch (Throwable t) {
                    continue; // the world can change under a background read; just try the next goal
                }
                if (path == null || path.getNodeCount() < 2) continue;

                // A route that just ends where it started (goal sealed inside solid rock, nothing
                // closer reachable) isn't a route.
                BlockPos end = path.getNodePos(path.getNodeCount() - 1);
                Vec3 endPos = new Vec3(end.getX() + 0.5, end.getY() + HIGHWAY_NODE_HEIGHT, end.getZ() + 0.5);
                if (endPos.distanceTo(request.start) < 2.0) continue;

                int nodeCount = Math.min(path.getNodeCount(), HIGHWAY_MAX_ROUTE_NODES);
                ArrayList<Vec3> nodes = new ArrayList<>(nodeCount);
                for (int n = 0; n < nodeCount; n++) {
                    BlockPos node = path.getNodePos(n);
                    nodes.add(new Vec3(node.getX() + 0.5, node.getY() + HIGHWAY_NODE_HEIGHT, node.getZ() + 0.5));
                }

                request.goalIndex = i;
                request.nodes = nodes;
                return;
            }
        } catch (Throwable ignored) {
            // No route this time; the caller falls back to heading straight at the goal.
        } finally {
            request.done = true;
        }
    }

    /**
     * Game thread: takes a finished search and, if it's still relevant, checks it against the
     * player's real hitbox and swaps it in as the route.
     */
    private void applyHighwayPathResult(ClientLevel world, Vec3 pos) {
        HighwayPathRequest request = highwayPathRequest;
        if (request == null || !request.done) return;
        highwayPathRequest = null;
        if (request.generation != highwayPathGeneration.get() || request.nodes == null) return;

        ArrayList<Vec3> nodes = request.nodes;

        // The navigator thinks any block that isn't a full cube (ender chests, slabs, stairs,
        // fences, ...) can be flown through, so its route can pass straight through cells the
        // player physically can't. Bend the route around those, or try the next goal.
        Player player = mc.player;
        if (player != null && (!repairHighwayPath(world, player, nodes) || nodes.getLast().distanceTo(pos) < 2.0)) {
            if (request.goalIndex + 1 < request.goals.size()) {
                submitHighwayPath(new HighwayPathRequest(request.generation, request.mob, pos, request.range, request.goals, request.goalIndex + 1));
            }
            return;
        }

        highwayPathNodes.clear();
        highwayPathNodes.addAll(nodes);
        highwayPathIndex = 0;
        highwayGoal = request.goals.get(request.goalIndex);
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
        return new BlockPos((int) Math.floor(node.x()), (int) Math.round(node.y() - HIGHWAY_NODE_HEIGHT), (int) Math.floor(node.z()));
    }

    /** Whether the hitbox, grown by margin, fits at this point without touching any block. */
    private boolean highwayPointFree(ClientLevel world, Player player, Vec3 p, double margin) {
        return highestBlockingY(world, highwayHitboxBox(player, p.x(), p.y(), p.z(), margin), null) == Integer.MIN_VALUE;
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
        int hi = Math.max(from.getY(), to.getY());
        // The ceiling is the max-height setting (none if it's off), not the build limit
        int maxY = Math.min(flightCeilingBlock(), hi + HIGHWAY_DETOUR_RADIUS);
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

        AABB startBox = highwayHitboxBox(player, pos.x(), pos.y(), pos.z(), HIGHWAY_BLOCK_CLEARANCE);
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
        return highwayRouteClear(world, player, from, to, startBox, false);
    }

    /** As above; with mapHazards, also not through or near the fluids Autonav is set to avoid. */
    private boolean highwayRouteClear(ClientLevel world, Player player, Vec3 from, Vec3 to, AABB startBox, boolean mapHazards) {
        if (Math.abs(to.y() - from.y()) > HIGHWAY_HEIGHT_BAND) return highwaySweepClear(world, player, from, to, startBox, mapHazards);

        Vec3 above = new Vec3(from.x(), to.y(), from.z());
        return highwaySweepClear(world, player, from, above, startBox, mapHazards)
            && highwaySweepClear(world, player, above, to, startBox, mapHazards);
    }

    /**
     * Whether the player's clearance-grown hitbox can travel the straight line from one point to
     * another without touching a block it wasn't already touching at the start
     */
    private boolean highwaySweepClear(ClientLevel world, Player player, Vec3 from, Vec3 to, AABB startBox, boolean mapHazards) {
        AABB trueStart = highwayHitboxBox(player, from.x(), from.y(), from.z(), HIGHWAY_TRUE_MARGIN);
        Vec3 delta = to.subtract(from);
        int steps = Math.max(1, (int) Math.ceil(delta.length() / 0.5));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            Vec3 point = new Vec3(from.x() + delta.x() * t, from.y() + delta.y() * t, from.z() + delta.z() * t);
            AABB box = highwayHitboxBox(player, point.x(), point.y(), point.z(), HIGHWAY_BLOCK_CLEARANCE);
            if (highestBlockingY(world, box, startBox) != Integer.MIN_VALUE) return false;
            if (highestBlockingY(world, highwayHitboxBox(player, point.x(), point.y(), point.z(), HIGHWAY_TRUE_MARGIN), trueStart) != Integer.MIN_VALUE) return false;
            if (mapHazards && mapFluidNear(world, player, point, from)) return false;
        }
        return true;
    }

    /**
     * Whether the hitbox at p is within the safety margin of a fluid Autonav is set to avoid
     * (lava and/or water).
     */
    private boolean mapFluidNear(ClientLevel world, Player player, Vec3 p, Vec3 start) {
        boolean lava = autonavAvoidLava.get(), water = autonavAvoidWater.get();
        if (!lava && !water) return false;

        if (lava && touchesFluid(world, highwayHitboxBox(player, p.x(), p.y(), p.z(), MAP_LAVA_MARGIN),
            start == null ? null : highwayHitboxBox(player, start.x(), start.y(), start.z(), MAP_LAVA_MARGIN), true)) return true;
        return water && touchesFluid(world, highwayHitboxBox(player, p.x(), p.y(), p.z(), MAP_WATER_MARGIN),
            start == null ? null : highwayHitboxBox(player, start.x(), start.y(), start.z(), MAP_WATER_MARGIN), false);
    }

    /** Whether the player's hitbox at (x, y, z) is touching lava (or water), when Autonav is set to avoid it. */
    private boolean mapTouchesAvoidedFluid(ClientLevel world, Player player, double x, double y, double z, boolean lava) {
        if (!(lava ? autonavAvoidLava.get() : autonavAvoidWater.get())) return false;
        return touchesFluid(world, highwayHitboxBox(player, x, y, z, MAP_FLUID_HARD_MARGIN), null, lava);
    }

    /** Whether any whole block cell in box holds lava (or water) and isn't also touched by ignoreBox. */
    private boolean touchesFluid(ClientLevel world, AABB box, AABB ignoreBox, boolean lava) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int maxX = (int) Math.floor(box.maxX), maxY = (int) Math.floor(box.maxY), maxZ = (int) Math.floor(box.maxZ);
        for (int bx = (int) Math.floor(box.minX); bx <= maxX; bx++) {
            for (int by = (int) Math.floor(box.minY); by <= maxY; by++) {
                for (int bz = (int) Math.floor(box.minZ); bz <= maxZ; bz++) {
                    pos.set(bx, by, bz);
                    var fluid = world.getFluidState(pos);
                    if (fluid.isEmpty() || !fluid.is(lava ? FluidTags.LAVA : FluidTags.WATER)) continue;
                    if (ignoreBox != null && ignoreBox.intersects(bx, by, bz, bx + 1, by + 1, bz + 1)) continue;
                    return true;
                }
            }
        }
        return false;
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
        double dx = highwaySteerTarget.x() - pos.x();
        double dz = highwaySteerTarget.z() - pos.z();
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
        double dy = highwaySteerTarget.y() - pos.y();
        if (Math.abs(dy) <= HIGHWAY_HEIGHT_BAND || Math.abs(velY) < 1.0E-6) return;

        double horizontalSpeed = Math.hypot(velX, velZ);
        if (horizontalSpeed < 1.0E-6) return;

        double horizontalDistance = Math.hypot(highwaySteerTarget.x() - pos.x(), highwaySteerTarget.z() - pos.z());
        double share = Math.min(1.0, Math.abs(velY) / Math.abs(dy));
        double allowed = horizontalDistance * share;
        if (allowed < horizontalSpeed) {
            double scale = allowed / horizontalSpeed;
            velX *= scale;
            velZ *= scale;
        }
    }

    // Autonav

    /**
     * Checks for a click on Xaero's World Map (see XaeroMapClicks) and starts flying to the spot.
     */
    private void pollMapClick() {
        if (!autonav.get()) {
            mapClicks.reset();
            // Autonav was switched off mid-route (by the settings screen, not just the keybind):
            // end the trip so the line goes, the destination is forgotten and Overdrive is free again
            if (autonavActive) cancelAutonav();
            return;
        }

        XaeroMapClicks.Click click = mapClicks.poll(mc, autonavButton.get().button);
        if (click != null) startAutonav(click.x(), click.z());

        String problem = mapClicks.takeError();
        if (problem != null) warning(problem);
    }

    /** Called by the Go button in the Autonav settings. */
    public void goToTypedCoords() {
        if (mc.player == null || mc.level == null) {
            warning("Join a world first.");
            return;
        }
        if (!isActive()) {
            warning("Turn VolytraFly on first.");
            return;
        }
        if (!autonav.get()) {
            warning("Turn Autonav on first.");
            return;
        }
        startAutonav(autonavGotoX.get(), autonavGotoZ.get());
    }

    private void startAutonav(int blockX, int blockZ) {
        Player player = mc.player;
        ClientLevel world = mc.level;
        if (player == null || world == null) return;

        resetMapRouteState();
        resetAutonavRamp();
        mapDestX = blockX + 0.5;
        mapDestZ = blockZ + 0.5;
        autonavDestX = blockX;
        autonavDestZ = blockZ;
        mapGoalX = mapDestX;
        mapGoalZ = mapDestZ;
        mapCruiseY = Math.max(world.getMinY() + 2, Math.min(flightCeiling(), player.getY()));
        mapDetouring = false;
        autonavActive = true;

        assistantMarkActive();
        assistantSay("map-set-" + blockX + "," + blockZ, false, true, "Got it! Flying to " + blockX + ", " + blockZ);

        if (autonavCloseMap.get() && mc.screen != null) mc.setScreen(null);
    }

    private void cancelAutonav() {
        boolean wasActive = autonavActive;
        resetAutonavState();
        releaseHighwayKeys();
        if (wasActive) assistantSay("map-cancelled", false, true, "Autonav cancelled", "You're back in control");
    }

    /**
     * For the route line visual: fills out with the points still ahead on the way to the Autonav
     * destination, ending at the destination itself
     */
    public boolean getAutonavRoute(List<Vec3> out) {
        out.clear();
        Player player = mc.player;
        if (!autonavActive || !autonav.get() || player == null) return false;
        if (Math.hypot(mapDestX - player.getX(), mapDestZ - player.getZ()) <= autonavArrivalRadius.get()) return false;

        if (mapDetouring) {
            for (int i = mapRouteIndex + 1; i < mapRouteNodes.size(); i++) out.add(mapRouteNodes.get(i));
        }
        if (mapHighwayPhase != MAP_HIGHWAY_NONE) {
            double ux = mapHighwayUx, uz = mapHighwayUz;
            // Being joined: the line goes via the point where the highway is joined
            if (mapHighwayPhase == MAP_HIGHWAY_JOINING) out.add(new Vec3(mapHighwayEntryX, mapCruiseY, mapHighwayEntryZ));

            // The highway is flown until it stops pointing within 45 degrees of the destination
            // (see mapHighwayUsable), which is where the distance along it has caught up with the
            // destination's distance across it. That is where the route leaves it for the destination.
            // (mapGoalX/Z is no use here: while following it is only an aim point 128 blocks ahead.)
            double exitAlong = (mapDestX * ux + mapDestZ * uz) - Math.abs(mapDestX * uz - mapDestZ * ux);
            Vec3 from = out.isEmpty() ? player.position() : out.getLast();
            if (exitAlong > from.x() * ux + from.z() * uz + 1.0) {
                out.add(new Vec3(ux * exitAlong, mapCruiseY, uz * exitAlong));
            }
        }
        out.add(new Vec3(mapDestX, mapCruiseY, mapDestZ));
        return true;
    }

    /**
     * For the route line visual: true while Autonav is detouring but has no route to follow yet
     * (the search is still running, or the last route has been used up), so the points from
     * getAutonavRoute are only the straight line, not something Autonav is actually flying.
     */
    public boolean isAutonavRoutePending() {
        return autonavActive && mapDetouring && !mapNoRoute
            && (mapRouteNodes.size() < 2 || mapRouteIndex >= mapRouteNodes.size() - 1);
    }

    /** Puts the speed ramp (and the Overdrive's bookkeeping) back to a fresh start. */
    private void resetAutonavRamp() {
        zeroAcceleration();
        overdriveCarry = 0;
        overdriveSpent = false;
        bypassRamping = false;
    }

    private void resetAutonavState() {
        autonavActive = false;
        autonavDestX = 0;
        autonavDestZ = 0;
        mapDestX = 0;
        mapDestZ = 0;
        mapCruiseY = 0;
        resetMapRouteState();
    }

    /** Forgets the route, the search in flight and the stuck counters, but not the destination. */
    private void resetMapRouteState() {
        mapRouteGeneration.incrementAndGet(); // any search still running was for the old situation
        mapRouteRequest = null;
        mapRouteNodes.clear();
        mapRouteIndex = 0;
        mapTicksSinceRepath = 0;
        mapNoRoute = false;
        mapRouteEndsAtFrontier = false;
        mapAwaitingPlanTicks = 0;
        mapHighwayPhase = MAP_HIGHWAY_NONE;
        mapHighwayCooldown = 0;
        mapDetouring = false;
        mapClearStreak = 0;
        mapLastPos = null;
        mapStuckTicks = 0;
        mapMovingTicks = 0;
        mapEscalation = 0;
    }

    /**
     * Drives Autonav for one tick. It flies the straight line to the destination while that line is
     * clear. When something is in the way it asks the planner for a route through air the player fits
     * in, ending at whichever cell touching an unloaded chunk is the quickest way on towards the
     * destination, and follows that until the straight line is clear.
     */
    private void driveAutonav(Player player) {
        ClientLevel world = mc.level;
        if (world == null) {
            releaseHighwayKeys();
            return;
        }

        assistantMarkActive();
        Vec3 pos = player.position();
        double dx = mapDestX - pos.x(), dz = mapDestZ - pos.z();
        double distToDest = Math.hypot(dx, dz);
        Vec3 destPoint = new Vec3(mapDestX, mapCruiseY, mapDestZ);

        // Arrived: hold position at the destination until the user takes over
        if (distToDest <= autonavArrivalRadius.get()) {
            if (isMovementKeyPhysicallyPressed()) {
                cancelAutonav();
                return;
            }
            resetAutonavRamp();
            assistantSay("map-arrived", false, false, "We've arrived", "I'll hover here until you take over");
            highwaySteerTarget = destPoint;
            holdHighwayDrivingKeys();
            applyHighwayVerticalHold(player, mapCruiseY);
            return;
        }

        // Prioritise Highways: the point flown to is the destination, or while a highway is being
        // joined or followed, a point on that highway
        updateHighwayGoal(world, pos);
        double gdx = mapGoalX - pos.x(), gdz = mapGoalZ - pos.z();
        double distToGoal = Math.max(Math.hypot(gdx, gdz), 1.0E-6);
        Vec3 goalPoint = new Vec3(mapGoalX, mapCruiseY, mapGoalZ);

        // Stuck detection, like Autohighway's: barely moved for a while while trying to. It isn't
        // counted while the next chunk along simply hasn't loaded yet, since the player is held
        // back for that (see dont-go-into-unloaded-chunks).
        boolean waitingOnChunks = !world.getChunkSource().hasChunk(
            ((int) Math.floor(pos.x() + gdx / distToGoal * 6.0)) >> 4,
            ((int) Math.floor(pos.z() + gdz / distToGoal * 6.0)) >> 4);
        // Holding still while a route is being worked out isn't being stuck (for a while)
        boolean awaitingPlan = mapAwaitingPlanTicks > 0 && mapAwaitingPlanTicks <= MAP_AWAITING_PLAN_GRACE_TICKS;
        if (mapLastPos != null && !waitingOnChunks && !mappingWaitingForChunks && !awaitingPlan) {
            if (pos.distanceTo(mapLastPos) < HIGHWAY_STUCK_MOVE_EPSILON) {
                mapStuckTicks++;
                mapMovingTicks = 0;
            } else {
                mapStuckTicks = 0;
                mapMovingTicks++;
                if (mapMovingTicks >= 60) mapEscalation = 0;
            }
        } else if (waitingOnChunks || awaitingPlan) {
            mapStuckTicks = 0;
        }
        mapLastPos = pos;
        if (mapStuckTicks >= Math.round(autonavIntelligence.get().stuckTimeout * 20)) {
            mapStuckTicks = 0;
            mapEscalation = Math.min(mapEscalation + 1, 4);
            // Not getting anywhere means the straight line isn't really clear: plan round it,
            // asking for more room round every cell
            mapDetouring = true;
            mapClearStreak = 0;
            clearMapRoute();
            if (mapEscalation == 1) {
                assistantSay("map-stuck1", true, false, "We're a lil' stuck", "I'll try and get us out");
            } else {
                assistantSay("map-stuck2", true, false, "Still stuck", "Lemme give us a bit more room to manoeuvre");
            }
        }

        // Is the straight line, as far as chunks are loaded, clear?
        double lineRange = Math.min(distToGoal, Math.max(96, Math.min(400, horizontalSpeed.get() * 32)));
        double dirX = gdx / distToGoal, dirZ = gdz / distToGoal;
        double blockedAt = mapStraightBlockedAt(world, player, pos, dirX, dirZ, lineRange);
        // Inside blocks, or in a fluid it's set to avoid: the way out has to be planned (flying the
        // straight line would only go on through it), so the line doesn't count as clear
        boolean inHazard = highestBlockingY(world, highwayHitboxBox(player, pos.x(), pos.y(), pos.z(), HIGHWAY_TRUE_MARGIN), null) != Integer.MIN_VALUE
            || mapTouchesAvoidedFluid(world, player, pos.x(), pos.y(), pos.z(), true)
            || mapTouchesAvoidedFluid(world, player, pos.x(), pos.y(), pos.z(), false);
        boolean straightClear = blockedAt < 0 && !inHazard;
        mapClearStreak = straightClear ? mapClearStreak + 1 : 0;

        if (!mapDetouring && !straightClear) {
            mapDetouring = true;
            clearMapRoute();
            mapTicksSinceRepath = Integer.MAX_VALUE / 2; // plan straight away
        } else if (mapDetouring && mapStuckTicks == 0
            && mapClearStreak >= (mapEscalation > 0 ? MAP_STRAIGHT_CONFIRM_TICKS_AFTER_STUCK : MAP_STRAIGHT_CONFIRM_TICKS)) {
            // Back to the open line: carry on level from wherever the detour got to
            mapDetouring = false;
            mapCruiseY = Math.max(world.getMinY() + 2, Math.min(flightCeiling(), pos.y()));
            clearMapRoute();
            goalPoint = new Vec3(mapGoalX, mapCruiseY, mapGoalZ);
        }

        Vec3 target = null;
        boolean awaitingRoute = false;
        if (mapDetouring) {
            applyMapRouteResult();
            mapTicksSinceRepath++;
            boolean routeLeft = mapRouteNodes.size() >= 2 && mapRouteIndex < mapRouteNodes.size() - 1;
            int interval = autonavIntelligence.get().repathInterval;
            // A long, still-unspent route doesn't need redoing every cadence, but it is refreshed
            // now and then so chunks that have loaded since get used
            boolean routeHealthy = routeLeft && mapRouteNodes.size() - 1 - mapRouteIndex >= 8 && mapEscalation == 0;
            // A route that ends at the edge of the loaded world is out of date as soon as the chunks
            // beyond that edge have loaded, so redo it then rather than waiting for the cadence
            boolean endLoaded = routeLeft && mapRouteEndsAtFrontier && mapRouteEndHasLoaded(world, player, pos);
            boolean due = endLoaded || mapTicksSinceRepath >= (routeHealthy ? interval * 3 : interval);
            if ((!routeLeft && !mapNoRoute) || due) submitMapPlan(world, player, pos);

            if (mapRouteNodes.size() >= 2) {
                advanceMapRouteIndex(pos);
                target = pickMapWaypoint(player, world, pos);
            }
            if (target == null && mapNoRoute) target = pos; // sealed in: hold still rather than push at a wall
            if (target == null) {
                // No route to follow yet (the search is still running, or the last route has been
                // used up). Never fall back to flying at the destination here, that is straight into
                // whatever made the line blocked: stop short of it until the route arrives
                awaitingRoute = true;
                target = mapApproachTarget(pos, dirX, dirZ, blockedAt);
            }
        }
        mapAwaitingPlanTicks = awaitingRoute ? mapAwaitingPlanTicks + 1 : 0;
        if (target == null) target = goalPoint;

        updateAutonavAssistant();

        highwaySteerTarget = target;
        holdHighwayDrivingKeys();
        applyHighwayVerticalHold(player, target.y());
    }

    /**
     * Sets the point Autonav flies to this tick (mapGoalX/Z). Normally that's the destination. With
     * Prioritise Highways on it looks for a highway to join first (see chooseHighway), flies to it,
     * then follows it for as long as it points within 45 degrees of the destination.
     */
    private void updateHighwayGoal(ClientLevel world, Vec3 pos) {
        mapGoalX = mapDestX;
        mapGoalZ = mapDestZ;
        mapGoalRadius = autonavArrivalRadius.get();

        if (!autonavPrioritiseHighways.get()) {
            if (mapHighwayPhase != MAP_HIGHWAY_NONE) endHighway(0);
            return;
        }
        if (mapHighwayCooldown > 0) mapHighwayCooldown--;

        if (mapHighwayPhase == MAP_HIGHWAY_NONE) {
            if (mapHighwayCooldown > 0) return;
            if (!chooseHighway(world, pos)) {
                mapHighwayCooldown = MAP_HIGHWAY_CHECK_TICKS;
                return;
            }
        }

        double ux = mapHighwayUx, uz = mapHighwayUz;
        double along = pos.x() * ux + pos.z() * uz;
        double footX = ux * along, footZ = uz * along;
        double lateral = Math.hypot(pos.x() - footX, pos.z() - footZ);

        // Off the highway as soon as it no longer points within 45 degrees of the destination
        if (mapHighwayUsable(ux, uz, footX, footZ) < 0) {
            endHighway(MAP_HIGHWAY_COOLDOWN_TICKS);
            return;
        }
        if (mapHighwayPhase == MAP_HIGHWAY_JOINING && lateral <= MAP_HIGHWAY_JOIN_DISTANCE) {
            mapHighwayPhase = MAP_HIGHWAY_FOLLOWING;
            resetHighwayRoute();
        }

        mapGoalRadius = MAP_HIGHWAY_GOAL_RADIUS;
        if (mapHighwayPhase == MAP_HIGHWAY_FOLLOWING) {
            mapGoalX = footX + ux * MAP_HIGHWAY_LOOKAHEAD;
            mapGoalZ = footZ + uz * MAP_HIGHWAY_LOOKAHEAD;
        } else {
            mapGoalX = mapHighwayEntryX;
            mapGoalZ = mapHighwayEntryZ;
        }
    }

    /**
     * How much further a highway, travelled in direction (ux, uz) from the point (fx, fz) on it,
     * stays within 45 degrees of the destination: the distance along it to the destination, less the
     * distance across it. Negative when it is already outside 45 degrees.
     */
    private double mapHighwayUsable(double ux, double uz, double fx, double fz) {
        double rx = mapDestX - fx, rz = mapDestZ - fz;
        double across = Math.abs(rx * uz - rz * ux);
        double along = rx * ux + rz * uz;
        return along - across;
    }

    /**
     * Picks a highway to join and starts joining it. First, small scale: the nearest highway that
     * passes through loaded chunks and points within 45 degrees of the destination where it would be
     * joined. Failing that, large scale: the highway (X axis, Z axis or either diagonal) that comes
     * closest to the destination.
     */
    private boolean chooseHighway(ClientLevel world, Vec3 pos) {
        double px = pos.x(), pz = pos.z();
        double distToDest = Math.hypot(mapDestX - px, mapDestZ - pz);

        int best = -1;
        double bestLateral = Double.MAX_VALUE;
        double bestFootX = 0, bestFootZ = 0;
        for (int i = 0; i < MAP_HIGHWAY_DIRECTIONS.length; i++) {
            double ux = MAP_HIGHWAY_DIRECTIONS[i][0], uz = MAP_HIGHWAY_DIRECTIONS[i][1];
            double along = px * ux + pz * uz;
            double footX = ux * along, footZ = uz * along;
            double lateral = Math.hypot(px - footX, pz - footZ);
            if (lateral >= distToDest || lateral >= bestLateral) continue;
            if (!world.getChunkSource().hasChunk(((int) Math.floor(footX)) >> 4, ((int) Math.floor(footZ)) >> 4)) continue;
            if (mapHighwayUsable(ux, uz, footX, footZ) < MAP_HIGHWAY_MIN_FOLLOW) continue;
            best = i;
            bestLateral = lateral;
            bestFootX = footX;
            bestFootZ = footZ;
        }

        double ux, uz, footX, footZ;
        if (best >= 0) {
            ux = MAP_HIGHWAY_DIRECTIONS[best][0];
            uz = MAP_HIGHWAY_DIRECTIONS[best][1];
            footX = bestFootX;
            footZ = bestFootZ;
        } else {
            // Of the four lines, the one the destination is nearest to
            int line = MAP_HIGHWAY_LINES[0];
            double nearest = Double.MAX_VALUE;
            for (int i : MAP_HIGHWAY_LINES) {
                double distance = Math.abs(mapDestX * MAP_HIGHWAY_DIRECTIONS[i][1] - mapDestZ * MAP_HIGHWAY_DIRECTIONS[i][0]);
                if (distance < nearest) {
                    nearest = distance;
                    line = i;
                }
            }
            ux = MAP_HIGHWAY_DIRECTIONS[line][0];
            uz = MAP_HIGHWAY_DIRECTIONS[line][1];
            double along = px * ux + pz * uz;
            footX = ux * along;
            footZ = uz * along;
            // Travelled towards the destination
            if ((mapDestX - footX) * ux + (mapDestZ - footZ) * uz < 0) {
                ux = -ux;
                uz = -uz;
            }
            double lateral = Math.hypot(px - footX, pz - footZ);
            if (lateral >= distToDest || mapHighwayUsable(ux, uz, footX, footZ) < MAP_HIGHWAY_MIN_FOLLOW) return false;
        }

        mapHighwayUx = ux;
        mapHighwayUz = uz;
        mapHighwayEntryX = footX;
        mapHighwayEntryZ = footZ;
        mapHighwayPhase = MAP_HIGHWAY_JOINING;
        resetHighwayRoute();
        return true;
    }

    private void endHighway(int cooldownTicks) {
        mapHighwayPhase = MAP_HIGHWAY_NONE;
        mapHighwayCooldown = cooldownTicks;
        resetHighwayRoute();
    }

    /** The point being flown to has just changed from one place to another: drop the route made for the old one. */
    private void resetHighwayRoute() {
        clearMapRoute();
        mapDetouring = false;
        mapClearStreak = 0;
    }

    private void clearMapRoute() {
        mapRouteGeneration.incrementAndGet();
        mapRouteRequest = null;
        mapRouteNodes.clear();
        mapRouteIndex = 0;
        mapNoRoute = false;
        mapRouteEndsAtFrontier = false;
    }

    private double mapStraightBlockedAt(ClientLevel world, Player player, Vec3 from, double dirX, double dirZ, double range) {
        // Chunk edges are looked for every 4 blocks, so a line clipping the corner of an unloaded
        // chunk isn't stepped over
        double reach = 0;
        for (double d = 4; ; d += 4) {
            double along = Math.min(d, range);
            int chunkX = ((int) Math.floor(from.x() + dirX * along)) >> 4;
            int chunkZ = ((int) Math.floor(from.z() + dirZ * along)) >> 4;
            if (!world.getChunkSource().hasChunk(chunkX, chunkZ)) break;
            reach = along;
            if (along >= range) break;
        }
        if (reach < 1.0) return -1;

        Vec3 to = new Vec3(from.x() + dirX * reach, from.y(), from.z() + dirZ * reach);
        AABB startBox = highwayHitboxBox(player, from.x(), from.y(), from.z(), HIGHWAY_BLOCK_CLEARANCE);
        AABB trueStart = highwayHitboxBox(player, from.x(), from.y(), from.z(), HIGHWAY_TRUE_MARGIN);
        int steps = Math.max(1, (int) Math.ceil(reach / 0.5));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            Vec3 point = new Vec3(from.x() + (to.x() - from.x()) * t, from.y(), from.z() + (to.z() - from.z()) * t);
            AABB box = highwayHitboxBox(player, point.x(), point.y(), point.z(), HIGHWAY_BLOCK_CLEARANCE);
            if (highestBlockingY(world, box, startBox) != Integer.MIN_VALUE
                || highestBlockingY(world, highwayHitboxBox(player, point.x(), point.y(), point.z(), HIGHWAY_TRUE_MARGIN), trueStart) != Integer.MIN_VALUE
                || mapFluidNear(world, player, point, from)) {
                return reach * t;
            }
        }
        return -1;
    }

    /**
     * Where to head while there's no route yet: along the straight line, but stopping short of the
     * first thing in its way (blockedAt, from mapStraightBlockedAt). Right at it, or with no known
     * reason to go on, it holds still.
     */
    private Vec3 mapApproachTarget(Vec3 pos, double dirX, double dirZ, double blockedAt) {
        if (blockedAt < 0) return pos;
        double go = blockedAt - MAP_OBSTACLE_STOP_DISTANCE;
        if (go < 1.0) return pos;
        return new Vec3(pos.x() + dirX * go, mapCruiseY, pos.z() + dirZ * go);
    }

    /** Whether the cell the current route ends on no longer touches an unloaded chunk. */
    private boolean mapRouteEndHasLoaded(ClientLevel world, Player player, Vec3 pos) {
        if (mapRouteNodes.isEmpty()) return false;
        Vec3 end = mapRouteNodes.getLast();
        return !AutonavPlanner.isFrontier(new MapGrid(world, player, pos), (int) Math.floor(end.x()), (int) Math.floor(end.z()));
    }

    /** One route search handed to the background thread. The worker fills in status and nodes, then sets done. */
    private static final class MapRouteRequest {
        final int generation;
        final ClientLevel world;
        final Player player;
        final Vec3 start;
        final double destX, destZ;
        final double arrivalRadius;
        final int escalation;
        final int maxNodes;
        volatile boolean done;
        AutonavPlanner.Status status;
        ArrayList<Vec3> nodes;

        MapRouteRequest(int generation, ClientLevel world, Player player, Vec3 start, double destX, double destZ,
                        double arrivalRadius, int escalation, int maxNodes) {
            this.generation = generation;
            this.world = world;
            this.player = player;
            this.start = start;
            this.destX = destX;
            this.destZ = destZ;
            this.arrivalRadius = arrivalRadius;
            this.escalation = escalation;
            this.maxNodes = maxNodes;
        }
    }

    private void submitMapPlan(ClientLevel world, Player player, Vec3 pos) {
        mapTicksSinceRepath = 0;

        // Still searching for the current situation: let it finish
        if (mapRouteRequest != null && !mapRouteRequest.done && mapRouteRequest.generation == mapRouteGeneration.get()) return;

        if (mapRouteExecutor == null) {
            mapRouteExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "VolytraFly-MapPlanner");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1); // never compete with the render thread
                return thread;
            });
        }

        // Each stuck escalation doubles how much the search may look at
        int budget = autonavIntelligence.get().mapSearchNodes * (1 << Math.min(mapEscalation, 2));
        MapRouteRequest request = new MapRouteRequest(mapRouteGeneration.get(), world, player, pos, mapGoalX, mapGoalZ,
            mapGoalRadius, mapEscalation, budget);
        mapRouteRequest = request;
        try {
            mapRouteExecutor.execute(() -> runMapPlan(request));
        } catch (RejectedExecutionException e) {
            mapRouteRequest = null;
        }
    }

    /** Runs on the background thread: plans the route, then straightens it. */
    private void runMapPlan(MapRouteRequest request) {
        try {
            AutonavPlanner.Grid grid = new MapGrid(request.world, request.player, request.start);
            int[] start = {(int) Math.floor(request.start.x()), (int) Math.floor(request.start.y()), (int) Math.floor(request.start.z())};

            // After getting stuck, ask for extra room round every cell first, and settle for the
            // normal amount only if that finds nothing
            AutonavPlanner.Result best = null;
            for (int clearance = request.escalation > 0 ? 1 : 0; clearance >= 0; clearance--) {
                if (request.generation != mapRouteGeneration.get()) return; // outdated: don't waste time
                AutonavPlanner.Result result = AutonavPlanner.planTwoPass(grid, start, request.destX, request.destZ,
                    request.arrivalRadius, start[1], clearance, request.maxNodes);
                boolean found = result.status == AutonavPlanner.Status.REACHED_DESTINATION
                    || result.status == AutonavPlanner.Status.REACHED_FRONTIER;
                if (best == null || found || (best.status == AutonavPlanner.Status.SEALED)) best = result;
                if (found) break;
            }
            // Nothing to search through from here: if that's because the player is inside blocks or a
            // fluid, find the cheapest way through them to open space
            if (best.status == AutonavPlanner.Status.SEALED && grid.escapeCost(start[0], start[1], start[2]) != 0) {
                AutonavPlanner.Result out = AutonavPlanner.escape(grid, start, MAP_ESCAPE_RADIUS, MAP_ESCAPE_MAX_NODES);
                if (out.status != AutonavPlanner.Status.SEALED) best = out;
            }
            request.status = best.status;
            if (best.status != AutonavPlanner.Status.SEALED) {
                // An escape route goes through cells the smoothing would call blocked, so it is left as it is
                List<int[]> cells = best.status == AutonavPlanner.Status.ESCAPING ? best.cells : AutonavPlanner.smooth(grid, best.cells, 64);
                ArrayList<Vec3> nodes = new ArrayList<>(cells.size());
                for (int[] cell : cells) nodes.add(new Vec3(cell[0] + 0.5, cell[1] + HIGHWAY_NODE_HEIGHT, cell[2] + 0.5));
                // The route starts where the player actually is, not the middle of their cell
                if (!nodes.isEmpty()) nodes.set(0, request.start);
                request.nodes = nodes;
            }
        } catch (Throwable ignored) {
            // The world can change under a background read. No route this time; the next cadence tries again.
        } finally {
            request.done = true;
        }
    }

    /** Game thread: takes a finished search and, if it's still relevant, swaps it in as the route. */
    private void applyMapRouteResult() {
        MapRouteRequest request = mapRouteRequest;
        if (request == null || !request.done) return;
        mapRouteRequest = null;
        if (request.generation != mapRouteGeneration.get() || request.status == null) return;

        if (request.status == AutonavPlanner.Status.SEALED) {
            mapRouteNodes.clear();
            mapRouteIndex = 0;
            mapNoRoute = true;
            return;
        }
        if (request.nodes == null || request.nodes.size() < 2) return;

        mapNoRoute = false;
        mapRouteNodes.clear();
        mapRouteNodes.addAll(request.nodes);
        mapRouteIndex = 0;
        mapRouteEndsAtFrontier = request.status == AutonavPlanner.Status.REACHED_FRONTIER;
    }

    /** Moves mapRouteIndex forward past every segment of the route the player has already flown. */
    private void advanceMapRouteIndex(Vec3 pos) {
        int last = mapRouteNodes.size() - 1;
        while (mapRouteIndex < last) {
            Vec3 cur = mapRouteNodes.get(mapRouteIndex);
            Vec3 seg = mapRouteNodes.get(mapRouteIndex + 1).subtract(cur);
            double len2 = seg.lengthSqr();
            if (len2 < 1.0E-6 || pos.subtract(cur).dot(seg) / len2 >= 1.0) mapRouteIndex++;
            else break;
        }
    }

    /** The furthest point along the route, within a short way of the player's place on it, that can be flown to directly. */
    private Vec3 pickMapWaypoint(Player player, ClientLevel world, Vec3 pos) {
        int first = mapRouteIndex + 1;
        if (first >= mapRouteNodes.size()) return null;
        int last = Math.min(mapRouteNodes.size() - 1, mapRouteIndex + HIGHWAY_MAX_LOOKAHEAD_NODES);

        AABB startBox = highwayHitboxBox(player, pos.x(), pos.y(), pos.z(), HIGHWAY_BLOCK_CLEARANCE);
        for (int k = last; k > first; k--) {
            Vec3 candidate = mapRouteNodes.get(k);
            if (highwayRouteClear(world, player, pos, candidate, startBox, true)) return candidate;
        }
        return mapRouteNodes.get(first);
    }

    /** What the planner sees of the world: the player's hitbox against the blocks, and which chunks are loaded. */
    private final class MapGrid implements AutonavPlanner.Grid {
        private final ClientLevel world;
        private final Player player;
        private final Vec3 start;

        MapGrid(ClientLevel world, Player player, Vec3 start) {
            this.world = world;
            this.player = player;
            this.start = start;
        }

        @Override
        public boolean isChunkUnloaded(int chunkX, int chunkZ) {
            return !world.getChunkSource().hasChunk(chunkX, chunkZ);
        }

        @Override
        public boolean isPassable(int x, int y, int z, int clearance) {
            if (isChunkUnloaded(x >> 4, z >> 4)) return false;
            // Slightly under the clearance the movement guard enforces, so cells the planner offers always pass it
            double margin = clearance > 0 ? MAP_FAT_MARGIN : HIGHWAY_BLOCK_CLEARANCE - 1.0E-4;
            Vec3 point = new Vec3(x + 0.5, y + HIGHWAY_NODE_HEIGHT, z + 0.5);
            return highwayPointFree(world, player, point, margin) && !mapFluidNear(world, player, point, start);
        }

        @Override
        public boolean lineClear(int x0, int y0, int z0, int x1, int y1, int z1) {
            Vec3 from = new Vec3(x0 + 0.5, y0 + HIGHWAY_NODE_HEIGHT, z0 + 0.5);
            Vec3 to = new Vec3(x1 + 0.5, y1 + HIGHWAY_NODE_HEIGHT, z1 + 0.5);
            AABB startBox = highwayHitboxBox(player, from.x(), from.y(), from.z(), HIGHWAY_BLOCK_CLEARANCE);
            return highwayRouteClear(world, player, from, to, startBox, true);
        }

        @Override
        public int escapeCost(int x, int y, int z) {
            if (isChunkUnloaded(x >> 4, z >> 4)) return -1;
            Vec3 point = new Vec3(x + 0.5, y + HIGHWAY_NODE_HEIGHT, z + 0.5);
            if (!highwayPointFree(world, player, point, HIGHWAY_BLOCK_CLEARANCE - 1.0E-4)) return MAP_ESCAPE_SOLID_COST;
            if (mapFluidNear(world, player, point, null)) return MAP_ESCAPE_FLUID_COST;
            return 0;
        }

        @Override
        public int minY() {
            return world.getMinY() + 1;
        }

        @Override
        public int maxY() {
            int startY = (int) Math.floor(start.y());
            // With the max-height limit on, that is the ceiling (never below where the route starts)
            if (limitMaxHeight.get()) return Math.max(flightCeilingBlock(), startY);
            // Limit off: the build limit, unless already at or above it, then the open air up there
            int top = world.getMinY() + world.getHeight() - 3;
            return startY >= top ? startY + MAP_ABOVE_LIMIT_ROOM : top;
        }
    }

    /** Has the assistant say which move Autonav is making. */
    private void updateAutonavAssistant() {
        // A general situation (see getAssistantText()) is being announced instead
        if (currentAssistantSituation(true) != null) return;

        if (mappingWaitingForChunks) {
            assistantSay("chunks", false, false, "Waiting for chunks ahead to load", "One moment");
        } else if (mapDetouring && mapNoRoute) {
            assistantSay("map-sealed", true, false, "I can't find a way out of here", "Lemme keep looking as more chunks load");
        } else if (mapDetouring) {
            assistantSay("map-detour", false, false, "Something's in the way", "I'm heading for the chunks nearest " + autonavDestX + ", " + autonavDestZ + " and I'll go on from there");
        } else if (mapHighwayPhase != MAP_HIGHWAY_NONE) {
            String heading = highwayHeadingName(new Vec3(mapHighwayUx, 0, mapHighwayUz));
            if (mapHighwayPhase == MAP_HIGHWAY_JOINING) {
                assistantSay("map-highway-join-" + heading, false, false, "Heading for the " + heading + " highway", "I'll follow it for as long as it points our way");
            } else {
                assistantSay("map-highway-follow-" + heading, false, false, "Following the " + heading + " highway");
            }
        } else {
            assistantSay("map-straight", false, false, "Flying straight to " + autonavDestX + ", " + autonavDestZ);
        }
    }

    // Volytra Assistant

    /**
     * What the Volytra Assistant should be saying right now
     */
    public String getAssistantText() {
        boolean autonavFlying = autonavActive;
        boolean highway = autohighway.get() || autonavFlying;
        Player player = mc.player;
        boolean flying = player != null && player.isFallFlying();
        long now = System.currentTimeMillis();

        AssistantSituation situation = flying ? currentAssistantSituation(highway) : null;
        if (situation != null) {
            // Leftover Autohighway text
            boolean stale = !highway && assistantKey != null && !assistantKey.startsWith("g-");
            assistantSayGeneral(situation, stale);
        } else if (!highway) {
            // Nothing special going on and Autohighway isn't speaking
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
            if (autonavFlying) {
                assistantSay("map-idle", false, true, "Destination set to " + autonavDestX + ", " + autonavDestZ, "Just take off and I'll fly us there");
            } else {
                assistantSay("idle", false, true, "Hey there - ooh you've got Autohighway on", "Just take off and I'll guide us along the highway");
            }
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
        String ns = axis.z() < -0.1 ? "north" : axis.z() > 0.1 ? "south" : "";
        String ew = axis.x() > 0.1 ? "east" : axis.x() < -0.1 ? "west" : "";
        if (ns.isEmpty()) return ew;
        if (ew.isEmpty()) return ns;
        return ns + "-" + ew;
    }

    /**
     * Works out which move Autohighway is making this tick and has the assistant say so
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

        double dy = target.y() - pos.y();
        if (dy > 1.5) {
            assistantSay("climbing", false, false, "Something's in the way", "I'll try and take us over the top");
            return;
        }
        if (dy < -1.5) {
            assistantSay("descending", false, false, "Returning us to cruising height");
            return;
        }

        Vec3 lateral = new Vec3(-axis.z(), 0, axis.x());
        double lat = pos.x() * lateral.x() + pos.z() * lateral.z();
        double targetLat = target.x() * lateral.x() + target.z() * lateral.z();

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

        double dot = x * dir.x() + z * dir.z();
        boolean awayFromCentre = highwayDirection.get() == HighwayDirection.AwayFromCentre;
        double sign = (dot >= 0) == awayFromCentre ? 1 : -1;

        highwayAxis = new Vec3(dir.x() * sign, 0, dir.z() * sign).normalize();
        highwayCruiseY = player.getY();

        assistantSay("armed", false, true, "Locked onto the " + highwayHeadingName(highwayAxis) + " highway", "Here we go");
    }

    /**
     * Forgets everything Autohighway is holding on to
     */
    private void resetHighwayState() {
        highwayAxis = null;
        highwayCruiseY = Double.NaN;
        highwayGoal = null;
        highwaySteerTarget = null;
        highwayTargetY = Double.NaN;
        highwayPathGeneration.incrementAndGet();
        highwayPathRequest = null;
        highwayPathNodes.clear();
        highwayPathIndex = 0;
        highwayTicksSinceRepath = 0;
        highwayStuckTicks = 0;
        highwayMovingTicks = 0;
        highwayEscalation = 0;
        highwayLastPos = null;
        highwayRelaunchArmed = false;
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
     * Keeps the vertical step from overshooting the height Autohighway is holding
     */
    private void limitHighwayVerticalStep() {
        if (Double.isNaN(highwayTargetY) || !(autohighway.get() || autonavActive) || mc.player == null) return;

        double error = highwayTargetY - mc.player.getY();
        if (velY > 0) velY = Math.min(velY, Math.max(0, error));
        else if (velY < 0) velY = Math.max(velY, Math.min(0, error));
    }

    /**
     * Last line of defence for highway-block-clearance
     */
    private void applyHighwayClearanceGuard(Player player) {
        if (!autonavActive && (!autohighway.get() || highwayAxis == null)) return;
        ClientLevel world = mc.level;
        if (world == null) return;
        if (Math.abs(velX) + Math.abs(velY) + Math.abs(velZ) < 1.0E-6) return;

        double x = player.getX(), y = player.getY(), z = player.getZ();
        AABB start = highwayHitboxBox(player, x, y, z, HIGHWAY_BLOCK_CLEARANCE);
        AABB hardStart = highwayHitboxBox(player, x, y, z, HIGHWAY_HARD_MARGIN);
        mapInLava = autonavActive && mapTouchesAvoidedFluid(world, player, x, y, z, true);
        mapInWater = autonavActive && mapTouchesAvoidedFluid(world, player, x, y, z, false);
        double horizontalBefore = Math.hypot(velX, velZ);

        double allowedY = clampHighwayAxis(world, player, x, y, z, 1, velY, start, hardStart);
        y += allowedY;
        // Y, then the longer horizontal axis, then the other: the order the server resolves a move in.
        // Moving X first when the server does Z first lets a move round a corner pass here and
        // still get rubberbanded
        double allowedX, allowedZ;
        if (Math.abs(velX) < Math.abs(velZ)) {
            allowedZ = clampHighwayAxis(world, player, x, y, z, 2, velZ, start, hardStart);
            z += allowedZ;
            allowedX = clampHighwayAxis(world, player, x, y, z, 0, velX, start, hardStart);
        } else {
            allowedX = clampHighwayAxis(world, player, x, y, z, 0, velX, start, hardStart);
            x += allowedX;
            allowedZ = clampHighwayAxis(world, player, x, y, z, 2, velZ, start, hardStart);
        }

        velX = allowedX;
        velY = allowedY;
        velZ = allowedZ;

        // A hit that costs speed drops the ramp to what was kept
        double horizontalAfter = Math.hypot(velX, velZ);
        if (horizontalAfter < horizontalBefore - 1.0E-3) {
            double kept = Math.max(minHorizontalSpeed(), horizontalAfter);
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

        // Autonav also never lets the hitbox into lava / water it is set to avoid (the planner keeps
        // a wider berth; this is the backstop for when the route is out of date)
        if (autonavActive && (autonavAvoidLava.get() || autonavAvoidWater.get())) {
            AABB fluidBox = highwayHitboxBox(player, nx, ny, nz, MAP_FLUID_HARD_MARGIN);
            if (autonavAvoidLava.get() && !mapInLava && touchesFluid(world, fluidBox, start, true)) return false;
            if (autonavAvoidWater.get() && !mapInWater && touchesFluid(world, fluidBox, start, false)) return false;
        }

        // Blocks the user is already inside the clearance of are skipped above so they can still move along them
        if (highestBlockingY(world, highwayHitboxBox(player, nx, ny, nz, HIGHWAY_HARD_MARGIN), hardStart) != Integer.MIN_VALUE) return false;

        // ...but never into or through one: the real hitbox has to stay clear of every block it isn't already overlapping
        AABB trueStart = highwayHitboxBox(player, x, y, z, HIGHWAY_TRUE_MARGIN);
        return highestBlockingY(world, highwayHitboxBox(player, nx, ny, nz, HIGHWAY_TRUE_MARGIN), trueStart) == Integer.MIN_VALUE;
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
     * Releases the keys Autohighway drives (WASD plus jump/sneak), skipping any the user is
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
            velX += forward.x() * acceleration * 10;
            velZ += forward.z() * acceleration * 10;
            a = true;
        } else if (mc.options.keyDown.isDown()) {
            velX -= forward.x() * acceleration * 10;
            velZ -= forward.z() * acceleration * 10;
            a = true;
        }

        if (mc.options.keyRight.isDown()) {
            velX += right.x() * acceleration * 10;
            velZ += right.z() * acceleration * 10;
            b = true;
        } else if (mc.options.keyLeft.isDown()) {
            velX -= right.x() * acceleration * 10;
            velZ -= right.z() * acceleration * 10;
            b = true;
        }

        if (a && b) {
            double diagonal = 1 / Math.sqrt(2);
            velX *= diagonal;
            velZ *= diagonal;
        }

    }

    /**
     * Mapping mode: hold horizontal movement at 0 until every chunk within
     * mapping-render-radius has finished loading. Runs on the final movement so it covers
     * the normal, Bypass and Highway/Autonav steering paths alike.
     */
    private void handleMappingMode() {
        if (mappingMode.get()) {
            mappingWaitingForChunks = !areChunksLoadedInRadius(mappingRenderRadius.get());

            if (mappingWaitingForChunks) {
                assistantFlag(AssistantSituation.CHUNKS);
                velX = 0;
                velZ = 0;

                // Keep the horizontal ramp at its start while waiting on chunks, so once
                // they finish loading you take off from start-speed again instead of picking up
                // wherever the ramp happened to be when the wait began.
                resetHorizontalAcceleration();
                bypassAccelerationDelayTicks = 0;
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

                if (hitResult.getType() != HitResult.Type.BLOCK) continue;

                double distance = y - hitResult.getLocation().y();
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

    private boolean noMovementKeyPressed() {
        return !(mc.options.keyUp.isDown() || mc.options.keyDown.isDown()
            || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown());
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
        double moved = Math.hypot(now.x() - lastGlidePos.x(), now.z() - lastGlidePos.z());
        // Any real loss of speed counts (head-on or glancing) - only float noise is ignored.
        if (moved >= lastGlideIntendedSpeed - 1.0E-3) return;

        double kept = Math.max(minHorizontalSpeed(), moved);
        if (kept < acceleration) acceleration = kept;
        atMaxSpeed = acceleration >= horizontalSpeed.get();
        accelerationDelayTicks = 0;
    }

    private void handleAcceleration() {
        if (noMovementKeyPressed()) {
            // No movement key held - reset back to the start of the sequence. Nothing is
            // remembered between releases.
            resetHorizontalAcceleration();
            return;
        }

        if (atMaxSpeed) return; // Already at horizontal-speed - nothing left to do.

        if (accelerationDelayTicks < ACCELERATION_DELAY_TICKS) {
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
        verticalAcceleration = minVerticalSpeed();
    }

    /**
     * Resets the horizontal acceleration ramp back to its starting point.
     */
    private void resetHorizontalAcceleration() {
        atMaxSpeed = false;
        accelerationDelayTicks = 0;
        acceleration = minHorizontalSpeed();
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

        if (verticalAccelerationDelayTicks < ACCELERATION_DELAY_TICKS) {
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

        double targetYaw = Math.toDegrees(Math.atan2(-dir.x(), dir.z()));
        double relative = wrapDegrees(targetYaw - player.getYRot());

        // Snap to the nearest of the 8 directions a keyboard can express (N/NE/E/SE/S/SW/W/NW
        // relative to view direction) and press the corresponding key(s).
        int octant = ((int) Math.round(relative / 45.0) % 8 + 8) % 8;

        mc.options.keyUp.setDown(octant == 0 || octant == 1 || octant == 7);
        mc.options.keyDown.setDown(octant == 3 || octant == 4 || octant == 5);
        mc.options.keyRight.setDown(octant == 1 || octant == 2 || octant == 3);
        mc.options.keyLeft.setDown(octant == 5 || octant == 6 || octant == 7);
    }

    /**
     * The entities that already have their own avoidance options (players, wither skulls, arrows),
     * so the Other Entities list leaves them out. Matched by registry path rather than the EntityType
     * constants, like parrotEntityType() does.
     */
    private static boolean isAvoidanceBuiltInEntity(EntityType<?> type) {
        String path = BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
        return path.equals("player") || path.equals("wither_skull") || path.equals("arrow");
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

        Set<EntityType<?>> otherEntities = otherAvoidedEntities.get();
        if (avoidWitherSkulls.get() || avoidArrows.get() || !otherEntities.isEmpty()) {
            for (Entity entity : world.entitiesForRendering()) {
                if (avoidWitherSkulls.get() && entity instanceof WitherSkull) {
                    if (accumulateThreat(entity.position(), witherSkullRadius.get(), acc)) foundThreat = true;
                } else if (avoidArrows.get() && entity instanceof Arrow) {
                    if (accumulateThreat(entity.position(), arrowRadius.get(), acc)) foundThreat = true;
                } else if (!otherEntities.isEmpty() && entity != self && otherEntities.contains(entity.getType())) {
                    if (accumulateThreat(entity.position(), otherEntitiesRadius.get(), acc)) foundThreat = true;
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
        Vec3 perpA = new Vec3(-away.z(), 0, away.x());
        Vec3 perpB = new Vec3(away.z(), 0, -away.x());

        boolean sideA;
        if (avoidanceLateralDir != null) {
            sideA = avoidanceLateralDir.dot(perpA) >= avoidanceLateralDir.dot(perpB);
        } else {
            Player player = mc.player;
            Vec3 vel = player != null ? player.getDeltaMovement() : Vec3.ZERO;
            Vec3 horizontalVel = new Vec3(vel.x(), 0, vel.z());
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

        return new Vec3(v.x() * cos - v.z() * sin, 0, v.x() * sin + v.z() * cos);
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
        awayFromThreat = new Vec3(awayFromThreat.x(), 0, awayFromThreat.z());
        if (awayFromThreat.lengthSqr() == 0) return false;

        double weight = 1 - (Math.sqrt(distanceSq) / radius);
        awayFromThreat = awayFromThreat.normalize().scale(weight);

        acc[0] += awayFromThreat.x();
        acc[1] += awayFromThreat.z();
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
     * Checks the actual hardware key state rather than KeyMapping.isDown().
     */
    private boolean isKeyPhysicallyPressed(KeyMapping binding) {
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

    // World the module last saw, used to notice a world change while active
    private ClientLevel trackedWorld;

    /**
     * Clears the WaitForGround chest swap and insta-drop listeners. They can outlive the
     * context they were armed in (module re-activated, disconnect, world change), so this runs on
     * activate, deactivate, GameLeftEvent and world change.
     */
    private void cancelPendingActions() {
        disableGroundListener();
        disableInstaDropListener();
    }

    //Ground
    private class StaticGroundListener {
        @SuppressWarnings("unused") // called by the event bus
        @EventHandler
        private void onGameLeft(GameLeftEvent event) {
            disableGroundListener();
        }

        @SuppressWarnings("unused") // called by the event bus
        @EventHandler
        private void chestSwapGroundListener(PlayerMoveEvent event) {
            if (mc.level != groundListenerWorld) {
                disableGroundListener();
                return;
            }

            Player player = mc.player;
            if (player == null || !player.onGround()) return;

            // Landing completes the action either way; only swap if an elytra is still equipped
            if (player.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA) {
                swapToChestSwap();
            }
            disableGroundListener();
        }
    }

    private final StaticGroundListener staticGroundListener = new StaticGroundListener();
    private boolean groundListenerActive;
    private ClientLevel groundListenerWorld;

    protected void enableGroundListener() {
        if (groundListenerActive) return;
        groundListenerActive = true;
        groundListenerWorld = mc.level;
        MeteorClient.EVENT_BUS.subscribe(staticGroundListener);
    }

    protected void disableGroundListener() {
        if (!groundListenerActive) return;
        groundListenerActive = false;
        groundListenerWorld = null;
        MeteorClient.EVENT_BUS.unsubscribe(staticGroundListener);
    }

    //Drop
    private class StaticInstaDropListener {
        @SuppressWarnings("unused") // called by the event bus
        @EventHandler
        private void onGameLeft(GameLeftEvent event) {
            disableInstaDropListener();
        }

        @SuppressWarnings("unused") // called by the event bus
        @EventHandler
        private void onInstadropTick(TickEvent.Post event) {
            if (mc.level != instaDropListenerWorld) {
                disableInstaDropListener();
                return;
            }

            LocalPlayer player = mc.player;
            if (player != null && player.isFallFlying()) {
                player.setDeltaMovement(0, 0, 0);
                player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(true, player.horizontalCollision));
            } else {
                disableInstaDropListener();
            }
        }
    }

    private final StaticInstaDropListener staticInstadropListener = new StaticInstaDropListener();
    private boolean instaDropListenerActive;
    private ClientLevel instaDropListenerWorld;

    protected void enableInstaDropListener() {
        if (instaDropListenerActive) return;
        instaDropListenerActive = true;
        instaDropListenerWorld = mc.level;
        MeteorClient.EVENT_BUS.subscribe(staticInstadropListener);
    }

    protected void disableInstaDropListener() {
        if (!instaDropListenerActive) return;
        instaDropListenerActive = false;
        instaDropListenerWorld = null;
        MeteorClient.EVENT_BUS.unsubscribe(staticInstadropListener);
    }

    public enum ChestSwapMode {
        Always,
        Never,
        WaitForGround
    }

    public enum Intelligence {
        Low(8, 20, 5.0, 25_000),
        Medium(64, 8, 3.0, 80_000),
        High(256, 6, 2.0, 200_000);

        final int pathRange;
        final int repathInterval;
        final double stuckTimeout;
        // How many cells one Autonav search may look at
        final int mapSearchNodes;

        Intelligence(int pathRange, int repathInterval, double stuckTimeout, int mapSearchNodes) {
            this.pathRange = pathRange;
            this.repathInterval = repathInterval;
            this.stuckTimeout = stuckTimeout;
            this.mapSearchNodes = mapSearchNodes;
        }
    }

    public enum MapClickButton {
        Middle(InputConstants.MOUSE_BUTTON_MIDDLE),
        Right(InputConstants.MOUSE_BUTTON_RIGHT),
        Left(InputConstants.MOUSE_BUTTON_LEFT);

        final int button;

        MapClickButton(int button) {
            this.button = button;
        }
    }

    public enum HighwayDirection {
        AwayFromCentre,
        TowardsCentre
    }

}
