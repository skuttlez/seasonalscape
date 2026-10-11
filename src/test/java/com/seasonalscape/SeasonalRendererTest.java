package com.seasonalscape;

import com.retronpcswapper.RetroDrawCallbacks;
import java.lang.reflect.Proxy;
import net.runelite.api.Client;
import net.runelite.api.Scene;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.plugins.gpu.GpuPlugin;
import org.junit.Test;
import rs117.hd.HdPlugin;
import static org.junit.Assert.*;

public class SeasonalRendererTest
{
    @Test
    public void defaultGraphicsAndTheBuiltInGpuRemainSupported()
    {
        Fixture f = new Fixture();
        assertTrue(f.renderer.supported());
        assertNull(f.renderer.gpu());
        GpuPlugin gpu = new GpuPlugin();
        f.callbacks = gpu;
        assertTrue(f.renderer.supported());
        assertSame(gpu, f.renderer.gpu());
    }

    @Test
    public void missingCallbacksDuringGpuTransitionAreNotSoftware()
    {
        Fixture f = new Fixture();
        f.gpuMode = true;
        assertUnsupported(f);
        f.gpuMode = false;
        assertTrue(f.renderer.supported());
    }

    @Test
    public void retroUsesActiveRegisteredGpuWithoutCallingTheWrapperGetter()
    {
        Fixture f = new Fixture();
        GpuPlugin gpu = f.activeGpu();
        RetroDrawCallbacks wrapper = new RetroDrawCallbacks(gpu);
        wrapper.failOnGetDelegate = true;
        f.callbacks = wrapper;
        assertTrue(f.renderer.supported());
        assertSame(gpu, f.renderer.gpu());
        assertEquals("Compatibility must never invoke another plugin's getter", 0, wrapper.getterCalls);
    }

    @Test
    public void configuredButInactiveGpuDoesNotApproveRetro()
    {
        Fixture f = new Fixture();
        GpuPlugin gpu = new GpuPlugin();
        f.plugins.add(gpu, false);
        f.gpuMode = true;
        f.callbacks = new RetroDrawCallbacks(gpu);
        assertUnsupported(f);
    }

    @Test
    public void gpuModeMustBeActiveEvenWithRegisteredGpu()
    {
        Fixture f = new Fixture();
        GpuPlugin gpu = f.activeGpu();
        f.gpuMode = false;
        f.callbacks = new RetroDrawCallbacks(gpu);
        assertUnsupported(f);
    }

    @Test
    public void activeHdRejectsRetroEvenIfGpuIsAlsoActive()
    {
        Fixture f = new Fixture();
        GpuPlugin gpu = f.activeGpu();
        HdPlugin hd = new HdPlugin();
        f.plugins.add(hd, true);
        f.callbacks = new RetroDrawCallbacks(gpu);
        assertUnsupported(f);
        f.plugins.setActive(hd, false);
        f.callbacks = new RetroDrawCallbacks(gpu);
        assertSame("An installed but inactive HD plugin must not block GPU", gpu, f.renderer.gpu());
    }

    @Test
    public void unknownCallbacksAndSubclassesRemainUnsupportedWithActiveGpu()
    {
        Fixture f = new Fixture();
        GpuPlugin gpu = f.activeGpu();
        f.callbacks = new UnknownRenderer();
        assertUnsupported(f);
        UnknownWrapper unknown = new UnknownWrapper(gpu);
        f.callbacks = unknown;
        assertUnsupported(f);
        assertEquals(0, unknown.getterCalls);
        f.callbacks = new RetroSubclass(gpu);
        assertUnsupported(f);
    }

    @Test
    public void stoppingGpuRejectsTheExistingWrapperAndRequiresAFreshOne()
    {
        Fixture f = new Fixture();
        GpuPlugin gpu = f.activeGpu();
        f.callbacks = new RetroDrawCallbacks(gpu);
        assertSame(gpu, f.renderer.gpu());
        f.plugins.setActive(gpu, false);
        assertUnsupported(f);
        f.plugins.setActive(gpu, true);
        assertUnsupported(f);
        f.callbacks = new RetroDrawCallbacks(gpu);
        assertSame(gpu, f.renderer.gpu());
    }

    @Test
    public void disablingGpuModeRejectsTheExistingWrapperAndRequiresAFreshOne()
    {
        Fixture f = new Fixture();
        GpuPlugin gpu = f.activeGpu();
        f.callbacks = new RetroDrawCallbacks(gpu);
        assertSame(gpu, f.renderer.gpu());
        f.gpuMode = false;
        assertUnsupported(f);
        f.gpuMode = true;
        assertUnsupported(f);
        f.callbacks = new RetroDrawCallbacks(gpu);
        assertSame(gpu, f.renderer.gpu());
    }

    @Test
    public void aRejectedWrapperDoesNotBecomeApprovedWhenGpuStartsLater()
    {
        Fixture f = new Fixture();
        GpuPlugin gpu = new GpuPlugin();
        f.plugins.add(gpu, false);
        f.gpuMode = true;
        f.callbacks = new RetroDrawCallbacks(gpu);
        assertUnsupported(f);
        f.plugins.setActive(gpu, true);
        assertUnsupported(f);
        f.callbacks = new RetroDrawCallbacks(gpu);
        assertSame(gpu, f.renderer.gpu());
    }

    @Test
    public void aChangedActiveGpuDoesNotReuseApprovalForAnOldWrapper()
    {
        Fixture f = new Fixture();
        GpuPlugin first = f.activeGpu();
        f.callbacks = new RetroDrawCallbacks(first);
        assertSame(first, f.renderer.gpu());
        f.plugins.setActive(first, false);
        GpuPlugin second = f.activeGpu();
        assertUnsupported(f);
        f.callbacks = new RetroDrawCallbacks(second);
        assertSame(second, f.renderer.gpu());
    }

    @Test
    public void multipleActiveGpuPluginsFailClosed()
    {
        Fixture f = new Fixture();
        GpuPlugin gpu = f.activeGpu();
        f.activeGpu();
        f.callbacks = new RetroDrawCallbacks(gpu);
        assertUnsupported(f);
    }

    @Test
    public void absentPluginRegistryFailsClosedForRetro()
    {
        Fixture f = new Fixture();
        GpuPlugin gpu = f.activeGpu();
        f.callbacks = new RetroDrawCallbacks(gpu);
        SeasonalRenderer renderer = new SeasonalRenderer(f.client, null);
        assertFalse(renderer.supported());
        assertNull(renderer.gpu());
    }

    private static void assertUnsupported(Fixture f)
    {
        assertFalse(f.renderer.supported());
        assertNull(f.renderer.gpu());
    }

    private static final class Fixture
    {
        private DrawCallbacks callbacks;
        private boolean gpuMode;
        private final SeasonalPluginManagerTest plugins = new SeasonalPluginManagerTest();
        private final Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
            new Class<?>[]{Client.class}, (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "getDrawCallbacks": return callbacks;
                    case "isGpu": return gpuMode;
                    default: throw new AssertionError("Unexpected client access: " + method.getName());
                }
            });
        private final SeasonalRenderer renderer = new SeasonalRenderer(client, plugins.manager());

        private GpuPlugin activeGpu()
        {
            GpuPlugin gpu = new GpuPlugin();
            plugins.add(gpu, true);
            gpuMode = true;
            return gpu;
        }
    }

    private static class UnknownRenderer implements DrawCallbacks
    {
        @Override public void draw(int overlayColor) {}
        @Override public void swapScene(Scene scene) {}
    }

    public static final class UnknownWrapper extends UnknownRenderer
    {
        private final DrawCallbacks delegate;
        private int getterCalls;

        private UnknownWrapper(DrawCallbacks delegate) { this.delegate = delegate; }

        public DrawCallbacks getDelegate()
        {
            getterCalls++;
            return delegate;
        }
    }

    private static final class RetroSubclass extends RetroDrawCallbacks
    {
        private RetroSubclass(DrawCallbacks delegate) { super(delegate); }
    }
}
