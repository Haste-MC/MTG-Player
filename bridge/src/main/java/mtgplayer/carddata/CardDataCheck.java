package mtgplayer.carddata;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.Match;
import forge.game.card.CardFactory;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.item.PaperCard;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import mtgplayer.ai.AiConfig;
import mtgplayer.match.CommanderRules;

/**
 * Stellt fest, ob Forge mit den vorliegenden Kartendaten arbeiten kann - die Pruefung hinter dem
 * woechentlichen Abgleich mit upstream (siehe {@code scripts/kartendaten-sync.sh}).
 *
 * <p>Die Klasse URTEILT NICHT: sie berichtet, was sie findet, und ueberlaesst jede Folgerung dem
 * Aufrufer. So bleibt sie auch von Hand brauchbar, wenn man nur wissen will, wie es gerade steht.</p>
 *
 * <p>Der wichtigste Posten ist {@link Befund#nichtBaubar}: eine Karte kann in der Datenbank stehen und
 * trotzdem nicht ENTSTEHEN, wenn ihr Skript ein Schluesselwort benutzt, das unsere Forge-Fassung noch
 * nicht kennt. Beim Abgleich am 2026-09-25 waren das 37 von 394 Karten ({@code Empower} fehlt in
 * {@code ApiType}, {@code FlippedCoinOnce} in {@code TriggerType}, {@code CantBeBeamedUp} und
 * {@code IgnorePlaneswalkerZeroLoyaltyRule} in {@code StaticAbilityMode}). Im Spiel reisst so eine
 * Karte den Spielaufbau ab, sobald sie in einem Deck liegt - erkennbar ist das nur, indem man sie
 * baut.</p>
 */
public final class CardDataCheck {

    public record Problem(String karte, String grund) { }

    public record Befund(int karten,
                         List<Problem> nichtBaubar,
                         List<String> nichtAuffindbar,
                         List<String> doppelteSetCodes,
                         List<String> doppelteNamen,
                         List<String> parseMeldungen) {

        /** Parse-Meldungen zaehlen NICHT: sie sind Beiwerk fuer den Bericht, kein Mangel. */
        public boolean sauber() {
            return nichtBaubar.isEmpty() && nichtAuffindbar.isEmpty()
                    && doppelteSetCodes.isEmpty() && doppelteNamen.isEmpty();
        }
    }

    private CardDataCheck() { }

    /**
     * @param res            Forges {@code res}-Verzeichnis, aus dem die geladenen Daten stammen
     * @param parseMeldungen was Forge beim Laden gemeldet hat (siehe {@code Main})
     */
    public static Befund pruefen(Path res, List<String> parseMeldungen) {
        StaticData sd = StaticData.instance();
        List<PaperCard> alle = new ArrayList<>(sd.getCommonCards().getUniqueCards());
        alle.addAll(sd.getVariantCards().getUniqueCards());

        Set<String> inDerDatenbank = new TreeSet<>();
        // Zusammengesetzte Karten (Split, Raeume) fuehrt die Datenbank unter "A // B", ihr Skript traegt als
        // ERSTE Name:-Zeile aber nur "A" - und genau die ordnet CardFiles zu. Ohne diesen Schnitt stuenden
        // 126 einwandfreie Karten als "nicht auffindbar" im Befund.
        alle.forEach(pc -> inDerDatenbank.add(vorderseite(pc.getName())));

        Map<String, List<Path>> kartendateien;
        Map<String, List<Path>> editionsdateien;
        try {
            kartendateien = CardFiles.karten(res);
            editionsdateien = CardFiles.editionen(res);
        } catch (IOException e) {
            throw new IllegalStateException("kann die Kartendateien unter " + res + " nicht lesen", e);
        }

        List<String> nichtAuffindbar = new ArrayList<>();
        kartendateien.keySet().forEach(name -> {
            if (!inDerDatenbank.contains(name)) {
                nichtAuffindbar.add(name);
            }
        });

        return new Befund(sd.getCommonCards().getUniqueCards().size(),
                nichtBaubar(alle),
                nichtAuffindbar,
                CardFiles.doppelte(editionsdateien),
                CardFiles.doppelte(kartendateien),
                parseMeldungen);
    }

    private static String vorderseite(String datenbankname) {
        int schnitt = datenbankname.indexOf(" // ");
        return schnitt < 0 ? datenbankname : datenbankname.substring(0, schnitt);
    }

    /** Baut jede Karte im Spielkontext - genau das, was beim Deckaufbau einer echten Partie geschieht. */
    private static List<Problem> nichtBaubar(List<PaperCard> alle) {
        Player besitzer = leeresSpiel().getPlayers().get(0);
        List<Problem> probleme = new ArrayList<>();
        for (PaperCard pc : alle) {
            try {
                CardFactory.getCard(pc, besitzer, besitzer.getGame());
            } catch (RuntimeException | LinkageError e) {
                probleme.add(new Problem(pc.getName(), e.toString()));
            }
        }
        return probleme;
    }

    /**
     * Zwei Sitze mit leeren Decks, Commander-Regeln wie in einer echten Partie (siehe
     * {@link CommanderRules}) - mehr braucht {@link CardFactory#getCard} nicht.
     */
    private static Game leeresSpiel() {
        // AiConfig.DEFAULT statt AiConfig.parse(...): parse() prueft das Profil ueber Forges
        // AiProfileUtil, DEFAULT ist die fertige Instanz (STANDARD/"Default") und braucht das nicht.
        List<RegisteredPlayer> sitze = List.of(
                new RegisteredPlayer(new Deck()).setPlayer(AiConfig.DEFAULT.newLobbyPlayer("Pruefung 1")),
                new RegisteredPlayer(new Deck()).setPlayer(AiConfig.DEFAULT.newLobbyPlayer("Pruefung 2")));
        GameRules regeln = CommanderRules.create();
        Game spiel = new Game(sitze, regeln, new Match(regeln, sitze, "Kartendaten"));
        // KEIN setAge(GameStage.Play): Forge baut die Karten in einer echten Partie im Anfangszustand
        // (BeforeMulligan, siehe Game.age) - GameAction.startGame schaltet erst NACH dem Deckaufbau auf
        // Play um. Im Zustand Play wertet Card.getAbilityText Triggerbedingungen aus, die einen aktiven
        // Spieler brauchen, den es hier nicht gibt: Razor Pendulum und Mask of Intolerance fielen dadurch
        // mit einer NullPointerException durch, obwohl sie in jeder echten Partie einwandfrei entstehen.
        return spiel;
    }
}
