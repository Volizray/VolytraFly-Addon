package com.volytrafly.hud;

import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * Retro CRT look shared by the VolytraFly HUD elements
 */
public final class CrtEffect {
    /**
     * Where finished triangles go. HUD elements draw through Meteor's {@link HudRenderer}, while
     * world-overlay visuals (Future Sight) draw straight into a Renderer2D batch.
     */
    @FunctionalInterface
    public interface Sink {
        void triangle(double x1, double y1, double x2, double y2, double x3, double y3, Color color);
    }

    // Timeline (0 = off, 1 = on): dot, then line stretching out, then the vertical unfold.
    private static final double DOT_END = 0.10;
    private static final double LINE_END = 0.38;
    // Seconds a full turn-on takes.
    private static final double DURATION = 0.25;
    // Overlay strength (0 to 1) and the width of one RGB phosphor stripe relative to the default.
    private static final double STRENGTH = 0.75;
    private static final double CATHODE_SIZE = 0.7;
    // Turning off plays a little quicker than turning on, like a real tube.
    private static final double OFF_SPEEDUP = 1.25;

    private static final double SCANLINE_SPACING = 3;
    private static final double SCANLINE_THICKNESS = 1.35;
    // Safety cap on phosphor stripes drawn per element per frame.
    private static final int MAX_STRIPES = 6000;
    // Safety cap on stripes across a single shape, so tiny cathode sizes can't flood the renderer.
    private static final int MAX_STRIPES_PER_SHAPE = 600;

    private double t;
    private boolean rising = true;

    private boolean overlay;

    // Per-frame state, set by begin().
    private double time;
    private double width, height, cx, cy, unit;
    private double sx = 1, sy = 1, animAlpha = 1, flicker = 1;
    private double dotGlow, lineGlow, jitter;
    private double glitchTop, glitchHeight, glitchShift;

    private int stripes;
    private final double[] vx = new double[3], vy = new double[3];
    private final double[] span = new double[2];

    private final Color scratch = new Color(255, 255, 255, 255);

    private Sink sink;

    // Phase of the brightness flicker, advanced by update().
    private double flickerTime;

    /**
     * Advances the animation.
     *
     * @param dt      seconds since the last frame
     * @param show    whether the element should currently be on screen
     * @param overlay whether the persistent screen overlay is enabled
     */
    public void update(double dt, boolean show, boolean overlay) {
        this.overlay = overlay;

        dt = Math.min(0.25, Math.max(0, dt));
        flickerTime += dt;

        double step = dt / DURATION;
        if (show) {
            t += step;
            rising = true;
        } else {
            t -= step * OFF_SPEEDUP;
            rising = false;
        }
        t = Math.max(0, Math.min(1, t));
    }

    /**
     * True while anything at all should be drawn (fully on, or partway through either animation).
     */
    public boolean visible() {
        return t > 0;
    }

    /**
     * Works out this frame's state around the element's box.
     */
    public void begin(HudRenderer renderer, double x, double y, double width, double height) {
        begin(renderer::triangle, x, y, width, height);
    }

    /**
     * Same as the HudRenderer version, for drawing anywhere else: triangles go to the given sink.
     */
    public void begin(Sink sink, double x, double y, double width, double height) {
        this.sink = sink;
        this.width = width;
        this.height = height;
        this.cx = x + width / 2;
        this.cy = y + height / 2;
        this.unit = Math.max(1, Math.min(width, height) / 140);
        this.time = System.nanoTime() / 1.0e9;

        dotGlow = 0;
        lineGlow = 0;
        jitter = 0;
        stripes = 0;

        if (t >= 1) {
            sx = 1;
            sy = 1;
            animAlpha = 1;
        } else if (t < DOT_END) {
            // Just the white-hot dot.
            sx = 0;
            sy = 0;
            animAlpha = 0;
            dotGlow = t / DOT_END;
        } else if (t < LINE_END) {
            // The dot snaps out into a line.
            double u = (t - DOT_END) / (LINE_END - DOT_END);
            sx = easeOutCubic(u);
            sy = 0;
            animAlpha = 0;
            dotGlow = 1;
            lineGlow = 1;
        } else {
            // The picture unfolds from (or collapses into) the line.
            double v = (t - LINE_END) / (1 - LINE_END);
            sx = 1;
            sy = Math.max(0.015, rising ? easeOutBack(v) : easeOutCubic(v));
            animAlpha = Math.min(1, v * 2.2);
            lineGlow = 1 - smooth(v);
            dotGlow = lineGlow;
            if (rising) {
                jitter = Math.pow(1 - v, 1.5);
            } else {
                jitter = 0.6 * Math.pow(1 - v, 1.2);
            }
        }

        // Screen overlay state.
        flicker = 1;
        glitchShift = 0;
        if (overlay) {
            double f = 0.5 + 0.5 * Math.sin(flickerTime * 57) * Math.sin(flickerTime * 11.3);
            flicker = 1 - 0.07 * STRENGTH * f;

            // Every few seconds, a brief horizontal tear across a band of the picture.
            double period = 6.5;
            double k = Math.floor(time / period);
            double phase = time - k * period;
            double length = 0.16;
            if (phase < length) {
                double env = Math.sin(phase / length * Math.PI);
                glitchTop = y + rnd(k * 1.7) * height * 0.8;
                glitchHeight = height * (0.06 + 0.08 * rnd(k * 2.3));
                glitchShift = (rnd(k * 3.1) - 0.5) * 2 * width * 0.05 * STRENGTH * env;
            }
        }
    }

    /**
     * Where the x coordinate of a point drawn at (px, py) ends up. Depends on py because of the
     * wobble while the picture is unfolding and the occasional tear.
     */
    public double mapX(double px, double py) {
        double x = cx + (px - cx) * sx;
        double off = 0;

        if (jitter > 0) {
            off += (Math.sin(py * 0.13 + time * 45) * 0.6 + Math.sin(py * 0.041 - time * 23) * 0.4) * jitter * width * 0.04;
        }
        if (glitchShift != 0) {
            double edge = glitchHeight * 0.15 + 0.001;
            double band = Math.max(0, Math.min(1, Math.min((py - glitchTop) / edge, (glitchTop + glitchHeight - py) / edge)));
            off += glitchShift * band;
        }
        return x + off * sx;
    }

    public double mapY(double py) {
        return cy + (py - cy) * sy;
    }

    /**
     * Vertical squash of the picture this frame (used to shrink text, which cannot be stretched).
     */
    public double scaleY() {
        return sy;
    }

    /**
     * Opacity multiplier for the picture this frame: 0 while only the line/dot is showing, and a
     * faint flicker once it is fully on.
     */
    public double alpha() {
        return animAlpha * flicker;
    }

    /**
     * Draws the power-on glow and then the screen overlay on top of the picture.
     */
    public void end(int r, int g, int b) {
        drawPowerGlow(r, g, b);
    }

    /**
     * Draws one triangle of the element through the whole effect: squashed, wobbled and faded with the
     * animation, then (if the overlay is on) with scanlines, RGB phosphor stripes and the rolling
     * bright bar laid over it. Every overlay piece is clipped to this triangle, so nothing is ever
     * drawn where the element itself has nothing.
     */
    public void triangle(double x1, double y1, double x2, double y2, double x3, double y3, Color color) {
        double a = alpha();
        if (a <= 0) return;

        vx[0] = mapX(x1, y1); vy[0] = mapY(y1);
        vx[1] = mapX(x2, y2); vy[1] = mapY(y2);
        vx[2] = mapX(x3, y3); vy[2] = mapY(y3);

        int oldAlpha = color.a;
        int shown = (int) Math.round(oldAlpha * a);
        if (shown <= 0) return;

        color.a = shown;
        wound(vx[0], vy[0], vx[1], vy[1], vx[2], vy[2], color);
        color.a = oldAlpha;

        if (!overlay || animAlpha <= 0.02) return;

        double ymin = Math.min(vy[0], Math.min(vy[1], vy[2]));
        double ymax = Math.max(vy[0], Math.max(vy[1], vy[2]));
        double xmin = Math.min(vx[0], Math.min(vx[1], vx[2]));
        double xmax = Math.max(vx[0], Math.max(vx[1], vx[2]));
        if (ymax - ymin < 0.5 || xmax - xmin < 0.5) return;

        // How strongly the overlay shows on this shape: faint shapes (glows) get faint lines.
        double sa = shown / 255.0 * STRENGTH;

        // Rolling bright bar, drifting slowly down the element (brightens only the shape).
        double top0 = cy - height / 2 * sy;
        double span0 = height * sy;
        double barMid = top0 + ((time / 4.0) % 1.4 - 0.2) * span0;
        double barHalf = span0 * 0.09;
        double[] layer = {1.0, 0.65, 0.35};
        double[] layerAlpha = {16, 20, 28};
        for (int i = 0; i < layer.length; i++) {
            double ya = Math.max(ymin, barMid - barHalf * layer[i]);
            double yb = Math.min(ymax, barMid + barHalf * layer[i]);
            if (yb > ya) band(ya, yb, 255, 255, 255, layerAlpha[i] * sa);
        }

        // RGB phosphor stripes: one thin vertical stripe per cathode, cycling red, green, blue.
        if (stripes < MAX_STRIPES) {
            double pitch = Math.max(1, unit * CATHODE_SIZE);
            if ((xmax - xmin) / pitch > MAX_STRIPES_PER_SHAPE) pitch = (xmax - xmin) / MAX_STRIPES_PER_SHAPE;
            long first = (long) Math.floor(xmin / pitch);
            for (double x = first * pitch; x < xmax; x += pitch) {
                double xa = Math.max(xmin, x), xb = Math.min(xmax, x + pitch);
                if (xb - xa < 0.05) continue;
                if (spanMissing(false, (xa + xb) / 2)) continue;
                double lo = span[0], hi = span[1];
                if (hi - lo < 0.3) continue;

                int idx = (int) Math.floorMod(Math.round(x / pitch), 3L);
                if (idx == 0) scratch.set(255, 30, 30, clampAlpha(60 * sa));
                else if (idx == 1) scratch.set(30, 255, 30, clampAlpha(60 * sa));
                else scratch.set(40, 60, 255, clampAlpha(60 * sa));
                if (scratch.a <= 0) break;

                rect(xa, lo, xb, hi);
                stripes++;
            }
        }

        // Scanlines, crawling slowly down the screen (dark lines, only across the shape).
        double spacing = SCANLINE_SPACING;
        double phase = (time * 9) % spacing;
        long k0 = (long) Math.floor((ymin - phase) / spacing);
        for (double yk = phase + k0 * spacing; yk < ymax; yk += spacing) {
            double ya = Math.max(ymin, yk), yb = Math.min(ymax, yk + SCANLINE_THICKNESS);
            if (yb - ya < 0.05) continue;
            band(ya, yb, 0, 0, 0, 58 * sa);
        }
    }

    /**
     * Fills the part of the current triangle between two heights as a trapezoid.
     */
    private void band(double y0, double y1, int r, int g, int b, double a) {
        int alpha = clampAlpha(a);
        if (alpha <= 0 || y1 <= y0) return;
        if (spanMissing(true, y0)) return;
        double l0 = span[0], h0 = span[1];
        if (spanMissing(true, y1)) return;
        double l1 = span[0], h1 = span[1];
        if (h0 - l0 < 0.01 && h1 - l1 < 0.01) return;

        scratch.set(r, g, b, alpha);
        wound(l0, y0, h0, y0, h1, y1, scratch);
        wound(l0, y0, h1, y1, l1, y1, scratch);
    }

    private void rect(double x0, double y0, double x1, double y1) {
        if (x1 <= x0 || y1 <= y0) return;
        wound(x0, y0, x0, y1, x1, y1, scratch);
        wound(x0, y0, x1, y1, x1, y0, scratch);
    }

    /**
     * Where the current triangle lies along a horizontal line at v (horizontal = true, gives x range)
     * or a vertical line at v (horizontal = false, gives y range). Result goes in {@link #span}.
     *
     * @return true if the triangle does not reach that line at all (nothing was written to span)
     */
    private boolean spanMissing(boolean horizontal, double v) {
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 3; i++) {
            int j = (i + 1) % 3;
            double a1 = horizontal ? vy[i] : vx[i], a2 = horizontal ? vy[j] : vx[j];
            double b1 = horizontal ? vx[i] : vy[i], b2 = horizontal ? vx[j] : vy[j];
            if (a1 == a2) {
                if (v == a1) {
                    lo = Math.min(lo, Math.min(b1, b2));
                    hi = Math.max(hi, Math.max(b1, b2));
                }
                continue;
            }
            if (v < Math.min(a1, a2) || v > Math.max(a1, a2)) continue;
            double b = b1 + (v - a1) / (a2 - a1) * (b2 - b1);
            lo = Math.min(lo, b);
            hi = Math.max(hi, b);
        }
        span[0] = lo;
        span[1] = hi;
        return !(hi >= lo);
    }

    private static int clampAlpha(double a) {
        return (int) Math.round(Math.max(0, Math.min(255, a)));
    }

    // Power animation

    private void drawPowerGlow(int r, int g, int b) {
        double pulse = 0.9 + 0.1 * Math.sin(time * 95);

        // The line, while it is stretching out and while the picture is unfolding around it.
        if (lineGlow > 0.01 && sx > 0.01) {
            double halfWidth = width / 2 * sx;
            double left = cx - halfWidth, right = cx + halfWidth;
            double strength = lineGlow * pulse;

            line(left, right, cy, strength, r, g, b);
        }

        // The white-hot dot: alone at the very start / end, and as a hot spot in the middle of the line.
        if (dotGlow > 0.01) {
            double radius = unit * (2.5 + 4.5 * dotGlow) * (1 - 0.6 * sx);
            if (radius > 0.4) {
                double[] scale = {4.2, 2.6, 1.5, 1.0};
                int[] alpha = {26, 60, 140, 255};
                for (int i = 0; i < scale.length; i++) {
                    disc(cx, cy, radius * scale[i], toWhite(r, i == 3 ? 1 : 0.5 + 0.15 * i),
                        toWhite(g, i == 3 ? 1 : 0.5 + 0.15 * i), toWhite(b, i == 3 ? 1 : 0.5 + 0.15 * i),
                        alpha[i] * Math.min(1, dotGlow * 1.5) * pulse);
                }
            }
        }
    }

    /**
     * One glowing horizontal line: wide soft glow layers, a tinted core, then a white-hot centre.
     */
    private void line(double x0, double x1, double y, double strength, int r, int g, int b) {
        double u = Math.min(unit, 2.5);
        double[] half = {7 * u, 4 * u, 2.2 * u, 1.2 * u, 0.55 * u};
        int[] alpha = {22, 45, 95, 210, 255};
        double[] white = {0, 0, 0, 0.6, 1};

        for (int i = 0; i < half.length; i++) {
            fill(x0, y - half[i], x1, y + half[i],
                toWhite(r, white[i]), toWhite(g, white[i]), toWhite(b, white[i]), alpha[i] * strength);
        }
    }

    // Drawing helpers

    /**
     * Fills an axis-aligned rectangle in a single colour (alpha is 0 to 255 and may be fractional).
     */
    private void fill(double x0, double y0, double x1, double y1, int r, int g, int b, double a) {
        int alpha = (int) Math.round(Math.max(0, Math.min(255, a)));
        if (alpha <= 0 || x1 <= x0 || y1 <= y0) return;

        scratch.set(r, g, b, alpha);
        tri(x0, y0, x0, y1, x1, y1);
        tri(x0, y0, x1, y1, x1, y0);
    }

    /**
     * Fills a disc as a fan of triangles.
     */
    private void disc(double dx, double dy, double radius, int r, int g, int b, double a) {
        int alpha = (int) Math.round(Math.max(0, Math.min(255, a)));
        if (alpha <= 0 || radius <= 0) return;

        scratch.set(r, g, b, alpha);
        int steps = 20;
        double px = dx + radius, py = dy;
        for (int i = 1; i <= steps; i++) {
            double angle = Math.PI * 2 * i / steps;
            double nx = dx + Math.cos(angle) * radius, ny = dy + Math.sin(angle) * radius;
            tri(dx, dy, px, py, nx, ny);
            px = nx;
            py = ny;
        }
    }

    /**
     * Draws a triangle in the scratch colour, always wound the same way round as Meteor's own quads,
     * whatever order the points were given in (Meteor's 2D pipeline can cull the other winding).
     */
    private void tri(double x1, double y1, double x2, double y2, double x3, double y3) {
        wound(x1, y1, x2, y2, x3, y3, scratch);
    }

    private void wound(double x1, double y1, double x2, double y2, double x3, double y3, Color color) {
        double cross = (x2 - x1) * (y3 - y1) - (y2 - y1) * (x3 - x1);
        if (cross > 0) sink.triangle(x1, y1, x3, y3, x2, y2, color);
        else sink.triangle(x1, y1, x2, y2, x3, y3, color);
    }

    private static double rnd(double seed) {
        double v = Math.sin(seed * 12.9898) * 43758.5453;
        return v - Math.floor(v);
    }

    private static double smooth(double u) {
        u = Math.max(0, Math.min(1, u));
        return u * u * (3 - 2 * u);
    }

    private static double easeOutCubic(double u) {
        double inv = 1 - Math.max(0, Math.min(1, u));
        return 1 - inv * inv * inv;
    }

    /**
     * Ease-out with a small overshoot past 1 before settling.
     */
    private static double easeOutBack(double u) {
        u = Math.max(0, Math.min(1, u));
        double c1 = 1.9, c3 = c1 + 1;
        return 1 + c3 * Math.pow(u - 1, 3) + c1 * Math.pow(u - 1, 2);
    }

    private static int toWhite(int from, double amount) {
        return (int) Math.round(from + (255 - from) * amount);
    }
}
