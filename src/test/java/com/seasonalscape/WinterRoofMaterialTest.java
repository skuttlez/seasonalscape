package com.seasonalscape;

import java.lang.reflect.Proxy;
import net.runelite.api.Model;
import org.junit.Test;

import static org.junit.Assert.*;

public class WinterRoofMaterialTest
{
    @Test
    public void steepAndDegenerateRoofFacesDoNotReceiveSnow()
    {
        Object owner = new Object();
        Roof roof = new Roof();
        roof.faceCount = 1;
        roof.y[roof.b[0]] = -170;
        roof.y[roof.c[0]] = 0;
        int[][] original = roof.copyColors();
        try
        {
            assertEquals("Upward slope just within the snow limit", 3, WinterRoofMaterial.apply(owner, roof.model()));
            assertTrue(WinterRoofMaterial.restore(owner));

            roof.y[roof.b[0]] = -172;
            assertEquals("Steeper slope stays bare", 0, WinterRoofMaterial.apply(owner, roof.model()));
            for (int channel = 0; channel < 3; channel++) { assertArrayEquals(original[channel], roof.colors[channel]); }
            assertFalse(WinterRoofMaterial.restore(owner));

            roof.b[0] = roof.c[0] = roof.a[0];
            assertEquals("Zero-area face stays bare", 0, WinterRoofMaterial.apply(owner, roof.model()));
            assertFalse(WinterRoofMaterial.restore(owner));
        }
        finally { WinterRoofMaterial.restore(owner); }
    }

    @Test
    public void onlyActiveFacesAndVerticesReceiveSnow()
    {
        for (boolean limitedVertices : new boolean[]{true, false})
        {
            Object owner = new Object();
            Roof roof = new Roof();
            roof.vertexCount = limitedVertices ? 3 : roof.x.length;
            roof.faceCount = limitedVertices ? roof.a.length : 1;
            int[][] original = roof.copyColors();
            try
            {
                assertEquals(3, WinterRoofMaterial.apply(owner, roof.model()));
                assertArrayEquals(new short[]{-1, 45, 45, 45, 45, -1}, roof.textures);
                for (int channel = 0; channel < 3; channel++)
                {
                    assertSnow(roof.colors[channel][0]);
                    for (int face = 1; face < roof.a.length; face++)
                    {
                        assertEquals("Unused model capacity stays unchanged", original[channel][face], roof.colors[channel][face]);
                    }
                }
                WinterRoofMaterial.restore(owner);
                for (int channel = 0; channel < 3; channel++) { assertArrayEquals(original[channel], roof.colors[channel]); }
            }
            finally { WinterRoofMaterial.restore(owner); }
        }
    }

    @Test
    public void snowCoversRoofSlopesButPreservesSidesUndersidesAndFaceSentinels()
    {
        Object owner = new Object();
        Roof roof = new Roof();
        int[][] original = roof.copyColors();
        short[] originalTextures = roof.textures.clone();
        float[] originalY = roof.y.clone();
        try
        {
            assertEquals(3, WinterRoofMaterial.apply(owner, roof.model()));
            assertArrayEquals(new short[]{-1, 45, 45, -1, 45, -1}, roof.textures);
            for (int channel = 0; channel < 3; channel++)
            {
                assertSnow(roof.colors[channel][0]);
                assertSnow(roof.colors[channel][5]);
                assertEquals("Vertical roof side is unchanged", original[channel][1], roof.colors[channel][1]);
                assertEquals("Roof underside is unchanged", original[channel][2], roof.colors[channel][2]);
                assertEquals("Hidden face is unchanged", original[channel][4], roof.colors[channel][4]);
            }
            assertTrue("Snow retains relative roof lighting", roof.colors[0][0] < roof.colors[1][0]);
            assertTrue(roof.colors[1][0] <= roof.colors[2][0]);
            assertSnow(roof.colors[0][3]);
            assertEquals("Unused flat channel is unchanged", 0, roof.colors[1][3]);
            assertEquals("Flat face sentinel survives", -1, roof.colors[2][3]);
            assertEquals("Hidden face sentinel survives", -2, roof.colors[2][4]);
            assertArrayEquals("Native geometry is preserved", originalY, roof.y, 0);

            int[][] snowy = roof.copyColors();
            assertEquals("Repeated refresh needs no GPU upload", 1, WinterRoofMaterial.apply(owner, roof.model()));
            for (int channel = 0; channel < 3; channel++) { assertArrayEquals(snowy[channel], roof.colors[channel]); }
            assertTrue(WinterRoofMaterial.restore(owner));
            assertArrayEquals(originalTextures, roof.textures);
            for (int channel = 0; channel < 3; channel++) { assertArrayEquals(original[channel], roof.colors[channel]); }
            assertFalse("Restoration is idempotent", WinterRoofMaterial.restore(owner));
        }
        finally { WinterRoofMaterial.restore(owner); }
    }

    @Test
    public void sharedColorAndTextureArraysDoNotAccumulateSnowAcrossRoofCopies()
    {
        Object owner = new Object();
        Roof roof = new Roof();
        int[] shared = {96, 96, 96, 100, -2, 5 << 10 | 4 << 7 | 40};
        roof.colors = new int[][]{shared, shared, shared};
        int[] original = shared.clone();
        short[] originalTextures = roof.textures.clone();
        try
        {
            assertEquals(3, WinterRoofMaterial.apply(owner, roof.model()));
            int[] first = shared.clone();
            assertEquals(1, WinterRoofMaterial.apply(owner, roof.model()));
            assertArrayEquals(first, shared);
            assertTrue(WinterRoofMaterial.restore(owner));
            assertArrayEquals(original, shared);
            assertArrayEquals(originalTextures, roof.textures);
        }
        finally { WinterRoofMaterial.restore(owner); }
    }

    @Test
    public void restoresOwnedValuesWithoutOverwritingAnotherModifiersChanges()
    {
        Object owner = new Object();
        Roof roof = new Roof();
        int[][] original = roof.copyColors();
        try
        {
            WinterRoofMaterial.apply(owner, roof.model());
            roof.colors[0][0] = 12345;
            roof.textures[3] = 7;
            WinterRoofMaterial.apply(owner, roof.model());
            assertEquals(12345, roof.colors[0][0]);
            assertEquals(7, roof.textures[3]);
            assertTrue(WinterRoofMaterial.restore(owner));
            assertEquals("External color survives restoration", 12345, roof.colors[0][0]);
            assertEquals("External texture survives restoration", 7, roof.textures[3]);
            assertEquals(original[1][0], roof.colors[1][0]);
            assertEquals(original[2][0], roof.colors[2][0]);
            assertEquals(45, roof.textures[0]);
            assertEquals(original[0][3], roof.colors[0][3]);
        }
        finally { WinterRoofMaterial.restore(owner); }
    }

    private static void assertSnow(int color)
    {
        assertEquals("Snow is neutral", 0, color >>> 7 & 7);
        assertTrue("Snow is softly shaded", (color & 127) >= 86 && (color & 127) <= 108);
    }

    private static final class Roof
    {
        private final float[] x = new float[18], y = new float[18], z = new float[18];
        private final int[] a = new int[6], b = new int[6], c = new int[6];
        private int[][] colors = {
            {96, 96, 96, 100, 80, 5 << 10 | 4 << 7 | 40},
            {116, 116, 116, 0, 80, 5 << 10 | 4 << 7 | 50},
            {120, 120, 120, -1, -2, 5 << 10 | 4 << 7 | 60}
        };
        private final short[] textures = {45, 45, 45, 45, 45, -1};
        private int vertexCount = x.length, faceCount = a.length;

        private Roof()
        {
            for (int face = 0; face < a.length; face++)
            {
                a[face] = face * 3;
                b[face] = face * 3 + 1;
                c[face] = face * 3 + 2;
                x[b[face]] = 64;
                z[c[face]] = 64;
            }
            // Face 0 is a sloped roof; face 1 a vertical side; face 2 faces down.
            y[b[0]] = -30;
            y[c[0]] = -30;
            z[c[1]] = 0;
            y[c[1]] = -64;
            x[b[2]] = 0;
            z[b[2]] = 64;
            x[c[2]] = 64;
            z[c[2]] = 0;
        }

        private int[][] copyColors()
        {
            return new int[][]{colors[0].clone(), colors[1].clone(), colors[2].clone()};
        }

        private Model model()
        {
            return (Model) Proxy.newProxyInstance(Model.class.getClassLoader(), new Class<?>[]{Model.class},
                (proxy, method, args) -> {
                    switch (method.getName())
                    {
                        case "getVerticesX": return x;
                        case "getVerticesY": return y;
                        case "getVerticesZ": return z;
                        case "getVerticesCount": return vertexCount;
                        case "getFaceCount": return faceCount;
                        case "getFaceIndices1": return a;
                        case "getFaceIndices2": return b;
                        case "getFaceIndices3": return c;
                        case "getFaceColors1": return colors[0];
                        case "getFaceColors2": return colors[1];
                        case "getFaceColors3": return colors[2];
                        case "getFaceTextures": return textures;
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
        }
    }
}
