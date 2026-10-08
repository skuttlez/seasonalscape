package com.seasonalscape;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.runelite.client.audio.AudioPlayer;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SeasonalWinterAudioTest
{
    private static final int SAMPLE_RATE = 22050;

    @Test
    public void wavContainsExactStereoPcmAndAConsistentPlayableHeader()
    {
        byte[] pcm = {0, 0, -1, 127, 0, -128, 4, 0};
        byte[] wav = SeasonalWinterAudio.wave(pcm);
        ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(wav.length - 8, header.getInt(4));
        assertEquals("WAVE", new String(wav, 8, 4, StandardCharsets.US_ASCII));
        assertEquals("fmt ", new String(wav, 12, 4, StandardCharsets.US_ASCII));
        assertEquals(16, header.getInt(16));
        assertEquals(1, header.getShort(20));
        assertEquals(2, header.getShort(22));
        assertEquals(SAMPLE_RATE, header.getInt(24));
        assertEquals(SAMPLE_RATE * 4, header.getInt(28));
        assertEquals(4, header.getShort(32));
        assertEquals(16, header.getShort(34));
        assertEquals("data", new String(wav, 36, 4, StandardCharsets.US_ASCII));
        assertEquals(pcm.length, header.getInt(40));
        assertArrayEquals(pcm, Arrays.copyOfRange(wav, 44, wav.length));
    }

    @Test
    public void supportedPlayerReceivesAQuietBoundedChunkAndDisableStopsFurtherSubmission()
    {
        SeasonalWinterAudio.State[] state = new SeasonalWinterAudio.State[1];
        byte[][] received = {null};
        int[] calls = {0};
        AudioPlayer player = new AudioPlayer()
        {
            @Override public void play(InputStream input, float gain) throws IOException
            {
                calls[0]++;
                assertEquals("PCM already controls volume; device gain stays at zero decibels", 0, gain, 0);
                received[0] = input.readAllBytes();
                state[0].enabled = false;
                state[0].signal();
            }
        };
        state[0] = ready(player);
        state[0].volume = 25;
        SeasonalWinterAudio.SnowSound sound = new SeasonalWinterAudio.SnowSound(123);
        sound.render(new byte[SAMPLE_RATE * 4 * 4], 0.2f); // Advance the initial silent four seconds.
        state[0].play(sound);
        assertEquals("Turning off prevents the next chunk", 1, calls[0]);
        assertFalse(state[0].playing);
        assertFalse(state[0].failed);
        int bytes = ByteBuffer.wrap(received[0]).order(ByteOrder.LITTLE_ENDIAN).getInt(40);
        assertEquals(received[0].length - 44, bytes);
        assertTrue("Already-started playback has at most a quarter-second tail", bytes / 4.0 / SAMPLE_RATE <= 0.25);
        byte[] pcm = Arrays.copyOfRange(received[0], 44, received[0].length);
        assertTrue(SeasonalWinterAudio.hasSignal(pcm));
        assertTrue("The user's quarter-volume setting is in the actual PCM", peak(pcm) < 400);
        assertArrayEquals("A finite clip starts at zero to avoid clicks", new byte[4], Arrays.copyOf(pcm, 4));
        assertArrayEquals("A finite clip ends at zero to avoid clicks", new byte[4], Arrays.copyOfRange(pcm, pcm.length - 4, pcm.length));
        state[0].play(sound);
        assertEquals("A disabled state cannot submit another clip", 1, calls[0]);
    }

    @Test
    public void quietGapsDoNotSubmitAudioAndEveryLifecycleGatePreventsPlayback()
    {
        SeasonalWinterAudio.SnowSound sound = new SeasonalWinterAudio.SnowSound(123);
        byte[] silence = new byte[SAMPLE_RATE * 4];
        sound.render(silence, 0.8f);
        assertFalse("The silent lead-in never needs an AudioPlayer call", SeasonalWinterAudio.hasSignal(silence));
        for (int scenario = 0; scenario < 6; scenario++)
        {
            AudioPlayer player = new AudioPlayer()
            {
                @Override public void play(InputStream input, float gain)
                {
                    throw new AssertionError("Inactive audio must not touch device playback");
                }
            };
            SeasonalWinterAudio.State state = ready(player);
            switch (scenario)
            {
                case 0: state.enabled = false; break;
                case 1: state.closed = true; break;
                case 2: state.inWorld = false; break;
                case 3: state.winter = false; break;
                case 4: state.volume = 0; break;
                case 5: state.failed = true; break;
                default: throw new AssertionError();
            }
            state.play(sound);
            assertFalse(state.playing);
        }
    }

    @Test
    public void unavailablePlayerStopsTheSessionUntilTheUserRetries()
    {
        AudioPlayer player = new AudioPlayer()
        {
            @Override public void play(InputStream input, float gain) throws IOException
            {
                throw new IOException("Test device unavailable");
            }
        };
        SeasonalWinterAudio.State state = ready(player);
        SeasonalWinterAudio.SnowSound sound = new SeasonalWinterAudio.SnowSound(123);
        sound.render(new byte[SAMPLE_RATE * 4 * 4], 0.2f);
        state.play(sound);
        assertTrue(state.failed);
        assertFalse(state.playing);
        assertFalse(state.wanted());
        assertTrue(state.status().contains("Toggle off and on"));
    }

    @Test
    public void pacingIgnoresSettingsWakeupsAndStopsPromptlyOnClose() throws Exception
    {
        SeasonalWinterAudio.State state = ready(new AudioPlayer());
        assertTrue("An elapsed deadline permits one next chunk", state.awaitChunk(System.nanoTime() - 1));
        CountDownLatch entered = new CountDownLatch(1), finished = new CountDownLatch(1);
        boolean[] result = {true};
        Thread waiter = new Thread(() -> {
            entered.countDown();
            result[0] = state.awaitChunk(System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
            finished.countDown();
        });
        waiter.setDaemon(true);
        waiter.start();
        try
        {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int wakeup = 0; wakeup < 4; wakeup++) { state.signal(); }
            assertFalse("Configuration notifications cannot accelerate the playback clock",
                finished.await(100, TimeUnit.MILLISECONDS));
            state.closed = true;
            state.signal();
            assertTrue("Closing wakes the worker rather than waiting through its deadline",
                finished.await(2, TimeUnit.SECONDS));
            assertFalse("A closed state never permits another chunk", result[0]);
        }
        finally
        {
            state.closed = true;
            state.signal();
            waiter.join(2000);
        }
        assertFalse(waiter.isAlive());
    }

    private static SeasonalWinterAudio.State ready(AudioPlayer player)
    {
        SeasonalWinterAudio.State state = new SeasonalWinterAudio.State(null, null, player);
        state.enabled = state.inWorld = state.winter = true;
        return state;
    }

    @Test
    public void DisabledAudioProducesExactDigitalSilence()
    {
        SeasonalWinterAudio.SnowSound sound = new SeasonalWinterAudio.SnowSound(123);
        byte[] buffer = new byte[2048];
        for (int i = 0; i < 4; i++) { sound.render(buffer, 0); }
        assertArrayEquals(new byte[buffer.length], buffer);
        assertTrue(sound.isSilent());
    }

    @Test
    public void SnowAndIceAccentsHaveSilentGapsInsteadOfContinuousWind()
    {
        SeasonalWinterAudio.SnowSound sound = new SeasonalWinterAudio.SnowSound(123);
        assertSilence(renderSeconds(sound, 4));
        assertTrue("The replacement snow-settling accent is audible", peak(renderSeconds(sound, 1)) > 100);
        assertSilence(renderSeconds(sound, 4));
        assertTrue("The original faint icy overtones remain audible", peak(renderSeconds(sound, 4)) > 100);
        assertSilence(renderSeconds(sound, 10));
    }

    @Test
    public void PcmIsDeterministicBoundedAndFadesToSilence()
    {
        SeasonalWinterAudio.SnowSound first = new SeasonalWinterAudio.SnowSound(567);
        SeasonalWinterAudio.SnowSound second = new SeasonalWinterAudio.SnowSound(567);
        byte[] a = new byte[2048], b = new byte[2048];
        int peak = 0;
        // Includes the initial icy overtone at nine seconds.
        for (int block = 0; block < 450; block++)
        {
            first.render(a, 0.8f);
            second.render(b, 0.8f);
            assertArrayEquals(a, b);
            peak = Math.max(peak, peak(a));
        }
        assertTrue("Audible signal is generated", peak > 100);
        assertTrue("Even full-volume accents remain quiet", peak < 1500);
        for (int i = 0; i < 40; i++) { first.render(a, 0); }
        assertArrayEquals("Switching off settles at exact silence", new byte[a.length], a);
        assertTrue(first.isSilent());
    }

    @Test
    public void RepeatedAccentsLeaveMostOfTheSessionSilent()
    {
        SeasonalWinterAudio.SnowSound sound = new SeasonalWinterAudio.SnowSound(567);
        byte[] buffer = new byte[SAMPLE_RATE * 4];
        int silentFrames = 0;
        for (int second = 0; second < 120; second++)
        {
            sound.render(buffer, 0.8f);
            assertTrue("Accents stay quiet throughout the session", peak(buffer) < 1500);
            for (int offset = 0; offset < buffer.length; offset += 4)
            {
                if ((buffer[offset] | buffer[offset + 1] | buffer[offset + 2] | buffer[offset + 3]) == 0)
                {
                    silentFrames++;
                }
            }
        }
        assertTrue("Long quiet gaps keep ambience from masking game sounds",
            silentFrames > SAMPLE_RATE * 120L * 0.70);
    }

    @Test(expected = IllegalArgumentException.class)
    public void RejectsIncompleteStereoFrames()
    {
        new SeasonalWinterAudio.SnowSound(1).render(new byte[3], 0.2f);
    }

    private static byte[] renderSeconds(SeasonalWinterAudio.SnowSound sound, int seconds)
    {
        byte[] result = new byte[seconds * SAMPLE_RATE * 4];
        sound.render(result, 0.8f);
        return result;
    }

    private static void assertSilence(byte[] pcm)
    {
        assertArrayEquals("No wind or lingering noise between accents", new byte[pcm.length], pcm);
    }

    private static int peak(byte[] pcm)
    {
        int peak = 0;
        for (int offset = 0; offset < pcm.length; offset += 2)
        {
            int value = (short) ((pcm[offset] & 255) | ((pcm[offset + 1] & 255) << 8));
            peak = Math.max(peak, Math.abs(value));
        }
        return peak;
    }
}
