package com.seasonalscape;

import net.runelite.api.Client;
import net.runelite.client.plugins.gpu.GpuPlugin;
import net.runelite.client.plugins.gpu.GpuPluginConfig;

/** GPU's bright-texture mode multiplies texture pixels by the face HSL color. */
final class SeasonalTextureTint
{
    private SeasonalTextureTint() {}

    static boolean supported(Client client)
    {
        if (!(client.getDrawCallbacks() instanceof GpuPlugin)) { return false; }
        GpuPlugin gpu = (GpuPlugin) client.getDrawCallbacks();
        return gpu.getInjector() != null
            && gpu.getInjector().getInstance(GpuPluginConfig.class).brightTextures();
    }

    static boolean isLeafTexture(int texture)
    {
        // Verified foliage textures in RuneLite 1.13.1's current game cache.
        return texture == 8 || texture == 30 || texture == 60;
    }

    static int autumn(int brightness, int texture)
    {
        // Negative values are face sentinels. Already-colored values belong to
        // another renderer/plugin and must not be interpreted as brightness.
        if (brightness < 0 || brightness > 127) { return brightness; }
        int hue = texture == 30 ? 6 : texture == 60 ? 4 : 5;
        int lightness = 24 + brightness * 64 / 127;
        return hue << 10 | 6 << 7 | lightness;
    }

    static int foliage(int brightness, int texture, Season season)
    {
        // Keep the cutout texture itself untouched; GPU multiplies its pixels
        // by this face tint, preserving the spaces between leaves.
        if (!isLeafTexture(texture) || brightness < 0 || brightness > 127) { return brightness; }
        switch (season)
        {
            case SPRING:
                return (texture == 30 ? 20 : 21) << 10 | 4 << 7
                    | 34 + brightness * 64 / 127;
            case SUMMER:
                return (texture == 30 ? 21 : 22) << 10 | 5 << 7
                    | 26 + brightness * 60 / 127;
            case AUTUMN:
                return autumn(brightness, texture);
            default:
                return brightness;
        }
    }
}
