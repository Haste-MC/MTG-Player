# Verdeckte Karten, die ich sehen darf – Design

Stand: 2026-10-08. Betrifft `bridge/` (Serialisierung) und `web/` (Darstellung).

Kevin am 2026-10-08: "mit karten wie spinerock knoll, die karten exilen die nur du weißt oder face
down, ich kann nicht sehen was unter face down liegt. das ist bissl problematisch weil ich will ja
meine sachen wissen" – und auf Nachfrage: **jedes** Face-down, nicht nur Hideaway.

## Befund

Die Bridge verbirgt nichts. Sie fragt den falschen Zustand ab.

`StateSerializer.cardSnap` nimmt `cv.getCurrentState()`. Bei einer verdeckten Karte IST das die
Rückseite: Name leer, Bildschlüssel `t:hidden`. Die echte Karte liegt daneben in
`cv.getAlternateState()` und ist vollständig da. Gemessen (`VerdecktDiagTest`, zwei Spieler,
Scene):

| Fall | `canFaceDownBeShownTo(ich)` | `(Gegner)` | `getAlternateState()` |
|---|---|---|---|
| Exil verdeckt, ohne Erlaubnis | nein | nein | Grizzly Bears |
| Exil verdeckt, mit Erlaubnis (Hideaway) | **ja** | nein | Grizzly Bears |
| Eigene verdeckte Kreatur (Morph/Manifest) | **ja** | nein | Hill Giant |

Bemerkenswert an Zeile drei: für die eigene verdeckte Kreatur gilt die Erlaubnis **ohne** weiteres
Zutun – der Beherrscher weiß immer, was er da liegen hat. Forge beantwortet die Frage also für jede
Herkunft (Morph, Manifest, Hideaway, Foretell, Cloak) an einer Stelle, und
`AbstractGuiGame.mayFlip(cv)` ist der fertige Aufruf dafür.

Nebenbei erklärt das die Logzeile vom selben Tag: `kein Bild: Key 't:hidden'`. Das war kein
fehlendes Bild, das war eine verdeckte Karte, deren Rückseite bei Scryfall gesucht wurde.

## Entscheidung

**Auf dem Tisch bleibt die Rückseite.** Sie trägt nur ein kleines Auge-Zeichen: "die darfst du
ansehen". Anfahren zeigt die echte Karte groß in der Vorschau und als Text in der Seitenleiste.

Begründung (Kevins Wahl aus drei Vorschlägen): der Tisch zeigt damit weiter die Wahrheit. Eine
verdeckte Kreatur kämpft als 2/2, und der Gegner sieht sie nicht – ein Brett, das die echte Karte
zeigt, lädt dazu ein, beides zu vergessen.

## Datenformat

`CardSnap` bekommt ein zusätzliches, normalerweise leeres Feld:

```java
/** Was unter einer verdeckten Karte liegt - nur gefuellt, wenn der Betrachter sie ansehen darf. */
public record Verdeckt(String name, String imageKey, String typeLine, String manaCost, String text) { }
```

- Die Felder des Schnappschusses bleiben unverändert die des **Brettzustands** (2/2, getappt,
  Anhänge). Nur die Identität kommt aus dem anderen Zustand.
- Gefüllt genau dann, wenn `cv.isFaceDown()` **und** `mayFlip(cv)`. Für den Gegner bleibt es leer;
  im Zuschauer-Modus ist es gefüllt, weil dort ohnehin alles sichtbar ist (so verhält sich
  `mayView` heute schon).
- `CardSnap.hidden(id)` (gar keine Sicht) bleibt unverändert ohne alles.

## Darstellung

- `CardBox`: bei verdeckter Karte **mit** Zusatz weiterhin die Rückseite, aber mit Auge-Marke und –
  neu – mit den Anfahr-Ereignissen, die der Zweig heute gar nicht setzt.
- `CardDetail` und die große Vorschau: zeigen die Identität aus dem Zusatz statt gar nichts.
- Ohne Zusatz (fremde verdeckte Karte) ändert sich nichts: blanke Rückseite, kein Hover.

## Nicht enthalten

- Ein eigenes Bild für Spielsteine ohne Scryfall-Eintrag (`t:hidden` als echter Kartenrücken).
- Irgendeine Änderung daran, WER etwas sehen darf. Das entscheidet Forge, und das bleibt so.

## Prüfung

- `VerdecktDiagTest` wird zum echten Test: Exil mit und ohne Erlaubnis, eigene und fremde verdeckte
  Kreatur, jeweils über `StateSerializer.cardSnap` statt über die Forge-Interna.
- Oberfläche über Screenshots aus einer echten Partie, wie am 2026-10-08.
