package mtgplayer.stats;

import forge.card.mana.ManaCost;
import forge.item.PaperCard;
import forge.model.FModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Auswertung je Deck ueber alle gewerteten Partien (Task-3-Brief): Grundlage fuer die Kartentabelle und
 * den Schnittgrund "nie gewirkt". {@code games}: gewertete Partien, in denen das Deck ueberhaupt einen
 * Sitz hatte ({@link MatchRecord#counted()}). {@code withCardData}: Teilmenge davon, fuer die auch eine
 * lesbare Kartendatei vorliegt ({@link CardStore#read}) - eine fehlende oder kaputte Datei senkt NUR
 * diese Zahl, nicht {@code games} (Task-3-Brief: "verlass dich nicht darauf, dass eine vorhandene Datei
 * eine gewertete Partie bedeutet" gilt auch umgekehrt - eine gewertete Partie ohne Datei bleibt trotzdem
 * gewertet). {@code enough}: {@code withCardData >= MIN_GAMES} - unter dieser Schwelle sind die Zeilen
 * statistisch duenn, sie kommen trotzdem mit (das UI entscheidet, ob es warnt).
 */
public record CardStats(int games, int withCardData, boolean enough, List<Card> cards) {

    /** Unter so vielen Partien MIT Kartendaten gilt eine Kartenzeile als statistisch zu duenn ({@link #enough}). */
    public static final int MIN_GAMES = 5;

    /** Grundlaender zaehlen nicht mit - sie liegen in praktisch jeder Hand und sagen ueber das Deck nichts aus. */
    private static final Set<String> BASIC_LANDS = Set.of("Plains", "Island", "Swamp", "Mountain", "Forest");

    /**
     * Eine Kartenzeile des Decks. {@code imageKey}/{@code manaCost}/{@code cmc} kommen aus Forges
     * Kartendatenbank und bleiben {@code null}, wenn der Name dort unbekannt ist - die Zeile bleibt
     * trotzdem stehen (Task-3-Brief). Die uebrigen Felder zaehlen Partien aus {@code withCardData}, nicht
     * Kopien: {@code handGames}/{@code castGames} Partien mit mindestens einer Hand- bzw. Wirk-Beobachtung,
     * {@code avgCastTurn} der Schnitt der fruehesten Wirk-Zuege ueber {@code castGames}, {@code stuckGames}
     * Partien mit Hand, aber ohne Wirkung, {@code neverDrawnGames} Partien, in denen die Karte ueberhaupt
     * nicht auftauchte (keine Zeile in der Kartendatei), {@code counteredGames}/{@code lostGames} Partien
     * mit mindestens einer Gegenzauberung bzw. einem Verlust.
     */
    public record Card(String name, String imageKey, String manaCost, Integer cmc, int handGames, int castGames,
                        Double avgCastTurn, int stuckGames, int neverDrawnGames, int counteredGames,
                        int lostGames) { }

    /**
     * Wertet {@code deck} ueber {@code matches} aus. {@code deck} darf in jeder Schreibweise hereinkommen
     * (roh mit "/" oder Forges sanitisierte Form mit "_") - {@link #normalize} gruppiert wie
     * {@code web/src/matchStats.ts} (dort {@code deckKey}). Je Partie zaehlt hoechstens ein Sitz: bei
     * einem Spiegel (dasselbe Deck auf zwei Sitzen, z.B. Sparring) gewinnt der menschliche Sitz, sonst der
     * erste passende - dieselbe Regel wie {@code pickSeat} im Client.
     */
    public static CardStats of(String deck, List<MatchRecord> matches, CardStore store) {
        String key = normalize(deck);
        // Erster Durchlauf: je zaehlender Partie die Kartenzeilen des gewaehlten Sitzes einsammeln, falls
        // eine lesbare Kartendatei vorliegt - eine Liste "eine Karte je Name" je Partie.
        List<Map<String, CardLog.Card>> perGame = new ArrayList<>();
        int games = 0;
        for (MatchRecord match : matches) {
            Integer seatIndex = pickSeat(match, key);
            if (seatIndex == null || !match.counted()) {
                continue;
            }
            games++;
            Optional<CardLog> log = store.read(match.id());
            if (log.isEmpty()) {
                continue;
            }
            CardLog.SeatCards seatCards = findSeat(log.get(), seatIndex);
            if (seatCards == null) {
                continue;
            }
            Map<String, CardLog.Card> byName = new LinkedHashMap<>();
            // Befund 5: seatCards.cards() ist seit dem kanonischen Konstruktor von CardLog.SeatCards nie
            // mehr null - die Pruefung bleibt trotzdem stehen (Belt-and-Suspenders, wie im Review
            // gefordert), falls ein Datensatz je auf einem anderen Weg als Json.mapper() entsteht.
            List<CardLog.Card> rows = seatCards.cards();
            if (rows != null) {
                for (CardLog.Card card : rows) {
                    byName.put(card.name(), card);
                }
            }
            perGame.add(byName);
        }
        int withCardData = perGame.size();

        // Vereinigung aller je gesehenen Kartennamen (ohne Grundlaender) - eine Karte, die in EINER Partie
        // auftauchte, muss ueber alle anderen Partien mit Kartendaten hinweg gezaehlt werden (fehlt sie
        // dort, ist das "nie gezogen", nicht "kommt nicht vor").
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, CardLog.Card> game : perGame) {
            names.addAll(game.keySet());
        }
        names.removeAll(BASIC_LANDS);

        List<Card> cards = new ArrayList<>();
        for (String name : names) {
            cards.add(buildCard(name, perGame));
        }
        cards.sort(Comparator.comparingInt(Card::stuckGames).reversed()
                .thenComparing(Comparator.comparingInt(Card::handGames).reversed())
                .thenComparing(Card::name));

        return new CardStats(games, withCardData, withCardData >= MIN_GAMES, cards);
    }

    private static Card buildCard(String name, List<Map<String, CardLog.Card>> perGame) {
        int handGames = 0;
        int castGames = 0;
        int stuckGames = 0;
        int neverDrawnGames = 0;
        int counteredGames = 0;
        int lostGames = 0;
        int castTurnSum = 0;
        int castTurnCount = 0;
        for (Map<String, CardLog.Card> game : perGame) {
            CardLog.Card row = game.get(name);
            if (row == null) {
                neverDrawnGames++;
                continue;
            }
            boolean hand = row.hand() != null;
            boolean cast = row.cast() != null;
            if (hand) {
                handGames++;
            } else {
                // keine Zeile mit Hand-Beobachtung heisst wie eine fehlende Zeile: nie gezogen.
                neverDrawnGames++;
            }
            if (cast) {
                castGames++;
            }
            if (hand && !cast) {
                stuckGames++;
            }
            if (row.countered() != null) {
                counteredGames++;
            }
            if (row.lost() != null) {
                lostGames++;
            }
            if (row.castTurn() != null) {
                castTurnSum += row.castTurn();
                castTurnCount++;
            }
        }
        Double avgCastTurn = castTurnCount == 0 ? null : (double) castTurnSum / castTurnCount;

        PaperCard pc = FModel.getMagicDb().getCommonCards().getCard(name);
        String imageKey = null;
        String manaCost = null;
        Integer cmc = null;
        if (pc != null) {
            imageKey = pc.getImageKey(false);
            ManaCost mc = pc.getRules() == null ? null : pc.getRules().getManaCost();
            if (mc != null) {
                manaCost = mc.getShortString();
                cmc = mc.getCMC();
            }
        }
        return new Card(name, imageKey, manaCost, cmc, handGames, castGames, avgCastTurn, stuckGames,
                neverDrawnGames, counteredGames, lostGames);
    }

    /** Der Sitz-Index des Decks in {@code match}, oder {@code null}, wenn das Deck gar nicht dabei war -
     *  bei einem Spiegel gewinnt der menschliche Sitz, sonst der erste Treffer (siehe {@link #of}). */
    private static Integer pickSeat(MatchRecord match, String key) {
        Integer fallback = null;
        for (int i = 0; i < match.seats().size(); i++) {
            MatchRecord.Seat seat = match.seats().get(i);
            if (!normalize(seat.deck()).equals(key)) {
                continue;
            }
            if (seat.human()) {
                return i;
            }
            if (fallback == null) {
                fallback = i;
            }
        }
        return fallback;
    }

    private static CardLog.SeatCards findSeat(CardLog log, int seatIndex) {
        // Befund 5: log.seats() ist seit dem kanonischen Konstruktor von CardLog nie mehr null (eine
        // formfremde Datei wie {"v":1,"id":"m3"} liest sich als leere Liste) - die Pruefung bleibt
        // trotzdem stehen, siehe CardStats#of.
        List<CardLog.SeatCards> seats = log.seats();
        if (seats == null) {
            return null;
        }
        for (CardLog.SeatCards seatCards : seats) {
            if (seatCards.seat() == seatIndex) {
                return seatCards;
            }
        }
        return null;
    }

    /**
     * Schluessel, unter dem zwei Schreibweisen desselben Decks zusammenfinden - dieselbe Regel wie
     * {@code deckKey} in {@code web/src/matchStats.ts}: Forge ersetzt beim Speichern jedes "/" durch "_"
     * (Decks werden intern als "verzeichnis/name" referenziert), aeltere Datensaetze auf der Platte tragen
     * deshalb Forges Schreibweise statt der rohen.
     */
    private static String normalize(String name) {
        return name == null ? null : name.replace('/', '_');
    }
}
