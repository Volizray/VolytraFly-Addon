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

import java.util.ArrayList;
import java.util.List;


/**
 * Volytra Assistant: a speech box in the same style as {@link ModeLightsHud}.
 * It'll say what VolytraFly is doing - Highway Mode's moves, and what is going on in general
 * flight. The words come from {@link VolytraFly}.
 */
public class VolytraAssistantHud extends HudElement {
    public static final HudElementInfo<VolytraAssistantHud> INFO = new HudElementInfo<>(
        VolytraFlyAddon.HUD_GROUP,
        "volytrafly-volytra-assistant",
        "A helpful assistant which explains what VolytraFly is doing",
        VolytraAssistantHud::new
    );

    private static final String TITLE = "Volytra Assistant";
    private static final String SAMPLE = "Cruising along the East highway~";
    private static final int MESSAGE_LINES = 3;
    private static final double MESSAGE_SCALE = 0.6;
    private static final double MIN_MESSAGE_SCALE = 0.3;
    private static final double TITLE_SCALE = 0.5;
    private static final double TITLE_GAP = 4;
    private static final double LINE_GAP = 2;

    private static final double SEGMENT_STEP = Math.toRadians(6);

    // Typewriter effect - how many characters of the message are shown per second.
    private static final double TYPE_CHARS_PER_SECOND = 45;

    private final Color scratch = new Color(255, 255, 255, 255);

    // Typewriter state - the message currently being typed and how many characters are showing.
    private String typedMessage = "";
    private double typedChars = 0;

    // CRT power on/off animation.
    private final CrtEffect crt = new CrtEffect();

    public VolytraAssistantHud() {
        super(INFO);
    }

    /**
     * Makes sure an assistant box exists in the HUD, directly above where the switches panel sits
     * by default (or directly above the speedometer if the switches panel is turned off), and that
     * Meteor's HUD is switched on, so the box actually shows something.
     */
    public static void ensureAdded() {
        if (HudLayout.alreadyAdded(VolytraAssistantHud.class)) return;

        Hud hud = Hud.get();

        VolytraFly module = Modules.get().get(VolytraFly.class);
        HudRenderer renderer = HudRenderer.INSTANCE;
        double speedometerScale = module != null ? module.speedometerScale.get() : HudLayout.defaultSpeedometerScale();
        int speedometerSize = (int) Math.ceil(SpeedometerHud.BASE_SIZE * speedometerScale);

        int textHeight = (int) Math.ceil(renderer.textHeight(true));
        int speedometerBottomOffset = 8 + textHeight * 3;
        int gap = 6;

        // Stack: speedometer, then the switches panel (if it's on), then this.
        double stackHeight = speedometerSize + gap;
        if (module == null || module.modeLights.get()) {
            stackHeight += Math.ceil(ModeLightsHud.panelHeight(renderer, module)) + gap;
        }

        // Same horizontal placement as the switches panel: its border sits on the speedometer
        // dial's outer circle, so the element edge is just the glow margin outside that.
        double scale = ModeLightsHud.panelScale(renderer, module);
        double glowMargin = (ModeLightsHud.BORDER_GLOW[0] + 1.5) * scale;
        int xOffset = (int) Math.round(-(4 + SpeedometerHud.DIAL_INSET * speedometerScale - glowMargin));

        hud.add(INFO, xOffset, -(int) Math.round(speedometerBottomOffset + stackHeight), XAnchor.Right, YAnchor.Bottom);
    }

    @Override
    public void render(HudRenderer renderer) {
        VolytraFly module = Modules.get().get(VolytraFly.class);
        boolean editor = HudEditorScreen.isOpen();

        boolean enabled = module != null && module.volytraAssistant.get()
            && (module.isActive() || HudLayout.inFlashbackReplay());
        String message = enabled ? module.getAssistantText() : null;
        boolean live = message != null;
        boolean sorry = live && module.isAssistantSorry();
        if (!live && editor) {
            message = SAMPLE;
        }

        Color base = HudLayout.baseColor(module);
        int baseR = base.r, baseG = base.g, baseB = base.b;

        // Same scale, and so the same width, border and glow, as the switches panel.
        double scale = ModeLightsHud.panelScale(renderer, module);
        double contentPadding = ModeLightsHud.CONTENT_PADDING * scale;
        double cornerRadius = ModeLightsHud.CORNER_RADIUS * scale;
        double borderHalfThickness = ModeLightsHud.BORDER_THICKNESS * scale / 2;
        double outerMargin = borderHalfThickness + ModeLightsHud.BORDER_GLOW[0] * scale + 1.5 * scale;

        double speedometerScale = module != null ? module.speedometerScale.get() : HudLayout.defaultSpeedometerScale();
        double borderOuterWidth = 2 * SpeedometerHud.DIAL_RADIUS * speedometerScale;
        double pathWidth = borderOuterWidth - ModeLightsHud.BORDER_THICKNESS * scale;
        double width = outerMargin * 2 + pathWidth;

        double titleScale = TITLE_SCALE * scale;
        double titleHeight = renderer.textHeight(true, titleScale);
        double titleGap = TITLE_GAP * scale;
        double lineGap = LINE_GAP * scale;

        // Wrap the message to the room inside the border, shrinking the text if it needs more
        // than the lines the box reserves.
        double textWidth = pathWidth - contentPadding * 2;
        double messageScale = MESSAGE_SCALE * scale;
        double minScale = MIN_MESSAGE_SCALE * scale;
        String fullText = message == null ? "" : message;
        List<String> lines = wrap(renderer, fullText, textWidth, messageScale);
        while (lines.size() > MESSAGE_LINES && messageScale > minScale) {
            messageScale = Math.max(minScale, messageScale * 0.92);
            lines = wrap(renderer, fullText, textWidth, messageScale);
        }
        if (lines.size() > MESSAGE_LINES) lines = new ArrayList<>(lines.subList(0, MESSAGE_LINES));

        // The reserved height is worked out at the normal text size, so it doesn't change with the
        // text (or the shrinking above).
        double reservedLineHeight = renderer.textHeight(true, MESSAGE_SCALE * scale);
        double linesHeight = MESSAGE_LINES * reservedLineHeight + (MESSAGE_LINES - 1) * lineGap;
        double height = outerMargin * 2 + contentPadding * 2 + titleHeight + titleGap + linesHeight;

        // Always set the size, so the element can be selected and dragged in the HUD editor.
        setSize(width, height);

        // Rests just above the switches panel, or the dial if that is off (unless arranging is
        // turned off).
        double ox = x, oy = y;
        if (HudLayout.enabled(module)) {
            double[] p = HudLayout.boxPosition(height, outerMargin - borderHalfThickness, true);
            if (p != null) {
                ox = p[0];
                oy = p[1];
            }
        }

        // Typewriter: restart whenever the message changes (or the box goes away, so it types
        // again next time it appears), otherwise reveal more characters as time passes. The
        // layout above always uses the full message, so words never jump between lines mid-typing.
        if (!fullText.equals(typedMessage)) {
            typedMessage = fullText;
            typedChars = 0;
        } else {
            typedChars += Math.min(0.25, Math.max(0, renderer.delta)) * TYPE_CHARS_PER_SECOND;
        }
        int charsLeft = (int) typedChars;

        crt.update(renderer.delta, live || editor, module == null || module.crtOverlay.get());
        if (!crt.visible()) return;
        crt.begin(renderer, ox, oy, width, height);

        double x0 = ox + outerMargin;
        double y0 = oy + outerMargin;
        double x1 = x0 + pathWidth;
        double y1 = y0 + contentPadding * 2 + titleHeight + titleGap + linesHeight;

        drawBorder(x0, y0, x1, y1, cornerRadius, borderHalfThickness, scale, baseR, baseG, baseB);

        double contentX = x0 + contentPadding;
        double textY = y0 + contentPadding;

        // Title, dimmed like an unlit switch label.
        setColor(mix(baseR, 0, 0.35), mix(baseG, 0, 0.35), mix(baseB, 0, 0.35), 220);
        crtText(renderer, TITLE, contentX, textY, scratch, titleScale);
        textY += titleHeight + titleGap;

        // Message: bright in the neon colour, or a warning red when the assistant is apologising.
        if (sorry) {
            setColor(255, 120, 120, 255);
        } else {
            setColor(mix(baseR, 255, 0.25), mix(baseG, 255, 0.25), mix(baseB, 255, 0.25), 255);
        }
        double lineHeight = renderer.textHeight(true, messageScale);
        for (String line : lines) {
            if (charsLeft > 0) {
                String shown = line.length() <= charsLeft ? line : line.substring(0, charsLeft);
                crtText(renderer, shown, contentX, textY, scratch, messageScale);
            }
            // +1 for the space that the word wrap swallowed at the end of this line.
            charsLeft -= line.length() + 1;
            textY += lineHeight + lineGap;
        }

        crt.end(baseR, baseG, baseB);
    }

    /**
     * Word wrap of text into lines no wider than maxWidth at the given text scale
     */
    private static List<String> wrap(HudRenderer renderer, String text, double maxWidth, double scale) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (!current.isEmpty() && renderer.textWidth(candidate, true, scale) > maxWidth) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) lines.add(current.toString());
        return lines;
    }

    // Drawing helpers (the same neon border as the switches panel)

    private void drawBorder(double x0, double y0, double x1, double y1,
                            double cornerRadius, double halfThickness, double scale, int r, int g, int b) {
        for (int i = 0; i < ModeLightsHud.BORDER_GLOW.length; i++) {
            double glowHalf = halfThickness + ModeLightsHud.BORDER_GLOW[i] * scale;
            roundedRectBand(x0, y0, x1, y1, cornerRadius, glowHalf, r, g, b, ModeLightsHud.BORDER_GLOW_ALPHA[i]);
        }
        roundedRectBand(x0, y0, x1, y1, cornerRadius, halfThickness, r, g, b, 235);
        roundedRectBand(x0, y0, x1, y1, cornerRadius, 0.4 * scale,
            mix(r, 255, 0.6), mix(g, 255, 0.6), mix(b, 255, 0.6), 200);
    }

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

    private void quad(double x1, double y1, double x2, double y2, double x3, double y3,
                                            double x4, double y4, int r, int g, int b, int a) {
        setColor(r, g, b, a);
        tri(x1, y1, x2, y2, x3, y3);
        tri(x1, y1, x3, y3, x4, y4);
    }

    private void tri(double x1, double y1, double x2, double y2, double x3, double y3) {
        // CRT effect: squash, fade, and add scanlines / phosphor stripes to this shape only.
        crt.triangle(x1, y1, x2, y2, x3, y3, scratch);
    }

    /**
     * Draws text through the CRT effect
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
