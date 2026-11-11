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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * A "fuel gauge", in the same visual style as {@link SpeedometerHud}, showing how much
 * durability is left on the worn elytra
 */
public class ElytraFuelHud extends HudElement {
    public static final HudElementInfo<ElytraFuelHud> INFO = new HudElementInfo<>(
        VolytraFlyAddon.HUD_GROUP,
        "volytrafly-fuel-gauge",
        "Fuel-style gauge for VolytraFly, showing your worn elytra's remaining durability",
        ElytraFuelHud::new
    );

    // Size of the element in pixels at fuel-gauge-scale 1. Deliberately smaller than the
    // speedometer - a gauge-cluster companion, not a second main dial.
    static final double BASE_SIZE = 110;

    // The dial spans 270 degrees, from bottom-left round the top to bottom-right (screen space, y
    // down), exactly like the speedometer, so the two dials read the same way side by side.
    private static final double START_ANGLE = Math.toRadians(135);
    private static final double SWEEP = Math.toRadians(270);
    private static final double SEGMENT_STEP = Math.toRadians(4);

    // Reading shown in the HUD editor when the gauge isn't live.
    private static final double PREVIEW_FRACTION = 0.4;

    // Colour the needle and fill fade towards as durability runs out, over the last quarter of
    // the dial (fraction < 0.25) - the exact same neon green the speedometer itself fades into at
    // the top of its own range, so the two dials share one colour language.
    private int maxR = 0, maxG = 255, maxB = 70;

    private final Color scratch = new Color(255, 255, 255, 255);

    // Old-CRT power-on / power-off animation.
    private final CrtEffect crt = new CrtEffect();

    // Smoothed value the needle is drawn at, 0 (empty) to 1 (full).
    private double shownFraction;

    public ElytraFuelHud() {
        super(INFO);
    }

    /**
     * Makes sure a fuel gauge exists in the HUD, immediately to the left of where the speedometer
     * sits by default (bottom right of the screen), and that Meteor's HUD is switched on.
     */
    public static void ensureAdded() {
        if (HudLayout.alreadyAdded(ElytraFuelHud.class)) return;

        Hud hud = Hud.get();

        VolytraFly module = Modules.get().get(VolytraFly.class);
        double speedometerScale = module != null ? module.speedometerScale.get() : HudLayout.defaultSpeedometerScale();
        int speedometerSize = (int) Math.ceil(SpeedometerHud.BASE_SIZE * speedometerScale);

        int textHeight = (int) Math.ceil(HudRenderer.INSTANCE.textHeight(true));
        int speedometerBottomOffset = 8 + textHeight * 3;
        int gap = 6;

        hud.add(INFO, -(4 + speedometerSize + gap), -speedometerBottomOffset, XAnchor.Right, YAnchor.Bottom);
    }

    /**
     * The worn elytra of whatever the camera is following, or null if that entity isn't wearing
     * one. That is the player when playing normally, but in a Flashback replay it is the recorded
     * player being watched, not your own (separate) camera player - exactly like SpeedometerHud's
     * own measuredSpeed tracking.
     */
    private static ItemStack wornElytra() {
        Entity entity = mc.getCameraEntity();
        if (entity == null) entity = mc.player;
        if (!(entity instanceof LivingEntity living)) return null;

        ItemStack chest = living.getItemBySlot(EquipmentSlot.CHEST);
        return chest.getItem() == Items.ELYTRA ? chest : null;
    }

    @Override
    public void render(HudRenderer renderer) {
        VolytraFly module = Modules.get().get(VolytraFly.class);
        double scale = module != null ? module.fuelGaugeScale.get() : HudLayout.defaultFuelGaugeScale();
        double size = BASE_SIZE * scale;

        if (module != null) {
            Color green = module.greenColor.get();
            maxR = green.r;
            maxG = green.g;
            maxB = green.b;
        }

        // Always set the size, so the element can be selected and dragged in the HUD editor.
        setSize(size, size);

        // Directly left of the speedometer, with the "Elytra" text level with the speedometer's
        // "Blocks Per Second" text (unless arranging is turned off).
        double ox = x, oy = y;
        if (HudLayout.enabled(module)) {
            double gaugeOuter = size / 2 - 8 * scale;
            double gaugeReadingScale = 0.65 * scale;
            double unitOffset = size / 2 + gaugeOuter * 0.42
                + renderer.textHeight(true, gaugeReadingScale) + 1 * scale;
            double[] p = HudLayout.fuelPosition(size, unitOffset);
            if (p != null) {
                ox = p[0];
                oy = p[1];
            }
        }

        boolean live = module != null && module.fuelGauge.get()
            && (module.isActive() || HudLayout.inFlashbackReplay());
        boolean editor = HudEditorScreen.isOpen();
        crt.update(renderer.delta, live || editor, module == null || module.crtOverlay.get());
        if (!crt.visible()) return;
        crt.begin(renderer, ox, oy, size, size);

        // While the turn-off animation plays the module is already off, but the panel should keep
        // showing its live reading as it collapses rather than jumping to the editor's sample one.
        boolean dataLive = live || !editor;

        ItemStack elytra = dataLive ? wornElytra() : null;
        boolean worn = elytra != null;

        int remaining = worn ? elytra.getMaxDamage() - elytra.getDamageValue() : 0;
        double target;
        if (!dataLive) {
            target = PREVIEW_FRACTION;
        } else if (worn) {
            target = elytra.getMaxDamage() <= 0 ? 0 : remaining / (double) elytra.getMaxDamage();
        } else {
            target = 0;
        }

        double dt = Math.min(0.25, Math.max(0, renderer.delta));
        shownFraction += (target - shownFraction) * (1 - Math.exp(-dt * 14));
        double fraction = Math.max(0, Math.min(1, shownFraction));

        Color base = HudLayout.baseColor(module);
        int baseR = base.r, baseG = base.g, baseB = base.b;

        double cx = ox + size / 2;
        double cy = oy + size / 2;
        double outer = size / 2 - 8 * scale;
        double ringThickness = 4 * scale;
        double ringMid = outer - ringThickness / 2;

        // Dial track (dim), fading towards the speedometer's own max-speed green as it nears the
        // empty end - a warning zone painted on the gauge face itself, the same way the
        // speedometer paints its own high-speed zone in green.
        band(cx, cy, outer - ringThickness, outer, 1, baseR, baseG, baseB, 42);

        // E / F labels at the two ends of the sweep.
        double labelScale = 0.55 * scale;
        double labelRadius = outer - ringThickness - 10 * scale;
        drawEndLabel(renderer, "E", START_ANGLE, cx, cy, labelRadius, labelScale, fraction <= 0.001, baseR, baseG, baseB);
        drawEndLabel(renderer, "F", START_ANGLE + SWEEP, cx, cy, labelRadius, labelScale, fraction >= 0.999, baseR, baseG, baseB);

        // Filled neon arc with a glow, from empty round to the current fraction, fading into the
        // same warning green wherever the fill dips into the low zone.
        if (fraction > 0.002) {
            double[] extra = {7 * scale, 4.5 * scale, 2.5 * scale};
            int[] glowAlpha = {14, 28, 52};
            for (int i = 0; i < extra.length; i++) {
                band(cx, cy, ringMid - ringThickness / 2 - extra[i], ringMid + ringThickness / 2 + extra[i], fraction, baseR, baseG, baseB, glowAlpha[i]);
            }
            band(cx, cy, ringMid - ringThickness / 2, ringMid + ringThickness / 2, fraction, baseR, baseG, baseB, 255);
            band(cx, cy, ringMid - 0.7 * scale, ringMid + 0.7 * scale, fraction, baseR, baseG, baseB, 210, 0.6);
        }

        // Needle and hub: the speedometer's own colour, fading into the same green near empty, the
        // way the speedometer's needle does near its max speed.
        double needleAngle = START_ANGLE + SWEEP * fraction;
        int[] needleColor = zoneColor(fraction, baseR, baseG, baseB);
        double needleLength = outer - ringThickness - 5 * scale;
        needle(cx, cy, needleAngle, needleLength, 8 * scale, 3.4 * scale, needleColor, 38, 0);
        needle(cx, cy, needleAngle, needleLength, 8 * scale, 1.9 * scale, needleColor, 90, 0);
        needle(cx, cy, needleAngle, needleLength, 8 * scale, 1.1 * scale, needleColor, 255, 0.35);

        // Hub
        sector(cx, cy, 0, 5 * scale, 0, Math.PI * 2, 0, 0, 0, 120);
        sector(cx, cy, 3.5 * scale, 5 * scale, 0, Math.PI * 2, needleColor[0], needleColor[1], needleColor[2], 255);

        // Digital readout: durability points (or a dash when nothing is worn), with "Elytra" under
        // it, in the open gap at the bottom of the dial.
        String reading = dataLive && !worn ? "--" : String.valueOf(worn ? remaining : (int) Math.round(PREVIEW_FRACTION * 432));
        double readingScale = 0.65 * scale;
        double rw = renderer.textWidth(reading, true, readingScale);
        double rh = renderer.textHeight(true, readingScale);
        double readingY = cy + outer * 0.42;
        setColor(mix(baseR, 255, 0.55), mix(baseG, 255, 0.55), mix(baseB, 255, 0.55), 255);
        crtText(renderer, reading, cx - rw / 2, readingY, scratch, true, readingScale);

        String unit = "Elytra";
        // Always the same size as the speedometer's "Blocks Per Second" text (0.4 * speedometer scale).
        double unitScale = 0.4 * (module != null ? module.speedometerScale.get() : HudLayout.defaultSpeedometerScale());
        double uw = renderer.textWidth(unit, false, unitScale);
        setColor(baseR, baseG, baseB, 200);
        crtText(renderer, unit, cx - uw / 2, readingY + rh + 1 * scale, scratch, false, unitScale);

        crt.end(baseR, baseG, baseB);
    }

    // Drawing helpers (mirroring SpeedometerHud's own, kept local so each HUD element stays
    // self-contained)

    private void drawEndLabel(HudRenderer renderer, String label, double angle, double cx, double cy,
                              double radius, double scale, boolean lit, int baseR, int baseG, int baseB) {
        double w = renderer.textWidth(label, false, scale);
        double h = renderer.textHeight(false, scale);
        setColor(baseR, baseG, baseB, lit ? 235 : 130);
        crtText(renderer, label, cx + Math.cos(angle) * radius - w / 2, cy + Math.sin(angle) * radius - h / 2, scratch, false, scale);
    }

    /**
     * Draws part of the dial ring, from the start of the sweep to fraction f1, in small segments so the
     * colour can blend into the low-durability warning green. whiten mixes the colour towards
     * white (0 = none).
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
     * Colour at a point along the dial: the base colour, fading into the speedometer's own
     * max-speed green over the last quarter as it runs towards empty.
     */
    private int[] zoneColor(double fraction, int baseR, int baseG, int baseB) {
        double t = Math.max(0, Math.min(1, (0.25 - fraction) / 0.25));
        return new int[]{mix(baseR, maxR, t), mix(baseG, maxG, t), mix(baseB, maxB, t)};
    }

    private static int mix(int from, int to, double t) {
        return (int) Math.round(from + (to - from) * t);
    }

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
}
