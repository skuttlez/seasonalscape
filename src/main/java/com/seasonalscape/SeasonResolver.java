package com.seasonalscape;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/** Calendar-only season detection, independent of RuneLite and rendering. */
public final class SeasonResolver
{
    private SeasonResolver()
    {
    }

    /** Defaults to the computer's date and the northern hemisphere. */
    public static Season resolve(SeasonMode mode)
    {
        return resolve(mode, Hemisphere.NORTH);
    }

    /** Uses the computer's local date, without requiring location services. */
    public static Season resolve(SeasonMode mode, Hemisphere hemisphere)
    {
        return resolve(mode, hemisphere, Clock.systemDefaultZone(), "");
    }

    /**
     * Uses meteorological seasons: March, June, September and December begin
     * spring, summer, autumn and winter in the northern hemisphere.
     *
     * An empty or invalid zone ID falls back to the supplied clock's zone.
     * Manual selections are used as-is in either hemisphere.
     */
    public static Season resolve(SeasonMode mode, Hemisphere hemisphere, Clock clock, String zoneId)
    {
        Objects.requireNonNull(mode, "mode");
        if (mode != SeasonMode.AUTOMATIC)
        {
            return mode.getFixedSeason();
        }

        Objects.requireNonNull(hemisphere, "hemisphere");
        Objects.requireNonNull(clock, "clock");
        ZoneId zone = clock.getZone();
        if (zoneId != null && !zoneId.trim().isEmpty())
        {
            try
            {
                zone = ZoneId.of(zoneId.trim());
            }
            catch (DateTimeException ignored)
            {
                // A malformed preference must not stop scene rendering.
            }
        }

        int month = LocalDate.now(clock.withZone(zone)).getMonthValue();
        Season season;
        if (month >= 3 && month <= 5)
        {
            season = Season.SPRING;
        }
        else if (month >= 6 && month <= 8)
        {
            season = Season.SUMMER;
        }
        else if (month >= 9 && month <= 11)
        {
            season = Season.AUTUMN;
        }
        else
        {
            season = Season.WINTER;
        }
        return hemisphere == Hemisphere.SOUTH ? season.opposite() : season;
    }
}
