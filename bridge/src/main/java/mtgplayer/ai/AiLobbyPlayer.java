package mtgplayer.ai;

import forge.ai.AiCache;
import forge.ai.AIOption;
import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.player.Player;

import java.util.Set;

/**
 * Forges KI-Sitz plus zwei Dinge, die die Bridge braucht:
 *
 * <ol>
 *   <li>Die {@link AiConfig}, mit der der Sitz gestartet wurde, bleibt am Spieler haengen
 *       ({@link #config()}). Forges {@code LobbyPlayerAi} kennt nur {@code AIOption}s und den
 *       Profilnamen, nicht unseren Modus-Begriff - {@link mtgplayer.stats.MatchRecorder} braucht ihn
 *       aber, um je Sitz {@code {"mode": …, "profile": …}} in den Partien-Datensatz zu schreiben.</li>
 *   <li>Der Speicher-Fix aus {@link AiConfig#newLobbyPlayer}: Forges statischer Memo-Cache
 *       {@code AiCache} haelt seine Eintraege (u. a. Game/Player-Argumente) per starker Referenz und
 *       wird sonst nur einmal pro Entscheidung des ORIGINALSPIELS geleert
 *       ({@code AiController.chooseSpellAbilityToPlay}). In der Voll-Simulation entstehen innerhalb
 *       EINER Entscheidung tausende Spielkopien ({@code GameSimulator}/{@code GameCopier}), die der
 *       Cache dann alle festhaelt - Live-Set-Spitze &gt; 12 GB, {@code OutOfMemoryError}. Der
 *       {@code Game}-Konstruktor ruft {@code createIngamePlayer(game, id)} fuer jede Kopie ueber
 *       dasselbe {@code LobbyPlayer}-Objekt auf ({@code GameCopier.clonePlayer} reicht es durch); wir
 *       leeren den Cache dort bei JEDER Kopie, unabhaengig von der id. Eine feste id (z. B. 0) waere
 *       kein verlaesslicher Marker fuer "das ist unser Sitz": {@code HostedMatch} sortiert den
 *       Menschen auf Platz 0, und {@code GameCopier.clonePlayer} ersetzt {@code LobbyPlayerHuman} in
 *       der Kopie durch eine FREMDE (Forges eigene) {@code LobbyPlayerAi} - unsere Unterklasse bleibt
 *       zwar erhalten, sitzt in der Kopie aber weiterhin auf ihrer id aus dem Originalspiel, also
 *       &gt;= 1, sobald ein Mensch am Tisch sitzt (id == 0 haette dort nie gefeuert). Der Cache ist
 *       global (static), das Leeren wirkt also fuer alle Sitze gleich; da der Konstruktor vor jeder
 *       Bewertung dieser Kopie laeuft, ist das unproblematisch. n Aufrufe je Kopie auf einer
 *       synchronisierten Map sind vernachlaessigbar und aendern keine Entscheidung (reines Memo) -
 *       siehe {@code .superpowers/sdd/sim-oom-investigation.md} (Lauf 4-7: Live-Set-Spitze faellt von
 *       &gt; 12 GB auf ~1,6 GB, Laufzeit unveraendert; gilt auch fuer menschliche Spiele mit
 *       Sim-KI).</li>
 * </ol>
 *
 * <p>Frueher war das eine anonyme Unterklasse in {@link AiConfig#newLobbyPlayer}; benannt, weil der
 * Recorder sie per {@code instanceof} wiederfinden muss.</p>
 */
public final class AiLobbyPlayer extends LobbyPlayerAi {

    private final AiConfig config;

    public AiLobbyPlayer(String name, Set<AIOption> options, AiConfig config) {
        super(name, options);
        this.config = config;
    }

    /** Die Einstellungen, mit denen dieser Sitz gestartet wurde - nie {@code null}. */
    public AiConfig config() {
        return config;
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        AiCache.clear();
        return super.createIngamePlayer(game, id);
    }
}
