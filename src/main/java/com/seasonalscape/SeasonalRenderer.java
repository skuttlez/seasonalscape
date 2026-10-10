package com.seasonalscape;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.plugins.gpu.GpuPlugin;

/** Recognizes supported renderers without replacing or bypassing another plugin's callbacks. */
final class SeasonalRenderer
{
    private static final String RETRO_CALLBACKS = "com.retronpcswapper.RetroDrawCallbacks";
    private static final int MAX_WRAPPERS = 8;
    // ClassValue permits Plugin Hub classloaders to unload when a plugin is updated.
    private static final ClassValue<Optional<Method>> DELEGATES = new ClassValue<Optional<Method>>()
    {
        @Override
        protected Optional<Method> computeValue(Class<?> type)
        {
            if (!RETRO_CALLBACKS.equals(type.getName())) { return Optional.empty(); }
            try
            {
                Method getter = type.getMethod("getDelegate");
                return getter.getReturnType() == DrawCallbacks.class
                    && !Modifier.isStatic(getter.getModifiers()) ? Optional.of(getter) : Optional.empty();
            }
            catch (ReflectiveOperationException | SecurityException ex)
            {
                return Optional.empty();
            }
        }
    };

    private SeasonalRenderer() {}

    static boolean supported(DrawCallbacks callbacks)
    {
        return callbacks == null || gpu(callbacks) != null;
    }

    static GpuPlugin gpu(DrawCallbacks callbacks)
    {
        for (int depth = 0; depth <= MAX_WRAPPERS; depth++)
        {
            if (callbacks instanceof GpuPlugin) { return (GpuPlugin) callbacks; }
            if (callbacks == null || depth == MAX_WRAPPERS) { return null; }
            Optional<Method> getter = DELEGATES.get(callbacks.getClass());
            if (!getter.isPresent()) { return null; }
            try
            {
                // Retro is optional and loaded separately. Only its public getter is used;
                // never access private fields or assume that its delegate is the GPU plugin.
                Object delegate = getter.get().invoke(callbacks);
                if (!(delegate instanceof DrawCallbacks) || delegate == callbacks) { return null; }
                callbacks = (DrawCallbacks) delegate;
            }
            catch (ReflectiveOperationException | RuntimeException ex)
            {
                return null;
            }
        }
        return null;
    }
}
