package com.seasonalscape;

import java.util.BitSet;
import java.util.Map;
import java.util.WeakHashMap;
import net.runelite.api.Model;

/** Client-thread-only material changes on roof models selected by the caller. */
final class WinterRoofMaterial
{
    private static final Map<Object, State> STATES = new WeakHashMap<>();

    private WinterRoofMaterial() {}

    /** Bit 0 means affected; bit 1 means a GPU upload is required. */
    static int apply(Object owner, Model model)
    {
        if (model == null) { return 0; }
        float[] x = model.getVerticesX(), y = model.getVerticesY(), z = model.getVerticesZ();
        int[] a = model.getFaceIndices1(), b = model.getFaceIndices2(), c = model.getFaceIndices3();
        int[][] channels = {model.getFaceColors1(), model.getFaceColors2(), model.getFaceColors3()};
        if (x == null || y == null || z == null || a == null || b == null || c == null
            || channels[0] == null || channels[1] == null || channels[2] == null) { return 0; }
        short[] textures = model.getFaceTextures();
        int count = Math.min(model.getFaceCount(), Math.min(a.length, Math.min(b.length, c.length)));
        for (int[] channel : channels) { count = Math.min(count, channel.length); }
        if (textures != null) { count = Math.min(count, textures.length); }
        int vertices = Math.min(model.getVerticesCount(), Math.min(x.length, Math.min(y.length, z.length)));
        State state = STATES.computeIfAbsent(owner, ignored -> new State());
        ColorState[] colors = new ColorState[3];
        int[] desired = new int[3];
        TextureState textureState = textures == null ? null : state.textures.get(textures);
        int flags = 0;
        for (int face = 0; face < count; face++)
        {
            int third = original(state, channels[2], face);
            if (third == -2) { continue; }
            int ai = a[face], bi = b[face], ci = c[face];
            if (ai < 0 || bi < 0 || ci < 0 || ai >= vertices || bi >= vertices || ci >= vertices) { continue; }
            double ux = x[bi] - x[ai], uy = y[bi] - y[ai], uz = z[bi] - z[ai];
            double vx = x[ci] - x[ai], vy = y[ci] - y[ai], vz = z[ci] - z[ai];
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            double lengthSquared = nx * nx + ny * ny + nz * nz;
            if (ny >= 0 || ny * ny <= 0.35 * 0.35 * lengthSquared) { continue; }

            if (textures != null && textureState == null)
            {
                textureState = state.textures.computeIfAbsent(textures, TextureState::new);
            }
            int texture = textureState == null ? -1 : textureState.original[face];
            boolean flat = third == -1;
            boolean available = textureState == null || textureState.available(textures, face);
            for (int channel = 0; channel < channels.length; channel++)
            {
                // Flat faces are shaded entirely from channel A. Preserve the
                // unused channel B and the channel C sentinel exactly.
                if (flat && channel > 0) { continue; }
                ColorState saved = colors[channel];
                if (saved == null)
                {
                    saved = state.colors.computeIfAbsent(channels[channel], ColorState::new);
                    colors[channel] = saved;
                }
                int source = saved.original[face];
                available &= saved.available(channels[channel], face);
                // Textured faces store brightness; packed colors here belong
                // to another modifier and must not be reinterpreted as light.
                if (source < 0 || (texture >= 0 && source > 127)) { available = false; }
                desired[channel] = snow(source & 127);
            }
            if (!available) { continue; }

            boolean affected = texture >= 0;
            boolean mutated = false;
            if (textureState != null) { mutated |= textureState.set(textures, face, (short) -1); }
            for (int channel = 0; channel < channels.length; channel++)
            {
                if (flat && channel > 0) { continue; }
                affected |= desired[channel] != colors[channel].original[face];
                mutated |= colors[channel].set(channels[channel], face, desired[channel]);
            }
            if (affected) { flags |= 1; }
            if (mutated) { flags |= 2; }
        }
        return flags;
    }

    /** Restores only values still owned by this modifier; returns whether anything changed. */
    static boolean restore(Object owner)
    {
        State state = STATES.remove(owner);
        if (state == null) { return false; }
        boolean changed = false;
        for (Map.Entry<int[], ColorState> entry : state.colors.entrySet())
        {
            changed |= entry.getValue().restore(entry.getKey());
        }
        for (Map.Entry<short[], TextureState> entry : state.textures.entrySet())
        {
            changed |= entry.getValue().restore(entry.getKey());
        }
        return changed;
    }

    private static int original(State state, int[] values, int face)
    {
        ColorState colors = state.colors.get(values);
        return colors == null ? values[face] : colors.original[face];
    }

    private static int snow(int brightness)
    {
        return 36 << 10 | 86 + brightness * 22 / 127;
    }

    private static final class State
    {
        private final Map<int[], ColorState> colors = new WeakHashMap<>();
        private final Map<short[], TextureState> textures = new WeakHashMap<>();
    }

    private static final class ColorState
    {
        private final int[] original, last;
        private final BitSet touched = new BitSet();
        private final BitSet conflicted = new BitSet();

        private ColorState(int[] values) { original = values.clone(); last = values.clone(); }

        private boolean available(int[] values, int face)
        {
            if (values[face] != last[face]) { conflicted.set(face); }
            return !conflicted.get(face);
        }

        private boolean set(int[] values, int face, int value)
        {
            boolean changed = values[face] != value;
            values[face] = last[face] = value;
            touched.set(face);
            return changed;
        }

        private boolean restore(int[] values)
        {
            boolean changed = false;
            for (int face = touched.nextSetBit(0); face >= 0; face = touched.nextSetBit(face + 1))
            {
                if (!conflicted.get(face) && values[face] == last[face])
                {
                    changed |= values[face] != original[face];
                    values[face] = original[face];
                }
            }
            return changed;
        }
    }

    private static final class TextureState
    {
        private final short[] original, last;
        private final BitSet touched = new BitSet();
        private final BitSet conflicted = new BitSet();

        private TextureState(short[] values) { original = values.clone(); last = values.clone(); }

        private boolean available(short[] values, int face)
        {
            if (values[face] != last[face]) { conflicted.set(face); }
            return !conflicted.get(face);
        }

        private boolean set(short[] values, int face, short value)
        {
            boolean changed = values[face] != value;
            values[face] = last[face] = value;
            touched.set(face);
            return changed;
        }

        private boolean restore(short[] values)
        {
            boolean changed = false;
            for (int face = touched.nextSetBit(0); face >= 0; face = touched.nextSetBit(face + 1))
            {
                if (!conflicted.get(face) && values[face] == last[face])
                {
                    changed |= values[face] != original[face];
                    values[face] = original[face];
                }
            }
            return changed;
        }
    }
}
