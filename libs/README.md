# libs/

Meteor Client's Maven repo (`maven.meteordev.org`) only ever hosts the
snapshot for the Minecraft version Meteor is *currently* tracking. Now that
Meteor has moved on past 1.21.4, that snapshot coordinate is gone, so this
project can't just `modImplementation(libs.meteor.client)` like the template
normally does.

To build this addon against 1.21.4:

1. Go to <https://meteorclient.com/archive>.
2. Under the "1.21" section, download **"1.21.4 - build 42"**.
3. Save it into this folder as `meteor-client-1.21.4.jar`
   (i.e. `libs/meteor-client-1.21.4.jar`).

`build.gradle.kts` picks it up from there via
`modImplementation(files("libs/meteor-client-1.21.4.jar"))`, and Loom will
remap it against the Yarn mappings configured in
`gradle/libs.versions.toml` the same way it would a normal Maven dependency.

This file is intentionally not committed to the jar itself — only this
README is tracked in git; keep the actual `.jar` local (add it to
`.gitignore` if it isn't already).
