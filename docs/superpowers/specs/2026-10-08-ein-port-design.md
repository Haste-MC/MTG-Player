# Die App mit einem einzigen Port – Design

Stand: 2026-10-08. Betrifft `bridge/src/.../server/` und `web/src/ws.ts`.

## Anlass

Der eingebaute Browser einer anderen Claude-Sitzung erreicht die App nicht. Gemessen an drei
Versuchen:

| Versuch | Seite lädt | WebSocket |
|---|---|---|
| `localhost:8080` | ja | nein |
| `127.0.0.1:8080` | **nein** | – |
| `localhost:8080/?ws=ws://127.0.0.1:8081` | ja | nein |

Dass die Seite über `localhost` lädt, über `127.0.0.1` aber gar nicht, ist der Befund: dieser
Browser hat **eine** Freigabe, für Name und Port der Seite. Ein anderer Name, ein anderer Port –
dicht. Die Bridge trifft daran keine Schuld; ein Chromium von außen verbindet sich problemlos
(nachgemessen am 2026-10-08).

## Entscheidung

Die App kommt mit dem **einen** Port aus, auf dem sie ohnehin ausgeliefert wird. Der WebSocket
bleibt der Hauptweg; geht er nicht auf, fällt der Browser auf zwei gewöhnliche HTTP-Wege zurück:

- `GET /ereignisse` – ein Ereignisstrom (Server-Sent Events) mit allem, was sonst über den Socket käme.
- `POST /eingabe?token=…` – eine Eingabe des Browsers, Rumpf ist dieselbe JSON-Nachricht wie bisher.

Beides ist gewöhnliches HTTP auf dem Port der Seite. Was die Seite laden darf, darf es auch.

**Kein Jetty, kein Austausch des Servers.** Der vorhandene `HttpServer` kann einen Strom offen
halten; ein zweiter Server oder eine Bibliothek, die HTTP und WebSocket auf einem Port mischt, wäre
für dieses eine Problem zu viel.

**Kein Umbau des Hauptwegs.** Der WebSocket bleibt erste Wahl: er ist sparsamer und bidirektional.
Der Rückfall greift nur, wenn er nicht aufgeht.

## Gemeinsame Buchhaltung

Heute führt `WsServer` die Klientenliste, bestimmt den Steuernden und meldet den letzten Abgang. Mit
zwei Wegen gehört das in eine eigene Stelle, sonst gäbe es zwei Wahrheiten darüber, wer steuert.

- `Klient` – Schnittstelle: `sende(String json)`, `offen()`, `schliesse()`.
- `Klienten` – die Liste in Eintreffens-Reihenfolge, der Steuernde ist der erste; `takeControl`,
  Rollenmeldung, Weiterreichen nur vom Steuernden, `onClientGone` beim letzten.
- `WsServer` und der Ereignisstrom liefern je eine `Klient`-Umsetzung. Für die Buchhaltung sind sie
  nicht unterscheidbar: ein zusehender Browser am Strom zählt wie einer am Socket.

## Der Strom im Einzelnen

- Antwort `text/event-stream`, `Cache-Control: no-cache`, `Connection: keep-alive`.
- Erstes Ereignis: `{"type":"hello","token":"<zufall>"}`. Der Browser braucht das Token, um seine
  Eingaben demselben Klienten zuzuordnen – eine HTTP-Anfrage trägt sonst keine Identität.
- Danach jede Nachricht der Bridge als `data: <json>`.
- Alle 20 s eine Kommentarzeile (`:ping`). Sie hält den Strom offen und ist zugleich die einzige
  Art, einen stillschweigend abgerissenen Browser zu bemerken: scheitert das Schreiben, fliegt der
  Klient raus.
- `POST /eingabe` mit unbekanntem Token: 404. Der Browser baut daraufhin den Strom neu auf.

**Fadenzahl.** `HttpStatic` hatte einen festen Vorrat von acht Fäden; ein offener Strom belegt
dauerhaft einen davon, und beim Spielstart laufen viele Bildabrufe gleichzeitig. Deshalb ein
wachsender Vorrat (`newCachedThreadPool`, weiterhin Daemon-Fäden).

## Im Browser

`ws.ts` versucht wie bisher den WebSocket. Öffnet er nicht innerhalb von **3 s**, oder bricht er vor
dem Öffnen ab, schaltet der Client auf den Strom um und bleibt dabei – ein Hin und Her zwischen zwei
Wegen wäre schlimmer als der langsamere Weg.

`send` geht dann per `fetch` an `/eingabe`. Reihenfolge bleibt gewahrt: die Nachrichten werden
nacheinander geschickt, nicht parallel.

## Nicht enthalten

- Verschlüsselung oder Zugangsschutz. Wer den Port erreicht, kann mitspielen – das war schon vorher so.
- Der umgekehrte Weg (WebSocket auf dem HTTP-Port). Das bräuchte einen anderen Server-Unterbau.

## Prüfung

- `KlientenTest`: Rollen, Weiterreichen, Nachrücken, letzter Abgang – ohne Netz, mit Attrappen.
- `SseTest`: Strom liefert `hello` und danach Nachrichten; `POST /eingabe` erreicht die Bridge;
  falsches Token gibt 404; ein Klient am Strom und einer am Socket sehen beide dasselbe.
- Ein Playwright-Lauf, bei dem `window.WebSocket` im Browser **abgeschaltet** ist: die App muss
  trotzdem die Lobby zeigen und eine Partie starten können. Das ist die Lage im eingebauten Browser,
  nachgestellt ohne ihn.
