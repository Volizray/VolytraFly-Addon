# In-game verification

Use Minecraft 26.1.1, Fabric Loader 0.19.2 or newer, and Meteor 26.1.2 build 42, matching
the pinned build in `gradle/libs.versions.toml`. Build with JDK 25 and Python 3 using `bash gradlew build`.
These checks require a running client; passing the headless harness does not mark them complete.

1. Enable **no-unloaded-chunks** and cross chunk boundaries on negative X and Z, including
   -1, -16 and -17. Confirm movement is allowed into loaded chunks and paused at unloaded
   destinations. Repeat with positive coordinates.
2. Enable **Mapping**, fly while nearby chunks load, and repeat with **Bypass** and **Highway Mode**.
   Horizontal motion should pause in all modes, vertical controls should still work, and flight
   should resume once the configured radius has loaded. Disable Mapping while waiting and confirm
   its warning clears and movement resumes.
3. Set **chest-swap** to **WaitForGround**, disable while airborne, then reactivate before landing.
   Landing must not trigger the previous pending swap. Repeat without reactivating: landing should
   perform one swap. Manually replace the chest item before landing: the pending action should end
   without changing that item.
4. Enable **insta-drop**, disable while gliding, and immediately reactivate. The previous pending
   drop must not stop active flight. Repeat without reactivating to confirm normal drop behavior.
5. Schedule either pending action, disconnect, and join another world. Neither action should carry
   over. Repeat with a dimension change while inactive and with no chest item change.
6. Check ordinary manual flight, avoidance and Highway steering with Mapping off, then check the
   HUD waiting status with Mapping on. Confirm the patch did not change those ordinary controls.
