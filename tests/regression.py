#!/usr/bin/env python3
"""Run the real flight handlers against small headless Minecraft stand-ins.

This compiles selected method bodies directly from VolytraFly.java. It does not
duplicate the movement pipeline or listener implementation. Unrelated flight,
rendering, networking and inventory dependencies are replaced with stand-ins.
Run with Python 3 and JDK 25: python3 tests/regression.py.
"""

from pathlib import Path
import os
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "src/main/java/com/volytrafly/modules/movement/volytrafly/VolytraFly.java"


def extract_block(source, declaration, optional=False):
    match = re.search(declaration, source)
    if not match:
        if optional:
            return ""
        raise ValueError(f"Production declaration missing: {declaration}")
    start = source.index("{", match.start())
    # Ignore braces in Java strings, character literals and comments.
    tokens = re.finditer(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/|[{}]', source[start:])
    depth = 0
    for token in tokens:
        if token.group() == "{":
            depth += 1
        elif token.group() == "}":
            depth -= 1
            if depth == 0:
                return source[match.start():start + token.end()]
    raise ValueError("Unbalanced production Java block")


FIXTURE = r"""
import java.util.*;
import java.lang.reflect.*;

public class FlightRegression {
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @interface EventHandler {}
    static class Setting<T> {
        T value;
        Setting(T value) { this.value = value; }
        T get() { return value; }
    }
    interface IVec3 { Vec3 meteor$set(double x, double y, double z); }
    static class Vec3 implements IVec3 {
        double x, y, z;
        Vec3(double x, double y, double z) { meteor$set(x, y, z); }
        public Vec3 meteor$set(double x, double y, double z) { this.x=x; this.y=y; this.z=z; return this; }
        static Vec3 directionFromRotation(double pitch, double yaw) {
            return new Vec3(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
        }
        Vec3 scale(double amount) { return new Vec3(x*amount, y*amount, z*amount); }
        Vec3 add(Vec3 v) { return new Vec3(x+v.x, y+v.y, z+v.z); }
        Vec3 normalize() { return this; }
        double getX() { return x; }
        double getZ() { return z; }
        double x() { return x; }
        double z() { return z; }
    }
    static class Key { boolean pressed; boolean isDown() { return pressed; } void setDown(boolean v) { pressed=v; } }
    static class Options { Key keyUp=new Key(), keyDown=new Key(), keyLeft=new Key(), keyRight=new Key(); }
    static class EquipmentSlot { static final int CHEST=0; }
    static class Items { static final Object ELYTRA=new Object(); }
    static class DataComponents { static final Object GLIDER=new Object(); }
    static class Stack {
        Object item=Items.ELYTRA;
        Object getItem() { return item; }
        boolean has(Object component) { return item==Items.ELYTRA; }
    }
    static class Player {
        double x, y=120, z;
        boolean ground, gliding=true, horizontalCollision;
        int velocityChanges;
        Stack stack=new Stack();
        ClientPacketListener connection=new ClientPacketListener();
        Stack getItemBySlot(int slot) { return stack; }
        boolean isFallFlying() { return gliding; }
        boolean onGround() { return ground; }
        boolean isInWater() { return false; }
        double getX() { return x; }
        double getY() { return y; }
        double getZ() { return z; }
        double getYRot() { return 0; }
        Vec3 getDeltaMovement() { return new Vec3(1, 0, 0); }
        Vec3 position() { return new Vec3(x, y, z); }
        void setDeltaMovement(double x, double y, double z) { velocityChanges++; }
    }
    static class LocalPlayer extends Player {}
    interface ChunkLookup { boolean loaded(int x, int z); }
    static class ClientLevel {
        ChunkLookup lookup=(x,z)->true;
        ClientLevel getChunkSource() { return this; }
        boolean hasChunk(int x,int z) { return lookup.loaded(x,z); }
        BlockHitResult clip(ClipContext context) { return null; }
    }
    static class ClientPacketListener { void send(Object packet) {} }
    static class ServerboundPlayerCommandPacket {
        enum Action { START_FALL_FLYING }
        ServerboundPlayerCommandPacket(Player p, Action m) {}
    }
    static class ServerboundMovePlayerPacket { record StatusOnly(boolean ground, boolean collision) {} }
    static class ClipContext {
        enum Block { COLLIDER }
        enum Fluid { NONE }
        ClipContext(Vec3 from, Vec3 to, Block shape, Fluid fluid, Player p) {}
    }
    static class HitResult { enum Type { BLOCK } }
    static class BlockHitResult { HitResult.Type getType() { return HitResult.Type.BLOCK; } }
    static class PlayerMoveEvent { Vec3 movement; PlayerMoveEvent(double x,double y,double z) { movement=new Vec3(x,y,z); } }
    static class TickEvent { static class Post {} }
    static class GameLeftEvent {}
    static class Bus {
        Set<Object> subscribers=Collections.newSetFromMap(new IdentityHashMap<>());
        void subscribe(Object listener) { subscribers.add(listener); }
        void unsubscribe(Object listener) { subscribers.remove(listener); }
        void post(Object event) {
            for (Object listener : List.copyOf(subscribers)) {
                for (Method method : listener.getClass().getDeclaredMethods()) {
                    if (method.isAnnotationPresent(EventHandler.class) && method.getParameterTypes()[0]==event.getClass()) {
                        try { method.setAccessible(true); method.invoke(listener,event); }
                        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
                    }
                }
            }
        }
    }
    static class MeteorClient { static Bus EVENT_BUS=new Bus(); }
    static class Client {
        LocalPlayer player=new LocalPlayer();
        ClientLevel level=new ClientLevel();
        Options options=new Options();
        ClientPacketListener getConnection() { return player.connection; }
    }
    static class Flight {
        Client mc=new Client();
        enum ChestSwapMode { Always, Never, WaitForGround }
        enum AssistantSituation { RUBBERBAND_CAP, UNLOADED_CHUNKS, NO_CRASH, CHUNKS }
        Setting<Boolean> bypassMode=new Setting<>(false), stopInWater=new Setting<>(false),
            dontGoIntoUnloadedChunks=new Setting<>(false), noCrash=new Setting<>(false),
            mappingMode=new Setting<>(false), autoPilot=new Setting<>(false),
            highwayMode=new Setting<>(false), instaDrop=new Setting<>(false);
        Setting<Integer> accelerationDelay=new Setting<>(0), crashLookAhead=new Setting<>(3),
            mappingRenderRadius=new Setting<>(0);
        Setting<Double> accelerationStep=new Setting<>(0.3), accelerationPlateau=new Setting<>(15.0),
            startSpeed=new Setting<>(1.0), horizontalSpeed=new Setting<>(15.0);
        Setting<ChestSwapMode> chestSwap=new Setting<>(ChestSwapMode.Never);
        boolean active, atMaxSpeed, lastJumpPressed, buildingModeEngaged, mappingWaitingForChunks, lastForwardPressed;
        int jumpTimer, accelerationDelayTicks, bypassAccelerationDelayTicks, rubberbandCapTicksLeft, swaps;
        double ticksLeft, acceleration=1, lastGlideIntendedSpeed, velX,velY,velZ;
        Vec3 lastGlidePos, forward, right, highwaySteerTarget;
        Object highwayPathMob, highwayPathMobWorld;
        ClientLevel groundListenerWorld, instaDropListenerWorld;
        ArrayDeque<Long> rubberbandQualifyingTimestamps=new ArrayDeque<>();
        StaticGroundListener staticGroundListener=new StaticGroundListener();
        StaticInstaDropListener staticInstadropListener=new StaticInstaDropListener();
        boolean isActive() { return active; }
        void ensureVisualsAdded() {}
        void resetAssistant() {}
        void resetHighwayState() {}
        void resetVerticalAcceleration() {}
        void resetHorizontalAcceleration() { acceleration=startSpeed.get(); }
        void swapToChestSwap() { swaps++; }
        void releaseAvoidance() {}
        void releaseVerticalStep() {}
        void releaseHighwayKeys() {}
        void autoTakeoff() {}
        void updatePlayerAvoidance() {}
        void handleFallMultiplier() {}
        void handleAutopilot() {}
        void resetRampOnCollision(Player p) {}
        void handleAcceleration() {}
        void handleVerticalAcceleration() {}
        void handleVerticalSpeed() {}
        void limitHighwayVerticalStep() {}
        void applyHighwaySlopeCoupling() {}
        void handleAntiSlam() {}
        void handleBuildingMode() {}
        void handleMaxHeight() {}
        void applyHighwayClearanceGuard(Player p) {}
        void assistantFlag(AssistantSituation situation) {}
        double horizontalSpeedCap() { return horizontalSpeed.get(); }
        static double nextSpeedFor(double current,double step,double plateau,double cap) { return current; }
        // PRODUCTION_HANDLERS
    }
    static int failures;
    static void check(boolean ok,String message) { if (!ok) throw new AssertionError(message); }
    static void test(String name,Runnable body) {
        MeteorClient.EVENT_BUS=new Bus();
        try { body.run(); System.out.println("PASS "+name); }
        catch (AssertionError e) { failures++; System.out.println("FAIL "+name+": "+e.getMessage()); }
    }
    static Flight pending() {
        Flight f=new Flight();
        f.chestSwap.value=Flight.ChestSwapMode.WaitForGround;
        f.instaDrop.value=true;
        f.onDeactivate();
        return f;
    }
    public static void main(String[] args) {
        for (double coordinate : new double[]{-0.01,-1,-15.99,-16,-16.01,-17,-32,0,15.99,16,32}) {
            for (boolean xAxis : new boolean[]{true,false}) {
                test("destination chunk "+coordinate+" on "+(xAxis?"X":"Z"),()->{
                    Flight f=new Flight();
                    f.bypassMode.value=true;
                    f.dontGoIntoUnloadedChunks.value=true;
                    int expected=(int)Math.floor(coordinate/16);
                    int[] observed={Integer.MAX_VALUE,Integer.MAX_VALUE};
                    f.mc.level.lookup=(x,z)->{ observed[0]=x; observed[1]=z; return true; };
                    f.onPlayerMove(new PlayerMoveEvent(xAxis?coordinate:0,0,xAxis?0:coordinate));
                    check(observed[xAxis?0:1]==expected,"expected chunk "+expected+", got "+observed[xAxis?0:1]);
                });
            }
        }
        for (boolean bypass : new boolean[]{false,true}) {
          for (boolean highway : new boolean[]{false,true}) {
            test("Mapping pauses "+(bypass?"Bypass":"normal")+(highway?" + Highway":"")+" flight",()->{
                Flight f=new Flight(); f.bypassMode.value=bypass; f.mappingMode.value=true;
                f.highwayMode.value=highway;
                if (highway) f.highwaySteerTarget=new Vec3(50,120,50);
                f.mc.options.keyUp.pressed=true;
                f.mc.level.lookup=(x,z)->false;
                PlayerMoveEvent e=new PlayerMoveEvent(1,-0.25,0);
                f.onPlayerMove(e);
                check(e.movement.x==0 && e.movement.z==0,"horizontal movement was not paused");
                check(e.movement.y==-0.25,"Mapping must preserve vertical movement");
                check(f.mappingWaitingForChunks,"missing waiting status");
                f.mc.level.lookup=(x,z)->true;
                e=new PlayerMoveEvent(1,-0.25,0);
                f.onPlayerMove(e);
                check(Math.hypot(e.movement.x,e.movement.z)>0,"flight did not resume");
                check(!f.mappingWaitingForChunks,"waiting status did not clear");
            });
          }
        }
        test("reactivation cancels pending ground swap",()->{
            Flight f=pending(); f.active=true; f.onActivate();
            f.mc.player.ground=true;
            MeteorClient.EVENT_BUS.post(new PlayerMoveEvent(0,0,0));
            check(f.swaps==0,"pending ground action swapped the active elytra");
            check(MeteorClient.EVENT_BUS.subscribers.isEmpty(),"pending listeners remain subscribed");
        });
        test("reactivation cancels pending insta-drop",()->{
            Flight f=pending(); f.active=true; f.onActivate();
            MeteorClient.EVENT_BUS.post(new TickEvent.Post());
            check(f.mc.player.velocityChanges==0,"pending drop stopped active flight");
            check(MeteorClient.EVENT_BUS.subscribers.isEmpty(),"pending listeners remain subscribed");
        });
        test("inactive landing still swaps once",()->{
            Flight f=pending(); f.mc.player.ground=true;
            MeteorClient.EVENT_BUS.post(new PlayerMoveEvent(0,0,0));
            MeteorClient.EVENT_BUS.post(new PlayerMoveEvent(0,0,0));
            check(f.swaps==1,"ground action should swap once");
        });
        test("inactive insta-drop still stops flight",()->{
            Flight f=pending(); MeteorClient.EVENT_BUS.post(new TickEvent.Post());
            check(f.mc.player.velocityChanges==1,"drop was not applied");
        });
        test("destination lookup includes position and intended movement",()->{
            Flight f=new Flight(); f.bypassMode.value=true; f.dontGoIntoUnloadedChunks.value=true;
            f.mc.player.x=-16; f.mc.player.z=16;
            f.mc.level.lookup=(x,z)->x==-2 && z==0;
            PlayerMoveEvent e=new PlayerMoveEvent(-1,0,-1);
            f.onPlayerMove(e);
            check(e.movement.x==-1 && e.movement.z==-1,"loaded destination incorrectly blocked");
            f.mc.level.lookup=(x,z)->false;
            e=new PlayerMoveEvent(-1,-0.25,-1); f.onPlayerMove(e);
            check(e.movement.x==0 && e.movement.z==0,"unloaded destination not blocked");
            check(e.movement.y==-0.25,"destination protection changed vertical movement");
        });
        test("Mapping off clears stale waiting status",()->{
            Flight f=new Flight(); f.bypassMode.value=true; f.mappingWaitingForChunks=true;
            f.mc.level.lookup=(x,z)->{ throw new AssertionError("disabled Mapping queried nearby chunks"); };
            PlayerMoveEvent e=new PlayerMoveEvent(1,0,0); f.onPlayerMove(e);
            check(!f.mappingWaitingForChunks && e.movement.x==1,"disabled Mapping still blocked movement");
        });
        test("level change cancels both actions without affecting new level",()->{
            Flight f=pending(); f.mc.level=new ClientLevel(); f.mc.player.ground=true;
            MeteorClient.EVENT_BUS.post(new PlayerMoveEvent(0,0,0));
            MeteorClient.EVENT_BUS.post(new TickEvent.Post());
            check(f.swaps==0 && f.mc.player.velocityChanges==0,"old actions affected new level");
            check(MeteorClient.EVENT_BUS.subscribers.isEmpty(),"level change leaked listeners");
        });
        test("disconnect cancels actions without requiring another movement event",()->{
            Flight f=pending(); f.mc.level=null; f.mc.player=null;
            MeteorClient.EVENT_BUS.post(new GameLeftEvent());
            check(MeteorClient.EVENT_BUS.subscribers.isEmpty(),"disconnect leaked listeners");
        });
        test("landing without an elytra completes pending swap",()->{
            Flight f=new Flight(); f.enableGroundListener();
            f.mc.player.stack.item=new Object(); f.mc.player.ground=true;
            MeteorClient.EVENT_BUS.post(new PlayerMoveEvent(0,0,0));
            check(f.swaps==0,"swapped a chest item the user had changed");
            check(MeteorClient.EVENT_BUS.subscribers.isEmpty(),"completed landing leaked listener");
        });
        test("pending drop completes when gliding ends",()->{
            Flight f=new Flight(); f.enableInstaDropListener(); f.mc.player.gliding=false;
            MeteorClient.EVENT_BUS.post(new TickEvent.Post());
            check(f.mc.player.velocityChanges==0,"drop ran after gliding ended");
            check(MeteorClient.EVENT_BUS.subscribers.isEmpty(),"completed drop leaked listener");
        });
        test("reactivation without a player still cancels actions",()->{
            Flight f=pending(); f.mc.player=null; f.active=true; f.onActivate();
            check(MeteorClient.EVENT_BUS.subscribers.isEmpty(),"early activation return leaked listeners");
        });
        test("actions are not scheduled outside a level",()->{
            Flight f=new Flight(); f.mc.level=null;
            f.enableGroundListener(); f.enableInstaDropListener();
            check(MeteorClient.EVENT_BUS.subscribers.isEmpty(),"listeners scheduled without a level");
        });
        if (failures>0) throw new AssertionError(failures+" regression checks failed");
    }
}
"""


def main():
    source = SOURCE.read_text()
    methods = ["onActivate", "onDeactivate", "onPlayerMove", "handleHorizontalSpeed",
               "applyHighwaySteering",
               "areChunksLoadedInRadius", "enableGroundListener", "disableGroundListener",
               "enableInstaDropListener", "disableInstaDropListener"]
    handlers = [extract_block(source, rf"(?:public|private|protected)\s+\w+\s+{name}\([^)]*\)") for name in methods]
    for name in ["handleMappingMode", "cancelPendingActions"]:
        handlers.append(extract_block(source, rf"private\s+void\s+{name}\([^)]*\)", optional=True))
    handlers += [extract_block(source, rf"private\s+class\s+{name}\s*") for name in ["StaticGroundListener", "StaticInstaDropListener"]]
    java = FIXTURE.replace("// PRODUCTION_HANDLERS", "\n".join(handlers))
    with tempfile.TemporaryDirectory(prefix="volytrafly-regression-") as directory:
        path = Path(directory) / "FlightRegression.java"
        path.write_text(java)
        java_home = os.environ.get("JAVA_HOME")
        javac = str(Path(java_home) / "bin/javac") if java_home else "javac"
        java_bin = str(Path(java_home) / "bin/java") if java_home else "java"
        subprocess.run([javac, "--release", "25", str(path)], check=True)
        subprocess.run([java_bin, "-cp", directory, "FlightRegression"], check=True)


if __name__ == "__main__":
    main()
