package com.seasonalscape;

import org.junit.Test;
import static org.junit.Assert.*;

public class SeasonalWorldAreaTest
{
    @Test
    public void mainOverworldIncludesOriginalAreaWesternMainlandAndKourend()
    {
        assertTrue(SeasonalWorldArea.contains(3200, 3200));
        assertTrue(SeasonalWorldArea.contains(2600, 3300));
        assertTrue(SeasonalWorldArea.contains(1640, 3680));
        assertTrue(SeasonalWorldArea.contains(1640, 3100));
    }

    @Test
    public void undergroundAndSeparateMapsRemainExcludedAtBothBoundaries()
    {
        assertTrue(SeasonalWorldArea.contains(0, 0));
        assertTrue(SeasonalWorldArea.contains(4095, 4095));
        assertFalse(SeasonalWorldArea.contains(-1, 3200));
        assertFalse(SeasonalWorldArea.contains(3200, -1));
        assertFalse(SeasonalWorldArea.contains(4096, 3200));
        assertFalse(SeasonalWorldArea.contains(3200, 4096));
        assertFalse(SeasonalWorldArea.contains(3200, 3200 + 6400));
        assertFalse(SeasonalWorldArea.contains(3200, 6000));
    }
}
