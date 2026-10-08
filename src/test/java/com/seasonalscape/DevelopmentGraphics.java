package com.seasonalscape;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.gpu.GpuPlugin;

/** Development-launcher graphics preference; not included in the plugin JAR. */
public final class DevelopmentGraphics
{
    private DevelopmentGraphics() {}

    public static void useGpu(Consumer<String> report)
    {
        Thread worker = new Thread(() -> {
            try
            {
                PluginManager manager = RuneLite.getInjector().getInstance(PluginManager.class);
                ClientThread clientThread = RuneLite.getInjector().getInstance(ClientThread.class);
                Client client = RuneLite.getInjector().getInstance(Client.class);
                GpuPlugin[] gpu = new GpuPlugin[1];
                SwingUtilities.invokeAndWait(() -> {
                    for (Plugin plugin : manager.getPlugins())
                    {
                        if (plugin instanceof GpuPlugin) { gpu[0] = (GpuPlugin) plugin; }
                    }
                    if (gpu[0] == null) { throw new IllegalStateException("Built-in GPU plugin unavailable"); }
                    for (Plugin plugin : manager.getPlugins())
                    {
                        PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
                        if ("rs117.hd.HdPlugin".equals(plugin.getClass().getName())
                            || (descriptor != null && "117 HD".equals(descriptor.name())))
                        {
                            manager.setPluginEnabled(plugin, false);
                            try { manager.stopPlugin(plugin); }
                            catch (Exception exception) { throw new IllegalStateException("HD shutdown failed", exception); }
                        }
                    }
                });
                awaitClient(clientThread, () -> client.getDrawCallbacks() == null || client.getDrawCallbacks() == gpu[0]);
                SwingUtilities.invokeAndWait(() -> {
                    manager.setPluginEnabled(gpu[0], true);
                    try { manager.startPlugin(gpu[0]); }
                    catch (Exception exception) { throw new IllegalStateException("GPU startup failed", exception); }
                });
                awaitClient(clientThread, () -> client.getDrawCallbacks() == gpu[0]);
                RuneLite.getInjector().getInstance(ConfigManager.class)
                    .setConfiguration("gpu", "brightTextures", true);
                SwingUtilities.invokeAndWait(() -> {
                    if (!manager.isPluginActive(gpu[0]) || !manager.isPluginEnabled(gpu[0]))
                    {
                        throw new IllegalStateException("GPU was disabled during startup");
                    }
                });
                report.accept("GPU_ACTIVE: built-in RuneLite GPU verified; 117 HD disabled.");
            }
            catch (Exception exception)
            {
                report.accept("GPU_SWITCH_FAILED: " + exception.getClass().getSimpleName());
            }
        }, "SeasonalScapeGraphicsSetup");
        worker.setDaemon(true);
        worker.start();
    }

    private static void awaitClient(ClientThread clientThread, BooleanSupplier ready) throws Exception
    {
        CompletableFuture<Void> complete = new CompletableFuture<>();
        clientThread.invokeLater(() -> {
            if (complete.isDone()) { return true; }
            try
            {
                if (!ready.getAsBoolean()) { return false; }
                complete.complete(null);
            }
            catch (Exception exception) { complete.completeExceptionally(exception); }
            return true;
        });
        try { complete.get(20, TimeUnit.SECONDS); }
        finally { complete.cancel(false); }
    }
}
