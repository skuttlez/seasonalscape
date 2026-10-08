package com.seasonalscape;

import net.runelite.api.Client;
import net.runelite.api.coords.LocalPoint;

/** Conservative camera bounds for scheduling snow work, not an occlusion test. */
final class WinterSnowVisibility
{
    static final int NEW_RADIUS = 22;
    static final int RETAIN_RADIUS = 24;
    private static final int TILE_SIZE = 128;
    private static final double ANGLE_UNIT = 2 * Math.PI / 16384;
    private static final double MOTION_ANGLE = Math.PI / 180;
    private static final int MOTION_DISTANCE = TILE_SIZE / 2;
    private static final int ENTER_MARGIN = 96;
    private static final int RETAIN_MARGIN = 160;

    private final int playerX, playerY, viewportWidth, viewportHeight, scale;
    private final double cameraX, cameraY, cameraZ, pitch, yaw;
    private final boolean hasCamera;
    private final Frustum entering, retaining;

    static WinterSnowVisibility capture(Client client, LocalPoint player)
    {
        int width = client.getViewportWidth(), height = client.getViewportHeight(), scale = client.getScale();
        // No usable projection during startup, in unsupported world views, or in test fixtures.
        // Keeping the distance cap here is safer than accidentally hiding every structure.
        if (width <= 0 || height <= 0 || scale <= 0 || player.getWorldView() != 0)
        {
            return new WinterSnowVisibility(player.getX(), player.getY(), 0, 0, 0, 0, 0, 0, 0, 0);
        }
        if (client.isGpu())
        {
            // RuneLite's GPU projection uses floating point positions and angles in radians.
            return new WinterSnowVisibility(player.getX(), player.getY(),
                client.getCameraFpX(), client.getCameraFpY(), client.getCameraFpZ(),
                client.getCameraFpPitch(), client.getCameraFpYaw(), width, height, scale);
        }
        // In RuneLite 1.13.1 integer camera angles use 14-bit Jagex angle units.
        return new WinterSnowVisibility(player.getX(), player.getY(),
            client.getCameraX(), client.getCameraY(), client.getCameraZ(),
            client.getCameraPitch() * ANGLE_UNIT, client.getCameraYaw() * ANGLE_UNIT,
            width, height, scale);
    }

    /** Angles are radians; package access also keeps projection tests independent of a live client. */
    WinterSnowVisibility(int playerX, int playerY, double cameraX, double cameraY, double cameraZ,
        double pitch, double yaw, int viewportWidth, int viewportHeight, int scale)
    {
        this.playerX = playerX;
        this.playerY = playerY;
        this.cameraX = cameraX;
        this.cameraY = cameraY;
        this.cameraZ = cameraZ;
        this.pitch = pitch;
        this.yaw = yaw;
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
        this.scale = scale;
        hasCamera = viewportWidth > 0 && viewportHeight > 0 && scale > 0
            && Double.isFinite(cameraX) && Double.isFinite(cameraY) && Double.isFinite(cameraZ)
            && Double.isFinite(pitch) && Double.isFinite(yaw);
        entering = hasCamera ? new Frustum(pitch, yaw,
            (viewportWidth * .5 + ENTER_MARGIN) / scale, (viewportHeight * .5 + ENTER_MARGIN) / scale) : null;
        retaining = hasCamera ? new Frustum(pitch, yaw,
            (viewportWidth * .5 + RETAIN_MARGIN) / scale, (viewportHeight * .5 + RETAIN_MARGIN) / scale) : null;
    }

    boolean similarTo(WinterSnowVisibility previous)
    {
        return previous != null && hasCamera == previous.hasCamera
            && viewportWidth == previous.viewportWidth && viewportHeight == previous.viewportHeight
            && scale == previous.scale
            && Math.abs(playerX - previous.playerX) < MOTION_DISTANCE
            && Math.abs(playerY - previous.playerY) < MOTION_DISTANCE
            && (!hasCamera || (Math.abs(cameraX - previous.cameraX) < MOTION_DISTANCE
                && Math.abs(cameraY - previous.cameraY) < MOTION_DISTANCE
                && Math.abs(cameraZ - previous.cameraZ) < MOTION_DISTANCE
                && angleDistance(pitch, previous.pitch) < MOTION_ANGLE
                && angleDistance(yaw, previous.yaw) < MOTION_ANGLE));
    }

    /** Includes tile corners, terrain slope, and roofs up to six tiles above this plane. */
    boolean visibleTile(int sceneX, int sceneY, int terrainHeight, boolean retained)
    {
        int x = sceneX * TILE_SIZE + TILE_SIZE / 2, y = sceneY * TILE_SIZE + TILE_SIZE / 2;
        return visibleBox(x, y, terrainHeight - 384, 96, 96, 448, retained);
    }

    boolean visibleSphere(int localX, int localY, int z, int radius, boolean retained)
    {
        // A box enclosing the sphere is intentionally conservative and avoids model bounds queries.
        int extent = Math.max(0, radius);
        return visibleBox(localX, localY, z, extent, extent, extent, retained);
    }

    private boolean visibleBox(int x, int y, int z, int halfX, int halfY, int halfZ, boolean retained)
    {
        double dx = (double) x - playerX, dy = (double) y - playerY;
        // Measure distance to the box, so a roof intersecting the boundary is not discarded.
        double distanceX = Math.max(0, Math.abs(dx) - halfX);
        double distanceY = Math.max(0, Math.abs(dy) - halfY);
        int range = (retained ? RETAIN_RADIUS : NEW_RADIUS) * TILE_SIZE;
        if (distanceX * distanceX + distanceY * distanceY > (double) range * range) { return false; }
        if (!hasCamera) { return true; }
        return (retained ? retaining : entering).intersects(
            x - cameraX, y - cameraY, z - cameraZ, halfX, halfY, halfZ);
    }

    private static double angleDistance(double a, double b)
    {
        return Math.abs(Math.IEEEremainder(a - b, 2 * Math.PI));
    }

    /** Five world-space planes, precomputed once per snapshot; no per-tile allocation/projection. */
    private static final class Frustum
    {
        private final double[][] planes = new double[5][3];

        Frustum(double pitch, double yaw, double horizontalSlope, double verticalSlope)
        {
            double sp = Math.sin(pitch), cp = Math.cos(pitch);
            double sy = Math.sin(yaw), cy = Math.cos(yaw);
            double[] depth = {-sy * cp, cy * cp, sp};
            double[] horizontal = {cy, sy, 0};
            double[] vertical = {sy * sp, -cy * sp, cp};
            for (int axis = 0; axis < 3; axis++)
            {
                planes[0][axis] = depth[axis];
                planes[1][axis] = depth[axis] * horizontalSlope + horizontal[axis];
                planes[2][axis] = depth[axis] * horizontalSlope - horizontal[axis];
                planes[3][axis] = depth[axis] * verticalSlope + vertical[axis];
                planes[4][axis] = depth[axis] * verticalSlope - vertical[axis];
            }
        }

        boolean intersects(double x, double y, double z, int halfX, int halfY, int halfZ)
        {
            for (int i = 0; i < planes.length; i++)
            {
                double[] plane = planes[i];
                double furthest = plane[0] * x + plane[1] * y + plane[2] * z
                    + Math.abs(plane[0]) * halfX + Math.abs(plane[1]) * halfY + Math.abs(plane[2]) * halfZ;
                if (furthest < (i == 0 ? 50 : 0)) { return false; }
            }
            return true;
        }
    }
}
