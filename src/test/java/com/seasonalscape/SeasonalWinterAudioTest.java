package com.seasonalscape;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

public class SeasonalWinterAudioTest
{
    private static final int SAMPLE_RATE = 22050;

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
