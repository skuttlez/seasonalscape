package com.seasonalscape;

/** The four visual seasons. */
public enum Season
{
    SPRING("Spring"),
    SUMMER("Summer"),
    AUTUMN("Autumn"),
    WINTER("Winter");

    private final String label;

    Season(String label)
    {
        this.label = label;
    }

    public Season opposite()
    {
        switch (this)
        {
            case SPRING:
                return AUTUMN;
            case SUMMER:
                return WINTER;
            case AUTUMN:
                return SPRING;
            case WINTER:
                return SUMMER;
            default:
                throw new IllegalStateException("Unknown season: " + name());
        }
    }

    @Override
    public String toString()
    {
        return label;
    }
}
