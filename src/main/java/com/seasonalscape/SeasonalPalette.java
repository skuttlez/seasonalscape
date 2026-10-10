package com.seasonalscape;

/** Pure Jagex HSL transforms. Textured faces must be excluded by the caller. */
final class SeasonalPalette
{
    private SeasonalPalette() {}

    static boolean isVegetation(int color)
    {
        if (color < 0 || color > 65535)
        {
            return false;
        }
        int hue = color >>> 10;
        int saturation = color >>> 7 & 7;
        int lightness = color & 127;
        return hue >= 12 && hue <= 28 && saturation >= 2
            && lightness >= 8 && lightness <= 118;
    }

    static int ground(int color, Season season)
    {
        // Grass extends into olive hues and deep tree shadows that are not
        // safe to classify as leaves on an object model.
        if (SeasonalGroundColors.isGrass(color))
        {
            int lightness = color & 127;
            if (season == Season.SPRING) { return pack(20, 4, lightness + 9); }
            if (season == Season.SUMMER) { return pack(17, 4, lightness + 4); }
            // GPU's classic shading interpolates packed HSL. Every corner of
            // classified grass needs the same hue/saturation, including olive
            // underlays and dark shadows, to avoid wrapped lightness bands.
            if (season == Season.AUTUMN) { return pack(9, 3, lightness + 2); }
        }
        return recolor(color, season, false);
    }

    static int foliage(int color, Season season)
    {
        return recolor(color, season, true);
    }

    private static int recolor(int color, Season season, boolean foliage)
    {
        if (!isVegetation(color))
        {
            return color;
        }
        int lightness = color & 127;
        switch (season)
        {
            case SPRING:
                return pack(21, 5, lightness + 12);
            case SUMMER:
                return pack(22, 5, lightness - 3);
            case AUTUMN:
                return pack(foliage ? 3 + (color >>> 10) % 7 : 9,
                    foliage ? 6 : 3, lightness + (foliage ? 7 : 2));
            case WINTER:
                return foliage ? pack(20, 1, 78 + lightness * 22 / 100)
                    : pack(36, 0, 83 + lightness * 28 / 100);
            default:
                return color;
        }
    }

    private static int pack(int hue, int saturation, int lightness)
    {
        return hue << 10 | saturation << 7 | Math.max(2, Math.min(126, lightness));
    }
}
