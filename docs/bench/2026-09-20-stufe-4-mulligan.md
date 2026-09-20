# Stufe 4: Mulligan-Heuristik (Farben, Kurve, London-Bottoming) – Bench

Datum: 2026-09-20. Fork-Commit aa851d40 (`MULLIGAN_CHECK_COLORS`, siehe `docs/forge-fork.md`). Zwei Spiegel,
je 40 Seeds ab 1, Standard-KI beiderseits, Kindprozess je Spiel, Zugdeckel 200, `--timeout 5` (für die Standard-KI
ohne Wirkung). Sitz A = `std:Default` (neue Heuristik), Sitz B = `std:Legacy` (`Legacy.ai` = `Default.ai` mit
`MULLIGAN_CHECK_COLORS=false`, also Zeile für Zeile der alte Mulligan-Code). Im Spiegel unterscheidet sich zwischen
den Sitzen nur der Mulligan – die Siegquote von A ist die Effektgröße.

Zusätzlich je Deck eine **Kontrolle** mit denselben 40 Seeds, `std:Legacy` gegen `std:Legacy`: sie misst, wie viel
Sitz A allein durch Seed-/Sitzreihenfolge-Glück gewinnt. Ohne sie wäre die Abzan-Zahl unten grob überinterpretiert
worden (siehe Einordnung). Rohberichte: `2026-09-20-stufe-4-roh-mulligan-{abzan,ahoy}-{spiegel,kontrolle}.md`.

| Lauf | A : B | Siegquote A | 95-%-Intervall | Nichtstun A / B | Ø Züge | Mulligans A / B | A hält 7 / 6 / 5 |
|---|---|---|---|---|---|---|---|
| **Abzan-Spiegel** (Default vs Legacy) | **28 : 12** | 70,0 % | 54,6–81,9 % | 0 / 1 | 18,1 | 18 / 7 | 25 / 12 / 3 |
| Abzan-Kontrolle (Legacy vs Legacy) | 25 : 15 | 62,5 % | 47,0–75,8 % | 0 / 0 | 17,9 | 11 / 7 | 31 / 7 / 2 |
| **Ahoy-Spiegel** (Default vs Legacy) | **24 : 16** | 60,0 % | 44,6–73,7 % | 0 / 1 | 16,9 | 15 / 7 | 27 / 11 / 2 |
| Ahoy-Kontrolle (Legacy vs Legacy) | 22 : 18 | 55,0 % | 39,8–69,3 % | 0 / 1 | 17,2 | 10 / 7 | 31 / 8 / 1 |

„Nichtstun" = Sitz spielt ≥ 5 Länder und wirkt ≤ 2 Zauber (neue Bench-Kennzahl `fewSpells`, aus den Logzeilen
`<Sitz> played …`/`<Sitz> cast …`). „Mulligans" = Zeilen `<Sitz> has mulliganed` über alle 40 Spiele. Keine
Abstürze, keine Unentschieden, keine Sim-Fehler (Standard-KI).

## Paarweiser Vergleich (gleiche Seeds, Spiegel gegen Kontrolle)

Ein Spiel ohne Mulligan auf beiden Sitzen läuft im Spiegel **identisch** zur Kontrolle (geprüft: Log von Spiel 3
Abzan mit Default/Default, Legacy/Legacy und Default/Legacy byte-gleich; im Vergleich unten stimmen alle Spiele
ohne Mulligan in Sieger und Zugzahl mit der Kontrolle überein). Der Effekt der Heuristik kann also nur in den
Spielen stecken, in denen mindestens ein Sitz einen Mulligan nahm – die übrigen sind Seed-Glück, das beide Läufe
gleich verteilt.

| Deck | Spiele ohne Mulligan (identisch) | A gewinnt dort | Spiele mit Mulligan | Spiegel A : B | Kontrolle A : B (gleiche Seeds) | Umgekippt Spiegel vs Kontrolle |
|---|---|---|---|---|---|---|
| Abzan | 21 | 16 : 5 | 19 | **12 : 7** | 9 : 10 | 4 Gewinne, 1 Verlust |
| Ahoy | 26 | 14 : 12 | 14 | **10 : 4** | 8 : 6 | 3 Gewinne, 1 Verlust |
| Summe | 47 | 30 : 17 | 33 | **22 : 11** | 17 : 16 | **7 Gewinne, 2 Verluste** |

Nur die Spiele, in denen A selbst mulliganierte: Abzan 15 (Spiegel 8 : 7, Kontrolle auf denselben Seeds 5 : 10),
Ahoy 13 (Spiegel 9 : 4, Kontrolle 7 : 6). In der Kontrolle nahm Legacy in 9 bzw. 9 dieser Spiele ebenfalls einen
Mulligan – dort unterscheidet sich nur das London-Bottoming (welche Karte nach unten geht).

## Einordnung

- **Richtung stimmt, Größe klein.** Beide Spiegel liegen über 50 %, aber die Untergrenze der Intervalle tut es
  nicht (54,6 % Abzan ja, 44,6 % Ahoy nein). Die Kontrolle zeigt, warum die Rohzahl täuscht: mit identischen
  Profilen gewinnt Sitz A in diesem Seed-Satz schon 62,5 % (Abzan) bzw. 55 % (Ahoy). Vom 70 %-Ergebnis im
  Abzan-Spiegel sind also etwa 7,5 Punkte der Heuristik zuzuschreiben, in Ahoy etwa 5 Punkte. Die Spec-Erwartung
  „Untergrenze ≥ 50 % in beiden" ist damit **nicht** belegt – sie war für einen Mulligan-Effekt bei 40 Spielen auch
  nicht erreichbar (Abzan: 21 von 40 Spielen sind von der Änderung gar nicht berührt).
- **Paarweise ist das Bild klarer:** in den 33 Spielen, in denen die Änderung überhaupt wirkte, gewinnt A 22 : 11
  gegen 17 : 16 in der Kontrolle; 7 Spiele kippen zu A, 2 gegen A (Vorzeichentest p ≈ 0,09 einseitig – Hinweis,
  kein Beweis). Das ist der ehrliche Effektnachweis: +5 Netto-Siege auf 80 Spiele.
- **Mulligan-Häufigkeit:** die neue Heuristik nimmt ~60 % mehr Mulligans (18 statt 11, 15 statt 10) und hält
  öfter 6 statt 7. Sie kostet dabei nicht: die Spiele, die sie neu wegwirft, gewinnt sie häufiger als die Kontrolle
  dieselben Hände behält (Abzan 8 : 7 gegen 5 : 10).
- **Nichtstun sinkt nicht messbar** – es gab nichts zu senken: mit der Standard-KI und diesen beiden Precons tritt
  das Muster (≥ 5 Länder, ≤ 2 Zauber) in 0–1 von 40 Spielen je Sitz auf, alle auf dem Legacy-Sitz B, nie auf A.
  Der Befund aus `2026-09-20-stufe-2-nichtstun.md` stammte aus Sim-KI-Spielen mit Ahoy; die Kennzahl ist jetzt im
  Bench und kann dort nachgemessen werden.
- **Erster Spieler:** in Abzan gewinnt der Startspieler 26 : 14 (Spiegel) bzw. 23 : 17 (Kontrolle), in Ahoy
  18 : 22. Sitz A war in allen vier Läufen 22-mal Startspieler – ein Teil des A-Vorteils in der Kontrolle.

## Einschränkungen

Zwei Precons, ein Profilpaar, 40 Seeds je Lauf. Für einen belastbaren Unterschied von 5–8 Punkten bräuchte es
mehrere hundert Spiele; der paarweise Vergleich hebt die Aussagekraft, ersetzt das aber nicht. Die Heuristik
prüft Farbabdeckung als Menge (ein Any-Color-Land „deckt" auch `{U}{U}`), zählt reflektiertes Mana
(z. B. Länder, die nachbilden, was Gegner erzeugen können) als beliebige Farbe und ignoriert Mana mit späterer
Farbwahl – bewusst optimistisch, damit sie keine Hände wegwirft, die spielbar sind. Standard-KI beiderseits: mit
der Sim-KI (`sim:Default`) ist der Mulligan derselbe Code, die Bewertung danach aber eine andere; nicht gemessen.
Das Profil `Legacy` bleibt als Vergleichsprofil im Lobby-Menü wählbar (und wird von Forges „Random"-Profilwahl
mitgezogen).
