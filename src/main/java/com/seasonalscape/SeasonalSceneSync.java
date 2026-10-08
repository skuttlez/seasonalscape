package com.seasonalscape;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.PostClientTick;
import net.runelite.client.RuneLite;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

/** Refreshes a newly loaded scene before rendering, without rescanning stable frames. */
final class SeasonalSceneSync
{
    private static final Map<Object, SeasonalSceneSync> STATES = new WeakHashMap<>();

    private final EventBus eventBus;
    private final Client client;
    private final Runnable refresh;
    private final AtomicBoolean pending = new AtomicBoolean(true);
    private volatile boolean active = true;
    private Scene lastScene;
    private int baseX;
    private int baseY;

    SeasonalSceneSync(EventBus eventBus, Client client, Runnable refresh)
    {
        this.eventBus = Objects.requireNonNull(eventBus);
        this.client = Objects.requireNonNull(client);
        this.refresh = Objects.requireNonNull(refresh);
        eventBus.register(this);
    }

    static void start(Object owner, Client client, Runnable refresh)
    {
        start(owner, client, refresh, RuneLite.getInjector().getInstance(EventBus.class));
    }

    /** Event-bus injection keeps lifecycle tests independent of a RuneLite process. */
    static void start(Object owner, Client client, Runnable refresh, EventBus eventBus)
    {
        Objects.requireNonNull(owner);
        synchronized (STATES)
        {
            SeasonalSceneSync previous = STATES.remove(owner);
            if (previous != null) { previous.close(); }
            STATES.put(owner, new SeasonalSceneSync(eventBus, client, refresh));
        }
    }

    static void requestRefresh(Object owner)
    {
        synchronized (STATES)
        {
            SeasonalSceneSync state = STATES.get(owner);
            if (state != null) { state.pending.set(true); }
        }
    }

    static void stop(Object owner)
    {
        synchronized (STATES)
        {
            SeasonalSceneSync state = STATES.remove(owner);
            if (state != null) { state.close(); }
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        // Loading events only request work. Scene reads and recoloring stay on
        // the client thread after the complete scene becomes available.
        if (active) { pending.set(true); }
    }

    @Subscribe
    public void onBeforeRender(BeforeRender event)
    {
        if (!active || !client.isClientThread() || client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }
        WorldView world = client.getTopLevelWorldView();
        Scene scene = world == null ? null : world.getScene();
        if (scene == null || client.getLocalPlayer() == null) { return; }
        int currentBaseX = scene.getBaseX();
        int currentBaseY = scene.getBaseY();
        boolean changed = scene != lastScene || currentBaseX != baseX || currentBaseY != baseY;
        if (!pending.getAndSet(false) && !changed) { return; }

        try
        {
            refresh.run();
            lastScene = scene;
            baseX = currentBaseX;
            baseY = currentBaseY;
        }
        catch (RuntimeException | Error error)
        {
            pending.set(true);
            throw error;
        }
    }

    @Subscribe(priority = 100)
    public void onPostClientTick(PostClientTick event)
    {
        // GPU rebuilds invalidated zones at default priority on this event.
        // Recolor first so it uploads the seasonal version before drawing.
        onBeforeRender(null);
    }

    private void close()
    {
        active = false;
        eventBus.unregister(this);
        lastScene = null;
    }
}
