package com.volytrafly.autonav;

import meteordevelopment.meteorclient.settings.IVisible;
import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.nbt.NbtCompound;

/**
 * A "setting" that is really a button: it stores nothing (always false) and is drawn as a button
 * by the widget factory registered in VolytraFlyAddon. Pressing it runs {@link #action}.
 */
public class ButtonSetting extends Setting<Boolean> {
    public Runnable action = () -> {};
    public final String buttonText;

    public ButtonSetting(String name, String description, String buttonText, IVisible visible) {
        super(name, description, false, null, null, visible);
        this.buttonText = buttonText;
    }

    @Override
    public Boolean parseImpl(String str) {
        return false;
    }

    @Override
    public boolean isValueValid(Boolean value) {
        return true;
    }

    @Override
    public NbtCompound save(NbtCompound tag) {
        return tag;
    }

    @Override
    public Boolean load(NbtCompound tag) {
        return false;
    }
}
