#!/usr/bin/env bash
# Gleicht Forges Kartendaten mit upstream ab und belegt, dass unsere Engine damit arbeiten kann.
#
# Aufruf im Wurzelverzeichnis von MTG-Player (oder, fuer --nur-uebernehmen, in einem Repo mit
# derselben Verzeichnisstruktur):
#   scripts/kartendaten-sync.sh [<upstream-ref>]
#   scripts/kartendaten-sync.sh --nur-uebernehmen <ref> [--ausschluss <datei>]
#
# Rueckgabewerte: 0 = Ergebnis liegt vor (Pull Request darf entstehen), 1 = Fehlschlag,
#                 2 = nichts Neues bei upstream. Es gibt KEINEN anderen Wert: jeder Fehler eines
#                 Zwischenschritts (auch ein Java-Rueckgabewert 2 oder 3) wird zu 1, sonst hielte der
#                 aufrufende Ablauf ein Scheitern fuer "nichts Neues".
#
# Eine Karte, die vor dem Abgleich da war, geht durch ihn nicht verloren: faellt upstreams NEUE Fassung
# einer bei uns schon vorhandenen Datei durch die Pruefung, kommt unsere alte Fassung zurueck (git checkout
# HEAD) und die Karte steht im Bericht unter "bleibt auf unserer bisherigen Fassung" - nicht auf der
# Ausschlussliste. Nur wirklich neue Dateien werden geloescht und ausgeschlossen. Die Gegenprobe vergleicht
# deshalb auch die MENGE der Kartennamen vorher/nachher, nicht nur ihre Anzahl.
#
# Kein halbes Ergebnis: scheitert irgendein Schritt, nachdem der Arbeitsbaum veraendert wurde, wird die
# Aenderung zurueckgenommen (Kartenordner im Submodul und Ausschlussliste) und das laut gesagt. Wer den
# halben Stand zum Nachsehen braucht: KARTENDATEN_BEHALTEN=1 (dann bleibt er stehen, mit Hinweis).
set -euo pipefail
export PYTHONUTF8=1

VERZEICHNISSE=(forge-gui/res/cardsfolder forge-gui/res/editions forge-gui/res/tokenscripts)
UPSTREAM_URL="${KARTENDATEN_UPSTREAM_URL:-https://github.com/Card-Forge/forge.git}"
AUSSCHLUSS=""
NUR_UEBERNEHMEN=0
REF="upstream/master"

SCHRITT="Vorbereitung"
ZIEL=""            # das Git-Verzeichnis, in dem uebernommen wird (Submodul bzw. Testrepo)
DIRS=()            # die Verzeichnisse, die in <ref> oder im Stand davor wirklich existieren
VERAENDERT=0       # 1, sobald der Arbeitsbaum angefasst wurde
FERTIG=0           # 1 nur auf den Wegen, die absichtlich mit 0 oder 2 enden
AUSSCHLUSS_KOPIE=""
BERICHT=""

fehler() { echo "FEHLER: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --nur-uebernehmen) NUR_UEBERNEHMEN=1; shift ;;
    --ausschluss) [ $# -ge 2 ] || fehler "--ausschluss braucht eine Datei"; AUSSCHLUSS="$2"; shift 2 ;;
    -*) fehler "unbekannte Option: $1" ;;
    *) REF="$1"; shift ;;
  esac
done

# --- Aufraeumen bei Fehlschlag ----------------------------------------------------------------
zuruecksetzen() {
  if [ "${KARTENDATEN_BEHALTEN:-0}" = 1 ]; then
    echo "Arbeitsbaum bleibt VERAENDERT stehen (KARTENDATEN_BEHALTEN=1): git -C $ZIEL status" >&2
    return
  fi
  local ok=1 d
  git -C "$ZIEL" reset -q HEAD -- "${DIRS[@]}" >/dev/null 2>&1 || ok=0
  for d in "${DIRS[@]}"; do
    # Nur was HEAD kennt, kann ausgecheckt werden; ein Ordner, den erst <ref> mitbringt, faellt unter clean.
    if git -C "$ZIEL" cat-file -e "HEAD:$d" 2>/dev/null; then
      git -C "$ZIEL" checkout -q -- "$d" || ok=0
    fi
  done
  git -C "$ZIEL" clean -fdq -- "${DIRS[@]}" || ok=0
  if [ -n "$AUSSCHLUSS_KOPIE" ]; then
    if [ -f "$AUSSCHLUSS_KOPIE" ]; then cp "$AUSSCHLUSS_KOPIE" "$AUSSCHLUSS" || ok=0; else rm -f "$AUSSCHLUSS"; fi
  fi
  if [ "$ok" = 1 ]; then
    echo "Arbeitsbaum zurueckgesetzt: Kartenordner in $ZIEL und Ausschlussliste sind wieder wie vorher." >&2
  else
    echo "ACHTUNG: Zuruecksetzen ist SELBST gescheitert - $ZIEL ist halb veraendert, von Hand aufraeumen:" >&2
    echo "  git -C $ZIEL status --short -- ${VERZEICHNISSE[*]}" >&2
  fi
}

abschluss() {
  local rc=$?
  trap - EXIT
  [ "$FERTIG" = 1 ] && exit "$rc"
  echo "ABBRUCH im Schritt: $SCHRITT" >&2
  if [ -n "$BERICHT" ]; then
    printf '\n**FEHLGESCHLAGEN im Schritt: %s** - dieser Stand darf nicht zum Pull Request werden.\n' \
      "$SCHRITT" >> "$BERICHT" || true
  fi
  if [ "$VERAENDERT" = 1 ]; then zuruecksetzen; fi
  exit 1
}
trap abschluss EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# --- Uebernehmen -----------------------------------------------------------------------------
# Macht aus den Kartenordnern in $ZIEL genau den Stand von $REF. Alles ueber "git -C", nie cd: die
# Funktion laeuft in der Haupt-Shell, und die Aufraeum-Falle muss $ZIEL kennen.
uebernehmen() {
  ZIEL="$1"
  local liste="$2" d in_ref in_head

  # Erst pruefen, DANN anfassen: ein Fehler mitten im Raeumen liesse den Baum ohne Kartenordner zurueck.
  git -C "$ZIEL" rev-parse --verify -q "$REF^{commit}" >/dev/null \
    || fehler "$REF ist in $ZIEL kein bekannter Stand"
  if [ -n "$(git -C "$ZIEL" status --porcelain -uall -- "${VERZEICHNISSE[@]}")" ]; then
    fehler "Die Kartenordner in $ZIEL haben nicht eingecheckte Aenderungen - erst einchecken oder verwerfen" \
           "(sonst ginge beim Uebernehmen Handarbeit verloren und die Neu-Liste waere falsch)"
  fi
  DIRS=()
  for d in "${VERZEICHNISSE[@]}"; do
    in_ref=0; in_head=0
    git -C "$ZIEL" cat-file -e "$REF:$d" 2>/dev/null && in_ref=1
    git -C "$ZIEL" cat-file -e "HEAD:$d" 2>/dev/null && in_head=1
    if [ "$in_ref" = 0 ] && [ "$in_head" = 1 ]; then
      fehler "$d fehlt in $REF, existiert bei uns aber - upstream hat umgebaut? Nicht lautlos alles loeschen"
    fi
    if [ "$in_ref" = 1 ] || [ "$in_head" = 1 ]; then DIRS+=("$d"); fi
  done
  [ "${#DIRS[@]}" -gt 0 ] || fehler "keines der Kartenverzeichnisse gibt es in $REF oder bei uns"

  # "git checkout <ref> -- <pfade>" holt neue und geaenderte Dateien, aber KEINE Loeschungen: eine
  # Datei, die upstream nicht mehr hat, bliebe stehen. Deshalb vorher alles aus Index und Arbeitsbaum
  # nehmen und danach aus <ref> einspielen - so entsteht genau upstreams Stand. Kein "|| true":
  # scheitert eines von beiden, endet das Skript, und die Falle raeumt auf.
  VERAENDERT=1
  git -C "$ZIEL" rm -r -q -f --ignore-unmatch -- "${DIRS[@]}"
  git -C "$ZIEL" checkout -q "$REF" -- "${DIRS[@]}"

  # Ausgeschlossene Karten gar nicht erst hereinlassen. Nur NEUE Dateien (im Index, nicht in HEAD):
  # bei einem Eintrag "Kartenname in mehr als einer Datei" haengt an demselben Schluessel auch die
  # gute alte Datei, die bleiben muss. Der Schluessel einer Datei wird wie in CardFiles bestimmt
  # (erste "Name:"-Zeile unter cardsfolder, erste "Code="-Zeile unter editions).
  python3 - "$ZIEL" "$liste" "${DIRS[@]}" <<'PY'
import os, subprocess, sys
repo, liste, dirs = sys.argv[1], sys.argv[2], sys.argv[3:]
schluessel = set()
if os.path.isfile(liste):
    with open(liste, encoding="utf-8") as f:
        for zeile in f:
            if zeile.strip() and not zeile.startswith("#"):
                schluessel.add(zeile.rstrip("\n").split("\t")[0])
if not schluessel:
    sys.exit(0)
neu = subprocess.run(
    ["git", "-C", repo, "diff", "--cached", "--name-only", "-z", "--no-renames", "--diff-filter=A", "HEAD", "--"]
    + dirs, capture_output=True, check=True).stdout.split(b"\0")
weg = []
for roh in neu:
    if not roh:
        continue
    pfad = os.fsdecode(roh)
    praefix = ("Name:" if "/cardsfolder/" in pfad
               else "Code=" if "/editions/" in pfad else None)
    if praefix is None:
        continue
    try:
        with open(os.path.join(repo, pfad), encoding="utf-8") as f:
            name = next((z[len(praefix):].strip() for z in f if z.startswith(praefix)), None)
    except UnicodeDecodeError:
        name = None
    if name in schluessel:
        weg.append(roh)
if weg:
    subprocess.run(["git", "-C", repo, "rm", "-q", "-f", "--pathspec-from-file=-", "--pathspec-file-nul"],
                   input=b"\0".join(weg), check=True)
print(f"vorab ausgeschlossen: {len(weg)} neue Datei(en)")
PY
}

# Ohne Ausschlussliste-Angabe gilt die des Projekts, relativ zum Aufrufverzeichnis (Wurzel).
absolut() { case "$1" in /*) printf '%s' "$1" ;; *) printf '%s/%s' "$PWD" "$1" ;; esac; }

if [ "$NUR_UEBERNEHMEN" = 1 ]; then
  SCHRITT="Uebernehmen"
  AUSSCHLUSS=$(absolut "${AUSSCHLUSS:-docs/kartendaten-ausgeschlossen.txt}")
  uebernehmen "$PWD" "$AUSSCHLUSS"
  FERTIG=1
  exit 0
fi

# --- Voller Durchgang ------------------------------------------------------------------------
cd "$(dirname "$0")/.."
WURZEL="$PWD"
AUSSCHLUSS=$(absolut "${AUSSCHLUSS:-docs/kartendaten-ausgeschlossen.txt}")
FORGE="$WURZEL/forge"
BEFUND_VORHER="$WURZEL/kartendaten-befund-vorher.json"
BEFUND_ROH="$WURZEL/kartendaten-befund-roh.json"
BEFUND="$WURZEL/kartendaten-befund.json"
BERICHT="$WURZEL/kartendaten-bericht.md"
NEUE_DATEIEN="$WURZEL/kartendaten-neu.txt"
NAMEN_VORHER="$WURZEL/target/kartennamen-vorher.txt"
NAMEN_NACHHER="$WURZEL/target/kartennamen-nachher.txt"
BLEIBT="$WURZEL/target/kartendaten-bleibt.tsv"   # Karten, die auf unserer alten Fassung bleiben
CP_DATEI="$WURZEL/target/cp.txt"
DATA="$WURZEL/target/kartendaten-data"

# Reste eines frueheren Laufs duerfen nie fuer das Ergebnis dieses Laufs gehalten werden.
rm -f "$BEFUND_VORHER" "$BEFUND_ROH" "$BEFUND" "$BERICHT" "$NEUE_DATEIEN" \
      "$NAMEN_VORHER" "$NAMEN_NACHHER" "$BLEIBT"

# Das Skript UEBERSETZT NICHT. Es erwartet eine fertig gebaute Bridge und sagt sonst, was zu tun ist.
# Grund: auf dem Entwicklungsrechner laeuft Kevins Bridge aus genau diesem target/classes - ein
# "mvn package" waehrend des Spiels tauscht ihr die Klassen unter der laufenden JVM weg
# (NoSuchMethodError mitten in der Partie). Der Ablauf baut vorher, von Hand baut man vorher.
if [ ! -d bridge/target/classes/mtgplayer ] || [ ! -s "$CP_DATEI" ]; then
  echo "Bridge ist nicht gebaut. Vorher einmal, und NICHT waehrend eine Bridge laeuft:" >&2
  echo "  mvn -q -f bridge/pom.xml -DskipTests package" >&2
  echo "  mvn -q -f bridge/pom.xml dependency:build-classpath -Dmdep.outputFile=\"$CP_DATEI\"" >&2
  fehler "keine gebaute Bridge"
fi
CP="$(cat "$CP_DATEI")"

# Die Bridge liest ihre Karten ueber den Symlink bridge/assets/res. Zeigt der woandershin als auf das
# Submodul, das wir veraendern, wuerde sie das Falsche pruefen und im falschen Baum loeschen.
[ -d "$FORGE/forge-gui/res" ] || fehler "Submodul forge fehlt (git submodule update --init forge)"
if [ "$(readlink -f bridge/assets/res)" != "$(readlink -f "$FORGE/forge-gui/res")" ]; then
  fehler "bridge/assets/res zeigt auf $(readlink -f bridge/assets/res), nicht auf $FORGE/forge-gui/res"
fi

bridge_java() {
  ( cd "$WURZEL/bridge" && java -Xmx4g -Dmtgplayer.data="$DATA" -cp "target/classes:$CP" mtgplayer.Main "$@" )
}

pruefen() {   # $1 = Zieldatei
  local roh
  roh=$(bridge_java --kartendaten-pruefen) || fehler "Pruefmodus ist abgestuerzt"
  printf '%s\n' "$roh" | sed -n 's/^KARTENDATEN_BEFUND //p' > "$1"
  [ -s "$1" ] || fehler "Pruefmodus lieferte keinen Befund"
  python3 -c "import json,sys; assert isinstance(json.load(open(sys.argv[1], encoding='utf-8'))['karten'], int)" "$1" \
    || fehler "Befund $1 ist kein brauchbares JSON"
}
karten() { python3 -c "import json,sys;print(json.load(open(sys.argv[1], encoding='utf-8'))['karten'])" "$1"; }

# Die Kartennamen unter cardsfolder (erste "Name:"-Zeile je Datei, wie CardFiles), sortiert, einer je Zeile.
# $1 = Zieldatei. Liest den Arbeitsbaum - vor dem Uebernehmen ist der (siehe Pruefung unten) gleich HEAD.
kartennamen() {
  python3 - "$FORGE/forge-gui/res/cardsfolder" "$1" <<'PY'
import os, sys
wurzel, ziel = sys.argv[1:3]
namen = set()
for ordner, _, dateien in os.walk(wurzel):
    for datei in dateien:
        try:
            with open(os.path.join(ordner, datei), encoding="utf-8") as f:
                name = next((z[5:].strip() for z in f if z.startswith("Name:")), None)
        except UnicodeDecodeError:
            name = None
        if name:
            namen.add(name)
with open(ziel, "w", encoding="utf-8") as f:
    f.write("".join(n + "\n" for n in sorted(namen)))
PY
}

SCHRITT="1. Vergleichsmarke"
echo "== 1. Vergleichsmarke =="
# Vorher: ein unsauberer Ausgangsstand wuerde sonst unserem Abgleich angelastet, und ein "git clean"
# in der Falle raeumte Handarbeit weg. Darum steht die Pruefung auch hier und nicht nur in uebernehmen().
if [ -n "$(git -C "$FORGE" status --porcelain -uall -- "${VERZEICHNISSE[@]}")" ]; then
  fehler "Die Kartenordner in forge haben nicht eingecheckte Aenderungen - erst einchecken oder verwerfen"
fi
pruefen "$BEFUND_VORHER"
KARTEN_VORHER=$(karten "$BEFUND_VORHER")
echo "Karten vorher: $KARTEN_VORHER"
mkdir -p "$WURZEL/target"
kartennamen "$NAMEN_VORHER"

SCHRITT="2. Uebernehmen von $REF"
echo "== 2. Uebernehmen von $REF =="
if [[ "$REF" == upstream/* ]]; then
  git -C "$FORGE" remote get-url upstream >/dev/null 2>&1 || git -C "$FORGE" remote add upstream "$UPSTREAM_URL"
  # Ein flaches Submodul bleibt flach; in einem vollen Klon wuerde --depth dessen Verlauf kappen.
  TIEFE=()
  [ "$(git -C "$FORGE" rev-parse --is-shallow-repository)" = true ] && TIEFE=(--depth=1)
  git -C "$FORGE" fetch --quiet "${TIEFE[@]}" upstream "${REF#upstream/}"
fi
mkdir -p "$(dirname "$AUSSCHLUSS")" "$WURZEL/target"
AUSSCHLUSS_KOPIE="$WURZEL/target/ausschluss-vorher.txt"
rm -f "$AUSSCHLUSS_KOPIE"
[ -f "$AUSSCHLUSS" ] && cp "$AUSSCHLUSS" "$AUSSCHLUSS_KOPIE"
uebernehmen "$FORGE" "$AUSSCHLUSS"

# "Nichts Neues" heisst: der Arbeitsbaum unterscheidet sich nicht von HEAD - nicht: "keine neuen Dateien"
# (eine Woche mit nur Loeschungen ist nicht nichts).
if git -C "$FORGE" diff --cached --quiet HEAD -- "${DIRS[@]}"; then
  echo "nichts Neues bei upstream"
  VERAENDERT=0
  FERTIG=1
  exit 2
fi

# Die Liste der Dateien, die dieser Abgleich hereingebracht oder geaendert hat, fuer das Aussortieren
# (von einer Dublette faellt die neue Datei, nicht die bestehende). Format, vom Aussortieren
# eingefordert: je Zeile "forge-gui/res/<pfad relativ zu res>".
# Aufruf bewusst "git diff --cached --name-only -z --no-renames", NICHT "git status --porcelain":
#   - status setzt Pfade mit Sonderzeichen in Anfuehrungszeichen ("Joetun Grunt" -> "j\303\266tun..."),
#     diff mit -z nennt sie unveraendert, durch NUL getrennt;
#   - status ohne -uall fasst ein neues Verzeichnis zu EINEM Eintrag mit "/" am Ende zusammen, statt der
#     Dateien darin (nach "git checkout <ref> --" liegt alles im Index, dort gibt es das Problem nicht);
#   - status zeigt zusaetzlich Loeschungen, die auf der Liste nichts verloren haben;
#   - --no-renames, damit eine umbenannte Datei als neue Datei erscheint und nicht als "alt -> neu".
# --diff-filter=AM: hinzugekommen oder geaendert. Eine geaenderte Datei kann ebenfalls die zweite
# Kartenskript-Datei zu einem Namen werden (upstream benennt eine Karte um).
git -C "$FORGE" diff --cached --name-only -z --no-renames --diff-filter=AM HEAD -- "${DIRS[@]}" \
  | python3 -c '
import sys
pfade = [p for p in sys.stdin.buffer.read().split(b"\0") if p]
for p in pfade:
    if b"\n" in p or b"\r" in p or not p.startswith(b"forge-gui/res/"):
        sys.exit("Pfad nicht als Zeile darstellbar: %r" % p)
sys.stdout.buffer.write(b"".join(p + b"\n" for p in pfade))
' > "$NEUE_DATEIEN"
echo "neue oder geaenderte Dateien: $(wc -l < "$NEUE_DATEIEN")"

SCHRITT="3. Pruefen und aussortieren"
echo "== 3. Pruefen und aussortieren =="
pruefen "$BEFUND_ROH"
rc=0
bridge_java --kartendaten-aussortieren "$BEFUND_ROH" "$AUSSCHLUSS" "$NEUE_DATEIEN" || rc=$?
if [ "$rc" -ne 0 ]; then
  # 2 = Neu-Liste fehlt/unbrauchbar (vor jeder Aenderung), 3 = ungeloeste Schluessel (stderr nennt sie),
  # anderes = Absturz. Alles davon ist ein Fehlschlag - und ausdruecklich NICHT "nichts Neues" (2).
  fehler "Aussortieren endete mit $rc (2 = Neu-Liste unbrauchbar, 3 = ungeloeste Schluessel, siehe oben)"
fi

# Eine Datei, die HEAD schon kannte, war eine laufende Karte: upstream hat sie nur geaendert. Faellt diese
# neue Fassung durch, ist es richtig, SIE zu verwerfen - aber die Karte darf dadurch nicht verloren gehen.
# Darum kommt unsere alte Fassung zurueck, und der Name verlaesst die Ausschlussliste wieder (er stand nur
# darauf, weil das Aussortieren die Datei nicht von der neuen unterscheiden kann). Bleibt der Schluessel
# trotzdem ausgeschlossen, wenn eine ZWEITE, wirklich neue Datei mit demselben Schluessel gefallen ist:
# diese Dublette bleibt draussen, die alte gute Datei bleibt stehen.
# Das Format von $BLEIBT: Schluessel, Grund, Pfad - Tabulator-getrennt, fuer den Bericht.
SCHRITT="3b. Bisherige Fassungen wiederherstellen"
python3 - "$FORGE" "$NEUE_DATEIEN" "$AUSSCHLUSS_KOPIE" "$AUSSCHLUSS" "$BLEIBT" <<'PY'
import os, subprocess, sys
repo, neue, ausschluss_vor, ausschluss_nach, bleibt = sys.argv[1:6]

def zeilen(p):
    if not os.path.isfile(p):
        return []
    with open(p, encoding="utf-8") as f:
        return f.read().split("\n")

def eintraege(p):
    ergebnis = {}
    for z in zeilen(p):
        if z.strip() and not z.startswith("#"):
            teile = z.split("\t")
            ergebnis[teile[0]] = teile[1] if len(teile) > 1 else ""
    return ergebnis

def git(*args, **kw):
    return subprocess.run(["git", "-C", repo, *args], capture_output=True, **kw)

def schluessel_im_index(pfad):
    praefix = ("Name:" if "/cardsfolder/" in pfad else "Code=" if "/editions/" in pfad else None)
    if praefix is None:
        return None
    inhalt = git("show", ":" + pfad, check=True).stdout
    try:
        text = inhalt.decode("utf-8")
    except UnicodeDecodeError:
        return None
    return next((z[len(praefix):].strip() for z in text.split("\n") if z.startswith(praefix)), None)

with open(neue, "rb") as f:
    kandidaten = [os.fsdecode(p) for p in f.read().split(b"\n") if p]
# Das Aussortieren loescht nur im Arbeitsbaum; der Index kennt die Datei noch (siehe unten, "add -A").
geloescht = [p for p in kandidaten if not os.path.lexists(os.path.join(repo, p))]
alt = [p for p in geloescht if git("cat-file", "-e", "HEAD:" + p).returncode == 0]
neu_weg = [p for p in geloescht if p not in alt]
vor = eintraege(ausschluss_vor)
nach = eintraege(ausschluss_nach)
bleibt_zeilen = []
if alt:
    schluessel_alt = {p: schluessel_im_index(p) for p in alt}
    schluessel_neu_weg = {schluessel_im_index(p) for p in neu_weg}
    git("checkout", "-q", "HEAD", "--pathspec-from-file=-", "--pathspec-file-nul",
        input=b"\0".join(os.fsencode(p) for p in alt), check=True)
    entfernen = set()
    for pfad, k in sorted(schluessel_alt.items()):
        if k is None:
            continue
        grund = nach.get(k) or "von unserer Forge-Fassung nicht baubar"
        bleibt_zeilen.append(f"{k}\t{grund}\t{pfad}")
        # Nur was dieser Lauf neu eingetragen hat, wird wieder gestrichen; ein alter Eintrag ist Handarbeit.
        if k not in vor and k not in schluessel_neu_weg:
            entfernen.add(k)
    if entfernen and os.path.isfile(ausschluss_nach):
        behalten = [z for z in zeilen(ausschluss_nach)
                    if not (z.strip() and not z.startswith("#") and z.split("\t")[0] in entfernen)]
        with open(ausschluss_nach, "w", encoding="utf-8") as f:
            f.write("\n".join(behalten))
with open(bleibt, "w", encoding="utf-8") as f:
    f.write("".join(z + "\n" for z in bleibt_zeilen))
print(f"auf unserer bisherigen Fassung geblieben: {len(bleibt_zeilen)} Karte(n)")
PY

# Das Aussortieren hat Dateien aus dem Arbeitsbaum geloescht, der Index kennt sie noch: angleichen, damit
# der Stand im Submodul (und die Zaehlung im Bericht) das Endergebnis zeigt.
git -C "$FORGE" add -A -- "${DIRS[@]}"

SCHRITT="4. Gegenprobe"
echo "== 4. Gegenprobe =="
pruefen "$BEFUND"
kartennamen "$NAMEN_NACHHER"
python3 - "$BEFUND" "$BEFUND_VORHER" "$BERICHT" "$AUSSCHLUSS_KOPIE" "$AUSSCHLUSS" "$NAMEN_VORHER" \
          "$NAMEN_NACHHER" "$BLEIBT" "$FORGE" "${DIRS[@]}" <<'PY'
import json, os, subprocess, sys
befund_p, vorher_p, bericht, ausschluss_vor, ausschluss_nach, namen_vor, namen_nach, bleibt_p, forge = sys.argv[1:10]
dirs = sys.argv[10:]
lade = lambda p: json.load(open(p, encoding="utf-8"))
befund, vorher = lade(befund_p), lade(vorher_p)

def eintraege(p):
    ergebnis = {}
    if os.path.isfile(p):
        for z in open(p, encoding="utf-8"):
            if z.strip() and not z.startswith("#"):
                teile = z.rstrip("\n").split("\t")
                ergebnis[teile[0]] = teile[1] if len(teile) > 1 else ""
    return ergebnis
vor, nach = eintraege(ausschluss_vor), eintraege(ausschluss_nach)
neu_ausgeschlossen = {k: v for k, v in nach.items() if k not in vor}

# "git diff --name-status -z": Status, Pfad, Status, Pfad ... (ohne Umbenennungen)
teile = subprocess.run(["git", "-C", forge, "diff", "--cached", "--name-status", "-z", "--no-renames", "HEAD", "--"]
                       + dirs, capture_output=True, check=True).stdout.split(b"\0")
zaehler = {"A": 0, "M": 0, "D": 0}
for i in range(0, len(teile) - 1, 2):
    zaehler[teile[i].decode()[:1]] = zaehler.get(teile[i].decode()[:1], 0) + 1

def namen(p):
    with open(p, encoding="utf-8") as f:
        return {z.rstrip("\n") for z in f if z.strip()}
# Die Zahl allein genuegt nicht: verschwindet eine Karte und kommen zwei neue dazu, steigt sie trotzdem.
fehlend = sorted(namen(namen_vor) - namen(namen_nach))
bleibt = []
if os.path.isfile(bleibt_p):
    with open(bleibt_p, encoding="utf-8") as f:
        bleibt = [z.rstrip("\n").split("\t") for z in f if z.strip()]

offen = (befund["nichtBaubar"] or befund["nichtAuffindbar"]
         or befund["doppelteSetCodes"] or befund["doppelteNamen"])
gefallen = befund["karten"] < vorher["karten"]
with open(bericht, "w", encoding="utf-8") as f:
    f.write(f"## Kartendaten-Abgleich\n\nKarten: {vorher['karten']} -> {befund['karten']}\n\n")
    f.write(f"Dateien gegenueber unserem Stand: {zaehler['A']} neu, {zaehler['M']} geaendert, "
            f"{zaehler['D']} geloescht.\n\n")
    if neu_ausgeschlossen:
        f.write(f"Neu ausgeschlossen ({len(neu_ausgeschlossen)}) - unsere Forge-Fassung kann sie nicht bauen:\n\n")
        for k, v in sorted(neu_ausgeschlossen.items()):
            f.write(f"- `{k}`: {v}\n")
        f.write("\n")
    else:
        f.write("Nichts neu ausgeschlossen.\n\n")
    if bleibt:
        f.write(f"Bleibt auf unserer bisherigen Fassung ({len(bleibt)}) - upstreams neue Fassung ist mit "
                "unserer Forge-Fassung nicht baubar, die Karte laeuft weiter wie bisher:\n\n")
        for k, grund, pfad in sorted(bleibt):
            f.write(f"- `{k}`: {grund}\n")
        f.write("\n")
    if offen:
        f.write("**Befund nach dem Aussortieren noch offen:**\n\n```\n"
                + json.dumps(befund, indent=1, ensure_ascii=False) + "\n```\n")
    if gefallen:
        f.write(f"**Kartenzahl gefallen: {vorher['karten']} -> {befund['karten']}**\n")
    if fehlend:
        f.write(f"**Karten verschwunden ({len(fehlend)}), die vor dem Abgleich da waren:**\n\n")
        for n in fehlend:
            f.write(f"- `{n}`\n")
if offen or gefallen or fehlend:
    print("Gegenprobe nicht sauber" + (": Befund offen" if offen else "")
          + (f": Kartenzahl gefallen {vorher['karten']} -> {befund['karten']}" if gefallen else "")
          + (f": {len(fehlend)} Karte(n) verschwunden: " + "; ".join(fehlend) if fehlend else ""),
          file=sys.stderr)
    sys.exit(1)
PY

SCHRITT="5. Abschliessende Partie"
echo "== 5. Abschliessende Partie =="
# Ausgabe einsammeln statt wegwerfen: ohne TRIMMED_RESULT-Zeile hat keine Partie stattgefunden.
PARTIE=$(bridge_java --trimmed-check) || fehler "die abschliessende Partie ist gescheitert"
printf '%s\n' "$PARTIE" | grep -q '^TRIMMED_RESULT ' || fehler "die abschliessende Partie meldete kein Ergebnis"
printf '%s\n' "$PARTIE" | grep '^TRIMMED_RESULT '

FERTIG=1
echo "fertig: $BERICHT"
