package com.seasonalscape;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import net.runelite.api.Constants;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Point;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.plugins.gpu.GpuPlugin;
import org.junit.Test;
import static org.junit.Assert.*;

public class WinterSurfaceSnowTest
{
    @Test
    public void disabledSnowNeverScansTheSceneOrLoadsModels()
    {
        Client client = proxy(Client.class, (name, args) -> {
            throw new AssertionError("Disabled structure snow must not call Client." + name);
        });
        Scene scene = proxy(Scene.class, (name, args) -> {
            throw new AssertionError("Disabled structure snow must not call Scene." + name);
        });
        Object owner = new Object();
        for (int tick = 0; tick < 3; tick++)
        {
            WinterSurfaceSnow.update(owner, client, scene, false);
            assertEquals(0, WinterSurfaceSnow.getCount(owner));
        }
    }

    @Test
    public void ignoresPooledArrayTailsInvalidVerticesAndShortOptionalChannels()
    {
        float[] x = {0, 10, 0, 20, 30, 20};
        float[] y = {-10, -10, -10, -10, -10, -10};
        float[] z = {0, 0, 10, 0, 0, 10};
        int[] a = {0, 3, 0}, b = {1, 4, 1}, c = {2, 5, 2};
        int[] counts = {2, 3};
        byte[][] alpha = {null};
        Model model = proxy(Model.class, (name, args) -> {
            switch (name)
            {
                case "getVerticesX": return x; case "getVerticesY": return y; case "getVerticesZ": return z;
                case "getFaceIndices1": return a; case "getFaceIndices2": return b; case "getFaceIndices3": return c;
                case "getFaceColors3": return new int[]{0, 0, 0};
                case "getFaceCount": return counts[0];
                case "getVerticesCount": return counts[1];
                case "getFaceTransparencies": return alpha[0];
                default: return null;
            }
        });
        assertEquals("Inactive vertices and unused face storage must not create snow", 1,
            WinterSurfaceSnow.surfaces(model, false).size());
        counts[0] = 3;
        counts[1] = 6;
        alpha[0] = new byte[]{0};
        assertEquals("Short optional channels bound the supported faces", 1,
            WinterSurfaceSnow.surfaces(model, false).size());
        counts[0] = 2;
        alpha[0] = null;
        a[1] = -1;
        assertEquals("Invalid indices are ignored", 1, WinterSurfaceSnow.surfaces(model, false).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void disablingSharedRoofsRestoresMaterialsAndInvalidatesTheReplacementGpuScene() throws Exception
    {
        Tile[][][] oldTiles = new Tile[4][Constants.EXTENDED_SCENE_SIZE][Constants.EXTENDED_SCENE_SIZE];
        Tile[][][] currentTiles = new Tile[4][32][32];
        Scene oldScene = proxy(Scene.class,
            (name, args) -> name.equals("getExtendedTiles") ? oldTiles : name.startsWith("getBase") ? 3200 : null);
        Scene currentScene = proxy(Scene.class,
            (name, args) -> name.equals("getExtendedTiles") ? currentTiles : null);
        RecordingGpu gpu = new RecordingGpu();
        WorldView world = proxy(WorldView.class, (name, args) -> name.equals("getScene") ? currentScene : null);
        Client client = proxy(Client.class, (name, args) -> name.equals("getDrawCallbacks") ? gpu
            : name.equals("getTopLevelWorldView") ? world : null);
        Class<?> stateType = Class.forName("com.seasonalscape.WinterSurfaceSnow$State");
        Constructor<?> constructor = stateType.getDeclaredConstructor(Client.class, Scene.class);
        constructor.setAccessible(true);
        Object state = constructor.newInstance(client, oldScene);
        Object owner = new Object();
        Field statesField = WinterSurfaceSnow.class.getDeclaredField("STATES");
        statesField.setAccessible(true);
        ((Map<Object, Object>) statesField.get(null)).put(owner, state);
        int[] colors = {100};
        short[] textures = {45};
        Model roof = proxy(Model.class, (name, args) -> {
            switch (name)
            {
                case "getVerticesX": return new float[]{0, 10, 0};
                case "getVerticesY": return new float[]{0, 0, 0};
                case "getVerticesZ": return new float[]{0, 0, 10};
                case "getVerticesCount": return 3;
                case "getFaceCount": return 1;
                case "getFaceIndices1": return new int[]{0};
                case "getFaceIndices2": return new int[]{1};
                case "getFaceIndices3": return new int[]{2};
                case "getFaceColors1": case "getFaceColors2": case "getFaceColors3": return colors;
                case "getFaceTextures": return textures;
                default: return null;
            }
        });
        try
        {
            assertEquals(3, WinterRoofMaterial.apply(state, roof));
            WinterSurfaceSnow.update(owner, client, oldScene, false);
            assertEquals(0, WinterSurfaceSnow.getCount(owner));
            assertArrayEquals(new int[]{100}, colors);
            assertArrayEquals(new short[]{45}, textures);
            assertEquals("Use the replacement scene's dimensions", 16, gpu.zones.size());
            for (Scene invalidated : gpu.scenes)
            {
                assertSame("Refresh GPU copies in the current scene", currentScene, invalidated);
            }
        }
        finally { WinterSurfaceSnow.restore(owner); }
    }

    @Test
    public void selectsExposedTopsAndSlopingRoofsWithoutEditingSourceGeometry()
    {
        float[] x = {0, 10, 0, 20, 20, 20, 30, 30, 40, 40, 50, 40};
        float[] y = {-10, -10, -10, 0, -10, 0, -10, -10, -10, -10, 0, -10};
        float[] z = {0, 0, 10, 0, 0, 10, 0, 10, 0, 0, 0, 10};
        int[] a = {0, 3, 6, 9}, b = {1, 4, 7, 10}, c = {2, 5, 8, 11};
        float[] sourceX = x.clone(), sourceY = y.clone(), sourceZ = z.clone();
        Model model = model(x, y, z, a, b, c, new int[]{0, 0, 0, 0}, null);
        List<WinterSurfaceSnow.Triangle> tops = WinterSurfaceSnow.surfaces(model, false);
        assertEquals("Only the horizontal upward face should receive object-top snow", 1, tops.size());
        assertEquals(-1, tops.get(0).ny, 0.001);
        assertEquals("A sloping roof also catches snow", 2, WinterSurfaceSnow.surfaces(model, true).size());
        assertArrayEquals(sourceX, x, 0);
        assertArrayEquals(sourceY, y, 0);
        assertArrayEquals(sourceZ, z, 0);
        assertArrayEquals(new int[]{0, 3, 6, 9}, a);
    }

    @Test
    public void skipsHiddenFoliageAndSurfacesShelteredByAChairSeat()
    {
        float[] x = {0, 10, 0, 0, 10, 0, 20, 30, 20, 40, 50, 40};
        float[] y = {-30, -30, -30, -10, -10, -10, -20, -20, -20, -20, -20, -20};
        float[] z = {0, 0, 10, 0, 0, 10, 0, 0, 10, 0, 0, 10};
        Model model = model(x, y, z, new int[]{0, 3, 6, 9}, new int[]{1, 4, 7, 10},
            new int[]{2, 5, 8, 11}, new int[]{0, 0, -2, 0}, new short[]{-1, -1, -1, 8});
        List<WinterSurfaceSnow.Triangle> surfaces = WinterSurfaceSnow.surfaces(model, false);
        assertEquals(1, surfaces.size());
        assertEquals("The seat shelters surfaces beneath it", -30, surfaces.get(0).xyz[1], 0);
    }

    @Test
    public void overlayUsesExactThinTrianglesAndNeverExceedsItsTemplateCapacity()
    {
        float[] x = new float[179], y = new float[179], z = new float[179];
        int[] a = new int[384], b = new int[384], c = new int[384];
        short[] colors = new short[384], textures = new short[384];
        byte[] alpha = new byte[384];
        int[] activeVertices = {179};
        ModelData data = proxy(ModelData.class, (name, args) -> {
            switch (name)
            {
                case "getVerticesX": return x;
                case "getVerticesY": return y;
                case "getVerticesZ": return z;
                case "getFaceIndices1": return a;
                case "getFaceIndices2": return b;
                case "getFaceIndices3": return c;
                case "getFaceColors": return colors;
                case "getFaceTextures": return textures;
                case "getFaceTransparencies": return alpha;
                case "getVerticesCount": return activeVertices[0];
                case "getFaceCount": return a.length;
                default: return null;
            }
        });
        WinterSurfaceSnow.Triangle triangle = new WinterSurfaceSnow.Triangle(0, -10, 0, 10, -10, 0, 0, -10, 10);
        float[] source = triangle.xyz.clone();
        List<WinterSurfaceSnow.Triangle> surfaces = new ArrayList<>();
        for (int i = 0; i < 80; i++) { surfaces.add(triangle); }
        assertEquals(59, WinterSurfaceSnow.writeTriangles(data, surfaces));
        assertArrayEquals(source, triangle.xyz, 0);
        for (int face = 0; face < 59; face++)
        {
            assertEquals(face * 3, a[face]);
            assertEquals(face * 3 + 1, b[face]);
            assertEquals(face * 3 + 2, c[face]);
            assertEquals(-10.9, y[a[face]], 0.001);
            assertEquals(0, x[a[face]], 0);
            assertEquals(10, x[b[face]], 0);
            assertEquals(10, z[c[face]], 0);
            assertEquals(0, alpha[face]);
        }
        for (int face = 59; face < a.length; face++)
        {
            assertEquals(0, a[face]); assertEquals(0, b[face]); assertEquals(0, c[face]);
            assertEquals(255, alpha[face] & 255);
        }
        for (short texture : textures) { assertEquals(-1, texture); }
        activeVertices[0] = 30;
        assertEquals("Merged geometry can have fewer active vertices than its array capacity",
            10, WinterSurfaceSnow.writeTriangles(data, surfaces));
        for (int face = 0; face < 10; face++)
        {
            assertTrue(a[face] < 30 && b[face] < 30 && c[face] < 30);
        }
    }

    @Test
    public void emptyUpperTilesDoNotShelterObjectsButRoofsFloorsAndFlagsDo()
    {
        Tile[][][] tiles = new Tile[4][Constants.SCENE_SIZE][Constants.SCENE_SIZE];
        byte[][][] flags = new byte[4][Constants.EXTENDED_SCENE_SIZE][Constants.EXTENDED_SCENE_SIZE];
        Scene scene = proxy(Scene.class, (name, args) -> name.equals("getExtendedTiles") ? tiles
            : name.equals("getExtendedTileSettings") ? flags : null);
        tiles[1][10][10] = proxy(Tile.class, (name, args) -> null);
        assertTrue(WinterSurfaceSnow.exposed(scene, 0, 10, 10));
        SceneTilePaint hidden = proxy(SceneTilePaint.class, (name, args) -> name.equals("getNeColor") ? 12345678 : null);
        tiles[1][10][10] = proxy(Tile.class, (name, args) -> name.equals("getSceneTilePaint") ? hidden : null);
        assertTrue(WinterSurfaceSnow.exposed(scene, 0, 10, 10));
        SceneTilePaint floor = proxy(SceneTilePaint.class, (name, args) -> name.equals("getNeColor") ? 7200 : null);
        tiles[1][10][10] = proxy(Tile.class, (name, args) -> name.equals("getSceneTilePaint") ? floor : null);
        assertFalse(WinterSurfaceSnow.exposed(scene, 0, 10, 10));
        GameObject roof = proxy(GameObject.class, (name, args) -> name.equals("getConfig") ? 402 : null);
        tiles[1][10][10] = proxy(Tile.class, (name, args) -> name.equals("getGameObjects") ? new GameObject[]{roof} : null);
        assertFalse("Unnamed roof objects provide cover", WinterSurfaceSnow.exposed(scene, 0, 10, 10));
        tiles[1][10][10] = null;
        int offset = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2;
        flags[1][10 + offset][10 + offset] = Constants.TILE_FLAG_UNDER_ROOF;
        assertFalse(WinterSurfaceSnow.exposed(scene, 0, 10, 10));
        assertTrue("Upper interior floors remain indoors on their own plane", WinterSurfaceSnow.underRoof(scene, 1, 10, 10));
    }

    @Test
    public void extendedSceneCoverUsesNormalCoordinatesEvenOutsideTheSceneBoundary()
    {
        Tile[][][] tiles = new Tile[4][Constants.EXTENDED_SCENE_SIZE][Constants.EXTENDED_SCENE_SIZE];
        byte[][][] flags = new byte[4][Constants.EXTENDED_SCENE_SIZE][Constants.EXTENDED_SCENE_SIZE];
        Scene scene = proxy(Scene.class, (name, args) -> name.equals("getExtendedTiles") ? tiles
            : name.equals("getExtendedTileSettings") ? flags : null);
        int offset = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2;
        int x = -15, y = -9;
        assertTrue(WinterSurfaceSnow.exposed(scene, 0, x, y));
        SceneTilePaint floor = proxy(SceneTilePaint.class, (name, args) -> name.equals("getNeColor") ? 7200 : null);
        tiles[1][x + offset][y + offset] = proxy(Tile.class,
            (name, args) -> name.equals("getSceneTilePaint") ? floor : null);
        assertFalse("Upper cover at negative scene coordinates still shelters objects",
            WinterSurfaceSnow.exposed(scene, 0, x, y));
        assertTrue("The neighboring column remains exposed", WinterSurfaceSnow.exposed(scene, 0, x + 1, y));
        tiles[1][x + offset][y + offset] = null;
        flags[1][x + offset][y + offset] = Constants.TILE_FLAG_UNDER_ROOF;
        assertFalse("Roof flags use the same world column", WinterSurfaceSnow.exposed(scene, 0, x, y));
        assertTrue(WinterSurfaceSnow.underRoof(scene, 1, x, y));
    }

    @Test
    public void roofScanIsBoundedNearbyAndIdleUntilTheViewChangesAndDisablingRestoresEverything() throws Exception
    {
        verifyRoofScanBudgetsAndRestoration(3200, 3300);
    }

    @Test
    public void expandedOverworldRoofSnowKeepsTheSameVisibilityAndWorkBudgets() throws Exception
    {
        verifyRoofScanBudgetsAndRestoration(1640, 3680);
    }

    private static void verifyRoofScanBudgetsAndRestoration(int baseX, int baseY) throws Exception
    {
        Tile[][][] tiles = new Tile[4][Constants.EXTENDED_SCENE_SIZE][Constants.EXTENDED_SCENE_SIZE];
        byte[][][] flags = new byte[4][Constants.EXTENDED_SCENE_SIZE][Constants.EXTENDED_SCENE_SIZE];
        int offset = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2;
        // Player X=16: -6 lies across the normal scene boundary, exactly 22 tiles away.
        int[][] positions = {{-6, 51}, {38, 51}, {-8, 51}, {34, 69}};
        int[][] colors = {{70, 80, 90, 100}, {70, 80, 90, 100}, {70, 80, 90, 100}, {70, 80, 90, 100}};
        int[][] textures = {{45}, {45}, {45}, {45}};
        for (int i = 0; i < positions.length; i++)
        {
            final int[] color = colors[i], texture = textures[i];
            SceneTilePaint paint = proxy(SceneTilePaint.class, (name, args) -> {
                switch (name)
                {
                    case "getSwColor": return color[0]; case "setSwColor": color[0] = (int) args[0]; return null;
                    case "getSeColor": return color[1]; case "setSeColor": color[1] = (int) args[0]; return null;
                    case "getNeColor": return color[2]; case "setNeColor": color[2] = (int) args[0]; return null;
                    case "getNwColor": return color[3]; case "setNwColor": color[3] = (int) args[0]; return null;
                    case "getTexture": return texture[0]; case "setTexture": texture[0] = (int) args[0]; return null;
                    default: return null;
                }
            });
            tiles[1][positions[i][0] + offset][positions[i][1] + offset] = proxy(Tile.class,
                (name, args) -> name.equals("getSceneTilePaint") ? paint : null);
        }
        boolean[] allowScans = {true};
        int[] cycle = {100}, scanReads = {0};
        Scene scene = proxy(Scene.class, (name, args) -> {
            assertTrue("Disabling structure snow must not rescan Scene." + name, allowScans[0]);
            switch (name)
            {
                case "getExtendedTiles": scanReads[0]++; return tiles;
                case "getExtendedTileSettings": return flags;
                case "getWorldViewId": return WorldView.TOPLEVEL;
                case "isInstance": return false;
                case "getBaseX": return baseX;
                case "getBaseY": return baseY;
                default: return null;
            }
        });
        Player player = proxy(Player.class, (name, args) -> name.equals("getLocalLocation")
            ? new LocalPoint(16 * 128 + 64, 51 * 128 + 64, WorldView.TOPLEVEL) : null);
        RecordingGpu gpu = new RecordingGpu();
        WorldView world = proxy(WorldView.class, (name, args) -> name.equals("getScene") ? scene : null);
        Client client = proxy(Client.class, (name, args) -> {
            assertTrue("Disabling structure snow only needs GPU cleanup, not Client." + name,
                allowScans[0] || name.equals("getDrawCallbacks") || name.equals("getTopLevelWorldView"));
            switch (name)
            {
                case "getGameState": return GameState.LOGGED_IN;
                case "getLocalPlayer": return player;
                case "getGameCycle": return cycle[0];
                case "getDrawCallbacks": return gpu;
                case "getTopLevelWorldView": return world;
                default: return null;
            }
        });
        Object owner = new Object();
        try
        {
            WinterSurfaceSnow.update(owner, client, scene, true);
            for (int step = 0; step < 500 && stateField(owner, "scan") != null; step++)
            {
                assertTrue("Tile discovery has a strict per-cycle cap", (int) stateField(owner, "lastTiles") <= 192);
                assertTrue("GPU uploads have a strict per-cycle cap", (int) stateField(owner, "lastGpuZones") <= 2);
                cycle[0]++;
                WinterSurfaceSnow.tick(owner);
            }
            assertNull("A bounded sweep eventually completes", stateField(owner, "scan"));
            assertEquals("Nearby roofs across the extended boundary are coated", 2,
                WinterSurfaceSnow.getCount(owner));
            int completedReads = scanReads[0];
            for (int idle = 0; idle < 40; idle++) { cycle[0]++; WinterSurfaceSnow.tick(owner); }
            assertEquals("A stationary view does not reread scene tiles", completedReads, scanReads[0]);
            assertEquals(0, (int) stateField(owner, "lastTiles"));
            assertEquals(0, (int) stateField(owner, "lastModels"));
            BitSet queue = (BitSet) stateField(owner, "dirty");
            queue.set(0, 20);
            int beforeUpload = gpu.scenes.size();
            cycle[0]++; WinterSurfaceSnow.tick(owner);
            assertEquals("Queued shared-material refreshes spread across client cycles", 2, gpu.scenes.size() - beforeUpload);
            assertEquals(18, queue.cardinality());
            assertEquals(-1, textures[0][0]);
            assertEquals(-1, textures[1][0]);
            assertEquals("A roof outside the nearby view stays unchanged", 45, textures[2][0]);
            assertEquals("The radius is circular, not a square", 45, textures[3][0]);
            assertTrue("Negative scene coordinates invalidate the correct extended GPU zone",
                gpu.zones.contains((long) ((positions[0][0] + offset) >> 3) << 32
                    | ((positions[0][1] + offset) >> 3) & 0xffffffffL));
            allowScans[0] = false;
            WinterSurfaceSnow.update(owner, client, scene, false);
            assertEquals(0, WinterSurfaceSnow.getCount(owner));
            for (int i = 0; i < colors.length; i++)
            {
                assertArrayEquals(new int[]{70, 80, 90, 100}, colors[i]);
                assertEquals(45, textures[i][0]);
            }
            Client idleClient = proxy(Client.class, (name, args) -> {
                throw new AssertionError("After cleanup, disabled structure snow must not call Client." + name);
            });
            for (int tick = 0; tick < 3; tick++)
            {
                WinterSurfaceSnow.update(owner, idleClient, scene, false);
                assertEquals(0, WinterSurfaceSnow.getCount(owner));
            }
        }
        finally { WinterSurfaceSnow.restore(owner); }
    }

    @Test
    public void modelWorkIsSpreadAcrossCyclesAndUnavailableModelsCanRetry() throws Exception
    {
        Tile[][][] tiles = new Tile[4][Constants.SCENE_SIZE][Constants.SCENE_SIZE];
        byte[][][] flags = new byte[4][Constants.SCENE_SIZE][Constants.SCENE_SIZE];
        int[] cycle = {1}, loads = {0}, playerX = {50};
        Scene scene = proxy(Scene.class, (name, args) -> {
            switch (name)
            {
                case "getExtendedTiles": return tiles;
                case "getExtendedTileSettings": return flags;
                case "getBaseX": return 3200;
                case "getBaseY": return 3300;
                case "getWorldViewId": return WorldView.TOPLEVEL;
                case "isInstance": return false;
                default: return null;
            }
        });
        Player player = proxy(Player.class, (name, args) -> name.equals("getLocalLocation")
            ? new LocalPoint(playerX[0] * 128 + 64, 50 * 128 + 64, WorldView.TOPLEVEL) : null);
        ObjectComposition definition = proxy(ObjectComposition.class,
            (name, args) -> name.equals("getName") ? "Bench" : null);
        for (int i = 0; i < 12; i++)
        {
            int x = 49 + i % 4, y = 49 + i / 4;
            Model model = model(new float[]{0, 10, 0}, new float[]{-10, -10, -10}, new float[]{0, 0, 10},
                new int[]{0}, new int[]{1}, new int[]{2}, new int[]{0}, null);
            GameObject object = proxy(GameObject.class, (name, args) -> {
                switch (name)
                {
                    case "getRenderable": return model;
                    case "getConfig": return 10;
                    case "getLocalLocation": return new LocalPoint(x * 128 + 64, y * 128 + 64, WorldView.TOPLEVEL);
                    case "getSceneMinLocation": case "getSceneMaxLocation": return new Point(x, y);
                    default: return null;
                }
            });
            tiles[0][x][y] = proxy(Tile.class,
                (name, args) -> name.equals("getGameObjects") ? new GameObject[]{object} : null);
        }
        Client client = proxy(Client.class, (name, args) -> {
            switch (name)
            {
                case "getGameState": return GameState.LOGGED_IN;
                case "getLocalPlayer": return player;
                case "getGameCycle": return cycle[0];
                case "getObjectDefinition": return definition;
                case "loadModelData": loads[0]++; return null;
                default: return null;
            }
        });
        Object owner = new Object();
        try
        {
            WinterSurfaceSnow.update(owner, client, scene, true);
            for (int step = 0; step < 500 && stateField(owner, "scan") != null; step++)
            {
                assertTrue("At most two models are processed in any one client cycle",
                    (int) stateField(owner, "lastModels") <= 2);
                assertTrue("At most two overlays are built in any one client cycle",
                    (int) stateField(owner, "lastBuilds") <= 2);
                cycle[0]++; WinterSurfaceSnow.tick(owner);
            }
            assertNull(stateField(owner, "scan"));
            assertEquals(12, loads[0]);
            playerX[0]++;
            cycle[0]++; WinterSurfaceSnow.tick(owner);
            assertNotNull("Moving the player starts another view snapshot", stateField(owner, "scan"));
            for (int step = 0; step < 500 && stateField(owner, "scan") != null; step++)
            {
                cycle[0]++; WinterSurfaceSnow.tick(owner);
            }
            assertEquals("Unavailable template data is retried instead of cached permanently", 24, loads[0]);
        }
        finally { WinterSurfaceSnow.restore(owner); }
    }

    private static Object stateField(Object owner, String name) throws Exception
    {
        Field states = WinterSurfaceSnow.class.getDeclaredField("STATES");
        states.setAccessible(true);
        Object state = ((Map<?, ?>) states.get(null)).get(owner);
        Field field = state.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(state);
    }

    @Test
    public void roofPaintRestoresItsTextureAndPreservesExternalColorChanges() throws Exception
    {
        int[] colors = {70, 80, 90, 100};
        int[] texture = {45};
        SceneTilePaint paint = proxy(SceneTilePaint.class, (name, args) -> {
            switch (name)
            {
                case "getSwColor": return colors[0]; case "setSwColor": colors[0] = (int) args[0]; return null;
                case "getSeColor": return colors[1]; case "setSeColor": colors[1] = (int) args[0]; return null;
                case "getNeColor": return colors[2]; case "setNeColor": colors[2] = (int) args[0]; return null;
                case "getNwColor": return colors[3]; case "setNwColor": colors[3] = (int) args[0]; return null;
                case "getTexture": return texture[0]; case "setTexture": texture[0] = (int) args[0]; return null;
                default: return null;
            }
        });
        Tile tile = proxy(Tile.class, (name, args) -> name.equals("getSceneTilePaint") ? paint : null);
        Class<?> type = Class.forName("com.seasonalscape.WinterSurfaceSnow$Roof");
        Constructor<?> constructor = type.getDeclaredConstructor(Tile.class, int.class, int.class);
        constructor.setAccessible(true);
        Object roof = constructor.newInstance(tile, 10, 10);
        Method apply = type.getDeclaredMethod("apply"), restore = type.getDeclaredMethod("restore");
        apply.setAccessible(true); restore.setAccessible(true);
        assertEquals(true, apply.invoke(roof));
        assertEquals(-1, texture[0]);
        int[] snow = colors.clone();
        assertEquals(false, apply.invoke(roof));
        assertArrayEquals(snow, colors);
        colors[2] = 999;
        restore.invoke(roof);
        assertArrayEquals(new int[]{70, 80, 999, 100}, colors);
        assertEquals(45, texture[0]);
    }

    private static Model model(float[] x, float[] y, float[] z, int[] a, int[] b, int[] c, int[] color3, short[] textures)
    {
        return proxy(Model.class, (name, args) -> {
            switch (name)
            {
                case "getVerticesX": return x; case "getVerticesY": return y; case "getVerticesZ": return z;
                case "getVerticesCount": return x.length;
                case "getFaceCount": return a.length;
                case "getFaceIndices1": return a; case "getFaceIndices2": return b; case "getFaceIndices3": return c;
                case "getFaceColors3": return color3; case "getFaceTextures": return textures;
                default: return null;
            }
        });
    }

    private static final class RecordingGpu extends GpuPlugin
    {
        private final List<Scene> scenes = new ArrayList<>();
        private final Set<Long> zones = new HashSet<>();

        @Override
        public void invalidateZone(Scene scene, int x, int y)
        {
            scenes.add(scene);
            zones.add((long) x << 32 | y & 0xffffffffL);
        }
    }

    private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> handler)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (proxy, method, args) -> {
                if (method.getName().equals("hashCode")) { return System.identityHashCode(proxy); }
                if (method.getName().equals("equals")) { return proxy == args[0]; }
                Object result = handler.apply(method.getName(), args);
                if (result != null || !method.getReturnType().isPrimitive()) { return result; }
                if (method.getReturnType() == boolean.class) { return false; }
                if (method.getReturnType() == int.class) { return 0; }
                if (method.getReturnType() == long.class) { return 0L; }
                if (method.getReturnType() == float.class) { return 0f; }
                if (method.getReturnType() == double.class) { return 0d; }
                return null;
            }));
    }
}
