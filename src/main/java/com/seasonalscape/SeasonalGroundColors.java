package com.seasonalscape;

/** Terrain grass includes the yellow-green hues and dark corners beneath trees. */
final class SeasonalGroundColors
{
    private SeasonalGroundColors() {}

    static boolean isGrass(int color)
    {
        if (color < 0 || color > 65535) { return false; }
        int hue = color >>> 10;
        int saturation = color >>> 7 & 7;
        int lightness = color & 127;
        // Grass blended beside Varrock's buildings reaches olive hues 8 and 9.
        // The neighboring stone and dirt paths use hue 7 and remain unchanged.
        return hue >= 8 && hue <= 28 && saturation >= 2
            && lightness >= 2 && lightness <= 118;
    }

    static int ground(int color, Season season)
    {
        if (season == Season.WINTER && isGrass(color))
        {
            // Preserve terrain lighting while covering shaded grass continuously.
            return 36 << 10 | 83 + (color & 127) * 28 / 100;
        }
        return SeasonalPalette.ground(color, season);
    }
}
