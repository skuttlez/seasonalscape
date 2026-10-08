package com.seasonalscape;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SeasonResolverTest
{
    @Test
    public void resolvesEveryNorthernMonth()
    {
        Season[] expected = {
            Season.WINTER, Season.WINTER, Season.SPRING, Season.SPRING,
            Season.SPRING, Season.SUMMER, Season.SUMMER, Season.SUMMER,
            Season.AUTUMN, Season.AUTUMN, Season.AUTUMN, Season.WINTER
        };
        for (int month = 1; month <= 12; month++)
        {
            assertEquals("month " + month, expected[month - 1], automatic(
                LocalDate.of(2026, month, 1), Hemisphere.NORTH));
            assertEquals("last day of month " + month, expected[month - 1], automatic(
                LocalDate.of(2026, month, 1).plusMonths(1).minusDays(1), Hemisphere.NORTH));
        }
    }

    @Test
    public void resolvesEverySouthernMonth()
    {
        Season[] expected = {
            Season.SUMMER, Season.SUMMER, Season.AUTUMN, Season.AUTUMN,
            Season.AUTUMN, Season.WINTER, Season.WINTER, Season.WINTER,
            Season.SPRING, Season.SPRING, Season.SPRING, Season.SUMMER
        };
        for (int month = 1; month <= 12; month++)
        {
            assertEquals("month " + month, expected[month - 1], automatic(
                LocalDate.of(2026, month, 1), Hemisphere.SOUTH));
        }
    }

    @Test
    public void changesSeasonAtLocalMidnight()
    {
        Clock beforeMidnight = Clock.fixed(Instant.parse("2026-03-01T06:59:59Z"), ZoneOffset.UTC);
        Clock atMidnight = Clock.fixed(Instant.parse("2026-03-01T07:00:00Z"), ZoneOffset.UTC);
        assertEquals(Season.WINTER, SeasonResolver.resolve(SeasonMode.AUTOMATIC,
            Hemisphere.NORTH, beforeMidnight, "America/Phoenix"));
        assertEquals(Season.SPRING, SeasonResolver.resolve(SeasonMode.AUTOMATIC,
            Hemisphere.NORTH, atMidnight, "America/Phoenix"));
        assertEquals(Season.SUMMER, SeasonResolver.resolve(SeasonMode.AUTOMATIC,
            Hemisphere.SOUTH, beforeMidnight, "America/Phoenix"));
        assertEquals(Season.AUTUMN, SeasonResolver.resolve(SeasonMode.AUTOMATIC,
            Hemisphere.SOUTH, atMidnight, "America/Phoenix"));
    }

    @Test
    public void winterSpansNewYearAndLeapDay()
    {
        assertEquals(Season.WINTER, automatic(LocalDate.of(2027, 12, 31), Hemisphere.NORTH));
        assertEquals(Season.WINTER, automatic(LocalDate.of(2028, 1, 1), Hemisphere.NORTH));
        assertEquals(Season.WINTER, automatic(LocalDate.of(2028, 2, 29), Hemisphere.NORTH));
        assertEquals(Season.SPRING, automatic(LocalDate.of(2028, 3, 1), Hemisphere.NORTH));
    }

    @Test
    public void blankAndInvalidZonesFallBackToClocksZone()
    {
        Clock localClock = Clock.fixed(Instant.parse("2026-03-01T01:00:00Z"),
            ZoneId.of("America/Phoenix"));
        for (String zone : new String[]{null, "", "   ", "not/a/real-zone", "UTC+nope"})
        {
            assertEquals("zone " + zone, Season.WINTER, SeasonResolver.resolve(
                SeasonMode.AUTOMATIC, Hemisphere.NORTH, localClock, zone));
        }
        assertEquals(Season.SPRING, SeasonResolver.resolve(SeasonMode.AUTOMATIC,
            Hemisphere.NORTH, localClock, " UTC "));
    }

    @Test
    public void manualModesIgnoreDateZoneAndHemisphere()
    {
        for (Hemisphere hemisphere : Hemisphere.values())
        {
            for (Season season : Season.values())
            {
                for (int month = 1; month <= 12; month++)
                {
                    Clock clock = clock(LocalDate.of(2026, month, 1));
                    assertEquals(season, SeasonResolver.resolve(SeasonMode.valueOf(season.name()),
                        hemisphere, clock, "invalid-zone"));
                }
            }
        }
    }

    private static Season automatic(LocalDate date, Hemisphere hemisphere)
    {
        return SeasonResolver.resolve(SeasonMode.AUTOMATIC, hemisphere, clock(date), "UTC");
    }

    private static Clock clock(LocalDate date)
    {
        return Clock.fixed(date.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    }
}
