package com.seasonalscape;

import com.retronpcswapper.RetroDrawCallbacks;
import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.plugins.gpu.GpuPlugin;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class WinterTreeFrostLifecycleTest
{
    @Test
    public void retroGpuRetainsTreeSnowAndRejectsAnUnsupportedDelegate()
    {
        Fixture f = new Fixture();
        GpuPlugin gpu = new GpuPlugin();
        f.callbacks = gpu;
        try
        {
            f.collect(f.tree(0, 0), f.tree(128, 0));
            f.nextTick();
            Snow existing = f.activeObject();
            assertEquals(1, f.activeCount());

            RetroDrawCallbacks retro = new RetroDrawCallbacks(gpu);
            f.callbacks = retro;
            f.refresh();
            f.nextTick();
            assertTrue("Enabling Retro does not remove existing tree snow", existing.active);
            assertEquals("Queued snow still appears through the wrapper", 2, f.activeCount());
            assertEquals(2, WinterTreeFrost.getCount(f.owner));
            int builds = f.lights, objects = f.objects.size();

            retro.setDelegate(proxy(DrawCallbacks.class, (object, method, args) -> {
                throw new AssertionError("Unknown renderer must never be called: " + method);
            }));
            f.nextTick();
            assertEquals("Unsupported wrapped renderers clear existing snow", 0, f.activeCount());
            assertEquals(0, WinterTreeFrost.getCount(f.owner));
            f.nextTick();
            assertEquals("Rejected renderers cannot rebuild pending snow", builds, f.lights);
            assertEquals(objects, f.objects.size());
        }
        finally { WinterTreeFrost.restore(f.owner); }
    }

    @Test
    public void nearbyTreesAppearOnePerClientCycleAndStopAtTheNearestTreeLimit()
    {
        Fixture f = new Fixture();
        List<Tree> trees = new ArrayList<>();
        for (int i = 0; i < 30; i++) { trees.add(f.tree(200 + i * 40, 0)); }
        try
        {
            f.collect(trees);
            assertEquals("Scene discovery does not build models", 0, f.lights);
            assertEquals("Scene discovery does not activate objects", 0, f.objects.size());
            for (int i = 0; i < 30; i++)
            {
                int builds = f.lights, objects = f.objects.size();
                f.cycle++;
                WinterTreeFrost.tick(f.owner);
                assertTrue("At most one model is lit in a client cycle", f.lights - builds <= 1);
                assertTrue("At most one tree is activated in a client cycle", f.objects.size() - objects <= 1);
                int afterBuilds = f.lights, afterObjects = f.objects.size();
                WinterTreeFrost.tick(f.owner);
                assertEquals("Repeated callbacks cannot spend the cycle budget twice", afterBuilds, f.lights);
                assertEquals(afterObjects, f.objects.size());
            }
            assertEquals("Dense woodland stays within the active-tree ceiling", 24,
                WinterTreeFrost.getCount(f.owner));
            assertEquals(24, f.activeCount());
            for (int i = 0; i < trees.size(); i++)
            {
                assertEquals("The nearest trees receive snow first", i < 24, f.activeAt(trees.get(i).point));
            }
        }
        finally { WinterTreeFrost.restore(f.owner); }
        assertEquals(0, f.activeCount());
    }

    @Test
    public void walkingRetainsNearbySnowAndReusesItsModelAfterLeavingAndReturning()
    {
        Fixture f = new Fixture();
        Tree tree = f.tree(0, 0);
        try
        {
            f.collect(tree);
            f.nextTick();
            Snow first = f.objects.get(0);
            Model fittedModel = first.model;
            assertTrue(first.active);
            assertSame(tree.point, first.location);
            assertEquals(tree.z, first.z);
            assertEquals(tree.orientation, first.orientation);
            assertEquals(0, first.plane);
            int builds = f.lights;

            // New trees enter within twenty-four tiles, but an existing coating
            // survives small movements through the surrounding retention band.
            f.move(25 * 128, 0);
            f.refresh();
            f.nextTick();
            assertTrue("Walking through the retention band does not flicker snow", first.active);
            assertEquals(builds, f.lights);

            f.move(27 * 128, 0);
            f.refresh();
            f.nextTick();
            assertFalse("Distant trees stop submitting their snow geometry", first.active);
            assertEquals(0, WinterTreeFrost.getCount(f.owner));

            f.move(0, 0);
            f.refresh();
            f.nextTick();
            assertEquals(1, WinterTreeFrost.getCount(f.owner));
            Snow returned = f.activeObject();
            assertSame("Returning reuses the fitted model", fittedModel, returned.model);
            assertEquals("Walking back does not relight cached geometry", builds, f.lights);
        }
        finally { WinterTreeFrost.restore(f.owner); }
    }

    @Test
    public void despawningTreesAndRestoringTheSeasonRemoveObjectsWithoutRecreation()
    {
        Fixture f = new Fixture();
        Tree first = f.tree(0, 0), second = f.tree(128, 0);
        try
        {
            f.collect(first, second);
            f.nextTick();
            f.nextTick();
            assertEquals(2, f.activeCount());
            f.collect(second);
            assertFalse("A tree omitted from the next scene scan loses its snow", f.activeAt(first.point));
            assertTrue(f.activeAt(second.point));
            assertEquals(1, WinterTreeFrost.getCount(f.owner));

            WinterTreeFrost.restore(f.owner);
            assertEquals(0, f.activeCount());
            assertEquals(0, WinterTreeFrost.getCount(f.owner));
            int builds = f.lights, objects = f.objects.size();
            f.nextTick();
            f.refresh();
            f.nextTick();
            assertEquals("Restoring clears pending work as well as active objects", objects, f.objects.size());
            assertEquals(builds, f.lights);
        }
        finally { WinterTreeFrost.restore(f.owner); }
    }

    @Test
    public void queuedWorkCannotAppearAfterLogoutSceneReplacementOrPlaneChange()
    {
        for (int transition = 0; transition < 3; transition++)
        {
            Fixture f = new Fixture();
            try
            {
                f.collect(f.tree(0, 0), f.tree(128, 0));
                f.nextTick();
                assertEquals(1, f.activeCount());
                int builds = f.lights, objects = f.objects.size();
                if (transition == 0) { f.gameState = GameState.LOGIN_SCREEN; }
                else if (transition == 1) { f.scene = f.newScene(); }
                else { f.plane = 1; }
                f.nextTick();
                assertEquals("Invalid scene context cannot build queued snow", builds, f.lights);
                assertEquals(objects, f.objects.size());
                assertEquals("Existing snow is removed in an invalid scene context", 0, f.activeCount());
                assertEquals(0, WinterTreeFrost.getCount(f.owner));
            }
            finally { WinterTreeFrost.restore(f.owner); }
        }
    }

    @Test
    public void exploringSeparateGrovesBoundsInactiveModelMemory() throws Exception
    {
        Fixture f = new Fixture();
        int[][] groves = {{-25 * 128, -25 * 128}, {25 * 128, -25 * 128}, {0, 25 * 128}};
        List<Tree> trees = new ArrayList<>();
        for (int[] grove : groves)
        {
            for (int i = 0; i < 20; i++) { trees.add(f.tree(grove[0] + i * 8, grove[1])); }
        }
        try
        {
            f.move(groves[0][0], groves[0][1]);
            f.collect(trees);
            for (int[] grove : groves)
            {
                f.move(grove[0], grove[1]);
                f.refresh();
                for (int i = 0; i < 20; i++) { f.nextTick(); }
                assertEquals("Only the visited grove submits snow objects", 20, f.activeCount());
            }
            assertEquals("Each distinct tree is fitted once during the initial walk", 60, f.lights);
            Field statesField = WinterTreeFrost.class.getDeclaredField("STATES");
            statesField.setAccessible(true);
            Object state = ((Map<?, ?>) statesField.get(null)).get(f.owner);
            Field modelsField = state.getClass().getDeclaredField("models");
            modelsField.setAccessible(true);
            assertEquals("Walking around a dense scene cannot retain unlimited inactive models",
                48, ((Map<?, ?>) modelsField.get(state)).size());
        }
        finally { WinterTreeFrost.restore(f.owner); }
    }

    @Test
    public void reusedSceneBaseChangesDiscardOldCoordinatesAndQueuedWork()
    {
        for (boolean scanBeforeTick : new boolean[]{false, true})
        {
            for (boolean changeX : new boolean[]{false, true})
            {
                Fixture f = new Fixture();
                Tree first = f.tree(0, 0), pending = f.tree(128, 0);
                try
                {
                    f.collect(first, pending);
                    f.nextTick();
                    Snow old = f.activeObject();
                    Scene scene = f.scene;
                    int builds = f.lights, objects = f.objects.size();
                    if (changeX) { f.baseX += 8; }
                    else { f.baseY += 8; }
                    assertSame("The Scene instance is deliberately reused", scene, f.scene);

                    if (scanBeforeTick)
                    {
                        // A completed rescan may arrive before the client-tick
                        // guard; accepting it must still clear the old context.
                        f.collect(first, pending);
                    }
                    else { f.nextTick(); }
                    assertFalse("Base changes remove objects anchored in the old scene", old.active);
                    assertEquals(0, WinterTreeFrost.getCount(f.owner));
                    assertEquals("Invalidating a base change never builds queued geometry", builds, f.lights);
                    assertEquals(objects, f.objects.size());

                    if (!scanBeforeTick) { f.collect(first, pending); }
                    f.nextTick();
                    assertEquals(1, f.activeCount());
                    assertNotSame("The new scene context creates fresh object placement", old, f.activeObject());
                    assertNotSame("Old coordinate-dependent fitted geometry is discarded",
                        old.model, f.activeObject().model);
                }
                finally { WinterTreeFrost.restore(f.owner); }
            }
        }
    }

    @Test
    public void directDespawnRemovalCancelsBothQueuedAndActiveSnowImmediately()
    {
        Fixture f = new Fixture();
        Tree active = f.tree(0, 0), pending = f.tree(128, 0);
        try
        {
            f.collect(active, pending);
            f.nextTick();
            assertTrue(f.activeAt(active.point));
            int builds = f.lights, objects = f.objects.size();
            WinterTreeFrost.remove(f.owner, pending.object);
            f.nextTick();
            assertEquals("A despawned queued tree never builds a model", builds, f.lights);
            assertEquals(objects, f.objects.size());
            assertEquals(1, f.activeCount());

            WinterTreeFrost.remove(f.owner, active.object);
            assertEquals("Despawn unregisters existing snow without waiting for a scan", 0, f.activeCount());
            assertEquals(0, WinterTreeFrost.getCount(f.owner));
            WinterTreeFrost.remove(f.owner, active.object);
            f.nextTick();
            assertEquals("Repeated removal is harmless and cannot resurrect an entry", objects, f.objects.size());
            assertEquals(builds, f.lights);
        }
        finally { WinterTreeFrost.restore(f.owner); }
    }

    private static final class Fixture
    {
        final Object owner = new Object();
        final int originX = 40 * 128 + 64, originY = 40 * 128 + 64;
        final List<Snow> objects = new ArrayList<>();
        LocalPoint point = new LocalPoint(originX, originY, WorldView.TOPLEVEL);
        int cycle = 100, plane, lights, nextId = 1, baseX = 3200, baseY = 3200;
        GameState gameState = GameState.LOGGED_IN;
        DrawCallbacks callbacks;
        Scene scene = newScene();
        final WorldView world = proxy(WorldView.class, (object, method, args) -> {
            switch (method)
            {
                case "getId": return WorldView.TOPLEVEL;
                case "getPlane": return plane;
                case "getScene": return scene;
                case "isTopLevel": return true;
                case "isInstance": return false;
                default: throw new AssertionError(method);
            }
        });
        final Player player = proxy(Player.class, (object, method, args) -> {
            switch (method)
            {
                case "getLocalLocation": return point;
                case "getWorldLocation": return new WorldPoint(baseX + point.getSceneX(),
                    baseY + point.getSceneY(), plane);
                case "getWorldView": return world;
                default: throw new AssertionError(method);
            }
        });
        final Client client = proxy(Client.class, (object, method, args) -> {
            switch (method)
            {
                case "getTopLevelWorldView": return world;
                case "getScene": return scene;
                case "getLocalPlayer": return player;
                case "getGameState": return gameState;
                case "getGameCycle": return cycle;
                case "getPlane": return plane;
                case "getBaseX": return baseX;
                case "getBaseY": return baseY;
                case "getDrawCallbacks": return callbacks;
                case "loadModelData": return new Mesh(this).modelData();
                case "mergeModels": return ((ModelData[]) args[0])[0];
                case "createRuneLiteObject":
                    Snow snow = new Snow((Client) object);
                    objects.add(snow);
                    return snow;
                default: throw new AssertionError(method);
            }
        });

        Scene newScene()
        {
            return proxy(Scene.class, (object, method, args) -> {
                switch (method)
                {
                    case "getWorldViewId": return WorldView.TOPLEVEL;
                    case "getBaseX": return baseX;
                    case "getBaseY": return baseY;
                    case "isInstance": return false;
                    default: throw new AssertionError(method);
                }
            });
        }

        Tree tree(int dx, int dy)
        {
            return new Tree(new LocalPoint(originX + dx, originY + dy, WorldView.TOPLEVEL), nextId++);
        }

        void collect(Tree... trees) { collect(java.util.Arrays.asList(trees)); }
        void collect(List<Tree> trees)
        {
            WinterTreeFrost.begin(owner);
            for (Tree tree : trees)
            {
                assertTrue(WinterTreeFrost.update(owner, client, tree.object, tree.model));
            }
            WinterTreeFrost.end(owner);
        }
        void move(int dx, int dy) { point = new LocalPoint(originX + dx, originY + dy, WorldView.TOPLEVEL); }
        void refresh() { WinterTreeFrost.refresh(owner, client); }
        void nextTick() { cycle++; WinterTreeFrost.tick(owner); }
        int activeCount() { return (int) objects.stream().filter(object -> object.active).count(); }
        boolean activeAt(LocalPoint location)
        {
            return objects.stream().anyMatch(object -> object.active && location.equals(object.location));
        }
        Snow activeObject()
        {
            return objects.stream().filter(object -> object.active).findFirst().orElseThrow(AssertionError::new);
        }
    }

    private static final class Tree
    {
        final LocalPoint point;
        final int id, z = -240, orientation = 512;
        final Model model = proxy(Model.class, (object, method, args) -> {
            switch (method)
            {
                case "getVerticesX": return new float[]{-96, 96, -96};
                case "getVerticesY": return new float[]{-200, -200, -200};
                case "getVerticesZ": return new float[]{-96, -96, 96};
                case "getFaceIndices1": return new int[]{0};
                case "getFaceIndices2": return new int[]{1};
                case "getFaceIndices3": return new int[]{2};
                case "getFaceTextures": return new short[]{8};
                case "getFaceColors3": return new int[]{80};
                case "getFaceTransparencies": return null;
                case "getVerticesCount": return 3;
                case "getFaceCount": return 1;
                default: throw new AssertionError(method);
            }
        });
        final GameObject object;
        Tree(LocalPoint point, int id)
        {
            this.point = point;
            this.id = id;
            object = proxy(GameObject.class, (self, method, args) -> {
                switch (method)
                {
                    case "getLocalLocation": return point;
                    case "getWorldLocation": return new WorldPoint(3200 + point.getSceneX(), 3200 + point.getSceneY(), 0);
                    case "getPlane": return 0;
                    case "getX": return point.getX();
                    case "getY": return point.getY();
                    case "getZ": return z;
                    case "getId": return id;
                    case "getModelOrientation": case "getOrientation": return orientation;
                    case "getRenderable": return model;
                    default: throw new AssertionError(method);
                }
            });
        }
    }

    private static final class Snow extends RuneLiteObject
    {
        Model model;
        LocalPoint location;
        int plane, z, orientation;
        boolean active;
        Snow(Client client) { super(client); }
        @Override public void setModel(Model model) { this.model = model; }
        @Override public void setLocation(LocalPoint location, int plane) { this.location = location; this.plane = plane; }
        @Override public void setZ(int z) { this.z = z; }
        @Override public void setOrientation(int orientation) { this.orientation = orientation; }
        @Override public void setActive(boolean active) { this.active = active; }
    }

    /** Minimal cache mesh with cloneable arrays and an observable lighting cost. */
    private static final class Mesh
    {
        final Fixture fixture;
        float[] x = {-8, 8, 0}, y = {0, 0, 0}, z = {-8, -8, 8};
        short[] colors = {123};
        byte[] transparencies;
        Mesh(Fixture fixture) { this.fixture = fixture; }
        ModelData modelData()
        {
            return proxy(ModelData.class, (object, method, args) -> {
                switch (method)
                {
                    case "getVerticesX": return x;
                    case "getVerticesY": return y;
                    case "getVerticesZ": return z;
                    case "getVerticesCount": return 3;
                    case "getFaceCount": return 1;
                    case "getFaceIndices1": return new int[]{0};
                    case "getFaceIndices2": return new int[]{1};
                    case "getFaceIndices3": return new int[]{2};
                    case "getFaceColors": return colors;
                    case "getFaceTextures": return null;
                    case "getFaceTransparencies": return transparencies;
                    case "shallowCopy":
                        Mesh copy = new Mesh(fixture);
                        copy.x = x; copy.y = y; copy.z = z; copy.colors = colors;
                        return copy.modelData();
                    case "cloneVertices": x = x.clone(); y = y.clone(); z = z.clone(); return object;
                    case "cloneColors": colors = colors.clone(); return object;
                    case "cloneTransparencies": transparencies = new byte[1]; return object;
                    case "translate": return object;
                    case "light":
                        fixture.lights++;
                        return proxy(Model.class, (self, name, parameters) -> { throw new AssertionError(name); });
                    default: throw new AssertionError(method);
                }
            });
        }
    }

    private interface Call
    {
        Object invoke(Object proxy, String method, Object[] arguments);
    }

    private static <T> T proxy(Class<T> type, Call call)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (object, method, args) -> {
                switch (method.getName())
                {
                    case "hashCode": return System.identityHashCode(object);
                    case "equals": return object == args[0];
                    case "toString": return type.getSimpleName() + " fixture";
                    default: return call.invoke(object, method.getName(), args);
                }
            }));
    }
}
