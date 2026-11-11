# VolytraFly

A [Meteor Client](https://github.com/MeteorDevelopment/meteor-client) addon that adds **VolytraFly**, a movement
module built around elytra flight: configurable acceleration curves, gentle landings, player/projectile avoidance,
autopilot, inventory management, and more.

Built from the [Meteor Addon Template](https://github.com/MeteorDevelopment/meteor-addon-template).

## Features

VolytraFly is organised into setting groups:

- **General** - horizontal/vertical speed and acceleration curves, max-height limit, no-crash raycasting,
  no-unloaded-chunks, stop-in-water, insta-drop, fall multiplier, auto take-off, and the rubberband speed cap
  (holds your speed just under the server's limit for a while after repeated rubberbanding at one speed)
- **Mapping** - Pauses flight until nearby chunks finish loading
- **Building Mode** - Eases your speed down when blocks are nearby
- **Bypass Mode** - Makes elytra flight behave closer to normal vanilla flying in case of stricter anti-cheat in the future (also very good at getting you through objects that slow you down)
- **Player Avoidance System** - Automatically moves you away from players and other threats
- **Landing** (Anti-slam) slows your fall as you approach the ground.
- **Inventory** - Replaces a worn-out elytra, optional chest-swap, and keeps a hotbar slot stocked with fireworks
- **Autopilot** - Automatically flies forward - the simplest of the auto features
- **Autohighway** - Automatically locks to a highway and either guides you towards or away from the centre of the world while avoiding obstacles
- **Autonav** - Automatically travels to selected coordinates while avoiding obstacles. Coordinates can be input, or you can use Xaero's world map to select a location
- **Visuals** - Helpful HUD elements that add to the experience of VolytraFly
- **Overdrive** - Applies a lil' bit of dark magic to warp the acceleration curve and achieve near-instant max speed

Additional modes have their own keybinds

VolytraFly comes with its defaults set to the best settings for playing on 6b6t. For some extra fun, I strongly recommend trying out Player Avoidance, Overdrive, and the new Auto features!

## Development

- Targets Minecraft 26.3 (Mojang names, no mappings) and needs JDK 25.
- Run the `Minecraft Client` run configuration in your IDE to test the addon.
- Source lives in `src/main/java/com/volytrafly`: the module is `modules/movement/volytrafly/VolytraFly.java`
  and the HUD elements are in `hud/`.
- `src/main/resources/fabric.mod.json` contains the addon's metadata.

## License

See [LICENSE](LICENSE) (CC0).

0.1.2 Changes:
- Addition of "Overdrive" mode which uses some sneaky lil' methods to force near-instant acceleration to max speed
- Addition of "Autonav" mode which will travels to selected coordinates on its own (once the player has selected a location)
- Addition of Autonav visuals to display the flight route
- Mapping mode should now be compatible and apply to all modes
- Fixed a few small issues related to no-unloaded-chunks and elytra-swapping not working as intended
