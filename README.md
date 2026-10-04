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

- Targets Minecraft 26.2 (Mojang names, no mappings) and needs JDK 25.
- Run the `Minecraft Client` run configuration in your IDE to test the addon.
- Source lives in `src/main/java/com/volytrafly`: the module is `modules/movement/volytrafly/VolytraFly.java`
  and the HUD elements are in `hud/`.
- `src/main/resources/fabric.mod.json` contains the addon's metadata.

## License

See [LICENSE](LICENSE) (CC0).

0.1.1 Changes:
- Speed no longer resets to min speed value after rubberbanding, but back along the acceleration curve (so a rubberband at 300 bps reduces speed by <1%)
- Added a rubberband speed cap, so if server speed changes while in-game, VolytraFly will detect and limit your speed too for smoother flight
- Added visuals, including a speedometer, fuel gauge, light switches for modes, Volytra assistant, and future sight
- Added bypass mode, which flies using additive acceleration to act more like normal elytra flight... it might be useless but we'll see!
- Removed some unnecessary features from the options like player avoidance's vertical step since they never really need to be changed
- Added highway mode, which uses a hybrid of mob pathfinding and custom navigation to travel across highways

0.1.1 (26.1.2 port):
- Updated from Minecraft 1.21.11 to 26.1.2: Yarn names replaced with Mojang names, `Vec3` coordinates read through `x()`/`y()`/`z()`, Java 25, Loom 1.16

0.1.1 (26.2 port):
- Updated from Minecraft 26.1.2 to 26.2: Meteor 26.2-SNAPSHOT, Fabric Loader 0.19.3, Loom 1.17-SNAPSHOT, Gradle 9.6.1
