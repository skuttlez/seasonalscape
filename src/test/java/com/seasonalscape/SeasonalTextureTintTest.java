package com.seasonalscape;

import org.junit.Test;
import static org.junit.Assert.*;

public class SeasonalTextureTintTest
{
    @Test
    public void preservesFaceSentinelsAndValuesThatAreNotTextureBrightness()
    {
        int[] unchanged = {Integer.MIN_VALUE, -2, -1, 128, 5642,
            20 << 10 | 4 << 7 | 50, 65535, 12345678, Integer.MAX_VALUE};
        for (int texture : new int[]{8, 30, 60})
        {
            for (int color : unchanged)
            {
                assertEquals("Texture " + texture + " must preserve " + color,
                    color, SeasonalTextureTint.autumn(color, texture));
                for (Season season : Season.values())
                {
                    assertEquals("Season " + season + " must preserve " + color,
                        color, SeasonalTextureTint.foliage(color, texture, season));
                }
            }
        }
    }

    @Test
    public void autumnTintKeepsFaceShadowsOrderedWithinVisibleHslBounds()
    {
        for (int texture : new int[]{8, 30, 60})
        {
            int previousLightness = -1;
            for (int brightness = 0; brightness <= 127; brightness++)
            {
                int tint = SeasonalTextureTint.autumn(brightness, texture);
                int hue = tint >>> 10;
                int lightness = tint & 127;
                assertTrue(tint >= 0 && tint <= 65535);
                assertTrue("Tint should remain orange or amber", hue >= 4 && hue <= 6);
                assertTrue("Shadows must remain visible", lightness >= 24);
                assertTrue("Highlights must retain color", lightness <= 88);
                assertTrue("A brighter face cannot become darker", lightness >= previousLightness);
                previousLightness = lightness;
            }
            int shadow = SeasonalTextureTint.autumn(33, texture) & 127;
            int highlight = SeasonalTextureTint.autumn(126, texture) & 127;
            assertTrue("Foliage needs visible shading contrast", highlight - shadow >= 40);
        }
    }

    @Test
    public void selectsOnlyTheThreeVerifiedLeafTextures()
    {
        for (int texture = Short.MIN_VALUE; texture <= Short.MAX_VALUE; texture++)
        {
            boolean expected = texture == 8 || texture == 30 || texture == 60;
            assertEquals("Unexpected texture selection: " + texture,
                expected, SeasonalTextureTint.isLeafTexture(texture));
        }
        assertFalse(SeasonalTextureTint.isLeafTexture(Integer.MIN_VALUE));
        assertFalse(SeasonalTextureTint.isLeafTexture(Integer.MAX_VALUE));
    }

    @Test
    public void springAndSummerTintsStayGreenAndRetainShading()
    {
        for (int texture : new int[]{8, 30, 60})
        {
            for (Season season : new Season[]{Season.SPRING, Season.SUMMER})
            {
                int previousLightness = -1;
                for (int brightness = 0; brightness <= 127; brightness++)
                {
                    int color = SeasonalTextureTint.foliage(brightness, texture, season);
                    int hue = color >>> 10;
                    int lightness = color & 127;
                    assertTrue("Foliage stays green", hue >= 20 && hue <= 22);
                    assertTrue("Texture shadows remain visible", lightness >= 26);
                    assertTrue("Highlights keep their color", lightness <= 98);
                    assertTrue(lightness >= previousLightness);
                    previousLightness = lightness;
                    assertTrue("Spring leaves are brighter than summer leaves",
                        (SeasonalTextureTint.foliage(brightness, texture, Season.SPRING) & 127)
                            > (SeasonalTextureTint.foliage(brightness, texture, Season.SUMMER) & 127));
                }
            }
        }
    }

    @Test
    public void tintLeavesWinterAndUnknownTexturesUntouched()
    {
        for (int brightness = 0; brightness <= 127; brightness++)
        {
            for (Season season : Season.values())
            {
                assertEquals(brightness, SeasonalTextureTint.foliage(brightness, 7, season));
                assertEquals(brightness, SeasonalTextureTint.foliage(brightness, -1, season));
            }
            assertEquals(brightness, SeasonalTextureTint.foliage(brightness, 8, Season.WINTER));
            assertEquals(SeasonalTextureTint.autumn(brightness, 8),
                SeasonalTextureTint.foliage(brightness, 8, Season.AUTUMN));
        }
    }
}
