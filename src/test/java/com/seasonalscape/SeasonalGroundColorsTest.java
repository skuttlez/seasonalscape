package com.seasonalscape;

import org.junit.Test;
import static org.junit.Assert.*;

public class SeasonalGroundColorsTest
{
    @Test
    public void recognizesObservedGrassShadesAndRejectsRoadsAndSentinels()
    {
        for (int grass : new int[]{8582, 8586, 8594, 9609, 9616, 9622, 10638, 11655, 12806, 16775})
        {
            assertTrue(SeasonalGroundColors.isGrass(grass));
            assertNotEquals(grass, SeasonalGroundColors.ground(grass, Season.WINTER));
            assertNotEquals(grass, SeasonalGroundColors.ground(grass, Season.SPRING));
            assertNotEquals(grass, SeasonalGroundColors.ground(grass, Season.SUMMER));
        }
        for (int other : new int[]{7200, 7337, 7455, 7566, 12345678, -1, -2})
        {
            assertFalse(SeasonalGroundColors.isGrass(other));
            for (Season season : Season.values())
            {
                assertEquals(other, SeasonalGroundColors.ground(other, season));
            }
        }
    }

    @Test
    public void grassPalettesRemainConsistentAndAutumnKeepsItsOriginalRange()
    {
        for (int grass : new int[]{8582, 8586, 8594, 9609, 9616, 9622, 10638, 11655, 12806, 16775, 20 << 10 | 4 << 7 | 50})
        {
            assertEquals(SeasonalPalette.ground(grass, Season.SPRING), SeasonalGroundColors.ground(grass, Season.SPRING));
            assertEquals(SeasonalPalette.ground(grass, Season.SUMMER), SeasonalGroundColors.ground(grass, Season.SUMMER));
            assertEquals(SeasonalPalette.ground(grass, Season.AUTUMN), SeasonalGroundColors.ground(grass, Season.AUTUMN));
        }
        assertEquals(8582, SeasonalGroundColors.ground(8582, Season.AUTUMN));
        assertEquals(9609, SeasonalGroundColors.ground(9609, Season.AUTUMN));
    }
}
