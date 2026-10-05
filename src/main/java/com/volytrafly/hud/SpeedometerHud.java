package com.volytrafly.hud;

import com.volytrafly.VolytraFlyAddon;
import com.volytrafly.modules.movement.volytrafly.VolytraFly;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.hud.XAnchor;
import meteordevelopment.meteorclient.systems.hud.YAnchor;
import meteordevelopment.meteorclient.systems.hud.screens.HudEditorScreen;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.world.entity.Entity;
import java.util.Locale;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * A speedometer with a transparent background. The dial runs from 0 up to
 * VolytraFly's maximum-horizontal-speed and the needle follows the player's real horizontal
 * speed, all in blocks per second. It is a normal Meteor HUD element, so it is moved (and anchored) in Meteor's HUD editor.
 * <p>
 * It only draws while VolytraFly is on. In the HUD editor it is always
 * drawn (with a sample reading) so it can be positioned.
 */
public class SpeedometerHud extends HudElement {
    public static final HudElementInfo<SpeedometerHud> INFO = new HudElementInfo<>(
        VolytraFlyAddon.HUD_GROUP,
        "volytrafly-speedometer",
        "Speedometer for VolytraFly. Shows your horizontal speed in blocks per second, up to maximum-horizontal-speed.",
        SpeedometerHud::new
    );

    // Size of the element in pixels at speedometer-scale 1. Package-visible so ModeLightsHud can
    // work out where the speedometer sits by default, to position itself above it.
    static final double BASE_SIZE = 190;

    // The dial ring's outer edge sits DIAL_INSET pixels (at scale 1) inside the element, so its
    // radius is DIAL_RADIUS pixels at scale 1.
    static final double DIAL_INSET = 10;
    static final double DIAL_RADIUS = BASE_SIZE / 2 - DIAL_INSET;

    // The module's speeds are in blocks per tick; the dial shows blocks per second.
    private static final double TICKS_PER_SECOND = 20;

    // The dial spans 270 degrees, from bottom-left round the top to bottom-right (screen space, y down).
    private static final double START_ANGLE = Math.toRadians(135);
    private static final double SWEEP = Math.toRadians(270);
    private static final double SEGMENT_STEP = Math.toRadians(4);

    // Reading shown in the HUD editor when the speedometer isn't live.
    private static final double PREVIEW_FRACTION = 0.62;

    // The green the dial fades into (the module's green-color setting).
    private int maxR = 0, maxG = 255, maxB = 70;

    private final Color scratch = new Color(255, 255, 255, 255);

    // Old-CRT power-on / power-off animation.
    private final CrtEffect crt = new CrtEffect();

    // Smoothed value the needle is drawn at, in blocks per second.
    private double shownSpeed;

    // Real horizontal speed in blocks per tick, measured from how far the viewed entity moved
    // since the last tick.
    private double measuredSpeed;
    private Entity lastEntity;
    private double lastX, lastZ;

    // Rubberband handling: see tick().
    private static final int IGNORE_TICKS_AFTER_CORRECTION = 3;
    private static final double MIN_PLAUSIBLE_BLOCKS_PER_TICK = 8;
    private int lastCorrections;
    private int ignoreTicks;

    public SpeedometerHud() {
        super(INFO);
    }

    /**
     * Makes sure a speedometer exists in the HUD (bottom right, just above Meteor's coordinate
     * text) and that Meteor's HUD is switched on, so the Visuals toggle actually shows something.
     */
    public static void ensureAdded() {
        if (HudLayout.alreadyAdded(SpeedometerHud.class)) return;

        Hud hud = Hud.get();

        int textHeight = (int) Math.ceil(HudRenderer.INSTANCE.textHeight(true));
        hud.add(INFO, -4, -(8 + textHeight * 3), XAnchor.Right, YAnchor.Bottom);
    }

    /**
     * Measures the speed of whatever the camera is following. That is the player when playing normally
     */
    @Override
    public void tick(HudRenderer renderer) {
        Entity entity = mc.getCameraEntity();
        if (entity == null) entity = mc.player;

        if (entity == null) {
            lastEntity = null;
            measuredSpeed = 0;
            return;
        }

        double px = entity.getX();
        double pz = entity.getZ();

        VolytraFly module = Modules.get().get(VolytraFly.class);

        // A rubberband moves you in one jump, which would read as an enormous speed for a tick.
        // The correction packet arrives slightly before the move is applied, so ignore the next
        // few ticks' movement after one, holding the last reading.
        if (module != null) {
            int corrections = module.getPositionCorrections();
            if (corrections != lastCorrections) {
                lastCorrections = corrections;
                ignoreTicks = IGNORE_TICKS_AFTER_CORRECTION;
            }
        }

        if (entity != lastEntity) {
            // A different entity (or the first tick) has nothing to compare against yet.
            measuredSpeed = 0;
        } else {
            double moved = Math.hypot(px - lastX, pz - lastZ);

            // Any other teleport-sized jump (a sudden move no real flight could make) is held
            // the same way. The limit scales with the module's own top speed.
            double maxPlausible = Math.max(MIN_PLAUSIBLE_BLOCKS_PER_TICK,
                (module != null ? module.horizontalSpeed.get() : 15) * 2);

            if (ignoreTicks > 0) {
                ignoreTicks--;
            } else if (moved <= maxPlausible) {
                measuredSpeed = moved;
            }
        }

        lastEntity = entity;
        lastX = px;
        lastZ = pz;
    }

    @Override
    public void render(HudRenderer renderer) {
        VolytraFly module = Modules.get().get(VolytraFly.class);
        double scale = module != null ? module.speedometerScale.get() : HudLayout.defaultSpeedometerScale();
        double size = BASE_SIZE * scale;

        if (module != null) {
            Color green = module.greenColor.get();
            maxR = green.r;
            maxG = green.g;
            maxB = green.b;
        }

        // Always set the size, so the element can be selected and dragged in the HUD editor.
        setSize(size, size);
        HudLayout.reportSpeedometer(x, y, scale);

        // Shown while VolytraFly is on
        boolean live = module != null && module.speedometer.get() && (module.isActive() || HudLayout.inFlashbackReplay());
        boolean editor = HudEditorScreen.isOpen();
        crt.update(renderer.delta, live || editor, module == null || module.crtOverlay.get());
        if (!crt.visible()) return;
        crt.begin(renderer, x, y, size, size);

        // While the turn-off animation plays the module is already off, but the panel should keep
        // showing its live reading as it collapses rather than jumping to the editor's sample one.
        boolean dataLive = live || !editor;

        // Everything on the dial is in blocks per second (the module's speeds are per tick).
        double max = (module != null ? module.horizontalSpeed.get() : 15) * TICKS_PER_SECOND;
        if (max < 0.001) max = 0.001;

        double target = dataLive ? measuredSpeed * TICKS_PER_SECOND : max * PREVIEW_FRACTION;
        double dt = Math.min(0.25, Math.max(0, renderer.delta));
        shownSpeed += (target - shownSpeed) * (1 - Math.exp(-dt * 14));

        double fraction = Math.max(0, Math.min(1, shownSpeed / max));

        Color base = HudLayout.baseColor(module);
        int baseR = base.r, baseG = base.g, baseB = base.b;

        double cx = x + size / 2;
        double cy = y + size / 2;
        double outer = DIAL_RADIUS * scale;   // outer edge of the dial ring
        double ringThickness = 5 * scale;
        double ringMid = outer - ringThickness / 2;

        // Dial track (dim), with the last quarter tinted green.
        band(cx, cy, outer - ringThickness, outer, 1, baseR, baseG, baseB, 42);

        // Tick marks: 10 intervals, a longer tick and a label every second one. While drawing the
        // labels, work out how far in they reach, so the inner circle can sit clear of them.
        double tickOuter = outer - ringThickness - 3 * scale;
        double labelRadius = tickOuter - 8 * scale - 9 * scale;
        double labelInnerEdge = labelRadius;
        for (int i = 0; i <= 10; i++) {
            double f = i / 10.0;
            double angle = START_ANGLE + SWEEP * f;
            boolean major = i % 2 == 0;
            double len = (major ? 8 : 5) * scale;
            double half = Math.toRadians(major ? 0.9 : 0.55);
            int[] c = zoneColor(f, baseR, baseG, baseB);
            boolean lit = f <= fraction + 1e-6;
            sector(cx, cy, tickOuter - len, tickOuter, angle - half, angle + half, c[0], c[1], c[2], lit ? 230 : 110);

            if (major) {
                String label = formatSpeed(max * f, max);
                double labelScale = 0.42 * scale;
                double w = renderer.textWidth(label, false, labelScale);
                double h = renderer.textHeight(false, labelScale);
                setColor(c[0], c[1], c[2], lit ? 235 : 150);
                crtText(renderer, label, cx + Math.cos(angle) * labelRadius - w / 2, cy + Math.sin(angle) * labelRadius - h / 2, scratch, false, labelScale);

                // How far the label's box reaches towards the centre, along the line to the centre.
                double reach = (w * Math.abs(Math.cos(angle)) + h * Math.abs(Math.sin(angle))) / 2;
                labelInnerEdge = Math.min(labelInnerEdge, labelRadius - reach);
            }
        }

        // Filled neon arc with a glow.
        if (fraction > 0.002) {
            double[] extra = {9 * scale, 6 * scale, 3.5 * scale};
            int[] glowAlpha = {14, 28, 52};
            for (int i = 0; i < extra.length; i++) {
                band(cx, cy, ringMid - ringThickness / 2 - extra[i], ringMid + ringThickness / 2 + extra[i], fraction, baseR, baseG, baseB, glowAlpha[i]);
            }
            band(cx, cy, ringMid - ringThickness / 2, ringMid + ringThickness / 2, fraction, baseR, baseG, baseB, 255);
            // Bright core down the middle for the neon-tube look.
            band(cx, cy, ringMid - 0.9 * scale, ringMid + 0.9 * scale, fraction, baseR, baseG, baseB, 210, 0.6);
        }

        // Dial: a neon circle round the centre, inside the numbers. Its glow stops short of the
        // labels' inner edge.
        double dialThickness = 2 * scale;
        double[] dialGlow = {5 * scale, 3.5 * scale, 2 * scale};
        double dialRadius = labelInnerEdge - dialThickness / 2 - dialGlow[0] - 1.5 * scale;
        // Safety net in case the text measurements come back odd: still draw a sensible circle.
        if (!(dialRadius > 10 * scale)) dialRadius = outer * 0.55;
        int[] dialGlowAlpha = {14, 28, 52};
        for (int i = 0; i < dialGlow.length; i++) {
            sector(cx, cy, dialRadius - dialThickness / 2 - dialGlow[i], dialRadius + dialThickness / 2 + dialGlow[i],
                0, Math.PI * 2, baseR, baseG, baseB, dialGlowAlpha[i]);
        }
        sector(cx, cy, dialRadius - dialThickness / 2, dialRadius + dialThickness / 2, 0, Math.PI * 2, baseR, baseG, baseB, 235);
        sector(cx, cy, dialRadius - 0.4 * scale, dialRadius + 0.4 * scale, 0, Math.PI * 2,
            mix(baseR, 255, 0.6), mix(baseG, 255, 0.6), mix(baseB, 255, 0.6), 200);

        // Needle
        double needleAngle = START_ANGLE + SWEEP * fraction;
        int[] needle = zoneColor(fraction, baseR, baseG, baseB);
        double needleLength = outer - ringThickness - 6 * scale;
        needle(cx, cy, needleAngle, needleLength, 10 * scale, 4.2 * scale, needle, 38, 0);
        needle(cx, cy, needleAngle, needleLength, 10 * scale, 2.4 * scale, needle, 90, 0);
        needle(cx, cy, needleAngle, needleLength, 10 * scale, 1.4 * scale, needle, 255, 0.35);

        // Hub
        sector(cx, cy, 0, 6.5 * scale, 0, Math.PI * 2, 0, 0, 0, 120);
        sector(cx, cy, 4.6 * scale, 6.5 * scale, 0, Math.PI * 2, needle[0], needle[1], needle[2], 255);

        // Digital readout: the speed with "Blocks Per Second" under it, placed in the open gap at the
        // bottom of the dial, below the inner circle (and its glow) so the two never overlap.
        String reading = formatSpeed(shownSpeed, max);
        double readingScale = 0.95 * scale;
        double rw = renderer.textWidth(reading, true, readingScale);
        double rh = renderer.textHeight(true, readingScale);
        double readingY = cy + dialRadius + dialThickness / 2 + dialGlow[0] + 4 * scale;
        setColor(mix(needle[0], 255, 0.55), mix(needle[1], 255, 0.55), mix(needle[2], 255, 0.55), 255);
        crtText(renderer, reading, cx - rw / 2, readingY, scratch, true, readingScale);

        String unit = "Blocks Per Second";
        double unitScale = 0.4 * scale;
        double uw = renderer.textWidth(unit, false, unitScale);
        setColor(needle[0], needle[1], needle[2], 200);
        crtText(renderer, unit, cx - uw / 2, readingY + rh + 1 * scale, scratch, false, unitScale);
        HudLayout.reportSpeedometerUnitY(readingY + rh + 1 * scale);

        crt.end(baseR, baseG, baseB);
    }

    // Drawing helpers

    /**
     * Draws part of the dial ring, from the start of the sweep to fraction f1, in small segments so the
     * colour can blend into the green zone. whiten mixes the colour towards white (0 = none).
     */
    private void band(double cx, double cy, double r0, double r1, double f1,
                      int baseR, int baseG, int baseB, int alpha) {
        band(cx, cy, r0, r1, f1, baseR, baseG, baseB, alpha, 0);
    }

    private void band(double cx, double cy, double r0, double r1, double f1,
                      int baseR, int baseG, int baseB, int alpha, double whiten) {
        double fStep = SEGMENT_STEP / SWEEP;
        int steps = Math.max(1, (int) Math.ceil(f1 / fStep));
        for (int i = 0; i < steps; i++) {
            double a = f1 * i / steps;
            double b = f1 * (i + 1) / steps;
            int[] c = zoneColor((a + b) / 2, baseR, baseG, baseB);
            sector(cx, cy, r0, r1, START_ANGLE + SWEEP * a, START_ANGLE + SWEEP * b,
                mix(c[0], 255, whiten), mix(c[1], 255, whiten), mix(c[2], 255, whiten), alpha);
        }
    }

    /**
     * Colour at a point along the dial: the base colour, blending into green over the last quarter.
     */
    private int[] zoneColor(double fraction, int baseR, int baseG, int baseB) {
        double t = Math.max(0, Math.min(1, (fraction - 0.75) / 0.25));
        return new int[]{mix(baseR, maxR, t), mix(baseG, maxG, t), mix(baseB, maxB, t)};
    }

    private static int mix(int from, int to, double t) {
        return (int) Math.round(from + (to - from) * t);
    }

    /**
     * A ring sector between radius r0 and r1 and angle a0 to a1 (radians, increasing = clockwise on screen).
     */
    private void sector(double cx, double cy, double r0, double r1, double a0, double a1,
                        int r, int g, int b, int a) {
        setColor(r, g, b, a);

        int steps = Math.max(1, (int) Math.ceil(Math.abs(a1 - a0) / SEGMENT_STEP));
        double px = Math.cos(a0), py = Math.sin(a0);

        for (int i = 1; i <= steps; i++) {
            double angle = a0 + (a1 - a0) * i / steps;
            double nx = Math.cos(angle), ny = Math.sin(angle);

            tri(cx + px * r0, cy + py * r0, cx + px * r1, cy + py * r1, cx + nx * r1, cy + ny * r1);
            tri(cx + px * r0, cy + py * r0, cx + nx * r1, cy + ny * r1, cx + nx * r0, cy + ny * r0);

            px = nx;
            py = ny;
        }
    }

    /**
     * Draws a needle pointing along angle, from a short tail behind the centre out to length.
     */
    private void needle(double cx, double cy, double angle, double length, double tail,
                        double width, int[] color, int alpha, double whiten) {
        double dx = Math.cos(angle), dy = Math.sin(angle);
        double perpX = -dy;
        double half = width / 2;

        setColor(mix(color[0], 255, whiten), mix(color[1], 255, whiten), mix(color[2], 255, whiten), alpha);

        double tipX = cx + dx * length, tipY = cy + dy * length;
        double tailX = cx - dx * tail, tailY = cy - dy * tail;

        tri(tipX, tipY, cx + perpX * half, cy + dx * half, cx - perpX * half, cy - dx * half);
        tri(tailX, tailY, cx + perpX * half, cy + dx * half, cx - perpX * half, cy - dx * half);
    }

    /**
     * Draws a triangle in the scratch colour, always wound the same way round as Meteor's own
     * quads (top-left, bottom-left, bottom-right, top-right on screen), whatever order the points
     * were given in
     */
    private void tri(double x1, double y1, double x2, double y2, double x3, double y3) {
        // CRT effect: squash, fade, and add scanlines / phosphor stripes to this shape only.
        crt.triangle(x1, y1, x2, y2, x3, y3, scratch);
    }

    /**
     * Draws text through the CRT effect. Text can't be stretched, so it is shrunk by the vertical
     * squash around its own centre and faded in and out with the animation.
     */
    private void crtText(HudRenderer renderer, String text, double tx, double ty, Color color, boolean shadow, double scale) {
        double a = crt.alpha();
        double squash = crt.scaleY();
        if (a <= 0.02 || squash < 0.25) return;

        double w = renderer.textWidth(text, shadow, scale);
        double h = renderer.textHeight(shadow, scale);
        double centerX = crt.mapX(tx + w / 2, ty + h / 2);
        double centerY = crt.mapY(ty + h / 2);

        double s = scale * squash;
        double w2 = renderer.textWidth(text, shadow, s);
        double h2 = renderer.textHeight(shadow, s);

        int oldAlpha = color.a;
        color.a = (int) Math.round(oldAlpha * a);
        renderer.text(text, centerX - w2 / 2, centerY - h2 / 2, color, shadow, s);
        color.a = oldAlpha;
    }

    private void setColor(int r, int g, int b, int a) {
        scratch.set(r, g, b, a);
    }

    /**
     * Whole numbers on a big dial, one decimal on a small one.
     */
    private static String formatSpeed(double value, double max) {
        if (max >= 10) return String.valueOf(Math.round(value));
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
