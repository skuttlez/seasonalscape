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
        return (Model) Proxy.newProxyInstance(Model.class.getClassLoader(), new Class<?>[]{Model.class},
            (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "getVerticesX": return x;
                    case "getVerticesY": return y;
                    case "getVerticesZ": return z;
                    case "getFaceIndices1": return a;
                    case "getFaceIndices2": return b;
                    case "getFaceIndices3": return c;
                    case "getFaceTextures": return textures;
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
