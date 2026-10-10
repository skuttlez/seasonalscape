package com.seasonalscape;

import org.junit.Test;
import static org.junit.Assert.*;

public class SeasonalTerrainPaletteTest
{
    @Test
    public void everyClassifiedGrassColorUsesOneMaterialPerSeason()
    {
        for (Season season : Season.values())
        {
            int material = SeasonalGroundColors.ground(20 << 10 | 4 << 7 | 50, season) >>> 7;
            for (int source = 0; source <= 65535; source++)
            {
                if (!SeasonalGroundColors.isGrass(source)) { continue; }
                int color = SeasonalGroundColors.ground(source, season);
                assertEquals("Mixed grass hues and deep shadows must share material in " + season,
                    material, color >>> 7);
            }
        }
    }

    @Test
    public void interpolatingOliveAndShadowedGrassCannotWrapTheLightnessBits()
    {
        int[][] triangles = {
            {8582, 9609, 20 << 10 | 4 << 7 | 50},
            {12 << 10 | 3 << 7 | 2, 16 << 10 | 4 << 7 | 7, 20 << 10 | 4 << 7 | 118}
        };
        for (Season season : Season.values())
        {
            for (int[] source : triangles)
            {
                int a = SeasonalGroundColors.ground(source[0], season);
                int b = SeasonalGroundColors.ground(source[1], season);
                int c = SeasonalGroundColors.ground(source[2], season);
                assertStableInterpolation(a, b, c);
            }
        }
    }

    static void assertStableInterpolation(int a, int b, int c)
    {
        assertEquals(a >>> 7, b >>> 7);
        assertEquals(a >>> 7, c >>> 7);
        int low = Math.min(a & 127, Math.min(b & 127, c & 127));
        int high = Math.max(a & 127, Math.max(b & 127, c & 127));
        // Match the classic GPU shader's packed-color interpolation followed
        // by its lightness mask across a grid of points inside the triangle.
        for (int u = 0; u <= 64; u++)
        {
            for (int v = 0; v <= 64 - u; v++)
            {
                int interpolated = (a * (64 - u - v) + b * u + c * v) / 64;
                int lightness = interpolated & 127;
                assertTrue("Interpolated shade cannot wrap into bright/dark stripes",
                    lightness >= low && lightness <= high);
            }
        }
    }
}
