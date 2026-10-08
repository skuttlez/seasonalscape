package com.seasonalscape;

import java.lang.reflect.Proxy;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.CollisionData;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class GroundCoverTest
{
    @Test
    public void springBlossomsAndSummerFlowersHaveDistinctPetalsCentersAndStems()
    {
        for (Season season : new Season[]{Season.SPRING, Season.SUMMER})
        {
            for (int variant = 0; variant < 4; variant++)
            {
                Mesh source = new Mesh(18);
                Mesh target = new Mesh(18);
                GroundCover.reshapeFlakes(source.proxy(), target.proxy(), season, variant);
                for (int face = 0; face < 16; face++)
                {
                    int role = face % 8;
                    int color = target.colors[face] & 65535;
                    int hue = color >>> 10;
                    int saturation = color >>> 7 & 7;
                    assertTrue("Petals and stems retain visible, upward-facing area", area(target, face) > 0);
                    if (role == 5)
                    {
                        assertEquals("Both seasons have warm flower centers", 9, hue);
                        assertTrue(saturation >= 3);
                    }
                    else if (season == Season.SUMMER && role >= 6)
                    {
                        assertEquals("Summer has green stems", 20, hue);
                        float minY = Math.min(target.y[face * 3], Math.min(target.y[face * 3 + 1], target.y[face * 3 + 2]));
                        float maxY = Math.max(target.y[face * 3], Math.max(target.y[face * 3 + 1], target.y[face * 3 + 2]));
                        assertTrue("Stem reaches its flower head", minY < -17);
                        assertEquals("Stem starts at the ground", -0.25f, maxY, 0.0001f);
                    }
                    else if (season == Season.SUMMER)
                    {
                        assertTrue("Summer petals are white or yellow", saturation == 0 || hue == 10);
                        assertTrue("Summer flower heads stand above grass", target.y[face * 3] <= -18);
                    }
                    else
                    {
                        assertTrue("Spring petals are white, blush or lavender",
                            saturation == 0 || hue == 61 || hue == 47);
                        assertTrue("Spring blossoms sit close to the grass", target.y[face * 3] > -3);
                    }
                }
                for (int vertex = 0; vertex < target.x.length; vertex++)
                {
                    assertTrue("Flowers and full diagonal position jitter stay within one tile",
                        Math.hypot(target.x[vertex], target.z[vertex]) + Math.hypot(8, 8) < 64);
                    assertTrue("Flowers remain low and clear of the ground", target.y[vertex] <= 0 && target.y[vertex] >= -26);
                }
                for (int face = 16; face < target.a.length; face++)
                {
                    assertEquals("Excess components remain collapsed", 0, area(target, face), 0);
                }
                assertTrue("The two flowers have different petal colors", target.colors[0] != target.colors[8]);
                assertArrayEquals(source.a, target.a);
                assertArrayEquals(source.b, target.b);
                assertArrayEquals(source.c, target.c);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void seasonChangesReplaceGroundCoverAndKeepPlacementProtection() throws Exception
    {
        Tile[][][] tiles = new Tile[2][104][104];
        int[][] flags = new int[104][104];
        int[][][] heights = new int[1][105][105];
        boolean[] instance = {false};
        Scene scene = (Scene) Proxy.newProxyInstance(Scene.class.getClassLoader(),
            new Class<?>[]{Scene.class}, (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "getTiles": return tiles;
                    case "getBaseX": return 3200;
                    case "getBaseY": return 3200;
                    case "isInstance": return instance[0];
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        CollisionData collision = (CollisionData) Proxy.newProxyInstance(CollisionData.class.getClassLoader(),
            new Class<?>[]{CollisionData.class}, (proxy, method, args) -> {
                if (method.getName().equals("getFlags")) { return flags; }
                throw new UnsupportedOperationException(method.getName());
            });
        WorldView world = (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(),
            new Class<?>[]{WorldView.class}, (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "getId": return -1;
                    case "getPlane": return 0;
                    case "isTopLevel": return true;
                    case "isInstance": return instance[0];
                    case "getScene": return scene;
                    case "getCollisionMaps": return new CollisionData[]{collision};
                    case "getTileHeights": return heights;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        LocalPoint point = new LocalPoint(192, 192, -1);
        LocalPoint[] playerPoint = {point};
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
            new Class<?>[]{Player.class}, (proxy, method, args) -> {
                if (method.getName().equals("getLocalLocation")) { return playerPoint[0]; }
                throw new UnsupportedOperationException(method.getName());
            });
        SceneTilePaint paint = (SceneTilePaint) Proxy.newProxyInstance(SceneTilePaint.class.getClassLoader(),
            new Class<?>[]{SceneTilePaint.class}, (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    case "getTexture": return -1;
                    case "getSwColor": case "getSeColor": case "getNeColor": case "getNwColor":
                        return 20 << 10 | 4 << 7 | 50;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        Tile tile = (Tile) Proxy.newProxyInstance(Tile.class.getClassLoader(),
            new Class<?>[]{Tile.class}, (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    case "getPlane": case "getRenderLevel": return 0;
                    case "getLocalLocation": return point;
                    case "getSceneTilePaint": return paint;
                    case "getBridge": case "getWallObject": case "getDecorativeObject":
                    case "getItemLayer": case "getGameObjects": return null;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        tiles[0][1][1] = tile;
        Mesh source = new Mesh(18);
        List<Boolean> activation = new ArrayList<>();
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
            new Class<?>[]{Client.class}, (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "getTopLevelWorldView": return world;
                    case "getLocalPlayer": return player;
                    case "getGameState": return GameState.LOGGED_IN;
                    case "loadModelData": return source.proxy();
                    case "mergeModels": return ((ModelData[]) args[0])[0];
                    case "createRuneLiteObject":
                        return new RuneLiteObject((Client) proxy)
                        {
                            @Override public void setModel(Model model) { }
                            @Override public void setLocation(LocalPoint location, int plane) { }
                            @Override public int getZ() { return 0; }
                            @Override public void setZ(int z) { }
                            @Override public void setOrientation(int orientation) { }
                            @Override public void setActive(boolean active) { activation.add(active); }
                        };
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        GroundCover cover = new GroundCover(client);
        SeasonalSceneRecolorer recolorer = new SeasonalSceneRecolorer(client);
        Field groundField = SeasonalSceneRecolorer.class.getDeclaredField("ground");
        groundField.setAccessible(true);
        Set<Tile> ground = (Set<Tile>) groundField.get(recolorer);
        ground.add(tile);
        for (Season season : new Season[]{Season.SPRING, Season.SUMMER, Season.AUTUMN})
        {
            cover.update(scene, recolorer, season, true, 100);
            assertEquals("Spring, summer and autumn place one eligible decoration", 1, cover.getCount());
        }
        assertEquals("Season changes deactivate old geometry before activating new geometry",
            Arrays.asList(true, false, true, false, true), activation);
        cover.update(scene, recolorer, Season.WINTER, true, 100);
        assertEquals(0, cover.getCount());
        flags[1][1] = 1;
        cover.update(scene, recolorer, Season.SUMMER, true, 100);
        assertEquals("Collision blocks wildflowers", 0, cover.getCount());
        flags[1][1] = 0;
        tiles[1][1][1] = tile;
        cover.update(scene, recolorer, Season.SUMMER, true, 100);
        assertEquals("Roofed ground remains protected", 0, cover.getCount());
        tiles[1][1][1] = null;
        heights[0][2][2] = 65;
        cover.update(scene, recolorer, Season.SUMMER, true, 100);
        assertEquals("Steep terrain remains protected", 0, cover.getCount());
        heights[0][2][2] = 0;
        ground.clear();
        cover.update(scene, recolorer, Season.SUMMER, true, 100);
        assertEquals("Unsupported or non-grass tiles remain protected", 0, cover.getCount());
        ground.add(tile);
        cover.update(scene, recolorer, Season.SUMMER, true, 100);
        assertEquals(1, cover.getCount());
        instance[0] = true;
        cover.update(scene, recolorer, Season.SUMMER, true, 100);
        assertEquals("Entering an instance removes flowers", 0, cover.getCount());
        instance[0] = false;
        cover.update(scene, recolorer, Season.SUMMER, true, 100);
        cover.update(scene, recolorer, Season.SUMMER, true, 0);
        assertEquals("Zero density removes flowers", 0, cover.getCount());

        // A full loaded scene must retain the exact same world-anchored piles
        // when the player walks across it, without re-lighting their geometry.
        for (int x = 1; x < 103; x++)
        {
            for (int y = 1; y < 103; y++)
            {
                tiles[0][x][y] = grassTile(new LocalPoint(x * 128 + 64, y * 128 + 64, -1), paint);
                ground.add(tiles[0][x][y]);
            }
        }
        cover.update(scene, recolorer, Season.AUTUMN, true, 30);
        assertEquals("Autumn is bounded by the existing global object cap", 200, cover.getCount());
        Field objectsField = GroundCover.class.getDeclaredField("objects");
        objectsField.setAccessible(true);
        Map<Long, RuneLiteObject> objects = (Map<Long, RuneLiteObject>) objectsField.get(cover);
        Map<Long, RuneLiteObject> beforeWalk = new HashMap<>(objects);
        Set<String> sectors = new HashSet<>();
        boolean farFromPlayer = false;
        for (long key : objects.keySet())
        {
            int worldX = (int) (key >> 32), worldY = (int) key;
            sectors.add(worldX / 8 + ":" + worldY / 8);
            farFromPlayer |= worldX > 3290 && worldY > 3290;
        }
        assertTrue("Autumn reaches the far side of the scene beyond the old ten-tile circle", farFromPlayer);
        assertEquals("Every eligible world sector receives a pile before any gets more", 169, sectors.size());
        Field selectionField = GroundCover.class.getDeclaredField("autumnCandidates");
        selectionField.setAccessible(true);
        Object selection = selectionField.get(cover);
        int copiesBeforeWalk = source.copies.size();
        playerPoint[0] = new LocalPoint(100 * 128 + 64, 100 * 128 + 64, -1);
        cover.update(scene, recolorer, Season.AUTUMN, true, 30);
        assertEquals("Walking retains every pile object and its world position", beforeWalk, objects);
        assertTrue("Walking reuses the cached scene selection", selection == selectionField.get(cover));
        assertEquals("Walking creates no new pile geometry", copiesBeforeWalk, source.copies.size());

        ground.clear();
        ground.add(tiles[0][2][2]);
        ground.add(tiles[0][54][54]);
        ground.add(tiles[0][99][99]);
        cover.invalidate();
        cover.update(scene, recolorer, Season.AUTUMN, true, 100);
        assertEquals("Disconnected eligible patches throughout the scene all receive piles", 3, cover.getCount());
        flags[99][99] = 1;
        cover.update(scene, recolorer, Season.AUTUMN, true, 100);
        assertEquals("Cached autumn placements are removed when collision changes", 2, cover.getCount());
        flags[99][99] = 0;
        cover.invalidate();
        cover.update(scene, recolorer, Season.AUTUMN, true, 100);
        assertEquals("Object refresh makes newly eligible ground available again", 3, cover.getCount());
        for (Season season : new Season[]{Season.SPRING, Season.SUMMER})
        {
            cover.update(scene, recolorer, season, true, 100);
            assertEquals("Flower seasons retain their nearby range", 1, cover.getCount());
        }
    }

    private static Tile grassTile(LocalPoint point, SceneTilePaint paint)
    {
        return (Tile) Proxy.newProxyInstance(Tile.class.getClassLoader(),
            new Class<?>[]{Tile.class}, (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    case "getPlane": case "getRenderLevel": return 0;
                    case "getLocalLocation": return point;
                    case "getSceneTilePaint": return paint;
                    case "getBridge": case "getWallObject": case "getDecorativeObject":
                    case "getItemLayer": case "getGameObjects": return null;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
    }

    @Test
    @SuppressWarnings("unchecked")
    public void winterRemovesAutumnPilesBeforeSceneOrPlayerIsAvailable() throws Exception
    {
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
            new Class<?>[]{Client.class}, (proxy, method, args) -> {
                if (method.getName().equals("getTopLevelWorldView")
                    || method.getName().equals("getLocalPlayer")) { return null; }
                throw new AssertionError("Winter must clear decorations without loading the scene: "
                    + method.getName());
            });
        List<Boolean> activation = new ArrayList<>();
        GroundCover cover = new GroundCover(client);
        Field objectsField = GroundCover.class.getDeclaredField("objects");
        objectsField.setAccessible(true);
        Map<Long, RuneLiteObject> objects = (Map<Long, RuneLiteObject>) objectsField.get(cover);
        for (long key = 0; key < 2; key++)
        {
            objects.put(key, new RuneLiteObject(client)
            {
                @Override public void setActive(boolean active) { activation.add(active); }
            });
        }
        assertEquals(2, cover.getCount());
        cover.update(null, null, Season.WINTER, true, 30);
        assertEquals("Both existing autumn piles are unregistered", Arrays.asList(false, false), activation);
        assertEquals(0, cover.getCount());
    }

    @Test
    public void terrainHeightInterpolatesBothAxesAndRejectsMissingCorners()
    {
        int[][] heights = {{0, 40}, {80, 200}};
        assertEquals(0, GroundCoverPlacement.height(heights, 0, 0), 0);
        assertEquals(80, GroundCoverPlacement.height(heights, 64, 64), 0);
        assertEquals(65, GroundCoverPlacement.height(heights, 32, 96), 0);
        assertTrue(Float.isNaN(GroundCoverPlacement.height(heights, -1, 64)));
        assertTrue(Float.isNaN(GroundCoverPlacement.height(heights, 128, 64)));
        assertTrue(Float.isNaN(GroundCoverPlacement.height(new int[][]{{0}, {0}}, 0, 0)));
    }

    @Test
    public void placedDecorationsFollowTheSlopeAtTheirJitteredPosition()
    {
        int[][] heights = new int[3][3];
        for (int x = 0; x < heights.length; x++)
        {
            for (int y = 0; y < heights[x].length; y++)
            {
                heights[x][y] = -400 + 32 * x - 16 * y;
            }
        }
        LocalPoint location = new LocalPoint(192 + 7, 192 - 5, -1);
        for (Season season : new Season[]{Season.SPRING, Season.SUMMER, Season.AUTUMN, Season.WINTER})
        {
            for (int variant = 0; variant < 4; variant++)
            {
                // The cache model has 18 components, with 16 visible in each layer.
                Mesh source = new Mesh(18);
                float[] originalX = source.x.clone();
                float[] originalY = source.y.clone();
                float[] originalZ = source.z.clone();
                short[] originalColors = source.colors.clone();
                List<ModelData[]> mergedLayers = new ArrayList<>();
                Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
                    new Class<?>[]{Client.class}, (proxy, method, args) -> {
                        if (method.getName().equals("loadModelData"))
                        {
                            return source.proxy();
                        }
                        if (method.getName().equals("mergeModels"))
                        {
                            ModelData[] layers = (ModelData[]) args[0];
                            mergedLayers.add(layers.clone());
                            return layers[0]; // Lighting is stubbed; inspect every input below.
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
                assertNotNull(GroundCoverPlacement.model(client, season, variant, location, heights));
                int expectedLayers = season == Season.AUTUMN ? 4 : 1;
                assertEquals(expectedLayers, source.copies.size());
                if (season == Season.AUTUMN)
                {
                    assertEquals("The complete pile is merged into one decoration", 1, mergedLayers.size());
                }
                for (ModelData[] layers : mergedLayers)
                {
                    assertEquals(expectedLayers, layers.length);
                    for (int layer = 0; layer < layers.length; layer++)
                    {
                        assertTrue(layers[layer].getVerticesX() == source.copies.get(layer).x);
                    }
                }
                int visibleLeaves = 0;
                for (int layer = 0; layer < expectedLayers; layer++)
                {
                    Mesh flat = new Mesh(18);
                    GroundCover.reshapeFlakes(source.proxy(), flat.proxy(), season, variant + layer * 4);
                    Mesh placed = source.copies.get(layer);
                    for (int vertex = 0; vertex < placed.y.length; vertex++)
                    {
                        assertEquals(flat.y[vertex] + flat.x[vertex] / 4 - flat.z[vertex] / 8
                            - layer * 0.8f, placed.y[vertex], 0.0001f);
                    }
                    for (int face = 0; face < placed.a.length; face++)
                    {
                        if (face < 16)
                        {
                            assertTrue("Slope fitting preserves upward face winding", area(placed, face) > 0);
                            visibleLeaves++;
                        }
                        else
                        {
                            assertEquals("Unused components remain collapsed", 0, area(placed, face), 0);
                        }
                    }
                    assertArrayEquals(flat.x, placed.x, 0);
                    assertArrayEquals(flat.z, placed.z, 0);
                    assertArrayEquals(source.a, placed.a);
                    assertArrayEquals(source.b, placed.b);
                    assertArrayEquals(source.c, placed.c);
                    if (layer > 0)
                    {
                        Mesh first = source.copies.get(0);
                        assertTrue("Additional layers scatter leaves into new positions",
                            !Arrays.equals(first.x, placed.x) || !Arrays.equals(first.z, placed.z));
                    }
                }
                assertEquals(season == Season.AUTUMN ? 64 : 16, visibleLeaves);
                assertArrayEquals(originalX, source.x, 0);
                assertArrayEquals(originalY, source.y, 0);
                assertArrayEquals(originalZ, source.z, 0);
                assertArrayEquals(originalColors, source.colors);
            }
        }
    }

    @Test
    public void untexturedCacheModelCreatesEveryVariantWithoutChangingTheSource() throws Exception
    {
        for (Season season : new Season[]{Season.SPRING, Season.SUMMER, Season.AUTUMN, Season.WINTER})
        {
            Mesh source = new Mesh(3);
            float[] originalX = source.x.clone();
            float[] originalY = source.y.clone();
            float[] originalZ = source.z.clone();
            short[] originalColors = source.colors.clone();
            Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
                new Class<?>[]{Client.class}, (proxy, method, args) -> {
                    if (method.getName().equals("loadModelData"))
                    {
                        return source.proxy();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
            Method createModels = GroundCover.class.getDeclaredMethod("createModels", Season.class);
            createModels.setAccessible(true);
            Model[] models = (Model[]) createModels.invoke(new GroundCover(client), season);

            assertEquals(4, models.length);
            assertEquals(4, source.copies.size());
            for (int i = 0; i < models.length; i++)
            {
                assertNotNull(models[i]);
                Mesh copy = source.copies.get(i);
                assertNotSame(source.colors, copy.colors);
                assertNotSame(source.x, copy.x);
                assertArrayEquals(new byte[source.a.length], copy.transparencies);
                for (int face = 0; face < copy.a.length; face++)
                {
                    assertTrue("Lit ground cover has a visible face", area(copy, face) > 0);
                    assertTrue("Ground cover receives seasonal colors", copy.colors[face] != 0);
                }
            }
            assertArrayEquals(originalX, source.x, 0);
            assertArrayEquals(originalY, source.y, 0);
            assertArrayEquals(originalZ, source.z, 0);
            assertArrayEquals(originalColors, source.colors);
        }
    }

    @Test
    public void flakesStayInsideTheirTileAndLieOnTheGround()
    {
        for (Season season : new Season[]{Season.AUTUMN, Season.WINTER})
        {
            for (int variant = 0; variant < 4; variant++)
            {
                Mesh source = new Mesh(16);
                Mesh target = new Mesh(16);
                float[] originalX = source.x.clone();
                float[] originalY = source.y.clone();
                float[] originalZ = source.z.clone();
                GroundCover.reshapeFlakes(source.proxy(), target.proxy(), season, variant);
                for (int i = 0; i < target.x.length; i++)
                {
                    // Rotation cannot increase radius. Even the largest 8-unit
                    // position jitter leaves the complete mesh within a tile.
                    assertTrue(Math.hypot(target.x[i], target.z[i]) + 8 < 64);
                    float lowestY = season == Season.AUTUMN ? -1.7f : -1.25f;
                    assertTrue(target.y[i] <= -0.25f && target.y[i] >= lowestY);
                }
                for (int face = 0; face < target.a.length; face++)
                {
                    assertTrue("Flake faces upward and has nonzero area", area(target, face) > 0);
                }
                assertArrayEquals(originalX, source.x, 0);
                assertArrayEquals(originalY, source.y, 0);
                assertArrayEquals(originalZ, source.z, 0);
                assertArrayEquals(source.a, target.a);
                assertArrayEquals(source.b, target.b);
                assertArrayEquals(source.c, target.c);
            }
        }
    }

    @Test
    public void excessComponentsCollapseWithoutChangingTopology()
    {
        Mesh source = new Mesh(24);
        Mesh target = new Mesh(24);
        GroundCover.reshapeFlakes(source.proxy(), target.proxy(), Season.WINTER, 0);
        int visible = 0;
        for (int face = 0; face < target.a.length; face++)
        {
            if (area(target, face) > 0)
            {
                visible++;
            }
        }
        assertEquals(16, visible);
        assertArrayEquals(source.a, target.a);
        assertArrayEquals(source.b, target.b);
        assertArrayEquals(source.c, target.c);
    }

    private static double area(Mesh mesh, int face)
    {
        int a = mesh.a[face], b = mesh.b[face], c = mesh.c[face];
        return (mesh.x[b] - mesh.x[a]) * (mesh.z[c] - mesh.z[a])
            - (mesh.z[b] - mesh.z[a]) * (mesh.x[c] - mesh.x[a]);
    }

    /** Independent triangles in three different source planes. */
    private static final class Mesh
    {
        private float[] x, y, z;
        private final int[] a, b, c;
        private short[] colors;
        private byte[] transparencies;
        private final List<Mesh> copies = new ArrayList<>();

        private Mesh(int components)
        {
            x = new float[components * 3];
            y = new float[components * 3];
            z = new float[components * 3];
            a = new int[components];
            b = new int[components];
            c = new int[components];
            colors = new short[components];
            float[][] axes = {x, y, z};
            for (int i = 0; i < components; i++)
            {
                a[i] = i * 3;
                b[i] = i * 3 + 1;
                c[i] = i * 3 + 2;
                int first = i % 3, second = (i + 1) % 3;
                axes[first][b[i]] = 1000;
                axes[second][c[i]] = -500;
            }
        }

        private Mesh(Mesh source)
        {
            x = source.x;
            y = source.y;
            z = source.z;
            a = source.a;
            b = source.b;
            c = source.c;
            colors = source.colors;
            transparencies = source.transparencies;
        }

        private ModelData proxy()
        {
            return (ModelData) Proxy.newProxyInstance(ModelData.class.getClassLoader(),
                new Class<?>[]{ModelData.class}, (proxy, method, args) -> {
                    switch (method.getName())
                    {
                        case "getVerticesCount": return x.length;
                        case "getFaceCount": return a.length;
                        case "getVerticesX": return x;
                        case "getVerticesY": return y;
                        case "getVerticesZ": return z;
                        case "getFaceIndices1": return a;
                        case "getFaceIndices2": return b;
                        case "getFaceIndices3": return c;
                        case "getFaceColors": return colors;
                        case "getFaceTextures": return null;
                        case "getFaceTransparencies": return transparencies;
                        case "shallowCopy":
                            Mesh copy = new Mesh(this);
                            copies.add(copy);
                            return copy.proxy();
                        case "cloneVertices":
                            x = x.clone();
                            y = y.clone();
                            z = z.clone();
                            return proxy;
                        case "cloneColors":
                            colors = colors.clone();
                            return proxy;
                        case "cloneTextures":
                            // The real client cannot clone a missing texture array.
                            throw new NullPointerException("Untextured model has no texture array");
                        case "cloneTransparencies":
                            transparencies = transparencies == null
                                ? new byte[a.length] : transparencies.clone();
                            return proxy;
                        case "translate": return proxy;
                        case "light":
                            return Proxy.newProxyInstance(Model.class.getClassLoader(),
                                new Class<?>[]{Model.class}, (model, modelMethod, modelArgs) -> {
                                    throw new UnsupportedOperationException(modelMethod.getName());
                                });
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
        }
    }
}
