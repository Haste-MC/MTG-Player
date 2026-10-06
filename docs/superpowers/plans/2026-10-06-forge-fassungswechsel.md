# Forge-Fassungswechsel 2.0.14 → 2.0.15 – Umsetzungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unsere 27 Fork-Änderungen auf Forge 2.0.15 neu aufsetzen, belegen dass sie weiter das Richtige tun, und messen, was die neue Engine an Karten und Spielstärke bringt.

**Architecture:** Der Wechsel entsteht auf einem **neuen Zweig** `mtg-player-2.0.15` im Fork — der bestehende Zweig `mtg-player` wird bis zum Schluss nicht angefasst. Die 27 Commits werden einzeln per `cherry-pick` auf den Tag `forge-2.0.15` gesetzt; zwei Konflikte sind bekannt und vermessen. Belegt wird in drei Stufen (Szenentests, volle Suite, Bench-Vergleich), und erst ein positives Ergebnis bewegt `mtg-player` und den Submodul-Zeiger.

**Tech Stack:** Git (cherry-pick, worktree, Tags), Maven, Java 17, Forge 2.0.15, die vorhandene Bench-Maschinerie (`mtgplayer.bench`).

**Spezifikation:** `docs/superpowers/specs/2026-10-06-forge-fassungswechsel-design.md`

## Global Constraints

- **Niemals im Hauptbaum `/home/kevin/projects/MTG-Player` bauen**, solange dort die Bridge des Nutzers läuft. Java-Arbeit geschieht in einem eigenen git-Arbeitsbaum. Vor jedem Maven-Aufruf prüfen: `ps -eo args= | grep -c '[c]lassworlds'` muss `0` oder `1` ergeben.
- **Niemals `mvn clean`.**
- **Der Zweig `mtg-player` im Fork wird vor Task 5 nicht verändert** — kein Rebase darauf, kein force-push. Gearbeitet wird auf `mtg-player-2.0.15`.
- Die Maven-Version des Forks lautet nach dem Wechsel exakt `2.0.15-mtgplayer`.
- Fork-Commits: **Englisch**, upstream-tauglich, ohne Verweis auf dieses Projekt, **ohne** `Co-Authored-By`.
- Commits im Hauptprojekt: Deutsch, klein, Präfixe `bridge:` / `build:` / `docs:`, letzte Zeile genau `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- Kein Test schreibt nach `~/.mtg-player`; kein Test geht ins Netz.
- **Solange Task 4 nicht durch ist, wird aus der neuen Engine kein Paket gebaut.**

---

### Task 1: Rettungsanker und der neue Zweig

**Files:**
- Fork (Arbeitsbaum): `forge-ai/src/main/java/forge/ai/AiAttackController.java`, `forge-game/src/main/java/forge/game/player/Player.java`, alle Modul-`pom.xml`, `MODIFICATIONS.md`

**Interfaces:**
- Consumes: Tag `forge-2.0.15` (liegt bereits im Fork-Klon), Zweig `mtg-player` mit 37 Commits über `forge-2.0.14`.
- Produces: Zweig `mtg-player-2.0.15` im Fork mit 27 Code-Commits plus zwei frischen Commits (Version, Änderungshinweis); Tag `mtg-player-2.0.14` auf dem alten Stand.

- [ ] **Step 1: Rettungsanker setzen**

Der alte Stand muss dauerhaft erreichbar bleiben — veröffentlichte Pakete verweisen über
`forge/MODIFICATIONS.md` darauf, und die GPL verlangt, dass dieser Verweis gültig bleibt.

```bash
git -C /home/kevin/projects/MTG-Player/forge tag -a mtg-player-2.0.14 903f0f1d7e3 -m "Fork state used for releases built on Forge 2.0.14"
git -C /home/kevin/projects/MTG-Player/forge push origin mtg-player-2.0.14
```

Erwartet: `* [new tag] mtg-player-2.0.14 -> mtg-player-2.0.14`

- [ ] **Step 2: Arbeitsbaum auf dem neuen Zweig anlegen**

```bash
git -C /home/kevin/projects/MTG-Player/forge worktree add -b mtg-player-2.0.15 /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung forge-2.0.15
```

Erwartet: `Zu neuem Branch 'mtg-player-2.0.15' gewechselt`

- [ ] **Step 3: Die 27 Commits einzeln aufsetzen**

Genau diese, in genau dieser Reihenfolge. Nicht übertragen werden die drei Paare aus
Bewertungs-Experiment und Rücknahme, der Kartendaten-Commit und der alte Versions-Commit
(Spezifikation §3).

```bash
cd /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung
for c in ffe45981 7b88bf01 2990f760 6c84aec7 450c26e5 deb29633 94931591 c3910fdb \
         7f98cdd9 54afc702 2e25b15a 773e120c db869874 932dd8e9 1c91bcd0 aa851d40 \
         7c4fbee2 af525e92 6b2dc295 a4aba034 f712e5fa 14c29d9a 55a04676 20a78f69 \
         a3318d4c d9a424e0 51251c00; do
  git cherry-pick "$c" || { echo "KONFLIKT bei $c"; break; }
done
```

Erwartet: Zwei Abbrüche, bei `2990f760` und bei `51251c00` — und **nur** bei diesen beiden. Bricht es
woanders ab, stimmt etwas mit der Annahme nicht: melde es, statt weiterzumachen.

- [ ] **Step 4: Konflikt in `Player.java` auflösen**

Fünf strittige Zeilen. Unsere Änderung stammt aus `2990f760`: `Player.mapEffectCard` darf eine
Effektkarte nur dann abbilden, wenn sie wirklich in ihrer Zone liegt (`Zone.remove` löscht
`Card.getZone()` nicht — die Monarch-Karte des Vor-Monarchen hing sonst als Zombie im Mapping), und der
Null-Test in `Player.getMonarchSet()` stand verkehrt herum.

Übernimm beide Absichten in den Text von 2.0.15. Danach:

```bash
git -C /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung add forge-game/src/main/java/forge/game/player/Player.java
git -C /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung cherry-pick --continue --no-edit
```

Dann die restlichen Commits weiterlaufen lassen — dieselbe Schleife, nur ohne die schon erledigten:

```bash
for c in 6c84aec7 450c26e5 deb29633 94931591 c3910fdb 7f98cdd9 54afc702 2e25b15a \
         773e120c db869874 932dd8e9 1c91bcd0 aa851d40 7c4fbee2 af525e92 6b2dc295 \
         a4aba034 f712e5fa 14c29d9a 55a04676 20a78f69 a3318d4c d9a424e0 51251c00; do
  git cherry-pick "$c" || { echo "KONFLIKT bei $c"; break; }
done
```

Erwartet: genau ein weiterer Abbruch, bei `51251c00`.

- [ ] **Step 5: Konflikt in `AiAttackController.java` auflösen**

Das ist die eigentliche Arbeit dieser Aufgabe. **Lies zuerst die ganze betroffene Methode in der Fassung
2.0.15**, bevor du etwas änderst — der Konflikt lässt sich nicht Zeile für Zeile auflösen, sondern nur,
wenn man die neue Struktur kennt. Upstream hat die Parallelverarbeitung dieser Methode
umgebaut: 2.0.15 sammelt `Callable`-Aufgaben in einer Liste `tasks` und führt sie über
`executor.invokeAll(tasks, ai.getGame().getAITimeout(), TimeUnit.SECONDS)` aus. Unsere Änderung aus
`51251c00` ist gegen die vorige Bauweise mit `CompletableFuture` geschrieben.

Unsere Absicht, unverändert gültig: `this.attackers` enthält nur Kreaturen, die den **einen** frei
gewählten Verteidiger angreifen können (siehe `refreshCombatants`). Eine Kreatur, die ausschließlich von
diesem Verteidiger gegoadet ist, darf ihn nicht angreifen, solange ein anderer, nicht goadender
Verteidiger legal ist — sie erreicht die Schleife also nie, ihre Angriffspflicht bleibt unerfüllt, und
`AiController.declareAttackers` verwirft daraufhin die **komplette** Angriffsplanung. Deshalb läuft eine
zweite Schleife über die übrigen eigenen Kreaturen, prüft deren `AttackRequirement` gegen die
brettweiten Vorgaben aus `combat.getAttackConstraints()` und zwingt die mit einem legalen Ziel über
`resolveRequiredDefender(...)` in den Kampf.

Formuliere diese Schleife in der Bauweise von 2.0.15 neu: als Aufgaben in derselben `tasks`-Liste (oder
als zweites `invokeAll` danach). Zwei Dinge müssen erhalten bleiben — der Zugriff auf `combat` bleibt
`synchronized`, weil es dieselbe nicht thread-sichere Multimap ist, und `numForcedAttackers` wird weiter
hochgezählt. Die Hilfsmethode `resolveRequiredDefender` kommt unverändert aus unserem Commit.

```bash
git -C /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung add forge-ai/src/main/java/forge/ai/AiAttackController.java
git -C /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung cherry-pick --continue --no-edit
```

- [ ] **Step 6: Version setzen**

```bash
cd /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung && mvn -q versions:set -DnewVersion=2.0.15-mtgplayer -DgenerateBackupPoms=false
git -C /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung add -A && git -C /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung commit -m "mtg-player: version 2.0.15-mtgplayer (fork branch for MTG-Player)"
```

- [ ] **Step 7: Änderungshinweis nachziehen**

`MODIFICATIONS.md` nennt heute `forge-2.0.14` als Basis und `mtg-player` als Zweig. Setz die Basis auf
`forge-2.0.15`, nenn den Zweig `mtg-player-2.0.15`, und ergänze einen Satz, dass der Stand für Pakete auf
Forge 2.0.14 als Tag `mtg-player-2.0.14` erhalten bleibt. Dann committen (englisch, ohne Projektverweis):

```bash
git -C /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung commit -am "Point the modification notice at the 2.0.15 base"
```

- [ ] **Step 8: Bauen**

```bash
ps -eo args= | grep -c '[c]lassworlds'   # muss 0 oder 1 sein
cd /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung && mvn -q -pl forge-gui -am install -DskipTests -Dcheckstyle.skip -Dmaven.javadoc.skip=true
```

Erwartet: kein Fehler; danach liegt `2.0.15-mtgplayer` in `~/.m2`. Die alte `2.0.14-mtgplayer` bleibt
daneben bestehen — das ist Absicht, Task 4 braucht beide.

- [ ] **Step 9: Zählprobe und schieben**

```bash
git -C /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung log --oneline forge-2.0.15..mtg-player-2.0.15 | wc -l   # erwartet: 29
git -C /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung push -u origin mtg-player-2.0.15
```

29 = 27 Code-Commits plus Version und Änderungshinweis.

---

### Task 2: Belegen, dass unsere Reparaturen weiter greifen

**Files:**
- Keine Änderung geplant. Schlägt ein Test fehl, gehört die Ursache in den Bericht, bevor irgendetwas angefasst wird.

**Interfaces:**
- Consumes: `2.0.15-mtgplayer` in `~/.m2` aus Task 1.
- Produces: ein Testbefund, der für jede der acht Szenen sagt, ob sie steht.

- [ ] **Step 1: Arbeitsbaum des Hauptprojekts vorbereiten**

```bash
git -C /home/kevin/projects/MTG-Player worktree add --detach /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung-bridge main
```

Der Arbeitsbaum steht auf `main`, sein Submodul-Zeiger also noch auf dem **alten** Fork-Stand. Das ist
Absicht und kein Versehen: die Tests holen die Engine aus `~/.m2` über `-Dforge.version`, während
`bridge/assets/res` weiter die bisherigen Kartendaten liefert. So misst diese Aufgabe genau eine
Veränderung — die Engine — und nicht zugleich neue Kartendaten.

- [ ] **Step 2: Die acht Szenentests gegen die neue Engine**

Jeder davon beschreibt eine Lage, die vor unserer Reparatur kaputt war.

```bash
ps -eo args= | grep -c '[c]lassworlds'
cd /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung-bridge/bridge && mvn test -Dforge.version=2.0.15-mtgplayer \
  -Dtest='GoadTest,GoadedTokenSceneTest,ControlDonationSceneTest,MeldTitaniaTest,PhagePlayerLossTest,SimCopierTest,MulliganTest,SorceryCommanderTest' \
  -DfailIfNoSpecifiedTests=false
```

Erwartet: `Failures: 0, Errors: 0`. Rechne mit mehreren Minuten — diese Tests fahren Forge hoch.

- [ ] **Step 3: Volle Bridge-Suite**

Nicht die enge CI-Auswahl: gerade die Tests, die Forge wirklich hochfahren und spielen, sind hier die
aussagekräftigen.

```bash
ps -eo args= | grep -c '[c]lassworlds'
cd /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung-bridge/bridge && mvn test -Dforge.version=2.0.15-mtgplayer
```

- [ ] **Step 4: Befund festhalten**

Schreib das Ergebnis nach `docs/forge-fork.md` in einen neuen Abschnitt „Wechsel auf 2.0.15": je Szene
eine Zeile, und bei jedem Fehlschlag die Ursache — nicht nur die Tatsache. Ein Fehlschlag **stoppt den
Wechsel**, bis die Ursache benannt ist (Spezifikation §8).

- [ ] **Step 5: Committen**

```bash
git add docs/forge-fork.md
git commit -m "docs: szenentests gegen forge 2.0.15, befund"
```

---

### Task 3: Hauptprojekt nachziehen und den Gewinn messen

**Files:**
- Modify: `bridge/pom.xml:15` (`<forge.version>`), `docs/forge-fork.md`, `README.md`, `.github/workflows/release.yml`
- Modify: `docs/kartendaten-ausgeschlossen.txt` (geleert)
- Modify: Submodul-Zeiger `forge`

**Interfaces:**
- Consumes: Zweig `mtg-player-2.0.15` aus Task 1, grüne Tests aus Task 2.
- Produces: ein `main`-Stand, der auf 2.0.15 baut, und die Zahl, wie viele der 78 ausgeschlossenen Karten zurückkommen.

- [ ] **Step 1: Forge-Version im Bridge-POM**

In `bridge/pom.xml` Zeile 15: `<forge.version>2.0.14-mtgplayer</forge.version>` →
`<forge.version>2.0.15-mtgplayer</forge.version>`.

- [ ] **Step 2: Submodul-Zeiger auf den neuen Zweig**

```bash
cd /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung-bridge && git -C forge fetch origin mtg-player-2.0.15 && git -C forge checkout --detach origin/mtg-player-2.0.15
git add forge bridge/pom.xml
```

- [ ] **Step 3: Ausschlussliste leeren**

Sie gilt je Engine-Fassung. `docs/kartendaten-ausgeschlossen.txt` behält nur den Kopf:

```
# Karten und Set-Codes, die unsere Forge-Fassung nicht bauen kann.
# Erzeugt von scripts/kartendaten-sync.sh - von Hand aendern ist erlaubt, aber unnoetig.
# Spalten (Tabulator): Schluessel, Grund, seit wann.
```

- [ ] **Step 4: Dokumentation auf die neue Basis**

In `docs/forge-fork.md`, `README.md` und `.github/workflows/release.yml` jede Stelle, die
`forge-2.0.14` oder `2.0.14-mtgplayer` als unsere Basis nennt, auf 2.0.15 ziehen. Sätze, die sich auf
die **historische** Basis beziehen (etwa die Herleitung früherer Messungen), bleiben wie sie sind.

- [ ] **Step 5: Den Gewinn messen**

```bash
ps -eo args= | grep -c '[c]lassworlds'
cd /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung-bridge/bridge && mvn -q -DskipTests package
mvn -q -f pom.xml dependency:build-classpath -Dmdep.outputFile=/tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung-bridge/target/cp.txt
java -Xmx4g -Dmtgplayer.data=/tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung-bridge/target/kartendaten-data \
  -cp "target/classes:$(cat /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung-bridge/target/cp.txt)" \
  mtgplayer.Main --kartendaten-pruefen | sed -n 's/^KARTENDATEN_BEFUND //p' > /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung-bridge/befund-2015.json
```

Vergleiche `nichtBaubar` mit den 78 Karten aus dem Bericht vom 2026-10-06 (im Pull Request des ersten
Abgleichs). Halte in `docs/forge-fork.md` fest: wie viele der 78 die neue Fassung baut, aufgeschlüsselt
nach `Empower`, Aufkleber-Mechanik und den Einzelfällen — und ob die zehn Karten zurückkommen, die
bisher auf unserer alten Fassung festhingen (`CanBlockIfShadow`, `ManaRestriction`, `CantGainControl`,
`DrawFromBottom`).

**Sinkt die Kartenzahl gegenüber 2.0.14, ist das ein Abbruchgrund** (Spezifikation §8): eine neuere
Engine darf nicht weniger Karten kennen.

- [ ] **Step 6: Committen**

```bash
git add bridge/pom.xml forge docs/ README.md .github/workflows/release.yml
git commit -m "build: forge-submodul und abhaengigkeit auf 2.0.15"
```

---

### Task 4: Bench-Vergleich und Entscheidung

**Files:**
- Create: `docs/bench/2026-10-06-fassung-2015.md`

**Interfaces:**
- Consumes: beide Forge-Fassungen in `~/.m2` (`2.0.14-mtgplayer` aus dem Bestand, `2.0.15-mtgplayer` aus Task 1).
- Produces: einen Bericht mit beiden Quoten samt Vertrauensintervall und die Entscheidung nach Spezifikation §6.

- [ ] **Step 1: Beide Läufe, identisch bis auf die Engine**

Der Bench vergleicht zwei KI-Einstellungen **innerhalb** einer Engine — er kann nicht zwei Engines
gegeneinander spielen lassen. Der Vergleich entsteht deshalb aus **zwei Läufen mit demselben Startwert**,
die sich ausschließlich in `-Dforge.version` unterscheiden. Decks, Profile, Partienzahl und Bedenkzeit
bleiben gleich.

```bash
ps -eo args= | grep -c '[c]lassworlds'
cd /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung-bridge/bridge
mvn -q compile exec:java -Dforge.version=2.0.14-mtgplayer \
  -Dexec.args="--bench --games 40 --seed 20261006 --out /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung-bridge/bench-2014.json"
mvn -q compile exec:java -Dforge.version=2.0.15-mtgplayer \
  -Dexec.args="--bench --games 40 --seed 20261006 --out /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung-bridge/bench-2015.json"
```

Beide Läufe dauern Stunden. Lass sie nacheinander laufen, nicht gleichzeitig — zwei Maven-Prozesse
nebeneinander sind in diesem Projekt ausgeschlossen.

- [ ] **Step 2: Bericht schreiben**

`docs/bench/2026-10-06-fassung-2015.md` nach dem Muster der vorhandenen Berichte in `docs/bench/`: beide
Quoten, beide Vertrauensintervalle, der verwendete Startwert, Partienzahl, Decks und Profile.

Dazu wörtlich die Einordnung aus Spezifikation §5, weil eine Quote ohne sie irreführt:

> Vierzig Partien tragen eine Unsicherheit von gut fünfzehn Prozentpunkten. Das fängt einen Absturz der
> Spielstärke, keine Feinheit. Eine Verschiebung von fünf Punkten sicher zu sehen, bräuchte Hunderte von
> Partien.

- [ ] **Step 3: Die Entscheidung treffen**

Nach Spezifikation §6, und nach der Regel, die **vor** der Messung festgelegt wurde:

- Quote von 2.0.15 **unterhalb der unteren Schranke** von 2.0.14 → der Wechsel wird nicht übernommen.
  Der Zweig `mtg-player-2.0.15` bleibt im Fork stehen, Task 5 entfällt, und der Grund gehört in den
  Bericht.
- gleichauf oder besser → weiter mit Task 5.

- [ ] **Step 4: Committen**

```bash
git add docs/bench/2026-10-06-fassung-2015.md
git commit -m "docs: bench-vergleich forge 2.0.14 gegen 2.0.15"
```

---

### Task 5: Umstellen — nur bei positiver Entscheidung

**Diese Aufgabe läuft nur, wenn Task 4 Schritt 3 dafür ausgegangen ist.** Vorher nicht, auch nicht
„schon mal vorbereiten".

**Files:**
- Fork: Zweig `mtg-player`
- Hauptprojekt: Submodul-Zeiger, `main`

**Interfaces:**
- Consumes: die Entscheidung aus Task 4.
- Produces: `mtg-player` zeigt auf den 2.0.15-Stand; `main` baut darauf.

- [ ] **Step 1: Den Fork-Zweig umstellen**

Der alte Stand bleibt über den Tag `mtg-player-2.0.14` aus Task 1 dauerhaft erreichbar — deshalb ist das
Umschreiben hier zulässig.

```bash
git -C /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung branch -f mtg-player mtg-player-2.0.15
git -C /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung push --force-with-lease origin mtg-player
```

- [ ] **Step 2: Submodul-Zeiger auf `mtg-player` statt auf den Arbeitszweig**

```bash
cd /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung-bridge && git -C forge fetch origin mtg-player && git -C forge checkout --detach origin/mtg-player
git add forge && git commit -m "build: forge-submodul zeigt wieder auf mtg-player"
```

- [ ] **Step 3: Die Arbeit nach `main` bringen**

Die Commits aus Task 2 bis 5 in den Hauptbaum übernehmen (`git cherry-pick`), die volle Bridge-Suite ein
letztes Mal laufen lassen, dann `git push origin main`.

- [ ] **Step 4: Aufräumen**

```bash
git -C /home/kevin/projects/MTG-Player worktree remove --force /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung-bridge
git -C /home/kevin/projects/MTG-Player/forge worktree remove --force /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/fassung
git -C /home/kevin/projects/MTG-Player worktree prune
git -C /home/kevin/projects/MTG-Player/forge worktree prune
```

---

## Was danach von Hand bleibt

- Der nächste wöchentliche Kartenabgleich baut die Ausschlussliste neu auf. Sein Bericht ist die
  Gegenprobe zu der Zahl aus Task 3 Schritt 5.
- Ein Paket aus der neuen Engine darf erst gebaut werden, wenn Task 4 durch ist (Global Constraints).
- Die wöchentliche Benachrichtigung über neue Forge-Releases ist bewusst nicht Teil dieses Plans
  (Spezifikation §10); sie wird erst wieder interessant, wenn 2.0.16 ansteht.
