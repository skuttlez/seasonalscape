package com.seasonalscape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.plugins.gpu.GpuPlugin;

/** Sparse, depth-tested spring petals and summer butterflies, on the client thread. */
final class SeasonalAir
{
    private static final Map<Object, State> STATES = new WeakHashMap<>();
    private static final int RADIUS = 7 * 128;
    private static final int FRAMES = 8;

    private SeasonalAir() {}

    static int particleCount(Season season, boolean enabled, int density)
    {
        if (!enabled || density <= 0) { return 0; }
        int amount = Math.min(100, density);
        if (season == Season.SPRING) { return Math.min(40, 6 + amount / 2); }
        if (season == Season.SUMMER) { return Math.min(12, 2 + amount / 6); }
        return 0;
    }

    static void update(Object owner, Client client, Scene scene, SeasonalSceneRecolorer recolorer,
        Season season, boolean enabled, int density)
    {
        int count = particleCount(season, enabled, density);
        if (count == 0) { clear(owner); return; }
        WorldView world = client.getTopLevelWorldView();
        Player player = client.getLocalPlayer();
        LocalPoint point = player == null ? null : player.getLocalLocation();
        DrawCallbacks renderer = client.getDrawCallbacks();
        if (client.getGameState() != GameState.LOGGED_IN || world == null || scene == null
            || recolorer == null || (renderer != null && !(renderer instanceof GpuPlugin))
            || !world.isTopLevel() || world.isInstance() || scene.isInstance()
            || world.getPlane() != 0 || world.getScene() != scene || point == null
            || point.getWorldView() != world.getId()
            || !SeasonalWorldArea.contains(scene.getBaseX() + point.getSceneX(), scene.getBaseY() + point.getSceneY())
            || !WinterSnowfall.clearSky(world.getTileSettings(), point.getSceneX(), point.getSceneY()))
        {
            clear(owner);
            return;
        }
        State state = STATES.get(owner);
        if (state != null && (state.scene != scene || state.world != world || state.season != season
            || state.baseX != scene.getBaseX() || state.baseY != scene.getBaseY()
            || state.particles.size() != count))
        {
            clear(owner);
            state = null;
        }
        if (state == null)
        {
            Model[][] models = createModels(client, season);
            if (models == null) { return; }
            state = new State(client, world, scene, recolorer, season, models, count);
            STATES.put(owner, state);
        }
        state.center = point;
        state.refresh();
        for (Particle particle : state.particles)
        {
            if (!particle.isActive()) { particle.respawn(true); }
        }
    }

    static void clear(Object owner)
    {
        State state = STATES.remove(owner);
        if (state == null) { return; }
        state.cleared = true;
        for (Particle particle : state.particles) { particle.setActive(false); }
    }

    static int getCount(Object owner)
    {
        State state = STATES.get(owner);
        if (state == null) { return 0; }
        int count = 0;
        for (Particle particle : state.particles) { if (particle.isActive()) { count++; } }
        return count;
    }

    private static Model[][] createModels(Client client, Season season)
    {
        ModelData source = client.loadModelData(27835);
        if (source == null || source.getVerticesCount() < 3 || source.getVerticesCount() > 8192
            || source.getFaceCount() == 0 || source.getFaceCount() > 8192) { return null; }
        Model[][] models = new Model[3][season == Season.SUMMER ? FRAMES : 1];
        for (int variant = 0; variant < models.length; variant++)
        {
            for (int frame = 0; frame < models[variant].length; frame++)
            {
                ModelData data = source.shallowCopy().cloneVertices().cloneColors();
                if (data.getFaceTextures() != null)
                {
                    data.cloneTextures();
                    Arrays.fill(data.getFaceTextures(), (short) -1);
                }
                data.cloneTransparencies(true);
                Arrays.fill(data.getFaceTransparencies(), (byte) (season == Season.SPRING ? 18 : 0));
                reshape(source, data, season, frame);
                int[] spring = {60 << 10 | 2 << 7 | 110, 58 << 10 | 3 << 7 | 100, 115};
                int[] summer = {7 << 10 | 6 << 7 | 76, 40 << 10 | 5 << 7 | 84, 13 << 10 | 3 << 7 | 108};
                Arrays.fill(data.getFaceColors(), (short) (season == Season.SPRING ? spring[variant] : summer[variant]));
                data.translate(0, 0, 0);
                models[variant][frame] = data.light(90, ModelData.DEFAULT_CONTRAST,
                    ModelData.DEFAULT_X, ModelData.DEFAULT_Y, ModelData.DEFAULT_Z);
            }
        }
        return models;
    }

    /** Keep one cache component and fold a small pair of wings; never edit cached topology. */
    static void reshape(ModelData source, ModelData target, Season season, int frame)
    {
        WinterSnowfall.reshape(source, target, 1);
        float[][] values = {target.getVerticesX(), target.getVerticesY(), target.getVerticesZ()};
        float[] spans = new float[3];
        for (int axis = 0; axis < 3; axis++)
        {
            for (float value : values[axis]) { spans[axis] = Math.max(spans[axis], Math.abs(value)); }
        }
        Integer[] axes = {0, 1, 2};
        Arrays.sort(axes, (a, b) -> Float.compare(spans[b], spans[a]));
        float[] u = values[axes[0]].clone(), v = values[axes[1]].clone();
        float fold = (float) (0.15 + 0.85 * (0.5 + 0.5 * Math.cos(frame * Math.PI * 2 / FRAMES)));
        for (int i = 0; i < target.getVerticesCount(); i++)
        {
            float across = u[i] / Math.max(0.001f, spans[axes[0]]);
            float along = v[i] / Math.max(0.001f, spans[axes[1]]);
            values[0][i] = across * (season == Season.SUMMER ? 9 * fold : 3.5f);
            values[1][i] = season == Season.SUMMER ? -Math.abs(across) * 9 * (1 - fold) : along * 1.5f;
            values[2][i] = along * (season == Season.SUMMER ? 6 : 5);
        }
    }

    private static final class State
    {
        final Client client;
        final WorldView world;
        final Scene scene;
        final SeasonalSceneRecolorer recolorer;
        final Season season;
        final int baseX, baseY;
        final Random random;
        final List<Particle> particles = new ArrayList<>();
        Tile[][][] tiles;
        byte[][][] flags;
        int[][] heights;
        LocalPoint center;
        boolean cleared;

        State(Client client, WorldView world, Scene scene, SeasonalSceneRecolorer recolorer,
            Season season, Model[][] models, int count)
        {
            this.client = client; this.world = world; this.scene = scene;
            this.recolorer = recolorer; this.season = season;
            baseX = scene.getBaseX(); baseY = scene.getBaseY();
            random = new Random(((long) baseX << 32) ^ baseY ^ season.ordinal());
            for (int i = 0; i < count; i++) { particles.add(new Particle(this, models[i % models.length])); }
        }

        void refresh()
        {
            tiles = scene.getTiles();
            flags = world.getTileSettings();
            int[][][] all = world.getTileHeights();
            heights = all == null || all.length == 0 ? null : all[0];
        }

        boolean meadow(int localX, int localY)
        {
            int x = localX >> 7, y = localY >> 7;
            if (x < 1 || y < 1 || tiles == null || tiles.length == 0 || tiles[0] == null
                || x >= tiles[0].length - 1 || tiles[0][x] == null || y >= tiles[0][x].length - 1)
            { return false; }
            Tile tile = tiles[0][x][y];
            return tile != null && tile.getRenderLevel() == 0 && tile.getBridge() == null
                && tile.getWallObject() == null && recolorer.isSeasonalGround(tile)
                && WinterSnowfall.clearSky(flags, x, y)
                && Float.isFinite(GroundCoverPlacement.height(heights, localX, localY));
        }
    }

    private static final class Particle extends RuneLiteObject
    {
        final State state;
        final Model[] frames;
        final float phase;
        float x, y, anchorX, anchorY, altitude, age;

        Particle(State state, Model[] frames)
        {
            super(state.client);
            this.state = state; this.frames = frames;
            phase = state.random.nextFloat() * 6.2831853f;
            setModel(frames[0]);
            setWorldView(state.world.getId());
            setLevel(0);
        }

        void respawn(boolean spread)
        {
            if (state.cleared || state.center == null) { return; }
            for (int attempt = 0; attempt < 16; attempt++)
            {
                double angle = state.random.nextDouble() * Math.PI * 2;
                double radius = Math.sqrt(state.random.nextDouble()) * RADIUS;
                x = state.center.getX() + (float) (Math.cos(angle) * radius);
                y = state.center.getY() + (float) (Math.sin(angle) * radius);
                if (!state.meadow((int) x, (int) y)) { continue; }
                anchorX = x; anchorY = y;
                altitude = state.season == Season.SPRING
                    ? (spread ? 40 + state.random.nextFloat() * 380 : 420) : 35;
                place();
                if (!isActive()) { setActive(true); }
                return;
            }
            setActive(false);
        }

        @Override
        public void tick(int ticksSinceLastFrame)
        {
            if (state.cleared || state.client.getGameState() != GameState.LOGGED_IN
                || state.world.getScene() != state.scene || state.world.getPlane() != 0)
            { setActive(false); return; }
            Player player = state.client.getLocalPlayer();
            LocalPoint point = player == null ? null : player.getLocalLocation();
            if (point == null || point.getWorldView() != getWorldView()
                || !WinterSnowfall.clearSky(state.flags, point.getSceneX(), point.getSceneY()))
            { setActive(false); return; }
            state.center = point;
            int elapsed = Math.max(0, Math.min(10, ticksSinceLastFrame));
            if (elapsed == 0) { return; }
            age += elapsed;
            if (state.season == Season.SPRING)
            {
                altitude -= elapsed * 0.7f;
                x += elapsed * (0.3f + 0.55f * (float) Math.sin(age * 0.03f + phase));
                y += elapsed * 0.35f * (float) Math.cos(age * 0.025f + phase);
                setOrientation(((int) (age * 3 + phase * 300)) & 2047);
            }
            else
            {
                float previousX = x, previousY = y;
                x = anchorX + 46 * (float) Math.sin(age * 0.016f + phase);
                y = anchorY + 32 * (float) Math.sin(age * 0.023f + phase);
                altitude = 34 + 13 * (float) Math.sin(age * 0.05f + phase);
                setOrientation(((int) (Math.atan2(x - previousX, y - previousY) * 1024 / Math.PI)) & 2047);
                setModel(frames[((int) (age / 2 + phase * 4)) % frames.length]);
            }
            float dx = x - point.getX(), dy = y - point.getY();
            if (altitude < 6 || dx * dx + dy * dy > (RADIUS + 128f) * (RADIUS + 128f)
                || !state.meadow((int) x, (int) y))
            { respawn(false); return; }
            place();
        }

        private void place()
        {
            setX((int) x); setY((int) y);
            setZ(Math.round(GroundCoverPlacement.height(state.heights, (int) x, (int) y) - altitude));
        }
    }
}
