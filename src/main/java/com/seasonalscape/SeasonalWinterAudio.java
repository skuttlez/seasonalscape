package com.seasonalscape;

import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import net.runelite.api.Client;
import net.runelite.api.GameState;
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

    private SeasonalWinterAudio() {}

    static synchronized void start(Object owner, Client client, ConfigManager configs)
    {
        stop(owner);
        State state = new State(client, configs);
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
        // The worker fades and closes its own audio line; no join or device calls here.
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

    private static final class State
    {
        final Client client;
        final ConfigManager configs;
        final Object wake = new Object();
        final Thread worker;
        volatile boolean enabled, inWorld, winter, closed, playing, failed;
        volatile int volume = 25;

        State(Client client, ConfigManager configs)
        {
            this.client = client;
            this.configs = configs;
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
            SourceDataLine line = null;
            try
            {
                AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
                line = (SourceDataLine) AudioSystem.getLine(info);
                line.open(format, 8192);
                if (!wanted()) { return; }
                line.start();
                playing = true;
                SnowSound sound = new SnowSound(0x534e4f5741554449L);
                byte[] buffer = new byte[512 * 4];
                do
                {
                    sound.render(buffer, wanted() ? volume / 100f * 0.8f : 0);
                    int offset = 0;
                    while (offset < buffer.length)
                    {
                        int written = line.write(buffer, offset, buffer.length - offset);
                        if (written <= 0) { throw new IllegalStateException("Audio output stopped"); }
                        offset += written;
                    }
                }
                while (wanted() || !sound.isSilent());
            }
            catch (LineUnavailableException | IllegalArgumentException | IllegalStateException | SecurityException error)
            {
                failed = true;
                LoggerFactory.getLogger(SeasonalWinterAudio.class)
                    .warn("Winter ambience audio output is unavailable: {}", error.toString());
            }
            finally
            {
                playing = false;
                if (line != null)
                {
                    try { line.stop(); line.flush(); line.close(); }
                    catch (RuntimeException ignored) { /* A disconnected device is already unusable. */ }
                }
            }
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
