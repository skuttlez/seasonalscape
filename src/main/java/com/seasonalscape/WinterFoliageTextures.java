package com.seasonalscape;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.WeakHashMap;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.RuneLite;
import net.runelite.client.plugins.gpu.GpuPlugin;
import net.runelite.client.ui.DrawManager;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.lwjgl.opengl.GL33C.*;

/** Stock-GPU leaf materials only: keeps the original geometry, detail and cutout mask. */
final class WinterFoliageTextures
{
    private static final Logger LOG = LoggerFactory.getLogger(WinterFoliageTextures.class);
    private static final Map<Object, State> STATES = new WeakHashMap<>();
    private static final int[] LEAF_LAYERS = {8, 30, 60};
    private static final int SIZE = 128, LAYER_BYTES = SIZE * SIZE * 4;

    private WinterFoliageTextures() {}

    /** Called on the client thread. The actual upload occurs with the GPU context current. */
    static void update(Object owner, Client client, boolean enabled)
    {
        GameState gameState = client.getGameState();
        // Crossing a region boundary keeps the requested material active even
        // while the new scene is loading. Explicit disable still restores it.
        if (!enabled || (gameState != GameState.LOGGED_IN && gameState != GameState.LOADING)
            || !(client.getDrawCallbacks() instanceof GpuPlugin))
        {
            restore(owner);
            return;
        }
        State state = STATES.get(owner);
        if (state == null)
        {
            DrawManager draws = RuneLite.getInjector().getInstance(DrawManager.class);
            state = new State(owner, client, draws);
            STATES.put(owner, state);
            draws.registerEveryFrameListener(state);
        }
        state.enabled = true;
    }

    /** Restoration is deferred to the next frame so it never runs without the GL context. */
    static void restore(Object owner)
    {
        State state = STATES.get(owner);
        if (state != null) { state.enabled = false; }
    }

    static byte[] whiten(byte[] original)
    {
        if (original.length % 4 != 0) { throw new IllegalArgumentException("Expected RGBA pixels"); }
        byte[] result = original.clone();
        for (int p = 0; p < original.length; p += 4)
        {
            if (original[p + 3] == 0) { continue; }
            int r = original[p] & 255, g = original[p + 1] & 255, b = original[p + 2] & 255;
            int luminance = (54 * r + 183 * g + 19 * b + 128) >> 8;
            int white = 180 + luminance * 50 / 255;
            // Keep the veins and shadows, with only two percent of the source hue.
            result[p] = (byte) (white + Math.round((r - luminance) / 50f));
            result[p + 1] = (byte) (white + Math.round((g - luminance) / 50f));
            result[p + 2] = (byte) (white + Math.round((b - luminance) / 50f));
        }
        return result;
    }

    /** Restores only entire pixels which still match our last upload. */
    static boolean restorePixels(byte[] current, byte[] original, byte[] applied)
    {
        if (current.length != original.length || current.length != applied.length || current.length % 4 != 0)
        {
            throw new IllegalArgumentException("Mismatched RGBA layers");
        }
        boolean changed = false;
        for (int p = 0; p < current.length; p += 4)
        {
            if (current[p] == applied[p] && current[p + 1] == applied[p + 1]
                && current[p + 2] == applied[p + 2] && current[p + 3] == applied[p + 3])
            {
                for (int channel = 0; channel < 4; channel++)
                {
                    changed |= current[p + channel] != original[p + channel];
                    current[p + channel] = original[p + channel];
                }
            }
        }
        return changed;
    }

    private static final class State implements Runnable
    {
        private final WeakReference<Object> owner;
        private final Client client;
        private final DrawManager draws;
        private boolean enabled = true;
        private Object renderer;
        private GLCapabilities context;
        private int textureId;
        private byte[][] original, applied;

        private State(Object owner, Client client, DrawManager draws)
        {
            this.owner = new WeakReference<>(owner);
            this.client = client;
            this.draws = draws;
        }

        @Override
        public void run()
        {
            GameState gameState = client.getGameState();
            boolean requested = enabled && owner.get() != null
                && (gameState == GameState.LOGGED_IN || gameState == GameState.LOADING);
            Object currentRenderer = client.getDrawCallbacks();
            if (!(currentRenderer instanceof GpuPlugin))
            {
                // Switching off GPU destroys its context and owned texture array.
                discard();
                if (!requested) { finish(); }
                return;
            }
            GLCapabilities currentContext;
            try { currentContext = GL.getCapabilities(); }
            catch (IllegalStateException noContext)
            {
                if (!requested && original == null) { finish(); }
                return;
            }
            int activeUnit = glGetInteger(GL_ACTIVE_TEXTURE);
            glActiveTexture(GL_TEXTURE1);
            int binding = glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);
            try
            {
                if (binding == 0)
                {
                    discard();
                    if (!requested) { finish(); }
                    return;
                }
                if (currentContext != context || currentRenderer != renderer || binding != textureId)
                {
                    // Never restore a deleted texture's backup into a new array/context.
                    discard();
                    context = currentContext;
                    renderer = currentRenderer;
                    textureId = binding;
                }
                if (!requested && original == null) { finish(); return; }
                if (requested && original != null) { return; }

                int width = glGetTexLevelParameteri(GL_TEXTURE_2D_ARRAY, 0, GL_TEXTURE_WIDTH);
                int height = glGetTexLevelParameteri(GL_TEXTURE_2D_ARRAY, 0, GL_TEXTURE_HEIGHT);
                int depth = glGetTexLevelParameteri(GL_TEXTURE_2D_ARRAY, 0, GL_TEXTURE_DEPTH);
                if (width != SIZE || height != SIZE || depth <= 60 || depth > 256)
                {
                    discard();
                    if (!requested) { finish(); }
                    return;
                }
                byte[][] pixels = readLayers(depth);
                if (requested)
                {
                    original = pixels;
                    applied = new byte[pixels.length][];
                    for (int layer = 0; layer < pixels.length; layer++) { applied[layer] = whiten(pixels[layer]); }
                    upload(applied);
                }
                else
                {
                    boolean changed = false;
                    for (int layer = 0; layer < pixels.length; layer++)
                    {
                        changed |= restorePixels(pixels[layer], original[layer], applied[layer]);
                    }
                    if (changed) { upload(pixels); }
                    discard();
                    finish();
                }
            }
            catch (RuntimeException error)
            {
                // A renderer incompatibility must not trigger another GL failure every frame.
                LOG.warn("Unable to update winter leaf textures", error);
                enabled = false;
                finish();
            }
            finally
            {
                glBindTexture(GL_TEXTURE_2D_ARRAY, binding);
                glActiveTexture(activeUnit);
            }
        }

        private byte[][] readLayers(int depth)
        {
            int packBuffer = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
            int[] parameters = {GL_PACK_ALIGNMENT, GL_PACK_ROW_LENGTH, GL_PACK_IMAGE_HEIGHT,
                GL_PACK_SKIP_PIXELS, GL_PACK_SKIP_ROWS, GL_PACK_SKIP_IMAGES};
            int[] saved = new int[parameters.length];
            ByteBuffer buffer = MemoryUtil.memAlloc(LAYER_BYTES * depth);
            try
            {
                glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
                for (int p = 0; p < parameters.length; p++)
                {
                    saved[p] = glGetInteger(parameters[p]);
                    glPixelStorei(parameters[p], p == 0 ? 4 : 0);
                }
                glGetTexImage(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
                byte[][] layers = new byte[LEAF_LAYERS.length][LAYER_BYTES];
                for (int layer = 0; layer < LEAF_LAYERS.length; layer++)
                {
                    buffer.position(LEAF_LAYERS[layer] * LAYER_BYTES);
                    buffer.get(layers[layer]);
                }
                return layers;
            }
            finally
            {
                for (int p = 0; p < parameters.length; p++) { glPixelStorei(parameters[p], saved[p]); }
                glBindBuffer(GL_PIXEL_PACK_BUFFER, packBuffer);
                MemoryUtil.memFree(buffer);
            }
        }

        private void upload(byte[][] layers)
        {
            int unpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
            int[] parameters = {GL_UNPACK_ALIGNMENT, GL_UNPACK_ROW_LENGTH, GL_UNPACK_IMAGE_HEIGHT,
                GL_UNPACK_SKIP_PIXELS, GL_UNPACK_SKIP_ROWS, GL_UNPACK_SKIP_IMAGES};
            int[] saved = new int[parameters.length];
            ByteBuffer buffer = MemoryUtil.memAlloc(LAYER_BYTES);
            try
            {
                glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
                for (int p = 0; p < parameters.length; p++)
                {
                    saved[p] = glGetInteger(parameters[p]);
                    glPixelStorei(parameters[p], p == 0 ? 4 : 0);
                }
                for (int layer = 0; layer < LEAF_LAYERS.length; layer++)
                {
                    buffer.clear();
                    buffer.put(layers[layer]).flip();
                    glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, LEAF_LAYERS[layer],
                        SIZE, SIZE, 1, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
                }
                // Preserve the GPU's anisotropic/mipmap sampling of the cutout leaves.
                glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
            }
            finally
            {
                for (int p = 0; p < parameters.length; p++) { glPixelStorei(parameters[p], saved[p]); }
                glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
                MemoryUtil.memFree(buffer);
            }
        }

        private void discard()
        {
            original = applied = null;
            renderer = null;
            context = null;
            textureId = 0;
        }

        private void finish()
        {
            draws.unregisterEveryFrameListener(this);
            Object key = owner.get();
            if (key != null && STATES.get(key) == this) { STATES.remove(key); }
        }
    }
}
