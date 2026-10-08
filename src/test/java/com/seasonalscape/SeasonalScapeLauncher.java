package com.seasonalscape;

import java.awt.Frame;
import javax.swing.SwingUtilities;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;

public class SeasonalScapeLauncher
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(SeasonalScapePlugin.class);
        RuneLite.main(args);
        if (RuneLite.getInjector() == null) { return; }
        SwingUtilities.invokeLater(() -> {
            for (Frame frame : Frame.getFrames())
            {
                if (frame.isDisplayable() && frame.getTitle().startsWith("RuneLite"))
                {
                    String prefix = "SeasonalScape Test - ";
                    frame.addPropertyChangeListener("title", event -> {
                        if (!frame.getTitle().startsWith(prefix))
                        {
                            frame.setTitle(prefix + frame.getTitle());
                        }
                    });
                    frame.setTitle(prefix + frame.getTitle());
                }
            }
            PluginManager manager = RuneLite.getInjector().getInstance(PluginManager.class);
            for (Plugin plugin : manager.getPlugins())
            {
                if (plugin instanceof SeasonalScapePlugin)
                {
                    try
                    {
                        manager.setPluginEnabled(plugin, true);
                        manager.startPlugin(plugin);
                        System.out.println("SeasonalScape test plugin enabled.");
                    }
                    catch (Exception exception)
                    {
                        System.err.println("SeasonalScape could not start: " + exception.getClass().getSimpleName());
                        exception.printStackTrace();
                    }
                }
            }
            DevelopmentGraphics.useGpu(System.out::println);
        });
    }
}
