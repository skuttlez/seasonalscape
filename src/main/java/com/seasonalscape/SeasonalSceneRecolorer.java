package com.seasonalscape;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.plugins.gpu.GpuPlugin;

/** Client-thread-only seasonal changes to existing scene colors. */
public final class SeasonalSceneRecolorer
{
    private final Client client;
    private final Map<SceneTilePaint, Colors> paints = new WeakHashMap<>();
    // Arrays have identity equality; weak keys release models evicted from the game cache.
    private final Map<int[], Colors> arrays = new WeakHashMap<>();
    private final Set<Tile> ground = Collections.newSetFromMap(new WeakHashMap<>());
    private WeakReference<Scene> activeScene = new WeakReference<>(null);
    private final BitSet zones = new BitSet();
    private Season previousSeason;
    private boolean previousTerrain, previousFoliage, previousTextureTint, changedModels;
    private int changedTiles, changedTrees;
    private long mutationVersion;

    public SeasonalSceneRecolorer(Client client)
    {
        this.client = client;
    }

    public static boolean supports(Scene scene)
    {
        return scene != null && scene.getWorldViewId() == WorldView.TOPLEVEL && !scene.isInstance();
    }

    public void apply(Scene scene, Season season, boolean terrain, boolean foliage)
    {
        // Face tints preserve texture pixels and cutouts. Winter keeps textured
        // leaves unchanged; whitening them requires a supported material API.
        boolean tintTextures = foliage && season != Season.WINTER && SeasonalTextureTint.supported(client);
        if (scene != activeScene.get() || season != previousSeason
            || terrain != previousTerrain || foliage != previousFoliage || tintTextures != previousTextureTint)
        {
            restore();
        }
        changedTiles = changedTrees = 0;
        ground.clear();
        if (!supports(scene))
        {
            restore();
            return;
        }
        activeScene = new WeakReference<>(scene);
        previousSeason = season;
        previousTerrain = terrain;
        previousFoliage = foliage;
        previousTextureTint = tintTextures;
        Set<GameObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<GameObject> affectedTrees = Collections.newSetFromMap(new IdentityHashMap<>());
        Tile[][][] tiles = scene.getExtendedTiles();
        if (tiles == null || tiles.length == 0 || tiles[0] == null)
        {
            return;
        }
        int width = tiles[0].length;
        BitSet dirty = new BitSet();
        BitSet treeZones = new BitSet();
        boolean treesMutated = false;
        boolean grassMutated = false;
        for (int x = 0; x < width; x++)
        {
            if (tiles[0][x] == null) { continue; }
            for (int y = 0; y < tiles[0][x].length; y++)
            {
                Tile tile = tiles[0][x][y];
                if (!outdoors(scene, tile, x, y)) { continue; }
                long before = mutationVersion;
                if (hasGrass(tile))
                {
                    ground.add(tile);
                    if (terrain && recolorGround(tile, season))
                    {
                        changedTiles++;
                    }
                    if (terrain && season == Season.WINTER && tile.getGroundObject() != null
                        && tile.getGroundObject().getRenderable() instanceof Model)
                    {
                        Model blades = (Model) tile.getGroundObject().getRenderable();
                        short[] textures = blades.getFaceTextures();
                        int[][] channels = {blades.getFaceColors1(), blades.getFaceColors2(), blades.getFaceColors3()};
                        long beforeGrass = mutationVersion;
                        boolean affectedGrass = false;
                        for (int[] channel : channels)
                        {
                            int[] source = original(channel);
                            for (int face = 0; face < channel.length; face++)
                            {
                                // Terrain also includes olive underlays, but
                                // those hues can be brown ground decorations.
                                if ((textures == null || textures[face] < 0)
                                    && (source[face] >>> 10) >= 10)
                                {
                                    affectedGrass |= change(channel, face,
                                        SeasonalGroundColors.ground(source[face], season));
                                }
                            }
                        }
                        if (affectedGrass)
                        {
                            changedModels = true;
                        }
                        grassMutated |= mutationVersion != beforeGrass;
                    }
                }
                int zone = (x >> 3) * zoneWidth(scene) + (y >> 3);
                if (mutationVersion != before) { dirty.set(zone); }
                if (foliage && tile.getGameObjects() != null)
                {
                    for (GameObject object : tile.getGameObjects())
                    {
                        before = mutationVersion;
                        if (object != null && seen.add(object) && recolorTree(object, season, tintTextures))
                        {
                            changedTrees++;
                            changedModels = true;
                            affectedTrees.add(object);
                        }
                        treesMutated |= mutationVersion != before;
                        if (affectedTrees.contains(object)) { markTreeZones(scene, object, treeZones); }
                    }
                }
            }
        }
        // Shared model colors and multi-tile objects can affect several upload zones.
        if (treesMutated) { dirty.or(treeZones); }
        if (grassMutated)
        {
            // Cached ground models can share face colors with placements on
            // roads or under roofs, outside the grass tiles visited above.
            // Rebuild every uploaded copy when those shared arrays change.
            int zoneCount = zoneWidth(scene);
            dirty.set(0, zoneCount * zoneCount);
        }
        zones.or(dirty);
        invalidate(scene, dirty);
    }

    public boolean isSeasonalGround(Tile tile)
    {
        return ground.contains(tile) && hasGrass(tile);
    }

    public int getChangedTiles() { return changedTiles; }
    public int getChangedTrees() { return changedTrees; }

    private boolean outdoors(Scene scene, Tile tile, int x, int y)
    {
        if (tile == null || tile.getPlane() != 0 || tile.getRenderLevel() != 0 || tile.getBridge() != null)
        {
            return false;
        }
        int wx = scene.getBaseX() + tile.getSceneLocation().getX();
        int wy = scene.getBaseY() + tile.getSceneLocation().getY();
        if (!SeasonalWorldArea.contains(wx, wy)) { return false; }
        byte[][][] flags = scene.getExtendedTileSettings();
        if (flags == null) { return false; }
        for (int plane = 1; plane < flags.length; plane++)
        {
            if (flags[plane] == null || x >= flags[plane].length || flags[plane][x] == null
                || y >= flags[plane][x].length) { return false; }
            int value = flags[plane][x][y];
            if ((value & 4) != 0 || (plane == 1 && (value & 2) != 0)) { return false; }
        }
        return true;
    }

    private boolean hasGrass(Tile tile)
    {
        SceneTilePaint paint = tile.getSceneTilePaint();
        if (paint != null)
        {
            if (paint.getTexture() >= 0) { return false; }
            Colors saved = paints.get(paint);
            int[] colors = saved == null ? paintColors(paint) : saved.original;
            return Arrays.stream(colors).allMatch(SeasonalGroundColors::isGrass);
        }
        SceneTileModel model = tile.getSceneTileModel();
        if (model == null || textured(model.getTriangleTextureId())) { return false; }
        int[] a = original(model.getTriangleColorA());
        int[] b = original(model.getTriangleColorB());
        int[] c = original(model.getTriangleColorC());
        for (int i = 0; i < a.length; i++)
        {
            if (SeasonalGroundColors.isGrass(a[i]) && SeasonalGroundColors.isGrass(b[i])
                && SeasonalGroundColors.isGrass(c[i])) { return true; }
        }
        return false;
    }

    private boolean recolorGround(Tile tile, Season season)
    {
        SceneTilePaint paint = tile.getSceneTilePaint();
        if (paint != null)
        {
            int[] current = paintColors(paint);
            Colors colors = paints.computeIfAbsent(paint, ignored -> new Colors(current));
            boolean affected = false;
            for (int i = 0; i < 4; i++)
            {
                affected |= colors.apply(current, i, SeasonalGroundColors.ground(colors.original[i], season));
            }
            if (!Arrays.equals(current, paintColors(paint))) { mutationVersion++; }
            setPaint(paint, current);
            return affected;
        }
        SceneTileModel model = tile.getSceneTileModel();
        int[] a = model.getTriangleColorA(), b = model.getTriangleColorB(), c = model.getTriangleColorC();
        int[] oa = arrays.computeIfAbsent(a, Colors::new).original;
        int[] ob = arrays.computeIfAbsent(b, Colors::new).original;
        int[] oc = arrays.computeIfAbsent(c, Colors::new).original;
        boolean affected = false;
        for (int i = 0; i < a.length; i++)
        {
            if (SeasonalGroundColors.isGrass(oa[i]) && SeasonalGroundColors.isGrass(ob[i])
                && SeasonalGroundColors.isGrass(oc[i]))
            {
                affected |= change(a, i, SeasonalGroundColors.ground(oa[i], season));
                affected |= change(b, i, SeasonalGroundColors.ground(ob[i], season));
                affected |= change(c, i, SeasonalGroundColors.ground(oc[i], season));
            }
        }
        return affected;
    }

    private boolean recolorTree(GameObject object, Season season, boolean tintTextures)
    {
        if (!(object.getRenderable() instanceof Model)) { return false; }
        ObjectComposition definition = client.getObjectDefinition(object.getId());
        if (definition == null || !Arrays.asList("Tree", "Oak", "Oak tree", "Willow", "Willow tree",
            "Yew", "Yew tree", "Maple tree")
            .contains(definition.getName())) { return false; }
        Model model = (Model) object.getRenderable();
        short[] textures = model.getFaceTextures();
        int[][] channels = {model.getFaceColors1(), model.getFaceColors2(), model.getFaceColors3()};
        boolean affected = false;
        for (int[] channel : channels)
        {
            int[] source = original(channel);
            for (int i = 0; i < channel.length; i++)
            {
                if (textures == null || textures[i] < 0)
                {
                    affected |= change(channel, i, SeasonalPalette.foliage(source[i], season));
                }
                else if (tintTextures && SeasonalTextureTint.isLeafTexture(textures[i]))
                {
                    affected |= change(channel, i, SeasonalTextureTint.foliage(source[i], textures[i], season));
                }
            }
        }
        return affected;
    }

    private int[] original(int[] current)
    {
        Colors saved = arrays.get(current);
        return saved == null ? current : saved.original;
    }

    private boolean change(int[] current, int index, int value)
    {
        if (value == current[index] && !arrays.containsKey(current)) { return false; }
        int before = current[index];
        boolean affected = arrays.computeIfAbsent(current, Colors::new).apply(current, index, value);
        if (current[index] != before) { mutationVersion++; }
        return affected;
    }

    public void restore()
    {
        WinterSnowfall.clear(this);
        WinterSurfaceSnow.restore(this);
        WinterTreeFrost.restore(this);
        for (Map.Entry<SceneTilePaint, Colors> entry : paints.entrySet())
        {
            int[] current = paintColors(entry.getKey());
            entry.getValue().restore(current);
            setPaint(entry.getKey(), current);
        }
        for (Map.Entry<int[], Colors> entry : arrays.entrySet()) { entry.getValue().restore(entry.getKey()); }
        WinterCanopySnow.restore(this);
        Scene scene = currentScene();
        if (changedModels && scene != null)
        {
            // Any zone may contain another placement of a cached recolored
            // model, including zones uploaded after the original mutation.
            // Restore their GPU copies even if the Scene object is unchanged.
            BitSet all = new BitSet();
            all.set(0, zoneWidth(scene) * zoneWidth(scene));
            invalidate(scene, all);
        }
        else { invalidate(activeScene.get(), zones); }
        paints.clear();
        arrays.clear();
        ground.clear();
        zones.clear();
        activeScene.clear();
        changedModels = false;
        changedTiles = changedTrees = 0;
    }

    private Scene currentScene()
    {
        WorldView world = client.getTopLevelWorldView();
        return world == null ? null : world.getScene();
    }

    private void markTreeZones(Scene scene, GameObject object, BitSet dirty)
    {
        int width = zoneWidth(scene);
        int offset = (scene.getExtendedTiles()[0].length - Constants.SCENE_SIZE) / 2;
        int minX = Math.max(0, (object.getSceneMinLocation().getX() + offset) >> 3);
        int minY = Math.max(0, (object.getSceneMinLocation().getY() + offset) >> 3);
        int maxX = Math.min(width - 1, (object.getSceneMaxLocation().getX() + offset) >> 3);
        int maxY = Math.min(width - 1, (object.getSceneMaxLocation().getY() + offset) >> 3);
        for (int x = minX; x <= maxX; x++)
        {
            for (int y = minY; y <= maxY; y++) { dirty.set(x * width + y); }
        }
    }

    private int zoneWidth(Scene scene)
    {
        Tile[][][] tiles = scene.getExtendedTiles();
        return tiles == null || tiles.length == 0 || tiles[0] == null ? 0 : (tiles[0].length + 7) >> 3;
    }

    private void invalidate(Scene scene, BitSet dirty)
    {
        DrawCallbacks callbacks = client.getDrawCallbacks();
        if (scene == null || scene != currentScene() || !(callbacks instanceof GpuPlugin)) { return; }
        int width = zoneWidth(scene);
        if (width == 0) { return; }
        for (int bit = dirty.nextSetBit(0); bit >= 0; bit = dirty.nextSetBit(bit + 1))
        {
            callbacks.invalidateZone(scene, bit / width, bit % width);
        }
    }

    private static boolean textured(int[] textures)
    {
        return textures != null && Arrays.stream(textures).anyMatch(texture -> texture >= 0);
    }

    private static int[] paintColors(SceneTilePaint p)
    {
        return new int[]{p.getSwColor(), p.getSeColor(), p.getNeColor(), p.getNwColor()};
    }

    private static void setPaint(SceneTilePaint p, int[] colors)
    {
        p.setSwColor(colors[0]); p.setSeColor(colors[1]); p.setNeColor(colors[2]); p.setNwColor(colors[3]);
    }

    static final class Colors
    {
        private final int[] original, last;
        private final BitSet conflicted = new BitSet();
        Colors(int[] colors) { original = colors.clone(); last = colors.clone(); }

        boolean apply(int[] current, int index, int desired)
        {
            if (current[index] != last[index]) { conflicted.set(index); }
            if (conflicted.get(index)) { return false; }
            current[index] = last[index] = desired;
            return desired != original[index];
        }

        void restore(int[] current)
        {
            for (int i = 0; i < current.length; i++)
            {
                if (!conflicted.get(i) && current[i] == last[i]) { current[i] = original[i]; }
            }
        }
    }
}
