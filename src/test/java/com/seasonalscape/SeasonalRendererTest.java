package com.seasonalscape;

import com.retronpcswapper.RetroDrawCallbacks;
import net.runelite.api.Scene;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.plugins.gpu.GpuPlugin;
import org.junit.Test;
import static org.junit.Assert.*;

public class SeasonalRendererTest
{
    @Test
    public void defaultGraphicsAndTheBuiltInGpuRemainSupported()
    {
        GpuPlugin gpu = new GpuPlugin();
        assertTrue(SeasonalRenderer.supported(null));
        assertNull(SeasonalRenderer.gpu(null));
        assertTrue(SeasonalRenderer.supported(gpu));
        assertSame(gpu, SeasonalRenderer.gpu(gpu));
    }

    @Test
    public void unknownRenderersRemainUnsupported()
    {
        assertUnsupported(new UnknownRenderer());
    }

    @Test
    public void retroWrapperResolvesTheActualBuiltInGpu()
    {
        GpuPlugin gpu = new GpuPlugin();
        RetroDrawCallbacks wrapper = new RetroDrawCallbacks(gpu);
        assertTrue(SeasonalRenderer.supported(wrapper));
        assertSame(gpu, SeasonalRenderer.gpu(wrapper));
    }

    @Test
    public void nestedKnownWrappersResolveWithinTheDepthLimit()
    {
        GpuPlugin gpu = new GpuPlugin();
        DrawCallbacks wrapper = gpu;
        for (int depth = 1; depth <= 8; depth++)
        {
            wrapper = new RetroDrawCallbacks(wrapper);
            assertTrue("Known wrapper depth " + depth, SeasonalRenderer.supported(wrapper));
            assertSame(gpu, SeasonalRenderer.gpu(wrapper));
        }
    }

    @Test
    public void missingOrUnknownDelegatesAreNotMistakenForSoftwareOrGpu()
    {
        assertUnsupported(new RetroDrawCallbacks(null));
        assertUnsupported(new RetroDrawCallbacks(new UnknownRenderer()));
        assertUnsupported(new RetroDrawCallbacks(new RetroDrawCallbacks(null)));
    }

    @Test(timeout = 2000)
    public void selfAndMutualCyclesFailClosed()
    {
        RetroDrawCallbacks first = new RetroDrawCallbacks(null);
        first.setDelegate(first);
        assertUnsupported(first);

        RetroDrawCallbacks second = new RetroDrawCallbacks(first);
        first.setDelegate(second);
        assertUnsupported(first);
        assertUnsupported(second);
    }

    @Test
    public void wrapperBeyondTheDepthLimitFailsClosed()
    {
        DrawCallbacks wrapper = new GpuPlugin();
        for (int depth = 0; depth < 9; depth++) { wrapper = new RetroDrawCallbacks(wrapper); }
        assertUnsupported(wrapper);
    }

    @Test
    public void getterFailureDoesNotEscapeIntoTheClientTick()
    {
        RetroDrawCallbacks wrapper = new RetroDrawCallbacks(new GpuPlugin());
        wrapper.failOnGetDelegate = true;
        assertUnsupported(wrapper);
    }

    @Test
    public void unrelatedWrappersWithTheSameGetterAreNeverInvoked()
    {
        UnknownWrapper wrapper = new UnknownWrapper(new GpuPlugin());
        assertUnsupported(wrapper);
        assertEquals("Only the known public Retro contract may be inspected", 0, wrapper.getterCalls);
    }

    @Test
    public void unrecognizedSubclassDoesNotInheritWrapperApproval()
    {
        assertUnsupported(new RetroSubclass(new GpuPlugin()));
    }

    @Test
    public void aChangedDelegateIsReevaluatedInsteadOfRetainingStaleApproval()
    {
        GpuPlugin first = new GpuPlugin();
        GpuPlugin second = new GpuPlugin();
        RetroDrawCallbacks wrapper = new RetroDrawCallbacks(first);
        assertSame(first, SeasonalRenderer.gpu(wrapper));
        wrapper.setDelegate(new UnknownRenderer());
        assertUnsupported(wrapper);
        wrapper.setDelegate(second);
        assertTrue(SeasonalRenderer.supported(wrapper));
        assertSame(second, SeasonalRenderer.gpu(wrapper));
    }

    private static void assertUnsupported(DrawCallbacks callbacks)
    {
        assertFalse(SeasonalRenderer.supported(callbacks));
        assertNull(SeasonalRenderer.gpu(callbacks));
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
