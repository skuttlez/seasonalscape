package com.seasonalscape;

/** Coordinate eligibility shared by terrain, foliage and seasonal decorations. */
final class SeasonalWorldArea
{
    private SeasonalWorldArea() {}

    /**
     * The supported main-overworld band is X 0..4095 and Y 0..4095. This
     * deliberately excludes underground and separately mapped areas, including
     * surface-looking pocket maps. Roof, bridge, plane and instance checks are
     * still required by each effect; coordinates alone do not prove clear sky.
     * Membership and world type do not affect eligibility.
     */
    static boolean contains(int worldX, int worldY)
    {
        return worldX >= 0 && worldX < 4096 && worldY >= 0 && worldY < 4096;
    }
}
