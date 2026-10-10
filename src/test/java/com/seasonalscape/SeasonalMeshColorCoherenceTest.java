package com.seasonalscape;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.BiFunction;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.ObjectComposition;
import org.junit.Test;
import static org.junit.Assert.*;

/** A packed-HSL hue change across a face becomes repeated lightness ramps on GPU. */
public class SeasonalMeshColorCoherenceTest
{
    @Test
    public void autumnKeepsOneChromaAcrossGreenCornersIncludingDeepShadows() throws Exception
    {
        int[][] colors = {{green(20, 70)}, {green(21, 30)}, {green(22, 2)}};
        Fixture f = new Fixture(colors, null, 1);
        f.tree(Season.AUTUMN, false);
        assertEquals("Hue/saturation must not become an interpolated lightness ramp",
            colors[0][0] >>> 7, colors[1][0] >>> 7);
        assertEquals(colors[0][0] >>> 7, colors[2][0] >>> 7);
        assertEquals(77, colors[0][0] & 127);
        assertEquals(37, colors[1][0] & 127);
        assertEquals(9, colors[2][0] & 127);
        int[][] once = copy(colors);
        f.tree(Season.AUTUMN, false);
        assertChannelsEqual(once, colors);
    }

    @Test
    public void mixedTrunkAndLeafFaceIsLeftIntact() throws Exception
    {
        for (Season season : Season.values())
        {
            int[][] colors = {{green(20, 50)}, {5 << 10 | 4 << 7 | 40}, {green(21, 30)}};
            int[][] original = copy(colors);
            new Fixture(colors, null, 1).model(season, true, false);
            assertChannelsEqual(original, colors);
        }
    }

    @Test
    public void everySeasonKeepsDeepGreenCornersCoherent() throws Exception
    {
        for (Season season : Season.values())
        {
            int[][] colors = {{green(13, 7)}, {green(14, 13)}, {green(15, 15)}};
            int[][] original = copy(colors);
            Fixture f = new Fixture(colors, null, 1);
            f.model(season, true, false);
            assertEquals(colors[0][0] >>> 7, colors[1][0] >>> 7);
            assertEquals(colors[0][0] >>> 7, colors[2][0] >>> 7);
            assertTrue((colors[0][0] & 127) <= (colors[1][0] & 127));
            assertTrue((colors[1][0] & 127) <= (colors[2][0] & 127));
            f.renderer.restore();
            assertChannelsEqual(original, colors);
        }
    }

    @Test
    public void winterBladesRequireAWholeGreenFaceAndPreserveTextureAndSentinels() throws Exception
    {
        int brown = 8 << 10 | 4 << 7 | 40;
        int[][] colors = {{green(10, 7), green(10, 7), green(10, 7), green(10, 7), green(10, 7)},
            {green(13, 13), brown, green(13, 13), green(13, 13), green(13, 13)},
            {green(15, 15), green(15, 15), green(15, 15), -1, -2}};
        int[][] original = copy(colors);
        Fixture f = new Fixture(colors, new short[]{-1, -1, 8, -1, -1}, 5);
        f.model(Season.WINTER, false, false);
        for (int channel = 0; channel < 3; channel++)
        {
            assertEquals(36 << 3, colors[channel][0] >>> 7);
            assertEquals(original[channel][1], colors[channel][1]);
            assertEquals(original[channel][2], colors[channel][2]);
            assertEquals(original[channel][4], colors[channel][4]);
        }
        assertNotEquals(original[0][3], colors[0][3]);
        assertEquals(original[1][3], colors[1][3]);
        assertEquals(-1, colors[2][3]);
        f.renderer.restore();
        assertChannelsEqual(original, colors);
    }

    @Test
    public void flatUnusedChannelAndEntireHiddenFaceArePreserved() throws Exception
    {
        int[][] colors = {{green(20, 50), green(20, 50)},
            {green(21, 60), green(21, 60)}, {-1, -2}};
        int[][] original = copy(colors);
        new Fixture(colors, null, 2).tree(Season.SPRING, false);
        assertNotEquals(original[0][0], colors[0][0]);
        assertEquals("Flat faces use A only", original[1][0], colors[1][0]);
        assertEquals(original[0][1], colors[0][1]);
        assertEquals(original[1][1], colors[1][1]);
        assertArrayEquals(new int[]{-1, -2}, colors[2]);
    }

    @Test
    public void textureTintRejectsAnAlreadyColoredCornerWithoutHalfTinting() throws Exception
    {
        int[][] colors = {{80, 80}, {70, 70}, {green(20, 50), -1}};
        int[][] original = copy(colors);
        new Fixture(colors, new short[]{8, 30}, 2).tree(Season.AUTUMN, true);
        for (int channel = 0; channel < 3; channel++)
        {
            assertEquals("A foreign colored corner excludes the whole face", original[channel][0], colors[channel][0]);
        }
        assertEquals(SeasonalTextureTint.foliage(80, 30, Season.AUTUMN), colors[0][1]);
        assertEquals("Unused flat brightness stays unchanged", 70, colors[1][1]);
        assertEquals(-1, colors[2][1]);
    }

    @Test
    public void externalCornerEditPreventsPartialUpdatesAndAliasedChannelsRemainStable() throws Exception
    {
        int[] shared = {green(20, 50)};
        int[][] colors = {shared, shared, new int[]{green(21, 30)}};
        Fixture f = new Fixture(colors, null, 1);
        f.tree(Season.AUTUMN, false);
        int[] lastThird = colors[2].clone();
        shared[0] = 999;
        f.tree(Season.SPRING, false);
        assertEquals(999, shared[0]);
        assertArrayEquals("Do not change other corners when one belongs to another writer", lastThird, colors[2]);
        f.renderer.restore();
        assertEquals(999, shared[0]);
        assertEquals(green(21, 30), colors[2][0]);
    }

    @Test
    public void activeFaceCountAndShortChannelsBoundModelChanges() throws Exception
    {
        int[][] colors = {{green(20, 50), green(21, 60)},
            {green(20, 40), green(21, 60)}, {green(20, 30)}};
        Fixture f = new Fixture(colors, new short[]{-1}, 2);
        f.tree(Season.SUMMER, false);
        assertNotEquals(green(20, 50), colors[0][0]);
        assertEquals(green(21, 60), colors[0][1]);
        assertEquals(green(21, 60), colors[1][1]);
        int[][] pooled = {{green(20, 50), green(21, 60)},
            {green(20, 40), green(21, 60)}, {green(20, 30), green(21, 60)}};
        new Fixture(pooled, null, 1).tree(Season.SUMMER, false);
        for (int[] channel : pooled) { assertEquals("Unused pooled faces stay untouched", green(21, 60), channel[1]); }
    }

    private static int green(int hue, int lightness) { return hue << 10 | 4 << 7 | lightness; }
    private static int[][] copy(int[][] colors)
    {
        return new int[][]{colors[0].clone(), colors[1].clone(), colors[2].clone()};
    }
    private static void assertChannelsEqual(int[][] expected, int[][] actual)
    {
        for (int channel = 0; channel < 3; channel++) { assertArrayEquals(expected[channel], actual[channel]); }
    }

    private static final class Fixture
    {
        final SeasonalSceneRecolorer renderer;
        final GameObject object;
        final Model model;
        Fixture(int[][] colors, short[] textures, int count)
        {
            model = proxy(Model.class, (name, args) -> {
                switch (name)
                {
                    case "getFaceColors1": return colors[0];
                    case "getFaceColors2": return colors[1];
                    case "getFaceColors3": return colors[2];
                    case "getFaceTextures": return textures;
                    case "getFaceCount": return count;
                    default: return null;
                }
            });
            object = proxy(GameObject.class, (name, args) -> name.equals("getRenderable") ? model
                : name.equals("getId") ? 1276 : null);
            ObjectComposition definition = proxy(ObjectComposition.class,
                (name, args) -> name.equals("getName") ? "Tree" : null);
            Client client = proxy(Client.class, (name, args) -> name.equals("getObjectDefinition") ? definition : null);
            renderer = new SeasonalSceneRecolorer(client);
        }
        void tree(Season season, boolean textures) throws Exception
        {
            Method method = SeasonalSceneRecolorer.class.getDeclaredMethod("recolorTree", GameObject.class, Season.class, boolean.class);
            method.setAccessible(true);
            method.invoke(renderer, object, season, textures);
        }
        void model(Season season, boolean foliage, boolean textures) throws Exception
        {
            Method method = SeasonalSceneRecolorer.class.getDeclaredMethod("recolorModel", Model.class,
                Season.class, boolean.class, boolean.class);
            method.setAccessible(true);
            method.invoke(renderer, model, season, foliage, textures);
        }
    }

    private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> handler)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (proxy, method, args) -> {
                if (method.getName().equals("hashCode")) { return System.identityHashCode(proxy); }
                if (method.getName().equals("equals")) { return proxy == args[0]; }
                return handler.apply(method.getName(), args);
            }));
    }
}
