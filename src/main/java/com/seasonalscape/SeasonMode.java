package com.seasonalscape;

/** Automatic calendar detection or a fixed visual season. */
public enum SeasonMode
{
    AUTOMATIC("Automatic", null),
    SPRING("Spring", Season.SPRING),
    SUMMER("Summer", Season.SUMMER),
    AUTUMN("Autumn", Season.AUTUMN),
    WINTER("Winter", Season.WINTER);

    private final String label;
    private final Season fixedSeason;

    SeasonMode(String label, Season fixedSeason)
    {
        this.label = label;
        this.fixedSeason = fixedSeason;
    }

    /** Returns null for AUTOMATIC; use SeasonResolver to resolve that mode. */
    public Season getFixedSeason()
    {
        return fixedSeason;
    }

    @Override
    public String toString()
    {
        return label;
    }
}
