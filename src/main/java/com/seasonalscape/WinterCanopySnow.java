package com.seasonalscape;

import java.util.BitSet;
import java.util.Map;
import java.util.WeakHashMap;
import net.runelite.api.Model;

/** Client-thread-only snow on solid canopy tops, preserving foliage cutout edges. */
final class WinterCanopySnow
{
    private static final Map<Object, State> STATES = new WeakHashMap<>();

    private WinterCanopySnow() {}

    /** Bit 0 means affected; bit 1 means a GPU upload is required. */
    static int apply(Object owner, Model model)
    {
        short[] textures = model.getFaceTextures();
        if (textures == null) { return 0; }
        int[][] channels = {model.getFaceColors1(), model.getFaceColors2(), model.getFaceColors3()};
        if (channels[0] == null || channels[1] == null || channels[2] == null) { return 0; }
        State state = STATES.computeIfAbsent(owner, ignored -> new State());
        Textures saved = state.textures.computeIfAbsent(textures, Textures::new);
        float[] ys = model.getVerticesY();
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        int[] a = model.getFaceIndices1(), b = model.getFaceIndices2(), c = model.getFaceIndices3();
        if (ys != null && a != null && b != null && c != null)
        {
            for (int face = 0; face < textures.length; face++)
            {
                if (saved.original[face] != 30) { continue; }
                for (int vertex : new int[]{a[face], b[face], c[face]})
                {
                    minY = Math.min(minY, ys[vertex]);
                    maxY = Math.max(maxY, ys[vertex]);
                }
            }
        }
        float capMinY = Float.POSITIVE_INFINITY, capMaxY = Float.NEGATIVE_INFINITY;
        if (ys != null && a != null && b != null && c != null)
        {
            for (int face = 0; face < textures.length; face++)
            {
                if (saved.original[face] != 60
                    && (saved.original[face] != 30 || !willowCap(model, face, minY, maxY))) { continue; }
                for (int vertex : new int[]{a[face], b[face], c[face]})
                {
                    capMinY = Math.min(capMinY, ys[vertex]);
                    capMaxY = Math.max(capMaxY, ys[vertex]);
                }
            }
        }
        int flags = 0;
        for (int face = 0; face < textures.length; face++)
        {
            int texture = saved.original[face];
            if (texture != 60 && (texture != 30 || !willowCap(model, face, minY, maxY))) { continue; }
            if (!saved.available(textures, face)) { continue; }
            boolean brightness = true;
            for (int[] channel : channels)
            {
                ColorState colors = state.colors.get(channel);
                int original = colors == null ? channel[face] : colors.original[face];
                if (original > 127) { brightness = false; break; }
            }
            // Existing HSL tint belongs to another modifier; do not reinterpret it as lighting.
            if (!brightness) { continue; }
            if (textures[face] != -1) { flags |= 2; }
            saved.set(textures, face, (short) -1);
            flags |= 1;
            ColorState first = state.colors.get(channels[0]), third = state.colors.get(channels[2]);
            int firstOriginal = first == null ? channels[0][face] : first.original[face];
            int thirdOriginal = third == null ? channels[2][face] : third.original[face];
            boolean flat = thirdOriginal == -1;
            boolean aliased = channels[0] == channels[1] || channels[0] == channels[2]
                || channels[1] == channels[2];
            for (int channelIndex = 0; channelIndex < channels.length; channelIndex++)
            {
                int[] channel = channels[channelIndex];
                ColorState colors = state.colors.computeIfAbsent(channel, ColorState::new);
                int original = flat ? firstOriginal : colors.original[face];
                if (original < 0 || thirdOriginal == -2) { continue; }
                double height = 1;
                if (Float.isFinite(capMinY) && Float.isFinite(capMaxY) && capMaxY > capMinY)
                {
                    float vertexY = aliased ? (ys[a[face]] + ys[b[face]] + ys[c[face]]) / 3f
                        : ys[channelIndex == 0 ? a[face] : channelIndex == 1 ? b[face] : c[face]];
                    height = Math.max(0, Math.min(1, (capMaxY - vertexY) / (capMaxY - capMinY)));
                    height = height * height * (3 - 2 * height);
                }
                // Gouraud shading blends pale snow at the apex into the canopy's
                // muted green rim instead of drawing an abrupt white polygon edge.
                int rimLight = 30 + original * 10 / 127;
                int topLight = 88 + original * 16 / 127;
                int light = (int) Math.round(rimLight + (topLight - rimLight) * height);
                // Snow stays below the vegetation saturation threshold so the
                // general winter foliage pass preserves this edge gradient.
                int saturation = (int) Math.round(1 - height);
                int desired = 16 << 10 | saturation << 7 | light;
                int before = channel[face];
                colors.saved.apply(channel, face, desired);
                if (channel[face] != before) { flags |= 2; }
            }
        }
        return flags;
    }

    static void restore(Object owner)
    {
        State state = STATES.remove(owner);
        if (state == null) { return; }
        for (Map.Entry<int[], ColorState> entry : state.colors.entrySet())
        {
            entry.getValue().saved.restore(entry.getKey());
        }
        for (Map.Entry<short[], Textures> entry : state.textures.entrySet())
        {
            entry.getValue().restore(entry.getKey());
        }
    }

    private static boolean willowCap(Model model, int face, float minY, float maxY)
    {
        float[] x = model.getVerticesX(), y = model.getVerticesY(), z = model.getVerticesZ();
        int[] ai = model.getFaceIndices1(), bi = model.getFaceIndices2(), ci = model.getFaceIndices3();
        if (x == null || y == null || z == null || ai == null || bi == null || ci == null
            || !Float.isFinite(minY) || !Float.isFinite(maxY)) { return false; }
        int a = ai[face], b = bi[face], c = ci[face];
        // Model Y decreases upwards. Exclude the lower hanging leaf tiers.
        if ((y[a] + y[b] + y[c]) / 3f > (minY + maxY) / 2f) { return false; }
        double ux = x[b] - x[a], uy = y[b] - y[a], uz = z[b] - z[a];
        double vx = x[c] - x[a], vy = y[c] - y[a], vz = z[c] - z[a];
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        return length > 0 && ny / length < -0.85;
    }

    private static final class State
    {
        private final Map<short[], Textures> textures = new WeakHashMap<>();
        private final Map<int[], ColorState> colors = new WeakHashMap<>();
    }

    private static final class ColorState
    {
        private final int[] original;
        private final SeasonalSceneRecolorer.Colors saved;
        private ColorState(int[] values)
        {
            original = values.clone();
            saved = new SeasonalSceneRecolorer.Colors(values);
        }
    }

    private static final class Textures
    {
        private final short[] original, last;
        private final BitSet conflicted = new BitSet();
        private Textures(short[] values) { original = values.clone(); last = values.clone(); }
        private boolean available(short[] values, int face)
        {
            if (values[face] != last[face]) { conflicted.set(face); }
            return !conflicted.get(face);
        }
        private void set(short[] values, int face, short texture) { values[face] = last[face] = texture; }
        private void restore(short[] values)
        {
            for (int face = 0; face < values.length; face++)
            {
                if (!conflicted.get(face) && values[face] == last[face]) { values[face] = original[face]; }
            }
        }
    }
}
