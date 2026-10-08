package com.seasonalscape;

import org.junit.Test;
import static org.junit.Assert.*;

public class SeasonalPaletteTest
{
    @Test
    public void restoresOriginalAfterSeveralPaletteChanges()
    {
        int[] current = {120, 200, -2};
        SeasonalSceneRecolorer.Colors saved = new SeasonalSceneRecolorer.Colors(current);
        saved.apply(current, 0, 350);
        saved.apply(current, 0, 460);
        saved.apply(current, 1, 300);
        saved.restore(current);
        assertArrayEquals(new int[]{120, 200, -2}, current);
    }

    @Test
    public void respectsAnotherPluginsChangesWhileRestoringOtherFaces()
    {
        int[] current = {120, 200, 300};
        SeasonalSceneRecolorer.Colors saved = new SeasonalSceneRecolorer.Colors(current);
        saved.apply(current, 0, 350);
        saved.apply(current, 1, 450);
        saved.apply(current, 2, 550);
        current[0] = 999;
        assertFalse(saved.apply(current, 0, 777));
        current[2] = 888;
        saved.restore(current);
        assertArrayEquals(new int[]{999, 200, 888}, current);
    }

    @Test
    public void leavesSentinelsStoneWaterAndTrunkColorsAlone()
    {
        int[] unchanged = {-2, -1, 12345678, 65536, 0,
            36 << 10 | 5 << 7 | 60, 5 << 10 | 4 << 7 | 50,
            20 << 10 | 1 << 7 | 50};
        for (Season season : Season.values())
        {
            for (int color : unchanged)
            {
                assertFalse(SeasonalPalette.isVegetation(color));
                assertEquals(color, SeasonalPalette.ground(color, season));
                assertEquals(color, SeasonalPalette.foliage(color, season));
            }
        }
    }

    @Test
    public void winterIsPaleAndPreservesRelativeShading()
    {
        int shadow = SeasonalPalette.ground(20 << 10 | 4 << 7 | 20, Season.WINTER);
        int sun = SeasonalPalette.ground(20 << 10 | 4 << 7 | 100, Season.WINTER);
        assertEquals(0, shadow >>> 7 & 7);
        assertEquals(0, sun >>> 7 & 7);
        assertTrue((shadow & 127) >= 83);
        assertTrue((sun & 127) > (shadow & 127));
    }

    @Test
    public void springAndSummerUseDistinctGreensWithPreservedShading()
    {
        int grass = 20 << 10 | 4 << 7 | 50;
        int springGround = SeasonalPalette.ground(grass, Season.SPRING);
        int summerGround = SeasonalPalette.ground(grass, Season.SUMMER);
        int springLeaves = SeasonalPalette.foliage(grass, Season.SPRING);
        int summerLeaves = SeasonalPalette.foliage(grass, Season.SUMMER);
        assertTrue("Summer grass is warmed toward yellow-green", (summerGround >>> 10) < (springGround >>> 10));
        assertTrue("Fresh spring leaves are brighter", (springLeaves & 127) > (summerLeaves & 127));
        assertTrue("Mature summer leaves stay richly green", (summerLeaves >>> 7 & 7) >= 5);
        assertNotEquals("Summer must have its own terrain palette", grass, summerGround);
        assertNotEquals("Summer must have its own foliage palette", grass, summerLeaves);

        for (Season season : new Season[]{Season.SPRING, Season.SUMMER})
        {
            int shadow = 20 << 10 | 4 << 7 | 20;
            int sun = 20 << 10 | 4 << 7 | 100;
            assertTrue((SeasonalPalette.ground(shadow, season) & 127)
                < (SeasonalPalette.ground(sun, season) & 127));
            assertTrue((SeasonalPalette.foliage(shadow, season) & 127)
                < (SeasonalPalette.foliage(sun, season) & 127));
        }
    }

    @Test
    public void palettesRemainValidForEveryEligibleHslColor()
    {
        for (int color = 0; color <= 65535; color++)
        {
            if (!SeasonalPalette.isVegetation(color))
            {
                continue;
            }
            for (Season season : Season.values())
            {
                int result = SeasonalPalette.foliage(color, season);
                assertTrue(result >= 0 && result <= 65535);
                assertTrue((result & 127) >= 2 && (result & 127) <= 126);
                int ground = SeasonalPalette.ground(color, season);
                assertTrue(ground >= 0 && ground <= 65535);
                assertTrue((ground & 127) >= 2 && (ground & 127) <= 126);
            }
            int autumn = SeasonalPalette.foliage(color, Season.AUTUMN);
            assertTrue((autumn >>> 10) >= 3 && (autumn >>> 10) <= 9);
        }
    }
}
