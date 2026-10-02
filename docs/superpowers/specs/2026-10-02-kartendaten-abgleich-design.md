# Kartendaten von upstream abgleichen – Design

Stand: 2026-10-02. Betrifft den Forge-Fork `Haste-MC/forge` (Branch `mtg-player`, Basis `forge-2.0.14`,
37 eigene Commits) und das Hauptprojekt `Haste-MC/MTG-Player`, das ihn als Submodul `forge/` führt.

Anlass: Kevins Befund „manche Decks kann ich gar nicht importieren, er kennt die Karten nicht". Einmal,
am 2026-09-25, wurden Kartendaten von Hand abgeglichen (Commit `0e30eaac`, +357 Skripte). Diese Arbeit
soll sich nicht wiederholen müssen.

## 1. Zwei Wege, die sich nie kreuzen

„Upstream-Update" meint zwei sehr verschiedene Dinge, und sie werden hier bewusst getrennt:

**Kartendaten** (`cardsfolder`, `editions`, `tokenscripts`) sind reine Datendateien. Sie berühren weder
unsere 37 Commits noch Forges Java. Dieser Weg läuft wöchentlich von allein bis zum fertigen Pull
Request.

**Die Forge-Fassung** (Wechsel auf ein Release über `forge-2.0.14`) ist ein Umbau: neue Engine, neue
Regeln, unsere Patches müssen sich neu einfügen, und die ganze Beweiskette muss erneut stehen. Dieser
Weg läuft **nie** von allein. Automatisiert wird nur die Nachricht, dass es etwas gibt, und die stumpfe
Handarbeit danach.

## 2. Umfang des Kartenabgleichs

Abgeglichen wird gegen `upstream/master` (`https://github.com/Card-Forge/forge.git`, im Fork bereits als
Remote `upstream` eingetragen), und zwar **vollständig**: neue Dateien, geänderte Dateien, Umbenennungen
und Löschungen in genau diesen drei Verzeichnissen unter `forge-gui/res/`:

- `cardsfolder`
- `editions`
- `tokenscripts`

Nichts anderes. Kein Java, keine POMs, keine `lists`/`formats`.

**Warum vollständig und nicht nur das Fehlende:** Der Abgleich von 2026-09-25 kopierte bewusst nur
fehlende Dateien. Die Nachprüfung am 2026-10-02 zeigt, dass diese Vorsicht nichts von uns schützt – wir
haben **nie** ein Kartenskript selbst geändert (`git diff --name-status forge-2.0.14..mtg-player` über
die drei Verzeichnisse: 376 × `A`, 2 × `R100`, kein einziges `M`). Gleichzeitig liegen 2536 Skripte
upstream in einer neueren Fassung als bei uns; darin stecken Korrekturen an Karten, die bei uns heute
falsch spielen. Der einzige echte Grund, etwas nicht zu übernehmen, ist, dass unsere Engine es nicht
bauen kann – und genau das stellt die Prüfung fest, statt es zu vermuten.

Umbenennungen und Löschungen werden mitgezogen, weil sonst derselbe Kartenname zweimal in der Datenbank
steht (beim letzten Abgleich mussten `sita_varma_masker_racer.txt` und `winter_cursed_raider.txt` von
Hand gelöscht werden, genau deswegen).

## 3. Der Prüfmodus

Neuer Betriebsmodus der Bridge, neben den vorhandenen `--bench-one`, `--sparring-one`, `--trimmed-check`:

```
mtgplayer.Main --kartendaten-pruefen
```

Er fährt Forge über `ForgeBoot.init()` hoch und schreibt **einen** JSON-Datensatz nach stdout:

| Feld | Inhalt |
|---|---|
| `karten` | `StaticData.instance().getCommonCards().getUniqueCards().size()` |
| `nichtBaubar` | je Eintrag Kartenname + Grund: Karte steht in der Datenbank, entsteht aber über `CardFactory` im Spielkontext nicht |
| `nichtAuffindbar` | Kartenskript vorhanden (`Name:`-Zeile), Karte aber nicht in der Datenbank – unsere Fassung kann das Skript nicht lesen |
| `doppelteSetCodes` | Set-Code, der in mehr als einer Datei unter `editions/` steht |
| `doppelteNamen` | Kartenname, der in mehr als einer Datei unter `cardsfolder/` steht |
| `parseMeldungen` | was Forge beim Lesen der Kartenskripte gemeldet hat |

Der Modus **urteilt nicht**. Er bricht nicht ab, er schlägt nichts vor, er gibt keinen von null
verschiedenen Rückgabewert wegen eines Befunds. Entschieden wird im Skript (§4). So bleibt er für sich
testbar und auch von Hand brauchbar, wenn man nur wissen will, wie es gerade steht.

`nichtBaubar` ist der Fang, auf den es ankommt: Am 2026-09-25 waren es 37 Karten, die Forge 2.0.14 nicht
bauen kann (`Empower` fehlt in `ApiType`, `FlippedCoinOnce` in `TriggerType`, `CantBeBeamedUp` und
`IgnorePlaneswalkerZeroLoyaltyRule` in `StaticAbilityMode`). Ohne diesen Fang reißt jede Partie beim
Spielaufbau ab, sobald eine solche Karte im Deck liegt.

## 4. Das Abgleich-Skript

`scripts/kartendaten-sync.sh`, aufrufbar ohne GitHub, mit höchstens vier Forge-Starts (drei Prüfläufe in den Schritten 1, 3 und 4, dazu die abschließende Partie in Schritt 5):

1. **Vergleichsmarke.** Prüfmodus auf dem unveränderten Stand laufen lassen.
2. **Übernehmen.** `git fetch upstream`, dann die drei Verzeichnisse aus `upstream/master` in den
   Arbeitsbaum des Submoduls übernehmen (einschließlich Löschungen). Karten auf der Ausschlussliste
   (§5) werden dabei gar nicht erst übernommen.
3. **Prüfen und aussortieren.** Prüfmodus laufen lassen, jeden Befund seiner Datei zuordnen, die Datei
   entfernen und den Eintrag auf die Ausschlussliste setzen. Zugeordnet wird je nach Art des Befunds
   unterschiedlich: Kartennamen (`nichtBaubar`, `nichtAuffindbar`, `doppelteNamen`) über die
   `Name:`-Zeile der Dateien unter `cardsfolder/`, Set-Codes (`doppelteSetCodes`) über die
   Code-Angabe der Dateien unter `editions/`. Bei einem doppelten Eintrag fliegt die **neu
   hinzugekommene** Datei, nicht die bestehende.
4. **Gegenprobe.** Prüfmodus ein letztes Mal.
5. **Abschließende Partie.** Eine kopflose Partie mit Precon-Decks (vorhandene Bench-Mechanik), damit
   Start und Spielablauf insgesamt belegt sind.

Ergebnis ist ein Bericht (Markdown) plus der Zustand des Arbeitsbaums. Das Skript schreibt **keinen**
Commit und schiebt nichts – das macht der Ablauf (§6).

**Keine laufende Karte darf verloren gehen.** Fällt eine Datei durch, die es vor dem Abgleich schon gab
(upstream hat sie nur geändert), wird unsere bisherige Fassung wiederhergestellt statt gelöscht - sie lief
ja. Diese Karte kommt NICHT auf die Ausschlussliste, sondern in einen eigenen Abschnitt des Berichts
("bleibt auf unserer bisherigen Fassung"). Gelöscht und ausgeschlossen werden nur wirklich neue Dateien.
Ohne diese Regel verschwände eine bei uns spielbare Karte, sobald upstream ihr ein Schlüsselwort beibringt,
das unsere Fassung nicht kennt - genau der Fall, der beim Abgleich 2026-09-25 bei 37 von 394 Karten eintrat.

**Abbruchbedingungen.** Das Skript meldet Fehlschlag, wenn nach Schritt 4 noch ein Befund offen ist, wenn
`karten` unter die Vergleichsmarke aus Schritt 1 gefallen ist, oder wenn ein Kartenname fehlt, der vorher
da war. Die Zahl allein genügt nicht: eine verschwundene Karte und zwei neue heben sie, der Verlust bliebe
unbemerkt.

**Löschungen bei upstream sind erlaubt.** Fehlt ein Kartenname nachher, entscheidet `upstream/master`: steht
er dort auch nicht mehr, hat upstream die Karte gestrichen - das ist dessen Entscheidung, die der Abgleich
übernimmt. Es ist kein Fehlschlag; der Bericht führt die Karte unter „von upstream entfernt", und die
Kartenzahl darf um genau so viele fallen. Steht der Name in `upstream/master` noch, hat der Abgleich die
Karte selbst verloren: Fehlschlag mit Rücknahme. (Anders wäre der erste echte Lauf garantiert gescheitert.) Eine Umbenennung
ist keine Löschung, denn der Kartenname in der Datei bleibt derselbe.

## 5. Die Ausschlussliste

`docs/kartendaten-ausgeschlossen.txt` im Hauptprojekt: je Zeile ein Kartenname, der Grund und das Datum
der Aufnahme.

Ohne sie käme jede Woche derselbe Kartensatz herein und flöge wieder heraus – der Bericht wäre jedes Mal
gleich laut und niemand läse ihn mehr. Mit ihr nennt der Bericht nur, was **neu** dazugekommen ist - dazu die Zahl der Einträge, die schon
vorher auf der Liste standen, mit Verweis auf sie: diese Karten werden nicht hereingelassen und fehlen deshalb
im Zweig. Das braucht ein zweiter Lauf vor dem Merge, dessen Text den des ersten überschreibt. Die
Liste ist zugleich die Antwort auf die Frage „was bekomme ich durch einen Fassungswechsel zurück?" (§8).

Die Liste gilt für unsere Engine-Fassung. Beim Fassungswechsel wird sie geleert und neu aufgebaut.

## 6. Der Ablauf „Kartendaten"

`.github/workflows/kartendaten.yml` im Hauptprojekt.

- **Auslöser:** wöchentlich (montags früh) und `workflow_dispatch`.
- **Läufer:** `ubuntu-latest`. Hier wird nichts gepackt, also braucht es kein Windows.
- **Schritte:** Auschecken mit Submodul → JDK 17 → Forge bauen → Bridge bauen → `kartendaten-sync.sh` →
  bei Erfolg und tatsächlicher Änderung: Zweig in den Fork schieben und Pull Request öffnen.
- **Mehrfachläufe:** Es gibt genau einen Zweig (`kartendaten-abgleich`). Ein weiterer Lauf schiebt
  denselben Zweig nach und schreibt den Berichtstext des offenen Pull Requests neu, statt einen zweiten
  zu öffnen.
- **Commit im Fork:** Englisch, upstream-tauglich, ohne Verweis auf dieses Projekt – wie jeder Commit
  dort. Der Bericht gehört in den Pull Request, nicht in die Commit-Nachricht.

**Zugang.** Ein Ablauf darf nicht ohne Weiteres in ein anderes Repo schreiben. Nötig ist ein fein
granuliertes Token auf `Haste-MC/forge` mit **Contents: Schreiben** und **Pull requests: Schreiben**,
hinterlegt als Geheimnis in `MTG-Player`. Kevin legt es an; es taucht in keiner Sitzung auf.

## 7. Der Ablauf „Submodul anheben"

`.github/workflows/submodul.yml` im Hauptprojekt, nur `workflow_dispatch`.

Holt den aktuellen Stand von `Haste-MC/forge` (Branch `mtg-player`), hebt den Submodul-Zeiger, lässt die
Bridge-Testsuite laufen und öffnet damit einen Pull Request in `MTG-Player`. Dafür reicht das eingebaute
Token – kein weiteres Geheimnis.

Ohne dieses Stück bliebe die Automatik auf halbem Weg stehen: der Fork wäre aktuell, das Paket aber
nicht, und der unangenehmste Handgriff bliebe liegen.

## 8. Der Fassungsweg

> **Eigener Plan, später.** Dieser Abschnitt hängt sachlich an nichts aus dem Rest und wird erst
> gebraucht, wenn upstream tatsächlich ein Release über `forge-2.0.14` veröffentlicht – heute gibt es
> keines. Der erste Umsetzungsplan deckt §1–§7 und §9–§12 ab; §8 bekommt einen eigenen, wenn es so weit
> ist. Der Abschnitt bleibt hier stehen, weil er die Gegenseite beschreibt, gegen die der Kartenweg
> abgegrenzt ist (§1).

**Nachricht.** `.github/workflows/forge-release.yml`, wöchentlich: fragt die GitHub-API nach dem
neuesten Release von `Card-Forge/forge` und vergleicht es mit unserer Basis. Ist es neuer, entsteht
**ein** Issue – und kein zweites für dasselbe Release.

**Handarbeit mit Hilfe.** `scripts/forge-fassung.sh <fassung>` nimmt ab, was stumpf ist: Tag holen, in
`mtg-player` zusammenführen, Konflikte **auflisten statt lösen**, `mvn versions:set
-DnewVersion=<fassung>-mtgplayer`, Bridge-POM nachziehen, bauen, Bridge-Testsuite laufen lassen.

Am Ende druckt es die Liste dessen, was nur ein Mensch beurteilen kann:

- Tun unsere KI-Reparaturen nach dem Zusammenführen noch, was sie sollen? (Szenentests `GoadTest`,
  `ControlDonationSceneTest`, `MeldTitaniaTest`, `PhagePlayerLossTest`, `SimCopierTest`)
- Welche Karten der Ausschlussliste (§5) baut die neue Fassung jetzt? Die Liste wird geleert.
- Bench-Vergleich gegen die alte Fassung, bevor das in ein Paket geht.

## 9. Fehlerverhalten

Durchweg gilt: **kein halbes Ergebnis.**

| Fall | Verhalten |
|---|---|
| upstream nicht erreichbar | Ablauf schlägt fehl, nichts wurde angefasst |
| nichts Neues bei upstream | kein Pull Request, Ablauf grün, eine Zeile im Protokoll |
| Befund nach dem Aussortieren noch offen | **kein** Pull Request, Ablauf schlägt fehl, voller Befund als Artefakt |
| Kartenzahl unter der Vergleichsmarke, abzüglich der von upstream entfernten Karten | wie oben |
| Karte nach dem Abgleich verschwunden, in `upstream/master` aber noch vorhanden | wie oben (Fehlschlag mit Rücknahme) |
| Karte von upstream gestrichen | **kein** Fehlschlag; Abschnitt „von upstream entfernt" im Bericht, Kartenzahl darf entsprechend fallen |
| Pull Request schon offen | derselbe Zweig wird nachgeschoben, Berichtstext neu geschrieben |

## 10. Tests

- **Prüfmodus gegen unsere heutigen Daten:** Befund muss sauber sein. Das ist zugleich ein Netz für
  alles, was später an den Kartendaten geschieht.
- **Prüfmodus gegen absichtlich kaputte Daten:** ein unlesbares Kartenskript in einer Kopie der
  Ressourcen muss als `nichtAuffindbar` gemeldet werden, eine Editionsdatei mit doppeltem Set-Code als
  `doppelteSetCodes`. Ohne diesen zweiten Test beweist der erste nichts – er wäre auch grün, wenn der
  Modus gar nichts prüft.
- **Zuordnung Kartenname → Datei:** eigener Test, ohne Forge. An dieser Zuordnung hängt, dass die
  richtige Datei entfernt wird.
- Die Tests schreiben nicht nach `~/.mtg-player` und gehen nicht ins Netz (eigenes `mtgplayer.data`,
  upstream-Abruf nur im Skript).

## 11. Grenzen, die bleiben

- Die Prüfung belegt, dass jede Karte **entsteht**, nicht dass sie **richtig spielt**. Ein baubares
  Skript mit falscher Regelumsetzung fällt hier nicht auf.
- Die abschließende Partie läuft mit Precon-Decks und sucht nicht gezielt nach neuen Karten.
- Der Kartenabgleich bringt keine Engine-Korrekturen. Was an Forges Java kaputt ist, bleibt bis zum
  Fassungswechsel kaputt – oder wir reparieren es wie bisher selbst im Fork.
- **Latente Falle für die Fassungswechsel-Arbeit:** `CardFiles` liest JEDE Datei unter `cardsfolder/`, Forge
  dagegen nur `*.txt` und überspringt Punkt-Verzeichnisse. Heute ist das harmlos, weil unsere Forge-Fassung als
  Entwicklungsfassung gilt und deshalb auch `upcoming/` lädt. Bekommt der Forge-Bau beim Fassungswechsel ein
  versioniertes Manifest, lädt Forge `upcoming/` nicht mehr, und rund 640 Karten von dort fielen als „nicht
  auffindbar" durch - der Lauf wäre dauerhaft rot. Wer die Fassung wechselt, muss `CardFiles` (und das
  Namensverzeichnis im Skript) auf Forges Regel angleichen: nur `*.txt`, keine Punkt-Verzeichnisse, und
  `upcoming/` nur, wenn Forge es lädt.

## 12. Nicht enthalten

- Automatischer Fassungswechsel.
- Abgleich von `lists`, `formats`, `adventure`, `quest` und allem anderen unter `res/`.
- Rückgabe unserer eigenen KI-Reparaturen an upstream.
