package mtgplayer.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kartenbiografie einer einzelnen Partie (Spec {@code 2026-09-25-kartenaufzeichnung-design.md} §2), von
 * {@link MatchRecorder} gefuellt. Bewusst eine eigene Ablage statt ein Feld an {@link MatchRecord}: die
 * Kartendaten kosten rund 7 KB je Sitz und wuerden {@code matches.json} auf ein Vielfaches aufblaehen
 * (Design §1) - {@link MatchRecord} und seine Formatversion 2 bleiben deshalb unveraendert, verbunden
 * ist nur die gemeinsame Partie-{@code id}.
 *
 * <p>{@code v}: Formatversion (aktuell {@link #VERSION}). {@code seats}: nur Sitze, deren Deck beim
 * Aufzeichnen in {@code ownDecks} stand ({@code MatchRecorder}); alle anderen Sitze fehlen ganz aus der
 * Liste, statt mit leeren {@code cards} aufzutauchen - das unterscheidet "kein eigenes Deck" von "eigenes
 * Deck ohne Kartendaten" (Letzteres kommt in der Praxis nicht vor).</p>
 */
public record CardLog(int v, String id, List<SeatCards> seats) {

    /** Aktuelle Formatversion; eigenes Format, siehe Klassenkommentar. */
    public static final int VERSION = 1;

    /**
     * Kanonischer Konstruktor: eine fehlende Sitzliste wird zu einer leeren Liste. Befund 5: eine
     * gueltige, aber formfremde Datei wie {@code {"v":1,"id":"m3"}} (kein {@code "seats"}-Feld) las sich
     * vorher als {@code seats == null} und riss ueber eine {@code NullPointerException} die ganze
     * Auswertung (Kartentabelle UND Kartenvorschlaege) des betroffenen Decks mit - die Spec verspricht
     * fuer eine kaputte Datei "keine Daten", nie eine Ausnahme. Mit der leeren Liste liefert
     * {@code CardStats.findSeat} fuer diese Partie konsequent keinen Treffer, genau wie bei einer Datei
     * ohne den angefragten Sitz.
     */
    public CardLog {
        seats = seats == null ? List.of() : seats;
    }

    /** Neuer Datensatz in der aktuellen Version. */
    public CardLog(String id, List<SeatCards> seats) {
        this(VERSION, id, seats);
    }

    /**
     * Kartenzeilen eines aufgezeichneten Sitzes. {@code seat}: Index wie in {@code MatchRecord.seats}
     * (nicht wiederholt fuer jede Karte, sondern einmal je Sitz). {@code deck}: der Deckname, unter dem
     * aufgezeichnet wurde (siehe {@code MatchRecorder.Seat#deckName}).
     */
    public record SeatCards(int seat, String deck, List<Card> cards) {
        /** Dieselbe Normalisierung wie am kanonischen Konstruktor von {@link CardLog} (Befund 5): eine
         *  fehlende Kartenliste wird zu einer leeren Liste statt {@code null}. */
        public SeatCards {
            cards = cards == null ? List.of() : cards;
        }
    }

    /**
     * Eine Kartenzeile, nach {@link #merge} bereits je Name zusammengefasst. {@code copies}: Anzahl der
     * zusammengefassten Kopien (mindestens 1, nie {@code null}). {@code hand}/{@code cast}/
     * {@code countered}/{@code lost}: Zaehlungen ueber die ganze Partie; ein Wert von 0 steht als
     * {@code null} in der Datei ({@code Json.MAPPER} ist {@code NON_NULL}), damit die Datei nicht mit
     * Nullen volllaeuft - siehe {@link #merge}. {@code castTurn}: der EIGENE Zug des Sitzes (nicht
     * Forges globale Zugnummer - dieselbe Zaehlweise wie {@code MatchRecord.Seat.firstCommanderTurn}),
     * in dem die erste der zusammengefassten Kopien gewirkt wurde, {@code null} wenn die Karte nie
     * gewirkt wurde. {@code end}: die Endzone
     * kleingeschrieben ({@code hand}, {@code library}, {@code graveyard}, {@code exile},
     * {@code battlefield}, {@code command}, {@code stack}, ...) der zuletzt zusammengefassten Kopie in
     * {@code rows} - Befund 9: NICHT die chronologisch letzte Kopie, sondern die Kopie, die in der
     * internen Kartenzaehlung ({@code MatchRecorder.Seat#byCardId}, einer {@code HashMap}) zuletzt an
     * der Reihe war, also eine beliebige unter mehreren tatsaechlichen Kopien. Bei nur einer Kopie ist
     * das immer ihre eigene Zone; bei mehreren siehe {@link #merge} fuer die Einordnung. {@code none},
     * wenn die Karten-Id am Partieende nicht mehr aufloesbar war (siehe {@code MatchRecorder.finish}).
     */
    public record Card(String name, Integer copies, Integer hand, Integer cast, Integer castTurn,
                        Integer countered, Integer lost, String end) { }

    /**
     * Fasst Kartenzeilen je Name zusammen (Design §2: "vier Kopien derselben Karte sind eine Zeile").
     * Die Zaehler {@code copies}/{@code hand}/{@code cast}/{@code countered}/{@code lost} werden
     * addiert (eine fehlende bzw. {@code null} Eingabe zaehlt dabei als 0), {@code castTurn} ist der
     * kleinste unter den gesehenen Werten (nie gewirkt bleibt {@code null}), und {@code end} ist die
     * Zone der Kopie, die in {@code rows} zuletzt steht - bei tatsaechlich mehreren Kopien meist
     * gleich, kann aber auseinanderlaufen (eine Kopie im Friedhof, eine noch in der Bibliothek); das
     * ist hingenommen, siehe Design. Die Ausgabe ist nach Namen sortiert (die Reihenfolge in
     * {@code rows} spielt sonst keine Rolle) und traegt die 0-zu-{@code null}-Umwandlung fuer die
     * geschriebene Datei.
     */
    public static List<Card> merge(List<Card> rows) {
        Map<String, Acc> byName = new LinkedHashMap<>();
        for (Card r : rows) {
            Acc a = byName.computeIfAbsent(r.name(), n -> new Acc());
            a.copies += nz(r.copies());
            a.hand += nz(r.hand());
            a.cast += nz(r.cast());
            a.countered += nz(r.countered());
            a.lost += nz(r.lost());
            a.castTurn = earliest(a.castTurn, r.castTurn());
            a.end = r.end();
        }
        List<Card> out = new ArrayList<>();
        for (Map.Entry<String, Acc> e : byName.entrySet()) {
            Acc a = e.getValue();
            out.add(new Card(e.getKey(), zeroToNull(a.copies), zeroToNull(a.hand), zeroToNull(a.cast),
                    a.castTurn, zeroToNull(a.countered), zeroToNull(a.lost), a.end));
        }
        out.sort(Comparator.comparing(Card::name));
        return out;
    }

    private static int nz(Integer i) {
        return i == null ? 0 : i;
    }

    private static Integer zeroToNull(int n) {
        return n == 0 ? null : n;
    }

    private static Integer earliest(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return Math.min(a, b);
    }

    /** Laufende Summe waehrend {@link #merge}; ein Name kann mehrfach in {@code rows} stehen. */
    private static final class Acc {
        int copies;
        int hand;
        int cast;
        int countered;
        int lost;
        Integer castTurn;
        String end;
    }
}
