package com.volytrafly.autonav;

import com.volytrafly.VolytraFlyAddon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.MouseHandler;
//#if MC>=260300
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
//#endif

import java.lang.reflect.Field;
import java.util.HashMap;

/**
 * Notices a click on Xaero's World Map and reports which block it was on.
 *
 * <p>Nothing here is compiled against Xaero's code, so the addon builds and runs with or without
 * Xaero's World Map installed. The map screen is recognised by its class name, the mouse is polled
 * through the vanilla mouse handler, and the block under the cursor is read out of the map screen by reflection (the map
 * keeps it in two int fields that it refreshes every frame to draw the coordinates in the corner).
 * If a Xaero update renames those fields the click is reported through {@link #takeError()} and the
 * field names found are written to the log, rather than flying somewhere wrong.
 */
public final class XaeroMapClicks {
    public record Click(int x, int z) {}

    private static final String MAP_SCREEN_CLASS = "xaero.map.gui.GuiMap";
    // Names Xaero's map screen has used for the block under the cursor
    private static final String[] HOVER_X_FIELDS = {"mouseBlockPosX"};
    private static final String[] HOVER_Z_FIELDS = {"mouseBlockPosZ"};
    // A press and release further apart than this (in window pixels) was a drag, which pans the map
    private static final double CLICK_SLOP_PIXELS = 6;

    private boolean pressed;
    private String lastLoggedScreen = "";
    private double pressX, pressY;
    private String error;
    private boolean fieldsLogged;
    private final HashMap<String, Field> fieldCache = new HashMap<>();

    /** The reason the last click could not be read, once, or null. */
    public String takeError() {
        String e = error;
        error = null;
        return e;
    }

    public void reset() {
        pressed = false;
    }

    /**
     * Call once per client tick. Returns the clicked block when the given mouse button was just
     * clicked (pressed and released without dragging) on the map screen, otherwise null.
     */
    public Click poll(Minecraft mc, int button) {
        //#if MC>=260200
        Screen screen = mc.gui.screen();
//#else
//$$         Screen screen = mc.screen;
//#endif
        if (screen == null || !isMapScreen(screen)) {
            pressed = false;
            logUnrecognisedXaeroScreen(screen);
            return null;
        }

        // 26.3 reads input through SDL. Ask SDL for the button state directly (SDL buttons are
        // numbered 1 = left, 2 = middle, 3 = right, the same as InputConstants.MOUSE_BUTTON_*), and
        // also accept vanilla's own pressed flags as a backup.
        MouseHandler mouse = mc.mouseHandler;
//#if MC>=260300
        boolean sdlDown;
        double sdlCursorX;
        double sdlCursorY;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer sdlX = stack.mallocFloat(1);
            FloatBuffer sdlY = stack.mallocFloat(1);
            long sdlButtons = SDLMouse.SDL_GetMouseState(sdlX, sdlY);
            // SDL numbers its buttons 1 = left, 2 = middle, 3 = right, whatever numbering InputConstants uses
            int sdlButton = button == InputConstants.MOUSE_BUTTON_LEFT ? 1
                : button == InputConstants.MOUSE_BUTTON_RIGHT ? 3 : 2;
            sdlDown = (sdlButtons & (1L << (sdlButton - 1))) != 0;
            sdlCursorX = sdlX.get(0);
            sdlCursorY = sdlY.get(0);
        }
        boolean vanillaDown = button == InputConstants.MOUSE_BUTTON_LEFT ? mouse.isLeftPressed()
            : button == InputConstants.MOUSE_BUTTON_RIGHT ? mouse.isRightPressed()
            : mouse.isMiddlePressed();
        boolean down = sdlDown || vanillaDown;
        double[] cursorX = {sdlCursorX}, cursorY = {sdlCursorY};
//#else
//$$         boolean vanillaDown = button == InputConstants.MOUSE_BUTTON_LEFT ? mouse.isLeftPressed()
//$$             : button == InputConstants.MOUSE_BUTTON_RIGHT ? mouse.isRightPressed()
//$$             : mouse.isMiddlePressed();
//$$         boolean down = vanillaDown;
//$$         double[] cursorX = {mouse.xpos()}, cursorY = {mouse.ypos()};
//#endif

        if (down) {
            if (!pressed) {
                pressed = true;
                pressX = cursorX[0];
                pressY = cursorY[0];
            }
            return null;
        }

        if (!pressed) return null;
        pressed = false;
        double moved = Math.hypot(cursorX[0] - pressX, cursorY[0] - pressY);
        if (moved > CLICK_SLOP_PIXELS) return null;

        return readHoveredBlock(screen);
    }

    /** If Xaero renamed its map screen, say so in the log (once per screen class) instead of failing silently. */
    private void logUnrecognisedXaeroScreen(Screen screen) {
        if (screen == null) return;
        String name = screen.getClass().getName();
        if (!name.toLowerCase().contains("xaero") || name.equals(lastLoggedScreen)) return;
        lastLoggedScreen = name;
        VolytraFlyAddon.LOG.warn("Autonav: a Xaero screen is open ({}) but it is not the world map screen this addon looks for ({}).", name, MAP_SCREEN_CLASS);
    }

    private static boolean isMapScreen(Object screen) {
        for (Class<?> c = screen.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals(MAP_SCREEN_CLASS)) return true;
        }
        return false;
    }

    private Click readHoveredBlock(Object screen) {
        Integer x = readInt(screen, HOVER_X_FIELDS);
        Integer z = readInt(screen, HOVER_Z_FIELDS);
        if (x == null || z == null) {
            error = "I couldn't read the clicked spot from Xaero's World Map (this version of it may have changed) - check the log for details";
            logFieldsOnce(screen);
            return null;
        }
        return new Click(x, z);
    }

    private Integer readInt(Object screen, String[] names) {
        for (String name : names) {
            Field field = findField(screen.getClass(), name);
            if (field == null) continue;
            try {
                Object value = field.get(screen);
                if (value instanceof Number number) return number.intValue();
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // try the next name
            }
        }
        return null;
    }

    private Field findField(Class<?> type, String name) {
        String cacheKey = type.getName() + "#" + name;
        if (fieldCache.containsKey(cacheKey)) return fieldCache.get(cacheKey);

        Field found = null;
        for (Class<?> c = type; c != null && found == null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                found = field;
            } catch (NoSuchFieldException | RuntimeException ignored) {
                // keep looking up the class hierarchy
            }
        }
        fieldCache.put(cacheKey, found);
        return found;
    }

    private void logFieldsOnce(Object screen) {
        if (fieldsLogged) return;
        fieldsLogged = true;

        StringBuilder sb = new StringBuilder("Could not find the hovered-block fields on ").append(screen.getClass().getName()).append(". Fields found:");
        for (Class<?> c = screen.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                sb.append("\n  ").append(c.getSimpleName()).append('.').append(field.getName()).append(" : ").append(field.getType().getSimpleName());
            }
        }
        VolytraFlyAddon.LOG.warn(sb.toString());
    }
}
