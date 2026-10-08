package com.seasonalscape;

import java.lang.reflect.Proxy;
import net.runelite.api.Model;
import org.junit.Test;
import static org.junit.Assert.*;

public class WinterCanopySnowTest
{
    @Test
    public void coversSolidCanopyAndFlatUpperWillowLeavesButPreservesSkirtsAndTrunks()
    {
        Fixture f = new Fixture();
        assertEquals(3, WinterCanopySnow.apply(f, f.model()));
        assertArrayEquals(new short[]{-1, 8, -1, 30, -1, 7}, f.textures);
        assertEquals("A flat cap receives visible pale snow", 0, f.a[0] >>> 7 & 7);
        assertTrue((f.a[0] & 127) >= 88);
        assertEquals("Flat faces use the original first-channel lighting", f.a[0], f.b[0]);
        assertEquals("Flat caps become Gouraud shaded", f.a[0], f.c[0]);
        assertEquals("Hidden faces remain hidden and retain their lighting", 90, f.a[2]);
        assertEquals(60, f.a[1]);
        assertEquals(50, f.a[3]);
        assertEquals(5642, f.a[4]);
        assertEquals(-2, f.c[2]);
        WinterCanopySnow.restore(f);
        assertArrayEquals(new short[]{60, 8, 30, 30, -1, 7}, f.textures);
        assertArrayEquals(new int[]{70, 60, 90, 50, 5642, 77}, f.a);
        assertEquals("Restoration recovers the flat-shading sentinel", -1, f.c[0]);
    }

    @Test
    public void repeatedScansAndSharedArraysRetainOriginalBrightness()
    {
        Fixture f = new Fixture();
        Model first = f.model(), second = f.model();
        assertEquals(3, WinterCanopySnow.apply(f, first));
        int[] after = f.a.clone();
        assertEquals(1, WinterCanopySnow.apply(f, second));
        assertEquals(1, WinterCanopySnow.apply(f, first));
        assertArrayEquals(after, f.a);
        WinterCanopySnow.restore(f);
        assertEquals(70, f.a[0]);
        assertEquals(90, f.a[2]);
        assertEquals(60, f.textures[0]);
    }

    @Test
    public void preservesExternalTextureAndColorWritesOnRepeatAndRestore()
    {
        Fixture f = new Fixture();
        WinterCanopySnow.apply(f, f.model());
        f.textures[0] = 13;
        f.a[2] = 999;
        WinterCanopySnow.apply(f, f.model());
        assertEquals(13, f.textures[0]);
        assertEquals(999, f.a[2]);
        WinterCanopySnow.restore(f);
        assertEquals(13, f.textures[0]);
        assertEquals(999, f.a[2]);
        assertEquals(30, f.textures[2]);
        assertEquals(80, f.b[0]);
    }

    @Test
    public void leavesPreviouslyColoredTextureFacesUntouched()
    {
        Fixture f = new Fixture();
        f.a[0] = 12345;
        WinterCanopySnow.apply(f, f.model());
        assertEquals(60, f.textures[0]);
        assertEquals(12345, f.a[0]);
        assertEquals(80, f.b[0]);
        WinterCanopySnow.restore(f);
        assertEquals(12345, f.a[0]);
    }

    @Test
    public void blendsTheSnowApexIntoTheGreenRimAndRestoresFlatShading()
    {
        Fixture f = new Fixture();
        f.y[0] = -130;
        WinterCanopySnow.apply(f, f.model());
        assertEquals("Apex is neutral snow", 0, f.a[0] >>> 7 & 7);
        assertTrue("Apex stays light", (f.a[0] & 127) >= 88);
        assertEquals("Rim has a muted green hue", 16, f.b[0] >>> 10);
        assertEquals(1, f.b[0] >>> 7 & 7);
        assertFalse("The foliage pass must not whiten the blended edge again", SeasonalPalette.isVegetation(f.b[0]));
        assertTrue("Rim merges into leaves", (f.b[0] & 127) >= 30 && (f.b[0] & 127) <= 40);
        assertEquals(f.b[0], f.c[0]);
        int[] firstPass = f.a.clone();
        assertEquals(1, WinterCanopySnow.apply(f, f.model()));
        assertArrayEquals(firstPass, f.a);
        WinterCanopySnow.restore(f);
        assertEquals(70, f.a[0]);
        assertEquals(80, f.b[0]);
        assertEquals(-1, f.c[0]);
    }

    private static final class Fixture
    {
        private final short[] textures = {60, 8, 30, 30, -1, 7};
        private final int[] a = {70, 60, 90, 50, 5642, 77};
        private final int[] b = {80, 60, 95, 50, 5650, 77};
        private final int[] c = {-1, 60, -2, 50, 5655, 77};
        private final float[] x = {0, 10, 0, 0, 10, 0};
        private final float[] y = {-100, -100, -100, -20, -20, -20};
        private final float[] z = {0, 0, 10, 0, 0, 10};
        private final int[] ai = {0, 0, 0, 3, 3, 3};
        private final int[] bi = {1, 1, 1, 4, 4, 4};
        private final int[] ci = {2, 2, 2, 5, 5, 5};

        private Model model()
        {
            return (Model) Proxy.newProxyInstance(Model.class.getClassLoader(), new Class<?>[]{Model.class},
                (proxy, method, args) -> {
                    switch (method.getName())
                    {
                        case "getFaceTextures": return textures;
                        case "getFaceColors1": return a;
                        case "getFaceColors2": return b;
                        case "getFaceColors3": return c;
                        case "getVerticesX": return x;
                        case "getVerticesY": return y;
                        case "getVerticesZ": return z;
                        case "getFaceIndices1": return ai;
                        case "getFaceIndices2": return bi;
                        case "getFaceIndices3": return ci;
                        case "getFaceCount": return textures.length;
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        default: return null;
                    }
                });
        }
    }
}
