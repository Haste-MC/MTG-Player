# MTG-Player als herunterladbare App – Design

Stand: 2026-09-26. Kevins Auftrag: „können wir starten den player in eine App oder sowas wie forge? also halt
das andere den einfach downloaden können und nutzen ohne complicationen mit console befehlen oder ähnliches,
mit update scripts oder so".

Entschieden: **Windows zuerst**, **ZIP zum Entpacken** (keine Installation), **eigenes Fenster über den
vorhandenen Browser**, **Selbst-Aktualisierung** über GitHub-Releases.

## 1. Was der Nutzer erlebt

ZIP herunterladen, in einen eigenen Ordner entpacken, `MTG-Player.exe` doppelklicken. Es öffnet sich ein
Fenster mit der Lobby – kein Java installieren, keine Konsole, kein Nachladen von Kartendaten. Beim ersten
Start dauert es etwas, weil Forge rund 30.000 Karten einliest; solange steht ein Ladehinweis im Fenster.
Deinstallieren heißt: Ordner löschen. Eigene Daten (Decks, Partien, Kartendaten-Auswertung) liegen getrennt
unter `%USERPROFILE%\.mtg-player` und überleben jedes Update.

## 2. Was im Paket steckt

```
MTG-Player/
  MTG-Player.exe          von jpackage erzeugter Starter
  runtime/                mitgelieferte Java-Laufzeit (jpackage)
  app/                    bridge.jar + abhängige Bibliotheken (Forge) + web/ (gebautes UI)
  assets/res/             der benutzte Teil von Forges Daten
  LICENSE                 GPLv3 (Pflicht, siehe §8)
  MODIFICATIONS.txt       Hinweis auf den veränderten Forge (Pflicht, siehe §8)
  version.txt             Fassung, die die App über sich selbst kennt
```

Aus Forges `res` (463 MB) kommt nur mit, was das Spiel benutzt: `cardsfolder`, `editions`, `tokenscripts`,
`formats`, `lists`, `blockdata`, `effects`, `defaults`, `ai`, `setlookup`, `licenses`, die einzelne Datei
`languages/en-US.properties` (Forges Localizer laedt dieses Bundle immer als Englisch-Rueckfall; ohne sie
startet Forge gar nicht – im Bau nachgewiesen) und – für die mitgelieferten Decks – `quest/commanderprecons`. Draußen bleiben `adventure` (154 MB), `quest` bis auf die
Precons (39 MB), `languages` (55 MB), `music`, `skins`, `sound`, `conquest`, `puzzle`, `draft`, `sealed`,
`cube`, `geneticaidecks`, `deckgendecks`. **Diese Liste ist eine Annahme und muss im Bau geprüft werden**:
Start mit ausgedünntem `res`, Kartenzahl vergleichen, alle Precons laden, eine KI-Partie bis zum Ende spielen.
Fehlt etwas, kommt der Ordner zurück in die Liste.

## 3. Wie die App startet

Der Starter ruft `mtgplayer.Main` mit `-Dmtgplayer.app=true` und setzt `mtgplayer.assets` auf `assets/` im
App-Ordner sowie `mtgplayer.web` auf `app/web`. **`mtgplayer.data` wird bewusst nicht gesetzt**: Nur dann legt
`ForgeBoot` ein isoliertes Assets-Verzeichnis mit einem **Symlink** an, und Symlinks brauchen unter Windows
Sonderrechte. Ohne die Property schreibt Forge seine `forge.profile.properties` in den App-Ordner, der bei
einem entpackten ZIP dem Nutzer gehört.

Neu im `mtgplayer.app=true`-Modus:

- **Freie Ports statt fester.** 8080/8081 können belegt sein. Die App probiert die Vorgabe und weicht sonst
  auf freie Ports aus; die geöffnete Adresse richtet sich nach dem tatsächlich belegten Port.
- **Fenster öffnen.** Nach dem Start des HTTP-Servers öffnet die App den Browser im App-Modus:
  `msedge --app=<url>`, sonst `chrome --app=<url>`, sonst der Standardbrowser über `Desktop.browse`. Edge ist
  auf jedem Windows 10/11 vorhanden, der Rückfall greift also praktisch nie – er existiert, damit ein fehlender
  Browser die App nicht unbenutzbar macht.
- **Ordner nicht beschreibbar.** Lässt sich im App-Ordner nicht schreiben (z. B. nach `C:\Programme` entpackt),
  bricht die App nicht wortlos ab, sondern sagt im Fenster: in einen eigenen Ordner entpacken.
- Der Server startet **vor** Forges Kartendatenbank, damit die Seite sofort lädt und den Ladehinweis zeigen
  kann; die Lobby erscheint, sobald die WebSocket-Verbindung antwortet.

## 4. Wie gebaut wird

Ein GitHub-Workflow `.github/workflows/release.yml`, ausgelöst durch einen Tag `v*`, auf `windows-latest`:

1. Auschecken mit Submodul (Fork `Haste-MC/forge`).
2. JDK 17 (Temurin) einrichten.
3. Forge bauen: `mvn -q -pl forge-gui -am install -DskipTests -Dcheckstyle.skip`.
4. Bridge bauen und Abhängigkeiten sammeln (`dependency:copy-dependencies`).
5. Web-UI bauen: `npm ci && npm run build` in `web/`.
6. `res` ausdünnen (Liste aus §2), Paketordner zusammenstellen, `LICENSE`, `MODIFICATIONS.txt` und
   `version.txt` hineinlegen.
7. `jpackage --type app-image` – erzeugt Starter und Laufzeit. Für ein app-image braucht Windows **kein** WiX.
8. ZIP packen, SHA-256 berechnen, beides an das GitHub-Release hängen.

Die Fassung kommt aus dem Tag (`v1.2.0` → `1.2.0`) und landet in `version.txt` **und** im Jar-Manifest, damit
die laufende App ohne Dateizugriff weiß, welche Fassung sie ist.

## 5. Selbst-Aktualisierung

- Beim Start fragt die Bridge im Hintergrund `https://api.github.com/repos/Haste-MC/MTG-Player/releases/latest`
  ab (ein Aufruf, 10 s Zeitgrenze, jeder Fehler wird stumm verschluckt – ein Ausfall bei GitHub darf das Spiel
  nie behindern). Ist `tag_name` neuer als die eigene Fassung, schickt sie dem Client eine Nachricht.
- Die Lobby zeigt daraufhin einen Hinweis „Version 1.3.0 verfügbar" mit den Knöpfen **Aktualisieren** und
  **Später**. Nichts passiert ungefragt.
- **Aktualisieren** lädt das ZIP in `%TEMP%`, prüft die SHA-256 gegen die Prüfsumme aus dem Release, entpackt
  es dort, schreibt ein `update.cmd` daneben und startet es; die App beendet sich. Das Skript wartet, bis der
  Prozess weg ist, benennt den alten Ordner in `<Name>.old` um, schiebt den neuen an seine Stelle, startet die
  App und löscht `.old` erst nach erfolgreichem Start.
- Schlägt Herunterladen, Prüfsumme oder Entpacken fehl, wird **nichts** angefasst und die Lobby sagt, was
  schiefging. Scheitert das Verschieben, stellt das Skript den alten Ordner zurück. Ein halb ausgetauschter
  Ordner darf nicht entstehen.
- `%USERPROFILE%\.mtg-player` wird vom Update nie angefasst.

## 6. Protokoll

```json
{ "type": "version", "current": "1.2.0", "latest": "1.3.0", "url": "https://…/MTG-Player-1.3.0-win.zip",
  "sha256": "…", "notes": "…" }
```
wird nach dem Verbinden geschickt, wenn eine neuere Fassung vorliegt (sonst gar nichts).
`{ "type": "applyUpdate" }` vom Client startet §5; Fortschritt und Fehler kommen als
`{ "type": "updateState", "state": "laden|pruefen|entpacken|neustart|fehler", "text": "…" }`.

## 7. Grenzen, die bleiben

- **SmartScreen/Virenscanner** warnen bei einer unsignierten `.exe` („unbekannter Herausgeber"). Dagegen hilft
  nur ein gekauftes Zertifikat. Die Release-Seite erklärt den Weg über „Weitere Informationen → Trotzdem
  ausführen"; wegzaubern lässt es sich nicht.
- **Erster Start** dauert (Kartendatenbank). Der Ladehinweis ist die Antwort darauf, nicht eine Beschleunigung.
- **Kartenbilder** kommen weiterhin live von Scryfall; ohne Internet läuft das Spiel, zeigt aber Text statt
  Bildern. Deck-Import und Kartenvorschläge brauchen ebenfalls Internet.
- **Sparring** startet Kindprozesse mit derselben Laufzeit (`ChildJvm` nimmt `java.home`), das trägt im Paket –
  muss aber im ersten Bau einmal wirklich ausprobiert werden, nicht nur angenommen.

## 8. Lizenz im Paket

Das Paket enthält Forge, also gilt GPLv3 für das Ganze: `LICENSE` (GPLv3) und ein `MODIFICATIONS.txt` mit dem
Hinweis auf den veränderten Forge samt Basis-Tag und Fundort des Quelltexts liegen im Paketordner, und die
Release-Seite verlinkt Quelltext-Repo und Fork. Das Repo trägt beides bereits (`LICENSE`, `README`-Abschnitt
„Lizenz und Herkunft", `forge/MODIFICATIONS.md`) – der Bau kopiert sie nur mit.

## 9. Tests

- **Bau:** Der Workflow läuft bei jedem Tag durch; ein Fehlschlag veröffentlicht nichts.
- **Ausgedünntes `res`:** Ein Test im Paketbau startet die App-Fassung headless, prüft die Kartenzahl gegen den
  ungekürzten Stand, lädt jedes Precon und spielt eine KI-Partie zu Ende.
- **Port-Ausweichen:** Test, der den Vorgabeport belegt und prüft, dass die App einen anderen nimmt und die
  richtige Adresse meldet.
- **Fassungsvergleich:** Tests für „neuer", „gleich", „älter", fehlerhafte Angabe, fehlende Antwort.
- **Update-Ablauf:** Tests gegen eine eingesetzte Quelle (kein echter Netzabruf): falsche Prüfsumme bricht ab,
  Abbruch lässt den Ordner unverändert, Erfolg schreibt das Skript.
- **Von Hand einmal am Stück:** ZIP auf einem Windows-Rechner entpacken, starten, Partie spielen, Sparring
  starten, Update auf eine neuere Testfassung durchführen. Was nur automatisch geprüft wurde, gilt hier nicht
  als geprüft.

## 10. Nicht enthalten

- Mac und Linux (später; der Workflow ist so gebaut, dass eine zweite Plattform dazukommen kann).
- Installer mit Startmenü-Eintrag, Signatur, Auto-Start.
- Mitgelieferte Kartenbilder, eigene Decks oder Partiedaten – eine frische Installation startet leer.
- Mehrspieler über Netzwerk. Die App bleibt ein lokaler Tisch gegen die KI.
