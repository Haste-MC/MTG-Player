package mtgplayer.forge;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Die Stacktraces, die bei einem stehenden Tisch etwas zu sagen haben.
 *
 * <p><b>Anlass.</b> Am 2026-10-06 meldete der Wachhund der {@link mtgplayer.gui.ThinkingTicker
 * Denk-Anzeige} zwei Vorfaelle und legte dazu genau einen Stacktrace ab - den des gemerkten
 * Spiel-Threads, der beide Male schon {@code TERMINATED} war. Ein toter Thread hat keinen Stack; der
 * Vermerk bestand also aus einer Kopfzeile und sonst nichts, und die Frage "warum steht der Tisch?"
 * blieb offen. Beantwortet haette sie der UI-Thread: ueber ihn laeuft Forges gesamte
 * Zustandsverarbeitung ({@code FControlGameEventHandler.processEvents}) einschliesslich
 * {@code finishGame()}, also genau der Stelle, die das Spielende an den Browser schickt und den
 * Wachhund abschaltet.
 *
 * <p><b>Wer mitkommt.</b> Der gemerkte Spiel-Thread immer (auch wenn sein Name nicht ins Muster
 * passt - im Zuschauer-Modus traegt der Log-Beobachter schon mal einen Arbeiter-Thread nach), dazu
 * jeder lebende Thread, der eine Partie bewegen kann: {@code bridge-ui} (unser UI-Thread),
 * {@code Game-*} (Forges Spiel-Thread-Pool) und {@code bridge-bg} (Hintergrund-Aufgaben wie das
 * Beenden einer Zuschauer-Partie). Die Rechen-Threads der KI bleiben draussen - rechnet die KI
 * wirklich, steht das im Stack des Spiel-Threads selbst, und es waeren Dutzende.
 *
 * <p><b>Verklemmungen.</b> Zwei der bisher gefundenen Haenger waren echte Verklemmungen zwischen
 * Spiel- und UI-Thread (siehe {@code HumanMatch.end()}). Genau die findet die JVM selbst, und zwar
 * zuverlaessiger als ein Mensch zwei Stacks nebeneinanderlegt - deshalb steht eine erkannte
 * Verklemmung als erste Zeile im Abzug.
 *
 * <p><b>Zeilenbudget.</b> Ein Abzug kostet hoechstens {@value #MAX_THREADS} Threads mit je
 * {@value #MAX_FRAMES} Rahmen; was darueber liegt, wird mit einer Zeile vermerkt statt abgeschnitten.
 * Der Wachhund meldet einmal je Vorfall, mehr Budget braucht es nicht.
 */
public final class ThreadDump {

    /** Hoechstens so viele Threads je Abzug - der gemerkte Spiel-Thread zaehlt mit. */
    static final int MAX_THREADS = 8;

    /** Hoechstens so viele Rahmen je Thread; eine Endlosrekursion soll das Log nicht fluten. */
    static final int MAX_FRAMES = 40;

    private ThreadDump() { }

    /**
     * @param known der gemerkte Spiel-Thread, oder {@code null}, wenn noch keiner bekannt ist
     * @return mehrzeiliger Abzug, ohne Zeilenumbruch am Ende
     */
    public static String of(Thread known) {
        return of(known, Thread.getAllStackTraces());
    }

    /**
     * Wie {@link #of(Thread)}, nur mit vorgegebener Thread-Liste. Fuer Tests: in einer laufenden JVM
     * leben immer fremde Threads mit (Forges Pool, ein UI-Thread aus einem anderen Testfall), und eine
     * Zusicherung ueber Reihenfolge oder Budget waere damit nicht mehr wiederholbar.
     *
     * @param all Threads samt Rahmen, wie {@link Thread#getAllStackTraces()} sie liefert
     */
    static String of(Thread known, Map<Thread, StackTraceElement[]> all) {
        StringBuilder sb = new StringBuilder();

        String deadlock = deadlock();
        if (deadlock != null) {
            sb.append(deadlock).append('\n');
        }

        if (known == null) {
            sb.append("(Spiel-Thread unbekannt)");
        } else {
            // Ein TERMINATED Thread steht nicht mehr in der Abzugs-Karte: dann bleibt es bei der
            // Kopfzeile, und genau die ist die Nachricht ("der Thread ist tot").
            append(sb, "Spiel-Thread", known, all.getOrDefault(known, new StackTraceElement[0]));
        }

        List<Thread> others = new ArrayList<>();
        for (Thread t : all.keySet()) {
            if (t != known && interesting(t)) {
                others.add(t);
            }
        }
        // Nach Wichtigkeit, nicht nach Namen: lebt Forges Pool noch mit mehreren Game-Threads, fiele
        // bei alphabetischer Ordnung ausgerechnet "bridge-ui" aus dem Budget - der Thread, dessen
        // Stack die Frage beantwortet. getId() statt threadId(): letzteres gibt es erst ab Java 19.
        others.sort(Comparator.comparingInt(ThreadDump::rank)
                .thenComparing(Thread::getName)
                .thenComparingLong(Thread::getId));

        int budget = MAX_THREADS - 1;
        for (int i = 0; i < others.size(); i++) {
            if (i >= budget) {
                sb.append("\n... (").append(others.size() - budget).append(" weitere Threads)");
                break;
            }
            Thread t = others.get(i);
            sb.append('\n');
            append(sb, "Thread", t, all.get(t));
        }
        return sb.toString();
    }

    /** Threads, die eine Partie bewegen koennen - siehe Klassenkommentar "Wer mitkommt". */
    static boolean interesting(Thread t) {
        String name = t.getName();
        return name.equals("bridge-ui") || name.equals("bridge-bg") || name.startsWith("Game-");
    }

    /** Reihenfolge im Abzug: wer am ehesten erklaert, warum der Tisch steht, kommt zuerst. */
    static int rank(Thread t) {
        String name = t.getName();
        if (name.equals("bridge-ui")) {
            return 0;
        }
        return name.equals("bridge-bg") ? 1 : 2;
    }

    private static void append(StringBuilder sb, String label, Thread t, StackTraceElement[] frames) {
        sb.append(label).append(" \"").append(t.getName()).append("\" (").append(t.getState()).append(')');
        for (int i = 0; i < frames.length; i++) {
            if (i >= MAX_FRAMES) {
                sb.append("\n\t... (").append(frames.length - MAX_FRAMES).append(" weitere Rahmen)");
                break;
            }
            sb.append("\n\tat ").append(frames[i]);
        }
    }

    /**
     * Von der JVM erkannte Verklemmung (Monitore UND {@code Lock}-Objekte), oder {@code null}. Jede
     * Panne hier bleibt stumm: der Abzug ist ein Beweismittel und darf nicht selbst zur Fehlerquelle
     * werden, und in einer JVM ohne Thread-Verwaltung gibt es eben keine Antwort.
     */
    private static String deadlock() {
        try {
            ThreadMXBean beans = ManagementFactory.getThreadMXBean();
            long[] ids = beans.findDeadlockedThreads();
            if (ids == null || ids.length == 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder("VERKLEMMUNG erkannt zwischen:");
            for (var info : beans.getThreadInfo(ids)) {
                if (info == null) {
                    continue;
                }
                sb.append("\n\t\"").append(info.getThreadName()).append("\" wartet auf ")
                        .append(info.getLockName());
                if (info.getLockOwnerName() != null) {
                    sb.append(", gehalten von \"").append(info.getLockOwnerName()).append('"');
                }
            }
            return sb.toString();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
