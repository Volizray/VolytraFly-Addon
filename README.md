# VolytraFly

A [Meteor Client](https://github.com/MeteorDevelopment/meteor-client) addon that adds **VolytraFly**, a movement
module built around elytra flight: configurable acceleration curves, gentle landings, player/projectile avoidance,
autopilot, inventory management, and more.

Built from the [Meteor Addon Template](https://github.com/MeteorDevelopment/meteor-addon-template).

## Features

VolytraFly is organised into setting groups:

- **General** - horizontal/vertical speed and acceleration curves, max-height limit, no-crash raycasting,
  no-unloaded-chunks, stop-in-water, insta-drop, fall multiplier, auto take-off, and the rubberband speed cap
  (holds your speed just under the server's limit for a while after repeated rubberbanding at one speed).
- **Mapping** - pauses horizontal movement until nearby chunks finish loading.
- **Building Mode** - eases your speed down when blocks are nearby.
- **Bypass Mode** - keeps your real horizontal velocity and accelerates it with the General acceleration settings.
- **Player Avoidance System** - moves you away from nearby players, wither skulls, arrows and blocks, with optional
  sidestepping and an automatic vertical step if you get stuck against a wall.
- **Landing** (anti-slam) - slows your fall as you approach the ground.
- **Inventory** - replaces a worn-out elytra, optional chest-swap, and keeps a hotbar slot stocked with fireworks.
- **Autopilot** - flies forward automatically above a minimum height and can fire fireworks on an interval.
- **Highway Mode** - snaps onto the nearest world highway line (X axis, Z axis or either diagonal) and flies along it. `Intelligence` trades planning range and reaction speed
  against CPU use.
- **Visuals** - a speedometer, switch panel, elytra fuel gauge and the Volytra Assistant on Meteor's HUD, plus Future Sight (a ring showing where
  nearby players will be in the next second). `Auto-Arrange-HUD` keeps the pieces stacked around the speedometer.

Mapping, Building, Bypass, Avoidance, Landing, Autopilot and Highway Mode can each be bound to their own key
(in that mode's setting group).

## Development

- This branch builds for **Minecraft 26.1.1**, using **Java 25** and **Fabric Loader 0.19.2 or newer**.
- Use Meteor's archived **26.1.2 build 42**, which declares support for the 26.1 series.
  [Official Meteor archive](https://meteorclient.com/archive).
- Requirements: JDK 25 and Python 3 (for the headless regression checks).
- Build and check: `bash gradlew build`. The installable add-on JAR is in `build/libs/`.
- Run just the regression checks: `python3 tests/regression.py` or `bash gradlew flightRegressionTest`.
- CI builds the add-on and runs those checks for pushes and pull requests, then uploads the JAR.
- Loom is pinned to a release and Meteor to an exact timestamped snapshot in
  `gradle/libs.versions.toml`. Update these deliberately and run the full build before committing.
- The output is `build/libs/VolytraFly-0.1.1-mc26.1.1.jar`; place it in the instance's `mods` folder
  alongside Meteor. The previous 1.21.11 JAR is preserved locally under `builds/`.
- Run the `Minecraft Client` run configuration in your IDE to test the addon.
- Source lives in `src/main/java/com/volytrafly`: the module is `modules/movement/volytrafly/VolytraFly.java`
  and the HUD elements are in `hud/`.
- `src/main/resources/fabric.mod.json` contains the addon's metadata.

The regression harness compiles the actual movement and deferred-action handlers against small
headless stand-ins. It covers negative chunk boundaries, normal/Bypass Mapping pauses and resumption,
and listener cleanup on reactivation, disconnect and world changes. It does not launch Minecraft or
validate mixins, real network behavior, rendering or Meteor's event dispatch. See
[the in-game verification checklist](tests/IN_GAME_CHECKS.md) for those checks.

### Unreleased fixes

- Port Minecraft references to Mojang names and update Meteor's input, vector and HUD interfaces for 26.1.1.
- Correct unloaded-chunk checks at negative X and Z coordinates.
- Apply Mapping's pause protection to normal, Bypass and Highway movement.
- Cancel pending ground-swap and insta-drop actions on reactivation, disconnect or world changes.
- Stop waiting for a ground swap after landing when the player has already changed their chest item.

## License

See [LICENSE](LICENSE) (CC0).

0.1.1 Changes:
- Speed no longer resets to min speed value after rubberbanding, but back along the acceleration curve (so a rubberband at 300 bps reduces speed by <1%)
- Added a rubberband speed cap, so if server speed changes while in-game, VolytraFly will detect and limit your speed too for smoother flight
- Added visuals, including a speedometer, fuel gauge, light switches for modes, Volytra assistant, and future sight
- Added bypass mode, which flies using additive acceleration to act more like normal elytra flight... it might be useless but we'll see!
- Removed some unnecessary features from the options like player avoidance's vertical step since they never really need to be changed
- Added highway mode, which uses a hybrid of mob pathfinding and custom navigation to travel across highways
