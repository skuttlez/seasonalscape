package com.seasonalscape;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SeasonalAirTest
{
    @Test
    public void particleBudgetsAreSparseAndRespectDisableAndDensity()
    {
        assertEquals(21, SeasonalAir.particleCount(Season.SPRING, true, 30));
        assertEquals(7, SeasonalAir.particleCount(Season.SUMMER, true, 30));
        assertTrue(SeasonalAir.particleCount(Season.SPRING, true, Integer.MAX_VALUE) <= 40);
        assertTrue(SeasonalAir.particleCount(Season.SUMMER, true, Integer.MAX_VALUE) <= 12);
        for (Season season : Season.values())
        {
            assertEquals(0, SeasonalAir.particleCount(season, false, 100));
            assertEquals(0, SeasonalAir.particleCount(season, true, 0));
            assertEquals(0, SeasonalAir.particleCount(season, true, -1));
        }
        assertEquals(0, SeasonalAir.particleCount(Season.AUTUMN, true, 100));
        assertEquals(0, SeasonalAir.particleCount(Season.WINTER, true, 100));
    }

    @Test
    public void petalsAndEveryWingFrameStaySmallWithoutMutatingTheCache()
    {
        float[] sx = {100, 80, 80, 120, 120, 500, 520, 510};
        float[] sy = {20, 21, 21, 21, 21, 60, 60, 70};
        float[] sz = {10, 0, 20, 0, 20, 60, 60, 80};
        int[] a = {0, 0, 5}, b = {2, 3, 6}, c = {1, 4, 7};
        float[] originalX = sx.clone(), originalY = sy.clone(), originalZ = sz.clone();
        ModelData source = mesh(sx, sy, sz, a, b, c);
        float openWidth = 0, foldedWidth = 0, foldedHeight = 0;
        for (Season season : new Season[]{Season.SPRING, Season.SUMMER})
        {
            for (int frame = 0; frame < 8; frame++)
            {
                float[] x = sx.clone(), y = sy.clone(), z = sz.clone();
                SeasonalAir.reshape(source, mesh(x, y, z, a, b, c), season, frame);
                float width = 0, height = 0;
                for (int vertex = 0; vertex < x.length; vertex++)
                {
                    assertTrue(Float.isFinite(x[vertex]) && Float.isFinite(y[vertex]) && Float.isFinite(z[vertex]));
                    assertTrue("Airborne decoration stays smaller than a tile", Math.hypot(x[vertex], z[vertex]) < 12);
                    assertTrue("Wing folds stay near the body", Math.abs(y[vertex]) <= 9);
                    if (vertex >= 5)
                    {
                        assertEquals("Other cache components remain collapsed", 0, x[vertex], 0);
                        assertEquals(0, y[vertex], 0);
                        assertEquals(0, z[vertex], 0);
                    }
                    width = Math.max(width, Math.abs(x[vertex]));
                    height = Math.max(height, Math.abs(y[vertex]));
                }
                assertTrue("The particle has a visible, nondegenerate silhouette", width > 0.5f);
                if (season == Season.SUMMER && frame == 0) { openWidth = width; }
                if (season == Season.SUMMER && frame == 4) { foldedWidth = width; foldedHeight = height; }
            }
        }
        assertTrue("Butterfly wings visibly close during the animation", foldedWidth < openWidth * 0.5f);
        assertTrue("Closing lifts the wings above the body", foldedHeight > 5);
        assertArrayEquals(originalX, sx, 0);
        assertArrayEquals(originalY, sy, 0);
        assertArrayEquals(originalZ, sz, 0);
        assertArrayEquals(new int[]{0, 0, 5}, a);
        assertArrayEquals(new int[]{2, 3, 6}, b);
        assertArrayEquals(new int[]{1, 4, 7}, c);
    }

    @Test
    public void disabledOrInactiveSeasonsRemoveParticlesWithoutLoadingTheScene() throws Exception
    {
        for (int scenario = 0; scenario < 4; scenario++)
        {
            Fixture fixture = new Fixture();
            try
            {
                fixture.forbidClientReads = true;
                Season season = scenario == 2 ? Season.AUTUMN : scenario == 3 ? Season.WINTER : Season.SPRING;
                SeasonalAir.update(fixture.owner, fixture.client, null, null, season,
                    scenario != 0, scenario == 1 ? 0 : 30);
                assertFalse("Existing particle is unregistered immediately", fixture.particle.isActive());
                assertEquals(0, SeasonalAir.getCount(fixture.owner));
            }
            finally { SeasonalAir.clear(fixture.owner); }
        }
    }

    @Test
    public void framesWithoutElapsedClientTicksPreserveButterflyHeading() throws Exception
    {
        Fixture fixture = new Fixture();
        try
        {
            fixture.particle.setOrientation(1370);
            fixture.particle.setX(190);
            fixture.particle.setY(185);
            fixture.particle.tick(0);
            assertEquals("Unlocked FPS must not reset heading between client ticks", 1370, fixture.particle.getOrientation());
            assertEquals(190, fixture.particle.getX());
            assertEquals(185, fixture.particle.getY());
            assertTrue(fixture.particle.isActive());
        }
        finally { SeasonalAir.clear(fixture.owner); }
    }

    private static ModelData mesh(float[] x, float[] y, float[] z, int[] a, int[] b, int[] c)
    {
        return (ModelData) Proxy.newProxyInstance(ModelData.class.getClassLoader(), new Class<?>[]{ModelData.class},
            (proxy, method, args) -> {
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

    private static final class Fixture
    {
        final Object owner = new Object();
        final Set<Object> registered = Collections.newSetFromMap(new IdentityHashMap<>());
        final Client client;
        final RuneLiteObject particle;
        boolean forbidClientReads;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception
        {
            Scene scene = (Scene) Proxy.newProxyInstance(Scene.class.getClassLoader(), new Class<?>[]{Scene.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getBaseX") || method.getName().equals("getBaseY")) { return 3200; }
                    throw new AssertionError(method.getName());
                });
            WorldView world = (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(), new Class<?>[]{WorldView.class},
                (proxy, method, args) -> {
                    switch (method.getName())
                    {
                        case "getId": return -1;
                        case "getPlane": return 0;
                        case "getScene": return scene;
                        default: throw new AssertionError(method.getName());
                    }
                });
            Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getLocalLocation")) { return new LocalPoint(192, 192, -1); }
                    throw new AssertionError(method.getName());
                });
            client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
                (proxy, method, args) -> {
                    switch (method.getName())
                    {
                        case "registerRuneLiteObject": registered.add(args[0]); return null;
                        case "removeRuneLiteObject": registered.remove(args[0]); return null;
                        case "isRuneLiteObjectRegistered": return registered.contains(args[0]);
                    }
                    if (forbidClientReads) { throw new AssertionError("Disabled particles must not load the scene: " + method.getName()); }
                    if (method.getName().equals("getGameState")) { return GameState.LOGGED_IN; }
                    if (method.getName().equals("getLocalPlayer")) { return player; }
                    throw new AssertionError(method.getName());
                });
            Class<?> stateType = Class.forName("com.seasonalscape.SeasonalAir$State");
            Constructor<?> constructor = stateType.getDeclaredConstructor(Client.class, WorldView.class, Scene.class,
                SeasonalSceneRecolorer.class, Season.class, Model[][].class, int.class);
            constructor.setAccessible(true);
            Object state = constructor.newInstance(client, world, scene, null, Season.SUMMER, new Model[][]{{null}}, 1);
            Field flags = stateType.getDeclaredField("flags");
            flags.setAccessible(true);
            flags.set(state, new byte[4][4][4]);
            Field particles = stateType.getDeclaredField("particles");
            particles.setAccessible(true);
            particle = (RuneLiteObject) ((List<?>) particles.get(state)).get(0);
            particle.setActive(true);
            Field states = SeasonalAir.class.getDeclaredField("STATES");
            states.setAccessible(true);
            ((Map<Object, Object>) states.get(null)).put(owner, state);
        }
    }
}
