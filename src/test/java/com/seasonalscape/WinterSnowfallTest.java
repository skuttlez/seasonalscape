package com.seasonalscape;

import java.lang.reflect.Proxy;
import java.util.function.BiFunction;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ModelData;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WinterSnowfallTest
{
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

    private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> handler)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, args) -> {
            Object result = handler.apply(method.getName(), args);
            if (result != null || !method.getReturnType().isPrimitive()) { return result; }
            if (method.getReturnType() == boolean.class) { return false; }
            if (method.getReturnType() == int.class) { return 0; }
            throw new AssertionError("Unexpected primitive method: " + method);
        }));
    }
}
