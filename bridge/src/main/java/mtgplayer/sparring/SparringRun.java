package mtgplayer.sparring;

import forge.deck.Deck;
import forge.util.MyRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import mtgplayer.ai.AiConfig;
import mtgplayer.decks.DeckStore;
import mtgplayer.match.AiMatch;
import mtgplayer.protocol.Messages;
import mtgplayer.stats.MatchRecord;
import mtgplayer.stats.MatchStore;

/**
 * Ein Sparring-Lauf (Spec §3): zieht je Partie einen Gegner (siehe {@link SparringOpponents}), spielt
 * sie ueber den {@link GameRunner} nacheinander auf einem Hintergrund-Thread und legt jeden gelieferten
 * {@link MatchRecord} im {@link MatchStore} ab.
 *
 * <p>Genau ein Lauf zur Zeit; ein zweiter {@link #start} wirft {@link IllegalStateException}
 * ("Sparring läuft noch"). Eine gescheiterte Partie (der Runner wirft oder liefert nichts) zaehlt mit,
 * landet als Zeile in {@code errors} und beendet den Lauf nicht. {@link #cancel()} beendet die gerade
 * laufende Partie ueber {@link GameRunner#cancel()} (beim Kindprozess-Runner: abschiessen); sie zaehlt
 * dann als Fehler "abgebrochen". Kommt sie trotzdem noch zu einem Ergebnis, wird das gespeichert - in
 * jedem Fall tritt danach keine weitere mehr an und der Lauf endet mit {@code running == false}.</p>
 *
 * <p>{@code out} bekommt zweierlei: jedes {@link Messages.SparringProgress} (einmal zu Beginn und nach
 * jedem Spielende, das letzte mit {@code running == false}) und - unmittelbar davor - jeden soeben
 * gespeicherten {@link MatchRecord}. Der Aufrufer schickt das Fortschritts-Record weiter und beantwortet
 * den Datensatz mit seiner eigenen, gedeckelten {@code matches}-Liste (siehe
 * {@code mtgplayer.server.Bridge}); hier wird sie nicht gebaut, weil ihr Zuschnitt dort haengt.</p>
 */
public final class SparringRun {

    private final DeckStore decks;
    private final MatchStore matches;
    private final GameRunner runner;
    private final Consumer<Object> out;

    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean cancelled;

    public SparringRun(DeckStore decks, MatchStore matches, GameRunner runner, Consumer<Object> out) {
        this.decks = decks;
        this.matches = matches;
        this.runner = runner;
        this.out = out;
    }

    public boolean running() {
        return running.get();
    }

    /**
     * Zieht die Gegner und startet den Lauf auf einem Hintergrund-Thread; kehrt sofort zurueck.
     *
     * @throws IllegalStateException    es laeuft schon ein Sparring
     * @throws IllegalArgumentException unbekanntes Deck oder keine Gegner (siehe {@link SparringOpponents})
     */
    public void start(SparringArgs args) {
        // Die Sperre VOR der Gegnerwahl: die liest alle Deck-Dateien, und zwei fast gleichzeitige
        // Starts sollen sich nicht daran vorbeischleichen.
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Sparring läuft noch");
        }
        List<String> opponents;
        try {
            opponents = SparringOpponents.draw(SparringOpponents.candidates(decks, args.deck()),
                    args.games(), new Random());
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
        cancelled = false;
        Thread t = new Thread(() -> loop(args, opponents), "sparring");
        t.setDaemon(true);
        try {
            t.start();
        } catch (RuntimeException e) {
            running.set(false);   // Thread kam nie zum Laufen - die Sperre nicht haengen lassen
            throw e;
        }
    }

    /**
     * Bricht den Lauf ab: die gerade laufende Partie wird ueber {@link GameRunner#cancel()} beendet
     * (Kindprozess abschiessen), danach tritt keine weitere mehr an. Ohne laufenden Lauf ohne Wirkung.
     * Blockiert nicht - der Aufrufer ist der WebSocket-Thread.
     */
    public void cancel() {
        cancelled = true;
        try {
            runner.cancel();
        } catch (RuntimeException e) {
            // Ein Runner, der sich nicht abschiessen laesst, darf den Abbruch nicht zum Fehler des
            // Aufrufers machen - das Flag oben greift spaetestens vor der naechsten Partie.
            e.printStackTrace();
        }
    }

    private void loop(SparringArgs args, List<String> opponents) {
        int total = opponents.size();
        int done = 0;
        List<String> errors = new ArrayList<>();
        try {
            out.accept(progress(0, total, opponents.get(0), errors, true));
            for (String opponent : opponents) {
                // Unterbrechung wie ein Abbruch behandeln: wird dieser Thread von aussen unterbrochen
                // (Abriss beim Herunterfahren), soll der Lauf stehen bleiben und nicht die restlichen
                // Partien im Eiltempo durchfallen lassen - eine unterbrochene Wartezeit im Runner kehrt
                // sofort zurueck, und das tut sie dann fuer jede folgende Partie wieder.
                if (cancelled || Thread.currentThread().isInterrupted()) {
                    cancelled = true;
                    break;
                }
                try {
                    MatchRecord r = runner.play(args, opponent, System.nanoTime());
                    if (r == null) {
                        throw new IllegalStateException("kein Ergebnis");
                    }
                    matches.add(r);
                    out.accept(r);
                } catch (RuntimeException e) {
                    // Eine Partie darf den Lauf nicht beenden (Spec §3) - Grund vermerken, weiter.
                    // Nach einem Abbruch ist der Grund immer derselbe (das Kind wurde abgeschossen);
                    // "Kindprozess: exit=143, letzte stderr-Zeile: ..." waere fuer Kevin nur Rauschen.
                    e.printStackTrace();
                    errors.add(opponent + ": " + (cancelled ? "abgebrochen"
                            : e.getMessage() == null ? e.toString() : e.getMessage()));
                }
                done++;
                boolean more = done < total && !cancelled;
                out.accept(progress(done, total, more ? opponents.get(done) : null, errors, more));
            }
            if (done == 0) {
                // Vor der ersten Partie abgebrochen: der Lauf muss trotzdem mit running == false enden.
                out.accept(progress(0, total, null, errors, false));
            }
        } catch (RuntimeException e) {
            // wie in Bridge: sonst stirbt der Fehler still auf dem Hintergrund-Thread
            e.printStackTrace();
            errors.add("Sparring: " + e);
            out.accept(progress(done, total, null, errors, false));
        } finally {
            running.set(false);
        }
    }

    private static Messages.SparringProgress progress(int done, int total, String current, List<String> errors,
                                                      boolean running) {
        return new Messages.SparringProgress(done, total, current, List.copyOf(errors), running);
    }

    /**
     * Genau eine Partie, so wie sie {@code Main --sparring-one} im Kindprozess spielt: beide Decks aus
     * dem gespeicherten Bestand laden, {@code MyRandom} auf den Seed setzen, {@link AiMatch#play}
     * aufrufen und den vom {@code MatchRecorder} erzeugten Datensatz (Quelle {@code "sparring"})
     * zurueckgeben. Die Sitzreihenfolge wechselt mit dem Seed; den Startspieler lost Forge zusaetzlich
     * selbst aus (wie im Bench).
     *
     * @param log bekommt jede Forge-Logzeile (im Kindprozess: stdout)
     */
    public static MatchRecord playOne(SparringArgs.Job job, Consumer<String> log) {
        DeckStore store = DeckStore.standard();
        Deck own = store.load(job.deck());
        Deck foe = store.load(job.opponent());
        MyRandom.setRandom(new Random(job.seed()));
        boolean swap = (job.seed() & 1L) != 0L;
        List<Deck> table = swap ? List.of(foe, own) : List.of(own, foe);
        List<String> names = swap ? List.of(job.opponent(), job.deck()) : List.of(job.deck(), job.opponent());
        AiConfig ai = job.aiConfig();
        AtomicReference<MatchRecord> record = new AtomicReference<>();
        AiMatch.play(table, names, List.of(ai, ai), job.timeout(), job.maxTurns(), log, record::set);
        MatchRecord r = record.get();
        if (r == null) {
            throw new IllegalStateException("Partie lieferte keinen Datensatz");
        }
        return r;
    }
}
