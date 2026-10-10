# MTG-Player

Local Commander table with AI opponents, built on Forge, with its own browser UI.
Design: `docs/superpowers/specs/2026-09-16-mtg-player-design.md`.

Note on language: the documentation is English, and so is the table vocabulary in the app. The surrounding
UI (lobby, statistics board) is still German, so German button labels in this README are quoted the way the
app shows them.

## Download and start

No installation, no Java, no console: the ZIP sits under the **Assets** of the latest release at
<https://github.com/Haste-MC/MTG-Player/releases/latest> (for example `MTG-Player-1.2.0-win.zip`). Unpack it
into its own empty folder (not straight into "Downloads" — the self-update later replaces exactly that
folder) and double-click `MTG-Player.exe` inside it.

On the very first start Windows warns with "Windows protected your PC" (SmartScreen, because the `.exe` is
not signed — the only remedy is a bought certificate, which this project does not have). Click "More info",
then "Run anyway". This appears only on the first start of a given version.

The first start takes noticeably longer than every later one: Forge reads about 30,000 cards. A loading
notice in the window shows that the app is working — just wait.

Your own data (decks, matches, card records) lives apart from the program folder under
`%USERPROFILE%\.mtg-player` and survives every update as well as deleting and re-unpacking the program
folder. Uninstalling means deleting the program folder (your own data is untouched by that and has to be
deleted separately if you want it gone).

## Requirements

- Java 17, Maven ≥ 3.8.1 (`sudo apt install openjdk-17-jdk-headless maven`)
- Node ≥ 20 (for the frontend, from M2 on)

## One-time: build Forge

```bash
git submodule update --init --depth 1   # fork Haste-MC/forge, branch mtg-player-2.0.15 (base: tag forge-2.0.15)
cd forge && mvn -q -pl forge-gui -am install -DskipTests -Dcheckstyle.skip -Dmaven.javadoc.skip=true   # installs 2.0.15-mtgplayer into ~/.m2
```

## Playing

Build the frontend once, then start the bridge:

```bash
cd web && npm install && npm run build && cd ..
cd bridge && mvn -q compile exec:java
```

Browser: <http://127.0.0.1:8080>. Lobby → clicking a deck tile per seat (commander art) opens a panel with
the tabs „Precons" / „Eigene Decks" / „Import" and a search box (deck or commander name); 1–5 AI opponents,
optionally a series (best of 3/5/7, off otherwise) → start the game. The last choice per seat is remembered
in the browser (even after „Zur Lobby").
Controls: glowing cards are clickable, right-click = another ability, Enter/Space = OK, Esc = cancel. Forge
passes automatically when there is nothing you can do (Arena style).

Your own decks: under „Import" in the panel, paste an Archidekt or Arena export (`1 Sol Ring (c21) 263`,
section `Commander` or the first legendary creature as the commander). The deck is saved under
`~/.mtg-player/decks/` and then appears under „Eigene Decks". Unknown cards are reported with their line and
the game does not start.

Archidekt: paste a deck URL (or just the id) — public decks; the deck name is taken over. The Archidekt id
sits as a tag (`archidekt:<id>`) in the saved `.dck` file; that gives the deck tile under „Eigene Decks" a
„↻ Resync" button which reloads the deck from Archidekt (after changes to the list there, for example)
without importing again.

Archidekt account: the „Archidekt" tab in the deck panel fetches the account's public Commander decks by
user name (Enter or „Decks laden", the name is remembered in the browser) — public and unlisted decks only,
Archidekt does not show private ones without a login. Every tile carries a mark: **neu** (not imported yet),
**aktuell** (the saved state matches Archidekt) or **geändert** (edited on Archidekt since); the changed
ones are checked in advance. „Ausgewählte holen (n)" imports or updates the checked decks one after another
(new ones under their Archidekt name, known ones under the saved name), „Alle aktualisieren" takes every
already imported deck of the account. During the run the footer shows „3/7 · deck name …", afterwards „7/7
fertig" and, in red, the decks that could not be loaded — the run continues past failures. Two tags in the
`.dck` file carry this: `archidekt:<id>` and `archidekt-updated:<state>` (Archidekt's `updatedAt` at import
time). Decks imported before this feature existed (only the `archidekt:` tag) show up once as **geändert**
and are checked in advance; after the first update they carry the state. A plain „↻ Resync" under „Eigene
Decks" now writes `archidekt-updated` as well. If a local deck without an Archidekt tag (an earlier text
import, say) has the same name as a deck about to be imported, the tile shows the mark **übernehmen**: the
import replaces that deck's contents and adds the tags — not checked in advance, not even under „Alle
aktualisieren". If the deck of the same name carries the tag of a *different* Archidekt deck, the import
saves under "<name> (<id>)" — nothing is overwritten.

Deleting: every tile under „Eigene Decks" has a trash button; the first click turns it into „Wirklich
löschen?" (gone again after 4 s or a click elsewhere), the second deletes the file. Only your own decks,
never a precon. If the deck was chosen in a seat, that seat is empty afterwards.

End of a game: besides „Zur Lobby" the dialog offers „Nochmal spielen" — it restarts the same deck and AI
constellation directly, without the detour through the lobby. During a series (lobby setting „Serie") the
dialog shows the score (for example „Du 2 · KI 1 1") and the button becomes „Neue Serie" as soon as someone
has the required wins.

If a game of the series is still outstanding, it starts **by itself**: the dialog counts down „Spiel 3 von 5
startet in 5 …" and then sends the same `startGame` as „Nochmal spielen". While the countdown runs, two
buttons stand in place of „Nochmal spielen": **„Jetzt starten"** (skips the rest of the countdown) and
**„Serie beenden"** (stops the auto-start without discarding the score — only „Zur Lobby" remains
afterwards). If the bridge rejects the start, its error message replaces the countdown and nothing is
retried by itself. A match that does not count (abort, crash) does not pull a new game after it.

Card images come from Scryfall and are cached under `~/.mtg-player/cache/images/` (the first game with new
cards loads for a few seconds; without internet they stay text boxes). Tokens take their image from
Scryfall's token set for the edition (`tc21`, for example); tokens without an edition entry stay text boxes.

Phase bar above the prompt: clicking a phase sets or clears a stop — the game halts there as long as you can
play something; without full control Forge still passes automatically when nothing is playable (kept
separately for your own and your opponents' turns, depending on whose turn it is). "Full control" halts in
every phase and switches the automatic passing off. Tutor effects (searching the library) open a list dialog
with card details.
Stale clicks (the prompt has moved on since) are ignored by the bridge.

Frontend development: `cd web && npm run dev` (Vite on :5173, connects to the bridge on :8081).
`npm run shot -- http://127.0.0.1:8080 out.png fixtures/table.json` produces a Playwright screenshot (fixture
state, `?debug=1` is appended automatically).

Log at the bottom right: category chips show and hide lines (mana and phase are hidden by default).
Players that can currently be targeted get a dashed frame around their header area.

Identical lands and tokens lie as a stack (up to four visible layers, ×N); tapped cards of the stack lie
rotated underneath. A click taps the first untapped one. As soon as a card of the stack becomes selectable,
enters combat or carries counters, all of them are shown individually. Tapped cards rotate in your own zone
and in the spectator panels; only the compact opponent rows of the table view show them dimmed with ⟳.
Forge's effect helper cards and emblems appear as chips in the "Effects" row (opponents: in the panel
header), hovering shows their text in the detail panel on the right.
Spectator panels and your own zone: creatures on top, other permanents in the middle, lands at the bottom;
the card size follows the panel size.
Attached auras and equipment lie behind their host and peek out at the top (to the side in the opponent row);
every edge is hoverable and clickable. Curses on players appear as an "Aura by" chip in the enchanted
player's panel header.
The graveyard and exile list opens towards the middle of the screen.
Alternative bridge ports can be set via the URL: `?wsPort=8082` (same host) or `?ws=ws://host:port` (your own
WebSocket URL).

**Only one port needed.** Normally the connection runs over the WebSocket (8081). If a browser cannot open
it — some embedded browsers allow only the host and port of the page itself — the app switches by itself to
two ordinary HTTP routes **on the page's port**: `GET /ereignisse` (event stream) and `POST /eingabe`. You
notice nothing except that it works. The WebSocket stays the first choice; the switch happens only once it
fails to open and the bridge has finished starting up.

**Several browsers at the same table.** Any number of windows can open the same bridge and see the same
state — handy for watching someone (or a Claude session) play. Only **one** of them may click: whoever
connected first. All the others see a banner at the top, "You are watching", with the button **Take
control**; whoever presses it takes control, and the previous controller becomes a spectator. If the
controller leaves the table, the spectator who has waited longest takes over.

## Team modes

Every seat can carry a team number (lobby: the select next to each deck, or the "2v2" quick pick for exactly
four seats). Teammates are not opponents — they cannot attack each other, "each opponent" effects skip them,
and the match ends when only one team is left; both seats of that team count as winners. A team of one seat
is a free-for-all seat, so 1v2 works, but at least one team needs two seats. Your partner is an AI like the
others; it will not attack you, but it does not actively help either (Forge's AI barely knows teammates).

The checkbox „Partnerhand zeigen" reveals your partner's hand to you. Team matches are recorded with their
line-up (shown as a "2v2" or "1v2" chip in the match list) and get their own format switch **Team** in the
statistics board, so their numbers stay apart from free-for-all pods: a team match is neither a 1 vs 1 nor
a pod, whatever its seat count. The rule is the same one the bridge uses — a match counts as a team match
when at least two seats share a team number; records from before team modes have no team field and stay
duels and pods. Sparring stays 1 vs 1.

## Card data sync

The workflow `.github/workflows/kartendaten.yml` syncs Forge's card data with upstream every week and files
the result as a pull request in `Haste-MC/forge`.

For that it needs a secret `FORK_TOKEN` in this repository: a fine-grained personal access token on
`Haste-MC/forge` with **Contents: Read and write** and **Pull requests: Read and write**. Without the token
even pushing to the fork fails and the exclusion list is not carried forward; sync and check still run, and
the report is attached to the run as an artifact.

Order of operations: first merge the pull request in the fork, then start
`.github/workflows/submodul.yml`, and only then sync again — otherwise the next sync builds on a stale
submodule pointer. `submodul.yml` opens a pull request in the main repository; for that, "Allow GitHub
Actions to create and approve pull requests" has to be enabled in its settings, otherwise the workflow
leaves a branch without a pull request behind.

## Bridge

```bash
cd bridge
mvn -q test                                # all tests incl. AI game and end-to-end over WebSocket (minutes)
mvn -q compile exec:java                   # bridge server (WebSocket 8081, HTTP 8080)
mvn -q compile exec:java -Dexec.args="--ai-demo 42"   # headless AI game as in M1
mvn -q compile exec:java -Dexec.args="--bench"         # N games AI vs. AI, see the "Bench" section
```

Ports: `-Dmtgplayer.wsPort=…`, `-Dmtgplayer.httpPort=…`; bind address `-Dmtgplayer.bind=…` (default
`0.0.0.0`, so that Windows can reach it via localhost under WSL2); frontend directory: `-Dmtgplayer.web=…`
(default `../web/dist`).
`ForgeBoot.init()` writes `bridge/assets/forge.profile.properties` on every start (generated, git-ignored)
and thereby points Forge's user data at `~/.mtg-player/`; `bridge/assets/res` is a symlink to
`forge/forge-gui/res`.
The assets path can be overridden with `-Dmtgplayer.assets=<dir>`; Maven sets it automatically for `test` and
`exec:java`.
`mvn test` writes the log, `matches.json`, bench output and the Forge profile of all tests to
`bridge/target/test-data` instead of `~/.mtg-player/`; `-Dmtgplayer.data=<dir>` redirects that storage for
your own runs too (`--bench`, for example).

Protocol (WebSocket, JSON, field `type`, details in
`docs/superpowers/specs/2026-09-16-mtg-player-design.md`): the `lobby` message lists precons and your own
decks as `DeckInfo` (name, commander with image, and for Archidekt imports the `archidekt` id); the client
message `resyncDeck` (deck name) reloads such a deck from Archidekt and the bridge answers with a fresh
`lobby` or with an `error` message; `deleteDeck` (deck name) deletes one of your own decks (never a precon)
and likewise answers with `lobby` or `error`.
`archidektList` (user name) returns `archidektDecks` (id, name, `updatedAt`, preview image per public
Commander deck of the account) or `error`; `archidektImport` (ids) imports or updates the decks one after
another and reports `archidektProgress` per deck (`done`, `total`, `current` = saved name of the running deck
or `Deck <id>` for a new import — the client shows the name from the account list for that, `errors`), a
fresh `lobby` message after each deck and finally `archidektProgress` with `current: null`; a second
`archidektImport` during a run is rejected with `error`.
`thinking` (`player` = seat with priority or `null`, `seconds`) reports once per second, starting after 3 s
without visible progress, that computation is happening; `seconds: 0` clears the display again (it arrives
once, as soon as things move on or the game ends). While the bridge waits for input **from you**, it stays
quiet — your own thinking time is not computation.

## Statistics

Every finished match (your own game, spectator mode and sparring) is recorded: per seat the deck name, winner
or reason for the loss, mulligans, lands per turn and missed land drops, spells and total mana, commander
casts including tax and the turn of the first commander, damage dealt and taken as well as life and poison at
the end; plus duration, turns and the source of the match. The records live as a JSON array in
`~/.mtg-player/matches.json` (newest last, capped at 2000 matches, written atomically); a broken file is
replaced on the next write instead of blocking the start.

Since round B there are four groups of **incident metrics** per seat: **spells and cards** — your countered
and fizzled spells, counterspells you cast yourself, cards drawn, discarded and milled; **board and losses**
— permanents lost (creatures in combat and outside combat among them), the largest sweep in one resolution
window plus the number of such windows, and tokens created; **combat and damage** — your attacks and attack
turns, opposing attackers and your own blocks, combat damage to the seat split by flying, trample and other
sources, non-combat damage, damage dealt in and outside combat, commander damage and life gained;
**timeline** — per own turn (capped at 60 points) lands, creatures, life, hand cards and the spells cast in
that turn segment, as the basis for later curves such as "when did you fall behind". In addition, per seat
the hand cards at the time of elimination ("died with a full hand"), the lands of the opening hand kept after
the mulligans and the number of removal spells cast. These metrics are only collected **from now on** (format
version `v: 2`) — older matches do not have them and read as **no data** for the evaluation, not as 0.

Three of them are approximations that are named differently from what they count: "discarded cards" is any
path from hand to graveyard (including as a cost); **infect damage sits in the damage taken but costs no
life** — whoever reasons about life has to use the life at the end, not the damage; and commander damage is
the sum over all opposing commanders together, so the 21-point rule cannot be derived from it. Removal cast
counts the intent, not the success.

Not every match should feed the evaluation. Automatically **not counted** are matches under three turns ("zu
kurz"), matches with a concession ("aufgegeben"), matches cut off at the turn cap ("Zugdeckel"), aborted
matches ("abgebrochen": the match was aborted via „Aufgeben"/„Beenden" or a restart) and crashed matches
("Absturz") — they stand in the list with their reason but do not count towards the metrics. For
"abgebrochen" and "Absturz" no seat counts as the winner, so marking them as counted afterwards cannot invent
wins.

"Zugdeckel" is the emergency brake of the headless matches (bench, sparring): after the maximum number of
turns the bridge sets all seats to an agreed draw and ends the game, otherwise a stuck AI match would run
forever. Technically that is a draw, but nobody actually agreed to it — the match was cut off, so it does not
count. The real outcome (draw, no winner) still stays in the record. How often a deck runs into it is a
metric of its own and stands as its own tile „Partien am Zugdeckel" (share of this deck's counted plus
turn-cap matches, with „n von m" underneath): a deck that regularly hits the cap has no reliable win
condition.

The „Statistik" button in the lobby opens the **statistics board** across the full window width. The header
holds three format switches — **Alle / 1 vs 1 / Pod (3+)**, each with the number of counted matches — and the
switch **„Erklärungen"** (on): it shows the sentence under every block and every tile that says what the
number means and what it is computed over. The format separates the evaluation throughout, because the same
number means something different in a pod than in a duel (there you lose most of the time, and damage spreads
over three opponents). On the left the decks with commander art, record and — where present — their turn-cap
matches: **all of your own (saved) decks** plus every deck that has counted or turn-cap matches. First the
decks with matches (by match count), then the rest of your own alphabetically — so a freshly imported deck
stands in the list without a single match, and that is exactly where you start its first sparring. A deck
that only appeared as an **opponent** (not your own, a precon for example) stays in the list with its
matches, says so in the tooltip and gets no sparring button.

On the right, for the selected deck in the selected format:

* **„Auffälligkeiten"** — the tiles translated into sentences: mana screw, flood, mulligans, sweepers
  suffered, fliers, countered spells, turn cap and (in the pod only) "out early". Every rule names the number
  **and the sample**, keeps to a minimum sample size and only speaks up **above** its threshold. Rules that
  need the deck contents ("and you have nothing against it") stay silent without a deck analysis. Card
  suggestions are not part of it yet — they come in the next piece.
* **Five metric blocks** — record, mana & start, tempo & commander, combat & survival, interaction & losses.
  Tiles without a basis show "–" instead of a 0 and say in the explanation why (for example "no match ran
  that long"). Tiles from the incident data name their own sample: "computed over 7 of 12 matches of the
  selection" — and where the computation is narrower still (mana screw only counts matches with a third own
  turn), exactly that number is there.
* **Deck** — the deck analysis from the card database, without matches: cards, lands (basics among them),
  average mana value, color identity, mana curve, color sources and "cards per job" (ramp, card draw,
  removal, sweepers, counters, anti-flier, protection from sweepers, recursion, tutors). The classification
  reads card texts with patterns — orders of magnitude, not truth.
* **Gegner** — which decks were played against how often, and won against.

Below that the match list. Per row the „gewertet" checkbox puts a match back into the evaluation or out of it,
the trash can deletes it (two clicks, as with deleting a deck); the filter „nur gewertete" is on by default,
and the format switch applies here too. The arrow on the left expands the match and loads its **timeline**
(two small curves per seat: life above, lands and creatures below, per own turn) — the list itself does not
carry it.

The evaluation is **per deck**: who played the deck — you or an AI — does not matter, so that the sparring
matches (AI plays your deck) count; per match at most one seat counts per deck, so a mirror stays one match.

Per match the record also holds the configured AI thinking time (`aiTimeout`, seconds per decision); in the
match list it stands as a small field "<n> s" next to the source. It belongs to any comparison of durations —
a long match may be down to the high thinking time rather than the deck. Older records do not know the value,
and there the field is missing.

Protocol: on connect, after every change and after every game end the bridge sends `matches` — the **last
300** records (oldest first), each **without the timeline**, plus `total` with the number of matches actually
stored; if there are more matches than were sent, the board's header says so. The client sends `deleteMatch`
(`id`) and `setMatchCounted` (`id`, `counted`) — both answer with a fresh `matches` list — as well as two
follow-up questions: `matchDetail` (`id`) fetches **one** match in full including the timeline (answer
`match`), `analyzeDeck` (`deck`) the deck analysis from the card database (answer `deckAnalysis`). Both are
asked only on demand (expanded row, selected deck) and not again while the answer is already there or on its
way. Errors arrive as `error` („Partie <id>: …", „Deckanalyse <name>: …") and stand in the screen above the
list; the expanded row or the deck panel then says that nothing was loaded instead of showing „wird geladen
…" forever.

## Sparring

Sparring plays a saved deck **in the background** against your other decks, so that the statistics have
something to say before weeks have passed: in the statistics board, pick 5, 10, 20 or 50 matches above the
tiles and press „Sparring starten". Every match is a **1 vs 1** (pod comes later), and every finished match
lands in the list as a record with the source **„Sparring"** right away — so the board keeps recomputing
during the run.

Play is **always with the standard AI**, without a choice: it is fast and consistent, and what is to be
measured is the deck, not the AI. A simulation seat needs up to the full thinking time per decision and turns
a run of 20 matches into hours instead of minutes. It may come as an option later.

The **opponents** are drawn at random (with replacement) from the same **bracket**: that way rare pairings get
tested over many matches too. If there are fewer than three other decks in your own bracket, it widens to
bracket ±1; if there is still no opponent then, the bridge rejects the start („keine Gegner im Bracket 3:
Bracket setzen oder Decks importieren"). A deck without a bracket plays against the decks without a bracket.
Underneath the button it says what is being drawn from („zufällig aus Bracket 3: 7 Decks").

A deck **without** a single played match can be sparred just the same: it stands at the bottom of the deck
list, on the right „Noch keine Partie" stands in place of the tiles, and the button above does its work.

The **bracket** (Commander bracket 1–5) comes from Archidekt on import (`edhBracket`) and can be changed by
hand: in the deck panel under „Eigene Decks" every tile carries a small mark at the top left („B3", „B ?"),
and a click opens the choice 1–5 / „unbekannt". Precons have no bracket.

During the run a progress line („3/20 · gegen Koma, World-Eater …") with **„Abbrechen"** stands in place of
the selection; cancelling ends the running match immediately (its child process is killed) and lets no
further one start. Failed matches stand under it in red (with the line from stderr that looks like the cause
— the last exception, not the last stack frame), count along and do **not end the run** — every match runs in
its **own JVM child process**, so that a Forge crash takes down neither the run nor the bridge you have
running alongside. Only **one** sparring runs at a time; a second start reports „Sparring läuft noch".

Matches that run into the **turn cap** (60 turns by default) count as "Zugdeckel" like everywhere else and do
**not** feed the record — they stand in the list with their reason (see "Statistics"). Output of the child
processes: `~/.mtg-player/sparring/sparring-<timestamp>-<no>-<opponent>.log` (stderr) and `.out.log` (Forge's
game log).

Protocol: `sparringStart` (`deck`, `games`, optional `ai`, `timeout`, `maxTurns`) starts the run,
`sparringCancel` aborts it. The bridge reports `sparringProgress` (`done`, `total`, `current`, `errors`,
`running`) once at the beginning and after every game end — the last report of a run carries
`running: false`. The bracket is set by `setDeckBracket` (`name`, `bracket` 1–5 or `null`); the answer is a
fresh `lobby` message.

Next up: a weakness analysis with card suggestions out of the metrics filled this way.

## When the table freezes

If a game freezes (no prompt any more, the log stands still), usually a thread died with an exception. The
bridge writes every such abort with its stack trace to `~/.mtg-player/logs/bridge.log` and as a red line into
the browser log („Spiel abgebrochen …"). For a bug report the last block from the file is enough.

That holds for **all three** ways a match can die: the game thread, the UI thread (`uncaught in bridge-ui` —
Forge's entire state processing including the game end runs over it) and a subscriber on the event bus
(`eventbus` — Guava swallows the exception so that the bus does not break). The last two only reached the
terminal until 2026-10-07; two incidents from 2026-10-06 could not be cleared up because of that.

If nothing is there, the AI is probably just computing. A seat in simulation mode needs up to the full
thinking time per decision, and measured, **four simulation seats cost 20–31 s per decision** — nothing
reaches the browser in that time. That is why "… is thinking" appears in the prompt bar after 3 s of silence
(spectator mode: in the footer); as long as the seconds keep counting up there, everything is fine.

If the table still stands still, the watchdog helps: after 120 s of silence the bridge writes a thread dump
to the log **once per incident** (only there, not in the browser):

```bash
grep -A60 Wachhund ~/.mtg-player/logs/bridge.log | tail -80
```

The dump holds the remembered game thread, `bridge-ui`, Forge's `Game-*` pool and `bridge-bg` — not just one
of them, because in the incidents of 2026-10-06 it was precisely the game thread that was already dead, and a
dead thread has no stack. What the dump tells you:

* `GameSimulator` / `GameCopier` (also `SpellAbilityPicker`, `AiController`) — the AI is computing. Not a
  fault, at most a reason to lower the thinking time or to set fewer simulation seats.
* `ChoiceBroker` / `CompletableFuture.get` — a **real hang**: the game thread waits for an answer that never
  comes (lost question, broken connection). That belongs in the bug report.
* `TERMINATED` for the game thread — the match is not computing any more. Then the rest of the dump says who
  is holding it; if `bridge-ui` hangs in a `send` or a `get`, it is the culprit.
* `VERKLEMMUNG erkannt zwischen:` as the first line — the JVM found a deadlock and names both threads and the
  lock. No further interpretation needed.
* `WARNING: GameView reports the game as OVER` in the header line — the match is over and the bridge did not
  notice (`finishGame()` is missing). Not a hang, a lost game end.

While the bridge waits for *your* input, the display and the watchdog rest — otherwise two minutes of
thinking would put a `ChoiceBroker` stack in the log, which is exactly the signature of the real hang.

## Forge fork

Since AI package level 2 the bridge runs against a fork of Forge (`Haste-MC/forge`, branch `mtg-player`,
Maven version `2.0.15-mtgplayer`, so that the fork build does not overwrite the original in `~/.m2`). In the
submodule `origin` is the fork and `upstream` is Card-Forge; new Forge versions come onto the branch via
`git fetch upstream && git merge forge-<version>`. After every change to Forge: run the `mvn install` above
again, then rebuild the bridge.
What the fork changes is listed commit by commit in `docs/forge-fork.md`: time budget for the full simulation
(the AI thinking time applies to all AI modes), `GameCopier` made robust against the monarch effect card,
`<Nothing>` combat placeholders, remembered tokens, eliminated players, reported cards and goad; candidates
sorted by usefulness; the AI works towards its own meld conditions and refuses goad counters that would lead
into a lost mandatory attack; mulligan with a color and curve check (`MULLIGAN_CHECK_COLORS`, comparison
profile `Legacy` = the old behavior); land weight in the simulation evaluation; debug flag
`-Dforge.ai.sim.debug`.

## Bench (AI vs. AI)

Level 4: the mulligan checks colors and curve (fork property `MULLIGAN_CHECK_COLORS`, comparison profile
`Legacy`) → Abzan mirror 28:12, Ahoy 24:16 against the old behavior, of which ~5–8 points of effect remain
after controlling with identical seeds:
[docs/bench/2026-09-20-stufe-4-mulligan.md](docs/bench/2026-09-20-stufe-4-mulligan.md).

Level 4: land weight in the evaluation → Ahoy mirror 30:10 = 75 % [60–86] (28:12 before); the other three
evaluation steps were neutral and have been reverted:
[docs/bench/2026-09-20-stufe-4-bewertung.md](docs/bench/2026-09-20-stufe-4-bewertung.md).

Level 3: candidates sorted under the budget → 70 % [55–82] in the mirror; a 30 s budget brings nothing (59 %):
[docs/bench/2026-09-20-stufe-3-kandidaten.md](docs/bench/2026-09-20-stufe-3-kandidaten.md).

First measurement run and its interpretation:
[docs/bench/2026-09-20-sim-vs-standard.md](docs/bench/2026-09-20-sim-vs-standard.md) (the simulation AI wins
the mirror match at ~76 %, but 28 % of the games end in a timeout or crash). Level 2 evidence for the two
fixes: [docs/bench/2026-09-20-stufe-2-zeitbudget.md](docs/bench/2026-09-20-stufe-2-zeitbudget.md) (0 timeouts
instead of 9) and [docs/bench/2026-09-20-stufe-2-copier.md](docs/bench/2026-09-20-stufe-2-copier.md) (0
crashes on monarch/`<Nothing>`; win rate statistically unchanged against the baseline run, different
population).

Measures whether one AI setting beats another: N games one on one, headless, with a seed. Without a time
budget (`--timeout 0`) a sim seat is as reproducible per game index as a standard seat; with a time budget the
deadline of every simulation decision hangs on the wall clock, not on the seed — a repeat run on another
machine or under load can cut off different candidates and thereby pick a different play. From a certain
board complexity on, bench runs with a time budget are to be read as a distribution (several seeds,
confidence interval), not as a repeatable single-game result.

```bash
cd bridge && mvn -q compile exec:java -Dexec.args="--bench --games 40 --a sim:Default --b std:Default --deck-a 'precon:Abzan Armor [TDC] [2025]' --deck-b 'precon:Adaptive Enchantment [C18] [2018]' --seed 1"
```

Precon names with spaces have to be quoted inside `-Dexec.args` (Maven splits at the space otherwise).

| Option | Meaning | Default |
|---|---|---|
| `--games N` | number of games | 40 |
| `--a spec` / `--b spec` | AI seats, `AiConfig.parse`, e.g. `sim:Reckless`, `std`, `hybrid:Cautious` | `sim:Default` / `std:Default` |
| `--deck-a ref` / `--deck-b ref` | deck: `precon:<name>` or `saved:<name>` | the first two precons alphabetically |
| `--turns N` | turn cap (player turns) → draw | 200 |
| `--timeout s` | AI thinking time in seconds; a guide value per decision, may be exceeded by up to about double (applies to the simulation as well) | 5 |
| `--seed n` | base seed; game i uses `seed + i` | current time |
| `--out dir` | output directory | `~/.mtg-player/bench/` |
| `--game-timeout min` | time limit per game in the child process (then `destroyForcibly`, the game counts as a crash) | 30 |
| `--in-process` | every game in the calling thread instead of its own JVM child process (flag, no value) | off |

Output: `<out>/<yyyy-MM-dd-HHmmss>-<a>-vs-<b>.md` (table, win rate with a 95 % Wilson interval, parameters,
seed, one line per game, column „Nichtstun" = seats with ≥ 5 lands played and ≤ 2 spells cast, counted from
the Forge log lines `<seat> played …`/`<seat> cast …`; total line `Nichtstun A x / B y`) and the `.json` of
the same name with all individual games (`fewSpells` per game, `fewSpellsA/B` in the summary). After every
game a progress line on stdout. Runs for minutes to hours; Ctrl-C writes the intermediate state.

**One JVM child process per game.** A Forge-internal crash during the simulation (for example `GameCopier`
"Couldn't map \<Nothing\>", triggered when `Combat.removeFromCombat` creates a placeholder card unknown to the
copier as an attacked planeswalker or battle leaves) poisons not just that one game but unknown global state
for the rest of the JVM — later games then crash one after another or "end" after seconds without a real
turn. That is why every bench game runs in a fresh `java` child process by default (`mtgplayer.Main
--bench-one <i> <the same bench options>`, with a fresh heap as a side effect): the child's stdout delivers
the line `BENCH_RESULT <json>`, its stderr goes to `<out>/game-<i>.log`. No result, an exit code ≠ 0 or
`--game-timeout` running out count as a crash of that one game, not as an abort of the whole run; Ctrl-C ends
a still running child process too. `--in-process` switches back to the old behavior (one thread, no
isolation) — for quick local tests, when a Forge crash is no risk (a single game, say, or decks that are
known to run stably).

`sim` (`USE_FULL_SIMULATION`) simulates attacks, blocks and targets ahead and is correspondingly expensive;
memory use is no longer critical (`AiConfig.newLobbyPlayer` clears Forge's `AiCache` after every simulation
copy, see `.superpowers/sdd/sim-oom-investigation.md` — without the fix the heap grew without bound per
decision; this also applies to human games with a sim AI, not just to the bench). `--timeout` applies to the
simulation too: the fork gives every spell-choice decision a time budget of `--timeout` seconds
(`SimulationController` deadline, see `docs/forge-fork.md`) — once it runs out, no further candidates,
targets, modes or deeper levels are evaluated, but at least one candidate is always computed through and the
result is the best play found so far. Without a budget (upstream Forge) a single decision in the bench hung
for over 30 minutes. `hybrid` (`USE_HYBRID_SIMULATION`, only the spell choice simulated) is considerably
faster. AI seats now play the profile `Default` (file `res/ai/Default.ai`) instead of Forge's built-in
standard heuristics — `AiConfig.newLobbyPlayer` always calls `setAiProfile`.

**Reading sim decisions:** `-Dforge.ai.sim.debug=true` (fork flag) makes the picker write every top-level
decision to stdout — phase, hand, every evaluated candidate with its value, the chosen play and the plan.
With `--bench` the parent process passes the flag on to the child processes (output in
`<out>/game-<i>.out.log`); a single game can be replayed directly: `java -Dforge.ai.sim.debug=true -cp …
mtgplayer.Main --bench-one <i> <bench options>` (seed = `--seed` + i, seat order as in the run). Report on the
supposed do-nothing weakness:
[docs/bench/2026-09-20-stufe-2-nichtstun.md](docs/bench/2026-09-20-stufe-2-nichtstun.md).

## Open

Stacking identical lands in deck building, the 5–6 player grid in spectator mode only prepared in CSS (no
fixture), very full spectator boards get small at 1280×720 (cards down to 42 px), Moxfield deliberately left
out.

## License and provenance

Copyright (C) 2026 Haste-MC

MTG-Player builds on [Forge](https://github.com/Card-Forge/forge), a free Magic rules engine under the GNU
General Public License v3, and is therefore distributed under the **GPLv3** itself — the full license text is
in [LICENSE](LICENSE). For anyone who passes a built version on, that means: ship the license text, keep the
source available, mark your changes.

The engine sits as a submodule under `forge/` and is a **modified** Forge: the fork
[Haste-MC/forge](https://github.com/Haste-MC/forge), with the pointer on the branch `mtg-player-2.0.15` (base
tag `forge-2.0.15`; the branch `mtg-player` stays 2.0.14-based until the switch is finished). What differs
from the original is in `git log forge-2.0.15..mtg-player-2.0.15` commit by commit and summarized in
`forge/MODIFICATIONS.md` — at its core bug fixes and improvements to the AI.

Magic: The Gathering is a trademark of Wizards of the Coast; this project has no connection to Wizards and is
free of charge. Card images are not shipped but loaded from [Scryfall](https://scryfall.com) while playing.
