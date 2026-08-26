package com.volytrafly;

import com.volytrafly.modules.movement.volytrafly.VolytraFly;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class VolytraFlyAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        LOG.info("Initializing VolytraFly");

        // Modules
        Modules.get().add(new VolytraFly());
    }

    @Override
    public void onRegisterCategories() {
        // No custom categories - VolytraFly registers under the built-in Movement category.
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
