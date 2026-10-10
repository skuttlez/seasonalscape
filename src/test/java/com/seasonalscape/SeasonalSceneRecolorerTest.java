package com.seasonalscape;

import com.google.inject.Injector;
import com.retronpcswapper.RetroDrawCallbacks;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GroundObject;
import net.runelite.api.Model;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.plugins.gpu.GpuPlugin;
import net.runelite.client.plugins.gpu.GpuPluginConfig;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the renderer through public RuneLite interfaces, without a live game client. */
public class SeasonalSceneRecolorerTest
{
    private static final int GREEN = 20 << 10 | 4 << 7 | 50;
    private static final int BROWN = 5 << 10 | 4 << 7 | 40;

    @Test
    public void retroGpuKeepsTerrainAndTreesVisibleAndRestoresUploadedColors()
    {
        Fixture f = new Fixture();
        RecordingGpu gpu = new RecordingGpu();
        RetroDrawCallbacks retro = new RetroDrawCallbacks(gpu);
        f.callbacks = retro;
        int[] leaves = {GREEN, BROWN, -2};
        int[] originalLeaves = leaves.clone(), originalGround = f.paintColors.clone();
        f.objects = new GameObject[]{tree(treeModel(leaves, new short[]{-1, -1, -1}), 0)};
        long zone = gpu.track(f.offset >> 3, f.offset >> 3, leaves);
        try
        {
            for (Season season : new Season[]{Season.AUTUMN, Season.SPRING, Season.SUMMER})
            {
                int invalidations = retro.invalidatedZones;
                f.apply(season, true, true);
                assertEquals(1, f.renderer.getChangedTiles());
                assertEquals(1, f.renderer.getChangedTrees());
                assertEquals(SeasonalGroundColors.ground(GREEN, season), f.paintColors[0]);
                assertEquals(SeasonalPalette.foliage(GREEN, season), leaves[0]);
                assertTrue("Zone invalidation must pass through Retro's active callback",
                    retro.invalidatedZones > invalidations);
                gpu.rebuildInvalidatedZones();
                assertArrayEquals("GPU copies receive the seasonal tree colors", leaves, gpu.uploaded.get(zone));
            }
            int invalidations = retro.invalidatedZones;
            f.renderer.restore();
            assertArrayEquals(originalGround, f.paintColors);
            assertArrayEquals(originalLeaves, leaves);
            assertTrue("Restoring also invalidates through Retro", retro.invalidatedZones > invalidations);
            gpu.rebuildInvalidatedZones();
            assertArrayEquals("Disabling leaves no tinted GPU copy", originalLeaves, gpu.uploaded.get(zone));
        }
        finally { f.renderer.restore(); }
    }

    @Test
    public void retroReadsBrightTextureCapabilityFromItsGpuDelegate()
    {
        Fixture f = new Fixture();
        RecordingGpu gpu = new RecordingGpu();
        f.callbacks = new RetroDrawCallbacks(gpu);
        int[] leaves = {30, 70, 115};
        int[] original = leaves.clone();
        short[] textures = {8, 30, 60};
        f.objects = new GameObject[]{tree(treeModel(leaves, textures), 0)};
        try
        {
            f.apply(Season.AUTUMN, false, true);
            assertArrayEquals("Disabled bright textures retain normal brightness", original, leaves);
            gpu.brightTextures = true;
            f.apply(Season.AUTUMN, false, true);
            for (int i = 0; i < leaves.length; i++)
            {
                assertEquals("The underlying GPU capability enables packed leaf tints",
                    SeasonalTextureTint.foliage(original[i], textures[i], Season.AUTUMN), leaves[i]);
            }
            gpu.brightTextures = false;
            f.apply(Season.AUTUMN, false, true);
            assertArrayEquals("A same-season capability change restores brightness", original, leaves);
            assertArrayEquals("Texture IDs are never replaced", new short[]{8, 30, 60}, textures);
        }
        finally { f.renderer.restore(); }
    }

    @Test
    public void pluginDoesNotShutOffWhenRetroIsEnabledOverGpu() throws Exception
    {
        Fixture f = new Fixture();
        RecordingGpu gpu = new RecordingGpu();
        f.callbacks = gpu;
        f.player = proxy(Player.class, (name, args) -> name.equals("getWorldLocation")
            ? new WorldPoint(f.baseX, f.baseY, 0) : null);
        SeasonalScapePlugin plugin = new SeasonalScapePlugin();
        SeasonalScapeConfig config = proxy(SeasonalScapeConfig.class, (name, args) -> {
            switch (name)
            {
                case "season": return SeasonMode.AUTUMN;
                case "hemisphere": return Hemisphere.NORTH;
                case "timeZone": return "";
                case "terrain": case "foliage": return true;
                default: return null;
            }
        });
        set(plugin, "client", f.client);
        set(plugin, "config", config);
        set(plugin, "recolorer", f.renderer);
        set(plugin, "groundCover", new GroundCover(f.client));
        set(plugin, "running", true);
        set(plugin, "dirty", true);
        try
        {
            plugin.onGameTick(new GameTick());
            assertEquals("Seasonal world active", plugin.getStatus());
            int expected = SeasonalPalette.ground(GREEN, Season.AUTUMN);
            assertEquals(expected, f.paintColors[0]);

            RetroDrawCallbacks retro = new RetroDrawCallbacks(gpu);
            f.callbacks = retro;
            plugin.onGameTick(new GameTick());
            assertEquals("Retro must not trigger the unsupported-renderer shutdown",
                "Seasonal world active", plugin.getStatus());
            assertEquals(expected, f.paintColors[0]);
            assertTrue(retro.invalidatedZones > 0);

            f.callbacks = new RetroDrawCallbacks(proxy(DrawCallbacks.class, (name, args) -> null));
            plugin.onGameTick(new GameTick());
            assertEquals("Unknown wrapped renderers remain unsupported", "Use default graphics or GPU",
                plugin.getStatus());
            assertEquals("Rejecting a renderer restores original terrain", GREEN, f.paintColors[0]);
        }
        finally { f.renderer.restore(); }
    }

    private static void set(SeasonalScapePlugin plugin, String name, Object value) throws Exception
    {
        Field field = SeasonalScapePlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }

    @Test
    public void paintTracksOriginalGrassAcrossSeasonsAndRestoresWhenDisabled()
    {
        Fixture f = new Fixture();
        int[] original = f.paintColors.clone();
        f.apply(Season.AUTUMN, true, false);
        assertEquals(1, f.renderer.getChangedTiles());
        assertEquals(SeasonalPalette.ground(GREEN, Season.AUTUMN), f.paintColors[0]);
        assertTrue(f.renderer.isSeasonalGround(f.tile));

        f.apply(Season.WINTER, true, false);
        assertEquals(SeasonalPalette.ground(GREEN, Season.WINTER), f.paintColors[0]);
        assertTrue("Snow retains its original grass eligibility", f.renderer.isSeasonalGround(f.tile));
        f.apply(Season.SUMMER, true, false);
        assertEquals(SeasonalGroundColors.ground(GREEN, Season.SUMMER), f.paintColors[0]);
        assertEquals(1, f.renderer.getChangedTiles());
        assertTrue("Summer grass remains eligible for ground cover", f.renderer.isSeasonalGround(f.tile));
        f.apply(Season.SPRING, true, false);
        assertEquals(SeasonalGroundColors.ground(GREEN, Season.SPRING), f.paintColors[0]);

        f.apply(Season.AUTUMN, true, false);
        f.apply(Season.AUTUMN, false, false);
        assertArrayEquals(original, f.paintColors);
        assertTrue("Ground cover can run with recoloring disabled", f.renderer.isSeasonalGround(f.tile));
        f.renderer.restore();
        assertFalse(f.renderer.isSeasonalGround(f.tile));
    }

    @Test
    public void winterCoversYellowGreenGrassAndDarkTreeShadowsWithoutGaps()
    {
        Fixture f = new Fixture();
        // Actual missed corners from grass underlays in the Varrock test scene.
        int[] original = {10638, 11655, 12806, 16775};
        System.arraycopy(original, 0, f.paintColors, 0, original.length);
        f.apply(Season.WINTER, true, false);
        assertEquals(1, f.renderer.getChangedTiles());
        for (int i = 0; i < original.length; i++)
        {
            assertNotEquals("Winter must cover every grass corner", original[i], f.paintColors[i]);
            assertEquals("Snow has neutral saturation", 0, f.paintColors[i] >>> 7 & 7);
        }
        assertTrue(f.renderer.isSeasonalGround(f.tile));
        f.apply(Season.WINTER, true, false);
        assertEquals("Repeated updates retain grass eligibility", 1, f.renderer.getChangedTiles());
        f.renderer.restore();
        assertArrayEquals(original, f.paintColors);
    }

    @Test
    public void springAndSummerCoverOliveGrassAndRestoreAfterRepeatedSeasonChanges()
    {
        Fixture f = new Fixture();
        int[] original = {8582, 9609, 11655, 16775};
        System.arraycopy(original, 0, f.paintColors, 0, original.length);
        for (Season season : new Season[]{Season.WINTER, Season.SPRING, Season.SPRING,
            Season.SUMMER, Season.SUMMER, Season.AUTUMN, Season.SUMMER})
        {
            f.apply(season, true, false);
            for (int i = 0; i < original.length; i++)
            {
                assertEquals("Season must transform original grass in " + season,
                    SeasonalGroundColors.ground(original[i], season), f.paintColors[i]);
            }
            assertTrue("Ground cover keeps grass eligibility in " + season, f.renderer.isSeasonalGround(f.tile));
        }
        f.apply(Season.SUMMER, false, false);
        assertArrayEquals(original, f.paintColors);
        assertTrue("Summer cover can run without terrain recoloring", f.renderer.isSeasonalGround(f.tile));
        f.renderer.restore();
        assertArrayEquals(original, f.paintColors);
        assertFalse(f.renderer.isSeasonalGround(f.tile));
    }

    @Test
    public void winterCoversGrassAlongRoadEdgesAndPreservesRoadTriangles()
    {
        Fixture f = new Fixture();
        int road = 7200; // Varrock's neutral stone path.
        int[] a = {10638, road, 12345678};
        int[] b = {11655, road + 2, 12345678};
        int[] c = {12806, road + 3, 12345678};
        int[] originalA = a.clone(), originalB = b.clone(), originalC = c.clone();
        f.paint = null;
        f.model = triangleModel(a, b, c, null);
        f.apply(Season.WINTER, true, false);
        assertEquals(1, f.renderer.getChangedTiles());
        assertNotEquals(originalA[0], a[0]);
        assertNotEquals(originalB[0], b[0]);
        assertNotEquals(originalC[0], c[0]);
        assertArrayEquals(new int[]{road, 12345678}, new int[]{a[1], a[2]});
        assertArrayEquals(new int[]{road + 2, 12345678}, new int[]{b[1], b[2]});
        assertArrayEquals(new int[]{road + 3, 12345678}, new int[]{c[1], c[2]});
        f.renderer.restore();
        assertArrayEquals(originalA, a);
        assertArrayEquals(originalB, b);
        assertArrayEquals(originalC, c);
    }

    @Test
    public void mixedOliveAndShadowedTerrainStaysSmoothThroughEverySeasonAndRestores()
    {
        Fixture paint = new Fixture();
        int[] originalPaint = {8582, 9609, 12806, 16775};
        System.arraycopy(originalPaint, 0, paint.paintColors, 0, originalPaint.length);
        Fixture mesh = new Fixture();
        int[] a = {8582, 12 << 10 | 3 << 7 | 2, 7200, 12345678};
        int[] b = {GREEN, GREEN + 40, 7202, 12345678};
        int[] c = {9609, 16 << 10 | 4 << 7 | 7, 7204, 12345678};
        int[] originalA = a.clone(), originalB = b.clone(), originalC = c.clone();
        mesh.paint = null;
        mesh.model = triangleModel(a, b, c, null);
        for (Season season : new Season[]{Season.AUTUMN, Season.WINTER, Season.SPRING,
            Season.SUMMER, Season.AUTUMN, Season.AUTUMN})
        {
            paint.apply(season, true, false);
            mesh.apply(season, true, false);
            assertEquals(1, paint.renderer.getChangedTiles());
            assertEquals(1, mesh.renderer.getChangedTiles());
            SeasonalTerrainPaletteTest.assertStableInterpolation(paint.paintColors[0],
                paint.paintColors[1], paint.paintColors[3]);
            SeasonalTerrainPaletteTest.assertStableInterpolation(paint.paintColors[1],
                paint.paintColors[2], paint.paintColors[3]);
            for (int face = 0; face < 2; face++)
            {
                SeasonalTerrainPaletteTest.assertStableInterpolation(a[face], b[face], c[face]);
            }
            assertArrayEquals(new int[]{7200, 12345678}, new int[]{a[2], a[3]});
            assertArrayEquals(new int[]{7202, 12345678}, new int[]{b[2], b[3]});
            assertArrayEquals(new int[]{7204, 12345678}, new int[]{c[2], c[3]});
        }
        paint.renderer.restore();
        mesh.renderer.restore();
        assertArrayEquals(originalPaint, paint.paintColors);
        assertArrayEquals(originalA, a);
        assertArrayEquals(originalB, b);
        assertArrayEquals(originalC, c);
    }

    @Test
    public void triangleColorsHandleAliasedChannelsAndPreserveExternalWrites()
    {
        Fixture f = new Fixture();
        int[] shared = {GREEN, BROWN, 12345678};
        int[] third = {GREEN + 10, BROWN, 12345678};
        f.paint = null;
        f.model = triangleModel(shared, shared, third, null);
        f.apply(Season.SPRING, true, false);
        int spring = SeasonalPalette.ground(GREEN, Season.SPRING);
        assertArrayEquals(new int[]{spring, BROWN, 12345678}, shared);
        f.apply(Season.SPRING, true, false);
        assertEquals("Repeated scans must not accumulate tint", spring, shared[0]);
        assertTrue(f.renderer.isSeasonalGround(f.tile));
        f.apply(Season.WINTER, true, false);
        assertEquals(SeasonalPalette.ground(GREEN, Season.WINTER), shared[0]);
        shared[1] = 999; // An unrelated plugin has changed a non-grass face.
        f.renderer.restore();
        assertArrayEquals(new int[]{GREEN, 999, 12345678}, shared);
        assertArrayEquals(new int[]{GREEN + 10, BROWN, 12345678}, third);
    }

    @Test
    public void excludesInstancesTexturesRoofsBridgesUndergroundAndSeparateMaps()
    {
        for (int scenario = 0; scenario < 12; scenario++)
        {
            Fixture f = new Fixture();
            int[] leaves = {GREEN, BROWN, -2};
            f.objects = new GameObject[]{tree(treeModel(leaves, new short[]{-1, -1, -1}), 0)};
            switch (scenario)
            {
                case 0: f.instance = true; break;
                case 1: f.paintTexture = 3; break;
                case 2: f.flags[2][f.offset][f.offset] = 4; break;
                case 3: f.flags[1][f.offset][f.offset] = 2; break;
                case 4: f.baseY = 3200 + 6400; break;
                case 5:
                    f.paint = null;
                    f.model = triangleModel(new int[]{GREEN}, new int[]{GREEN},
                        new int[]{GREEN}, new int[]{2});
                    break;
                case 6: f.baseY = 4096; break;
                case 7: f.baseX = 4096; break;
                case 8: f.plane = 1; break;
                case 9: f.renderLevel = 1; break;
                case 10: f.bridge = f.tile; break;
                case 11: f.worldViewId = 1; break;
                default: throw new AssertionError();
            }
            for (Season season : Season.values())
            {
                f.apply(season, true, true);
                assertEquals("scenario " + scenario + " in " + season, 0, f.renderer.getChangedTiles());
                if (scenario != 1 && scenario != 5)
                {
                    assertEquals("Sheltered or unsupported locations must not recolor trees", 0,
                        f.renderer.getChangedTrees());
                    assertArrayEquals(new int[]{GREEN, BROWN, -2}, leaves);
                }
                assertFalse("scenario " + scenario + " in " + season, f.renderer.isSeasonalGround(f.tile));
                assertArrayEquals(new int[]{GREEN, GREEN + 2, GREEN + 4, GREEN + 6}, f.paintColors);
                if (f.model != null) { assertEquals(GREEN, f.model.getTriangleColorA()[0]); }
            }
        }
    }

    @Test
    public void expandedOverworldRecolorsEligibleGrassAndTreesThroughEverySeason()
    {
        int[][] places = {{2600, 3300}, {1640, 3680}, {1640, 3100}, {3500, 3500}};
        for (int[] place : places)
        {
            Fixture f = new Fixture();
            f.baseX = place[0];
            f.baseY = place[1];
            int[] leaves = {GREEN, BROWN, -2};
            f.objects = new GameObject[]{tree(treeModel(leaves, new short[]{-1, -1, -1}), 0)};
            for (Season season : Season.values())
            {
                f.apply(season, true, true);
                assertEquals("Eligible grass outside the old rectangle receives " + season,
                    1, f.renderer.getChangedTiles());
                assertEquals("Supported trees outside the old rectangle receive " + season,
                    1, f.renderer.getChangedTrees());
                assertTrue(f.renderer.isSeasonalGround(f.tile));
                assertEquals(SeasonalGroundColors.ground(GREEN, season), f.paintColors[0]);
                assertEquals(SeasonalPalette.foliage(GREEN, season), leaves[0]);
                assertEquals("Tree trunks keep their original material", BROWN, leaves[1]);
                assertEquals(-2, leaves[2]);
            }
            f.renderer.restore();
            assertArrayEquals(new int[]{GREEN, BROWN, -2}, leaves);
            assertArrayEquals(new int[]{GREEN, GREEN + 2, GREEN + 4, GREEN + 6}, f.paintColors);
        }
    }

    @Test
    public void winterFrostsGrassBladesAndRestoresThemBeforeAutumn()
    {
        Fixture f = new Fixture();
        int blade = 17680; // Observed untextured Varrock grass blade color.
        // Olive terrain hues also appear on brown ground decorations and must
        // not inherit the expanded terrain-only snow classification.
        int[] faces = {blade, BROWN, 8582, 9609, blade, -1, -2};
        int[] original = faces.clone();
        Model blades = treeModel(faces, new short[]{-1, -1, -1, -1, 8, -1, -1});
        f.groundObject = proxy(GroundObject.class,
            (name, args) -> name.equals("getRenderable") ? blades : null);

        f.apply(Season.WINTER, true, false);
        assertArrayEquals(new int[]{SeasonalGroundColors.ground(blade, Season.WINTER),
            BROWN, 8582, 9609, blade, -1, -2}, faces);
        f.apply(Season.WINTER, true, false);
        assertEquals("Shared color arrays do not accumulate frost",
            SeasonalGroundColors.ground(blade, Season.WINTER), faces[0]);
        f.apply(Season.AUTUMN, true, false);
        assertArrayEquals("Autumn must remove frost from grass blades", original, faces);
        f.apply(Season.WINTER, true, false);
        f.renderer.restore();
        assertArrayEquals(original, faces);
    }

    @Test
    public void sharedGrassGpuCopiesRestoreEvenOutsideEligibleGrassTiles()
    {
        Fixture f = new Fixture();
        RecordingGpu gpu = new RecordingGpu();
        f.callbacks = gpu;
        int blade = 17680;
        int[] colors = {blade, BROWN, -1, -2};
        int[] original = colors.clone();
        Model blades = treeModel(colors, new short[]{-1, -1, -1, -1});
        f.groundObject = proxy(GroundObject.class,
            (name, args) -> name.equals("getRenderable") ? blades : null);

        // A cached model is also placed on a road in another GPU zone. This
        // tile does not pass hasGrass(), but its model shares the color arrays.
        SceneTilePaint road = proxy(SceneTilePaint.class, (name, args) -> {
            if (name.equals("getTexture")) { return -1; }
            return name.endsWith("Color") ? BROWN : null;
        });
        Tile roadTile = proxy(Tile.class, (name, args) -> {
            switch (name)
            {
                case "getSceneLocation": return new Point(16, 0);
                case "getSceneTilePaint": return road;
                case "getGroundObject": return f.groundObject;
                default: return null;
            }
        });
        f.tiles[0][f.offset + 16][f.offset] = roadTile;
        long grassZone = gpu.track(f.offset >> 3, f.offset >> 3, colors);
        long roadZone = gpu.track((f.offset + 16) >> 3, f.offset >> 3, colors);

        f.apply(Season.WINTER, true, false);
        assertFalse("The road must remain outside seasonal terrain", f.renderer.isSeasonalGround(roadTile));
        gpu.rebuildInvalidatedZones();
        assertNotEquals("Grass zone uploads frost", blade, gpu.uploaded.get(grassZone)[0]);
        assertNotEquals("Shared model copies rebuild outside eligible grass", blade,
            gpu.uploaded.get(roadZone)[0]);

        f.apply(Season.SUMMER, true, false);
        assertArrayEquals("CPU colors restore first", original, colors);
        gpu.rebuildInvalidatedZones();
        assertArrayEquals("Grass GPU copy loses frost", original, gpu.uploaded.get(grassZone));
        assertArrayEquals("Road GPU copy must not retain winter colors", original, gpu.uploaded.get(roadZone));
    }

    @Test
    public void sharedTreeFacesAreTintedOnceAndRestoredOnce()
    {
        Fixture f = new Fixture();
        int[] shared = {GREEN, BROWN, GREEN, -2};
        short[] textures = {-1, -1, 7, -1};
        Model first = treeModel(shared, textures);
        Model second = treeModel(shared, textures);
        f.objects = new GameObject[]{tree(first, 0), tree(second, 1)};
        f.apply(Season.SPRING, false, true);
        assertEquals(2, f.renderer.getChangedTrees());
        assertArrayEquals(new int[]{SeasonalPalette.foliage(GREEN, Season.SPRING), BROWN, GREEN, -2}, shared);
        f.apply(Season.SPRING, false, true);
        assertEquals(SeasonalPalette.foliage(GREEN, Season.SPRING), shared[0]);
        f.apply(Season.SPRING, false, false);
        assertArrayEquals(new int[]{GREEN, BROWN, GREEN, -2}, shared);
        assertEquals(0, f.renderer.getChangedTrees());
    }

    @Test
    public void winterKeepsLeafTexturesLightingAndSentinelsThroughSeasonChanges()
    {
        Fixture f = new Fixture();
        // Textured faces contain lighting values rather than packed HSL colors.
        // Include smooth, flat (-1) and hidden (-2) faces on upward canopy geometry.
        int[][] colors = {
            {30, 55, 95, 70, 85, BROWN, GREEN, -1, -2},
            {42, 61, 104, 0, 92, BROWN + 1, GREEN + 10, -1, -2},
            {55, 71, 119, -1, -2, BROWN + 2, GREEN + 20, -1, -2}
        };
        int[][] original = {colors[0].clone(), colors[1].clone(), colors[2].clone()};
        short[] textures = {8, 30, 60, 60, 30, -1, -1, -1, -1};
        short[] originalTextures = textures.clone();
        f.objects = new GameObject[]{tree(treeModel(colors, textures), 0)};

        for (Season season : new Season[]{Season.WINTER, Season.WINTER, Season.SPRING, Season.AUTUMN,
            Season.WINTER, Season.SUMMER})
        {
            f.apply(season, false, true);
            assertArrayEquals("Leaf texture IDs and cutout coverage must survive " + season,
                originalTextures, textures);
            for (int channel = 0; channel < colors.length; channel++)
            {
                int[] expected = original[channel].clone();
                int leaf = original[channel][6];
                expected[6] = season == Season.WINTER
                    ? 20 << 10 | 1 << 7 | 78 + (leaf & 127) * 22 / 100
                    : SeasonalPalette.foliage(leaf, season);
                assertArrayEquals("Keep textured brightness, sentinels and brown trunk in " + season,
                    expected, colors[channel]);
            }
        }

        f.apply(Season.WINTER, false, true);
        f.renderer.restore();
        assertArrayEquals(originalTextures, textures);
        for (int channel = 0; channel < colors.length; channel++)
        {
            assertArrayEquals("Disabling restores the complete original tree", original[channel], colors[channel]);
        }
    }

    @Test
    public void gpuLeafTintFollowsSeasonsAndBrightTextureCapabilityWithoutChangingCutouts()
    {
        Fixture f = new Fixture();
        RecordingGpu gpu = new RecordingGpu();
        f.callbacks = gpu;
        int[] colors = {30, 70, 115, 60, GREEN, BROWN, -1, -2, GREEN};
        int[] original = colors.clone();
        short[] textures = {8, 30, 60, 7, -1, -1, 30, 60, 8};
        short[] originalTextures = textures.clone();
        byte[] transparency = {0, 64, (byte) 192, 0, 0, 0, 0, 0, (byte) 255};
        byte[] originalTransparency = transparency.clone();
        Model model = treeModel(new int[][]{colors, colors, colors}, textures, transparency);
        f.objects = new GameObject[]{tree(model, 0)};

        f.apply(Season.SPRING, false, true);
        assertEquals("Disabled bright textures keep original texture lighting", original[0], colors[0]);
        gpu.brightTextures = true;
        for (Season season : new Season[]{Season.SPRING, Season.SPRING, Season.SUMMER,
            Season.AUTUMN, Season.WINTER, Season.SUMMER})
        {
            f.apply(season, false, true);
            for (int i = 0; i < 3; i++)
            {
                assertEquals("GPU tint comes from original brightness in " + season,
                    SeasonalTextureTint.foliage(original[i], textures[i], season), colors[i]);
            }
            assertEquals("Unknown textures keep their lighting", original[3], colors[3]);
            assertEquals(SeasonalPalette.foliage(GREEN, season), colors[4]);
            assertArrayEquals("Trunks, sentinels and already-colored faces stay intact",
                new int[]{BROWN, -1, -2, GREEN}, new int[]{colors[5], colors[6], colors[7], colors[8]});
            assertArrayEquals(originalTextures, model.getFaceTextures());
            assertArrayEquals("Leaf cutout coverage must remain unchanged", originalTransparency,
                model.getFaceTransparencies());
        }

        gpu.brightTextures = false;
        f.apply(Season.SUMMER, false, true);
        assertArrayEquals("Changing renderer capability restores textured lighting in the same season",
            new int[]{30, 70, 115}, new int[]{colors[0], colors[1], colors[2]});
        assertEquals(SeasonalPalette.foliage(GREEN, Season.SUMMER), colors[4]);
        f.renderer.restore();
        assertArrayEquals(original, colors);
        assertArrayEquals(originalTextures, textures);
        assertArrayEquals(originalTransparency, transparency);
    }

    private static Model treeModel(int[] shared, short[] textures)
    {
        return treeModel(new int[][]{shared, shared, shared}, textures);
    }

    private static Model treeModel(int[][] colors, short[] textures)
    {
        return treeModel(colors, textures, null);
    }

    private static Model treeModel(int[][] colors, short[] textures, byte[] transparency)
    {
        int[] a = new int[textures.length], b = new int[textures.length], c = new int[textures.length];
        java.util.Arrays.fill(b, 1);
        java.util.Arrays.fill(c, 2);
        return proxy(Model.class, (name, args) -> {
            switch (name)
            {
                case "getFaceColors1": return colors[0];
                case "getFaceColors2": return colors[1];
                case "getFaceColors3": return colors[2];
                case "getFaceTextures": return textures;
                case "getFaceTransparencies": return transparency;
                case "getFaceCount": return textures.length;
                case "getVerticesCount": return 3;
                case "getVerticesX": return new float[]{0, 32, 0};
                case "getVerticesY": return new float[]{-100, -100, -100};
                case "getVerticesZ": return new float[]{0, 0, 32};
                case "getFaceIndices1": return a;
                case "getFaceIndices2": return b;
                case "getFaceIndices3": return c;
                default: return null;
            }
        });
    }

    private static GameObject tree(Model model, int x)
    {
        return proxy(GameObject.class, (name, args) -> {
            switch (name)
            {
                case "getRenderable": return model;
                case "getSceneMinLocation": case "getSceneMaxLocation": return new Point(x, 0);
                default: return null;
            }
        });
    }

    private static SceneTileModel triangleModel(int[] a, int[] b, int[] c, int[] textures)
    {
        return proxy(SceneTileModel.class, (name, args) -> {
            switch (name)
            {
                case "getTriangleColorA": return a;
                case "getTriangleColorB": return b;
                case "getTriangleColorC": return c;
                case "getTriangleTextureId": return textures;
                default: return null;
            }
        });
    }

    private static final class Fixture
    {
        private final int size = Constants.EXTENDED_SCENE_SIZE;
        private final int offset = (size - Constants.SCENE_SIZE) / 2;
        private final Tile[][][] tiles = new Tile[4][size][size];
        private final byte[][][] flags = new byte[4][size][size];
        private final int[] paintColors = {GREEN, GREEN + 2, GREEN + 4, GREEN + 6};
        private int paintTexture = -1, baseX = 3200, baseY = 3200;
        private int plane, renderLevel, worldViewId = WorldView.TOPLEVEL;
        private boolean instance;
        private Tile bridge;
        private SceneTilePaint paint;
        private SceneTileModel model;
        private GameObject[] objects = new GameObject[0];
        private GroundObject groundObject;
        private Player player;
        private DrawCallbacks callbacks;
        private final Client client;
        private final Tile tile;
        private final Scene scene;
        private final SeasonalSceneRecolorer renderer;

        private Fixture()
        {
            paint = proxy(SceneTilePaint.class, (name, args) -> {
                String[] corners = {"Sw", "Se", "Ne", "Nw"};
                for (int i = 0; i < corners.length; i++)
                {
                    if (name.equals("get" + corners[i] + "Color")) { return paintColors[i]; }
                    if (name.equals("set" + corners[i] + "Color")) { paintColors[i] = (int) args[0]; return null; }
                }
                return name.equals("getTexture") ? paintTexture : null;
            });
            tile = proxy(Tile.class, (name, args) -> {
                switch (name)
                {
                    case "getSceneLocation": return new Point(0, 0);
                    case "getPlane": return plane;
                    case "getRenderLevel": return renderLevel;
                    case "getBridge": return bridge;
                    case "getSceneTilePaint": return paint;
                    case "getSceneTileModel": return model;
                    case "getGameObjects": return objects;
                    case "getGroundObject": return groundObject;
                    default: return null;
                }
            });
            tiles[0][offset][offset] = tile;
            scene = proxy(Scene.class, (name, args) -> {
                switch (name)
                {
                    case "getWorldViewId": return worldViewId;
                    case "isInstance": return instance;
                    case "getExtendedTiles": return tiles;
                    case "getExtendedTileSettings": return flags;
                    case "getBaseX": return baseX;
                    case "getBaseY": return baseY;
                    default: return null;
                }
            });
            WorldView world = proxy(WorldView.class, (name, args) -> name.equals("getScene") ? scene : null);
            ObjectComposition definition = proxy(ObjectComposition.class,
                (name, args) -> name.equals("getName") ? "Tree" : null);
            client = proxy(Client.class, (name, args) -> {
                if (name.equals("getTopLevelWorldView")) { return world; }
                if (name.equals("getObjectDefinition")) { return definition; }
                if (name.equals("getDrawCallbacks")) { return callbacks; }
                if (name.equals("getLocalPlayer")) { return player; }
                if (name.equals("getGameState")) { return GameState.LOGGED_IN; }
                return null;
            });
            renderer = new SeasonalSceneRecolorer(client);
        }

        private void apply(Season season, boolean terrain, boolean foliage)
        {
            renderer.apply(scene, season, terrain, foliage);
        }
    }

    /** Models GPU uploads separately from the mutable CPU model color arrays. */
    private static final class RecordingGpu extends GpuPlugin
    {
        private final Map<Long, int[]> sources = new HashMap<>();
        private final Map<Long, int[]> uploaded = new HashMap<>();
        private final Set<Long> invalidated = new HashSet<>();
        private boolean brightTextures;

        private RecordingGpu()
        {
            GpuPluginConfig config = proxy(GpuPluginConfig.class,
                (name, args) -> name.equals("brightTextures") ? brightTextures : null);
            injector = proxy(Injector.class, (name, args) -> name.equals("getInstance") ? config : null);
        }

        private long track(int x, int y, int[] colors)
        {
            long key = (long) x << 32 | y & 0xffffffffL;
            sources.put(key, colors);
            uploaded.put(key, colors.clone());
            return key;
        }

        @Override
        public void invalidateZone(Scene scene, int x, int y)
        {
            invalidated.add((long) x << 32 | y & 0xffffffffL);
        }

        private void rebuildInvalidatedZones()
        {
            for (Long key : invalidated)
            {
                int[] source = sources.get(key);
                if (source != null) { uploaded.put(key, source.clone()); }
            }
            invalidated.clear();
        }
    }

    private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> handler)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, args) -> {
            if (method.getName().equals("hashCode")) { return System.identityHashCode(object); }
            if (method.getName().equals("equals")) { return object == args[0]; }
            if (method.getName().equals("toString")) { return type.getSimpleName() + " test proxy"; }
            Object result = handler.apply(method.getName(), args);
            if (result != null || !method.getReturnType().isPrimitive() || method.getReturnType() == void.class)
            {
                return result;
            }
            if (method.getReturnType() == boolean.class) { return false; }
            if (method.getReturnType() == int.class) { return 0; }
            throw new AssertionError("Unexpected primitive method: " + method);
        }));
    }
}
