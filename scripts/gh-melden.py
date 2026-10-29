#!/usr/bin/env python3
"""Schreibt eine GitHub-Actions-Annotation (notice/warning/error) mit mehrzeiligem Text.

Aufruf: scripts/gh-melden.py <notice|warning|error> <Titel> <Datei oder -> [max. Zeichen]

Wozu: Lauf-Logs und Artefakte liegen auf Hosts, die von Claudes Cloud-Umgebung aus gesperrt sind; die
Annotationen eines Jobs dagegen liefert die normale REST-API
(GET /repos/{owner}/{repo}/check-runs/{job_id}/annotations). Was ein spaeterer automatischer Bericht lesen
soll, steht deshalb hier drin - gekuerzt, weil GitHub lange Meldungen abschneidet.
"""
import sys


def maskieren(text, titel=False):
    text = text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    if titel:
        text = text.replace(":", "%3A").replace(",", "%2C")
    return text


def main():
    if len(sys.argv) < 4 or sys.argv[1] not in ("notice", "warning", "error"):
        sys.exit(__doc__)
    stufe, titel, quelle = sys.argv[1:4]
    grenze = int(sys.argv[4]) if len(sys.argv) > 4 else 4000
    text = sys.stdin.read() if quelle == "-" else open(quelle, encoding="utf-8", errors="replace").read()
    text = text.strip() or "(leer)"
    if len(text) > grenze:
        text = text[:grenze] + "\n[... gekuerzt]"
    print(f"::{stufe} title={maskieren(titel, True)}::{maskieren(text)}")


if __name__ == "__main__":
    main()
