package com.seasonalscape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.JagexColor;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;

/**
 * Small, depth-tested ground decorations. All methods run on the client thread.
 * Objects have no menus, collision data, animations or gameplay effects.
 */
public final class GroundCover
{
    // An existing low-polygon snow-particle mesh in the game cache. The mesh is
    // cloned before its vertices, colors, textures or transparency are changed.
    private static final int FLAKE_MODEL_ID = 27835;
    private static final int SECTOR_TILES = 8;
    private static final int MAX_OBJECTS = 200;
    private static final int VARIANT_COUNT = 4;
    private static final int MAX_FLAKES = 16;
    private static final int MAX_CORNER_HEIGHT_DIFFERENCE = 64;

    private final Client client;
    private final Map<Long, RuneLiteObject> objects = new HashMap<>();
    private Scene currentScene;
    private int baseX;
    private int baseY;
    private Season modelSeason;
    private Model[] models;
    private List<Candidate> cachedCandidates;
    private int cachedDensity = -1;
    private Season cachedSeason;

    public GroundCover(Client client)
    {
        this.client = client;
    }

    public int getCount()
    {
        return objects.size();
    }

    /** Unregisters every decoration immediately, including objects offscreen. */
    public void clear()
    {
        for (RuneLiteObject object : objects.values())
        {
            object.setActive(false);
        }
        objects.clear();
        currentScene = null;
        invalidate();
    }

    /** Recheck outdoor eligibility after a scene/configuration/object refresh. */
    public void invalidate()
    {
        cachedCandidates = null;
        cachedDensity = -1;
        cachedSeason = null;
    }

    /**
     * Every nonwinter season covers the loaded outdoor scene with evenly spread
     * decorations. Default density places up to 120 flowers or 200 leaf piles;
     * all seasons retain a hard limit of 200 objects.
     * Density continues to select the same percentage of eligible world tiles.
     */
    public void update(Scene scene, SeasonalSceneRecolorer recolorer, Season season,
        boolean enabled, int density)
    {
        WorldView world = client.getTopLevelWorldView();
        Player player = client.getLocalPlayer();
        LocalPoint playerPoint = player == null ? null : player.getLocalLocation();
        if (!enabled || density <= 0 || season == null
            || season == Season.WINTER
            || client.getGameState() != GameState.LOGGED_IN || scene == null || recolorer == null
            || world == null || !world.isTopLevel() || world.getScene() != scene
            || world.isInstance() || scene.isInstance() || world.getPlane() != 0
            || playerPoint == null || playerPoint.getWorldView() != world.getId()
            || !SeasonalWorldArea.contains(scene.getBaseX() + playerPoint.getSceneX(),
                scene.getBaseY() + playerPoint.getSceneY()))
        {
            clear();
            return;
        }

        if (currentScene != scene || baseX != scene.getBaseX() || baseY != scene.getBaseY()
            || modelSeason != season)
        {
            clear();
            currentScene = scene;
            baseX = scene.getBaseX();
            baseY = scene.getBaseY();
        }
        if (models == null || modelSeason != season)
        {
            models = createModels(season);
            modelSeason = season;
            if (models == null)
            {
                return; // A cache model may not have been downloaded yet; retry later.
            }
        }

        Tile[][][] tiles = scene.getTiles();
        CollisionData[] collisionMaps = world.getCollisionMaps();
        // WorldView heights use the same regular scene coordinates as getTiles();
        // Scene heights can include the extended-scene border.
        int[][][] heights = world.getTileHeights();
        if (tiles == null || tiles.length == 0 || tiles[0] == null
            || collisionMaps == null || collisionMaps.length == 0 || collisionMaps[0] == null
            || heights == null || heights.length == 0 || heights[0] == null)
        {
            clear();
            return;
        }

        int[][] collisionFlags = collisionMaps[0].getFlags();
        int chance = Math.min(100, density);
        int limit = Math.min(MAX_OBJECTS, chance * (season == Season.AUTUMN ? 8 : 4));
        List<Candidate> candidates;
        if (cachedCandidates != null && cachedDensity == chance && cachedSeason == season
            && candidatesStillEligible(cachedCandidates, tiles, collisionFlags, heights[0], recolorer))
        {
            candidates = cachedCandidates;
        }
        else
        {
            candidates = distributeAcrossSectors(
                candidates(tiles, collisionFlags, heights[0], recolorer, chance), limit);
            cachedCandidates = candidates;
            cachedDensity = chance;
            cachedSeason = season;
        }

        // Desired positions are independent of both player and camera;
        // walking through a stable scene retains the same registered objects.
        Set<Long> desired = new HashSet<>();
        for (int i = 0; i < Math.min(limit, candidates.size()); i++)
        {
            desired.add(candidates.get(i).key);
        }
        Iterator<Map.Entry<Long, RuneLiteObject>> iterator = objects.entrySet().iterator();
        while (iterator.hasNext())
        {
            Map.Entry<Long, RuneLiteObject> entry = iterator.next();
            if (!desired.contains(entry.getKey()))
            {
                entry.getValue().setActive(false);
                iterator.remove();
            }
        }
        for (int i = 0; i < Math.min(limit, candidates.size()); i++)
        {
            Candidate candidate = candidates.get(i);
            if (!objects.containsKey(candidate.key))
            {
                LocalPoint center = candidate.tile.getLocalLocation();
                int jitterX = (int) ((candidate.seed >>> 8) % 17) - 8;
                int jitterY = (int) ((candidate.seed >>> 16) % 17) - 8;
                LocalPoint location = new LocalPoint(center.getX() + jitterX,
                    center.getY() + jitterY, world.getId());
                int variant = (int) ((candidate.seed >>> 24) & (VARIANT_COUNT - 1));
                Model model = GroundCoverPlacement.model(client, season, variant, location, heights[0]);
                if (model == null)
                {
                    continue;
                }
                RuneLiteObject object = client.createRuneLiteObject();
                object.setModel(model);
                object.setLocation(location, 0);
                object.setZ(object.getZ() - 2);
                // The mesh already has varied decoration angles and follows the
                // terrain at this location, so rotating it would undo that fit.
                object.setOrientation(0);
                object.setActive(true);
                objects.put(candidate.key, object);
            }
        }
    }

    private List<Candidate> candidates(Tile[][][] tiles, int[][] collisionFlags,
        int[][] heights, SeasonalSceneRecolorer recolorer, int chance)
    {
        List<Candidate> candidates = new ArrayList<>();
        for (int x = 1; x < tiles[0].length - 1; x++)
        {
            if (tiles[0][x] == null)
            {
                continue;
            }
            for (int y = 1; y < tiles[0][x].length - 1; y++)
            {
                long key = worldKey(baseX + x, baseY + y);
                long seed = mix(key);
                Tile tile = tiles[0][x][y];
                if (Math.floorMod(seed, 100) < chance
                    && eligible(tile, x, y, tiles, collisionFlags, heights, recolorer))
                {
                    candidates.add(new Candidate(key, seed, tile, x, y));
                }
            }
        }

        return candidates;
    }

    private static boolean candidatesStillEligible(List<Candidate> candidates, Tile[][][] tiles,
        int[][] collision, int[][] heights, SeasonalSceneRecolorer recolorer)
    {
        for (Candidate candidate : candidates)
        {
            int x = candidate.x, y = candidate.y;
            if (x >= tiles[0].length || tiles[0][x] == null || y >= tiles[0][x].length
                || tiles[0][x][y] != candidate.tile
                || !eligible(candidate.tile, x, y, tiles, collision, heights, recolorer))
            {
                return false;
            }
        }
        return true;
    }

    /** Give every occupied world sector a decoration before filling any sector again. */
    private static List<Candidate> distributeAcrossSectors(List<Candidate> candidates, int limit)
    {
        Map<Long, List<Candidate>> sectors = new HashMap<>();
        for (Candidate candidate : candidates)
        {
            int worldX = (int) (candidate.key >> 32);
            int worldY = (int) candidate.key;
            long sector = worldKey(Math.floorDiv(worldX, SECTOR_TILES),
                Math.floorDiv(worldY, SECTOR_TILES));
            sectors.computeIfAbsent(sector, unused -> new ArrayList<>()).add(candidate);
        }
        List<Long> order = new ArrayList<>(sectors.keySet());
        order.sort(Comparator.comparingLong(GroundCover::mix));
        for (List<Candidate> sector : sectors.values())
        {
            sector.sort(Comparator.comparingLong(candidate -> candidate.seed));
        }
        List<Candidate> result = new ArrayList<>(Math.min(limit, candidates.size()));
        for (int round = 0; result.size() < limit; round++)
        {
            boolean found = false;
            for (long key : order)
            {
                List<Candidate> sector = sectors.get(key);
                if (round < sector.size())
                {
                    result.add(sector.get(round));
                    found = true;
                    if (result.size() == limit) { break; }
                }
            }
            if (!found) { break; }
        }
        return result;
    }

    private static boolean eligible(Tile tile, int x, int y, Tile[][][] tiles,
        int[][] collision, int[][] heights, SeasonalSceneRecolorer recolorer)
    {
        if (tile == null || tile.getPlane() != 0 || tile.getRenderLevel() != 0
            || tile.getBridge() != null || tile.getWallObject() != null
            || tile.getDecorativeObject() != null
            || tile.getItemLayer() != null || !recolorer.isSeasonalGround(tile)
            || collision == null || x >= collision.length || collision[x] == null
            || y >= collision[x].length || collision[x][y] != 0)
        {
            return false;
        }
        GameObject[] gameObjects = tile.getGameObjects();
        if (gameObjects != null)
        {
            for (GameObject object : gameObjects)
            {
                if (object != null)
                {
                    return false;
                }
            }
        }
        for (int plane = 1; plane < tiles.length; plane++)
        {
            if (tiles[plane] != null && x < tiles[plane].length && tiles[plane][x] != null
                && y < tiles[plane][x].length && tiles[plane][x][y] != null)
            {
                return false; // Roofs, bridges and upper floors.
            }
        }
        if (x + 1 >= heights.length || heights[x] == null || heights[x + 1] == null
            || y + 1 >= heights[x].length || y + 1 >= heights[x + 1].length)
        {
            return false;
        }
        int min = Math.min(Math.min(heights[x][y], heights[x + 1][y]),
            Math.min(heights[x][y + 1], heights[x + 1][y + 1]));
        int max = Math.max(Math.max(heights[x][y], heights[x + 1][y]),
            Math.max(heights[x][y + 1], heights[x + 1][y + 1]));
        return max - min <= MAX_CORNER_HEIGHT_DIFFERENCE;
    }

    private Model[] createModels(Season season)
    {
        ModelData source = client.loadModelData(FLAKE_MODEL_ID);
        if (source == null || source.getVerticesCount() < 3 || source.getFaceCount() == 0
            || source.getVerticesCount() > 8192 || source.getFaceCount() > 8192)
        {
            return null;
        }
        Model[] result = new Model[VARIANT_COUNT];
        for (int variant = 0; variant < VARIANT_COUNT; variant++)
        {
            ModelData data = source.shallowCopy().cloneVertices().cloneColors();
            // Untextured cache models have no texture array to clone.
            if (data.getFaceTextures() != null)
            {
                data.cloneTextures();
            }
            data.cloneTransparencies(true);
            reshapeFlakes(source, data, season, variant);
            if (data.getFaceTextures() != null)
            {
                Arrays.fill(data.getFaceTextures(), (short) -1);
            }
            Arrays.fill(data.getFaceTransparencies(), (byte) 0);
            // Exercise the normal transform path after direct vertex edits as
            // a precaution against cached derived geometry before lighting.
            data.translate(0, 0, 0);
            result[variant] = data.light(80, ModelData.DEFAULT_CONTRAST,
                ModelData.DEFAULT_X, ModelData.DEFAULT_Y, ModelData.DEFAULT_Z);
        }
        return result;
    }

    /** Reuses mesh topology without ever changing the cache's face indices. */
    static void reshapeFlakes(ModelData source, ModelData target, Season season, int variant)
    {
        int count = source.getVerticesCount();
        int[] roots = new int[count];
        for (int i = 0; i < count; i++)
        {
            roots[i] = i;
        }
        int[] faceA = source.getFaceIndices1();
        int[] faceB = source.getFaceIndices2();
        int[] faceC = source.getFaceIndices3();
        for (int i = 0; i < source.getFaceCount(); i++)
        {
            union(roots, faceA[i], faceB[i]);
            union(roots, faceA[i], faceC[i]);
        }
        Map<Integer, List<Integer>> components = new LinkedHashMap<>();
        for (int i = 0; i < count; i++)
        {
            components.computeIfAbsent(root(roots, i), unused -> new ArrayList<>()).add(i);
        }
        float[][] original = {source.getVerticesX(), source.getVerticesY(), source.getVerticesZ()};
        float[] x = target.getVerticesX();
        float[] y = target.getVerticesY();
        float[] z = target.getVerticesZ();
        Map<Integer, Integer> pieces = new HashMap<>();
        int flake = 0;
        for (Map.Entry<Integer, List<Integer>> component : components.entrySet())
        {
            List<Integer> vertices = component.getValue();
            if (vertices.size() < 3 || flake >= MAX_FLAKES)
            {
                for (int index : vertices)
                {
                    x[index] = 0;
                    y[index] = 0;
                    z[index] = 0;
                }
                continue;
            }
            pieces.put(component.getKey(), flake);
            float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
            float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
            for (int index : vertices)
            {
                for (int axis = 0; axis < 3; axis++)
                {
                    min[axis] = Math.min(min[axis], original[axis][index]);
                    max[axis] = Math.max(max[axis], original[axis][index]);
                }
            }
            Integer[] axes = {0, 1, 2};
            Arrays.sort(axes, (a, b) -> Float.compare(max[b] - min[b], max[a] - min[a]));
            int first = axes[0];
            int second = axes[1];
            float spanA = Math.max(1, max[first] - min[first]);
            float spanB = Math.max(1, max[second] - min[second]);
            float winding = 1;
            for (int face = 0; face < source.getFaceCount(); face++)
            {
                if (root(roots, faceA[face]) != component.getKey())
                {
                    continue;
                }
                float area = (original[first][faceB[face]] - original[first][faceA[face]])
                    * (original[second][faceC[face]] - original[second][faceA[face]])
                    - (original[second][faceB[face]] - original[second][faceA[face]])
                    * (original[first][faceC[face]] - original[first][faceA[face]]);
                if (Math.abs(area) > 0.01f)
                {
                    winding = Math.signum(area);
                    break;
                }
            }
            long seed = mix(0x534541534f4eL + flake * 97L + variant * 7919L);
            double angle = ((seed >>> 8) & 1023) * (2 * Math.PI / 1024);
            double distance = Math.sqrt(((seed >>> 20) & 255) / 255.0)
                * (season == Season.AUTUMN ? 28 : 33);
            float centerX = (float) (Math.cos(angle) * distance);
            float centerZ = (float) (Math.sin(angle) * distance);
            double rotation = ((seed >>> 30) & 1023) * (2 * Math.PI / 1024);
            float cosine = (float) Math.cos(rotation);
            float sine = (float) Math.sin(rotation);
            float length = season == Season.WINTER ? 18 + (seed & 7) : 9 + (seed & 7);
            float width = season == Season.WINTER ? 14 + ((seed >>> 3) & 7)
                : season == Season.SPRING ? 5 : 7;
            for (int index : vertices)
            {
                float u = (original[first][index] - min[first]) / spanA - 0.5f;
                float v = ((original[second][index] - min[second]) / spanB - 0.5f) * winding;
                if (SeasonalFlowerGeometry.appliesTo(season))
                {
                    SeasonalFlowerGeometry.vertex(season, variant, flake, u, v, x, y, z, index);
                    continue;
                }
                float along = u * length;
                float across = v * width;
                x[index] = centerX + along * cosine - across * sine;
                z[index] = centerZ + along * sine + across * cosine;
                y[index] = -0.25f - (1 - Math.abs(u * 2)) * (1 - Math.abs(v * 2));
                if (season == Season.AUTUMN) { y[index] -= flake * 0.03f; }
            }
            flake++;
        }
        short[] colors = target.getFaceColors();
        for (int face = 0; face < source.getFaceCount(); face++)
        {
            int shade = Math.floorMod(root(roots, faceA[face]) + variant, 4);
            colors[face] = SeasonalFlowerGeometry.appliesTo(season)
                ? SeasonalFlowerGeometry.color(season, variant,
                    pieces.getOrDefault(root(roots, faceA[face]), 0))
                : flakeColor(season, shade);
        }
    }

    private static short flakeColor(Season season, int shade)
    {
        if (season == Season.WINTER)
        {
            return JagexColor.packHSL(36, 0, 108 + shade * 4);
        }
        if (season == Season.SPRING)
        {
            return JagexColor.packHSL(59 + shade % 2, 2, 91 + shade * 6);
        }
        int[] hues = {3, 6, 9, 11};
        return JagexColor.packHSL(hues[shade], 5, 40 + shade * 7);
    }

    private static int root(int[] parents, int index)
    {
        while (parents[index] != index)
        {
            parents[index] = parents[parents[index]];
            index = parents[index];
        }
        return index;
    }

    private static void union(int[] parents, int first, int second)
    {
        parents[root(parents, second)] = root(parents, first);
    }

    private static long worldKey(int x, int y)
    {
        return ((long) x << 32) | (y & 0xffffffffL);
    }

    private static long mix(long value)
    {
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static final class Candidate
    {
        private final long key;
        private final long seed;
        private final Tile tile;
        private final int x;
        private final int y;

        private Candidate(long key, long seed, Tile tile, int x, int y)
        {
            this.key = key;
            this.seed = seed;
            this.tile = tile;
            this.x = x;
            this.y = y;
        }
    }
}
