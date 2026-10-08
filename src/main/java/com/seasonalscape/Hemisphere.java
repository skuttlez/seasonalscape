package com.seasonalscape;

/** Selects the calendar used by automatic season detection. */
public enum Hemisphere
{
    NORTH("Northern hemisphere"),
    SOUTH("Southern hemisphere");

    private final String label;

    Hemisphere(String label)
    {
        this.label = label;
    }

    @Override
    public String toString()
    {
        return label;
    }
}
