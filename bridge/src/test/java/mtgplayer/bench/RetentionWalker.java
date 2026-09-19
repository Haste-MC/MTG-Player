package mtgplayer.bench;

import forge.game.Game;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

/**
 * Wegwerf-Werkzeug: laeuft den Objektgraphen reflektiv ab (BFS ab Wurzeln) und meldet, ueber welche
 * Pfade (Klasse.Feld-Ketten) fremde forge.game.Game-Instanzen (Simulationskopien) erreichbar sind.
 * Braucht --add-opens java.base/java.util=ALL-UNNAMED (und java.lang, java.util.concurrent). Kein
 * eigener Testeinstieg (kein {@code @Test}) - wird von {@link SimMemoryProbe} ueber
 * {@code -Dprobe.walkAtMB=<MB>} aufgerufen, siehe dort fuer den vollstaendigen Aufruf.
 */
final class RetentionWalker {
    private RetentionWalker() { }

    private static final Map<Class<?>, List<Field>> FIELDS = new ConcurrentHashMap<>();

    private record Edge(Object parent, String label) { }

    static void walk(Game mainGame, List<Object> namedRootsObjs, List<String> namedRootsNames, ClassLoader cl, int maxGamesToPrintPerSig) {
        long t0 = System.currentTimeMillis();
        IdentityHashMap<Object, Edge> visited = new IdentityHashMap<>(1 << 22);
        ArrayDeque<Object> queue = new ArrayDeque<>();
        Map<String, Integer> sigCount = new HashMap<>();
        Map<String, Set<Game>> sigGames = new HashMap<>();
        Set<Game> foreign = Collections.newSetFromMap(new IdentityHashMap<>());

        Object rootMarker = new Object();
        for (int i = 0; i < namedRootsObjs.size(); i++) {
            Object r = namedRootsObjs.get(i);
            if (r != null && !visited.containsKey(r)) {
                visited.put(r, new Edge(rootMarker, "ROOT:" + namedRootsNames.get(i)));
                queue.add(r);
            }
        }
        // statische Felder aller forge-Klassen
        int staticRoots = 0;
        for (Class<?> c : forgeClasses(cl)) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v != null && !visited.containsKey(v) && expandable(v)) {
                        visited.put(v, new Edge(rootMarker, "STATIC:" + c.getName() + "." + f.getName()));
                        queue.add(v);
                        staticRoots++;
                    }
                } catch (Throwable ignored) { }
            }
        }
        System.out.println("[walker] Wurzeln: " + namedRootsObjs.size() + " benannte + " + staticRoots + " statische Felder");

        long expanded = 0;
        while (!queue.isEmpty()) {
            Object o = queue.poll();
            expanded++;
            if (expanded % 2_000_000 == 0) System.out.println("[walker] ... " + expanded + " Objekte, queue=" + queue.size());
            try {
                if (o.getClass().isArray()) {
                    if (o.getClass().getComponentType().isPrimitive()) continue;
                    int n = Array.getLength(o);
                    for (int i = 0; i < n; i++) {
                        handle(Array.get(o, i), o, "[]", mainGame, visited, queue, sigCount, sigGames, foreign, maxGamesToPrintPerSig);
                    }
                } else {
                    for (Field f : fields(o.getClass())) {
                        Object v;
                        try { v = f.get(o); } catch (Throwable t) { continue; }
                        handle(v, o, f.getDeclaringClass().getSimpleName() + "." + f.getName(), mainGame, visited, queue, sigCount, sigGames, foreign, maxGamesToPrintPerSig);
                    }
                }
            } catch (Throwable t) {
                // laufendes Spiel mutiert den Graphen; einzelne Fehler ignorieren
            }
        }
        System.out.println("[walker] fertig: " + expanded + " Objekte in " + (System.currentTimeMillis() - t0) + " ms, fremde Games gefunden: " + foreign.size());
        List<Map.Entry<String, Integer>> sigs = new ArrayList<>(sigCount.entrySet());
        sigs.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));
        for (Map.Entry<String, Integer> e : sigs.subList(0, Math.min(25, sigs.size()))) {
            System.out.println("[walker] " + e.getValue() + " Kanten auf " + sigGames.get(e.getKey()).size() + " fremde Games via:\n" + e.getKey());
        }
    }

    private static void handle(Object v, Object parent, String label, Game mainGame, IdentityHashMap<Object, Edge> visited,
                               ArrayDeque<Object> queue, Map<String, Integer> sigCount, Map<String, Set<Game>> sigGames,
                               Set<Game> foreign, int maxGamesToPrintPerSig) {
        if (v == null || v == mainGame) return;
        if (v instanceof Game g) {
            foreign.add(g);
            String sig = pathSignature(parent, label, visited);
            sigCount.merge(sig, 1, Integer::sum);
            sigGames.computeIfAbsent(sig, k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(g);
            return; // Kopie nicht weiter expandieren
        }
        if (!expandable(v) || visited.containsKey(v)) return;
        visited.put(v, new Edge(parent, label));
        queue.add(v);
    }

    /** Pfad von der Wurzel bis zum Elternobjekt als Klasse.Feld-Kette; Wiederholungen (Listen-Knoten) werden zusammengefasst. */
    private static String pathSignature(Object parent, String label, IdentityHashMap<Object, Edge> visited) {
        List<String> parts = new ArrayList<>();
        parts.add(label);
        Object cur = parent;
        int guard = 0;
        while (cur != null && guard++ < 200) {
            Edge e = visited.get(cur);
            if (e == null) break;
            parts.add(cur.getClass().getSimpleName() + " <-" + e.label());
            cur = e.parent();
            if (e.label().startsWith("ROOT:") || e.label().startsWith("STATIC:")) break;
        }
        Collections.reverse(parts);
        StringBuilder sb = new StringBuilder();
        String prev = null; int rep = 0;
        for (String p : parts) {
            if (p.equals(prev)) { rep++; continue; }
            if (rep > 0) sb.append(" (x").append(rep + 1).append(")");
            sb.append("\n    ").append(p);
            prev = p; rep = 0;
        }
        if (rep > 0) sb.append(" (x").append(rep + 1).append(")");
        return sb.toString();
    }

    private static boolean expandable(Object v) {
        Class<?> c = v.getClass();
        if (c == String.class || c == Class.class || v instanceof Enum || v instanceof Number || v instanceof Boolean || v instanceof Character) return false;
        if (v instanceof ClassLoader || v instanceof Thread || v instanceof ThreadGroup) return false;
        String n = c.getName();
        if (c.isArray()) return !c.getComponentType().isPrimitive();
        return !(n.startsWith("java.lang.reflect") || n.startsWith("java.lang.invoke") || n.startsWith("jdk.") || n.startsWith("sun.")
                || n.startsWith("java.io") || n.startsWith("java.nio") || n.startsWith("java.security") || n.startsWith("java.net")
                || n.startsWith("org.junit") || n.startsWith("org.apache.maven") || n.startsWith("ch.qos") || n.startsWith("org.slf4j")
                || n.startsWith("org.tinylog") || n.startsWith("io.sentry") || n.startsWith("java.lang.ref") || n.startsWith("java.util.logging")
                || n.startsWith("mtgplayer.bench.RetentionWalker") || n.startsWith("java.lang.Module") || n.startsWith("java.util.concurrent.locks")
                || n.startsWith("java.lang.Thread"));
    }

    private static List<Field> fields(Class<?> c) {
        return FIELDS.computeIfAbsent(c, k -> {
            List<Field> out = new ArrayList<>();
            for (Class<?> x = k; x != null && x != Object.class; x = x.getSuperclass()) {
                for (Field f : x.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    try { f.setAccessible(true); out.add(f); } catch (Throwable ignored) { }
                }
            }
            return out;
        });
    }

    private static List<Class<?>> forgeClasses(ClassLoader cl) {
        List<Class<?>> out = new ArrayList<>();
        String cp = System.getProperty("java.class.path");
        String surefireCp = System.getProperty("surefire.test.class.path", "");
        for (String entry : (cp + ":" + surefireCp).split(":")) {
            if (!entry.contains("forge") || !entry.endsWith(".jar")) continue;
            try (JarFile jar = new JarFile(entry)) {
                jar.stream().filter(e -> e.getName().endsWith(".class") && !e.getName().contains("module-info"))
                        .forEach(e -> {
                            String name = e.getName().replace('/', '.').replaceAll("\\.class$", "");
                            if (!(name.startsWith("forge.game.") || name.startsWith("forge.ai.") || name.startsWith("forge.util.")
                                    || name.startsWith("forge.card.") || name.startsWith("forge.trackable.") || name.startsWith("forge.item.")
                                    || name.startsWith("forge.deck.") || name.equals("forge.StaticData") || name.equals("forge.ImageKeys")
                                    || name.equals("forge.CardStorageReader"))) return;
                            try {
                                // false = nicht initialisieren, nur bereits geladene/ladbare Klassen ohne Nebenwirkung
                                Class<?> c = Class.forName(name, false, cl);
                                out.add(c);
                            } catch (Throwable ignored) { }
                        });
            } catch (Throwable ignored) { }
        }
        System.out.println("[walker] forge-Klassen gescannt: " + out.size());
        return out;
    }
}
