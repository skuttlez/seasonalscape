package com.seasonalscape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.JagexColor;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.plugins.gpu.GpuPlugin;

/** Small snow deposits that follow foliage surfaces without replacing their cutout texture. */
final class WinterTreeFrost
{
    private static final Map<Object, State> STATES = new WeakHashMap<>();
    private static final int MAX_TREES = 24;
    private static final int RADIUS = 24 * 128, RETAIN_RADIUS = 26 * 128;
    private static final int MAX_CACHED_MODELS = 48;

    private WinterTreeFrost() {}

    static void begin(Object owner)
    {
        State state = STATES.get(owner);
        if (state != null)
        {
            for (Entry entry : state.entries.values()) { entry.seen = false; }
        }
    }

    /** Records the existing, already oriented tree mesh; creation is distance limited in refresh. */
    static boolean update(Object owner, Client client, GameObject tree, Model model)
    {
        short[] textures = model.getFaceTextures();
        if (textures == null) { return false; }
        boolean leaves = false;
        for (short texture : textures) { leaves |= SeasonalTextureTint.isLeafTexture(texture); }
        if (!leaves) { return false; }
        WorldView world = client.getTopLevelWorldView();
        Scene scene = world == null ? null : world.getScene();
        State state = STATES.get(owner);
        if (state != null && (state.scene != scene || scene == null
            || state.baseX != scene.getBaseX() || state.baseY != scene.getBaseY()))
        {
            restore(owner);
            state = null;
        }
        if (state == null)
        {
            state = new State();
            state.scene = scene;
            state.baseX = scene == null ? 0 : scene.getBaseX();
            state.baseY = scene == null ? 0 : scene.getBaseY();
            STATES.put(owner, state);
        }
        state.client = client;
        Entry entry = state.entries.get(tree);
        if (entry == null || entry.model != model)
        {
            if (entry != null) { entry.clear(); state.models.remove(entry); }
            entry = new Entry(tree, model);
            state.entries.put(tree, entry);
        }
        entry.seen = true;
        return true;
    }

    static void end(Object owner)
    {
        State state = STATES.get(owner);
        if (state == null) { return; }
        Iterator<Entry> iterator = state.entries.values().iterator();
        while (iterator.hasNext())
        {
            Entry entry = iterator.next();
            if (!entry.seen) { entry.clear(); state.models.remove(entry); iterator.remove(); }
        }
        refresh(owner, state.client);
    }

    /** Called each game tick so walking does not require a full terrain scan. */
    static void refresh(Object owner, Client client)
    {
        State state = STATES.get(owner);
        if (state == null || client == null) { return; }
        if (!ready(state, client)) { restore(owner); return; }
        Player player = client.getLocalPlayer();
        LocalPoint point = player == null ? null : player.getLocalLocation();
        List<Entry> nearby = new ArrayList<>();
        for (Entry entry : state.entries.values())
        {
            LocalPoint location = entry.tree.getLocalLocation();
            entry.wanted = false;
            if (point == null || location == null || location.getWorldView() != point.getWorldView()
                || entry.tree.getPlane() != player.getWorldLocation().getPlane()) { continue; }
            long dx = location.getX() - point.getX(), dy = location.getY() - point.getY();
            entry.distance = dx * dx + dy * dy;
            int radius = entry.object == null ? RADIUS : RETAIN_RADIUS;
            if (entry.distance <= (long) radius * radius) { nearby.add(entry); }
        }
        nearby.sort(Comparator.comparingLong(entry -> entry.distance));
        state.selected.clear();
        for (int i = 0; i < Math.min(MAX_TREES, nearby.size()); i++)
        {
            Entry entry = nearby.get(i);
            entry.wanted = true;
            state.selected.add(entry);
        }
        for (Entry entry : state.entries.values())
        {
            if (!entry.wanted) { entry.clear(); }
        }
    }

    /** At most one model build/object activation per client cycle, never on camera rotation. */
    static void tick(Object owner)
    {
        State state = STATES.get(owner);
        if (state == null) { return; }
        Client client = state.client;
        if (!ready(state, client)) { restore(owner); return; }
        int cycle = client.getGameCycle();
        if (state.lastCycle == cycle) { return; }
        state.lastCycle = cycle;
        state.lastBuilds = 0;
        for (Entry entry : state.selected)
        {
            if (!entry.wanted || entry.object != null || cycle < entry.retryCycle) { continue; }
            Model snow = state.models.get(entry);
            if (snow == null)
            {
                state.lastBuilds = 1;
                snow = createModel(client, entry.model,
                    ((long) entry.tree.getX() << 32) ^ entry.tree.getY() ^ entry.tree.getId());
                if (snow == null) { entry.retryCycle = cycle + 50; return; }
                state.models.put(entry, snow);
                if (state.models.size() > MAX_CACHED_MODELS)
                {
                    state.models.remove(state.models.keySet().iterator().next());
                }
            }
            RuneLiteObject object = client.createRuneLiteObject();
            object.setModel(snow);
            object.setLocation(entry.tree.getLocalLocation(), entry.tree.getPlane());
            object.setZ(entry.tree.getZ());
            object.setOrientation(entry.tree.getModelOrientation());
            object.setActive(true);
            entry.object = object;
            return;
        }
    }

    private static boolean ready(State state, Client client)
    {
        WorldView world = client.getTopLevelWorldView();
        Player player = client.getLocalPlayer();
        LocalPoint point = player == null ? null : player.getLocalLocation();
        return client.getGameState() == GameState.LOGGED_IN && world != null && world.isTopLevel()
            && !world.isInstance() && world.getPlane() == 0 && world.getScene() == state.scene
            && SeasonalSceneRecolorer.supports(state.scene) && point != null
            && state.scene.getBaseX() == state.baseX && state.scene.getBaseY() == state.baseY
            && point.getWorldView() == world.getId()
            && SeasonalWorldArea.contains(state.scene.getBaseX() + point.getSceneX(),
                state.scene.getBaseY() + point.getSceneY())
            && (client.getDrawCallbacks() == null || client.getDrawCallbacks() instanceof GpuPlugin);
    }

    static int getCount(Object owner)
    {
        State state = STATES.get(owner);
        if (state == null) { return 0; }
        int count = 0;
        for (Entry entry : state.entries.values()) { if (entry.object != null) { count++; } }
        return count;
    }

    static void remove(Object owner, GameObject tree)
    {
        State state = STATES.get(owner);
        if (state == null) { return; }
        Entry entry = state.entries.remove(tree);
        if (entry == null) { return; }
        entry.clear();
        state.selected.remove(entry);
        state.models.remove(entry);
    }

    static void restore(Object owner)
    {
        State state = STATES.remove(owner);
        if (state != null) { for (Entry entry : state.entries.values()) { entry.clear(); } }
    }

    private static Model createModel(Client client, Model tree, long seed)
    {
        ModelData source = client.loadModelData(27835);
        if (source == null || source.getVerticesCount() < 3 || source.getVerticesCount() > 8192
            || source.getFaceCount() == 0 || source.getFaceCount() > 8192) { return null; }
        List<Surface> surfaces = surfaces(tree);
        if (surfaces.isEmpty()) { return null; }
        ModelData data = source.shallowCopy().cloneVertices().cloneColors();
        if (data.getFaceTextures() != null)
        {
            data.cloneTextures();
            Arrays.fill(data.getFaceTextures(), (short) -1);
        }
        data.cloneTransparencies(true);
        Arrays.fill(data.getFaceTransparencies(), (byte) 0);
        GroundCover.reshapeFlakes(source, data, Season.WINTER, 0);
        fit(source, data, surfaces, seed);
        data.translate(0, 0, 0);
        return data.light(90, ModelData.DEFAULT_CONTRAST,
            ModelData.DEFAULT_X, ModelData.DEFAULT_Y, ModelData.DEFAULT_Z);
    }

    /** Fits each connected particle to one triangle, keeping every patch inside its leaf surface. */
    static void fit(ModelData source, ModelData target, List<Surface> surfaces, long seed)
    {
        int[] parents = new int[source.getVerticesCount()];
        for (int i = 0; i < parents.length; i++) { parents[i] = i; }
        int[] a = source.getFaceIndices1(), b = source.getFaceIndices2(), c = source.getFaceIndices3();
        for (int i = 0; i < source.getFaceCount(); i++)
        {
            parents[root(parents, b[i])] = root(parents, a[i]);
            parents[root(parents, c[i])] = root(parents, a[i]);
        }
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < parents.length; i++)
        {
            groups.computeIfAbsent(root(parents, i), unused -> new ArrayList<>()).add(i);
        }
        float[] x = target.getVerticesX(), y = target.getVerticesY(), z = target.getVerticesZ();
        Random random = new Random(seed);
        double total = 0;
        for (Surface surface : surfaces) { total += surface.area; }
        int visible = 0;
        for (List<Integer> vertices : groups.values())
        {
            double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
            for (int vertex : vertices)
            {
                minX = Math.min(minX, x[vertex]); maxX = Math.max(maxX, x[vertex]);
                minZ = Math.min(minZ, z[vertex]); maxZ = Math.max(maxZ, z[vertex]);
            }
            if (vertices.size() < 3 || maxX - minX < 0.01 || maxZ - minZ < 0.01
                || visible++ >= 16 || total == 0)
            {
                for (int vertex : vertices) { x[vertex] = 0; y[vertex] = 0; z[vertex] = 0; }
                continue;
            }
            double chosen = random.nextDouble() * total;
            Surface surface = surfaces.get(surfaces.size() - 1);
            for (Surface candidate : surfaces)
            {
                chosen -= candidate.area;
                if (chosen <= 0) { surface = candidate; break; }
            }
            // Keep centers away from polygon edges where leaf textures become transparent.
            double u = 0.18 + random.nextDouble() * 0.46;
            double v = 0.18 + random.nextDouble() * (0.82 - u - 0.18);
            double[] center = add(surface.a, add(scale(surface.ab, u), scale(surface.ac, v)));
            double clearance = Math.min(distanceToLine(center, surface.a, surface.b),
                Math.min(distanceToLine(center, surface.b, surface.c), distanceToLine(center, surface.c, surface.a)));
            double radius = Math.min(14 + random.nextDouble() * 10, clearance * 0.72);
            double centerX = (minX + maxX) / 2, centerZ = (minZ + maxZ) / 2;
            double oldRadius = 0;
            for (int vertex : vertices)
            {
                oldRadius = Math.max(oldRadius, Math.hypot(x[vertex] - centerX, z[vertex] - centerZ));
            }
            for (int vertex : vertices)
            {
                double dx = x[vertex] - centerX, dz = z[vertex] - centerZ;
                double distance = Math.hypot(dx, dz);
                double along = 0, across = 0;
                if (distance > Math.max(0.001, oldRadius * 0.04))
                {
                    // The cache particle has a pointed star silhouette. Move
                    // its perimeter onto a gently uneven oval, preserving the
                    // angular order and central vertices of the existing mesh.
                    double angle = Math.atan2(dz, dx);
                    double edge = radius * (0.89 + 0.09 * Math.sin(angle * 3 + visible * 1.7));
                    along = dx / distance * edge;
                    across = dz / distance * edge * 0.78;
                }
                double[] fitted = add(center, add(scale(surface.tangent, along),
                    add(scale(surface.bitangent, across), scale(surface.normal, 1.4))));
                x[vertex] = (float) fitted[0]; y[vertex] = (float) fitted[1]; z[vertex] = (float) fitted[2];
            }
        }
        short[] colors = target.getFaceColors();
        for (int i = 0; i < colors.length; i++)
        {
            colors[i] = JagexColor.packHSL(36, 0, 106 + Math.floorMod(root(parents, a[i]), 4) * 3);
        }
    }

    static List<Surface> surfaces(Model tree)
    {
        List<Surface> surfaces = new ArrayList<>();
        short[] textures = tree.getFaceTextures();
        float[] x = tree.getVerticesX(), y = tree.getVerticesY(), z = tree.getVerticesZ();
        int[] a = tree.getFaceIndices1(), b = tree.getFaceIndices2(), c = tree.getFaceIndices3();
        if (textures == null || x == null || y == null || z == null || a == null || b == null || c == null)
        {
            return surfaces;
        }
        int[] colors = tree.getFaceColors3();
        byte[] alpha = tree.getFaceTransparencies();
        int faces = Math.min(tree.getFaceCount(),
            Math.min(Math.min(textures.length, a.length), Math.min(b.length, c.length)));
        if (colors != null) { faces = Math.min(faces, colors.length); }
        if (alpha != null) { faces = Math.min(faces, alpha.length); }
        int vertices = Math.min(tree.getVerticesCount(), Math.min(x.length, Math.min(y.length, z.length)));
        if (faces <= 0 || faces > 8192 || vertices <= 0) { return surfaces; }
        boolean[] usable = new boolean[faces];
        float top = Float.POSITIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
        for (int face = 0; face < faces; face++)
        {
            if (!SeasonalTextureTint.isLeafTexture(textures[face])
                || colors != null && colors[face] == -2
                || alpha != null && (alpha[face] & 255) > 128) { continue; }
            boolean valid = true;
            for (int vertex : new int[]{a[face], b[face], c[face]})
            {
                if (vertex < 0 || vertex >= vertices || !Float.isFinite(x[vertex])
                    || !Float.isFinite(y[vertex]) || !Float.isFinite(z[vertex]))
                {
                    valid = false;
                    break;
                }
            }
            if (!valid) { continue; }
            usable[face] = true;
            for (int vertex : new int[]{a[face], b[face], c[face]})
            {
                top = Math.min(top, y[vertex]);
                bottom = Math.max(bottom, y[vertex]);
            }
        }
        float cutoff = top + (bottom - top) * 0.72f;
        for (int face = 0; face < faces; face++)
        {
            if (!usable[face]
                || (y[a[face]] + y[b[face]] + y[c[face]]) / 3f > cutoff) { continue; }
            Surface surface = new Surface(point(x, y, z, a[face]), point(x, y, z, b[face]), point(x, y, z, c[face]));
            // Snow rests on upper, upward-facing foliage; keep hanging skirts and undersides green.
            if (Double.isFinite(surface.area) && surface.area >= 40 && surface.normal[1] < -0.2)
            { surfaces.add(surface); }
        }
        return surfaces;
    }

    private static int root(int[] parents, int i)
    {
        while (parents[i] != i) { parents[i] = parents[parents[i]]; i = parents[i]; }
        return i;
    }

    private static double[] point(float[] x, float[] y, float[] z, int i) { return new double[]{x[i], y[i], z[i]}; }
    private static double[] add(double[] a, double[] b) { return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }
    private static double[] subtract(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    private static double[] scale(double[] a, double n) { return new double[]{a[0] * n, a[1] * n, a[2] * n}; }
    private static double length(double[] a) { return Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]); }
    private static double[] cross(double[] a, double[] b) { return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
    private static double distanceToLine(double[] p, double[] a, double[] b)
    {
        double[] edge = subtract(b, a);
        return length(cross(subtract(p, a), edge)) / Math.max(0.001, length(edge));
    }

    static final class Surface
    {
        final double[] a, b, c, ab, ac, normal, tangent, bitangent;
        final double area;
        Surface(double[] a, double[] b, double[] c)
        {
            this.a = a; this.b = b; this.c = c;
            ab = subtract(b, a); ac = subtract(c, a);
            double[] n = cross(ab, ac);
            area = length(n) / 2;
            normal = scale(n, 1 / Math.max(0.001, area * 2));
            tangent = scale(ab, 1 / Math.max(0.001, length(ab)));
            bitangent = cross(normal, tangent);
        }
    }

    private static final class State
    {
        final Map<GameObject, Entry> entries = new IdentityHashMap<>();
        final List<Entry> selected = new ArrayList<>();
        final Map<Entry, Model> models = new LinkedHashMap<>(64, 0.75f, true);
        Client client;
        Scene scene;
        int baseX, baseY;
        int lastCycle = Integer.MIN_VALUE, lastBuilds;
    }

    private static final class Entry
    {
        final GameObject tree;
        final Model model;
        RuneLiteObject object;
        boolean seen, wanted;
        int retryCycle;
        long distance;
        Entry(GameObject tree, Model model) { this.tree = tree; this.model = model; }
        void clear() { if (object != null) { object.setActive(false); object = null; } }
    }
}
