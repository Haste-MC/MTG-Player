#!/usr/bin/env bash
set -euo pipefail

# Duennt Forges "res"-Verzeichnis (463 MB) auf die Ordner aus, die MTG-Player tatsaechlich braucht:
# headless KI-Partien mit Commander-Precons ueber eine eigene Web-UI - kein Forge-eigener Client.
# Draussen bleiben deshalb (samt Begruendung, damit ein spaeterer Blick auf die Liste nicht raet):
#   adventure           - Forges Adventure-Spielmodus (Overworld, eigene Karten); MTG-Player bietet
#                          ihn nicht an
#   languages, bis auf languages/en-US.properties - siehe unten: Forges Localizer laedt IMMER ein
#                          Bundle "en-US" als Englisch-Rueckfall (forge-core Localizer.setLanguage),
#                          unabhaengig von UI_LANGUAGE. Der Rest von languages/ (neun weitere
#                          *.properties sowie acht cardnames-*.txt mit uebersetzten Kartennamen,
#                          zusammen ueber 50 MB) wird nie angefragt, weil die Bridge fest auf en-US
#                          laeuft (siehe ForgeBoot.init) - gefunden per TrimmedResTest: mit
#                          komplett fehlendem languages/ bricht ForgeBoot.init() im Kindprozess mit
#                          "MissingResourceException: Can't find bundle for base name en-US" ab.
#   music, sound, fonts - Audio/Swing-Ressourcen; headless/Browser-UI gibt weder Musik noch Sounds
#                          aus (UI_ENABLE_MUSIC/UI_ENABLE_SOUNDS=false) und zeichnet keine Swing-Fonts
#   skins               - Swing/Desktop-Look von Forges eigenem Fenster; MTG-Player hat eine eigene
#                          Web-UI, kein Forge-Fenster
#   quest/*             - bis auf commanderprecons nur der Quest-Spielmodus (Kampagne, Weltkarte,
#                          Haendler-Bazaar), den MTG-Player nicht anbietet; die Precons darunter sind
#                          die Decks fuer die KI-Partien und bleiben drin
#   conquest, cube, deckgendecks, draft, geneticaidecks, puzzle, sealed, tutorial, howto.txt
#                       - weitere Forge-Spielmodi/Deckgeneratoren bzw. Anleitungstexte, die die
#                          Bridge nicht ansteuert
#
# TrimmedResTest belegt genau diese Auswahl gegen eine echte KI-Partie; faellt der Test dort aus,
# weil ein Pfad fehlt, gehoert der fehlende Ordner (oder die fehlende Datei) hier in die Liste.

if [ "$#" -ne 2 ]; then
    echo "Aufruf: $0 <quelle> <ziel>" >&2
    exit 1
fi

quelle="$1"
ziel="$2"

# Ordner direkt unter res/, die die Bridge benutzt.
ordner=(
    cardsfolder
    editions
    tokenscripts
    formats
    lists
    blockdata
    effects
    defaults
    ai
    setlookup
    licenses
)

ziel_res="$ziel/res"
mkdir -p "$ziel_res"

kopiere() {
    local quelle_pfad="$1"
    local ziel_eltern="$2"
    if [ ! -d "$quelle_pfad" ]; then
        echo "trim-res.sh: Ordner fehlt in der Quelle: $quelle_pfad (Forges res-Aufbau hat sich geaendert?)" >&2
        exit 1
    fi
    # -a fuer Rechte/Zeitstempel, echte Kopie (kein -l/Hardlink): das Ziel ist keine reine Testkopie,
    # sondern auch der Ordner, den der Release-Workflow danach weiterbearbeitet und verpackt - ein
    # Hardlink wuerde jede Aenderung an der Kopie unbemerkt in Forges Original res/ durchschlagen
    # lassen (geteilter Inode). Laufzeit siehe TrimmedResTest-Log.
    cp -a "$quelle_pfad" "$ziel_eltern/"
}

kopiere_datei() {
    local quelle_datei="$1"
    local ziel_eltern="$2"
    if [ ! -f "$quelle_datei" ]; then
        echo "trim-res.sh: Datei fehlt in der Quelle: $quelle_datei (Forges res-Aufbau hat sich geaendert?)" >&2
        exit 1
    fi
    cp -a "$quelle_datei" "$ziel_eltern/"
}

for o in "${ordner[@]}"; do
    kopiere "$quelle/$o" "$ziel_res"
done

# Die Precons liegen unter quest/, der Rest von quest (Kampagne, Weltkarte, ~39 MB) wird nicht gebraucht.
mkdir -p "$ziel_res/quest"
kopiere "$quelle/quest/commanderprecons" "$ziel_res/quest"

# Nur die eine Datei aus languages/, siehe Begruendung oben (Englisch-Rueckfall-Bundle).
mkdir -p "$ziel_res/languages"
kopiere_datei "$quelle/languages/en-US.properties" "$ziel_res/languages"

echo "trim-res.sh: fertig, Groesse von $ziel_res:"
du -sh "$ziel_res"
