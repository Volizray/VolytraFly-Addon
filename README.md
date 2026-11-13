# VolytraFly

A [Meteor Client](https://github.com/MeteorDevelopment/meteor-client) addon that adds **VolytraFly**, a movement
module built around elytra flight: configurable acceleration curves, gentle landings, player/projectile avoidance,
autopilot, inventory management, and more.

Built from the [Meteor Addon Template](https://github.com/MeteorDevelopment/meteor-addon-template).

Defaults are tuned for **6b6t**. For some extra fun, try Player Avoidance, Overdrive, and the Auto features.

## Features

VolytraFly is organised into setting groups:

- **General** — horizontal/vertical speed and acceleration curves, max-height limit, no-crash raycasting,
  no-unloaded-chunks, stop-in-water, insta-drop, fall multiplier, auto take-off, and the rubberband speed cap
  (holds your speed just under the server's limit for a while after repeated rubberbanding at one speed)
- **Mapping** — pauses flight until nearby chunks finish loading
- **Building Mode** — eases your speed down when blocks are nearby
- **Bypass Mode** — makes elytra flight behave closer to normal vanilla flying in case of stricter anti-cheat
  (also very good at getting you through objects that slow you down)
- **Player Avoidance System** — automatically moves you away from players and other threats
- **Landing** (Anti-slam) — slows your fall as you approach the ground
- **Inventory** — replaces a worn-out elytra, optional chest-swap, and keeps a hotbar slot stocked with fireworks
- **Autopilot** — automatically flies forward (simplest auto feature)
- **Autohighway** — locks to a highway and guides you towards or away from the centre of the world while avoiding obstacles
- **Autonav** — travels to selected coordinates while avoiding obstacles; set coords manually or pick a point on Xaero's world map
- **Visuals** — HUD elements that complement VolytraFly
- **Overdrive** — warps the acceleration curve for near-instant max speed

Additional modes have their own keybinds.

## Supported Minecraft versions

| Minecraft | Notes |
|-----------|--------|
| **1.21.11** | Remapped (official Mojang mappings), Java ≥21 |
| **26.1.x** | Targets `26.1.2`; works on 26.1 / 26.1.1 / 26.1.2, Java ≥25 |
| **26.2** | Minecraft 26.2, Java ≥25 |
| **26.3** | Main / current development target, Java ≥25 |

Standalone JARs per version, plus a multi-version wrapper build.

## Building

Multi-version Gradle setup (Fallen-Breath preprocessor)

JDK 25 is recommended when building the complete multi-version wrapper.

Multi-version wrapper:

```bash
./gradlew :fabricWrapper:build
```

Standalone version (examples):

```bash
./gradlew :1.21.11:build
./gradlew :26.1:build
./gradlew :26.2:build
./gradlew :26.3:build
```

Build outputs:

- Multi-version wrapper: `build/libs/`
- Standalone version JARs: `versions/1.21.11/build/libs/` 

The first build downloads Minecraft and mod dependencies and needs a working connection to their Maven repositories.

## Development

- Main source target is **Minecraft 26.3** (Mojang names); other versions are produced via preprocess.
- Run the `Minecraft Client` run configuration in your IDE to test the addon (per version project).
- Source lives in `src/main/java/com/volytrafly`: the module is `modules/movement/volytrafly/VolytraFly.java`
  and the HUD elements are in `hud/`.
- `src/main/resources/fabric.mod.json` contains the addon’s metadata.

### Project layout

```
versions/                      # per-MC gradle.properties + mappings
src/main/                      # shared source (preprocess; main = 26.3)
build.fabric.gradle.kts        # 26.x (unobfuscated loom)
build.fabric.remap.gradle.kts  # 1.21.11 (remap loom)
fabricWrapper/                 # multi-version pack (collectSubModules)
buildSrc/
.github/workflows/
```

## 0.1.2 changes

- **Overdrive** mode — near-instant acceleration to max speed
- **Autonav** mode — fly to selected coordinates (after the player sets a target)
- Autonav route visuals
- Mapping mode applies across modes more consistently
- Fixes for no-unloaded-chunks and elytra-swapping

## License

See [LICENSE](LICENSE) (CC0).
