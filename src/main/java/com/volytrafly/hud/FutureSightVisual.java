package com.volytrafly.hud;

import com.volytrafly.modules.movement.volytrafly.VolytraFly;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Future Sight: draws a rotating, pulsing target ring over the spot each nearby player is expected
 * to reach one second from now
 */
public final class FutureSightVisual {
    private static final int CIRCLE_SEGMENTS = 28;
    private static final double STUB_FRACTION = 0.55;       // stub length as a fraction of the radius
    private static final int LOOKAHEAD_TICKS = 20;          // 1 second
    private static final double VELOCITY_SMOOTHING = 0.4;   // weight of the newest tick's movement
    private static final double MIN_LINE_PIXELS = 2.0;
    // Vanilla player fall physics, applied once per tick: vy = (vy - GRAVITY) * DRAG.
    private static final double GRAVITY = 0.08;
    private static final double VERTICAL_DRAG = 0.98;

    // Each player's recent movement per tick, smoothed. Other players' positions only update when
    // the server sends them, so raw tick-to-tick movement flickers between zero and a double step.
    private final Map<UUID, Vec3> velocity = new HashMap<>();

    // Where each player is expected to have moved to one second from now, relative to where they
    // are this tick. Worked out once per tick (not per frame) because airborne players are stepped
    // through gravity and block collisions.
    private final Map<UUID, Vec3> predictedOffset = new HashMap<>();

    // Fixed look of the rings: range in blocks, ring size multiplier, line thickness and dash
    // length in pixels, rotation in degrees per second, pulse period in seconds.
    private static final double RANGE = 256.0;
    private static final double SIZE_MULTIPLIER = 1.3;
    private static final double THICKNESS = 3.0;
    private static final double DASH_LENGTH = 8.0;
    private static final double ROTATION_SPEED = 90.0;
    private static final double PULSE_PERIOD = 3.0;

    // One shared power on/off timeline; every ring is positioned around its own box each frame.
    private final CrtEffect crt = new CrtEffect();
    private final Color scratch = new Color(255, 255, 255, 255);
    private long lastFrameNanos;

    /**
     * True while Future Sight should be on: the module is running and the setting is enabled.
     */
    private static boolean live(VolytraFly module) {
        return module != null && module.isActive() && module.futureSight.get();
    }

    /**
     * Tracks how far every other player moves per tick, smoothed over a few ticks. Keeps running
     * while the power-off animation plays so the rings collapse where the players really are.
     */
    @SuppressWarnings("unused") // called by Meteor's event bus
    @EventHandler
    private void onTick(TickEvent.Post event) {
        VolytraFly module = Modules.get().get(VolytraFly.class);
        ClientLevel world = mc.level;
        if (world == null || !(live(module) || crt.visible())) {
            velocity.clear();
            predictedOffset.clear();
            return;
        }

        Set<UUID> present = new HashSet<>();
        for (Player player : world.players()) {
            if (player == mc.player) continue;
            UUID id = player.getUUID();
            present.add(id);

            Vec3 moved = new Vec3(player.getX() - player.xo, player.getY() - player.yo, player.getZ() - player.zo);
            Vec3 smoothed = velocity.compute(id, (key, previous) -> previous == null
                ? moved
                : previous.scale(1 - VELOCITY_SMOOTHING).add(moved.scale(VELOCITY_SMOOTHING)));
            predictedOffset.put(id, predictOffset(world, player, smoothed));
        }
        velocity.keySet().retainAll(present);
        predictedOffset.keySet().retainAll(present);
    }

    /**
     * How far the player is expected to move over the look-ahead, given their smoothed per-tick
     * movement. Players gliding on an elytra, on the ground, swimming or climbing just keep going
     * in a straight line. Players who are airborne without an elytra (jumping or falling) are
     * stepped tick by tick with vanilla gravity, keeping their horizontal movement, and stop
     * against the floor, ceiling or walls instead of passing through them.
     */
    private Vec3 predictOffset(ClientLevel world, Player player, Vec3 perTick) {
        boolean falling = !player.onGround() && !player.isFallFlying() && !player.isInWater()
            && !player.isInLava() && !player.onClimbable() && !player.isNoGravity();
        if (!falling) return perTick.scale(LOOKAHEAD_TICKS);

        AABB box = player.getBoundingBox();
        double vx = perTick.x(), vy = perTick.y(), vz = perTick.z();
        double dx = 0, dy = 0, dz = 0;

        for (int i = 0; i < LOOKAHEAD_TICKS; i++) {
            // Gravity acts on last tick's vertical movement to give this tick's.
            vy = (vy - GRAVITY) * VERTICAL_DRAG;

            // Horizontal, one axis at a time so the player slides along walls.
            if (vx != 0) {
                if (world.noCollision(player, box.move(dx + vx, dy, dz))) dx += vx;
                else vx = 0;
            }
            if (vz != 0) {
                if (world.noCollision(player, box.move(dx, dy, dz + vz))) dz += vz;
                else vz = 0;
            }

            // Vertical: landing on the floor or bumping a ceiling stops the vertical movement.
            if (world.noCollision(player, box.move(dx, dy + vy, dz))) dy += vy;
            else vy = 0;
        }

        return new Vec3(dx, dy, dz);
    }

    @SuppressWarnings("unused") // called by Meteor's event bus
    @EventHandler
    private void onRender2D(Render2DEvent event) {
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 0 : (now - lastFrameNanos) / 1.0e9;
        lastFrameNanos = now;

        VolytraFly module = Modules.get().get(VolytraFly.class);
        if (module == null) return;

        crt.update(dt, live(module), module.crtOverlay.get());
        if (!crt.visible()) return;

        Player self = mc.player;
        ClientLevel world = mc.level;
        if (self == null || world == null) return;

        double rangeSq = RANGE * RANGE;
        // Same neon colour as the speedometer and switches, so every VolytraFly visual matches.
        // Copied so the CRT effect's temporary alpha changes never touch the setting itself.
        Color setting = module.speedometerColor.get();
        scratch.set(setting.r, setting.g, setting.b, setting.a);
        double thickness = THICKNESS;

        double nowSeconds = now / 1_000_000_000.0;
        double rotation = (nowSeconds * ROTATION_SPEED) % 360.0;
        // Oscillates between -12.5% and +12.5% of the base size - a 25% swing peak-to-trough.
        double pulse = 1 + 0.125 * Math.sin((2 * Math.PI * nowSeconds) / PULSE_PERIOD);

        // The camera's true horizontal right vector, so the body-width sample below is taken
        // side-to-side as the camera actually sees it, not along a fixed world axis.
        // (26.2 removed GameRenderer#getMainCamera, so the camera entity's view yaw is used.)
        Entity cameraEntity = mc.getCameraEntity() != null ? mc.getCameraEntity() : self;
        double yawRad = Math.toRadians(cameraEntity.getViewYRot(event.tickDelta));
        double rightX = -Math.cos(yawRad);
        double rightZ = -Math.sin(yawRad);

        CrtEffect.Sink sink = Renderer2D.COLOR::triangle;
        Renderer2D.COLOR.begin();

        for (Player player : world.players()) {
            if (player == self) continue;
            if (module.futureSightIgnoreFriends.get() && Friends.get().isFriend(player)) continue;
            if (self.position().distanceToSqr(player.position()) > rangeSq) continue;

            double x = player.xo + (player.getX() - player.xo) * event.tickDelta;
            double y = player.yo + (player.getY() - player.yo) * event.tickDelta;
            double z = player.zo + (player.getZ() - player.zo) * event.tickDelta;
            y += player.getBbHeight() / 2.0;

            Vec3 offset = predictedOffset.getOrDefault(player.getUUID(), Vec3.ZERO);
            double futureX = x + offset.x();
            double futureY = y + offset.y();
            double futureZ = z + offset.z();

            Vector3d ringCentre = new Vector3d(futureX, futureY, futureZ);
            if (!NametagUtils.to2D(ringCentre, 1, false, false)) continue;

            // Project a second point one half body-width to the camera's side of the predicted
            // spot, so the pixel gap tells us how big a player would look standing there.
            double halfWidth = player.getBbWidth() / 2.0;
            Vector3d edge = new Vector3d(futureX + rightX * halfWidth, futureY, futureZ + rightZ * halfWidth);
            if (!NametagUtils.to2D(edge, 1, false, false)) continue;

            double bodyPixelRadius = Math.hypot(edge.x - ringCentre.x, edge.y - ringCentre.y);
            double radius = bodyPixelRadius * SIZE_MULTIPLIER * pulse;

            // Each ring is its own little CRT screen, centred on the ring and just big enough to
            // hold the circle and its stubs: the power-on dot, line and unfold happen around it.
            double half = radius * (1 + STUB_FRACTION) + thickness;
            crt.begin(sink, ringCentre.x - half, ringCentre.y - half, half * 2, half * 2);

            // Dashed line from the player's own on-screen position to the ring's edge. Skipped
            // when the player is standing still (ring sits on them) or the line would be tiny.
            Vector3d playerScreen = new Vector3d(x, y, z);
            if (NametagUtils.to2D(playerScreen, 1, false, false)) {
                double dx = playerScreen.x - ringCentre.x;
                double dy = playerScreen.y - ringCentre.y;
                double distance = Math.hypot(dx, dy);
                if (distance - radius > MIN_LINE_PIXELS) {
                    double ux = dx / distance, uy = dy / distance;
                    drawDashedLine(
                        playerScreen.x, playerScreen.y,
                        ringCentre.x + ux * radius, ringCentre.y + uy * radius,
                        thickness
                    );
                }
            }

            drawRing(ringCentre.x, ringCentre.y, radius, rotation, thickness);
            crt.end(scratch.r, scratch.g, scratch.b);
        }

        Renderer2D.COLOR.render();
    }

    /**
     * Draws one ring at screen position (cx, cy): a circle plus four short outward lines at
     * 0/90/180/270 degrees in the rotating frame, so they sweep around the circle as it spins.
     * Each stub starts exactly on the circle's edge and runs outward - nothing is ever drawn
     * inside the circle. Everything is drawn as filled quads (not GL lines) so thickness can be
     * controlled precisely.
     */
    private void drawRing(double cx, double cy, double radius, double rotationDegrees, double thickness) {
        double rot = Math.toRadians(rotationDegrees);

        double prevX = 0, prevY = 0;
        for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
            double angle = rot + (2 * Math.PI * i) / CIRCLE_SEGMENTS;
            double px = cx + Math.cos(angle) * radius;
            double py = cy + Math.sin(angle) * radius;

            if (i > 0) thickLine(prevX, prevY, px, py, thickness);
            prevX = px;
            prevY = py;
        }

        double stubLength = radius * STUB_FRACTION;
        for (int i = 0; i < 4; i++) {
            double angle = rot + (Math.PI / 2) * i;
            double dx = Math.cos(angle), dy = Math.sin(angle);

            thickLine(
                cx + dx * radius, cy + dy * radius,
                cx + dx * (radius + stubLength), cy + dy * (radius + stubLength),
                thickness
            );
        }
    }

    /**
     * Draws a dashed line from (x1, y1) to (x2, y2): dashes of DASH_LENGTH pixels separated by
     * gaps of the same length, starting with a dash at (x1, y1). The final dash is clipped to
     * end exactly at (x2, y2).
     */
    private void drawDashedLine(double x1, double y1, double x2, double y2, double thickness) {
        double dx = x2 - x1, dy = y2 - y1;
        double length = Math.hypot(dx, dy);
        if (length < 1.0e-6) return;

        double ux = dx / length, uy = dy / length;
        for (double start = 0; start < length; start += DASH_LENGTH * 2) {
            double end = Math.min(start + DASH_LENGTH, length);
            thickLine(x1 + ux * start, y1 + uy * start, x1 + ux * end, y1 + uy * end, thickness);
        }
    }

    /**
     * Draws a line as a thickness-wide filled rectangle (two triangles) instead of a 1-pixel GL
     * line, since Meteor's 2D line pipeline has no width control of its own. Both triangles go
     * through the CRT effect, which also keeps them wound the way Meteor's 2D pipeline expects
     * (it culls the other winding).
     */
    private void thickLine(double x1, double y1, double x2, double y2, double thickness) {
        double dx = x2 - x1, dy = y2 - y1;
        double length = Math.hypot(dx, dy);
        if (length < 1.0e-6) return;

        double half = thickness / 2.0;
        double perpX = -dy / length * half;
        double perpY = dx / length * half;

        crt.triangle(x1 + perpX, y1 + perpY, x2 + perpX, y2 + perpY, x2 - perpX, y2 - perpY, scratch);
        crt.triangle(x1 + perpX, y1 + perpY, x2 - perpX, y2 - perpY, x1 - perpX, y1 - perpY, scratch);
    }
}
