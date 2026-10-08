package com.seasonalscape;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.ui.DrawManager;
import org.junit.Test;
import static org.junit.Assert.*;

public class WinterFoliageTexturesTest
{
    @Test
    public void loadingKeepsTheMaterialListenerAliveButLogoutRemovesIt() throws Exception
    {
        Object owner = new Object();
        GameState[] gameState = {GameState.LOGGED_IN};
        int[] unregistered = {0};
        DrawManager draws = new DrawManager()
        {
            @Override public void unregisterEveryFrameListener(Runnable listener) { unregistered[0]++; }
        };
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, method, args) -> method.getName().equals("getGameState") ? gameState[0] : null);
        Constructor<?> constructor = Class.forName("com.seasonalscape.WinterFoliageTextures$State")
            .getDeclaredConstructor(Object.class, Client.class, DrawManager.class);
        constructor.setAccessible(true);
        Runnable state = (Runnable) constructor.newInstance(owner, client, draws);

        // A temporarily unavailable renderer avoids any GL calls in this unit
        // test while exercising the listener's actual game-state lifetime.
        state.run();
        gameState[0] = GameState.LOADING;
        state.run();
        state.run();
        assertEquals("Loading must not unregister the winter material", 0, unregistered[0]);
        gameState[0] = GameState.LOGGED_IN;
        state.run();
        assertEquals(0, unregistered[0]);
        gameState[0] = GameState.LOGIN_SCREEN;
        state.run();
        assertEquals("Logout must release the listener", 1, unregistered[0]);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void explicitDisableStillReleasesTheMaterialDuringLoading() throws Exception
    {
        Object owner = new Object();
        int[] unregistered = {0};
        DrawManager draws = new DrawManager()
        {
            @Override public void unregisterEveryFrameListener(Runnable listener) { unregistered[0]++; }
        };
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, method, args) -> method.getName().equals("getGameState") ? GameState.LOADING : null);
        Constructor<?> constructor = Class.forName("com.seasonalscape.WinterFoliageTextures$State")
            .getDeclaredConstructor(Object.class, Client.class, DrawManager.class);
        constructor.setAccessible(true);
        Runnable state = (Runnable) constructor.newInstance(owner, client, draws);
        Field statesField = WinterFoliageTextures.class.getDeclaredField("STATES");
        statesField.setAccessible(true);
        Map<Object, Object> states = (Map<Object, Object>) statesField.get(null);
        states.put(owner, state);
        try
        {
            WinterFoliageTextures.update(owner, client, false);
            state.run();
            assertEquals(1, unregistered[0]);
            assertFalse(states.containsKey(owner));
        }
        finally { states.remove(owner); }
    }

    @Test
    public void whiteningPreservesCutoutsAndAlphaWithoutChangingTheSource()
    {
        byte[] source = {(byte) 50, (byte) 100, (byte) 20, (byte) 255,
            12, 34, 56, 0, 30, 70, 10, (byte) 128};
        byte[] backup = source.clone();
        byte[] result = WinterFoliageTextures.whiten(source);
        assertArrayEquals(backup, source);
        assertEquals(255, result[3] & 255);
        assertEquals(128, result[11] & 255);
        for (int i = 4; i < 8; i++) { assertEquals("Transparent pixels remain exact", source[i], result[i]); }
        assertTrue((result[1] & 255) > (result[0] & 255));
        assertTrue("Only a trace of green remains", (result[1] & 255) - (result[2] & 255) <= 4);
    }

    @Test
    public void retainsOrderedLeafDetailWithinASoftWhiteRange()
    {
        int previous = -1;
        for (int level = 0; level <= 255; level++)
        {
            byte[] source = {(byte) level, (byte) level, (byte) level, (byte) 255};
            byte[] result = WinterFoliageTextures.whiten(source);
            int value = result[0] & 255;
            assertTrue(value >= 180 && value <= 230);
            assertTrue(value >= previous);
            assertEquals(result[0], result[1]);
            assertEquals(result[1], result[2]);
            previous = value;
        }
        byte[] dark = WinterFoliageTextures.whiten(new byte[]{0, 0, 0, (byte) 255});
        byte[] light = WinterFoliageTextures.whiten(new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255});
        assertEquals("Veins and highlights retain visible detail", 50, (light[0] & 255) - (dark[0] & 255));
    }

    @Test
    public void roundTripRestoresEveryUntouchedPixelExactly()
    {
        byte[] original = {10, 50, 20, (byte) 255, 0, 0, 0, 0, 90, 100, 30, (byte) 255};
        byte[] applied = WinterFoliageTextures.whiten(original);
        byte[] current = applied.clone();
        assertTrue(WinterFoliageTextures.restorePixels(current, original, applied));
        assertArrayEquals(original, current);
        assertFalse(WinterFoliageTextures.restorePixels(current, original, applied));
    }

    @Test
    public void restorationPreservesAnotherModifiersWholePixelAndAlpha()
    {
        byte[] original = {10, 50, 20, (byte) 255, 70, 90, 40, (byte) 255, 90, 100, 30, (byte) 255};
        byte[] applied = WinterFoliageTextures.whiten(original);
        byte[] current = applied.clone();
        current[0] = 12; // An independent material adjustment changed this pixel.
        current[7] = 0; // Another modifier changed this leaf's cutout shape.
        byte[] externallyModified = current.clone();
        assertTrue(WinterFoliageTextures.restorePixels(current, original, applied));
        for (int i = 0; i < 8; i++) { assertEquals(externallyModified[i], current[i]); }
        for (int i = 8; i < 12; i++) { assertEquals(original[i], current[i]); }
    }
}
