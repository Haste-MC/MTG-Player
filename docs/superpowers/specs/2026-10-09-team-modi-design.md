# Team modes (2v2 and free team assignment) — design

**Status:** approved 2026-10-09 (Kevin). Supersedes nothing; extends the existing match flow.

## Goal

Let a match be played in teams: every seat carries a team number, teammates are not opponents, and a team
wins together. One human seat plus AI opponents and an AI partner; AI-only matches (spectator mode) too.
Sparring stays 1 vs 1.

## Why this is cheap in the engine

Forge already supports teams at the player level:

- `RegisteredPlayer.setTeamNumber(int)` → `Player.getTeam()`; `-1` means "no team" (today's free-for-all).
- `Player.isOpponentOf(other)` is `other.teamNumber < 0 || other.teamNumber != teamNumber` — attack
  legality, `ValidTgts$ Opponent`, "each opponent" and the AI's `ai.getOpponents()` all follow from it.
- `GameAction` ends the game with `GameEndReason.AllOpposingTeamsLost` as soon as only one team is left,
  and `GameOutcome.getWinningTeam()` names it.

The bridge builds its `RegisteredPlayer`s itself (`HumanMatch`, `AiMatch`) and does not use Forge's lobby
layer, so setting the team is one call per seat. **No fork change is needed for this piece.**

What Forge does *not* give us:

- `PlayerView` carries no team (only `GameView` has `WinningTeam`, and only once the game is over), so the
  view layer cannot read it — see "Team map" below.
- `GameOutcome.getWinningLobbyPlayer()` returns an arbitrary winner when a team wins, so the bridge's own
  `WebGuiGame.gewinner()` has to become team-aware (today it returns nothing for several survivors, and the
  dialog would say "Game over" after every 2v2).
- The AI does not actively cooperate (no pump, draw or protection aimed at a teammate). Out of scope here,
  first follow-up piece (see "Later").

## Protocol

`startGame` gains one optional field per seat:

```json
{"type":"startGame","humanDeck":{…},"humanTeam":1,"revealPartnerHand":false,
 "opponents":[{…,"name":"AI 1","team":1},{…,"team":2},{…,"team":2}],"aiTimeout":10}
```

- Spectator mode (`"spectate":true`, no `humanDeck`) uses the same `team` per opponent entry.
- **Validity:** either no seat carries a team, or every seat carries one *and* at least two distinct team
  numbers are present. Anything else is rejected with an `error` message before Forge starts (a seat with no
  opponent would end the match immediately).
- Missing fields everywhere = today's behaviour, so older clients and the series ("Nochmal spielen" resends
  the same `startGame`) keep working unchanged.
- `revealPartnerHand` only has an effect when a human seat exists.

`Snapshot.PlayerSnap` gains `team` (int, `-1` = none), mirrored in `web/src/protocol.ts`. The client derives
everything from it: my team is the team of seat `me`, my partner is the other seat with the same number.

`GameOver` gains `winnerSeats` (list of seat ids, empty when there is no winner). The client composes the
sentence — one seat: "X wins"; several: "Team 1 wins — X and Y"; none: "Game over" / "Draw".

## Components and data flow

1. **Lobby (`web/src/components/Lobby.tsx`)** — a third select per seat section ("kein Team", "Team 1",
   "Team 2", "Team 3"), a quick pick "2v2" that sets 1/1/2/2 when four seats are filled, a line that spells
   the line-up out ("Team 1: Du + KI 1 — Team 2: KI 2 + KI 3"), and the checkbox "Partnerhand zeigen" (off).
   Choices are remembered in the browser like the other lobby settings. Lobby wording stays German per the
   language rule of 2026-10-09.
2. **Bridge (`server/Bridge.java`)** — reads `humanTeam`, `team` per opponent and `revealPartnerHand`,
   validates as above, hands a team list (seat order) and the flag to `HumanMatch`.
3. **Match (`match/HumanMatch.java`)** — `rp.setTeamNumber(team)` after `RegisteredPlayer.forCommander(…)`,
   for the human seat and every AI seat, in both `start(…)` and `startSpectator(…)`. It also builds the
   **team map** (seat id → team) and hands it to `WebGuiGame`.
4. **Snapshot (`protocol/StateSerializer.java`, `protocol/Snapshot.java`)** — writes `team` per player from
   the team map, and, when `revealPartnerHand` is set, includes the partner's hand cards with their details
   exactly like the viewer's own hand.
5. **Table (`web/src/components/PlayerZone.tsx` and the shared styles)** — a team chip (`T1`, `T2`, …) in a
   fixed per-team colour in the seat header, the panel border picking up the same colour, and the word
   `Partner` on the viewer's teammate. Without teams nothing changes (no chip).
6. **Game end (`gui/WebGuiGame.java`, `web/src/components/Table.tsx`)** — `gewinner()` accepts several
   survivors when they all share a team; `GameOver` carries the winning seats; the client writes the text.
7. **Series (`web/src/series.ts`, `store.ts`)** — with teams the series counts team wins (key `team:<n>`)
   and the standings line names the members once: "Team 1 (You + AI 1) 2 · Team 2 1".
8. **Statistics (`stats/MatchRecord.java`, `stats/MatchRecorder.java`, `web/src/matchStats.ts`,
   `components/Stats.tsx`)** — `Seat.team`, `MatchRecord.VERSION 4`, `formatOf()` returns `"team"` when at
   least two seats share a team, a fourth format switch "Team", and a line-up chip (`2v2`, `1v2`) in the
   match list.

### Team map (why a copy exists)

`PlayerView` has no team, so the view layer gets a copy. `HumanMatch` hands `WebGuiGame` the team numbers
**in seat order** — the same list it used for `setTeamNumber` — because the seat ids of the views do not exist
yet when the `RegisteredPlayer`s are built. `GameView.getPlayers()` comes in registration order (the bridge
already relies on that where it matches seat names to records), so the serializer resolves index → team on the
first snapshot and caches id → team from then on.
The engine stays authoritative (`Player.getTeam()`), and the `MatchRecorder` — which holds the game objects —
reads the engine directly instead of the copy. A test compares both sides seat by seat in a real match so the
copy cannot drift. Teams never change during a game, so a snapshot of the mapping is faithful.

### Partner hand

Revealing the partner's hand is **our** decision in the serializer, not Forge's: `CardView.canBeShownTo`
would say no. Consequences, stated on purpose: it applies only when a human seat exists (spectator mode has
no "my team"), and every browser at this table sees the same thing, because all clients share one state.

## Statistics details

- Old records (v1–v3) have no `team` field and read as `-1`: they are free-for-all matches, which is true, so
  no record is silently reinterpreted.
- `formatOf()` checks teams first, then falls back to seat count (`duel` for 2 seats, otherwise `pod`). Team
  matches therefore leave the "Pod (3+)" bucket; "Alle" still counts everything.
- A team win marks **both** seats of the winning team as `winner`, so the per-deck record needs no new logic.
- The findings rules run over the selected format, so their numbers come from team matches only. The rules
  that are pod-only today (above all "früh raus") also apply to team; minimum sample sizes stay as they are,
  so the rules simply stay silent until enough team matches exist.

## Error handling

- Invalid team assignment → `error` before the match starts, naming what is wrong ("Teams: entweder alle
  Sitze oder keiner", "Teams: mindestens zwei verschiedene Teams").
- A seat count that cannot form the chosen line-up (e.g. "2v2" with three seats) disables the quick pick in
  the lobby instead of sending something the bridge would reject.
- Everything else (crash, concede, turn cap) behaves as today; a team match that is aborted counts as not
  counted, exactly like a free-for-all one.

## Testing

Bridge:
- team assignment validation, including both error cases;
- `setTeamNumber` reaches the engine (`Player.getTeam()` per seat);
- **scene test: a real headless four-seat match with teams 1/1/2/2 that ends with `AllOpposingTeamsLost`** —
  the end-to-end proof that engine, winner and record agree;
- snapshot team equals engine team, seat by seat;
- `gewinner()` with two survivors of one team (winner) and with two survivors of different teams (no winner);
- partner hand in the snapshot with and without the flag;
- `MatchRecorder` writes `Seat.team`.

Web:
- `formatOf` with a team record;
- series counting by team;
- game-over text for one seat, several seats and none;
- lobby validation and the 2v2 quick pick;
- team chip and `Partner` marker in the seat header.

## Later (deliberately not in this piece)

1. **Real cooperation in the AI** (Kevin's follow-up wish): the AI should aim pump, card draw and protection
   at its teammate. That is `forge-ai` work in the fork — teammates barely appear there today — and deserves
   its own piece with bench evidence.
2. Two-Headed Giant (shared life and turns, rule 810) — not implemented in Forge at all.
3. Sparring with teams (partner choice, team record, own statistics category).
4. Seating grouped by team at the table, team totals.
5. A second human seat.

## Risks

- Forge's AI will not help its partner, so the first spectator matches may look slow and unspectacular. That
  is the finding that motivates follow-up 1, not a defect of this piece.
- Multiplayer effects resolved through `getOpponents()` (goad, monarch, "each opponent") gain new cases with
  teams. The headless scene test plus one or two spectator matches are the cheap probe before playing along.
