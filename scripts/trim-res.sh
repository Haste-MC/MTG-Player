#!/usr/bin/env bash
set -euo pipefail

# Duennt Forges "res"-Verzeichnis (463 MB) auf die Ordner aus, die MTG-Player tatsaechlich braucht:
# headless KI-Partien mit Commander-Precons ueber eine eigene Web-UI - kein Forge-eigener Client.
# Draussen bleiben deshalb (samt Begruendung, damit ein spaeterer Blick auf die Liste nicht raet):
#   adventure           - Forges Adventure-Spielmodus (Overworld, eigene Karten); MTG-Player bietet
#                          ihn nicht an
#   languages           - Uebersetzungen; die Bridge laeuft fest auf en-US (siehe ForgeBoot.init)
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
# weil ein Pfad fehlt, gehoert der fehlende Ordner hier in die Liste.

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
    # -a fuer Rechte/Zeitstempel, -l fuer Hardlinks statt echter Kopien: die Quelle wird nur gelesen,
    # nie veraendert (ForgeBoot schreibt hoechstens eine forge.profile.properties eine Ebene ueber
    # res/, nie in die kopierten Ordner) - ein Hardlink teilt sich den Inode, spart also die 150+ MB
    # Kopie bei jedem Testlauf. Faellt das mangels gemeinsamem Dateisystem aus (Quelle und Ziel auf
    # verschiedenen Mounts), normale Kopie als Rueckfall.
    if ! cp -al "$quelle_pfad" "$ziel_eltern/" 2>/dev/null; then
        cp -a "$quelle_pfad" "$ziel_eltern/"
    fi
}

for o in "${ordner[@]}"; do
    kopiere "$quelle/$o" "$ziel_res"
done

# Die Precons liegen unter quest/, der Rest von quest (Kampagne, Weltkarte, ~39 MB) wird nicht gebraucht.
mkdir -p "$ziel_res/quest"
kopiere "$quelle/quest/commanderprecons" "$ziel_res/quest"

echo "trim-res.sh: fertig, Groesse von $ziel_res:"
du -sh "$ziel_res"
