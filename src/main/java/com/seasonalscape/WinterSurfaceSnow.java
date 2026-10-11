package com.seasonalscape;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Renderable;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.hooks.DrawCallbacks;

/** Thin winter coatings on exposed structures; furniture source meshes remain untouched. */
final class WinterSurfaceSnow
{
    private static final Map<Object, State> STATES = new WeakHashMap<>();
    private static final int RADIUS = 22, SCAN_RADIUS = 24, MAX_OBJECTS = 128, MAX_FACES = 59;
    private static final int TILES_PER_CYCLE = 192, MODELS_PER_CYCLE = 2, GPU_ZONES_PER_CYCLE = 2;
    private static final long WORK_BUDGET_NANOS = 2_000_000L;
    private static final int[][] OFFSETS = scanOffsets();
    private static final int HIDDEN = 12345678;

    private WinterSurfaceSnow() {}

    /** Starts snow; subsequent client ticks continue a bounded amount of work. */
    static void update(Object owner, Client client, Scene scene, boolean enabled, SeasonalRenderer renderer)
    {
        if (!enabled) { restore(owner); return; }
        if (client.getGameState() == GameState.LOADING) { return; }
        if (client.getGameState() != GameState.LOGGED_IN || !SeasonalSceneRecolorer.supports(scene))
        {
            restore(owner); return;
        }
        State state = STATES.get(owner);
        if (state == null || state.scene != scene || state.baseX != scene.getBaseX() || state.baseY != scene.getBaseY())
        {
            restore(owner);
            Tile[][][] tiles = scene.getExtendedTiles();
            if (tiles == null || tiles.length == 0 || tiles[0] == null) { return; }
            state = new State(client, scene, renderer);
            STATES.put(owner, state);
        }
        tick(owner);
    }

    static void tick(Object owner)
    {
        State state = STATES.get(owner);
        if (state == null) { return; }
        Client client = state.client;
        if (client.getGameState() == GameState.LOADING) { return; }
        if (client.getGameState() != GameState.LOGGED_IN || !SeasonalSceneRecolorer.supports(state.scene))
        {
            restore(owner); return;
        }
        if (!state.renderer.supported()) { restore(owner); return; }
        WorldView world = client.getTopLevelWorldView();
        if (world != null && world.getScene() != state.scene) { return; }
        int cycle = client.getGameCycle();
        if (cycle == state.lastCycle) { return; }
        state.lastCycle = cycle;
        state.lastTiles = state.lastModels = state.lastCandidates = state.lastBuilds = state.lastGpuZones = 0;
        long start = System.nanoTime();
        Player player = client.getLocalPlayer();
        LocalPoint point = player == null ? null : player.getLocalLocation();
        if (point == null) { return; }
        if (point.getWorldView() != WorldView.TOPLEVEL) { restore(owner); return; }
        if (!inRegion(state.scene, point.getSceneX(), point.getSceneY())) { restore(owner); return; }
        WinterSnowVisibility view = WinterSnowVisibility.capture(client, point);
        int plane = world == null ? 0 : world.getPlane();
        if (state.scan == null && (state.rescan || !view.similarTo(state.lastView)
            || state.lastPlane != plane || cycle >= state.retryAt))
        {
            Tile[][][] tiles = state.scene.getExtendedTiles();
            byte[][][] flags = state.scene.getExtendedTileSettings();
            if (tiles == null || tiles.length == 0 || flags == null) { return; }
            state.scan = new Scan(state, point, view, tiles, flags, world == null ? null : world.getTileHeights());
            state.rescan = false;
            state.retryAt = Integer.MAX_VALUE;
            state.lastPlane = plane;
        }
        Scan scan = state.scan;
        if (scan != null)
        {
            // Finish each snapshot before taking the newest view: continuous camera
            // movement must never restart the scan and starve the remaining work.
            while (state.lastTiles < TILES_PER_CYCLE && state.lastModels < MODELS_PER_CYCLE
                && state.lastCandidates < 32
                && System.nanoTime() - start < WORK_BUDGET_NANOS)
            {
                if (!scan.pending.isEmpty()) { process(state, scan, scan.pending.removeFirst()); }
                else if (scan.position < OFFSETS.length * scan.tiles.length)
                {
                    scanTile(state, scan); state.lastTiles++;
                }
                else
                {
                    state.lastTiles++;
                    if (cleanup(state, scan))
                    {
                        state.lastView = scan.view; state.scan = null; break;
                    }
                }
            }
        }
        state.invalidate(GPU_ZONES_PER_CYCLE);
        state.lastWorkNanos = System.nanoTime() - start;
        state.maxWorkNanos = Math.max(state.maxWorkNanos, state.lastWorkNanos);
    }

    static void markDirty(Object owner)
    {
        State state = STATES.get(owner);
        if (state != null) { state.rescan = true; }
    }

    private static int[][] scanOffsets()
    {
        List<int[]> offsets = new ArrayList<>();
        for (int x = -SCAN_RADIUS; x <= SCAN_RADIUS; x++)
        {
            for (int y = -SCAN_RADIUS; y <= SCAN_RADIUS; y++)
            {
                if (x * x + y * y <= SCAN_RADIUS * SCAN_RADIUS) { offsets.add(new int[]{x, y}); }
            }
        }
        offsets.sort(Comparator.comparingInt(offset -> offset[0] * offset[0] + offset[1] * offset[1]));
        return offsets.toArray(new int[0][]);
    }

    private static void scanTile(State state, Scan scan)
    {
        int position = scan.position++;
        int[] offset = OFFSETS[position / scan.tiles.length];
        int plane = scan.tiles.length - 1 - position % scan.tiles.length;
        int x = scan.point.getSceneX() + offset[0], y = scan.point.getSceneY() + offset[1];
        int tx = x + state.offset, ty = y + state.offset;
        if (scan.tiles[plane] == null || tx < 0 || tx >= scan.tiles[plane].length
            || scan.tiles[plane][tx] == null || ty < 0 || ty >= scan.tiles[plane][tx].length
            || !inRegion(state.scene, x, y)) { return; }
        Tile tile = scan.tiles[plane][tx][ty];
        if (tile == null) { return; }
        int height = 0;
        if (scan.heights != null && plane < scan.heights.length && scan.heights[plane] != null
            && x >= 0 && x < scan.heights[plane].length && scan.heights[plane][x] != null
            && y >= 0 && y < scan.heights[plane][x].length) { height = scan.heights[plane][x][y]; }
        boolean retained = state.roofs.containsKey(tile) || state.retainedColumns.get(tx * state.size + ty);
        if (!scan.view.visibleTile(x, y, height, retained) || tile.getBridge() != null
            || !exposed(scan.tiles, scan.flags, plane, x, y)) { return; }
        boolean indoors = underRoof(scan.flags, plane, x, y);
        if (plane > 0 && !indoors && visibleFloor(tile) && !roofObjectOn(tile))
        {
            Roof roof = state.roofs.get(tile);
            if (roof != null && (roof.paint != tile.getSceneTilePaint() || roof.model != tile.getSceneTileModel()))
            {
                if (roof.restore()) { state.dirty(x, y); }
                roof = null;
            }
            if (roof == null)
            {
                roof = new Roof(tile, x, y); state.roofs.put(tile, roof);
                if (roof.apply()) { state.dirty(x, y); }
            }
            roof.seen = true;
        }
        GameObject[] objects = tile.getGameObjects();
        if (objects != null)
        {
            for (GameObject object : objects)
            {
                if (object == null || !scan.seen.add(object)) { continue; }
                String name = name(state.client, object);
                boolean roof = isRoof(name, object.getConfig());
                if ((!roof && !furnitureOrStructure(name)) || vegetation(name)) { continue; }
                if ((!roof && indoors) || !footprintExposed(scan.tiles, scan.flags, object, roof)) { continue; }
                int type = object.getConfig() & 31;
                candidate(state, scan.pending, object, object.getRenderable(), 0, object.getModelOrientation(), 0, 0,
                    roof, plane > 0 && type >= 12 && type <= 21, scan.point);
            }
        }
        WallObject wall = tile.getWallObject();
        if (!indoors && wall != null && scan.seen.add(wall) && !vegetation(name(state.client, wall)))
        {
            candidate(state, scan.pending, wall, wall.getRenderable1(), 0, 0, 0, 0, false, false, scan.point);
            candidate(state, scan.pending, wall, wall.getRenderable2(), 1, 0, 0, 0, false, false, scan.point);
        }
        DecorativeObject decoration = tile.getDecorativeObject();
        if (decoration != null && scan.seen.add(decoration))
        {
            String name = name(state.client, decoration);
            boolean roof = isRoof(name, decoration.getConfig());
            if ((roof || furnitureOrStructure(name)) && !vegetation(name) && (roof || !indoors))
            {
                candidate(state, scan.pending, decoration, decoration.getRenderable(), 0, 0,
                    decoration.getXOffset(), decoration.getYOffset(), roof, false, scan.point);
                candidate(state, scan.pending, decoration, decoration.getRenderable2(), 1, 0,
                    decoration.getXOffset2(), decoration.getYOffset2(), roof, false, scan.point);
            }
        }
    }

    private static void process(State state, Scan scan, Candidate candidate)
    {
        state.lastCandidates++;
        if (candidate.nativeMaterial)
        {
            Integer result = state.nativeModels.get(candidate.model);
            if (result == null)
            {
                state.lastModels++;
                result = WinterRoofMaterial.apply(state, candidate.model);
                state.nativeModels.put(candidate.model, result & 1);
                if ((result & 2) != 0)
                {
                    scan.nativeChanged = true;
                    LocalPoint point = candidate.object.getLocalLocation();
                    state.dirty(point.getSceneX(), point.getSceneY());
                }
            }
            if ((result & 1) != 0) { scan.nativeRoofs++; }
            return;
        }
        if (scan.active >= MAX_OBJECTS) { return; }
        List<Entry> entries = state.entries.computeIfAbsent(candidate.object, ignored -> new ArrayList<>());
        Entry entry = null;
        for (Entry old : entries) { if (old.slot == candidate.slot) { entry = old; break; } }
        if (entry != null && !entry.matches(candidate))
        {
            entry.clear(); entries.remove(entry); entry = null;
        }
        if (entry == null)
        {
            Map<Model, Model> cache = candidate.roof ? state.roofModels : state.surfaceModels;
            Model snow = cache.get(candidate.model);
            if (snow == null && !cache.containsKey(candidate.model))
            {
                state.lastModels++;
                Map<Model, List<Triangle>> geometry = candidate.roof ? state.roofGeometry : state.surfaceGeometry;
                List<Triangle> triangles = geometry.computeIfAbsent(candidate.model, model -> surfaces(model, candidate.roof));
                snow = build(state.client, triangles);
                state.lastBuilds++;
                if (snow != null || triangles.isEmpty()) { cache.put(candidate.model, snow); }
                else { state.retryAt = Math.min(state.retryAt, state.lastCycle + 50); }
            }
            if (snow == null) { return; }
            RuneLiteObject object = state.client.createRuneLiteObject();
            object.setModel(snow);
            LocalPoint location = candidate.object.getLocalLocation();
            object.setLocation(new LocalPoint(location.getX() + candidate.offsetX,
                location.getY() + candidate.offsetY, location.getWorldView()), candidate.object.getPlane());
            object.setZ(candidate.object.getZ()); object.setOrientation(candidate.orientation); object.setActive(true);
            entry = new Entry(candidate, object); entries.add(entry);
        }
        entry.seen = true; scan.active++;
        LocalPoint location = candidate.object.getLocalLocation();
        state.retainedColumns.set((location.getSceneX() + state.offset) * state.size
            + location.getSceneY() + state.offset);
    }

    /** Cleanup is also incremental, so a camera turn cannot restore hundreds of roofs in one frame. */
    private static boolean cleanup(State state, Scan scan)
    {
        if (scan.cleanupObjects == null)
        {
            scan.cleanupObjects = state.entries.entrySet().iterator();
            scan.cleanupRoofs = state.roofs.values().iterator();
            state.retainedColumns.clear();
        }
        if (scan.cleanupObjects.hasNext())
        {
            Map.Entry<TileObject, List<Entry>> object = scan.cleanupObjects.next();
            List<Entry> entries = object.getValue();
            entries.removeIf(entry -> { if (!entry.seen) { entry.clear(); return true; } return false; });
            if (entries.isEmpty()) { scan.cleanupObjects.remove(); }
            else
            {
                LocalPoint point = object.getKey().getLocalLocation();
                state.retainedColumns.set((point.getSceneX() + state.offset) * state.size + point.getSceneY() + state.offset);
            }
            return false;
        }
        if (scan.cleanupRoofs.hasNext())
        {
            Roof roof = scan.cleanupRoofs.next();
            if (!roof.seen)
            {
                if (roof.restore()) { state.dirty(roof.x, roof.y); }
                scan.cleanupRoofs.remove();
            }
            return false;
        }
        state.nativeRoofs = scan.nativeRoofs;
        // Native models share material arrays across zones. A fresh mutation
        // needs all aliases refreshed, but uploads drain a few zones each cycle.
        if (scan.nativeChanged) { state.dirty.set(0, state.width * state.width); }
        return true;
    }

    static void restore(Object owner)
    {
        State state = STATES.remove(owner);
        if (state == null) { return; }
        for (List<Entry> entries : state.entries.values()) { for (Entry entry : entries) { entry.clear(); } }
        for (Roof roof : state.roofs.values()) { if (roof.restore()) { state.dirty(roof.x, roof.y); } }
        if (WinterRoofMaterial.restore(state)) { state.dirty.set(0, state.width * state.width); }
        state.invalidate(Integer.MAX_VALUE);
    }

    static int getCount(Object owner)
    {
        State state = STATES.get(owner);
        if (state == null) { return 0; }
        int count = state.roofs.size() + state.nativeRoofs;
        for (List<Entry> entries : state.entries.values()) { count += entries.size(); }
        return count;
    }

    private static boolean inRegion(Scene scene, int x, int y)
    {
        int wx = scene.getBaseX() + x, wy = scene.getBaseY() + y;
        return SeasonalWorldArea.contains(wx, wy);
    }

    static boolean exposed(Scene scene, int plane, int x, int y)
    {
        return exposed(scene.getExtendedTiles(), scene.getExtendedTileSettings(), plane, x, y);
    }

    private static boolean exposed(Tile[][][] tiles, byte[][][] flags, int plane, int x, int y)
    {
        if (tiles == null || tiles.length == 0 || tiles[0] == null
            || flags == null || flags.length == 0 || flags[0] == null) { return false; }
        int tileOffset = (tiles[0].length - Constants.SCENE_SIZE) / 2;
        int offset = (flags[0].length - Constants.SCENE_SIZE) / 2;
        for (int upper = plane + 1; upper < tiles.length; upper++)
        {
            int fx = x + offset, fy = y + offset;
            if (upper < flags.length && flags[upper] != null && fx >= 0 && fx < flags[upper].length
                && flags[upper][fx] != null && fy >= 0 && fy < flags[upper][fx].length
                && (flags[upper][fx][fy] & Constants.TILE_FLAG_UNDER_ROOF) != 0) { return false; }
            int tx = x + tileOffset, ty = y + tileOffset;
            if (tiles[upper] == null || tx < 0 || tx >= tiles[upper].length || tiles[upper][tx] == null
                || ty < 0 || ty >= tiles[upper][tx].length) { continue; }
            Tile cover = tiles[upper][tx][ty];
            if (visibleFloor(cover) || roofObjectOn(cover)) { return false; }
        }
        return true;
    }

    static boolean underRoof(Scene scene, int plane, int x, int y)
    {
        return underRoof(scene.getExtendedTileSettings(), plane, x, y);
    }

    private static boolean underRoof(byte[][][] flags, int plane, int x, int y)
    {
        if (flags == null || flags.length == 0 || flags[0] == null || plane < 0 || plane >= flags.length) { return true; }
        int offset = (flags[0].length - Constants.SCENE_SIZE) / 2;
        int fx = x + offset, fy = y + offset;
        return flags[plane] == null || fx < 0 || fx >= flags[plane].length || flags[plane][fx] == null
            || fy < 0 || fy >= flags[plane][fx].length
            || (flags[plane][fx][fy] & Constants.TILE_FLAG_UNDER_ROOF) != 0;
    }

    private static boolean footprintExposed(Tile[][][] tiles, byte[][][] flags, GameObject object, boolean roof)
    {
        if (object.getSceneMinLocation() == null || object.getSceneMaxLocation() == null) { return false; }
        for (int x = object.getSceneMinLocation().getX(); x <= object.getSceneMaxLocation().getX(); x++)
        {
            for (int y = object.getSceneMinLocation().getY(); y <= object.getSceneMaxLocation().getY(); y++)
            {
                if (!exposed(tiles, flags, object.getPlane(), x, y)
                    || !roof && underRoof(flags, object.getPlane(), x, y)) { return false; }
            }
        }
        return true;
    }

    static boolean visibleFloor(Tile tile)
    {
        if (tile == null) { return false; }
        SceneTilePaint paint = tile.getSceneTilePaint();
        if (paint != null) { return paint.getNeColor() != HIDDEN; }
        SceneTileModel model = tile.getSceneTileModel();
        if (model == null || model.getTriangleColorA() == null) { return false; }
        for (int color : model.getTriangleColorA()) { if (color != HIDDEN && color >= 0) { return true; } }
        return false;
    }

    private static boolean roofObjectOn(Tile tile)
    {
        if (tile == null || tile.getGameObjects() == null) { return false; }
        for (GameObject object : tile.getGameObjects())
        {
            if (object != null) { int type = object.getConfig() & 31; if (type >= 12 && type <= 21) { return true; } }
        }
        return false;
    }

    private static String name(Client client, TileObject object)
    {
        ObjectComposition definition = client.getObjectDefinition(object.getId());
        String name = definition == null ? null : definition.getName();
        return name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean vegetation(String name)
    {
        return name.contains("tree") || name.contains("bush") || name.contains("branch")
            || name.contains("vine") || name.contains("plant") || name.contains("hedge");
    }

    private static boolean furnitureOrStructure(String name)
    {
        return name.contains("chair") || name.contains("stool") || name.contains("bench")
            || name.contains("fence") || name.contains("wall") || name.contains("railing")
            || name.contains("battlement") || name.contains("parapet");
    }

    private static boolean isRoof(String name, int config)
    {
        int type = config & 31;
        return name.contains("roof") || type >= 12 && type <= 21;
    }

    private static void candidate(State state, Deque<Candidate> candidates, TileObject object, Renderable renderable,
        int slot, int orientation, int offsetX, int offsetY, boolean roof, boolean nativeMaterial, LocalPoint player)
    {
        if (!(renderable instanceof Model) || object.getLocalLocation() == null) { return; }
        long dx = object.getLocalLocation().getX() - player.getX();
        long dy = object.getLocalLocation().getY() - player.getY();
        long distance = dx * dx + dy * dy;
        int radius = state.entries.containsKey(object) ? SCAN_RADIUS : RADIUS;
        if (distance <= (long) radius * radius * 128 * 128)
        {
            candidates.add(new Candidate(object, (Model) renderable, slot, orientation,
                offsetX, offsetY, roof, nativeMaterial, distance));
        }
    }

    private static Model build(Client client, List<Triangle> triangles)
    {
        if (triangles.isEmpty()) { return null; }
        ModelData template = client.loadModelData(27835);
        if (template == null) { return null; }
        ModelData data = client.mergeModels(template);
        if (data == null || data.getFaceIndices1() == template.getFaceIndices1()
            || data.getFaceIndices2() == template.getFaceIndices2()
            || data.getFaceIndices3() == template.getFaceIndices3()) { return null; }
        data.cloneVertices().cloneColors().cloneTransparencies(true);
        if (data.getFaceTextures() != null) { data.cloneTextures(); }
        if (writeTriangles(data, triangles) == 0) { return null; }
        data.translate(0, 0, 0);
        return data.light(88, ModelData.DEFAULT_CONTRAST, ModelData.DEFAULT_X,
            ModelData.DEFAULT_Y, ModelData.DEFAULT_Z);
    }

    static List<Triangle> surfaces(Model model, boolean roof)
    {
        List<Triangle> triangles = new ArrayList<>();
        float[] x = model.getVerticesX(), y = model.getVerticesY(), z = model.getVerticesZ();
        int[] a = model.getFaceIndices1(), b = model.getFaceIndices2(), c = model.getFaceIndices3();
        if (x == null || y == null || z == null || a == null || b == null || c == null) { return triangles; }
        int[] colors = model.getFaceColors3();
        byte[] alpha = model.getFaceTransparencies();
        short[] textures = model.getFaceTextures();
        int count = Math.min(model.getFaceCount(), Math.min(a.length, Math.min(b.length, c.length)));
        if (colors != null) { count = Math.min(count, colors.length); }
        if (alpha != null) { count = Math.min(count, alpha.length); }
        if (textures != null) { count = Math.min(count, textures.length); }
        int vertices = Math.min(model.getVerticesCount(), Math.min(x.length, Math.min(y.length, z.length)));
        for (int face = 0; face < count; face++)
        {
            if (colors != null && colors[face] == -2 || alpha != null && (alpha[face] & 255) > 128) { continue; }
            if (textures != null && SeasonalTextureTint.isLeafTexture(textures[face])) { continue; }
            if (a[face] < 0 || b[face] < 0 || c[face] < 0
                || a[face] >= vertices || b[face] >= vertices || c[face] >= vertices) { continue; }
            Triangle triangle = new Triangle(x[a[face]], y[a[face]], z[a[face]],
                x[b[face]], y[b[face]], z[b[face]], x[c[face]], y[c[face]], z[c[face]]);
            if (triangle.area >= 1 && triangle.ny < (roof ? -0.35 : -0.85)) { triangles.add(triangle); }
        }
        // A chair seat shelters the tops of its legs; upper rails similarly
        // shield surfaces directly below. Use the mesh itself for this local cover.
        List<Triangle> exposed = new ArrayList<>();
        for (Triangle triangle : triangles)
        {
            boolean covered = false;
            double cx = (triangle.xyz[0] + triangle.xyz[3] + triangle.xyz[6]) / 3.0;
            double cy = (triangle.xyz[1] + triangle.xyz[4] + triangle.xyz[7]) / 3.0;
            double cz = (triangle.xyz[2] + triangle.xyz[5] + triangle.xyz[8]) / 3.0;
            for (Triangle above : triangles)
            {
                if (above == triangle) { continue; }
                double ax = above.xyz[0], az = above.xyz[2];
                double bx = above.xyz[3], bz = above.xyz[5], tx = above.xyz[6], tz = above.xyz[8];
                double denominator = (bz - tz) * (ax - tx) + (tx - bx) * (az - tz);
                if (Math.abs(denominator) < 0.001) { continue; }
                double u = ((bz - tz) * (cx - tx) + (tx - bx) * (cz - tz)) / denominator;
                double v = ((tz - az) * (cx - tx) + (ax - tx) * (cz - tz)) / denominator;
                double w = 1 - u - v;
                if (u >= -0.001 && v >= -0.001 && w >= -0.001
                    && u * above.xyz[1] + v * above.xyz[4] + w * above.xyz[7] < cy - 1)
                {
                    covered = true; break;
                }
            }
            if (!covered) { exposed.add(triangle); }
        }
        triangles = exposed;
        triangles.sort(Comparator.comparingDouble((Triangle triangle) -> triangle.area).reversed());
        return triangles;
    }

    /** Writes independently owned template arrays; never edits the source object's mesh. */
    static int writeTriangles(ModelData data, List<Triangle> triangles)
    {
        float[] x = data.getVerticesX(), y = data.getVerticesY(), z = data.getVerticesZ();
        int[] a = data.getFaceIndices1(), b = data.getFaceIndices2(), c = data.getFaceIndices3();
        short[] colors = data.getFaceColors();
        byte[] alpha = data.getFaceTransparencies();
        if (x == null || y == null || z == null || a == null || b == null || c == null
            || colors == null || alpha == null) { return 0; }
        Arrays.fill(x, 0); Arrays.fill(y, 0); Arrays.fill(z, 0);
        Arrays.fill(a, 0); Arrays.fill(b, 0); Arrays.fill(c, 0);
        Arrays.fill(alpha, (byte) 255);
        if (data.getFaceTextures() != null) { Arrays.fill(data.getFaceTextures(), (short) -1); }
        int vertices = Math.min(data.getVerticesCount(), Math.min(x.length, Math.min(y.length, z.length)));
        int faces = Math.min(data.getFaceCount(), Math.min(a.length, Math.min(b.length, c.length)));
        int count = Math.min(Math.min(MAX_FACES, triangles.size()), Math.min(faces, vertices / 3));
        for (int face = 0; face < count; face++)
        {
            Triangle triangle = triangles.get(face);
            for (int vertex = 0; vertex < 3; vertex++)
            {
                int index = face * 3 + vertex;
                x[index] = (float) (triangle.xyz[vertex * 3] + triangle.nx * 0.9);
                y[index] = (float) (triangle.xyz[vertex * 3 + 1] + triangle.ny * 0.9);
                z[index] = (float) (triangle.xyz[vertex * 3 + 2] + triangle.nz * 0.9);
            }
            a[face] = face * 3; b[face] = face * 3 + 1; c[face] = face * 3 + 2;
            colors[face] = (short) (36 << 10 | 104);
            alpha[face] = 0;
        }
        return count;
    }

    static final class Triangle
    {
        final float[] xyz;
        final double nx, ny, nz, area;
        Triangle(float... xyz)
        {
            this.xyz = xyz.clone();
            double ux = xyz[3] - xyz[0], uy = xyz[4] - xyz[1], uz = xyz[5] - xyz[2];
            double vx = xyz[6] - xyz[0], vy = xyz[7] - xyz[1], vz = xyz[8] - xyz[2];
            double ax = uy * vz - uz * vy, ay = uz * vx - ux * vz, az = ux * vy - uy * vx;
            double length = Math.sqrt(ax * ax + ay * ay + az * az);
            area = length * 0.5;
            nx = length == 0 ? 0 : ax / length;
            ny = length == 0 ? 0 : ay / length;
            nz = length == 0 ? 0 : az / length;
        }
    }

    private static final class Candidate
    {
        final TileObject object;
        final Model model;
        final int slot, orientation, offsetX, offsetY;
        final boolean roof, nativeMaterial;
        final long distance;
        Candidate(TileObject object, Model model, int slot, int orientation, int offsetX, int offsetY,
            boolean roof, boolean nativeMaterial, long distance)
        {
            this.object = object; this.model = model; this.slot = slot; this.orientation = orientation;
            this.offsetX = offsetX; this.offsetY = offsetY; this.roof = roof;
            this.nativeMaterial = nativeMaterial; this.distance = distance;
        }
    }

    private static final class Entry
    {
        final Model model;
        final RuneLiteObject snow;
        final int slot, orientation, offsetX, offsetY, x, y, z, plane;
        boolean seen;
        Entry(Candidate candidate, RuneLiteObject snow)
        {
            this.model = candidate.model; this.snow = snow; slot = candidate.slot;
            orientation = candidate.orientation; offsetX = candidate.offsetX; offsetY = candidate.offsetY;
            x = candidate.object.getX(); y = candidate.object.getY(); z = candidate.object.getZ();
            plane = candidate.object.getPlane();
        }
        boolean matches(Candidate candidate)
        {
            return candidate.model == model && orientation == candidate.orientation
                && offsetX == candidate.offsetX && offsetY == candidate.offsetY
                && x == candidate.object.getX() && y == candidate.object.getY()
                && z == candidate.object.getZ() && plane == candidate.object.getPlane();
        }
        void clear() { snow.setActive(false); }
    }

    private static final class Scan
    {
        final LocalPoint point;
        final WinterSnowVisibility view;
        final Tile[][][] tiles;
        final byte[][][] flags;
        final int[][][] heights;
        final Set<TileObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        final Deque<Candidate> pending = new ArrayDeque<>();
        int position, active, nativeRoofs;
        Iterator<Map.Entry<TileObject, List<Entry>>> cleanupObjects;
        Iterator<Roof> cleanupRoofs;
        boolean nativeChanged;
        Scan(State state, LocalPoint point, WinterSnowVisibility view, Tile[][][] tiles, byte[][][] flags,
            int[][][] heights)
        {
            this.point = point; this.view = view; this.tiles = tiles; this.flags = flags; this.heights = heights;
            for (Roof roof : state.roofs.values()) { roof.seen = false; }
            for (List<Entry> entries : state.entries.values()) { for (Entry entry : entries) { entry.seen = false; } }
        }
    }

    private static final class State
    {
        final Client client;
        final SeasonalRenderer renderer;
        final Scene scene;
        final Map<Tile, Roof> roofs = new IdentityHashMap<>();
        final Map<TileObject, List<Entry>> entries = new IdentityHashMap<>();
        final BitSet dirty = new BitSet(), urgent = new BitSet(), retainedColumns = new BitSet();
        final Map<Model, Integer> nativeModels = new IdentityHashMap<>();
        final Map<Model, Model> roofModels = new IdentityHashMap<>(), surfaceModels = new IdentityHashMap<>();
        final Map<Model, List<Triangle>> roofGeometry = new IdentityHashMap<>(), surfaceGeometry = new IdentityHashMap<>();
        Scan scan;
        WinterSnowVisibility lastView;
        boolean rescan = true;
        int uploadCursor, lastTiles, lastModels, lastCandidates, lastBuilds, lastGpuZones, lastPlane = -1;
        int retryAt = Integer.MAX_VALUE;
        long lastWorkNanos, maxWorkNanos;
        final int offset, width, size, baseX, baseY;
        int lastCycle = Integer.MIN_VALUE, nativeRoofs;
        State(Client client, Scene scene, SeasonalRenderer renderer)
        {
            this.client = client; this.scene = scene;
            this.renderer = renderer;
            baseX = scene.getBaseX(); baseY = scene.getBaseY();
            size = scene.getExtendedTiles()[0].length;
            offset = (size - Constants.SCENE_SIZE) / 2;
            width = (size + 7) >> 3;
        }
        void dirty(int x, int y)
        {
            int bit = ((x + offset) >> 3) * width + ((y + offset) >> 3);
            dirty.set(bit); urgent.set(bit);
        }
        void invalidate(int budget)
        {
            DrawCallbacks callbacks = client.getDrawCallbacks();
            if (renderer.gpu() != null && client.getTopLevelWorldView() != null)
            {
                Scene current = client.getTopLevelWorldView().getScene();
                if (current == scene)
                {
                    int uploaded = 0;
                    while (!dirty.isEmpty() && uploaded < budget)
                    {
                        int bit = urgent.nextSetBit(0);
                        boolean nearby = bit >= 0 && (uploaded & 1) == 0;
                        if (!nearby)
                        {
                            bit = dirty.nextSetBit(uploadCursor);
                            if (bit < 0) { bit = dirty.nextSetBit(0); }
                            uploadCursor = bit + 1;
                        }
                        callbacks.invalidateZone(scene, bit / width, bit % width);
                        dirty.clear(bit); urgent.clear(bit);
                        uploaded++; lastGpuZones++;
                    }
                    return;
                }
                else if (current != null && !dirty.isEmpty())
                {
                    // The replacement scene may already have uploaded shared
                    // roof models before their old seasonal state was restored.
                    Tile[][][] tiles = current.getExtendedTiles();
                    if (tiles != null && tiles.length > 0 && tiles[0] != null)
                    {
                        int currentWidth = (tiles[0].length + 7) >> 3;
                        for (int x = 0; x < currentWidth; x++)
                        {
                            for (int y = 0; y < currentWidth; y++)
                            {
                                callbacks.invalidateZone(current, x, y);
                            }
                        }
                    }
                }
            }
            dirty.clear(); urgent.clear();
        }
    }

    private static final class Values
    {
        final int[] original, last;
        final BitSet conflicts = new BitSet();
        Values(int[] values) { original = values.clone(); last = values.clone(); }
        boolean set(int[] current, int index, int desired)
        {
            if (current[index] != last[index]) { conflicts.set(index); }
            if (conflicts.get(index)) { return false; }
            boolean changed = current[index] != desired;
            current[index] = last[index] = desired;
            return changed;
        }
        boolean restore(int[] current)
        {
            boolean changed = false;
            for (int i = 0; i < current.length; i++)
            {
                if (!conflicts.get(i) && current[i] == last[i])
                {
                    changed |= current[i] != original[i]; current[i] = original[i];
                }
            }
            return changed;
        }
    }

    private static final class Roof
    {
        final Tile tile;
        final int x, y;
        final SceneTilePaint paint;
        final SceneTileModel model;
        final Values[] colors;
        final Values textures;
        final int originalTexture;
        int lastTexture;
        boolean textureConflict, seen;
        Roof(Tile tile, int x, int y)
        {
            this.tile = tile; this.x = x; this.y = y;
            paint = tile.getSceneTilePaint(); model = tile.getSceneTileModel();
            if (paint != null)
            {
                colors = new Values[]{new Values(paintColors())}; textures = null;
                originalTexture = lastTexture = paint.getTexture();
            }
            else
            {
                colors = new Values[]{new Values(model.getTriangleColorA()), new Values(model.getTriangleColorB()),
                    new Values(model.getTriangleColorC())};
                textures = model.getTriangleTextureId() == null ? null : new Values(model.getTriangleTextureId());
                originalTexture = lastTexture = -1;
            }
        }
        int[] paintColors() { return new int[]{paint.getSwColor(), paint.getSeColor(), paint.getNeColor(), paint.getNwColor()}; }
        void setPaint(int[] values)
        {
            paint.setSwColor(values[0]); paint.setSeColor(values[1]); paint.setNeColor(values[2]); paint.setNwColor(values[3]);
        }
        boolean apply()
        {
            boolean changed = false;
            if (paint != null)
            {
                if (paint.getTexture() != lastTexture) { textureConflict = true; }
                if (textureConflict) { return false; }
                changed = paint.getTexture() != -1;
                paint.setTexture(lastTexture = -1);
                int[] current = paintColors();
                for (int corner = 0; corner < 4; corner++)
                {
                    changed |= colors[0].set(current, corner, snow(colors[0].original[corner]));
                }
                setPaint(current);
            }
            else
            {
                int[][] current = {model.getTriangleColorA(), model.getTriangleColorB(), model.getTriangleColorC()};
                int[] texture = model.getTriangleTextureId();
                for (int face = 0; face < current[0].length; face++)
                {
                    if (colors[0].original[face] == HIDDEN) { continue; }
                    if (texture != null) { changed |= textures.set(texture, face, -1); }
                    for (int channel = 0; channel < current.length; channel++)
                    {
                        changed |= colors[channel].set(current[channel], face, snow(colors[channel].original[face]));
                    }
                }
            }
            return changed;
        }
        boolean restore()
        {
            boolean changed = false;
            if (paint != null)
            {
                int[] current = paintColors(); changed |= colors[0].restore(current); setPaint(current);
                if (!textureConflict && paint.getTexture() == lastTexture)
                {
                    changed |= paint.getTexture() != originalTexture; paint.setTexture(originalTexture);
                }
            }
            else
            {
                changed |= colors[0].restore(model.getTriangleColorA());
                changed |= colors[1].restore(model.getTriangleColorB());
                changed |= colors[2].restore(model.getTriangleColorC());
                if (textures != null) { changed |= textures.restore(model.getTriangleTextureId()); }
            }
            return changed;
        }
        int snow(int original) { return original < 0 || original == HIDDEN ? original : 36 << 10 | 96 + (original & 127) * 16 / 127; }
    }
}
