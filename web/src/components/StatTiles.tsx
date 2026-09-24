import type { ReactNode } from "react";
import type { DeckSummary, Format } from "../matchStats";

// Die Kacheln des Statistik-Boards (Spec §4, Bloecke 1-5) samt der Zahlenformate, die der ganze Screen
// benutzt. Zwei Regeln gelten in jeder Kachel:
//  1. Kein Wert -> "–" und ein Satz, WARUM es ihn nicht gibt. Nie eine 0, die es nie gab (siehe NO_DATA).
//  2. Jede Kachel kann ihren Satz mitliefern ("Erklärungen" im Kopf, Standard an) - die Zahl allein
//     beantwortet Kevins Frage "wo lese ich da eine Schwaeche raus?" nicht.

/** Erklaerung an einer Kachel, deren Kennzahl es erst ab Formatversion 2 gibt (Runde B). In aelteren
 * Partien wurde sie NIE gezaehlt - eine 0 waere erfunden (siehe matchStats.v2Metrics). */
const NO_DATA = "wird erst ab neuen Partien erfasst";

/** Anteil als ganze Prozent. */
const pct = (x: number) => Math.round(x * 100) + " %";
/** Zahl mit deutschem Dezimalkomma. */
export const one = (x: number, digits = 1) => x.toFixed(digits).replace(".", ",");
/** Dauer als mm:ss (Minuten laufen ueber 60 weiter - eine lange Partie zeigt lieber 84:10 als 1:24:10). */
export const mmss = (ms: number) => {
  const s = Math.round(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
};
/** Dieselben Formate fuer einen Wert, den es auch nicht geben kann: undefined bleibt undefined und die
 * Kachel zeigt "–" mit ihrer Begruendung, statt eine erfundene 0 zu drucken. */
const optPct = (x?: number) => (x == null ? undefined : pct(x));
const optOne = (x?: number, digits = 1) => (x == null ? undefined : one(x, digits));

/** Eine Kennzahl: Wert gross, Label klein, optional eine Zusatzzahl und (wenn Erklaerungen an) ein Satz.
 * Fehlt `value`, steht "–" da und `missing` sagt, warum - unabhaengig vom Erklaerungs-Schalter, denn
 * das ist keine Lehrstunde, sondern der Zustand der Daten. */
function Tile(
  { value, label, sub, hint, missing = NO_DATA, explain }:
  { value?: string; label: string; sub?: string; hint?: string; missing?: string; explain?: boolean },
) {
  const empty = value === undefined;
  return (
    <div className={"tile" + (empty ? " empty" : "")}>
      <span className="tile-value">{empty ? "–" : value}</span>
      <span className="tile-label">{label}</span>
      {empty ? <span className="tile-missing">{missing}</span> : sub && <span className="tile-sub">{sub}</span>}
      {explain && hint && <span className="tile-hint">{hint}</span>}
    </div>
  );
}

/** Ein Block mit Ueberschrift; `note` steht als kleiner Satz darunter (nur mit Erklaerungen). */
function Block({ title, note, explain, children }: { title: string; note?: string; explain?: boolean; children: ReactNode }) {
  return (
    <section className="sb-block">
      <div className="sb-block-head">
        <h2>{title}</h2>
        {explain && note && <p className="sb-block-note">{note}</p>}
      </div>
      <div className="tile-grid">{children}</div>
    </section>
  );
}

/** Stichprobe der Vorfall-Kennzahlen als Satzende: "(3 von 8 Partien)" - jede Kachel aus Runde B nennt
 * sie, damit klar ist, dass hier nicht ueber alle Partien gerechnet wird. */
function sample(s: DeckSummary): string {
  return s.v2Games === s.games
    ? `Alle ${s.games} Partien der Auswahl tragen diese Daten.`
    : `Gerechnet über ${s.v2Games} von ${s.games} Partien der Auswahl.`;
}

/** Stichprobe einer Kennzahl, die NICHT ueber alle v2-Partien rechnet (siehe DeckSummary): die eigene
 * Zahl und wodurch sie begrenzt ist. v2Games gehoert in so einen Satz nicht - es ist der groessere Nenner. */
function subSample(n: number, s: DeckSummary, what: string): string {
  return `Gerechnet über ${n} von ${s.games} Partien der Auswahl – ${what}.`;
}

/** Die Kennzahlen-Bloecke 1 bis 5 der Spec. Block 6 (Deckinhalt) und 7 (Gegner) stehen daneben in
 * DeckAnalysisPanel bzw. Stats.tsx, weil sie keine Kacheln, sondern Listen mit Bildern sind. */
export default function StatBlocks({ s, format, explain }: { s: DeckSummary; format: Format; explain: boolean }) {
  const pod = format === "pod";
  return (
    <>
      <Block title="Bilanz" explain={explain}
        note="Gezählt werden nur gewertete Partien; Abbrüche, Aufgaben und Zugdeckel-Partien stehen unten in der Liste.">
        <Tile explain={explain} value={String(s.games)} label="Partien" sub={`${s.wins} S · ${s.losses} N · ${s.draws} R`}
          hint="Siege, Niederlagen, Remis in der gewählten Auswahl." />
        <Tile explain={explain} value={pct(s.winRate)} label="Siegquote" sub={`95 %: ${pct(s.ci[0])} – ${pct(s.ci[1])}`}
          hint="Das Intervall sagt, wie weit die Quote bei dieser Partienzahl noch wandern kann – bei wenigen Partien ist es breit." />
        <Tile explain={explain} value={one(s.avgTurns)} label="Ø Züge"
          hint="Partiezüge insgesamt (alle Sitze), nicht eigene Züge." />
        <Tile explain={explain} value={mmss(s.avgDurationMs)} label="Ø Dauer (mm:ss)"
          hint="Echte Zeit am Stück – hängt auch an der KI-Bedenkzeit der Partie." />
        {pod && (
          <Tile explain={explain} value={one(s.avgPlace, 2)} label="Ø Platz"
            sub={`1 = Sieg, ${s.games} Partien`}
            hint="Platz 1 plus jeder Mitspieler, der dich überlebt hat. Im Pod sagt er mehr als die Siegquote." />
        )}
        {pod && (
          <Tile explain={explain} value={optOne(s.avgEliminatedTurn)} label="Ø Ausscheide-Zug"
            sub={s.avgEliminatedTurn != null ? `von Ø ${one(s.avgTurns)} Zügen` : undefined}
            missing="du bist in keiner dieser Partien ausgeschieden"
            hint="In welchem Zug du aus der Partie gefallen bist – weit vor dem Partieende heißt: du warst nur Zuschauer." />
        )}
        {s.turnCappedGames > 0 && (
          <Tile explain={explain} value={pct(s.turnCappedRate)} label="Partien am Zugdeckel"
            sub={`${s.turnCappedGames} von ${s.games + s.turnCappedGames} gespielten`}
            hint="Die Partie lief in die maximale Zugzahl und wurde abgeschnitten – dem Deck fehlt ein Abschluss. Zählt nicht in die Bilanz." />
        )}
      </Block>

      <Block title="Mana & Start" explain={explain}
        note="Wie zuverlässig das Deck anläuft. Gezählt wird in EIGENEN Zügen, nicht in Partiezügen.">
        <Tile explain={explain} value={optOne(s.avgLandsTurn3)} label="Ø Länder – eigener Zug 3"
          missing="keine Partie lief so lange"
          hint="Länder im Spiel im dritten eigenen Zug. Unter 3 wird es eng für alles ab 3 Mana." />
        <Tile explain={explain} value={optOne(s.avgLandsTurn5)} label="Ø Länder – eigener Zug 5"
          missing="keine Partie lief so lange"
          hint="Nur über Partien, in denen du den fünften eigenen Zug überhaupt erlebt hast." />
        <Tile explain={explain} value={optPct(s.manaScrewRate)} label="Mana-Screw-Quote"
          sub={s.manaScrewRate != null
            ? subSample(s.manaScrewGames ?? s.v2Games, s, "nur die, die deinen 3. eigenen Zug erreicht haben")
            : undefined}
          hint="Partien mit höchstens 2 Ländern im dritten eigenen Zug. Über 30 % ist es die Manabasis, nicht das Pech." />
        <Tile explain={explain} value={pct(s.floodRate)} label="Flut-Quote"
          sub={`über alle ${s.games} Partien`}
          hint="6 Länder oder mehr gespielt und höchstens 2 Zauber gewirkt – reichlich Land, nichts zu tun." />
        <Tile explain={explain} value={pct(s.missedLandDropRate)} label="Partien mit verpasster Landabgabe"
          sub={`Ø ${one(s.avgMissedLandDrops)} je Partie`}
          hint="Ein eigener Zug ohne Landabgabe, obwohl du eines hättest legen dürfen – meist kein Land auf der Hand." />
        <Tile explain={explain} value={pct(s.mulliganRate)} label="Mulligan-Quote"
          sub={`Ø ${one(s.avgMulligans)} je Partie`}
          hint="Partien mit mindestens einem Mulligan. Dauerhaft über 40 % heißt: die Starthände stimmen nicht." />
        <Tile explain={explain} value={optOne(s.avgOpeningLands)} label="Ø Länder der Starthand"
          sub={s.avgOpeningLands != null ? sample(s) : undefined}
          hint="Länder in der Hand, die du nach allen Mulligans behalten hast." />
      </Block>

      <Block title="Tempo & Commander" explain={explain}
        note="Wie schnell das Deck seinen Plan auf den Tisch bringt.">
        <Tile explain={explain} value={optOne(s.avgCommanderTurn)} label="Ø Zug des 1. Commanders"
          missing="der Commander kam in keiner Partie ins Spiel"
          hint="Forges Zugzähler beim ersten Wirken – nur über Partien, in denen er überhaupt kam." />
        <Tile explain={explain} value={one(s.avgCommanderTax)} label="Ø Commander-Steuer"
          sub="zusätzliches Mana am Partieende"
          hint="2 Mana je erneutem Wirken aus der Kommandozone. Hoch heißt: der Commander wird dir laufend abgeräumt." />
        <Tile explain={explain} value={one(s.avgSpells)} label="Ø Zauber je Partie"
          sub={`Ø ${one(s.avgSpellMana)} Mana ausgegeben`}
          hint="Alle gewirkten Zauber des Sitzes, Länder zählen nicht mit." />
        <Tile explain={explain} value={optOne(s.spellsPerTurn, 2)} label="Ø Zauber je eigenem Zug"
          sub={s.spellsPerTurn != null ? sample(s) : undefined}
          hint="Zauber geteilt durch eigene Züge – unabhängig davon, wie lange die Partie lief." />
      </Block>

      <Block title="Kampf & Überleben" explain={explain}
        note="Woher der Schaden kommt, den du kassierst – und was du dagegensetzt.">
        <Tile explain={explain} value={one(s.avgDamageDealt)} label="Ø Schaden gemacht"
          hint="Aller Schaden, den deine Karten austeilen – Kampf und Nichtkampf zusammen." />
        <Tile explain={explain} value={one(s.avgDamageTaken)} label="Ø Schaden genommen"
          hint="Aller Schaden an deinem Sitz, ohne Commander-Schadensregel." />
        <Tile explain={explain} value={optPct(s.flyingShare)} label="Anteil Flieger am Kampfschaden"
          sub={s.flyingShare != null ? sample(s) : undefined}
          hint="Wie viel des erlittenen KAMPFschadens aus der Luft kam. Hoch plus wenig Reichweite im Deck = offene Flanke." />
        <Tile explain={explain} value={optPct(s.tramplingShare)} label="Anteil Trampelschaden"
          sub={s.tramplingShare != null ? sample(s) : undefined}
          hint="Schaden, der trotz Blocks durchkam – Chump-Blocken hilft dagegen nicht." />
        <Tile explain={explain} value={optOne(s.avgAttacks)} label="Ø eigene Angriffe"
          sub={s.avgAttacks != null ? sample(s) : undefined}
          hint="Jede Deklaration eines Angreifers zählt einzeln, auch mehrfach im selben Zug." />
        <Tile explain={explain} value={optOne(s.avgAttackersFaced)} label="Ø Angreifer gegen dich"
          sub={s.avgAttackersFaced != null ? sample(s) : undefined}
          hint="Wie oft gegnerische Kreaturen auf DICH deklariert wurden – im Pod die Frage, ob du die Zielscheibe bist." />
        <Tile explain={explain} value={one(s.avgLifeEnd)} label="Ø Leben am Ende"
          hint="Lebenspunkte beim Ausscheiden bzw. am Partieende." />
        <Tile explain={explain} value={optOne(s.avgHandEnd)} label="Ø Handkarten am Ende"
          sub={s.avgHandEnd != null ? sample(s) : undefined}
          hint="Karten, die du beim Ausscheiden noch auf der Hand hattest – viele heißen: zu wenig Mana, nicht zu wenig Karten." />
      </Block>

      <Block title="Interaktion & Verluste" explain={explain}
        note="Was dir abhanden kommt und wie oft du selbst eingreifst. Alle Zahlen dieses Blocks stammen aus den neuen Vorfall-Daten.">
        <Tile explain={explain} value={optPct(s.counteredRate)} label="Gekonterte eigene Zauber"
          sub={s.counteredRate != null ? sample(s) : undefined}
          hint="Anteil deiner Zauber, die auf dem Stapel gestoppt wurden." />
        <Tile explain={explain} value={optOne(s.avgCounterspellsCast)} label="Ø eigene Konterzauber"
          sub={s.avgCounterspellsCast != null ? sample(s) : undefined}
          hint="Von der Bridge aus dem Kartentext geschätzt – eine Größenordnung, keine Zählung." />
        <Tile explain={explain} value={optOne(s.avgRemovalCast)} label="Ø eigene Entfernung"
          sub={s.avgRemovalCast != null ? sample(s) : undefined}
          hint="Zerstören/Verbannen/gezielter Schaden, ebenfalls aus dem Kartentext geschätzt." />
        <Tile explain={explain} value={optOne(s.avgPermanentsLost)} label="Ø verlorene bleibende Karten"
          sub={s.avgPermanentsLost != null ? sample(s) : undefined}
          hint="Alles, was dir vom Tisch verschwindet – Kampf, Entfernung, eigene Kosten." />
        <Tile explain={explain} value={optOne(s.avgBiggestSweep)} label="Ø größte Massenentfernung"
          sub={s.avgBiggestSweep != null ? sample(s) : undefined}
          hint="Der größte Verlust in einem einzigen Moment. Partien ohne Massenentfernung gehen mit 0 ein." />
        <Tile explain={explain} value={s.sweepGames != null ? pct(s.sweepGames / s.v2Games) : undefined}
          label="Partien mit Massenentfernung"
          sub={s.sweepGames != null ? `${s.sweepGames} von ${s.v2Games} Partien mit Vorfall-Daten` : undefined}
          hint="Mindestens drei eigene bleibende Karten auf einmal verloren." />
      </Block>
    </>
  );
}
