# Minecraft 26.1.1 build verification

Verified locally on 2026-10-04.

## Build environment

- Minecraft 26.1.1, Java 25, Fabric Loader 0.19.5.
- Fabric Loom 1.16.3 and Gradle 9.5.1.
- Meteor 26.1.2 build 42, Maven artifact `26.1.2-20260810.135545-42`.
  Its own mod metadata allows Minecraft `~26.1` and requires Java 25 and Fabric Loader >=0.19.2.
- Add-on metadata restricts Minecraft to 26.1.1 and Meteor to the tested `26.1.2-42` build.

## Passed checks

- Clean compilation and build using `bash gradlew clean build` under Java 25.
- All 38 headless regression cases, including negative coordinates on both axes, Mapping with
  normal/Bypass/Highway movement, and deferred action cleanup.
- JAR inspection: 17 expected class files, Java 25 bytecode (major version 69), correct Minecraft,
  Java and loader requirements, and no nested JARs or native libraries.
- Development-client startup using `bash gradlew runClient`: Minecraft 26.1.1 and Meteor 26.1.2-42
  loaded, VolytraFly initialization completed, rendering/audio initialized, and the client shut down
  with a successful Gradle result. This used the workspace's separate `run/` directory and a dummy
  development player, without launcher account credentials.

Startup emitted dependency warnings about optional Baritone classes, shader attributes and the dummy
player's Realms authentication. No add-on startup exception was observed.

## Still requiring in-game verification

The startup smoke test does not establish flight, HUD appearance, server-specific behavior, or every
setting combination. Follow [IN_GAME_CHECKS.md](IN_GAME_CHECKS.md) for those checks. The headless
harness substitutes Minecraft and Meteor collaborators and does not validate their event dispatch.

## Build outputs

- `build/libs/VolytraFly-0.1.1-mc26.1.1.jar`: current Gradle output.
- `builds/VolytraFly-0.1.1-mc26.1.1.jar`: preserved local copy.
- `builds/VolytraFly-0.1.1-mc1.21.11.jar`: preserved previous build.

Place the 26.1.1 JAR alongside Meteor build 42 in the instance's `mods` directory; install only one
VolytraFly JAR in an instance.
