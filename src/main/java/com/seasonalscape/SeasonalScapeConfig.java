package com.seasonalscape;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(SeasonalScapeConfig.GROUP)
public interface SeasonalScapeConfig extends Config
{
    String GROUP = "seasonalscape";

    @ConfigItem(keyName = "season", name = "Season", position = 0,
        description = "Follow your calendar or choose a season. Northern seasons start March, June, September and December.")
    default SeasonMode season() { return SeasonMode.AUTOMATIC; }

    @ConfigItem(keyName = "hemisphere", name = "Hemisphere", position = 1,
        description = "Which real-world seasonal calendar to follow in Automatic mode.")
    default Hemisphere hemisphere() { return Hemisphere.NORTH; }

    @ConfigItem(keyName = "timeZone", name = "Time zone", position = 2,
        description = "Blank uses your computer's time zone. Optional example: America/Phoenix. Invalid entries use your computer's zone.")
    default String timeZone() { return ""; }

    @ConfigItem(keyName = "terrain", name = "Seasonal ground", position = 3,
        description = "Changes suitable outdoor grass across the main overworld, including snowy ground in winter. Separate maps and underground areas are excluded.")
    default boolean terrain() { return true; }

    @ConfigItem(keyName = "foliage", name = "Seasonal trees", position = 4,
        description = "Recolors green foliage on supported static trees. Tree trunks and interactions remain intact.")
    default boolean foliage() { return true; }

    @ConfigItem(keyName = "groundCover", name = "Ground cover", position = 5,
        description = "Spring blossoms, summer wildflowers or autumn leaf piles on nearby suitable grass. Winter uses continuous snow coverage.")
    default boolean groundCover() { return true; }

    @Range(min = 0, max = 100)
    @ConfigItem(keyName = "density", name = "Ground cover density", position = 6,
        description = "Amount of ground cover and spring/summer airborne details, from 0 to 100. Reduce this if performance drops.")
    default int density() { return 30; }

    @ConfigItem(keyName = "showStatus", name = "Show season status", position = 7,
        description = "Shows the active season and whether your location is within the supported main overworld.")
    default boolean showStatus() { return true; }

    @ConfigItem(keyName = "winterAudio", name = "Winter audio", position = 8,
        description = "Plays occasional soft snow-settling and faint ice sounds while winter is active. Separate from game audio.")
    default boolean winterAudio() { return false; }

    @Range(min = 0, max = 100)
    @ConfigItem(keyName = "winterAudioVolume", name = "Winter audio volume", position = 9,
        description = "Winter ambience volume, from 0 to 100. Set to 0 to mute.")
    default int winterAudioVolume() { return 25; }

    @ConfigItem(keyName = "seasonalAir", name = "Petals and butterflies", position = 10,
        description = "Sparse drifting spring petals and low-flying summer butterflies over suitable outdoor grass. Uses ground cover density.")
    default boolean seasonalAir() { return true; }

    @ConfigItem(keyName = "winterStructureSnow", name = "Snow on structures", position = 11,
        description = "Adds snow to nearby structures in and around the camera view, with gradual updates to reduce stutter. Ground snow and snowfall are separate.")
    default boolean winterStructureSnow() { return false; }
}
