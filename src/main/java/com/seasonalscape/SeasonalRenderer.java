package com.seasonalscape;

import java.lang.ref.WeakReference;
import net.runelite.api.Client;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.gpu.GpuPlugin;

/** Client-thread-only compatibility checks using RuneLite's public plugin state. */
final class SeasonalRenderer
{
    private static final String RETRO_CALLBACKS = "com.retronpcswapper.RetroDrawCallbacks";
    private static final String HD_PLUGIN = "rs117.hd.HdPlugin";
    private final Client client;
    private final PluginManager plugins;
    private WeakReference<DrawCallbacks> observedWrapper = new WeakReference<>(null);
    private GpuPlugin wrapperGpu;

    SeasonalRenderer(Client client, PluginManager plugins)
    {
        this.client = client;
        this.plugins = plugins;
    }

    boolean supported()
    {
        // A missing callback while GPU mode is set is a renderer transition, not software.
        return client.getDrawCallbacks() == null ? !client.isGpu() : gpu() != null;
    }

    GpuPlugin gpu()
    {
        DrawCallbacks callbacks = client.getDrawCallbacks();
        if (callbacks instanceof GpuPlugin) { return (GpuPlugin) callbacks; }
        if (callbacks == null || !RETRO_CALLBACKS.equals(callbacks.getClass().getName())) { return null; }

        // Published Retro wraps only the built-in GPU or 117 HD's zone renderer.
        // RuneLite makes those plugins mutually exclusive. Read the public registry;
        // never inspect the wrapper's fields or invoke methods on another plugin.
        GpuPlugin activeGpu = activeGpu();
        if (callbacks != observedWrapper.get())
        {
            observedWrapper = new WeakReference<>(callbacks);
            wrapperGpu = activeGpu;
        }
        else if (activeGpu != wrapperGpu)
        {
            // Never approve a previously rejected/stopped wrapper merely because GPU
            // becomes active. Retro must install a fresh wrapper for the new renderer.
            wrapperGpu = null;
        }
        return wrapperGpu;
    }

    private GpuPlugin activeGpu()
    {
        if (plugins == null || !client.isGpu()) { return null; }
        GpuPlugin gpu = null;
        for (Plugin plugin : plugins.getPlugins())
        {
            if (!(plugin instanceof GpuPlugin) && !HD_PLUGIN.equals(plugin.getClass().getName())) { continue; }
            if (!plugins.isPluginActive(plugin)) { continue; }
            if (HD_PLUGIN.equals(plugin.getClass().getName())) { return null; }
            if (plugin instanceof GpuPlugin)
            {
                if (gpu != null) { return null; }
                gpu = (GpuPlugin) plugin;
            }
        }
        return gpu;
    }
}
