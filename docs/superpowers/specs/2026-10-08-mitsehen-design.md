# Mehrere Browser am selben Tisch – Design

Stand: 2026-10-08. Betrifft `bridge/src/.../server/WsServer.java`, `Bridge`, `Main` und `web/`.

## Anlass

Kevin wollte zusehen, während eine andere Claude-Sitzung im eingebauten Browser eine Partie spielt.
Beide Seiten zeigten dauerhaft „Verbinde mit der Bridge …", in beiden Konsolen scheiterte scheinbar
jeder Versuch.

Die Bridge war gesund (`curl http://127.0.0.1:8081/` antwortet mit „404 WebSocket Upgrade Failure",
beide Ports über IPv4 und IPv6 erreichbar). Die Ursache ist eine **Schaukel**: `WsServer` hält genau
einen Klienten und wirft bei einer neuen Verbindung die alte raus, und `web/src/ws.ts` verbindet
nach `onclose` eine Sekunde später neu. Zwei offene Tabs werfen sich damit endlos gegenseitig raus.

## Entscheidung

Die Bridge bedient **mehrere** Browser. Genau einer davon **steuert**, die übrigen **sehen zu**.

**Wer steuert:** der erste verbundene Klient. Kommt ein weiterer dazu, sieht er zu — das ist Kevins
Fall: die spielende Sitzung ist schon dran, er setzt sich daneben. Geht der Steuernde, übernimmt der
am längsten wartende Zuschauer, damit kein Tisch ohne Hand zurückbleibt.

**Übernehmen:** ein Zuschauer kann die Steuerung ausdrücklich an sich ziehen (Nachricht
`takeControl`, Knopf in der Oberfläche). Ohne das wäre man je nach Reihenfolge des Verbindens
festgelegt — und wer zuerst da war, ist nicht zwingend der, der spielen will.

Bewusst NICHT „der Neueste steuert" (das heutige Verhalten): genau daraus entsteht die Schaukel,
sobald zwei Tabs offen sind.

## Was sich ändert

**`WsServer`**
- `clients`: eine Liste statt eines Feldes, in Verbindungsreihenfolge. Niemand wird mehr geschlossen.
- `send` geht an alle offenen Klienten; ein Fehler bei einem darf die übrigen nicht kosten.
- `inbound` bekommt nur, was vom **Steuernden** kommt. Ausnahme: `takeControl`, das darf jeder.
- `onClientGone` meldet erst, wenn der **letzte** Klient weg ist (App-Modus: Fenster zu, siehe
  `IdleExit`) — heute ist das derselbe Fall, weil es nur einen gibt.
- Wechselt die Steuerung, bekommen **alle** Klienten eine `role`-Nachricht.

**Protokoll**
- Neue Nachricht an den Browser: `{"type":"role","control":true|false,"watchers":n}`.
- Neue Nachricht vom Browser: `{"type":"takeControl"}`.

**Oberfläche**
- Zuschauer sehen eine Marke „zuschauen" samt Knopf „Steuerung übernehmen"; der Steuernde sieht
  nur, wie viele zusehen.
- Eingaben bleiben sichtbar, aber gesperrt, solange man zusieht. Ein Knopf, der nichts tut, ist
  schlimmer als ein sichtbar gesperrter.

## Grenzen, die bleiben

- Alle sehen **dieselbe** Sicht (die des menschlichen Sitzes bzw. die des Zuschauer-Modus). Eine
  eigene Perspektive je Browser gäbe es erst mit mehreren `WebGuiGame`-Instanzen; das ist eine
  andere, größere Sache.
- Kein Zugriffsschutz. Wer den Port erreicht, kann die Steuerung übernehmen — die Bridge hört auf
  `0.0.0.0`, damit Windows sie unter WSL2 erreicht, und das war schon vorher so.

## Prüfung

- `WsServerTest`: zwei Klienten, beide bekommen den Zustand; nur der Steuernde wird durchgereicht;
  `takeControl` dreht es um; geht der Steuernde, rückt der nächste nach; `onClientGone` erst beim
  letzten.
- Ein Durchlauf mit zwei echten Browsern über Playwright: beide bleiben verbunden (keine Schaukel),
  der zweite sieht den Tisch und kann nicht klicken, bis er übernimmt.
