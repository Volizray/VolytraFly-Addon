package com.volytrafly.hud;

import com.volytrafly.modules.movement.volytrafly.VolytraFly;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Autonav route line: a neon line from the player to the Autonav destination, following
 * the route Autonav has chosen, with arrows travelling along it towards the destination. Drawn
 * the same way as the speedometer's rings and the switches' border (three glow-bleed passes, a
 * crisp line, then a whitened highlight down the middle) in the shared primary colour, and runs
 * through the CRT effect like Future Sight does.
 */
public final class AutonavRouteVisual {
    // Look of the line, in pixels. The glow passes and alphas are the same as the neon borders'.
    private static final double LINE_THICKNESS = 2.2;
    private static final double[] GLOW = {5, 3.5, 2};
    private static final int[] GLOW_ALPHA = {14, 28, 52};
    private static final int CORE_ALPHA = 235;
    private static final double HIGHLIGHT_HALF = 0.5;
    private static final int HIGHLIGHT_ALPHA = 200;
    private static final double HIGHLIGHT_WHITEN = 0.6; // how far the highlight is pulled towards white
    private static final double MIN_POINT_GAP = 0.75; // pixels: closer points are merged
    private static final double MAX_MITER = 2.0;

    // Shaping: the route's nodes are already straightened by the planner (each straight leg between
    // two of them is known to be clear), so the line goes through every one of them and only the
    // corners are rounded off, never by more than CORNER_RADIUS blocks or CORNER_FRACTION of a leg.
    // Nodes closer together than MIN_NODE_SPACING (an unsmoothed escape route, a block per step)
    // are thinned out so those don't show as a staircase.
    private static final double MIN_NODE_SPACING = 2.5;
    private static final double CORNER_RADIUS = 3.0;
    private static final double CORNER_FRACTION = 0.4;
    private static final int CORNER_SUBDIVISIONS = 5;

    // Only the part of the line this deep in front of the camera (blocks) is drawn
    private static final double NEAR_DEPTH = 0.35;
    private static final double FAR_DEPTH = 300.0;

    // Arrows: spacing and speed along the route in blocks, how far from the player they appear,
    // half-length range in pixels, and how far from either end they fade in and out
    private static final double ARROW_SPACING = 24.0;
    private static final double ARROW_SPEED = 24.0;
    private static final double ARROW_RANGE = 220.0;
    private static final double ARROW_WORLD_SIZE = 1.5;
    private static final double ARROW_MIN_PIXELS = 4.5;
    private static final double ARROW_MAX_PIXELS = 15.0;
    private static final double ARROW_FADE_BLOCKS = 6.0;
    private static final int MAX_ARROWS = 80;

    // Ring marking the destination
    private static final double MARKER_WORLD_RADIUS = 2.5;
    private static final double MARKER_MIN_PIXELS = 6.0;
    private static final double MARKER_MAX_PIXELS = 26.0;
    private static final int MARKER_SEGMENTS = 28;

    private final CrtEffect crt = new CrtEffect();
    private final Color scratch = new Color(255, 255, 255, 255);
    private long lastFrameNanos;

    // The last route that was live, kept so the line can still be drawn through the CRT
    // power-off animation after Autonav has ended
    private final List<Vec3d> route = new ArrayList<>();
    private final List<Vec3d> fresh = new ArrayList<>();

    // This frame's world-space line (start, smoothed route, destination) and its running length
    private final List<Vec3d> poly = new ArrayList<>();
    private double[] cum = new double[0];

    // This frame's screen-space pieces of the line
    private final List<List<double[]>> strips = new ArrayList<>();

    // Camera for this frame
    private double camX, camY, camZ, fwdX, fwdY, fwdZ, rightX, rightZ;

    private final double[] screenA = new double[2];
    private final double[] screenB = new double[2];

    @SuppressWarnings("unused") // called by Meteor's event bus
    @EventHandler
    private void onRender2D(Render2DEvent event) {
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 0 : (now - lastFrameNanos) / 1.0e9;
        lastFrameNanos = now;

        VolytraFly module = Modules.get().get(VolytraFly.class);
        PlayerEntity self = mc.player;
        ClientWorld world = mc.world;
        if (module == null || self == null || world == null) return;

        boolean live = module.isActive() && module.autonavRoute.get() && module.getAutonavRoute(fresh);
        // While a new route is being worked out Autonav holds still and has nothing to follow, so
        // "fresh" is just the straight line through whatever is in the way. Keep drawing the last
        // real route until the new one arrives instead of flashing that line.
        boolean pending = live && !route.isEmpty() && module.isAutonavRoutePending();
        if (live && !pending) {
            route.clear();
            route.addAll(fresh);
        }
        crt.update(dt, live, module.crtOverlay.get());
        if (!crt.visible()) {
            // The power-off animation has finished: forget the old route entirely
            route.clear();
            return;
        }
        if (route.isEmpty()) return;

        // Camera basis, from the camera entity's view angles
        Entity cameraEntity = mc.getCameraEntity() != null ? mc.getCameraEntity() : self;
        double yaw = Math.toRadians(cameraEntity.getYaw(event.tickDelta));
        double pitch = Math.toRadians(cameraEntity.getPitch(event.tickDelta));
        Vec3d eye = cameraEntity.getCameraPosVec(event.tickDelta);
        camX = eye.x;
        camY = eye.y;
        camZ = eye.z;
        fwdX = -Math.sin(yaw) * Math.cos(pitch);
        fwdY = -Math.sin(pitch);
        fwdZ = Math.cos(yaw) * Math.cos(pitch);
        rightX = -Math.cos(yaw);
        rightZ = -Math.sin(yaw);

        // The line starts at the player (low, so it never sits on top of the view itself)
        Vec3d start = new Vec3d(
            self.lastX + (self.getX() - self.lastX) * event.tickDelta,
            self.lastY + (self.getY() - self.lastY) * event.tickDelta + 0.1,
            self.lastZ + (self.getZ() - self.lastZ) * event.tickDelta
        );
        buildPolyline(start);
        if (poly.size() < 2) return;

        buildStrips();
        if (strips.isEmpty()) return;

        // The CRT effect's box: the bounds of everything on screen, kept to the screen
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (List<double[]> strip : strips) {
            for (double[] p : strip) {
                minX = Math.min(minX, p[0]);
                minY = Math.min(minY, p[1]);
                maxX = Math.max(maxX, p[0]);
                maxY = Math.max(maxY, p[1]);
            }
        }
        double screenW = mc.getWindow().getScaledWidth();
        double screenH = mc.getWindow().getScaledHeight();
        double pad = 40;
        minX = Math.max(0, minX - pad);
        minY = Math.max(0, minY - pad);
        maxX = Math.min(screenW, maxX + pad);
        maxY = Math.min(screenH, maxY + pad);
        if (maxX - minX < 8 || maxY - minY < 8) {
            minX = 0;
            minY = 0;
            maxX = screenW;
            maxY = screenH;
        }

        Color setting = module.speedometerColor.get();
        int r = setting.r, g = setting.g, b = setting.b;

        Renderer2D.COLOR.begin();
        crt.begin(Renderer2D.COLOR::triangle, minX, minY, maxX - minX, maxY - minY);

        for (List<double[]> strip : strips) drawNeonLine(dedupe(strip), r, g, b);
        drawArrows(r, g, b, now / 1.0e9);
        drawMarker(r, g, b);

        crt.end(r, g, b);
        Renderer2D.COLOR.render();
    }

    // ---------------------------------------------------------------------------------------
    // World-space line

    /**
     * Turns player + route points + destination into one world-space polyline: straight legs
     * through the planned route's nodes with the corners rounded off, then the run to the
     * destination.
     */
    private void buildPolyline(Vec3d start) {
        poly.clear();
        Vec3d dest = route.getLast();

        // Corner points: the player, the route nodes (everything but the destination), the destination
        ArrayList<Vec3d> pts = new ArrayList<>();
        pts.add(start);
        int nodeCount = route.size() - 1;
        for (int i = 0; i < nodeCount; i++) {
            boolean last = i == nodeCount - 1;
            Vec3d node = route.get(i);
            double gap = node.distanceTo(pts.getLast());
            if (last ? gap < 0.5 : gap < MIN_NODE_SPACING) continue;
            pts.add(node);
        }
        if (pts.size() > 2 && pts.get(pts.size() - 2).distanceTo(dest) < 0.05) pts.remove(pts.size() - 2);
        pts.add(dest);

        poly.add(pts.getFirst());
        for (int i = 1; i < pts.size() - 1; i++) {
            Vec3d prev = pts.get(i - 1), corner = pts.get(i), next = pts.get(i + 1);
            double inLength = corner.distanceTo(prev), outLength = next.distanceTo(corner);
            double radius = Math.min(CORNER_RADIUS, CORNER_FRACTION * Math.min(inLength, outLength));
            if (radius < 0.05) {
                poly.add(corner);
                continue;
            }
            // A quadratic curve from just before the corner to just after it, bent towards the corner
            Vec3d a = corner.add(prev.subtract(corner).multiply(radius / inLength));
            Vec3d b = corner.add(next.subtract(corner).multiply(radius / outLength));
            for (int k = 0; k <= CORNER_SUBDIVISIONS; k++) {
                double t = (double) k / CORNER_SUBDIVISIONS, u = 1 - t;
                poly.add(a.multiply(u * u).add(corner.multiply(2 * u * t)).add(b.multiply(t * t)));
            }
        }
        poly.add(pts.getLast());
        computeLengths();
    }

    private void computeLengths() {
        if (cum.length < poly.size()) cum = new double[poly.size() * 2];
        cum[0] = 0;
        for (int i = 1; i < poly.size(); i++) cum[i] = cum[i - 1] + poly.get(i).distanceTo(poly.get(i - 1));
    }

    /** The world point this far along the polyline. */
    private Vec3d pointAt(double s) {
        int n = poly.size();
        if (s <= 0) return poly.getFirst();
        if (s >= cum[n - 1]) return poly.get(n - 1);
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (cum[mid] <= s) lo = mid;
            else hi = mid;
        }
        double span = cum[hi] - cum[lo];
        double t = span < 1.0E-9 ? 0 : (s - cum[lo]) / span;
        Vec3d a = poly.get(lo), b = poly.get(hi);
        return a.add(b.subtract(a).multiply(t));
    }

    // ---------------------------------------------------------------------------------------
    // Projection

    private double depth(Vec3d p) {
        return (p.x - camX) * fwdX + (p.y - camY) * fwdY + (p.z - camZ) * fwdZ;
    }

    /** Projects a world point to the screen; true when it can't be projected (and out is left alone). */
    private static boolean projectFails(Vec3d p, double[] out) {
        Vector3d v = new Vector3d(p.x, p.y, p.z);
        if (!NametagUtils.to2D(v, 1, false, false)) return true;
        out[0] = v.x;
        out[1] = v.y;
        return false;
    }

    /** Pixels per block at a world point, or NaN when it can't be projected. */
    private double pixelsPerBlock(Vec3d p) {
        if (projectFails(p, screenA)) return Double.NaN;
        if (projectFails(p.add(rightX, 0, rightZ), screenB)) return Double.NaN;
        return Math.hypot(screenB[0] - screenA[0], screenB[1] - screenA[1]);
    }

    /**
     * Projects the polyline into screen-space strips, cutting each segment to the depth range
     * in front of the camera that can be projected. A new strip starts wherever the line leaves
     * and re-enters that range.
     */
    private void buildStrips() {
        strips.clear();
        List<double[]> current = null;
        boolean continues = false; // the previous segment ended without being cut

        for (int i = 0; i < poly.size() - 1; i++) {
            Vec3d a = poly.get(i), b = poly.get(i + 1);
            double dA = depth(a), dB = depth(b);

            double t0, t1;
            if (Math.abs(dB - dA) < 1.0E-9) {
                if (dA < NEAR_DEPTH || dA > FAR_DEPTH) { continues = false; continue; }
                t0 = 0;
                t1 = 1;
            } else {
                double tNear = (NEAR_DEPTH - dA) / (dB - dA);
                double tFar = (FAR_DEPTH - dA) / (dB - dA);
                t0 = Math.max(0, Math.min(tNear, tFar));
                t1 = Math.min(1, Math.max(tNear, tFar));
                if (t0 >= t1) { continues = false; continue; }
            }

            Vec3d ca = t0 > 0 ? a.add(b.subtract(a).multiply(t0)) : a;
            Vec3d cb = t1 < 1 ? a.add(b.subtract(a).multiply(t1)) : b;
            double[] pa = new double[2], pb = new double[2];
            if (projectFails(ca, pa) || projectFails(cb, pb)) { continues = false; continue; }

            if (current == null || !continues || t0 > 0) {
                current = new ArrayList<>();
                strips.add(current);
                current.add(pa);
            }
            current.add(pb);
            continues = t1 >= 1;
        }

        strips.removeIf(strip -> strip.size() < 2);
    }

    // ---------------------------------------------------------------------------------------
    // Drawing

    /**
     * Strokes one screen-space strip as a neon tube: three glow-bleed passes, the crisp line,
     * then a whitened highlight down the middle.
     */
    private void drawNeonLine(List<double[]> pts, int r, int g, int b) {
        if (pts.size() < 2) return;

        double half = LINE_THICKNESS / 2;
        for (int i = 0; i < GLOW.length; i++) ribbon(pts, half + GLOW[i], r, g, b, GLOW_ALPHA[i]);
        ribbon(pts, half, r, g, b, CORE_ALPHA);
        ribbon(pts, HIGHLIGHT_HALF, whiten(r), whiten(g), whiten(b), HIGHLIGHT_ALPHA);
    }

    private static List<double[]> dedupe(List<double[]> strip) {
        ArrayList<double[]> out = new ArrayList<>(strip.size());
        for (double[] p : strip) {
            if (out.isEmpty() || Math.hypot(p[0] - out.getLast()[0], p[1] - out.getLast()[1]) >= MIN_POINT_GAP) out.add(p);
        }
        return out;
    }

    /**
     * Fills a band of the given half-width along the points, with mitred joints so consecutive
     * pieces share their edges: translucent glow passes never overlap themselves into bright
     * beads at the joints.
     */
    private void ribbon(List<double[]> pts, double half, int r, int g, int b, int a) {
        int n = pts.size();
        double[] lx = new double[n], ly = new double[n], rx = new double[n], ry = new double[n];

        for (int i = 0; i < n; i++) {
            double[] p = pts.get(i);
            double nx0 = 0, ny0 = 0, nx1 = 0, ny1 = 0;
            boolean hasPrev = i > 0, hasNext = i < n - 1;
            if (hasPrev) {
                double[] q = pts.get(i - 1);
                double dx = p[0] - q[0], dy = p[1] - q[1];
                double len = Math.hypot(dx, dy);
                nx0 = -dy / len;
                ny0 = dx / len;
            }
            if (hasNext) {
                double[] q = pts.get(i + 1);
                double dx = q[0] - p[0], dy = q[1] - p[1];
                double len = Math.hypot(dx, dy);
                nx1 = -dy / len;
                ny1 = dx / len;
            }
            double nx, ny, scale = 1;
            if (hasPrev && hasNext) {
                double mx = nx0 + nx1, my = ny0 + ny1;
                double mlen = Math.hypot(mx, my);
                if (mlen < 1.0E-6) {
                    nx = nx1;
                    ny = ny1;
                } else {
                    nx = mx / mlen;
                    ny = my / mlen;
                    scale = Math.min(MAX_MITER, 1.0 / Math.max(0.5, nx * nx0 + ny * ny0));
                }
            } else if (hasNext) {
                nx = nx1;
                ny = ny1;
            } else {
                nx = nx0;
                ny = ny0;
            }
            lx[i] = p[0] + nx * half * scale;
            ly[i] = p[1] + ny * half * scale;
            rx[i] = p[0] - nx * half * scale;
            ry[i] = p[1] - ny * half * scale;
        }

        scratch.set(r, g, b, a);
        for (int i = 0; i < n - 1; i++) {
            crt.triangle(lx[i], ly[i], rx[i], ry[i], rx[i + 1], ry[i + 1], scratch);
            crt.triangle(lx[i], ly[i], rx[i + 1], ry[i + 1], lx[i + 1], ly[i + 1], scratch);
        }
    }

    /**
     * Chevrons spaced along the route that slide towards the destination. They are measured
     * back from the destination, so they stay put on the route as the player moves along it, and
     * fade in near the player and out as they reach the destination.
     */
    private void drawArrows(int r, int g, int b, double timeSeconds) {
        double length = cum[poly.size() - 1];
        if (length < 2) return;

        double phase = (timeSeconds * ARROW_SPEED) % ARROW_SPACING;
        // Arrow k sits (k * spacing - phase) blocks short of the destination
        int kMin = Math.max(0, (int) Math.ceil((length - ARROW_RANGE + phase) / ARROW_SPACING));
        int kMax = (int) Math.floor((length + phase) / ARROW_SPACING);
        if (kMax - kMin + 1 > MAX_ARROWS) kMin = kMax - MAX_ARROWS + 1;

        for (int k = kMin; k <= kMax; k++) {
            double fromDest = k * ARROW_SPACING - phase;
            if (fromDest < 0) continue;
            double s = length - fromDest;
            if (s < 0) continue;

            double fade = Math.min(1, Math.min(s / ARROW_FADE_BLOCKS, fromDest / ARROW_FADE_BLOCKS));
            if (fade <= 0.02) continue;

            Vec3d here = pointAt(s);
            Vec3d next = pointAt(Math.min(length, s + ARROW_WORLD_SIZE));
            if (depth(here) < NEAR_DEPTH + 0.5 || depth(here) > FAR_DEPTH || depth(next) < NEAR_DEPTH) continue;

            double ppb = pixelsPerBlock(here);
            if (Double.isNaN(ppb)) continue;
            double[] p = {screenA[0], screenA[1]};
            if (projectFails(next, screenB)) continue;

            double dx = screenB[0] - p[0], dy = screenB[1] - p[1];
            double len = Math.hypot(dx, dy);
            if (len < 0.25) continue;
            dx /= len;
            dy /= len;

            double size = Math.max(ARROW_MIN_PIXELS, Math.min(ARROW_MAX_PIXELS, ppb * ARROW_WORLD_SIZE));
            drawChevron(p[0], p[1], dx, dy, size, r, g, b, fade);
        }
    }

    /** One filled chevron centred on (cx, cy) pointing along (dx, dy), with the neon layering. */
    private void drawChevron(double cx, double cy, double dx, double dy, double size, int r, int g, int b, double fade) {
        for (int i = 0; i < GLOW.length; i++) {
            chevron(cx, cy, dx, dy, size + GLOW[i], r, g, b, (int) Math.round(GLOW_ALPHA[i] * fade));
        }
        chevron(cx, cy, dx, dy, size, r, g, b, (int) Math.round(CORE_ALPHA * fade));
        chevron(cx, cy, dx, dy, size * 0.45, whiten(r), whiten(g), whiten(b),
            (int) Math.round(HIGHLIGHT_ALPHA * fade));
    }

    private void chevron(double cx, double cy, double dx, double dy, double size, int r, int g, int b, int a) {
        if (a <= 0) return;
        double px = -dy;

        double tipX = cx + dx * size, tipY = cy + dy * size;
        double leftX = cx - dx * size * 0.7 + px * size * 0.85, leftY = cy - dy * size * 0.7 + dx * size * 0.85;
        double wingX = cx - dx * size * 0.7 - px * size * 0.85, wingY = cy - dy * size * 0.7 - dx * size * 0.85;
        double notchX = cx - dx * size * 0.1, notchY = cy - dy * size * 0.1;

        scratch.set(r, g, b, a);
        crt.triangle(tipX, tipY, leftX, leftY, notchX, notchY, scratch);
        crt.triangle(tipX, tipY, notchX, notchY, wingX, wingY, scratch);
    }

    /** A neon ring on the destination, when it is within range and in front of the camera. */
    private void drawMarker(int r, int g, int b) {
        Vec3d dest = poly.getLast();
        double d = depth(dest);
        if (d < NEAR_DEPTH || d > FAR_DEPTH) return;

        double ppb = pixelsPerBlock(dest);
        if (Double.isNaN(ppb)) return;
        double cx = screenA[0], cy = screenA[1];
        double radius = Math.max(MARKER_MIN_PIXELS, Math.min(MARKER_MAX_PIXELS, ppb * MARKER_WORLD_RADIUS));

        ArrayList<double[]> ring = new ArrayList<>();
        for (int i = 0; i <= MARKER_SEGMENTS; i++) {
            double angle = 2 * Math.PI * i / MARKER_SEGMENTS;
            ring.add(new double[]{cx + Math.cos(angle) * radius, cy + Math.sin(angle) * radius});
        }
        drawNeonLine(ring, r, g, b);
    }

    /** A colour channel pulled towards white by the highlight amount. */
    private static int whiten(int channel) {
        return (int) Math.round(channel + (255 - channel) * HIGHLIGHT_WHITEN);
    }
}
