package com.seasonalscape;

import com.retronpcswapper.RetroDrawCallbacks;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.plugins.gpu.GpuPlugin;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WinterSnowfallTest
{
    @Test
    public void cameraCeilingTracksHeightAboveTerrainAndRejectsInvalidInput()
    {
        float near = WinterSnowfall.ceilingForCamera(0, -1000);
        float far = WinterSnowfall.ceilingForCamera(0, -4500);
        assertTrue("Zooming out raises the visible snow column", far > near + 1500);
        assertEquals("Equivalent height above elevated terrain gives the same column", far,
            WinterSnowfall.ceilingForCamera(-800, -5300), 0);
        assertEquals("Invalid projection uses the normal taller volume", near,
            WinterSnowfall.ceilingForCamera(0, Double.NaN), 0);
        assertEquals(near, WinterSnowfall.ceilingForCamera(Float.NaN, -4500), 0);
        assertTrue("Extreme zoom cannot create an unbounded sparse column",
            WinterSnowfall.ceilingForCamera(0, -1_000_000) < far * 2);
    }

    @Test
    public void tallerSnowKeepsDenseLowerLayerAndRespondsToZoomWithoutAddingParticles() throws Exception
    {
        SnowFixture f = new SnowFixture();
        try
        {
            assertEquals("The expanded volume keeps the exact particle budget", 384, WinterSnowfall.getCount(f.owner));
            Set<Object> registered = Collections.newSetFromMap(new IdentityHashMap<>());
            registered.addAll(f.registered);
            int low = 0, high = 0, aboveOldCeiling = 0;
            float lowerMax = 0, upperMax = 0;
            float[] originalHeights = new float[f.flakes.size()];
            for (int i = 0; i < f.flakes.size(); i++)
            {
                RuneLiteObject flake = f.flakes.get(i);
                float altitude = f.altitude.getFloat(flake);
                originalHeights[i] = altitude;
                if (f.upperLayer.getBoolean(flake)) { high++; upperMax = Math.max(upperMax, altitude); }
                else { low++; lowerMax = Math.max(lowerMax, altitude); }
                if (altitude > 1500) { aboveOldCeiling++; }
                assertTrue("The horizontal footprint does not grow with the column",
                    Math.hypot(flake.getX() - f.point.getX(), flake.getY() - f.point.getY()) <= 1282);
                assertEquals("Negative scene Z places flakes above the sloping-world height",
                    -160 - altitude, flake.getZ(), 1);
            }
            assertEquals("Two thirds of the budget remain near the ground", 256, low);
            assertEquals("A dedicated upper layer fills the taller view immediately", 128, high);
            assertTrue("Stratification fills the top of the original lower column", lowerMax > 1450);
            assertTrue("The upper column is populated on its first frame", upperMax > 3100);
            assertTrue("Higher coverage is substantial instead of one stray high flake", aboveOldCeiling >= 100);

            f.cameraZ = -4800;
            int readsBefore = f.cameraReads;
            for (int step = 0; step < 40; step++) { f.tickAll(0); }
            assertEquals("Camera projection is read once per client cycle, not for every flake",
                40, f.cameraReads - readsBefore);
            int above4000 = 0;
            for (int i = 0; i < f.flakes.size(); i++)
            {
                RuneLiteObject flake = f.flakes.get(i);
                float altitude = f.altitude.getFloat(flake);
                if (f.upperLayer.getBoolean(flake))
                {
                    assertTrue("Existing upper flakes move into the new volume without waiting for respawn",
                        altitude > originalHeights[i]);
                    if (altitude > 4000) { above4000++; }
                }
                else { assertEquals("Zooming does not dilute or stretch lower snowfall", originalHeights[i], altitude, 0); }
            }
            assertTrue("Fully zoomed out views keep many flakes well above the old ceiling", above4000 >= 40);
            assertEquals("Zoom changes reuse the same active particle objects", registered, f.registered);

            f.cameraZ = -1800;
            for (int step = 0; step < 40; step++) { f.tickAll(0); }
            for (int i = 0; i < f.flakes.size(); i++)
            {
                assertEquals("Zooming back in contracts the layer without replacing its flakes",
                    originalHeights[i], f.altitude.getFloat(f.flakes.get(i)), 0.02);
            }
        }
        finally { WinterSnowfall.clear(f.owner); }
        assertTrue("Clearing removes both altitude layers", f.registered.isEmpty());
    }

    @Test
    public void eachAltitudeLayerRecyclesAtItsOwnTopAndStillHonorsShelter() throws Exception
    {
        SnowFixture f = new SnowFixture();
        try
        {
            RuneLiteObject lower = f.flakes.get(0), upper = f.flakes.get(f.flakes.size() - 1);
            f.altitude.setFloat(lower, 0);
            f.altitude.setFloat(upper, 0);
            f.tickAll(1);
            assertTrue("Lower flakes recycle above the canopy", f.altitude.getFloat(lower) > 1100);
            assertTrue("The lower layer stays dense within its original height", f.altitude.getFloat(lower) <= 1500);
            assertTrue("Upper flakes recycle in the raised part of the column", f.altitude.getFloat(upper) > 2600);
            assertTrue(lower.isActive());
            assertTrue(upper.isActive());

            for (byte[] row : f.flags[2]) { java.util.Arrays.fill(row, (byte) 4); }
            f.tickAll(1);
            assertEquals("Raising the ceiling never makes snow spawn beneath roofs", 0, WinterSnowfall.getCount(f.owner));
            for (byte[] row : f.flags[2]) { java.util.Arrays.fill(row, (byte) 0); }
            WinterSnowfall.update(f.owner, f.client, f.scene);
            assertEquals("Returning outdoors repopulates both layers", 384, WinterSnowfall.getCount(f.owner));
        }
        finally { WinterSnowfall.clear(f.owner); }
    }

    @Test
    public void retroGpuKeepsSnowfallAndAnUnsupportedWrappedRendererClearsIt() throws Exception
    {
        SnowFixture f = new SnowFixture();
        try
        {
            Set<Object> registered = Collections.newSetFromMap(new IdentityHashMap<>());
            registered.addAll(f.registered);
            f.callbacks = new RetroDrawCallbacks(new GpuPlugin());
            WinterSnowfall.update(f.owner, f.client, f.scene);
            assertEquals("The NPC wrapper retains the full snowfall volume", 384,
                WinterSnowfall.getCount(f.owner));
            assertEquals("Compatible callbacks reuse the existing flakes", registered, f.registered);

            f.callbacks = new RetroDrawCallbacks(proxy(DrawCallbacks.class, (name, args) -> null));
            WinterSnowfall.update(f.owner, f.client, f.scene);
            assertEquals("A wrapper must not hide an unsupported renderer", 0,
                WinterSnowfall.getCount(f.owner));
            assertTrue("Unsupported rendering unregisters every particle", f.registered.isEmpty());
            for (RuneLiteObject flake : f.flakes) { assertFalse(flake.isActive()); }
        }
        finally { WinterSnowfall.clear(f.owner); }
    }

    @Test
    public void expandedSurfaceAdmitsSnowfallWhileUndergroundAndInstancesDoNotLoadParticles()
    {
        int[] base = {1640, 3680}, loads = {0};
        boolean[] instance = {false};
        byte[][][] flags = new byte[4][4][4];
        Scene scene = proxy(Scene.class, (name, args) -> {
            if (name.equals("getBaseX")) { return base[0]; }
            if (name.equals("getBaseY")) { return base[1]; }
            if (name.equals("isInstance")) { return instance[0]; }
            return null;
        });
        WorldView world = proxy(WorldView.class, (name, args) -> {
            switch (name)
            {
                case "isTopLevel": return true;
                case "getId": return WorldView.TOPLEVEL;
                case "getScene": return scene;
                case "getTileSettings": return flags;
                default: return null;
            }
        });
        Player player = proxy(Player.class, (name, args) -> name.equals("getLocalLocation")
            ? new LocalPoint(192, 192, WorldView.TOPLEVEL) : null);
        Client client = proxy(Client.class, (name, args) -> {
            switch (name)
            {
                case "getGameState": return GameState.LOGGED_IN;
                case "getTopLevelWorldView": return world;
                case "getLocalPlayer": return player;
                case "loadModelData": loads[0]++; return null;
                default: return null;
            }
        });
        Object owner = new Object();
        WinterSnowfall.update(owner, client, scene);
        assertEquals("Kourend surface requests the normal snow models", 1, loads[0]);
        base[0] = 2600; base[1] = 3300;
        WinterSnowfall.update(owner, client, scene);
        assertEquals("Western mainland also requests snowfall", 2, loads[0]);
        base[1] += 6400;
        WinterSnowfall.update(owner, client, scene);
        assertEquals("Underground must not even load particle models", 2, loads[0]);
        base[1] = 6000;
        WinterSnowfall.update(owner, client, scene);
        assertEquals("Separate maps stay outside supported coverage", 2, loads[0]);
        base[1] = 3300; instance[0] = true;
        WinterSnowfall.update(owner, client, scene);
        assertEquals("Instance coordinates never bypass the scene guard", 2, loads[0]);
        assertEquals(0, WinterSnowfall.getCount(owner));
    }

    @Test
    public void roofsBridgesAndUnavailableTilesExcludeSnow()
    {
        byte[][][] flags = new byte[4][4][4];
        assertTrue(WinterSnowfall.clearSky(flags, 1, 2));
        flags[2][1][2] = 4;
        assertFalse("An upper roof blocks the sky", WinterSnowfall.clearSky(flags, 1, 2));
        assertTrue("The adjoining outdoor tile stays eligible", WinterSnowfall.clearSky(flags, 2, 2));
        flags[2][1][2] = 0;
        flags[1][1][2] = 2;
        assertFalse("A bridge also covers the ground below it", WinterSnowfall.clearSky(flags, 1, 2));
        assertFalse(WinterSnowfall.clearSky(null, 1, 2));
        assertFalse(WinterSnowfall.clearSky(flags, -1, 2));
        assertFalse(WinterSnowfall.clearSky(flags, 4, 2));
        flags[3][1] = null;
        assertFalse(WinterSnowfall.clearSky(flags, 1, 2));
    }

    @Test
    public void onlyOneSmallCenteredFlakeSurvivesWithoutChangingCachedGeometry()
    {
        float[] sx = {100, 120, 110, 1000, 1020, 1010};
        float[] sy = {40, 40, 60, 140, 140, 160};
        float[] sz = {10, 10, 20, 110, 110, 120};
        float[] x = sx.clone(), y = sy.clone(), z = sz.clone();
        int[] a = {0, 3}, b = {1, 4}, c = {2, 5};
        ModelData source = mesh(sx, sy, sz, a, b, c);
        ModelData target = mesh(x, y, z, a, b, c);

        WinterSnowfall.reshape(source, target, 4);

        assertArrayEquals(new float[]{-4, 4, 0, 0, 0, 0}, x, 0.001f);
        assertArrayEquals(new float[]{-4, -4, 4, 0, 0, 0}, y, 0.001f);
        assertArrayEquals(new float[]{-2, -2, 2, 0, 0, 0}, z, 0.001f);
        assertArrayEquals(new float[]{100, 120, 110, 1000, 1020, 1010}, sx, 0);
        assertArrayEquals(new float[]{40, 40, 60, 140, 140, 160}, sy, 0);
        assertArrayEquals(new float[]{10, 10, 20, 110, 110, 120}, sz, 0);
        assertArrayEquals(new int[]{0, 3}, a);
        assertArrayEquals(new int[]{1, 4}, b);
        assertArrayEquals(new int[]{2, 5}, c);
        assertEquals(0, WinterSnowfall.getCount(new Object()));
    }

    private static ModelData mesh(float[] x, float[] y, float[] z, int[] a, int[] b, int[] c)
    {
        return (ModelData) Proxy.newProxyInstance(ModelData.class.getClassLoader(),
            new Class<?>[]{ModelData.class}, (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "getVerticesCount": return x.length;
                    case "getFaceCount": return a.length;
                    case "getVerticesX": return x;
                    case "getVerticesY": return y;
                    case "getVerticesZ": return z;
                    case "getFaceIndices1": return a;
                    case "getFaceIndices2": return b;
                    case "getFaceIndices3": return c;
                    default: throw new AssertionError(method.getName());
                }
            });
    }

    private static final class SnowFixture
    {
        final Object owner = new Object();
        final LocalPoint point = new LocalPoint(16 * 128 + 64, 16 * 128 + 64, WorldView.TOPLEVEL);
        final byte[][][] flags = new byte[4][32][32];
        final Set<Object> registered = Collections.newSetFromMap(new IdentityHashMap<>());
        final Client client;
        final Scene scene;
        final List<RuneLiteObject> flakes;
        final Field altitude, upperLayer;
        int cycle = 1, cameraZ = -1800, cameraReads;
        DrawCallbacks callbacks;

        @SuppressWarnings("unchecked")
        SnowFixture() throws Exception
        {
            Tile[][][] tiles = new Tile[1][32][32];
            Tile outdoor = proxy(Tile.class, (name, args) -> null);
            for (Tile[] row : tiles[0]) { java.util.Arrays.fill(row, outdoor); }
            int[][][] heights = new int[1][33][33];
            for (int[] row : heights[0]) { java.util.Arrays.fill(row, -160); }
            scene = proxy(Scene.class, (name, args) -> {
                if (name.equals("getBaseX") || name.equals("getBaseY")) { return 3200; }
                if (name.equals("getTiles")) { return tiles; }
                return null;
            });
            WorldView world = proxy(WorldView.class, (name, args) -> {
                switch (name)
                {
                    case "getId": return WorldView.TOPLEVEL;
                    case "isTopLevel": return true;
                    case "getScene": return scene;
                    case "getTileSettings": return flags;
                    case "getTileHeights": return heights;
                    default: return null;
                }
            });
            Player player = proxy(Player.class, (name, args) -> name.equals("getLocalLocation") ? point : null);
            client = proxy(Client.class, (name, args) -> {
                switch (name)
                {
                    case "getTopLevelWorldView": return world;
                    case "getLocalPlayer": return player;
                    case "getGameState": return GameState.LOGGED_IN;
                    case "getGameCycle": return cycle;
                    case "getDrawCallbacks": return callbacks;
                    case "getCameraZ": cameraReads++; return cameraZ;
                    case "registerRuneLiteObject": registered.add(args[0]); return null;
                    case "removeRuneLiteObject": registered.remove(args[0]); return null;
                    case "isRuneLiteObjectRegistered": return registered.contains(args[0]);
                    default: return null;
                }
            });
            Class<?> stateType = Class.forName("com.seasonalscape.WinterSnowfall$State");
            Constructor<?> constructor = stateType.getDeclaredConstructor(Client.class, WorldView.class, Scene.class, Model[].class);
            constructor.setAccessible(true);
            Object state = constructor.newInstance(client, world, scene, new Model[6]);
            Field flakesField = stateType.getDeclaredField("flakes");
            flakesField.setAccessible(true);
            flakes = (List<RuneLiteObject>) flakesField.get(state);
            altitude = flakes.get(0).getClass().getDeclaredField("altitude");
            altitude.setAccessible(true);
            upperLayer = flakes.get(0).getClass().getDeclaredField("upperLayer");
            upperLayer.setAccessible(true);
            Field states = WinterSnowfall.class.getDeclaredField("STATES");
            states.setAccessible(true);
            ((Map<Object, Object>) states.get(null)).put(owner, state);
            WinterSnowfall.update(owner, client, scene);
        }

        void tickAll(int elapsed)
        {
            cycle++;
            for (RuneLiteObject flake : flakes) { flake.tick(elapsed); }
        }
    }

    private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> handler)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, args) -> {
            Object result = handler.apply(method.getName(), args);
            if (result != null || !method.getReturnType().isPrimitive() || method.getReturnType() == void.class) { return result; }
            if (method.getReturnType() == boolean.class) { return false; }
            if (method.getReturnType() == int.class) { return 0; }
            throw new AssertionError("Unexpected primitive method: " + method);
        }));
    }
}
