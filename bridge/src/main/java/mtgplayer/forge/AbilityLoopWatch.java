package mtgplayer.forge;

import com.google.common.eventbus.Subscribe;
import forge.game.Game;
import forge.game.card.CardView;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventTurnBegan;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.player.LobbyPlayerHuman;
import mtgplayer.ai.AiLobbyPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Wächter gegen eine KI, die sich in derselben Fähigkeit festfährt - Anlass war eine echte
 * 4-Spieler-Partie, in der ein KI-Sitz eine Ausrüstung (vermutlich Lightning Greaves, Ausrüsten
 * {@code {0}}) offenbar endlos umgehängt und die Partie damit faktisch stillgelegt hat. Zwei
 * Nachstellversuche auf gebauten Brettern konnten das Verhalten NICHT reproduzieren (siehe
 * {@code .superpowers/sdd/loop-watch-report.md}); dieser Wächter liefert beim naechsten Auftreten
 * endlich Fakten statt eine dritte Vermutung.
 *
 * <p><b>Was gezaehlt wird.</b> {@code GameEventSpellAbilityCast} feuert nicht nur fuer Zauber,
 * sondern (siehe {@code GameEventSpellAbilityCast.toString()}: "cast"/"triggered"/"activated")
 * auch fuer ausgeloeste UND aktivierte Faehigkeiten - genau das deckt den Verdachtsfall ab, denn
 * Ausruesten ist eine aktivierte Faehigkeit, kein Zauber. Gezaehlt wird je Zug und je Paar aus
 * Karte (ueber ihre Id, nicht ihren Namen - zwei gleichnamige Karten sind zwei Vorfaelle) und
 * Faehigkeit (ueber {@link SpellAbilityView#getDescription()}, den unaufgeloesten Ability-Text wie
 * "Equip {0}" - der enthaelt KEIN gewaehltes Ziel, ein Umhaengen auf eine andere Kreatur zaehlt also
 * weiter zur selben Faehigkeit, wie es sein muss). {@link #onTurnBegan} setzt beide Zaehlungen bei
 * jedem Zugwechsel zurueck.
 *
 * <p><b>Zeilenbudget.</b> Ab {@link #THRESHOLD} gleichen Aktivierungen im selben Zug schreibt
 * {@link #onSpellAbilityCast} GENAU EINE Zeile ({@code CrashLog.note}, siehe dort - ein Hinweis,
 * kein Absturz, geht den Browser nichts an). Laeuft die Karte danach im selben Zug weiter, kommt
 * KEINE zweite Zeile je Aktivierung (das waere die Flut, die diese Mitschrift gerade vermeiden
 * soll) - stattdessen merkt sich {@link #reported} den Vorfall, und {@link #onTurnBegan} schreibt,
 * BEVOR es die Zaehler leert, fuer jeden gemeldeten Vorfall, dessen Endstand ueber dem gemeldeten
 * Stand liegt, eine zweite, abschliessende Zeile mit der Endzahl. Diese zweite Zeile ist die
 * Kennzahl, die Kevin wirklich braucht ("acht bis Alarm, aber dann bis zum Zugende auf 340
 * gelaufen" erzaehlt etwas anderes als "acht und dann von selbst aufgehoert") - und bleibt trotzdem
 * bei hoechstens zwei Zeilen je Karte und Zug, nie einer je Aktivierung.
 *
 * <p><b>Robustheit.</b> Wie {@code MatchRecorder}: beide {@code @Subscribe}-Methoden laufen unter
 * demselben Monitor und fangen jede {@code RuntimeException} - ein kaputter Zaehler darf die
 * laufende Partie nie stoeren, im Zweifel bleibt der Vorfall unentdeckt statt dass das Spiel
 * abstuerzt.
 */
public final class AbilityLoopWatch {

    /** Ab so vielen gleichen Aktivierungen derselben Karte im selben Zug gilt es als auffaellig -
     *  zweimal Umhaengen ist normal, acht Mal nicht (siehe Klassenkommentar). */
    public static final int THRESHOLD = 8;

    private final Game game;
    private final Map<PlayerView, Player> byView = new HashMap<>();

    /** Aktivierungen je Karte+Faehigkeit im laufenden Zug; geleert bei jedem {@link #onTurnBegan}. */
    private final Map<Key, Integer> counts = new HashMap<>();
    /** Vorfaelle, die im laufenden Zug schon eine Zeile bekommen haben (siehe Klassenkommentar
     *  "Zeilenbudget"); ebenfalls je Zug geleert. */
    private final Set<Key> reported = new HashSet<>();

    private int currentTurn;

    public AbilityLoopWatch(Game game) {
        this.game = game;
        for (Player p : game.getRegisteredPlayers()) {
            byView.put(p.getView(), p);
        }
        game.subscribeToEvents(this);
    }

    @Subscribe
    public synchronized void onTurnBegan(GameEventTurnBegan e) {
        try {
            closeOutstandingIncidents();
        } catch (RuntimeException ex) {
            System.err.println("[ability-loop-watch] Zugabschluss fehlgeschlagen: " + ex);
        }
        counts.clear();
        reported.clear();
        currentTurn = e.turnNumber();
    }

    @Subscribe
    public synchronized void onSpellAbilityCast(GameEventSpellAbilityCast e) {
        try {
            SpellAbilityView sa = e.sa();
            if (sa == null) {
                return;
            }
            CardView host = sa.getHostCard();
            String ability = sa.getDescription();
            if (host == null || ability == null || ability.isBlank()) {
                return;
            }
            Key key = new Key(host.getId(), ability, host.getName());
            int n = counts.merge(key, 1, Integer::sum);
            if (n >= THRESHOLD && reported.add(key)) {
                Player actor = e.si() == null ? null : byView.get(e.si().getActivatingPlayer());
                CrashLog.note("AbilityLoopWatch", host.getName() + ": " + n + "x dieselbe Faehigkeit ('"
                        + ability + "') in Zug " + currentTurn + " (" + phase() + ") - Sitz " + seatLabel(actor));
            }
        } catch (RuntimeException ex) {
            System.err.println("[ability-loop-watch] Zaehlung fehlgeschlagen: " + ex);
        }
    }

    /**
     * Abschliessende Zeile fuer jeden im GERADE ENDENDEN Zug gemeldeten Vorfall, dessen Endstand
     * ueber dem beim Alarm gemeldeten {@link #THRESHOLD} liegt - siehe Klassenkommentar
     * "Zeilenbudget". Laeuft VOR {@code counts.clear()}/{@code reported.clear()} in
     * {@link #onTurnBegan}, damit sie noch den Endstand des zu Ende gehenden Zuges sieht.
     */
    private void closeOutstandingIncidents() {
        for (Key key : reported) {
            int total = counts.getOrDefault(key, 0);
            if (total > THRESHOLD) {
                CrashLog.note("AbilityLoopWatch", key.cardName + ": Zug " + currentTurn
                        + " endete bei " + total + "x derselben Faehigkeit ('" + key.ability + "')");
            }
        }
    }

    private String phase() {
        PhaseType p = game.getPhaseHandler().getPhase();
        return p == null ? "unbekannt" : p.toString();
    }

    private static String seatLabel(Player p) {
        if (p == null) {
            return "unbekannt";
        }
        String mode = p.getLobbyPlayer() instanceof AiLobbyPlayer ai ? ai.config().mode().json()
                : p.getLobbyPlayer() instanceof LobbyPlayerHuman ? "human" : "unbekannt";
        return p.getName() + " (" + mode + ")";
    }

    /** Karte (per Id, nicht Name - siehe Klassenkommentar) + Faehigkeitstext als Zaehl-Schluessel;
     *  {@code cardName} haengt nur fuer die Log-Zeile in {@link #closeOutstandingIncidents} dran,
     *  fliesst nicht in Gleichheit/Hash ein. */
    private static final class Key {
        final int cardId;
        final String ability;
        final String cardName;

        Key(int cardId, String ability, String cardName) {
            this.cardId = cardId;
            this.ability = ability;
            this.cardName = cardName;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.cardId == cardId && k.ability.equals(ability);
        }

        @Override
        public int hashCode() {
            return 31 * cardId + ability.hashCode();
        }
    }
}
