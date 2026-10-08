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

/** Small depth-tested flakes. All state and animation run on the client thread. */
final class WinterSnowfall
{
    private static final Map<Object, State> STATES = new WeakHashMap<>();
    private static final int PARTICLE_COUNT = 384;
    private static final int LOWER_PARTICLES = 256;
    private static final int RADIUS = 10 * 128;
    private static final int MODEL_ID = 27835;
    private static final float LOWER_CEILING = 1500;
    private static final float UPPER_FLOOR = 1100;
    private static final float MIN_CEILING = 3200, MAX_CEILING = 6400;

    private WinterSnowfall() {}

    /** The caller enables this only during winter. */
    static void update(Object owner, Client client, Scene scene)
    {
        WorldView world = client.getTopLevelWorldView();
        Player player = client.getLocalPlayer();
        LocalPoint point = player == null ? null : player.getLocalLocation();
        DrawCallbacks renderer = client.getDrawCallbacks();
        if (client.getGameState() != GameState.LOGGED_IN || world == null || scene == null
            || (renderer != null && !(renderer instanceof GpuPlugin))
            || !world.isTopLevel() || world.isInstance() || scene.isInstance()
            || world.getPlane() != 0 || world.getScene() != scene || point == null
            || point.getWorldView() != world.getId()
            || !SeasonalWorldArea.contains(scene.getBaseX() + point.getSceneX(), scene.getBaseY() + point.getSceneY())
            || !clearSky(world.getTileSettings(), point.getSceneX(), point.getSceneY()))
        {
            clear(owner);
            return;
        }
        State state = STATES.get(owner);
        if (state != null && (state.scene != scene || state.baseX != scene.getBaseX()
            || state.baseY != scene.getBaseY() || state.world != world))
        {
            clear(owner);
            state = null;
        }
        if (state == null)
        {
            Model[] models = createModels(client);
            if (models == null) { return; }
            state = new State(client, world, scene, models);
            STATES.put(owner, state);
        }
        state.center = point;
        state.refreshTiles();
        state.refreshCeiling();
        // A failed outdoor placement remains inactive until the next game tick.
        for (Flake flake : state.flakes)
        {
            if (!flake.isActive()) { flake.respawn(true); }
        }
    }

    static void clear(Object owner)
    {
        State state = STATES.remove(owner);
        if (state == null) { return; }
        state.cleared = true;
        for (Flake flake : state.flakes) { flake.setActive(false); }
    }

    static int getCount(Object owner)
    {
        State state = STATES.get(owner);
        if (state == null) { return 0; }
        int count = 0;
        for (Flake flake : state.flakes) { if (flake.isActive()) { count++; } }
        return count;
    }

    private static Model[] createModels(Client client)
    {
        ModelData source = client.loadModelData(MODEL_ID);
        if (source == null || source.getVerticesCount() < 3 || source.getVerticesCount() > 8192
            || source.getFaceCount() == 0 || source.getFaceCount() > 8192) { return null; }
        Model[] models = new Model[6];
        for (int variant = 0; variant < models.length; variant++)
        {
            ModelData data = source.shallowCopy().cloneVertices().cloneColors();
            if (data.getFaceTextures() != null)
            {
                data.cloneTextures();
                Arrays.fill(data.getFaceTextures(), (short) -1);
            }
            data.cloneTransparencies(true);
            Arrays.fill(data.getFaceTransparencies(), (byte) (24 + variant % 3 * 8));
            Arrays.fill(data.getFaceColors(), (short) 120);
            // Upper flakes are slightly larger so the raised layer remains
            // legible at far zoom without adding more animated objects.
            reshape(source, data, (variant < 3 ? 4.4f : 7.0f) + variant % 3 * 0.8f);
            data.translate(0, 0, 0);
            models[variant] = data.light(100, ModelData.DEFAULT_CONTRAST,
                ModelData.DEFAULT_X, ModelData.DEFAULT_Y, ModelData.DEFAULT_Z);
        }
        return models;
    }

    static float ceilingForCamera(float groundHeight, double cameraZ)
    {
        if (!Float.isFinite(groundHeight) || !Double.isFinite(cameraZ)) { return MIN_CEILING; }
        // Scene Z becomes more negative above the ground. Follow camera height,
        // with headroom for the upper view, while keeping the volume bounded.
        return (float) Math.max(MIN_CEILING, Math.min(MAX_CEILING, groundHeight - cameraZ + 1024));
    }

    /** Retain one cache flake; collapse other components without modifying face indices. */
    static void reshape(ModelData source, ModelData target, float radius)
    {
        int count = source.getVerticesCount();
        int[] roots = new int[count];
        for (int i = 0; i < count; i++) { roots[i] = i; }
        int[] a = source.getFaceIndices1(), b = source.getFaceIndices2(), c = source.getFaceIndices3();
        for (int face = 0; face < source.getFaceCount(); face++)
        {
            union(roots, a[face], b[face]);
            union(roots, a[face], c[face]);
        }
        int selected = root(roots, a[0]);
        float[] sx = source.getVerticesX(), sy = source.getVerticesY(), sz = source.getVerticesZ();
        float[] x = target.getVerticesX(), y = target.getVerticesY(), z = target.getVerticesZ();
        float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
        float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int i = 0; i < count; i++)
        {
            if (root(roots, i) != selected) { continue; }
            minX = Math.min(minX, sx[i]); maxX = Math.max(maxX, sx[i]);
            minY = Math.min(minY, sy[i]); maxY = Math.max(maxY, sy[i]);
            minZ = Math.min(minZ, sz[i]); maxZ = Math.max(maxZ, sz[i]);
        }
        float scale = radius * 2 / Math.max(1, Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ)));
        for (int i = 0; i < count; i++)
        {
            boolean visible = root(roots, i) == selected;
            x[i] = visible ? (sx[i] - (minX + maxX) / 2) * scale : 0;
            y[i] = visible ? (sy[i] - (minY + maxY) / 2) * scale : 0;
            z[i] = visible ? (sz[i] - (minZ + maxZ) / 2) * scale : 0;
        }
    }

    private static int root(int[] roots, int value)
    {
        while (roots[value] != value)
        {
            roots[value] = roots[roots[value]];
            value = roots[value];
        }
        return value;
    }

    private static void union(int[] roots, int a, int b) { roots[root(roots, b)] = root(roots, a); }

    private static final class State
    {
        final Client client;
        final WorldView world;
        final Scene scene;
        final int baseX, baseY;
        final Random random;
        final List<Flake> flakes = new ArrayList<>();
        Tile[][][] tiles;
        byte[][][] flags;
        int[][] heights;
        LocalPoint center;
        float ceiling = MIN_CEILING;
        int lastVolumeCycle = Integer.MIN_VALUE;
        boolean cleared;

        State(Client client, WorldView world, Scene scene, Model[] models)
        {
            this.client = client;
            this.world = world;
            this.scene = scene;
            baseX = scene.getBaseX();
            baseY = scene.getBaseY();
            random = new Random(((long) baseX << 32) ^ baseY ^ 0x534e4f57L);
            refreshTiles();
            for (int i = 0; i < PARTICLE_COUNT; i++)
            {
                flakes.add(new Flake(this, models[i % 3 + (i >= LOWER_PARTICLES ? 3 : 0)], i));
            }
        }

        void refreshTiles()
        {
            // Some scene accessors translate extended arrays. Read once per game
            // tick rather than once per particle on every rendered frame.
            tiles = scene.getTiles();
            flags = world.getTileSettings();
            int[][][] allHeights = world.getTileHeights();
            heights = allHeights == null || allHeights.length == 0 ? null : allHeights[0];
        }

        void refreshCeiling()
        {
            int cycle = client.getGameCycle();
            if (cycle == lastVolumeCycle || center == null) { return; }
            double cameraZ = client.isGpu() ? client.getCameraFpZ() : client.getCameraZ();
            float target = ceilingForCamera(GroundCoverPlacement.height(heights, center.getX(), center.getY()), cameraZ);
            // Follow zoom changes within about a second, without stretching the
            // whole upper layer abruptly on one frame. Compute once per cycle.
            ceiling = lastVolumeCycle == Integer.MIN_VALUE ? target
                : ceiling + Math.max(-80, Math.min(80, target - ceiling));
            lastVolumeCycle = cycle;
        }

        boolean outdoors(int localX, int localY)
        {
            int x = localX >> 7, y = localY >> 7;
            if (x < 1 || y < 1 || !SeasonalWorldArea.contains(baseX + x, baseY + y)) { return false; }
            if (tiles == null || tiles.length == 0 || tiles[0] == null
                || x >= tiles[0].length - 1 || tiles[0][x] == null
                || y >= tiles[0][x].length - 1) { return false; }
            Tile tile = tiles[0][x][y];
            if (tile == null || tile.getRenderLevel() != 0 || tile.getBridge() != null) { return false; }
            return clearSky(flags, x, y)
                && Float.isFinite(GroundCoverPlacement.height(heights, localX, localY));
        }
    }

    /** Ordinary WorldView coordinates, unlike Scene's extended tile settings. */
    static boolean clearSky(byte[][][] flags, int x, int y)
    {
        if (flags == null || flags.length < 2 || x < 0 || y < 0) { return false; }
        for (int plane = 1; plane < flags.length; plane++)
        {
            if (flags[plane] == null || x >= flags[plane].length || flags[plane][x] == null
                || y >= flags[plane][x].length) { return false; }
            int value = flags[plane][x][y];
            if ((value & 4) != 0 || (plane == 1 && (value & 2) != 0)) { return false; }
        }
        return true;
    }

    private static final class Flake extends RuneLiteObject
    {
        final State state;
        final float speed;
        final float phase;
        final boolean upperLayer;
        final int layerIndex, layerCount;
        float x, y, altitude, age;
        float altitudeCeiling;

        Flake(State state, Model model, int index)
        {
            super(state.client);
            this.state = state;
            upperLayer = index >= LOWER_PARTICLES;
            layerIndex = upperLayer ? index - LOWER_PARTICLES : index;
            layerCount = upperLayer ? PARTICLE_COUNT - LOWER_PARTICLES : LOWER_PARTICLES;
            speed = 1.5f + state.random.nextFloat() * 1.2f;
            phase = state.random.nextFloat() * 6.2831853f;
            setModel(model);
            setWorldView(state.world.getId());
            setLevel(0);
            setOrientation(state.random.nextInt(2048));
        }

        boolean respawn(boolean spreadVertically)
        {
            if (state.cleared || state.center == null) { return false; }
            for (int attempt = 0; attempt < 24; attempt++)
            {
                double angle = state.random.nextDouble() * Math.PI * 2;
                double radius = Math.sqrt(state.random.nextDouble()) * RADIUS;
                x = state.center.getX() + (float) (Math.cos(angle) * radius);
                y = state.center.getY() + (float) (Math.sin(angle) * radius);
                if (!state.outdoors((int) x, (int) y)) { continue; }
                // Keep two thirds of the flakes in the original dense lower
                // volume, with a separate upper layer for distant camera views.
                // Stratified initial heights populate both layers immediately.
                float floor = upperLayer ? UPPER_FLOOR : 30;
                altitudeCeiling = upperLayer ? state.ceiling : LOWER_CEILING;
                float fraction = spreadVertically ? (layerIndex + state.random.nextFloat()) / layerCount
                    : 0.75f + state.random.nextFloat() * 0.25f;
                altitude = floor + fraction * (altitudeCeiling - floor);
                place();
                if (!isActive()) { setActive(true); }
                return true;
            }
            setActive(false);
            return false;
        }

        @Override
        public void tick(int ticksSinceLastFrame)
        {
            if (state.cleared || state.client.getGameState() != GameState.LOGGED_IN
                || state.world.getScene() != state.scene || state.world.getPlane() != 0)
            {
                setActive(false);
                return;
            }
            Player player = state.client.getLocalPlayer();
            LocalPoint point = player == null ? null : player.getLocalLocation();
            if (point == null || point.getWorldView() != getWorldView())
            {
                setActive(false);
                return;
            }
            state.center = point;
            state.refreshCeiling();
            if (upperLayer && altitudeCeiling != state.ceiling)
            {
                // Preserve each flake's position within its layer as the camera
                // zoom changes; no respawn, extra object or model rebuild needed.
                altitude = UPPER_FLOOR + (altitude - UPPER_FLOOR)
                    * (state.ceiling - UPPER_FLOOR) / (altitudeCeiling - UPPER_FLOOR);
                altitudeCeiling = state.ceiling;
            }
            int elapsed = Math.max(0, Math.min(10, ticksSinceLastFrame));
            age += elapsed;
            altitude -= speed * elapsed;
            x += (0.5f + (float) Math.sin(age * 0.035f + phase) * 0.55f) * elapsed;
            y += (float) Math.cos(age * 0.024f + phase) * 0.45f * elapsed;
            float dx = x - point.getX(), dy = y - point.getY();
            if (altitude <= (upperLayer ? UPPER_FLOOR : 6)
                || dx * dx + dy * dy > (RADIUS + 128f) * (RADIUS + 128f)
                || !state.outdoors((int) x, (int) y))
            {
                respawn(false);
                return;
            }
            place();
        }

        private void place()
        {
            // Use cached regular-scene heights and absolute coordinates, avoiding
            // per-frame LocalPoint allocation and repeated scene array access.
            setX((int) x);
            setY((int) y);
            setZ(Math.round(GroundCoverPlacement.height(state.heights, (int) x, (int) y) - altitude));
        }
    }
}
