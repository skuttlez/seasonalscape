package com.seasonalscape;

import net.runelite.api.JagexColor;

/** Turns independent cache mesh pieces into two small flowers per decoration. */
final class SeasonalFlowerGeometry
{
    private SeasonalFlowerGeometry()
    {
    }

    static boolean appliesTo(Season season)
    {
        return season == Season.SPRING || season == Season.SUMMER;
    }

    /**
     * Five radial petals and a center share the first six pieces of each flower.
     * Spring's last two pieces are fallen petals; summer's are crossed stems.
     * Source UVs and face indices stay intact, preserving the cache topology.
     */
    static void vertex(Season season, int variant, int piece, float u, float v,
        float[] x, float[] y, float[] z, int index)
    {
        int flower = piece / 8;
        int role = piece % 8;
        double rotation = variant * 0.71 + flower * 2.4;
        double placement = rotation + 0.4;
        float centerX = (float) Math.cos(placement) * 20;
        float centerZ = (float) Math.sin(placement) * 20;
        float headHeight = season == Season.SUMMER ? 18 + (variant + flower * 3) % 7 : 1.6f;
        float along;
        float across;
        float height;
        if (role < 5)
        {
            rotation += role * Math.PI * 2 / 5;
            along = 0.7f + (u + 0.5f) * (season == Season.SUMMER ? 6.5f : 5.5f);
            across = v * (season == Season.SUMMER ? 5.2f : 4.6f);
            height = headHeight + (1 - Math.abs(u * 2)) * 0.7f;
        }
        else if (role == 5)
        {
            along = u * 3.4f;
            across = v * 3.4f;
            height = headHeight + 0.8f;
        }
        else if (season == Season.SUMMER)
        {
            // Two narrow upright planes keep the stem legible from different
            // camera angles. A slight lean also gives each face some top area.
            rotation += (role - 6) * Math.PI / 2;
            along = (u + 0.5f) * 1.5f;
            across = v * 1.4f;
            height = 0.25f + (u + 0.5f) * headHeight;
        }
        else
        {
            rotation += role * 1.7;
            along = 9 + u * 5;
            across = v * 3.4f;
            height = 0.35f + (1 - Math.abs(u * 2)) * 0.5f;
        }
        float cosine = (float) Math.cos(rotation);
        float sine = (float) Math.sin(rotation);
        x[index] = centerX + along * cosine - across * sine;
        z[index] = centerZ + along * sine + across * cosine;
        y[index] = -height;
    }

    static short color(Season season, int variant, int piece)
    {
        int flower = piece / 8;
        int role = piece % 8;
        if (role == 5)
        {
            return JagexColor.packHSL(9, season == Season.SUMMER ? 5 : 3,
                season == Season.SUMMER ? 70 : 83);
        }
        if (season == Season.SUMMER)
        {
            if (role >= 6)
            {
                return JagexColor.packHSL(20, 3, 42);
            }
            // Each clump pairs a buttercup-yellow flower with a white daisy.
            return (flower + variant) % 2 == 0
                ? JagexColor.packHSL(10, 5, 87 + role % 2 * 3)
                : JagexColor.packHSL(9, 0, 110 + role % 2 * 3);
        }
        int shade = (flower + variant) % 3;
        if (shade == 0)
        {
            return JagexColor.packHSL(9, 0, 111 + role % 2 * 3);
        }
        return JagexColor.packHSL(shade == 1 ? 61 : 47, 2, 103 + role % 2 * 3);
    }
}
