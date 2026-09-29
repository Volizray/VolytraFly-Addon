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


/**
 * A panel in the same visual style as {@link SpeedometerHud}, showing the on/off
 * state of VolytraFly's toggleable modes: Mapping, Building, Bypass, Avoidance, Anti Slam, Autopilot
 * and Highway Mode.
 */
public class ModeLightsHud extends HudElement {
    public static final HudElementInfo<ModeLightsHud> INFO = new HudElementInfo<>(
        VolytraFlyAddon.HUD_GROUP,
        "volytrafly-switches",
        "Neon switches panel for VolytraFly's toggleable modes, in the same style and colour as the speedometer.",
        ModeLightsHud::new
    );

    // Grid and light sizes in pixels at scale 1. The grid is laid out vertically: each
    // column is filled top to bottom before starting the next column, up to MAX_ROWS per column.
    static final int MAX_ROWS = 5;
    static final double LIGHT_DIAMETER = 12;
    static final double ROW_GAP = 10;
    private static final double COLUMN_GAP = 16;
    private static final double LABEL_GAP = 7;
    static final double LABEL_SCALE = 0.55;

    // Enclosing neon border, matching the speedometer's inner dial ring: a thin bright line with a
    // soft glow bleed and a bright highlight down its middle.
    static final double CONTENT_PADDING = 9;
    static final double CORNER_RADIUS = 8;
    static final double BORDER_THICKNESS = 2;
    static final double[] BORDER_GLOW = {5, 3.5, 2};
    static final int[] BORDER_GLOW_ALPHA = {14, 28, 52};

    private static final double SEGMENT_STEP = Math.toRadians(6);

    private final Color scratch = new Color(255, 255, 255, 255);

    // Old-CRT power-on / power-off animation.
    private final CrtEffect crt = new CrtEffect();

    public ModeLightsHud() {
        super(INFO);
    }

    /**
     * Makes sure a switches panel exists in the HUD, directly above where the speedometer sits
     * by default (bottom right of the screen), and that Meteor's HUD is switched on, so the panel
     * actually shows something.
     */
    public static void ensureAdded() {
        if (HudLayout.alreadyAdded(ModeLightsHud.class)) return;

        Hud hud = Hud.get();

        VolytraFly module = Modules.get().get(VolytraFly.class);
        double speedometerScale = module != null ? module.speedometerScale.get() : HudLayout.defaultSpeedometerScale();
        int speedometerSize = (int) Math.ceil(SpeedometerHud.BASE_SIZE * speedometerScale);

        int textHeight = (int) Math.ceil(HudRenderer.INSTANCE.textHeight(true));
        int speedometerBottomOffset = 8 + textHeight * 3;
        int gap = 6;

        // Horizontally, the panel's outer border edge sits on the speedometer dial's outer circle,
        // so its element edge is just the glow margin outside that. The speedometer element extends
        // SpeedometerHud.DIAL_INSET * scale beyond the circle.
        double scale = fitScale(HudRenderer.INSTANCE, buildLights(module, false), speedometerScale);
        double glowMargin = (BORDER_GLOW[0] + 1.5) * scale;
        int xOffset = (int) Math.round(-(4 + SpeedometerHud.DIAL_INSET * speedometerScale - glowMargin));

        hud.add(INFO, xOffset, -(speedometerBottomOffset + speedometerSize + gap), XAnchor.Right, YAnchor.Bottom);
    }

    /**
     * The scale the switches panel is drawn at (the one that makes it as wide as the speedometer's
     * outer circle). The Volytra Assistant box uses the same, so the two line up.
     */
    static double panelScale(HudRenderer renderer, VolytraFly module) {
        double speedometerScale = module != null ? module.speedometerScale.get() : HudLayout.defaultSpeedometerScale();
        return fitScale(renderer, buildLights(module, false), speedometerScale);
    }

    /**
     * Total height of the switches panel element at its default size, so something can be stacked
     * directly on top of it.
     */
    static double panelHeight(HudRenderer renderer, VolytraFly module) {
        double scale = panelScale(renderer, module);
        double rowHeight = Math.max(LIGHT_DIAMETER * scale, renderer.textHeight(true, LABEL_SCALE * scale));
        int rows = Math.min(MAX_ROWS, buildLights(module, false).length);
        double gridHeight = rows * rowHeight + (rows - 1) * ROW_GAP * scale;
        double outerMargin = BORDER_THICKNESS * scale / 2 + BORDER_GLOW[0] * scale + 1.5 * scale;
        return outerMargin * 2 + CONTENT_PADDING * scale * 2 + gridHeight;
    }

    /**
     * Width of the panel's outer border edge (the outside of the crisp line, excluding the glow) at
     * scale 1. Everything in it scales linearly with scale.
     */
    private static double borderWidthAtUnitScale(HudRenderer renderer, Light[] lights) {
        double maxLabelWidth = 0;
        for (Light light : lights) {
            maxLabelWidth = Math.max(maxLabelWidth, renderer.textWidth(light.label(), true, LABEL_SCALE));
        }
        double cellWidth = LIGHT_DIAMETER + LABEL_GAP + maxLabelWidth;
        int rows = Math.min(MAX_ROWS, lights.length);
        int columns = (int) Math.ceil(lights.length / (double) rows);
        double gridWidth = columns * cellWidth + (columns - 1) * COLUMN_GAP;
        return CONTENT_PADDING * 2 + gridWidth + BORDER_THICKNESS;
    }

    /**
     * The scale at which the panel's left and right sides are tangent to the speedometer's outer
     * circle (diameter 2 * DIAL_RADIUS * speedometerScale).
     */
    private static double fitScale(HudRenderer renderer, Light[] lights, double speedometerScale) {
        double target = 2 * SpeedometerHud.DIAL_RADIUS * speedometerScale;
        double perUnit = borderWidthAtUnitScale(renderer, lights);
        return perUnit > 0 ? target / perUnit : 1.0;
    }

    @Override
    public void render(HudRenderer renderer) {
        VolytraFly module = Modules.get().get(VolytraFly.class);
        boolean editor = HudEditorScreen.isOpen();

        boolean live = module != null && module.modeLights.get()
            && (module.isActive() || HudLayout.inFlashbackReplay());

        Color base = HudLayout.baseColor(module);
        int baseR = base.r, baseG = base.g, baseB = base.b;

        Light[] lights = buildLights(module, editor && !live);

        // Always the scale that makes the panel's sides tangent to the speedometer's outer circle.
        double speedometerScale = module != null ? module.speedometerScale.get() : HudLayout.defaultSpeedometerScale();
        double scale = fitScale(renderer, lights, speedometerScale);

        // Grid metrics.
        double lightDiameter = LIGHT_DIAMETER * scale;
        double lightRadius = lightDiameter / 2;
        double rowGap = ROW_GAP * scale;
        double columnGap = COLUMN_GAP * scale;
        double labelGap = LABEL_GAP * scale;
        double labelScale = LABEL_SCALE * scale;

        double textHeight = renderer.textHeight(true, labelScale);
        double rowHeight = Math.max(lightDiameter, textHeight);

        double maxLabelWidth = 0;
        for (Light light : lights) {
            maxLabelWidth = Math.max(maxLabelWidth, renderer.textWidth(light.label(), true, labelScale));
        }
        double cellWidth = lightDiameter + labelGap + maxLabelWidth;

        int rows = Math.min(MAX_ROWS, lights.length);
        int columns = (int) Math.ceil(lights.length / (double) rows);

        double gridWidth = columns * cellWidth + (columns - 1) * columnGap;
        double gridHeight = rows * rowHeight + (rows - 1) * rowGap;

        // Border metrics: enough outer margin to fit the glow bleed without it being clipped at
        // the edge of the HUD element, exactly as the speedometer margins its dial ring.
        double contentPadding = CONTENT_PADDING * scale;
        double cornerRadius = CORNER_RADIUS * scale;
        double borderHalfThickness = BORDER_THICKNESS * scale / 2;
        double outerMargin = borderHalfThickness + BORDER_GLOW[0] * scale + 1.5 * scale;

        double width = outerMargin * 2 + contentPadding * 2 + gridWidth;
        double height = outerMargin * 2 + contentPadding * 2 + gridHeight;

        // Always set the size, so the element can be selected and dragged in the HUD editor.
        setSize(width, height);

        // Rests just above the speedometer's dial (unless arranging is turned off).
        double ox = x, oy = y;
        if (HudLayout.enabled(module)) {
            double[] p = HudLayout.boxPosition(height, outerMargin - borderHalfThickness, false);
            if (p != null) {
                ox = p[0];
                oy = p[1];
            }
        }
        HudLayout.reportPanelTop(oy + outerMargin - borderHalfThickness);

        crt.update(renderer.delta, live || editor, module == null || module.crtOverlay.get());
        if (!crt.visible()) return;
        crt.begin(renderer, ox, oy, width, height);

        double x0 = ox + outerMargin;
        double y0 = oy + outerMargin;
        double x1 = x0 + contentPadding * 2 + gridWidth;
        double y1 = y0 + contentPadding * 2 + gridHeight;

        drawBorder(x0, y0, x1, y1, cornerRadius, borderHalfThickness, scale, baseR, baseG, baseB);

        double contentX = x0 + contentPadding;
        double contentY = y0 + contentPadding;

        for (int i = 0; i < lights.length; i++) {
            Light light = lights[i];
            int col = i / rows;
            int row = i % rows;

            double cellX = contentX + col * (cellWidth + columnGap);
            double cellY = contentY + row * (rowHeight + rowGap);
            double lightCx = cellX + lightRadius;
            double lightCy = cellY + rowHeight / 2;

            drawLight(lightCx, lightCy, lightRadius, light.on(), baseR, baseG, baseB);

            double textX = lightCx + lightRadius + labelGap;
            double textY = lightCy - textHeight / 2;
            if (light.on()) {
                setColor(mix(baseR, 255, 0.25), mix(baseG, 255, 0.25), mix(baseB, 255, 0.25), 255);
            } else {
                setColor(mix(baseR, 0, 0.55), mix(baseG, 0, 0.55), mix(baseB, 0, 0.55), 180);
            }
            crtText(renderer, light.label(), textX, textY, scratch, labelScale);
        }

        crt.end(baseR, baseG, baseB);
    }

    /**
     * One indicator light: its label, and whether its mode is currently on.
     */
    private record Light(String label, boolean on) {}

    /**
     * The lights to draw, one per toggleable mode. When the module isn't loaded, or the HUD editor
     * wants a preview (the panel isn't otherwise live), a fixed sample pattern is shown instead so
     * the on/off contrast is visible while positioning the element.
     */
    private static Light[] buildLights(VolytraFly module, boolean preview) {
        if (module == null || preview) {
            return new Light[]{
                new Light("Mapping", true),
                new Light("Building", false),
                new Light("Bypass", true),
                new Light("Avoidance", false),
                new Light("Anti Slam", true),
                new Light("Autopilot", false),
                new Light("Highway Mode", true)
            };
        }

        return new Light[]{
            new Light("Mapping", module.mappingMode.get()),
            new Light("Building", module.buildingMode.get()),
            new Light("Bypass", module.bypassMode.get()),
            new Light("Avoidance", module.playerAvoidance.get()),
            new Light("Anti Slam", module.antiSlam.get()),
            new Light("Autopilot", module.autoPilot.get()),
            new Light("Highway Mode", module.highwayMode.get())
        };
    }

    // Drawing helpers

    /**
     * Draws one light: a bright, glowing disc in the shared colour when on, or just a
     * dimmer (darkened) version of that same colour, with no glow, when off.
     */
    private void drawLight(double cx, double cy, double radius, boolean on, int r, int g, int b) {
        if (on) {
            double[] extra = {radius * 1.6, radius, radius * 0.45};
            int[] glowAlpha = {16, 32, 58};
            for (int i = 0; i < extra.length; i++) {
                sector(cx, cy, 0, radius + extra[i], 0, Math.PI * 2, r, g, b, glowAlpha[i]);
            }
            // Bright core.
            sector(cx, cy, 0, radius, 0, Math.PI * 2, r, g, b, 255);
            // Hot highlight in the middle, for the neon-tube look.
            sector(cx, cy, 0, radius * 0.45, 0, Math.PI * 2, mix(r, 255, 0.65), mix(g, 255, 0.65), mix(b, 255, 0.65), 225);
            // Thin dark rim for definition against the glow.
            sector(cx, cy, radius * 0.9, radius, 0, Math.PI * 2, 0, 0, 0, 70);
        } else {
            // Just a darker, unlit version of the same colour - no glow.
            sector(cx, cy, 0, radius, 0, Math.PI * 2, mix(r, 0, 0.6), mix(g, 0, 0.6), mix(b, 0, 0.6), 220);
        }
    }

    /**
     * Draws the rounded-rectangle neon border enclosing the panel, using the exact same layering
     * (three glow-bleed passes, a bright crisp line, then a whitened highlight down the middle) as
     * the speedometer's own inner dial ring, so the colour and brightness match exactly.
     */
    private void drawBorder(double x0, double y0, double x1, double y1,
                            double cornerRadius, double halfThickness, double scale, int r, int g, int b) {
        for (int i = 0; i < BORDER_GLOW.length; i++) {
            double glowHalf = halfThickness + BORDER_GLOW[i] * scale;
            roundedRectBand(x0, y0, x1, y1, cornerRadius, glowHalf, r, g, b, BORDER_GLOW_ALPHA[i]);
        }
        roundedRectBand(x0, y0, x1, y1, cornerRadius, halfThickness, r, g, b, 235);
        roundedRectBand(x0, y0, x1, y1, cornerRadius, 0.4 * scale,
            mix(r, 255, 0.6), mix(g, 255, 0.6), mix(b, 255, 0.6), 200);
    }

    /**
     * Strokes a rounded rectangle (four straight edge bands plus four quarter-circle corner arcs)
     * of a given half-thickness straddling the path defined by x0,y0 to x1,y1 and cornerRadius.
     */
    private void roundedRectBand(double x0, double y0, double x1, double y1,
                                 double cornerRadius, double halfThickness, int r, int g, int b, int a) {
        double innerR = Math.max(0, cornerRadius - halfThickness);
        double outerR = cornerRadius + halfThickness;

        // Straight edges, inset from the corners.
        quad(x0 + cornerRadius, y0 - halfThickness, x1 - cornerRadius, y0 - halfThickness,
            x1 - cornerRadius, y0 + halfThickness, x0 + cornerRadius, y0 + halfThickness, r, g, b, a); // top
        quad(x0 + cornerRadius, y1 - halfThickness, x1 - cornerRadius, y1 - halfThickness,
            x1 - cornerRadius, y1 + halfThickness, x0 + cornerRadius, y1 + halfThickness, r, g, b, a); // bottom
        quad(x0 - halfThickness, y0 + cornerRadius, x0 + halfThickness, y0 + cornerRadius,
            x0 + halfThickness, y1 - cornerRadius, x0 - halfThickness, y1 - cornerRadius, r, g, b, a); // left
        quad(x1 - halfThickness, y0 + cornerRadius, x1 + halfThickness, y0 + cornerRadius,
            x1 + halfThickness, y1 - cornerRadius, x1 - halfThickness, y1 - cornerRadius, r, g, b, a); // right

        // Corner arcs, each a quarter turn connecting the two adjacent straight edges.
        sector(x0 + cornerRadius, y0 + cornerRadius, innerR, outerR, Math.PI, 1.5 * Math.PI, r, g, b, a);       // top-left
        sector(x1 - cornerRadius, y0 + cornerRadius, innerR, outerR, 1.5 * Math.PI, 2 * Math.PI, r, g, b, a);   // top-right
        sector(x1 - cornerRadius, y1 - cornerRadius, innerR, outerR, 0, 0.5 * Math.PI, r, g, b, a);             // bottom-right
        sector(x0 + cornerRadius, y1 - cornerRadius, innerR, outerR, 0.5 * Math.PI, Math.PI, r, g, b, a);       // bottom-left
    }

    private static int mix(int from, int to, double t) {
        return (int) Math.round(from + (to - from) * t);
    }

    /**
     * A ring sector between radius r0 and r1 and angle a0 to a1 (radians, increasing = clockwise on screen).
     * Passing r0 = 0 draws a full disc (or pie slice).
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
     * Draws a quad (two triangles) in a single flat colour.
     */
    private void quad(double x1, double y1, double x2, double y2, double x3, double y3,
                                            double x4, double y4, int r, int g, int b, int a) {
        setColor(r, g, b, a);
        tri(x1, y1, x2, y2, x3, y3);
        tri(x1, y1, x3, y3, x4, y4);
    }

    /**
     * Draws a triangle in the scratch colour, always wound the same way round as Meteor's own quads
     */
    private void tri(double x1, double y1, double x2, double y2, double x3, double y3) {
        // CRT effect: squash, fade, and add scanlines / phosphor stripes to this shape only.
        crt.triangle(x1, y1, x2, y2, x3, y3, scratch);
    }

    /**
     * Draws text through the CRT effect. Text can't be stretched, so it is shrunk by the vertical
     * squash around its own centre and faded in and out with the animation.
     */
    private void crtText(HudRenderer renderer, String text, double tx, double ty, Color color, double scale) {
        double a = crt.alpha();
        double squash = crt.scaleY();
        if (a <= 0.02 || squash < 0.25) return;

        double w = renderer.textWidth(text, true, scale);
        double h = renderer.textHeight(true, scale);
        double centerX = crt.mapX(tx + w / 2, ty + h / 2);
        double centerY = crt.mapY(ty + h / 2);

        double s = scale * squash;
        double w2 = renderer.textWidth(text, true, s);
        double h2 = renderer.textHeight(true, s);

        int oldAlpha = color.a;
        color.a = (int) Math.round(oldAlpha * a);
        renderer.text(text, centerX - w2 / 2, centerY - h2 / 2, color, true, s);
        color.a = oldAlpha;
    }

    private void setColor(int r, int g, int b, int a) {
        scratch.set(r, g, b, a);
    }
}
