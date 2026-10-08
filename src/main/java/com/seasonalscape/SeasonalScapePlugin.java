package com.seasonalscape;

import com.google.inject.Provides;
import java.time.Clock;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.PostClientTick;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.gpu.GpuPlugin;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(name = "SeasonalScape", description = "Seasonal outdoor terrain, trees and ground cover",
    tags = {"season", "spring", "summer", "autumn", "winter", "snow", "leaves", "flowers"}, enabledByDefault = false)
public class SeasonalScapePlugin extends Plugin
{
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private SeasonalScapeConfig config;
    @Inject private OverlayManager overlayManager;
    @Inject private SeasonalStatusOverlay overlay;

    private SeasonalSceneRecolorer recolorer;
    private GroundCover groundCover;
    private Scene lastScene;
    private DrawCallbacks lastRenderer;
    private volatile Season activeSeason;
    private volatile String status = "Log in to preview";
    private volatile boolean running;
    private volatile boolean dirty;

    @Provides
    SeasonalScapeConfig provideConfig(ConfigManager manager)
    {
        return manager.getConfig(SeasonalScapeConfig.class);
    }

    @Override
    protected void startUp()
    {
        running = true;
        dirty = true;
        recolorer = new SeasonalSceneRecolorer(client);
        groundCover = new GroundCover(client);
        overlayManager.add(overlay);
        SeasonalWinterAudio.start(this, client, getInjector().getInstance(ConfigManager.class));
        SeasonalSceneSync.start(this, client, () -> {
            if (running) { dirty = true; update(); }
        });
        clientThread.invoke(this::update);
    }

    @Override
    protected void shutDown()
    {
        running = false;
        SeasonalWinterAudio.stop(this);
        SeasonalSceneSync.stop(this);
        overlayManager.remove(overlay);
        // Capture this session's state so a quick re-enable cannot restore a newer session.
        GroundCover oldCover = groundCover;
        SeasonalSceneRecolorer oldRecolorer = recolorer;
        clientThread.invoke(() -> {
            if (oldCover != null) { oldCover.clear(); }
            if (oldRecolorer != null) { SeasonalAir.clear(oldRecolorer); }
            if (oldRecolorer != null) { oldRecolorer.restore(); }
        });
        lastScene = null;
        lastRenderer = null;
        activeSeason = null;
        status = "Disabled";
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        update();
        if (recolorer != null)
        {
            WinterFoliageTextures.update(recolorer, client,
                running && activeSeason == Season.WINTER && config.foliage());
        }
        if (running && activeSeason == Season.WINTER && recolorer != null)
        {
            WorldView world = client.getTopLevelWorldView();
            WinterSnowfall.update(recolorer, client, world == null ? null : world.getScene());
        }
        else if (recolorer != null)
        {
            WinterSnowfall.clear(recolorer);
        }
    }

    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned event)
    {
        dirty = true;
        SeasonalSceneSync.requestRefresh(this);
    }

    @Subscribe(priority = 100)
    public void onPostClientTick(PostClientTick event)
    {
        // Spread surface work across client ticks, before GPU's zone uploads.
        if (running && recolorer != null) { WinterSurfaceSnow.tick(recolorer); }
    }

    @Subscribe
    public void onGameObjectDespawned(GameObjectDespawned event)
    {
        dirty = true;
        SeasonalSceneSync.requestRefresh(this);
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (SeasonalScapeConfig.GROUP.equals(event.getGroup())
            && "winterStructureSnow".equals(event.getKey()))
        {
            clientThread.invoke(() -> {
                if (!running || recolorer == null) { return; }
                // Remove existing coatings even during a map load. The normal
                // update recreates them only when enabled and the scene is ready.
                if (!config.winterStructureSnow()) { WinterSurfaceSnow.restore(recolorer); }
                else { update(); }
            });
            return;
        }
        if (SeasonalScapeConfig.GROUP.equals(event.getGroup())
            && ("winterAudio".equals(event.getKey()) || "winterAudioVolume".equals(event.getKey())))
        {
            // Sound controls do not require rebuilding any scene geometry.
            return;
        }
        if (SeasonalScapeConfig.GROUP.equals(event.getGroup())
            || ("gpu".equals(event.getGroup()) && "brightTextures".equals(event.getKey())))
        {
            dirty = true;
            clientThread.invoke(() -> { if (running) { recolorer.restore(); update(); } });
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        dirty = true;
        SeasonalWinterAudio.update(this, activeSeason, running);
        SeasonalSceneSync.requestRefresh(this);
        if (event.getGameState() != GameState.LOGGED_IN && recolorer != null)
        {
            SeasonalAir.clear(recolorer);
        }
        if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
        {
            groundCover.clear();
            recolorer.restore();
            lastScene = null;
            status = "Log in to preview";
        }
        else if (event.getGameState() == GameState.LOGGED_IN)
        {
            // Refresh a completed scene immediately instead of exposing its
            // original colors until the next server game tick.
            update();
        }
    }

    private void update()
    {
        if (!running || client.getGameState() != GameState.LOGGED_IN) { return; }
        WorldView view = client.getTopLevelWorldView();
        if (view == null || view.getScene() == null || client.getLocalPlayer() == null) { return; }
        Scene scene = view.getScene();
        Season season = SeasonResolver.resolve(config.season(), config.hemisphere(), Clock.systemDefaultZone(), config.timeZone());
        DrawCallbacks renderer = client.getDrawCallbacks();
        if (renderer != null && !(renderer instanceof GpuPlugin))
        {
            groundCover.clear();
            SeasonalAir.clear(recolorer);
            recolorer.restore();
            lastScene = null;
            activeSeason = season;
            SeasonalWinterAudio.update(this, season, running);
            status = "Use default graphics or GPU";
            return;
        }
        if (dirty || scene != lastScene || season != activeSeason || renderer != lastRenderer)
        {
            // Packed texture tints are valid only in GPU's bright-texture mode.
            // Switching renderer must restore them before software draws again.
            if (renderer != lastRenderer) { recolorer.restore(); }
            recolorer.apply(scene, season, config.terrain(), config.foliage());
            WinterSurfaceSnow.markDirty(recolorer);
            groundCover.invalidate();
            if (scene != lastScene || season != activeSeason) { groundCover.clear(); }
            lastScene = scene;
            lastRenderer = renderer;
            activeSeason = season;
            dirty = false;
        }
        // Scene/config restoration and material reactivation belong to the
        // same client-thread update, without an intervening original frame.
        WinterFoliageTextures.update(recolorer, client, season == Season.WINTER && config.foliage());
        SeasonalWinterAudio.update(this, season, running);
        WinterSurfaceSnow.update(recolorer, client, scene,
            season == Season.WINTER && config.terrain() && config.winterStructureSnow());
        groundCover.update(scene, recolorer, season, config.groundCover(), config.density());
        SeasonalAir.update(recolorer, client, scene, recolorer, season, config.seasonalAir(), config.density());
        int x = client.getLocalPlayer().getWorldLocation().getX();
        int y = client.getLocalPlayer().getWorldLocation().getY();
        boolean inArea = SeasonalSceneRecolorer.supports(scene) && view.getPlane() == 0
            && SeasonalWorldArea.contains(x, y);
        status = inArea ? "Seasonal world active" : "Outdoor main-overworld areas only";
    }

    Season getActiveSeason() { return activeSeason; }
    String getStatus() { return status; }
}
