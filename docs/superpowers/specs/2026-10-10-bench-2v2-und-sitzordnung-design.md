# 2v2 bench and fair seating — design

**Status:** approved 2026-10-10 (Kevin). Prerequisite for the partner-play AI work: that piece changes shared
AI code and must be measured, not guessed.

## Goal

1. Measure a team match: the bench plays 2v2 and reports a team win rate the way it reports a seat win rate
   today.
2. Seat a team match fairly, in the bench **and** in the games Kevin plays: the teams alternate around the
   table instead of each team taking two turns in a row.

## Why

- Every AI package so far (levels 2–4) was decided on bench numbers with a confidence interval. Partner play
  touches `PumpAi`, `ProtectAi`, `DrawAi` and friends — shared code that Kevin's ordinary free-for-all games
  run through as well — so "it feels better" is not enough.
- The team mode shipped on 2026-10-10 seats by lobby order: with teams `1,1,2,2` the seats are
  `You, partner, opponent, opponent`, and since turn order follows seat order, team 1 takes two turns in a
  row. A bench built on that order would measure the double turn, not the AI.

## Part 1: the bench plays 2v2

**CLI.** One new flag, `--teams`. Without it everything stays exactly as today (two seats, `--a` vs `--b`).
With it:

- `--deck-a` and `--deck-b` are no longer the two opponents' decks but the **deck pair that both teams play**;
- `--a` and `--b` are the AI settings of team A and team B, applied to both seats of their team;
- the run has four seats.

```bash
cd bridge && mvn -q compile exec:java -Dexec.args="--bench --teams --games 40 \
  --a sim:Default --b sim:Legacy \
  --deck-a 'precon:Abzan Armor [TDC] [2025]' --deck-b 'precon:Adaptive Enchantment [C18] [2018]' --seed 1"
```

**Seats.** Mirrored and interleaved: `A1(P), B1(P), A2(Q), B2(Q)`. Each deck plays once per team, the teams
alternate, and neighbouring seats hold the same deck. As today the bench rotates which seat comes first
across games (`i % 4` instead of `i % 2`), so the starting advantage does not stick to one team.

**Measurement.** Team A's win rate with the same 95 % Wilson interval the bench already prints; per game one
row with the winning team, the reason, the turns and the duration. The `.json` carries the team per seat, so
later runs can be compared. A draw at the turn cap counts as "no winner" and stays out of the rate, exactly
as today.

**Plumbing.** `AiMatch.play(...)` already takes 2–6 seats with their own `AiConfig`; it gains an optional team
list and applies it the way `HumanMatch` does. The child-process path (`--bench-one`, one JVM per game) is
unchanged except that `--teams` has to travel to the child — a flag that does not reach the child would
silently measure a free-for-all.

**Rejected:** a deck per seat (`--deck-a1 …`), teams in sparring, more than two teams. All three are switch
work later if they are ever wanted.

## Part 2: fair seating in the games Kevin plays

A small pure unit decides the order and is used by **both** sides, so the bench measures what he plays:

```java
/** Permutation der Sitze, so dass sich die Teams abwechseln; ohne Teams die Reihenfolge wie gegeben. */
static List<Integer> Seating.interleave(List<Integer> teams)
```

Two constraints shape the implementation:

- **Forge sorts the human seat to the front.** `HostedMatch.startMatch` stable-sorts `LobbyPlayerHuman`
  first, so the permutation must put the human at index 0 itself and alternate from there
  (`You(T1), AI(T2), AI(T1), AI(T2)`). Working against that sort would fail silently.
- **The team list must be permuted with the seats.** It arrives in lobby order; permuting the players but not
  the teams would hand the wrong team to the wrong seat — a fault that only shows up in a real match.

**Uneven teams** (1v2) cannot avoid a double turn; the rule spreads the seats as evenly as it can
(`T1, T2, T2`) and says so in its comment instead of pretending to be fair.

**Visible effect:** the lobby order now decides only *who plays with whom*, no longer *who plays when*. The
README says that in one sentence.

## Error handling

- `--teams` without both deck flags, or with a deck reference that does not resolve, fails the same way the
  two-seat run does today, with a German message on stderr and a non-zero exit.
- An odd or missing team list reaching `AiMatch.play` is a programming error, not user input:
  `IllegalArgumentException`, as `HumanMatch.applyTeams` already does.

## Testing

- `Seating.interleave`: pure unit — 2v2, 1v2, three teams, no teams (unchanged), human seat first.
- Bench: `BenchArgs.parse` with and without `--teams`; the seat construction for a given game index (decks,
  names, configs and teams line up); `--teams` reaches the child process (the `SubprocessRunner` argument
  list).
- Engine: a four-seat scene whose **actual turn order** alternates between the teams.
- Regression: the existing team tests expect seat order `1,1,2,2`; they move to the new order. They are the
  proof that the team list is permuted along with the seats.
- A short real bench run (few games) as a smoke test, since the full run takes hours.

## Out of scope

Partner-play itself (the AI aiming pump, draw and protection at its teammate) — that is the next spec, and it
is measured with what this one builds. Also out: Two-Headed Giant, teams in sparring, a second human seat.
