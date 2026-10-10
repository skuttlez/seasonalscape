package com.retronpcswapper;

import net.runelite.api.Scene;
import net.runelite.api.hooks.DrawCallbacks;

/**
 * Independent test double for Retro NPC Swapper's public wrapper contract.
 * This is not the plugin implementation and must never ship in either plugin JAR.
 */
public class RetroDrawCallbacks implements DrawCallbacks
{
    private DrawCallbacks delegate;
    public boolean failOnGetDelegate;
    public int invalidatedZones;

    public RetroDrawCallbacks(DrawCallbacks delegate)
    {
        this.delegate = delegate;
    }

    public DrawCallbacks getDelegate()
    {
        if (failOnGetDelegate) { throw new IllegalStateException("Test getter failure"); }
        return delegate;
    }

    public void setDelegate(DrawCallbacks delegate)
    {
        this.delegate = delegate;
    }

    @Override
    public void invalidateZone(Scene scene, int x, int y)
    {
        invalidatedZones++;
        if (delegate != null) { delegate.invalidateZone(scene, x, y); }
    }

    @Override
    public void draw(int overlayColor)
    {
        if (delegate != null) { delegate.draw(overlayColor); }
    }

    @Override
    public void swapScene(Scene scene)
    {
        if (delegate != null) { delegate.swapScene(scene); }
    }
}
