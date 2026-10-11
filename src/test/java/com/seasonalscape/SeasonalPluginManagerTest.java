package com.seasonalscape;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collection;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;

/** Test-only registry setup; the submitted plugin uses only PluginManager's public API. */
public final class SeasonalPluginManagerTest
{
    private final PluginManager manager;
    public final Collection<Plugin> plugins;
    public final Collection<Plugin> active;

    @SuppressWarnings("unchecked")
    public SeasonalPluginManagerTest()
    {
        try
        {
            // RuneLite's package is signed, so a test subclass cannot share it.
            // Construct its real registry without starting services or plugins.
            Constructor<?>[] constructors = PluginManager.class.getDeclaredConstructors();
            if (constructors.length != 1) { throw new AssertionError("Unexpected PluginManager constructors"); }
            Constructor<?> constructor = constructors[0];
            constructor.setAccessible(true);
            manager = (PluginManager) constructor.newInstance(false, false, null, null, null, null, null);
            Field installed = PluginManager.class.getDeclaredField("plugins");
            Field running = PluginManager.class.getDeclaredField("activePlugins");
            installed.setAccessible(true);
            running.setAccessible(true);
            plugins = (Collection<Plugin>) installed.get(manager);
            active = (Collection<Plugin>) running.get(manager);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Cannot prepare PluginManager test registry", exception);
        }
    }

    public PluginManager manager() { return manager; }

    public void add(Plugin plugin, boolean running)
    {
        plugins.add(plugin);
        setActive(plugin, running);
    }

    public void setActive(Plugin plugin, boolean running)
    {
        if (running) { if (!active.contains(plugin)) { active.add(plugin); } }
        else { active.remove(plugin); }
    }
}
