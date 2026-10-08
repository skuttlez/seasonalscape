package com.seasonalscape;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.BiFunction;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.PostClientTick;
import net.runelite.client.eventbus.EventBus;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SeasonalSceneSyncTest
{
    @Test
    public void postTickRefreshPrecedesDefaultPriorityUploadAndDoesNotRepeatBeforeRender()
    {
        try (Fixture f = new Fixture())
        {
            SeasonalSceneSync.stop(f.owner);
            int[] observedAtUpload = {-1};
            // Register the simulated GPU first, proving priority rather than
            // registration order places seasonal changes before its upload.
            EventBus.Subscriber gpu = f.bus.register(PostClientTick.class,
                event -> observedAtUpload[0] = f.refreshes, 0);
            SeasonalSceneSync.start(f.owner, f.client, () -> f.refreshes++, f.bus);
            f.state = GameState.LOGGED_IN;

            f.bus.post(new PostClientTick());
            assertEquals("GPU upload observes the already refreshed scene", 1, observedAtUpload[0]);
            f.frame();
            assertEquals("BeforeRender reuses the completed post-tick refresh", 1, f.refreshes);

            SeasonalSceneSync.requestRefresh(f.owner);
            f.bus.post(new PostClientTick());
            assertEquals(2, observedAtUpload[0]);
            f.frame();
            assertEquals(2, f.refreshes);
            f.bus.unregister(gpu);
        }
    }

    @Test
    public void loadingRefreshesOnTheFirstLoggedInFrameWithoutWaitingForAGameTick()
    {
        try (Fixture f = new Fixture())
        {
            f.frame();
            SeasonalSceneSync.requestRefresh(f.owner);
            f.changeState(GameState.LOADING);
            f.frame();
            assertEquals("Loading retains pending work", 0, f.refreshes);

            f.changeState(GameState.LOGGED_IN);
            assertEquals("State events do not read or modify the scene", 0, f.refreshes);
            f.frame();
            assertEquals(1, f.refreshes);
            f.frame();
            f.frame();
            assertEquals("Stable frames do not rescan", 1, f.refreshes);
        }
    }

    @Test
    public void identityAndBothBaseCoordinatesDetectSceneChanges()
    {
        try (Fixture f = new Fixture())
        {
            f.state = GameState.LOGGED_IN;
            f.frame();
            f.scene = f.newScene();
            f.frame();
            assertEquals("A replacement Scene triggers refresh", 2, f.refreshes);
            f.baseX += 8;
            f.frame();
            assertEquals("A reused Scene with a changed X base triggers refresh", 3, f.refreshes);
            f.baseY += 8;
            f.frame();
            assertEquals("A reused Scene with a changed Y base triggers refresh", 4, f.refreshes);
            f.frame();
            assertEquals(4, f.refreshes);
        }
    }

    @Test
    public void requestsCoalesceAndWaitForClientThreadAndCompleteScene()
    {
        try (Fixture f = new Fixture())
        {
            f.state = GameState.LOGGED_IN;
            f.clientThread = false;
            f.frame();
            assertEquals(0, f.refreshes);
            f.clientThread = true;
            f.worldAvailable = false;
            f.frame();
            f.worldAvailable = true;
            Scene scene = f.scene;
            f.scene = null;
            f.frame();
            f.scene = scene;
            f.playerAvailable = false;
            f.frame();
            assertEquals("Incomplete scenes preserve pending work", 0, f.refreshes);
            f.playerAvailable = true;
            f.frame();
            assertEquals(1, f.refreshes);

            SeasonalSceneSync.requestRefresh(f.owner);
            SeasonalSceneSync.requestRefresh(f.owner);
            SeasonalSceneSync.requestRefresh(f.owner);
            f.frame();
            f.frame();
            assertEquals("Many spawn events need one refresh", 2, f.refreshes);
        }
    }

    @Test
    public void replacingAndStoppingAnOwnerUnregistersItsSubscribers()
    {
        try (Fixture f = new Fixture())
        {
            assertEquals(1, f.bus.registered.size());
            SeasonalSceneSync.start(f.owner, f.client, () -> f.refreshes += 10, f.bus);
            assertEquals("Replacing an owner leaves one subscriber", 1, f.bus.registered.size());
            f.state = GameState.LOGGED_IN;
            f.frame();
            assertEquals(10, f.refreshes);

            SeasonalSceneSync.stop(f.owner);
            assertEquals(0, f.bus.registered.size());
            SeasonalSceneSync.requestRefresh(f.owner);
            f.changeState(GameState.LOADING);
            f.changeState(GameState.LOGGED_IN);
            f.frame();
            assertEquals("Stopped owners receive no later refresh", 10, f.refreshes);
        }
    }

    private static final class Fixture implements AutoCloseable
    {
        private final Object owner = new Object();
        private final RecordingEventBus bus = new RecordingEventBus();
        private GameState state = GameState.LOADING;
        private boolean clientThread = true;
        private boolean worldAvailable = true;
        private boolean playerAvailable = true;
        private int baseX = 3200;
        private int baseY = 3200;
        private int refreshes;
        private Scene scene = newScene();
        private final Player player = proxy(Player.class, (name, args) -> null);
        private final WorldView world = proxy(WorldView.class,
            (name, args) -> name.equals("getScene") ? scene : null);
        private final Client client = proxy(Client.class, (name, args) -> {
            switch (name)
            {
                case "isClientThread": return clientThread;
                case "getGameState": return state;
                case "getTopLevelWorldView": return worldAvailable ? world : null;
                case "getLocalPlayer": return playerAvailable ? player : null;
                default: return null;
            }
        });

        private Fixture()
        {
            SeasonalSceneSync.start(owner, client, () -> refreshes++, bus);
        }

        private Scene newScene()
        {
            return proxy(Scene.class, (name, args) -> {
                if (name.equals("getBaseX")) { return baseX; }
                if (name.equals("getBaseY")) { return baseY; }
                return null;
            });
        }

        private void changeState(GameState next)
        {
            state = next;
            GameStateChanged event = new GameStateChanged();
            event.setGameState(next);
            bus.post(event);
        }

        private void frame()
        {
            bus.post(new BeforeRender());
        }

        @Override
        public void close()
        {
            SeasonalSceneSync.stop(owner);
        }
    }

    private static final class RecordingEventBus extends EventBus
    {
        private final Set<Object> registered = Collections.newSetFromMap(new IdentityHashMap<>());

        private RecordingEventBus()
        {
            super(error -> { throw new AssertionError(error); });
        }

        @Override
        public synchronized void register(Object subscriber)
        {
            super.register(subscriber);
            registered.add(subscriber);
        }

        @Override
        public synchronized void unregister(Object subscriber)
        {
            super.unregister(subscriber);
            registered.remove(subscriber);
        }
    }

    private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> handler)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (object, method, args) -> {
                if (method.getName().equals("hashCode")) { return System.identityHashCode(object); }
                if (method.getName().equals("equals")) { return object == args[0]; }
                if (method.getName().equals("toString")) { return type.getSimpleName() + " test proxy"; }
                Object result = handler.apply(method.getName(), args);
                if (result != null || !method.getReturnType().isPrimitive()) { return result; }
                if (method.getReturnType() == boolean.class) { return false; }
                if (method.getReturnType() == int.class) { return 0; }
                throw new AssertionError("Unexpected method: " + method);
            }));
    }
}
