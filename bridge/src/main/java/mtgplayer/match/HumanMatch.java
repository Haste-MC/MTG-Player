package mtgplayer.match;

import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameRules;
import forge.game.GameView;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gui.interfaces.IGuiGame;
import forge.player.LobbyPlayerHuman;
import mtgplayer.gui.WebGuiGame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ein Commander-Spiel mit genau einem menschlichen Sitz (Browser) und 1–5 KIs über Forges
 * HostedMatch. startMatch kehrt sofort zurück; das Spiel läuft auf Forges Game-Thread und
 * spricht über die WebGuiGame.
 */
public final class HumanMatch {

    private volatile HostedMatch hosted;
    private volatile boolean lastGameOver = true;

    public void start(String humanName, Deck humanDeck, List<Deck> aiDecks, List<String> aiNames, WebGuiGame gui) {
        if (aiDecks.size() != aiNames.size() || aiDecks.isEmpty() || aiDecks.size() > 5) {
            throw new IllegalArgumentException("1–5 KI-Decks mit gleich vielen Namen");
        }
        end();
        List<RegisteredPlayer> players = new ArrayList<>();
        RegisteredPlayer human = RegisteredPlayer.forCommander(humanDeck);
        human.setPlayer(new LobbyPlayerHuman(humanName));
        players.add(human);
        for (int i = 0; i < aiDecks.size(); i++) {
            RegisteredPlayer rp = RegisteredPlayer.forCommander(aiDecks.get(i));
            rp.setPlayer(new LobbyPlayerAi(aiNames.get(i), null));
            players.add(rp);
        }
        Map<RegisteredPlayer, IGuiGame> guis = new HashMap<>();
        guis.put(human, gui);

        GameRules rules = CommanderRules.create();
        // Forge zeigt sonst beim Start eine Liste von Karten, die die KI nicht spielen kann – Rauschen für den menschlichen Sitz
        rules.setWarnAboutAICards(false);
        hosted = new HostedMatch();
        gui.resetForNewMatch(); // sonst haengt Auswahl/Prompt-Zustand aus dem vorigen Spiel noch dran
        hosted.startMatch(rules, null, players, guis, null);
    }

    /**
     * KI-only-Sitz: 2–6 Forge-KIs spielen gegeneinander, der Browser sieht als Zuschauer zu
     * (Forges eigener Spectator-Pfad, siehe {@code HostedMatch.startGame}: bei leerer guis-Map
     * ruft es {@code GuiBase.getInterface().getNewGuiGame()} und registriert das Ergebnis als
     * Zuschauer-Sitz – {@code WebGuiBase.setGuiSupplier} liefert dafuer dieselbe {@code gui}).
     */
    public void startSpectator(List<Deck> aiDecks, List<String> aiNames, WebGuiGame gui) {
        if (aiDecks.size() != aiNames.size() || aiDecks.size() < 2 || aiDecks.size() > 6) {
            throw new IllegalArgumentException("2–6 KI-Decks mit gleich vielen Namen");
        }
        end();
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < aiDecks.size(); i++) {
            RegisteredPlayer rp = RegisteredPlayer.forCommander(aiDecks.get(i));
            rp.setPlayer(new LobbyPlayerAi(aiNames.get(i), null));
            players.add(rp);
        }

        GameRules rules = CommanderRules.create();
        rules.setWarnAboutAICards(false);
        hosted = new HostedMatch();
        gui.resetForNewMatch(); // sonst haengt Auswahl/Prompt-Zustand aus dem vorigen Spiel noch dran
        hosted.startMatch(rules, null, players, Map.of(), null);
    }

    public boolean isRunning() {
        return hosted != null && hosted.getGame() != null && !hosted.getGame().isGameOver();
    }

    /**
     * Beendet das laufende Spiel wirklich, statt es nur aus der Buchhaltung von HostedMatch zu
     * loesen: {@code hosted.endCurrentGame()} allein setzt nur {@code hosted}s eigene Referenz auf
     * {@code null} - der Game-Thread selbst laeuft unbeeinflusst weiter (relevant z. B. beim
     * Beenden eines KI-only-Zuschauer-Spiels, das sonst als verwaister Thread im Hintergrund
     * weiterspielt und Events in die geteilte WebGuiGame feuert, die mit dem naechsten Spiel
     * kollidieren koennen). Beendet ueber Forges eigenen Weg fuer "kein Mensch mehr da, die KIs
     * koennen sich nicht selbst beenden" (siehe AbstractGuiGame#forceEndGameForRemainingAIs:
     * game.getAction().invoke(() -> game.setGameOver(GameEndReason.AllHumansLost))), wartet kurz
     * auf GameStage.GameOver und loest erst danach die Buchhaltung.
     *
     * <p>Bewusst {@code AllHumansLost} statt {@code Draw}: {@code Player.intentionalDraw()} setzt
     * einen expliziten "kein Sieger"-Outcome auf jeden Spieler. {@code Match.isMatchOver()} prueft
     * aber ausschliesslich, ob irgendein Spieler die Partie GEWONNEN hat - ohne Sieger bleibt die
     * Partie aus Sicht des Match "nicht vorbei", und Forges eigene Fortsetzungslogik
     * ({@code HostedMatch.startGame()}, {@code NextGameDecision.CONTINUE}) startet danach automatisch
     * ein neues Spiel im Hintergrund - genau der verwaiste Thread, der hier vermieden werden soll.
     * Ohne vorherige Spieler-Outcomes macht {@code Player.onGameOver()} jeden noch outcome-losen
     * Spieler automatisch zum Sieger, das reicht {@code Match.isMatchOver()} zum Abschluss.</p>
     *
     * <p><b>Deadlock-Falle (gefunden und gefixt):</b> {@code Game.isGameOver()} und
     * {@code Game.setGameOver(...)} sind beide {@code synchronized} auf dasselbe {@code Game}-
     * Objekt. {@code setGameOver(...)} haelt diesen Monitor waehrend es (ueber
     * {@code view.updateGameOver}/{@code fireEvent}) synchron auf den UI-Thread wartet
     * ({@code invokeInEdtAndWait}). Wuerde diese Methode hier {@code g.isGameOver()} pollen
     * UND selbst auf dem UI-Thread laufen (frueher: {@code Bridge}s Zuschauer-Concede-Zweig lief
     * ueber {@code ui(...)}), blockiert der UI-Thread auf dem Monitor, den der Game-Thread haelt,
     * waehrend der Game-Thread auf genau diesen UI-Thread wartet – klassischer Deadlock, den ein
     * dritter Thread (z. B. dieser Testlauf, der parallel {@code stop()} aufruft) noch verschaerfen
     * kann. Deshalb hier ausschliesslich {@link GameView#isGameOver()} lesen (ein einfacher,
     * synchronisationsfreier Trackable-Feldzugriff, den {@code Game.setGameOver} bereits VOR dem
     * Event-Versand setzt) statt {@code Game.isGameOver()} – und {@code Bridge} ruft {@code end()}
     * fuer den Zuschauer-Fall nicht mehr auf dem UI-Thread auf (siehe Bridge.handle, "concede").</p>
     *
     * <p><b>Zweite Falle (gefunden und gefixt):</b> concede fuer Zuschauer laeuft als eigener
     * Hintergrund-Task pro Nachricht ({@code runBackgroundTask} startet jedes Mal einen neuen
     * Thread) - ohne Schutz konnten zwei ueberlappende {@code end()}-Aufrufe (z. B. dieser hier und
     * {@code Bridge.stop()} vom Testthread) beide den alten {@code if (hosted != null)}-Check
     * bestehen, bevor einer von beiden {@code hosted} auf {@code null} setzte; der zweite griff dann
     * auf das inzwischen genullte Feld zu (NullPointerException in {@code hosted.endCurrentGame()}).
     * Deshalb {@code hosted} zuerst atomar "beanspruchen" (unter {@code synchronized(this)} lesen
     * und sofort auf {@code null} setzen, das dauert nur Mikrosekunden) und erst DANACH - ausserhalb
     * des Locks, damit ein zweiter Aufrufer nicht bis zu 5 s blockiert - mit der lokalen Kopie
     * weiterarbeiten; ein zweiter, ueberlappender Aufruf sieht {@code hosted} bereits als
     * {@code null} und kehrt sofort zurueck.</p>
     */
    public void end() {
        HostedMatch h;
        synchronized (this) {
            h = hosted;
            hosted = null;
        }
        if (h == null) {
            return;
        }
        Game g = h.getGame();
        GameView view = h.getGameView();
        if (g != null && view != null && !view.isGameOver()) {
            g.getAction().invoke(() -> g.setGameOver(GameEndReason.AllHumansLost));
            long deadline = System.currentTimeMillis() + 5000;
            while (!view.isGameOver() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!view.isGameOver()) {
                System.err.println("[HumanMatch] end(): Spiel wurde nicht innerhalb von 5 s beendet (GameView.isGameOver() weiterhin false)");
            }
        }
        lastGameOver = view == null || view.isGameOver();
        h.endCurrentGame();
    }

    /** Fuer Tests (auch ausserhalb dieses Package, siehe Bridge#match): ob das zuletzt per
     *  {@link #end()} beendete Spiel wirklich GameStage.GameOver erreicht hat, statt als
     *  verwaister Game-Thread im Hintergrund weiterzulaufen. */
    public boolean lastGameOver() {
        return lastGameOver;
    }
}
