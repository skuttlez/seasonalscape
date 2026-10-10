# SeasonalScape

A RuneLite development plugin that follows the real-world calendar or a manually selected season. It changes eligible **3D ground and tree colors**, and adds small decorative ground-cover models. It targets RuneLite's default graphics and built-in GPU plugin.

**Status: development release; not yet published on the Plugin Hub.** Seasonal effects now cover eligible tiles and supported trees across the main overworld, including members' areas. Earlier builds received short logged-in previews of all four seasons with the built-in GPU in free-to-play outdoor areas. Spring and summer each placed 120 flower patches across roughly 97 by 98 tiles of the loaded landscape; winter had 384 flakes across its lower and raised upper layers. Version 0.1.20 adds tree-snow patches: a short logged-in preview showed 24 active tree-snow objects and reported 60 FPS. This is a brief observation, not a broad performance benchmark. Expanded members-area coverage has automated eligibility checks, but has not been visually verified with a members account; wider visual and performance testing remains.

Build verification: compiled against RuneLite 1.13.1, including a separate production-source compile with the official Plugin Hub standard build template. Automated tests cover calendar boundaries, expanded-area eligibility, stable flower/leaf placement across the loaded scene, palette transformations, scene restoration and exclusions, shared color arrays, untextured cache models, slope-aware ground cover, camera-limited structure snow, snowfall zoom response, generated audio, and GPU foliage tint bounds. See [Plugin Hub submission #18106](https://github.com/runelite/plugin-hub/pull/18106) for RuneLite's current build and review status.

## Seasons

Version 0.1.21 fixes seasonal color bands on shaded grass and tree faces. Autumn now recolors dark and olive grass consistently; each recolored tree triangle keeps one hue and saturation while retaining its corner lighting. This avoids repeated bright bands when the GPU interpolates packed HSL colors with Smooth banding disabled. Mixed foliage/trunk faces are left intact, and hidden or unused model faces are excluded. This was checked with automated regressions and an offline reproduction of RuneLite's shader behavior; the reported members-area locations still need an in-game visual check.

| Season | World changes |
| --- | --- |
| Spring | Fresh green grass and foliage, low pastel/white blossom clusters spread across the loaded outdoor map, sparse drifting pink and white petals |
| Summer | Warm green grass, deeper mature foliage, yellow/white wildflower patches spread across the loaded outdoor map, a few low-flying butterflies with fluttering wings |
| Autumn / fall | Muted golden ground, orange/red/gold foliage, compact piles of up to 64 overlapping leaves, spread across the loaded outdoor map |
| Winter | Continuous snow across eligible grass and grass blades, snowy untextured foliage and small snow patches on nearby tree canopies, drifting snowfall, optional snow on exposed roofs and nearby structural tops, and optional winter ambience |

Automatic mode uses the computer's date and time zone; it requires no location lookup, weather service, or paid hosting. Northern seasons begin March 1, June 1, September 1 and December 1. Select Southern Hemisphere to reverse them. A manual season always takes priority. An optional time-zone setting accepts names such as `America/Phoenix`.

The existing **SeasonalScape plugin settings** contain **Winter audio** and **Winter audio volume**, below the season and visual controls. Audio starts off. When enabled, it generates occasional soft snow-settling accents and faint ice tones locally, with silence between events and no continuous wind. Playback uses RuneLite's audio player while winter is active and the player is logged in. Disabling it, muting it, changing seasons or logging out stops new sound; an already playing fragment finishes within 250 milliseconds. It does not change the game's sound settings or download audio assets.

**Petals and butterflies** controls the spring/summer moving details independently of **Ground cover**. Both use **Ground cover density**; zero removes both. At the default density of 30, airborne effects are limited to 21 spring petals or seven summer butterflies within seven tiles, over eligible outdoor grass. They have no collision or interactions. Spring and summer ideas draw on the Woodland Trust's [spring woodland guide](https://www.woodlandtrust.org.uk/visiting-woods/things-to-do/woods-through-the-seasons/spring/) and [summer woodland guide](https://www.woodlandtrust.org.uk/visiting-woods/things-to-do/woods-through-the-seasons/summer/): blossoms, fresh growth, meadow flowers and butterflies.

## Supported areas

Eligible outdoor ground and selected static trees across the main overworld, on both free and members worlds. There is no membership check. The original Lumbridge/Draynor/Varrock/Falador boundary has been removed. Effects operate on the currently loaded scene; they do not load or scan the entire game map.

Coverage remains conservative: world X and Y must both be in 0–4095. Underground and separate-map areas outside that coordinate band are excluded, as are instances, sub-worldviews, detected roofs and bridges. Ground changes apply to untextured green terrain; roads, sand, water and textured terrain retain their original appearance. Upper-floor ground and unsupported tree variants are excluded. This is expanded main-overworld coverage, not a claim that every location or surface has been verified.

Tree names supported initially: Tree, Oak / Oak tree, Willow / Willow tree, Yew / Yew tree and Maple tree. Green untextured faces are recolored in all seasonal modes. Spring, summer and autumn also tint three verified leaf textures through GPU's **Bright textures** option, retaining leaf transparency; the development launcher enables this option. These tints change model face colors through RuneLite's API, leaving texture pixels intact. Version 0.1.19 removed direct LWJGL/OpenGL winter texture whitening in response to Plugin Hub review feedback. Version 0.1.20 adds separate snow-patch models over upper, upward-facing foliage, preserving the original tree mesh, leaf textures and trunks. Patches are fitted inside leaf triangles but do not follow individual transparent pixels in a leaf texture; their placement and appearance remain under evaluation. They use at most 24 nearby trees within 24 tiles (26 for retaining existing patches), 16 patches per tree, one model build or object activation per client cycle, and a cache of up to 48 fitted models. Seasonal trees controls these patches; Snow on structures remains separate. Animated trees, every tree variant, bare winter branches and snow depth are not implemented. Colors and decorative geometry derive from the local cache; no game assets are redistributed.

**Snow on structures** is off by default to reduce rendering work. Enable it in SeasonalScape settings to add snow on roofs, buildings, walls and other exposed structures. Switching it off immediately restores those surfaces and skips their coating scans; ground snow, seasonal foliage and falling snow remain independent.

Falling snow uses 384 particles in two height layers. Most remain near the ground and canopy, while an upper layer raises the snow ceiling with camera height so snowfall remains visible when zoomed out. The ceiling adjusts smoothly within a bounded range; zooming repositions existing particles without adding objects or rebuilding their models. Upper flakes are slightly larger for visibility at a distance. Roof and bridge filtering still applies.

When enabled, structure snow schedules nearby exposed surfaces inside a padded camera view, within roughly 22 tiles (24 for retaining existing coatings). This is camera-view filtering, not a ray-traced obstruction test: a surface behind another object may still be eligible. Roof materials retain native roof hiding. Work is spread over client ticks, static coatings are reused, and GPU zone uploads are limited per tick; small camera movements do not trigger a new sweep. Snow may fill in gradually as you approach or turn toward structures. Turning the option off still restores all coatings.

## Run on this computer

1. Install [RuneLite](https://runelite.net/) and confirm the ordinary game works.
2. Close the ordinary client before logging into the development client on the same account.
3. Double-click `Launch-SeasonalScape.cmd` in this folder. A portable JDK in `.tooling` is used on the development machine; no system Java changes are required.
4. Keep the window titled **SeasonalScape Test**. This development launcher enables SeasonalScape and RuneLite's built-in **GPU**, and disables 117 HD. Search **SeasonalScape** in plugin settings to change its configuration.
5. Choose **Autumn** first, then **Winter**, while outside near Lumbridge or Varrock. Use default graphics or the built-in **GPU** plugin. Other renderers pause this prototype.

For a Jagex account, a development client needs the additional steps in [RuneLite's official Jagex account development guide](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts). Complete those locally. The guide uses a credentials file that grants account access; keep it private and out of this repository. This plugin does not read credentials or change launcher settings.

The ordinary RuneLite client cannot install this unpublished project by dragging in its JAR. Local testing uses the development launch above. Plugin Hub distribution requires a public source repository and review through the [official submission process](https://github.com/runelite/plugin-hub#submitting-a-plugin).

## Build elsewhere

Requires JDK 17+ to run Gradle; plugin bytecode targets Java 11. The checked-in Gradle wrapper downloads the build tool and dependencies. RuneLite is pinned to `1.13.1` for reproducible initial testing.

```powershell
.\gradlew.bat test jar
.\gradlew.bat run
```

The plugin JAR is `build/libs/seasonalscape-0.1.21.jar`. A standalone development client can be built with `shadowJar`. To test against a later RuneLite release, use `-PruneLiteVersion=latest.release` and repeat the live checks below.

## Live acceptance checks

- On default graphics, check autumn trees/grass/leaves, winter snowy ground, spring and summer.
- Repeat with the built-in GPU enabled; switching seasons should update the loaded scene.
- With GPU Smooth banding both on and off, inspect shaded grass beside trees, fences and flowers in every season. Check for bright stripes, then disable SeasonalScape to confirm original colors restore.
- Rotate/zoom the camera: ground cover should sit on the terrain and be occluded by world geometry.
- Zoom fully out in winter: snowfall should extend into the higher view, while retaining flakes near the ground. Recheck entering a roofed area and switching out of winter.
- Cross region boundaries while running: seasonal materials should remain active during loading, with new geometry recolored before GPU uploads.
- Check roof hiding and indoor furniture, then switch seasons to confirm surface coatings restore.
- In spring and summer, check flower patches from several camera angles, petal drift and butterfly wings; enter a building, change seasons, set density to zero and toggle Petals and butterflies to verify cleanup.
- Enable Winter audio in SeasonalScape settings, wait for occasional powder/ice accents separated by silence, adjust volume, switch out of winter and log out to verify it stops.
- Walk across a map boundary; enter and leave a building and an underground area; travel outside the original starter region. With a members account, check eligible grass and supported trees in several mainland and western-map locations.
- Chop a supported tree and watch its respawn. Check that tree interaction and collision stay normal.
- In winter, inspect snow patches near several tree types, walk beyond their range and return, and toggle Seasonal trees. Check that patches disappear on removed trees and when leaving winter.
- Disable the plugin: original colors return and all added ground cover disappears. Re-enable and world-hop.
- Lower ground-cover density and compare FPS in a tree-heavy area.
- Test Automatic with both hemisphere settings. Automated tests cover calendar boundaries and time zones without changing your system clock.

## Implementation

Calendar selection is independent of graphics. Scene edits run on the client thread and preserve original color values. Shared model color arrays are tracked by identity. Restoration checks that values still match the plugin's last write, so it does not knowingly overwrite another plugin's edits. GPU zones are explicitly invalidated after edits. Decoration counts are bounded. Spring flowers, summer flowers and autumn leaves are distributed across world-anchored sectors of the loaded supported map. Walking within that map keeps their placement; scene/config/object changes refresh eligibility. Patch geometry and density controls are preserved: the default density allows up to 120 flower patches or 200 leaf piles, with a hard ceiling of 200 ground-cover objects. Petals and butterflies retain their nearby seven-tile range.

Reference APIs: [RuneLite API](https://static.runelite.net/runelite-api/apidocs/), [GPU renderer](https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/plugins/gpu/GpuPlugin.java). The cache particle model ID was identified from [3D Weather](https://github.com/ScreteMonge/3D-Weather); the rendering logic here is independently written.
