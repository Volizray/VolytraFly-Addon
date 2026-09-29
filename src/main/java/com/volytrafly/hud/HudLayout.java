package com.volytrafly.hud;

import com.volytrafly.modules.movement.volytrafly.VolytraFly;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.utils.render.color.Color;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Shared layout for the VolytraFly HUD cluster, so the pieces always sit together whatever the
 * speedometer's scale or position:
 * <ul>
 * <li>the fuel gauge sits directly left of the speedometer, bottom edges in line;</li>
 * <li>the switches panel rests just above the speedometer's dial;</li>
 * <li>the Volytra Assistant rests just above the switches panel (or the dial, if the panel is off).</li>
 * </ul>
 * <p>
 * The speedometer is the anchor: it reports where it is each frame and the others read that.
 * Everything here is skipped when the module's auto-arrange-hud setting is off, so the elements
 * can then be dragged freely in Meteor's HUD editor.
 */
public final class HudLayout {
    // Scales at 4K (2160 pixels tall). Other resolutions scale linearly from these, so 1080p
    // gets exactly half: 2 for the speedometer and 1.25 for the fuel gauge.
    private static final double REFERENCE_HEIGHT = 2160;
    private static final double SPEEDOMETER_SCALE_4K = 4.0;
    private static final double FUEL_GAUGE_SCALE_4K = 2.5;

    // A position reported by another element counts as current for this long.
    private static final long FRESH_MS = 500;

    private static double speedoX, speedoY, speedoScale;
    private static double speedoUnitY;   // top of the speedometer's "Blocks Per Second" text
    private static long speedoAt;

    private static double panelTopEdge;   // crisp outer edge of the switches panel's top border
    private static long panelAt;

    private HudLayout() {}

    // Resolution-based default scales

    private static double resolutionFactor() {
        try {
            var window = mc.getWindow();
            if (window != null && window.getFramebufferHeight() > 0) {
                return window.getFramebufferHeight() / REFERENCE_HEIGHT;
            }
        } catch (Throwable ignored) {
            // Fall through to the 4K values.
        }
        return 1.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public static double defaultSpeedometerScale() {
        return round2(SPEEDOMETER_SCALE_4K * resolutionFactor());
    }

    public static double defaultFuelGaugeScale() {
        return round2(FUEL_GAUGE_SCALE_4K * resolutionFactor());
    }

    // Shared helpers

    /** The speedometer colour, or the default orange when the module isn't around. */
    static Color baseColor(VolytraFly module) {
        return module != null ? module.speedometerColor.get() : new Color(255, 128, 0, 255);
    }

    /**
     * Whether a Flashback replay is being played. Flashback runs replays on its own integrated
     * server class, which can be spotted without depending on Flashback itself.
     */
    static boolean inFlashbackReplay() {
        var server = mc.getServer();
        return server != null && server.getClass().getName().startsWith("com.moulberry.flashback");
    }

    /**
     * Switches Meteor's HUD on (so the visuals actually show) and reports whether an element of
     * the given class is already in it.
     */
    static boolean alreadyAdded(Class<? extends HudElement> type) {
        Hud hud = Hud.get();
        hud.active = true;
        for (HudElement element : hud) {
            if (type.isInstance(element)) return true;
        }
        return false;
    }

    // Arrangement

    public static boolean enabled(VolytraFly module) {
        return module == null || module.autoArrangeHud.get();
    }

    static void reportSpeedometer(double x, double y, double scale) {
        speedoX = x;
        speedoY = y;
        speedoScale = scale;
        speedoAt = System.currentTimeMillis();
    }

    static void reportSpeedometerUnitY(double unitY) {
        speedoUnitY = unitY;
    }

    /** Called by the switches panel with the crisp outer edge of its top border. */
    static void reportPanelTop(double topEdge) {
        panelTopEdge = topEdge;
        panelAt = System.currentTimeMillis();
    }

    private static boolean speedometerMissing() {
        return System.currentTimeMillis() - speedoAt >= FRESH_MS;
    }

    /** Space between the crisp border lines of stacked pieces, and above the dial. */
    private static double gap() {
        return 4 * speedoScale;
    }

    /**
     * Where the fuel gauge's top-left goes, or null if the speedometer isn't around. It sits
     * directly left of the speedometer, vertically placed so its "Elytra" text lines up with the
     * speedometer's "Blocks Per Second" text. unitOffset is how far below the gauge's own top
     * edge that text is.
     */
    static double[] fuelPosition(double fuelSize, double unitOffset) {
        if (speedometerMissing()) return null;
        return new double[]{speedoX - fuelSize, speedoUnitY - unitOffset};
    }

    /**
     * Where the top-left of a neon box (the switches panel or the assistant) goes. Its left and
     * right border edges sit on the dial's outer circle. glowMargin is the space between the
     * element's edge and its crisp border line; height is the element's height. The box rests
     * just above what is below it: the switches panel if stacked, otherwise the dial. Returns
     * null if the speedometer isn't around.
     */
    static double[] boxPosition(double height, double glowMargin, boolean aboveSwitches) {
        if (speedometerMissing()) return null;

        double x = speedoX + SpeedometerHud.DIAL_INSET * speedoScale - glowMargin;

        double supportTop;
        if (aboveSwitches && System.currentTimeMillis() - panelAt < FRESH_MS) {
            supportTop = panelTopEdge;
        } else {
            supportTop = speedoY + SpeedometerHud.DIAL_INSET * speedoScale;
        }

        double y = supportTop - gap() + glowMargin - height;
        return new double[]{x, y};
    }
}
