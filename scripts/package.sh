#!/usr/bin/env bash
set -euo pipefail

# Stellt den vollstaendigen App-Paketordner zusammen (siehe
# docs/superpowers/specs/2026-09-26-app-paket-design.md §2) und ruft jpackage darauf los.
# Laeuft unveraendert auf Linux (hier entsteht nur ein Linux-App-Image zum Pruefen der
# Zusammenstellung) und auf Windows im GitHub-Workflow ueber Git-Bash (dort entsteht das
# eigentliche, herunterladbare Paket) - jpackage baut immer nur fuer die gerade laufende
# Plattform, ein Windows-Paket kann dieses Skript hier nicht erzeugen.
#
# Aufruf: scripts/package.sh <version> <ziel>
#   <version>  Fassung, die in --app-version und version.txt landet (z.B. 1.2.0)
#   <ziel>     Arbeits- und Ausgabeverzeichnis; wird bei jedem Lauf neu angelegt

if [ "$#" -ne 2 ]; then
    echo "Aufruf: $0 <version> <ziel>" >&2
    exit 1
fi

version="$1"
ziel="$2"
wurzel="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# <ziel> muss absolut sein, BEVOR unten in bridge/ hineingewechselt wird (Maven fuer
# dependency:copy-dependencies) - sonst zeigt ein relativ uebergebenes <ziel> nach dem cd auf den
# falschen Ort (waere z.B. unter bridge/ statt an der gewuenschten Stelle gelandet).
# Zwei Formen gelten als absolut: "/..." (Linux/macOS/Git-Bash-eigene Pfade) UND "<Buchstabe>:..."
# (Windows-Laufwerkspfad, z.B. "D:\a\..." - so liefert der GitHub-Runner GITHUB_WORKSPACE auf
# windows-latest; ohne diese zweite Form haette case sie faelschlich fuer relativ gehalten und
# ihnen "$(pwd)/" vorangestellt).
case "$ziel" in
    /*) ;;
    [A-Za-z]:*) ;;
    *) ziel="$(pwd)/$ziel" ;;
esac

rm -rf "$ziel"
mkdir -p "$ziel"

# --- 1. Bridge-Jar und Abhaengigkeiten sammeln -----------------------------------------------
# Beides landet FLACH im selben Verzeichnis: jpackage baut den Klassenpfad einer app-image aus
# allen *.jar-Dateien, die direkt (nicht in Unterordnern) in --input liegen (per Probelauf
# geprueft, siehe Bericht) - ein separater "libs"-Unterordner wuerde stillschweigend NICHT auf
# dem Klassenpfad landen.
eingabe="$ziel/input"
mkdir -p "$eingabe"
(
    cd "$wurzel/bridge"
    # includeScope=runtime: compile+runtime, OHNE test/provided - sonst wuerden auch
    # junit/opentest4j/... (nur bridge/pom.xml <scope>test</scope>) mit ins Paket wandern.
    mvn -q -DskipTests package dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory="$eingabe"
)
hauptjar_pfad="$(find "$wurzel/bridge/target" -maxdepth 1 -name 'bridge-*.jar' \
    ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -n1)"
if [ -z "$hauptjar_pfad" ]; then
    echo "package.sh: kein bridge-*.jar in bridge/target gefunden (mvn package fehlgeschlagen?)" >&2
    exit 1
fi
cp "$hauptjar_pfad" "$eingabe/"
hauptjar="$(basename "$hauptjar_pfad")"
# Anmerkung: der Dateiname traegt Maven-Version aus bridge/pom.xml (0.1.0-SNAPSHOT), NICHT die
# hier uebergebene App-Fassung <version> - jpackage bekommt den tatsaechlichen Namen ueber
# --main-jar, die App-Fassung landet unabhaengig davon in --app-version und version.txt.

# --- 2. web/dist nach <input>/web -------------------------------------------------------------
# package.sh baut die Web-UI nicht selbst (eigener Workflow-Schritt "npm run build" davor) -
# ohne dist waere das nur ein stilles leeres Fenster.
if [ ! -d "$wurzel/web/dist" ]; then
    echo "package.sh: web/dist fehlt - vorher 'npm run build' in web/ ausfuehren" >&2
    exit 1
fi
cp -a "$wurzel/web/dist" "$eingabe/web"

# --- 3. Ausgeduenntes res -----------------------------------------------------------------
# Liegt bewusst NICHT in --input: es soll als "assets/" neben dem von jpackage erzeugten
# App-Ordner landen (Spec §2), nicht mit den Jars vermischt. trim-res.sh legt es hier erstmal
# beiseite; der endgueltige Platz wird nach dem jpackage-Lauf bestimmt (Schritt 6).
assets_staging="$ziel/assets-staging"
"$wurzel/scripts/trim-res.sh" "$wurzel/forge/forge-gui/res" "$assets_staging"

# --- 4. jpackage --------------------------------------------------------------------------
bild="$ziel/image"
mkdir -p "$bild"
jpackage --type app-image --name MTG-Player --input "$eingabe" --dest "$bild" \
    --main-jar "$hauptjar" --main-class mtgplayer.Main --app-version "$version" \
    --java-options "-Xmx4g" \
    --java-options "-Dmtgplayer.app=true" \
    --java-options "-Dmtgplayer.assets=\$APPDIR/../assets" \
    --java-options "-Dmtgplayer.web=\$APPDIR/web"

bild_ordner="$bild/MTG-Player"
if [ ! -d "$bild_ordner" ]; then
    echo "package.sh: jpackage-Ausgabe $bild_ordner nicht gefunden" >&2
    exit 1
fi

# --- 5. Lizenzpflicht + Fassung, oben im Paketordner (Spec §2: neben app/, assets/, runtime/) --
cp "$wurzel/LICENSE" "$bild_ordner/"
cp "$wurzel/forge/MODIFICATIONS.md" "$bild_ordner/MODIFICATIONS.txt"
echo "$version" > "$bild_ordner/version.txt"

# --- 6. assets/ dorthin legen, wo "$APPDIR/../assets" zur Laufzeit wirklich hinzeigt -----------
# $APPDIR ist der Ordner, in den jpackage --input kopiert hat. Wo der innerhalb des App-Images
# liegt, ist plattformabhaengig (Windows: <Name>/app, direkt unter der Bildwurzel - "../assets"
# landet dann exakt bei der Spec-Zeichnung aus §2. Linux: <Name>/lib/app, "../assets" landet also
# bei <Name>/lib/assets, eine Ebene tiefer als die Zeichnung) - das wird hier NICHT geraten,
# sondern am tatsaechlich kopierten Hauptjar wiedergefunden (siehe Bericht: mit einem
# Testjar+Testlauf gegen die generierte HiApp.cfg und den Launcher selbst geprueft, welchen Wert
# $APPDIR zur Laufzeit tatsaechlich einsetzt).
# -maxdepth 4, head -n1 und die Leer-Pruefung sind Pflicht, nicht Kosmetik: findet find nichts
# (Virenscanner haelt die frisch kopierte Datei kurz fest, ein anderes JDK legt das Jar woanders
# ab, ...), liefert "dirname \"\"" klanglos "." zurueck - MIT Exit-Code 0, set -e greift also
# nicht. Das folgende mv haette dann das ausgeduennte res eine Ebene zu hoch (ins Arbeitsverzeichnis
# des Aufrufers) geschoben und das Skript haette trotzdem "fertig" gemeldet - genau das still
# kaputte Paket, das die ganze Messerei oben vermeiden soll. Dieselbe Absicherung wie beim
# Hauptjar-Fund oben (Zeile ~48).
appdir_treffer="$(find "$bild_ordner" -maxdepth 4 -name "$hauptjar" | head -n1)"
if [ -z "$appdir_treffer" ]; then
    echo "package.sh: $hauptjar nicht im erzeugten App-Image $bild_ordner gefunden - APPDIR unbekannt, breche ab" >&2
    exit 1
fi
appdir_tatsaechlich="$(dirname "$appdir_treffer")"
mv "$assets_staging" "$appdir_tatsaechlich/../assets"

echo "package.sh: fertig."
echo "  App-Image:        $bild_ordner"
echo "  \$APPDIR (echt):    $appdir_tatsaechlich"
echo "  assets/ liegt bei: $appdir_tatsaechlich/../assets"
du -sh "$bild_ordner"
