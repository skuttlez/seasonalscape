package com.seasonalscape;

import java.lang.reflect.Proxy;
import java.util.List;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WinterTreeFrostTest
{
    @Test
    public void spareFaceAndVertexCapacityCannotCreateDistantSnowSurfaces()
    {
        float[] x = {0, 100, 0, 1000, 1100, 1000};
        float[] y = {-100, -100, -100, -100, -100, -100};
        float[] z = {0, 0, 100, 1000, 1000, 1100};
        int[] a = {0, 3}, b = {1, 4}, c = {2, 5};
        short[] textures = {8, 8};
        for (int[] counts : new int[][]{{1, 6}, {2, 3}})
        {
            Model model = tree(x, y, z, a, b, c, textures, counts[0], counts[1], new int[]{80, 80}, null);
            List<WinterTreeFrost.Surface> surfaces = WinterTreeFrost.surfaces(model);
            assertEquals("Only active geometry receives snow", 1, surfaces.size());
            assertEquals("No deposit can land on unused distant vertices", 0, surfaces.get(0).a[0], 0);
        }
    }

    @Test
    public void hiddenAndTransparentLeafFacesDoNotReceiveSnow()
    {
        int[] third = {80, -2, 80, 80, -1};
        byte[] alpha = {0, 0, (byte) 255, (byte) 129, (byte) 128};
        Model model = tree(new float[]{0, 100, 0}, new float[]{-100, -100, -100},
            new float[]{0, 0, 100}, new int[]{0, 0, 0, 0, 0}, new int[]{1, 1, 1, 1, 1},
            new int[]{2, 2, 2, 2, 2}, new short[]{8, 8, 8, 8, 8}, 5, 3, third, alpha);
        assertEquals("Keep visible and flat faces; skip hidden and high-transparency faces", 2,
            WinterTreeFrost.surfaces(model).size());
        assertArrayEquals(new int[]{80, -2, 80, 80, -1}, third);
        assertArrayEquals(new byte[]{0, 0, (byte) 255, (byte) 129, (byte) 128}, alpha);
    }

    @Test
    public void malformedFaceAndShortOptionalChannelsDoNotDiscardValidCanopy()
    {
        float[] x = {0, 100, 0}, y = {-100, -100, -100}, z = {0, 0, 100};
        int[] a = {0, 999}, b = {1, 1}, c = {2, 2};
        short[] textures = {8, 30};
        assertEquals("An invalid leaf index must not discard other valid faces", 1,
            WinterTreeFrost.surfaces(tree(x, y, z, a, b, c, textures, 2, 3, null, null)).size());
        a[1] = 0;
        assertEquals("Short alpha arrays cap readable faces", 1,
            WinterTreeFrost.surfaces(tree(x, y, z, a, b, c, textures, 2, 3,
                new int[]{80, 80}, new byte[]{0})).size());
        assertEquals("Short lighting arrays cap readable faces", 1,
            WinterTreeFrost.surfaces(tree(x, y, z, a, b, c, textures, 2, 3,
                new int[]{80}, null)).size());
    }

    @Test
    public void foliageSurfacesIncludeSolidCapsButExcludeBarkUndersidesAndSlivers()
    {
        float[] x = {0, 100, 0, 0, 100, 0, 0, 1, 0};
        float[] y = {-100, -100, -100, -100, -100, -100, 0, 0, 0};
        float[] z = {0, 0, 100, 0, 0, 100, 0, 0, 1};
        int[] a = {0, 0, 0, 3, 6};
        int[] b = {1, 1, 1, 5, 7};
        int[] c = {2, 2, 2, 4, 8};
        short[] textures = {8, 60, 2, 30, 8};
        List<WinterTreeFrost.Surface> surfaces = WinterTreeFrost.surfaces(tree(x, y, z, a, b, c, textures));
        assertEquals(2, surfaces.size());
        assertEquals(-1, surfaces.get(0).normal[1], 0.0001);
        assertArrayEquals("Existing texture IDs stay unchanged", new short[]{8, 60, 2, 30, 8}, textures);
    }

    @Test
    public void depositedSnowFollowsSlopingLeafFaceWithoutMovingSourceGeometry()
    {
        Model tree = tree(new float[]{0, 120, 0}, new float[]{-100, -160, -100},
            new float[]{0, 0, 120}, new int[]{0}, new int[]{1}, new int[]{2}, new short[]{30});
        List<WinterTreeFrost.Surface> surfaces = WinterTreeFrost.surfaces(tree);
        assertEquals(1, surfaces.size());
        float[] sourceX = {-4, 4, 0}, sourceY = {0, 0, 0}, sourceZ = {-2, -2, 2};
        ModelData source = particle(sourceX, sourceY, sourceZ, new short[]{123});
        float[] x = sourceX.clone(), y = sourceY.clone(), z = sourceZ.clone();
        short[] colors = {123};
        ModelData target = particle(x, y, z, colors);
        WinterTreeFrost.fit(source, target, surfaces, 12345);
        WinterTreeFrost.Surface surface = surfaces.get(0);
        for (int vertex = 0; vertex < 3; vertex++)
        {
            double offset = (x[vertex] - surface.a[0]) * surface.normal[0]
                + (y[vertex] - surface.a[1]) * surface.normal[1]
                + (z[vertex] - surface.a[2]) * surface.normal[2];
            assertEquals("Snow sits just above the leaf polygon", 1.4, offset, 0.0001);
            double projectedX = x[vertex] - 1.4 * surface.normal[0];
            double projectedZ = z[vertex] - 1.4 * surface.normal[2];
            assertTrue(projectedX >= 0 && projectedZ >= 0 && projectedX + projectedZ <= 120);
        }
        assertArrayEquals(new float[]{-4, 4, 0}, sourceX, 0);
        assertArrayEquals(new float[]{0, 0, 0}, sourceY, 0);
        assertArrayEquals(new float[]{-2, -2, 2}, sourceZ, 0);
        assertEquals("Snow is neutral, not autumn colored", 0, (colors[0] >>> 7) & 7);
        assertTrue((colors[0] & 127) >= 106);
    }

    @Test
    public void starParticleBecomesRoundedDepositWithItsCenterAndTopologyPreserved()
    {
        List<WinterTreeFrost.Surface> surfaces = WinterTreeFrost.surfaces(tree(
            new float[]{0, 160, 0}, new float[]{-100, -100, -100}, new float[]{0, 0, 160},
            new int[]{0}, new int[]{1}, new int[]{2}, new short[]{8}));
        float[] x = {0, 16, 3, 0, -3, -16, -3, 0, 3};
        float[] z = {0, 0, 3, 16, 3, 0, -3, -16, -3};
        float[] y = new float[x.length];
        int[] a = new int[8], b = {1, 2, 3, 4, 5, 6, 7, 8}, c = {2, 3, 4, 5, 6, 7, 8, 1};
        ModelData source = particle(x.clone(), y.clone(), z.clone(), new short[8], a, b, c);
        ModelData target = particle(x, y, z, new short[8], a, b, c);
        WinterTreeFrost.fit(source, target, surfaces, 7351);
        double smallest = Double.POSITIVE_INFINITY, largest = 0;
        for (int vertex = 1; vertex < x.length; vertex++)
        {
            double radius = Math.hypot(x[vertex] - x[0], (z[vertex] - z[0]) / 0.78);
            smallest = Math.min(smallest, radius);
            largest = Math.max(largest, radius);
            assertEquals("The center and outline sit on the same foliage plane", -101.4, y[vertex], 0.0001);
            assertTrue(x[vertex] >= 0 && z[vertex] >= 0 && x[vertex] + z[vertex] <= 160);
        }
        assertEquals(-101.4, y[0], 0.0001);
        assertTrue("Deep star notches are filled into an irregular oval", largest / smallest < 1.24);
        assertTrue("The rounded edge stays within the original clearance radius", largest <= 24);
        assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6, 7, 8}, b);
        assertArrayEquals(new int[]{2, 3, 4, 5, 6, 7, 8, 1}, c);
    }

    private static Model tree(float[] x, float[] y, float[] z, int[] a, int[] b, int[] c, short[] textures)
    {
        return tree(x, y, z, a, b, c, textures, a.length, x.length, null, null);
    }

    private static Model tree(float[] x, float[] y, float[] z, int[] a, int[] b, int[] c,
        short[] textures, int faces, int vertices, int[] third, byte[] alpha)
    {
        return (Model) Proxy.newProxyInstance(Model.class.getClassLoader(), new Class<?>[]{Model.class},
            (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "getVerticesX": return x;
                    case "getVerticesY": return y;
                    case "getVerticesZ": return z;
                    case "getVerticesCount": return vertices;
                    case "getFaceCount": return faces;
                    case "getFaceIndices1": return a;
                    case "getFaceIndices2": return b;
                    case "getFaceIndices3": return c;
                    case "getFaceTextures": return textures;
                    case "getFaceColors3": return third;
                    case "getFaceTransparencies": return alpha;
                    default: throw new AssertionError(method.getName());
                }
            });
    }

    private static ModelData particle(float[] x, float[] y, float[] z, short[] colors)
    {
        return particle(x, y, z, colors, new int[]{0}, new int[]{1}, new int[]{2});
    }

    private static ModelData particle(float[] x, float[] y, float[] z, short[] colors, int[] a, int[] b, int[] c)
    {
        return (ModelData) Proxy.newProxyInstance(ModelData.class.getClassLoader(), new Class<?>[]{ModelData.class},
            (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "getVerticesX": return x;
                    case "getVerticesY": return y;
                    case "getVerticesZ": return z;
                    case "getVerticesCount": return x.length;
                    case "getFaceCount": return a.length;
                    case "getFaceIndices1": return a;
                    case "getFaceIndices2": return b;
                    case "getFaceIndices3": return c;
                    case "getFaceColors": return colors;
                    default: throw new AssertionError(method.getName());
                }
            });
    }
}
