package com.seasonalscape;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.coords.LocalPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class WinterSnowVisibilityTest
{
    @Test
    public void rejectsStructuresBehindCameraAndOutsideThePaddedViewport()
    {
        WinterSnowVisibility view = view(0, 0);
        assertTrue(view.visibleSphere(0, 1000, 0, 20, false));
        assertFalse(view.visibleSphere(0, -1000, 0, 20, false));
        assertFalse(view.visibleSphere(1300, 1000, 0, 20, false));
        assertFalse(view.visibleSphere(-1300, 1000, 0, 20, false));
        assertFalse(view.visibleSphere(0, 1000, 1300, 20, false));
        assertFalse(view.visibleSphere(0, 1000, -1300, 20, false));
    }

    @Test
    public void rotatesAndPitchesVisibilityWithTheCamera()
    {
        WinterSnowVisibility west = view(0, Math.PI / 2);
        assertTrue(west.visibleSphere(-1000, 0, 0, 20, false));
        assertFalse(west.visibleSphere(1000, 0, 0, 20, false));
        WinterSnowVisibility tilted = view(Math.PI / 4, 0);
        assertTrue(tilted.visibleSphere(0, 1000, 1000, 20, false));
        assertFalse(tilted.visibleSphere(0, 1000, -1000, 20, false));
    }

    @Test
    public void preservesPartlyVisibleCornersAndNearPlaneIntersections()
    {
        WinterSnowVisibility view = view(0, 0);
        assertFalse("The center is outside two viewport edges", view.visibleSphere(1000, 1000, 800, 0, false));
        assertTrue("Part of the structure is still visible", view.visibleSphere(1000, 1000, 800, 60, false));
        assertTrue("Camera inside bounds must not hide the roof", view.visibleSphere(0, 0, 0, 100, false));
        assertTrue(view.visibleSphere(0, 40, 0, 20, false));
        assertFalse(view.visibleSphere(0, 10, 0, 10, false));
    }

    @Test
    public void keepsHighRoofsWhenTheirGroundTileIsBelowTheViewport()
    {
        WinterSnowVisibility view = view(0, 0);
        assertFalse(view.visibleSphere(64, 960, 1200, 0, false));
        assertTrue("The roof above the offscreen ground is visible", view.visibleTile(0, 7, 1200, false));
        assertFalse("An entirely offscreen column still gets culled", view.visibleTile(0, 7, 3000, false));
    }

    @Test
    public void retainsExistingSnowInAWiderFrustumToAvoidEdgeFlicker()
    {
        WinterSnowVisibility view = view(0, 0);
        assertFalse(view.visibleSphere(1050, 1000, 0, 0, false));
        assertTrue(view.visibleSphere(1050, 1000, 0, 0, true));
        assertFalse(view.visibleSphere(1200, 1000, 0, 0, true));
    }

    @Test
    public void distanceCapWorksWithoutAViewportAndHasRetentionHysteresis()
    {
        int player = 4000;
        WinterSnowVisibility view = new WinterSnowVisibility(player, player, 0, 0, 0, 0, 0, 0, 0, 0);
        assertTrue(view.visibleSphere(player + 22 * 128, player, 0, 0, false));
        assertFalse(view.visibleSphere(player + 23 * 128, player, 0, 0, false));
        assertTrue(view.visibleSphere(player + 23 * 128, player, 0, 0, true));
        assertFalse(view.visibleSphere(player + 25 * 128, player, 0, 0, true));
        assertTrue("Part of a large structure overlaps the range", view.visibleSphere(player + 23 * 128, player, 0, 256, false));
    }

    @Test
    public void rescanThresholdTracksMeaningfulMotionZoomAndViewportChanges()
    {
        WinterSnowVisibility previous = view(0, 0);
        assertTrue(previous.similarTo(view(0, 0)));
        assertTrue(previous.similarTo(view(0, 2 * Math.PI - .005)));
        assertFalse(previous.similarTo(view(0, .05)));
        assertFalse(previous.similarTo(view(.05, 0)));
        assertFalse(previous.similarTo(null));
        assertTrue(previous.similarTo(new WinterSnowVisibility(40, 40, 40, 40, 40, 0, 0, 800, 600, 512)));
        assertFalse(previous.similarTo(new WinterSnowVisibility(128, 0, 0, 0, 0, 0, 0, 800, 600, 512)));
        assertFalse(previous.similarTo(new WinterSnowVisibility(0, 0, 0, 128, 0, 0, 0, 800, 600, 512)));
        assertFalse(previous.similarTo(new WinterSnowVisibility(0, 0, 0, 0, 0, 0, 0, 900, 600, 512)));
        assertFalse(previous.similarTo(new WinterSnowVisibility(0, 0, 0, 0, 0, 0, 0, 800, 600, 700)));
    }

    @Test
    public void capturesCpuFourteenBitAnglesAndGpuRadianAngles()
    {
        Map<String, Object> values = new HashMap<>();
        values.put("getViewportWidth", 800);
        values.put("getViewportHeight", 600);
        values.put("getScale", 512);
        values.put("getCameraYaw", 4096);
        Client client = client(values);
        WinterSnowVisibility cpu = WinterSnowVisibility.capture(client, new LocalPoint(0, 0, 0));
        assertTrue(cpu.visibleSphere(-1000, 0, 0, 20, false));
        assertFalse(cpu.visibleSphere(1000, 0, 0, 20, false));
        values.put("isGpu", true);
        values.put("getCameraFpYaw", (float) -Math.PI / 2);
        values.put("getCameraFpX", 128f);
        WinterSnowVisibility gpu = WinterSnowVisibility.capture(client, new LocalPoint(0, 0, 0));
        assertTrue(gpu.visibleSphere(1000, 0, 0, 20, false));
        assertFalse(gpu.visibleSphere(-1000, 0, 0, 20, false));
    }

    @Test
    public void unavailableProjectionFallsBackToDistanceOnly()
    {
        Map<String, Object> values = new HashMap<>();
        WinterSnowVisibility missing = WinterSnowVisibility.capture(client(values), new LocalPoint(0, 0, 0));
        assertTrue(missing.visibleSphere(0, -1000, 0, 20, false));
        values.put("getViewportWidth", 800);
        values.put("getViewportHeight", 600);
        values.put("getScale", 512);
        WinterSnowVisibility subview = WinterSnowVisibility.capture(client(values), new LocalPoint(0, 0, 1));
        assertTrue(subview.visibleSphere(0, -1000, 0, 20, false));
        assertFalse(subview.visibleSphere(0, -4000, 0, 20, false));
    }

    private static WinterSnowVisibility view(double pitch, double yaw)
    {
        return new WinterSnowVisibility(0, 0, 0, 0, 0, pitch, yaw, 800, 600, 512);
    }

    private static Client client(Map<String, Object> values)
    {
        return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, method, args) -> {
                Object value = values.get(method.getName());
                if (value != null) { return value; }
                if (method.getReturnType() == boolean.class) { return false; }
                if (method.getReturnType() == float.class) { return 0f; }
                if (method.getReturnType() == int.class) { return 0; }
                return null;
            });
    }
}
