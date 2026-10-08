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
import net.runelite.api.JagexColor;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;

/** Small snow deposits that follow foliage surfaces without replacing their cutout texture. */
final class WinterTreeFrost
{
    private static final Map<Object, State> STATES = new WeakHashMap<>();
    private static final int MAX_TREES = 64;
    private static final int RADIUS = 24 * 128;
    private static final int LAYERS = 3;

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
        for (short texture : textures) { leaves |= texture == 8 || texture == 30; }
        if (!leaves) { return false; }
        State state = STATES.computeIfAbsent(owner, ignored -> new State());
        state.client = client;
        Entry entry = state.entries.get(tree);
        if (entry == null || entry.model != model)
        {
            if (entry != null) { entry.clear(); }
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
            if (!entry.seen) { entry.clear(); iterator.remove(); }
        }
        refresh(owner, state.client);
    }

    /** Called each game tick so walking does not require a full terrain scan. */
    static void refresh(Object owner, Client client)
    {
        State state = STATES.get(owner);
        if (state == null || client == null) { return; }
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
            if (entry.distance <= (long) RADIUS * RADIUS) { nearby.add(entry); }
        }
        nearby.sort(Comparator.comparingLong(entry -> entry.distance));
        for (int i = 0; i < Math.min(MAX_TREES, nearby.size()); i++) { nearby.get(i).wanted = true; }
        for (Entry entry : state.entries.values())
        {
            if (!entry.wanted) { entry.clear(); continue; }
            if (entry.object == null)
            {
                Model snow = createModel(client, entry.model,
                    ((long) entry.tree.getX() << 32) ^ entry.tree.getY() ^ entry.tree.getId());
                if (snow == null) { continue; }
                RuneLiteObject object = client.createRuneLiteObject();
                object.setModel(snow);
                object.setLocation(entry.tree.getLocalLocation(), entry.tree.getPlane());
                object.setZ(entry.tree.getZ());
                object.setOrientation(entry.tree.getModelOrientation());
                object.setActive(true);
                entry.object = object;
            }
        }
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
        ModelData[] layers = new ModelData[LAYERS];
        for (int layer = 0; layer < layers.length; layer++)
        {
            ModelData data = source.shallowCopy().cloneVertices().cloneColors();
            if (data.getFaceTextures() != null)
            {
                data.cloneTextures();
                Arrays.fill(data.getFaceTextures(), (short) -1);
            }
            data.cloneTransparencies(true);
            Arrays.fill(data.getFaceTransparencies(), (byte) 0);
            GroundCover.reshapeFlakes(source, data, Season.WINTER, layer);
            fit(source, data, surfaces, seed + 7919L * layer);
            data.translate(0, 0, 0);
            layers[layer] = data;
        }
        ModelData combined = client.mergeModels(layers);
        return combined == null ? null : combined.light(90, ModelData.DEFAULT_CONTRAST,
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
            double radius = Math.min(7 + random.nextDouble() * 5, clearance * 0.72);
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
                    double edge = radius * (0.93 + 0.05 * Math.sin(angle * 3 + visible * 1.7));
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
        for (int face = 0; face < Math.min(textures.length, a.length); face++)
        {
            if (textures[face] != 8 && textures[face] != 30) { continue; }
            Surface surface = new Surface(point(x, y, z, a[face]), point(x, y, z, b[face]), point(x, y, z, c[face]));
            // Snow rests on upward and outward foliage; avoid undersides and tiny slivers.
            if (surface.area >= 40 && surface.normal[1] < 0.45) { surfaces.add(surface); }
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
        Client client;
    }

    private static final class Entry
    {
        final GameObject tree;
        final Model model;
        RuneLiteObject object;
        boolean seen, wanted;
        long distance;
        Entry(GameObject tree, Model model) { this.tree = tree; this.model = model; }
        void clear() { if (object != null) { object.setActive(false); object = null; } }
    }
}
