package com.volytrafly;

import com.volytrafly.hud.VolytraAssistantHud;
import com.volytrafly.hud.ElytraFuelHud;
import com.volytrafly.hud.FutureSightVisual;
import com.volytrafly.hud.ModeLightsHud;
import com.volytrafly.hud.SpeedometerHud;
import com.volytrafly.modules.movement.volytrafly.VolytraFly;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class VolytraFlyAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final HudGroup HUD_GROUP = new HudGroup("VolytraFly");

    @Override
    public void onInitialize() {
        LOG.info("Initializing VolytraFly");

        // Modules
        Modules.get().add(new VolytraFly());

        // HUD
        Hud.get().register(SpeedometerHud.INFO);
        Hud.get().register(ModeLightsHud.INFO);
        Hud.get().register(VolytraAssistantHud.INFO);
        Hud.get().register(ElytraFuelHud.INFO);

        // Future Sight is outside the module so its CRT power off animation can finish
        // after the module has already stopped.
        MeteorClient.EVENT_BUS.subscribe(new FutureSightVisual());
    }

    @Override
    public String getPackage() {
        return "com.volytrafly";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("Volizray", "VolytraFly-Addon");
    }
}
