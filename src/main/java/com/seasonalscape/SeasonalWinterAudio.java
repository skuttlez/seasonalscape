package com.seasonalscape;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.config.ConfigManager;
import org.slf4j.LoggerFactory;

/** Optional, locally generated ambience. Device I/O never runs on the client or UI thread. */
final class SeasonalWinterAudio
{
    private static final Map<Object, State> STATES = new WeakHashMap<>();
    private static final String GROUP = "seasonalscape";
    private static final String ENABLED = "winterAudio";
    private static final String VOLUME = "winterAudioVolume";
    private static final int SAMPLE_RATE = 22050;
    private static final int CHUNK_FRAMES = SAMPLE_RATE / 4;
    private static final long CHUNK_NANOS = CHUNK_FRAMES * 1_000_000_000L / SAMPLE_RATE;

    private SeasonalWinterAudio() {}

    static synchronized void start(Object owner, Client client, ConfigManager configs, AudioPlayer audioPlayer)
    {
        stop(owner);
        State state = new State(client, configs, audioPlayer);
        STATES.put(owner, state);
        state.readSettings();
        state.worker.start();
    }

    /** Call on the client thread; the audio worker reads only the resulting snapshot. */
    static synchronized void update(Object owner, Season season, boolean running)
    {
        State state = STATES.get(owner);
        if (state == null) { return; }
        GameState game = state.client.getGameState();
        state.inWorld = game == GameState.LOGGED_IN || (game == GameState.LOADING && state.inWorld);
        state.winter = running && season == Season.WINTER;
        state.readSettings();
    }

    static synchronized void stop(Object owner)
    {
        State state = STATES.remove(owner);
        if (state == null) { return; }
        state.closed = true;
        state.signal();
        // AudioPlayer owns playback and exposes no stop handle. Stop submitting
        // new chunks; an already-started clip may finish its remaining <=250 ms.
    }

    static synchronized boolean isPlaying(Object owner)
    {
        State state = STATES.get(owner);
        return state != null && state.playing;
    }

    static synchronized String getStatus(Object owner)
    {
        State state = STATES.get(owner);
        return state == null ? "Disabled" : state.status();
    }

    /** Small in-memory PCM WAVs let RuneLite own all access to the audio device. */
    static byte[] wave(byte[] pcm)
    {
        if ((pcm.length & 3) != 0) { throw new IllegalArgumentException("Stereo PCM requires complete frames"); }
        return ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x46464952).putInt(36 + pcm.length).putInt(0x45564157)
            .putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 2)
            .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 4).putShort((short) 4).putShort((short) 16)
            .putInt(0x61746164).putInt(pcm.length).put(pcm).array();
    }

    static boolean hasSignal(byte[] pcm)
    {
        for (byte value : pcm) { if (value != 0) { return true; } }
        return false;
    }

    /** A two-millisecond edge fade avoids clicks between separately owned clips. */
    static void softenEdges(byte[] pcm)
    {
        int frames = pcm.length / 4;
        int fade = Math.min(SAMPLE_RATE / 500, frames / 2);
        for (int frame = 0; frame < fade; frame++)
        {
            double gain = frame / (double) fade;
            for (int channel = 0; channel < 2; channel++)
            {
                for (int offset : new int[]{frame * 4 + channel * 2, (frames - frame - 1) * 4 + channel * 2})
                {
                    int sample = (short) ((pcm[offset] & 255) | (pcm[offset + 1] << 8));
                    int scaled = (int) Math.round(sample * gain);
                    pcm[offset] = (byte) scaled;
                    pcm[offset + 1] = (byte) (scaled >> 8);
                }
            }
        }
    }

    static final class State
    {
        final Client client;
        final ConfigManager configs;
        final AudioPlayer audioPlayer;
        final Object wake = new Object();
        final Thread worker;
        volatile boolean enabled, inWorld, winter, closed, playing, failed;
        volatile int volume = 25;

        State(Client client, ConfigManager configs, AudioPlayer audioPlayer)
        {
            this.client = client;
            this.configs = configs;
            this.audioPlayer = audioPlayer;
            worker = new Thread(this::run, "SeasonalScape winter audio");
            worker.setDaemon(true);
        }

        synchronized void readSettings()
        {
            boolean nextEnabled = Boolean.parseBoolean(configs.getConfiguration(GROUP, ENABLED));
            if (nextEnabled && !enabled) { failed = false; }
            enabled = nextEnabled;
            String storedVolume = configs.getConfiguration(GROUP, VOLUME);
            try { volume = storedVolume == null ? 25 : Math.max(0, Math.min(100, Integer.parseInt(storedVolume))); }
            catch (NumberFormatException ignored) { volume = 25; }
            signal();
        }

        boolean wanted() { return !closed && enabled && winter && inWorld && volume > 0 && !failed; }

        String status()
        {
            if (closed) { return "Disabled"; }
            if (!enabled) { return "Winter audio is off"; }
            if (failed) { return "Audio unavailable. Toggle off and on to retry."; }
            if (!inWorld) { return "Ready when you log in"; }
            if (!winter) { return "Ready when winter is active"; }
            if (volume == 0) { return "Muted"; }
            return playing ? "Occasional snow and ice sounds are enabled" : "Starting winter ambience";
        }

        void signal() { synchronized (wake) { wake.notifyAll(); } }

        void run()
        {
            while (!closed)
            {
                synchronized (wake)
                {
                    while (!closed && !wanted())
                    {
                        try { wake.wait(); }
                        catch (InterruptedException ignored) { /* Recheck lifecycle state. */ }
                    }
                }
                if (closed) { return; }
                play();
            }
        }

        void play()
        {
            play(new SnowSound(0x534e4f5741554449L));
        }

        void play(SnowSound sound)
        {
            try
            {
                if (!wanted()) { return; }
                playing = true;
                byte[] buffer = new byte[CHUNK_FRAMES * 4];
                while (wanted())
                {
                    sound.render(buffer, volume / 100f * 0.8f);
                    if (!wanted()) { break; }
                    if (hasSignal(buffer))
                    {
                        softenEdges(buffer);
                        try (ByteArrayInputStream input = new ByteArrayInputStream(wave(buffer)))
                        {
                            // AudioPlayer's gain is decibels. Volume is already
                            // applied to PCM, so leave device gain at unity.
                            audioPlayer.play(input, 0);
                        }
                    }
                    // AudioPlayer starts a finite clip asynchronously. Pace even
                    // silent chunks, from completion of this submission, so a
                    // slow device never triggers a burst of catch-up playback.
                    if (!awaitChunk(System.nanoTime() + CHUNK_NANOS)) { break; }
                }
            }
            catch (Exception error)
            {
                failed = true;
                LoggerFactory.getLogger(SeasonalWinterAudio.class)
                    .warn("Winter ambience audio output is unavailable: {}", error.toString());
            }
            finally
            {
                playing = false;
            }
        }

        boolean awaitChunk(long deadline)
        {
            synchronized (wake)
            {
                while (wanted())
                {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) { return true; }
                    try { wake.wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L)); }
                    catch (InterruptedException ignored) { /* Recheck lifecycle and monotonic deadline. */ }
                }
            }
            return false;
        }
    }

    /** Deterministic, isolated snow-settling accents and faint icy overtones, with silent gaps. */
    static final class SnowSound
    {
        private static final int SNOW_FRAMES = SAMPLE_RATE * 3 / 4;
        private static final int ICE_FRAMES = SAMPLE_RATE * 4;
        private static final int ICE_FADE_FRAMES = SAMPLE_RATE / 4;
        private final Random random;
        private double snowLeft, snowRight, gain, chime, chimePhase, chimeStep;
        private int snowFrame = SNOW_FRAMES;
        private int iceFrame = ICE_FRAMES;
        private long sample;
        private long nextSnow = SAMPLE_RATE * 4L;
        private long nextChime = SAMPLE_RATE * 9L;

        SnowSound(long seed) { random = new Random(seed); }

        boolean isSilent() { return gain < 0.0001; }

        void render(byte[] pcm, float desiredGain)
        {
            if ((pcm.length & 3) != 0) { throw new IllegalArgumentException("Stereo PCM requires complete frames"); }
            double target = Float.isFinite(desiredGain) ? Math.max(0, Math.min(0.8, desiredGain)) : 0;
            for (int offset = 0; offset < pcm.length; offset += 4, sample++)
            {
                gain += (target - gain) * 0.001;
                if (target == 0 && gain < 0.0001) { gain = 0; }
                if (sample >= nextSnow)
                {
                    snowFrame = 0;
                    snowLeft = snowRight = 0;
                    nextSnow = sample + SAMPLE_RATE * (20L + random.nextInt(15));
                }
                if (sample >= nextChime)
                {
                    chime = 0.008;
                    iceFrame = 0;
                    chimePhase = 0;
                    chimeStep = (1800 + random.nextDouble() * 1400) * Math.PI * 2 / SAMPLE_RATE;
                    nextChime = sample + SAMPLE_RATE * (14 + random.nextInt(12));
                }
                double left = 0, right = 0;
                if (snowFrame < SNOW_FRAMES)
                {
                    // A small, muffled powder fall with a finite envelope; no continuous noise bed.
                    snowLeft += ((random.nextDouble() * 2 - 1) - snowLeft) * 0.12;
                    snowRight += ((random.nextDouble() * 2 - 1) - snowRight) * 0.12;
                    double progress = snowFrame++ / (double) (SNOW_FRAMES - 1);
                    double envelope = Math.sin(Math.PI * progress);
                    envelope *= envelope * 0.045;
                    left = (snowLeft * 0.75 + snowRight * 0.25) * envelope;
                    right = (snowRight * 0.75 + snowLeft * 0.25) * envelope;
                }
                if (iceFrame < ICE_FRAMES)
                {
                    chimePhase += chimeStep;
                    chime *= 0.99996;
                    double attack = Math.min(1, iceFrame / (SAMPLE_RATE * 0.015));
                    double release = Math.min(1, (ICE_FRAMES - 1 - iceFrame) / (double) ICE_FADE_FRAMES);
                    double ice = chime * attack * release
                        * (Math.sin(chimePhase) + 0.3 * Math.sin(chimePhase * 1.417));
                    left += ice;
                    right += ice * 0.75;
                    iceFrame++;
                }
                write(pcm, offset, left * gain);
                write(pcm, offset + 2, right * gain);
            }
        }

        private static void write(byte[] bytes, int offset, double value)
        {
            int sample = (int) Math.round(Math.max(-0.8, Math.min(0.8, value)) * 32767);
            bytes[offset] = (byte) sample;
            bytes[offset + 1] = (byte) (sample >> 8);
        }
    }
}
