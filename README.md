# VolytraFly

A Meteor Client (https://github.com/MeteorDevelopment/meteor-client) addon that adds **VolytraFly**, a movement
module built around elytra flight: configurable acceleration curves, gentle landings, player/projectile avoidance,
autopilot, inventory management, and more.

This project was built from the Meteor Addon Template (https://github.com/MeteorDevelopment/meteor-addon-template).

## Features

VolytraFly is organised into setting groups:

- **General** - horizontal/vertical top speed, acceleration curves, max-height limiting, no-crash raycasting,
  stop-in-water, insta-drop, and fall-speed multiplier.
- **Mapping Mode** - pauses horizontal movement until nearby chunks finish loading, so you never outrun the world.
- **Building Mode** - eases your speed down when blocks are nearby, so you can build while flying.
- **Player Avoidance System** - moves you away from nearby players, wither skulls, arrows, and blocks, with
  optional sidestepping and a vertical-step fallback if you get stuck against a wall.
- **Landing** - slows your fall as you approach the ground to avoid fall damage.
- **Inventory** - automatically replaces a worn-out elytra and keeps a hotbar slot stocked with fireworks.
- **Autopilot** - flies forward automatically above a minimum height and can fire fireworks on an interval.

## Building

```bash
./gradlew build
```

The compiled JAR will be in `build/libs`. Move it into your `mods` folder alongside the Meteor Client mod.

## Development

- Run the `Minecraft Client` run configuration in your IDE to test the addon.
- Source lives in `src/main/java/com/volytrafly`, with the module itself in
  `modules/movement/volytrafly/VolytraFly.java`.
- `src/main/resources/fabric.mod.json` contains the addon's metadata.

## License

See [LICENSE](LICENSE) (CC0).
