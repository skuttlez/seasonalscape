package com.seasonalscape;

import java.util.Arrays;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.coords.LocalPoint;

/** Fits each decoration to the ground without modifying its cached source. */
final class GroundCoverPlacement
{
    private GroundCoverPlacement()
    {
    }

    static Model model(Client client, Season season, int variant, LocalPoint location, int[][] heights)
    {
        float centerHeight = height(heights, location.getX(), location.getY());
        if (!Float.isFinite(centerHeight))
        {
            return null;
        }
        ModelData source = client.loadModelData(27835);
        if (source == null || source.getVerticesCount() < 3 || source.getFaceCount() == 0
            || source.getVerticesCount() > 8192 || source.getFaceCount() > 8192)
        {
            return null;
        }
        // Four overlapping leaf layers fill each autumn pile without creating
        // more world objects or changing the spacing between piles.
        ModelData[] layers = new ModelData[season == Season.AUTUMN ? 4 : 1];
        for (int layer = 0; layer < layers.length; layer++)
        {
            ModelData data = source.shallowCopy().cloneVertices().cloneColors();
            if (data.getFaceTextures() != null)
            {
                data.cloneTextures();
                Arrays.fill(data.getFaceTextures(), (short) -1);
            }
            data.cloneTransparencies(true);
            Arrays.fill(data.getFaceTransparencies(), (byte) 0);
            GroundCover.reshapeFlakes(source, data, season, variant + layer * 4);

            float[] x = data.getVerticesX();
            float[] y = data.getVerticesY();
            float[] z = data.getVerticesZ();
            for (int vertex = 0; vertex < data.getVerticesCount(); vertex++)
            {
                float groundHeight = height(heights, location.getX() + x[vertex],
                    location.getY() + z[vertex]);
                if (!Float.isFinite(groundHeight)) { return null; }
                // Negative Y is uphill. Slightly separate the overlapping
                // layers to keep their leaf faces from flickering.
                y[vertex] += groundHeight - centerHeight - layer * 0.8f;
            }
            data.translate(0, 0, 0);
            layers[layer] = data;
        }
        ModelData data = layers.length == 1 ? layers[0] : client.mergeModels(layers);
        if (data == null) { return null; }
        return data.light(80, ModelData.DEFAULT_CONTRAST,
            ModelData.DEFAULT_X, ModelData.DEFAULT_Y, ModelData.DEFAULT_Z);
    }

    /** Bilinear terrain height at a position in ordinary scene-local units. */
    static float height(int[][] heights, float localX, float localY)
    {
        int x = (int) Math.floor(localX / 128.0f);
        int y = (int) Math.floor(localY / 128.0f);
        if (heights == null || x < 0 || y < 0 || x + 1 >= heights.length
            || heights[x] == null || heights[x + 1] == null
            || y + 1 >= heights[x].length || y + 1 >= heights[x + 1].length)
        {
            return Float.NaN;
        }
        float offsetX = localX / 128.0f - x;
        float offsetY = localY / 128.0f - y;
        float south = heights[x][y] * (1 - offsetX) + heights[x + 1][y] * offsetX;
        float north = heights[x][y + 1] * (1 - offsetX) + heights[x + 1][y + 1] * offsetX;
        return south * (1 - offsetY) + north * offsetY;
    }
}
