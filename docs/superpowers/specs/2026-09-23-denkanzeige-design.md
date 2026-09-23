# Denk-Anzeige, Wachhund, Sim-Hinweis – Design

Stand: 2026-09-23. Kevins Befund: eine Zuschauer-Partie mit vier Sim-KIs stand am Umbris-Trigger „~5 Minuten"
still. Reproduktion (dieselben vier Decks, alle Sitze `sim`, Budget 5 s): Partie lief normal durch (38 Züge,
869 s), **keine** Ausnahme, aber sechs Stillstände über 20 s (Median 25 s, Maximum 31 s) an Triggern und in
Hauptphasen. Ursache ist Rechenzeit: jede Simulation kopiert das ganze Spiel inklusive Bibliotheken und Exil
(`GameCopier.ZONES`), je Kandidat einmal, begrenzt nur vom Budget je Entscheidung (bis ~2× Überschreitung).
Mit vier simulierenden Sitzen und einer Trigger-Kette summiert sich das auf Minuten – sichtbar passiert dabei
nichts.

Dieses Stück macht das Rechnen sichtbar, macht einen echten Hänger beweisbar und schreibt die Bedenkzeit in den
Partie-Datensatz. Es ändert **nichts** an der Spielstärke oder am Simulations-Code.

## 1. Denk-Anzeige (Bridge → Browser)

`mtgplayer.gui.ThinkingTicker` (neu): ein Daemon-Thread, der im Sekundentakt prüft, wie lange die letzte
Aktivität her ist. Als Aktivität zählt jede Log-Zeile (`WebGuiGame.sendLog`) und jeder Zustands-Push
(`WebGuiGame.pushState`) – beide melden dem Ticker `touch()`. Läuft ein Spiel und liegt die letzte Aktivität
länger als **3 s** zurück, schickt der Ticker einmal je Sekunde

```json
{ "type": "thinking", "player": 2, "seconds": 42 }
```

`player` ist der Sitz mit Priorität aus dem zuletzt gebauten Snapshot (sonst `null`). Sobald wieder etwas
passiert – oder das Spiel endet –, geht einmalig `{ "type": "thinking", "seconds": 0 }` raus. Der Ticker läuft
nur, solange ein Spiel läuft (Start in `setGameView`/`onNewGame`, Stopp in `finishGame`/`resetForNewMatch`).

Web: Store-Feld `thinking?: { player?: number; seconds: number }` (0 → gelöscht). Anzeige als ruhige Zeile mit
Punkt-Animation: in der Tischansicht über dem Prompt, im Zuschauer-Modus in der Fußzeile neben den Steuerknöpfen:
„**KI 2 denkt …** 42 s". Ab 60 s zusätzlich der Hinweis „Simulation in einer 4er-Runde kann Minuten dauern".
Ist kein Spielername bekannt: „KI denkt …".

## 2. Wachhund (nur Beweismittel, kein Eingriff)

Derselbe Ticker: überschreitet die Stille **120 s**, schreibt er **einmal je Vorfall** über `CrashLog.warn`
(kein Crash-Kanal, keine Fehlermeldung im Browser) nach `~/.mtg-player/logs/bridge.log`:

```
Wachhund: 120 s ohne Fortschritt – Priorität: KI 2, Phase: MAIN1, Zug 14
<Stacktrace des Spiel-Threads>
```

Den Spiel-Thread merkt sich `WebGuiGame` beim ersten Log-Ereignis (`Thread.currentThread()` im Log-Observer, der
auf dem Spiel-Thread läuft); fehlt er, wird der Stacktrace weggelassen und das im Text vermerkt. Nach einem
Vorfall wird erst wieder gemeldet, wenn zwischendurch Aktivität war. Damit ist beim nächsten Mal sofort
unterscheidbar: Stack in `GameSimulator`/`GameCopier` → die KI rechnet; Stack in `ChoiceBroker`/`CompletableFuture`
→ echter Hänger.

## 3. Hinweis in der Lobby

Sobald mehr als ein Sitz auf „Simulation" steht, erscheint unter der Bedenkzeit eine Zeile:
„**N Simulations-Sitze**: die KIs rechnen reihum – in einer 4er-Runde dauert eine Partie schnell 15–30 Minuten,
einzelne Züge über eine Minute. Weniger Sim-Sitze oder ein kleineres Budget machen es flüssiger."
Kein Zwang, keine Änderung der Voreinstellung.

## 4. Bedenkzeit im Datensatz

`MatchRecord` bekommt das Feld `aiTimeout` (Sekunden, `Integer`, `null` wenn unbekannt) direkt nach `source`;
`MatchRecorder` erhält ihn im Konstruktor, `HumanMatch`/`AiMatch` reichen den Wert durch, den sie ohnehin setzen.
Formatversion bleibt `v: 1` (rein additiv, alte Datensätze lesen sich weiter). Der Statistik-Screen zeigt ihn in
der Partienzeile als Chip „5 s" neben der Quelle.

## 5. Nachweise

- `ThinkingTickerTest`: ohne Aktivität kommen nach 3 s Meldungen mit wachsenden Sekunden; `touch()` beendet sie mit
  `seconds: 0`; nach 120 s genau **eine** Wachhund-Zeile (über `CrashLog.setFile` in ein Temp-Verzeichnis), danach
  erst wieder eine nach neuer Stille. Zeitquelle als injizierbarer `LongSupplier` (nanoTime), damit der Test nicht
  wartet.
- `MatchRecorderTest`: `aiTimeout` landet im Datensatz.
- Web: `store.test.ts` (Reducer `thinking`, Löschen bei 0), Screenshot der Tischansicht und des Zuschauer-Modus mit
  laufender Anzeige (Fixture + `mtgApply`), Screenshot des Lobby-Hinweises bei zwei Sim-Sitzen.
- Bestehende Suiten grün; Layout-Check der Tisch-Fixturen unverändert.

## Nicht in diesem Stück

Budget automatisch nach Sitzzahl, Simulation abbrechbar machen, Kopier-Optimierung in Forge (Bibliothek/Exil nur
als Zähler) – Letzteres wäre der eigentliche Hebel für Tempo, aber ein Fork-Eingriff mit Risiko für die Spielstärke.
