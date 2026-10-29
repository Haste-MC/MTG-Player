#!/usr/bin/env bash
# Legt fuer ein neues Forge-Release einen Kandidaten-Zweig im Fork an: der Release-Stand von upstream plus
# unsere Fork-Commits, so weit sie sich ohne Konflikt uebertragen lassen. Entscheidet NICHTS am KI-Verhalten:
# ein Commit, der nicht sauber passt, wird uebersprungen und im Bericht genannt - aufloesen ist Handarbeit
# (siehe docs/forge-fork.md, "Switch to 2.0.15" fuer den letzten Wechsel).
#
# Aufruf im Wurzelverzeichnis von MTG-Player:
#   scripts/fork-kandidat.sh forge-2.0.16
#   scripts/fork-kandidat.sh --ref upstream/master --version 2.0.16-SNAPSHOT   # Probelauf ohne Release
# Optionen:
#   --basis <ref>    Stand mit unseren Commits (Vorgabe: origin/mtg-player, wird geholt)
#   --zweig <name>   Name des neuen Zweigs (Vorgabe: mtg-player-<version>)
#   --forge <dir>    Git-Verzeichnis des Forks (Vorgabe: forge)
#
# Ergebnis: Zweig im Fork-Verzeichnis (NICHT geschoben) und fork-kandidat-bericht.md im Aufrufverzeichnis.
# Rueckgabewerte: 0 = alle Commits uebertragen, 3 = mindestens ein Konflikt (Zweig steht trotzdem),
#                 1 = Fehlschlag (kein brauchbarer Zweig).
#
# Welche Commits zaehlen: alles zwischen dem Release-Tag, auf dem die Basis sitzt (aus deren pom.xml:
# "<X>-mtgplayer" -> forge-<X>), und der Basis, ohne Merges, ohne Commits des Kartendaten-Bots und ohne
# Commits, die nur Kartendaten anfassen - die kommen nach dem Wechsel ueber den woechentlichen Abgleich.
# Ein Commit, dessen Betreff die alte Versionsnummer traegt (Versions-Commit, Hinweis in MODIFICATIONS.md),
# wird mit der neuen Nummer uebertragen statt woertlich.
set -euo pipefail

FORGE=forge
BASIS=origin/mtg-player
ZWEIG=""
NEU_REF=""
NEU_VERSION=""
UPSTREAM_URL="${FORK_KANDIDAT_UPSTREAM_URL:-https://github.com/Card-Forge/forge.git}"
KARTENDATEN='^forge-gui/res/(cardsfolder|editions|tokenscripts)/'
BOT='41898282+github-actions[bot]@users.noreply.github.com'

fehler() { echo "FEHLER: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --basis) BASIS="$2"; shift 2 ;;
    --zweig) ZWEIG="$2"; shift 2 ;;
    --forge) FORGE="$2"; shift 2 ;;
    --ref) NEU_REF="$2"; shift 2 ;;
    --version) NEU_VERSION="$2"; shift 2 ;;
    -*) fehler "unbekannte Option: $1" ;;
    forge-*) NEU_REF="$1"; NEU_VERSION="${1#forge-}"; shift ;;
    *) fehler "erwartet ein Release-Tag wie forge-2.0.16, nicht: $1" ;;
  esac
done
[ -n "$NEU_REF" ] && [ -n "$NEU_VERSION" ] || fehler "Aufruf: scripts/fork-kandidat.sh forge-<version>"
[ -d "$FORGE/.git" ] || [ -f "$FORGE/.git" ] || fehler "$FORGE ist kein Git-Verzeichnis"
ZWEIG="${ZWEIG:-mtg-player-$NEU_VERSION}"
BERICHT="$PWD/fork-kandidat-bericht.md"
g() { git -C "$FORGE" "$@"; }

if [ -n "$(g status --porcelain --untracked-files=no)" ]; then
  fehler "$FORGE hat nicht eingecheckte Aenderungen - erst aufraeumen"
fi
if g cherry-pick --abort >/dev/null 2>&1; then echo "liegengebliebenes cherry-pick abgebrochen" >&2; fi

# --- Staende holen ---------------------------------------------------------------------------------
g remote get-url upstream >/dev/null 2>&1 || g remote add upstream "$UPSTREAM_URL"
if [[ "$BASIS" == origin/* ]]; then
  # Tief genug, um bis zum alten Release-Tag zu reichen; ein voller Klon bleibt voll.
  TIEFE=()
  [ "$(g rev-parse --is-shallow-repository)" = true ] && TIEFE=(--depth=300)
  g fetch -q "${TIEFE[@]}" origin "+refs/heads/${BASIS#origin/}:refs/remotes/$BASIS"
fi
g rev-parse -q --verify "$BASIS^{commit}" >/dev/null || fehler "Basis $BASIS ist unbekannt"

ALT_VERSION=$(g show "$BASIS:pom.xml" | python3 -c '
import re, sys
m = re.search(r"<artifactId>forge</artifactId>.*?<version>([^<]+)</version>", sys.stdin.read(), re.S)
print(m.group(1) if m else "")')
[[ "$ALT_VERSION" == *-mtgplayer ]] || fehler "Basis-Version '$ALT_VERSION' endet nicht auf -mtgplayer"
ALT_VERSION="${ALT_VERSION%-mtgplayer}"
ALT_TAG="forge-$ALT_VERSION"
[ "$ALT_VERSION" != "$NEU_VERSION" ] || fehler "die Basis sitzt schon auf $ALT_VERSION"

for t in "$ALT_TAG" "$NEU_REF"; do
  case "$t" in
    forge-*) g fetch -q --depth=1 upstream "+refs/tags/$t:refs/tags/$t" || fehler "Tag $t gibt es bei upstream nicht" ;;
    upstream/*) g fetch -q --depth=1 upstream "+refs/heads/${t#upstream/}:refs/remotes/$t" ;;
  esac
done
g merge-base --is-ancestor "$ALT_TAG" "$BASIS" \
  || fehler "$ALT_TAG ist kein Vorfahr von $BASIS (Basis nicht tief genug geholt, oder sie sitzt nicht auf dem Tag)"

# --- Unsere Commits ermitteln ------------------------------------------------------------------------
mapfile -t ALLE < <(g rev-list --reverse --no-merges "$ALT_TAG..$BASIS")
UEBERTRAGEN=(); KARTEN=()
for c in "${ALLE[@]}"; do
  if [ "$(g log -1 --format=%ae "$c")" = "$BOT" ]; then KARTEN+=("$c"); continue; fi
  if ! g diff-tree --no-commit-id --name-only -r "$c" | grep -qvE "$KARTENDATEN"; then KARTEN+=("$c"); continue; fi
  UEBERTRAGEN+=("$c")
done
echo "Basis $BASIS auf $ALT_TAG, Ziel $NEU_REF ($NEU_VERSION): ${#UEBERTRAGEN[@]} Commits, ${#KARTEN[@]} Kartendaten-Commits ausgelassen"

# --- Zweig bauen ---------------------------------------------------------------------------------------
g checkout -q -B "$ZWEIG" "$NEU_REF"
ALT_RE="${ALT_VERSION//./\\.}"
declare -A STATUS DETAIL
VERSION_NEU=""
FLATTEN=0
# Setzt die Fork-Version im Arbeitsbaum und gibt sie aus. Upstream hat nach 2.0.15 auf "CI-friendly versions"
# umgestellt (<version>${revision}</version>, <revision>${versionCode}${snapshotName}</revision>); dann wird
# <revision> zu "${versionCode}-mtgplayer". Sonst wie bisher: jedes <version>NEU</version> der pom.xml-Dateien.
version_setzen() {
  python3 - "$FORGE" "$NEU_VERSION" <<'PY'
import pathlib, re, subprocess, sys
repo, neu = pathlib.Path(sys.argv[1]), sys.argv[2]
wurzel = repo / "pom.xml"
text = wurzel.read_text(encoding="utf-8")
m = re.search(r"<revision>([^<]*)</revision>", text)
if m:
    alt = m.group(1)
    rev = "${versionCode}-mtgplayer" if "${versionCode}" in alt else neu + "-mtgplayer"
    neu_text = text[:m.start(1)] + rev + text[m.end(1):]
    # Upstream laesst flatten-maven-plugin (loest ${revision} in den installierten POMs auf) erst bei "deploy"
    # laufen. Ein "mvn install" - unser Weg, README und Workflows - legte dann POMs mit einem woertlichen
    # ${revision} ins lokale Repository, und die Bridge faende Forge nicht ("forge:forge:pom:${revision}").
    # process-resources ist die Phase, die das Plugin selbst empfiehlt.
    neu_text = re.sub(r"(<id>flatten</id>\s*<phase>)deploy(</phase>)", r"\1process-resources\2", neu_text)
    wurzel.write_text(neu_text, encoding="utf-8")
    props = dict(re.findall(r"<([A-Za-z][\w.]*)>([^<]*)</\1>", text.split("<properties>", 1)[1].split("</properties>", 1)[0]))
    props["revision"] = rev
    wert = rev
    for _ in range(10):
        wert = re.sub(r"\$\{([\w.]+)\}", lambda x: props.get(x.group(1), x.group(0)), wert)
    print(wert)
else:
    dateien = subprocess.run(["git", "-C", str(repo), "ls-files", "*pom.xml", "pom.xml"],
                             capture_output=True, text=True, check=True).stdout.split()
    alt, ersatz = f"<version>{neu}</version>", f"<version>{neu}-mtgplayer</version>"
    for d in dateien:
        p = repo / d
        t = p.read_text(encoding="utf-8")
        if alt in t:
            p.write_text(t.replace(alt, ersatz), encoding="utf-8")
    print(neu + "-mtgplayer")
PY
}

for c in "${UEBERTRAGEN[@]}"; do
  betreff=$(g log -1 --format=%s "$c")
  if [[ "$betreff" =~ (^|[^0-9.])$ALT_RE([^0-9]|$) ]] \
     && ! g diff-tree --no-commit-id --name-only -r "$c" | grep -qv 'pom\.xml$'; then
    # Der Versions-Commit (nur pom.xml, alte Nummer im Betreff): nicht nachspielen, neu setzen - das
    # Versionsschema kann sich zwischen Releases aendern.
    if VERSION_NEU=$(version_setzen) && [[ "$VERSION_NEU" == *-mtgplayer ]] \
       && g add -u -- '*pom.xml' && ! g diff --cached --quiet; then
      GIT_AUTHOR_NAME=$(g log -1 --format=%an "$c") GIT_AUTHOR_EMAIL=$(g log -1 --format=%ae "$c") \
        zusatz="(replaces $c, set anew for $NEU_VERSION)"
        if g diff --cached -- pom.xml | grep -q '^+.*process-resources'; then
          zusatz="Also runs flatten-maven-plugin at process-resources instead of deploy, so that a plain
'mvn install' puts resolved POMs (no literal \${revision}) into the local repository for the
MTG-Player bridge.

$zusatz"
          FLATTEN=1
        fi
        g commit -q -m "mtg-player: version $VERSION_NEU (fork branch for MTG-Player)" -m "$zusatz"
      STATUS[$c]=version; DETAIL[$c]=$VERSION_NEU
    else
      g reset -q --hard HEAD
      STATUS[$c]=konflikt; DETAIL[$c]="Version liess sich nicht setzen"
    fi
    continue
  fi
  if [[ "$betreff" =~ (^|[^0-9.])$ALT_RE([^0-9]|$) ]]; then
    # Versionsbezogen: Diff mit der neuen Nummer anwenden, Nachricht ebenso; Autor bleibt.
    if g show --format= --binary "$c" | sed -E "s/(^|[^0-9.])$ALT_RE([^0-9]|$)/\1$NEU_VERSION\2/g" \
         | g apply --index --3way >/dev/null 2>&1 && ! g diff --cached --quiet; then
      nachricht=$(g log -1 --format=%B "$c" | sed -E "s/(^|[^0-9.])$ALT_RE([^0-9]|$)/\1$NEU_VERSION\2/g")
      GIT_AUTHOR_NAME=$(g log -1 --format=%an "$c") GIT_AUTHOR_EMAIL=$(g log -1 --format=%ae "$c") \
        g commit -q -m "$nachricht" -m "(adapted from $c for $NEU_VERSION)"
      STATUS[$c]=angepasst
    else
      g reset -q --hard HEAD
      STATUS[$c]=konflikt; DETAIL[$c]="Versionsanpassung liess sich nicht anwenden"
    fi
    continue
  fi
  if g cherry-pick -x "$c" >/dev/null 2>&1; then
    STATUS[$c]=ok
    continue
  fi
  offen=$(g diff --name-only --diff-filter=U)
  if [ -z "$offen" ] && g diff --cached --quiet; then
    # Nichts uebrig: upstream enthaelt die Aenderung schon (oder eine gleichwertige).
    g cherry-pick --skip >/dev/null 2>&1 || g cherry-pick --abort >/dev/null 2>&1 || true
    STATUS[$c]=leer
  else
    g cherry-pick --abort >/dev/null 2>&1 || g reset -q --hard HEAD
    STATUS[$c]=konflikt; DETAIL[$c]=$(printf '%s\n' "$offen" | paste -sd ',' -)
  fi
done

# --- Bericht ----------------------------------------------------------------------------------------------
konflikte=0
for c in "${UEBERTRAGEN[@]}"; do [ "${STATUS[$c]}" = konflikt ] && konflikte=$((konflikte + 1)); done
{
  echo "## Fork-Kandidat $ZWEIG"
  echo
  echo "Basis \`$BASIS\` ($(g rev-parse --short "$BASIS"), auf \`$ALT_TAG\`) → \`$NEU_REF\` ($(g rev-parse --short "$NEU_REF"))."
  echo "Zweig \`$ZWEIG\` steht auf $(g rev-parse --short HEAD). Maven-Version: \`${VERSION_NEU:-nicht gesetzt}\`."
  echo
  echo "${#UEBERTRAGEN[@]} Fork-Commits: $(( ${#UEBERTRAGEN[@]} - konflikte )) übertragen, **$konflikte mit Konflikt (übersprungen)**."
  echo "${#KARTEN[@]} Kartendaten-Commits ausgelassen (kommen über den wöchentlichen Abgleich)."
  echo
  echo "| Commit | Betreff | Ergebnis |"
  echo "|---|---|---|"
  for c in "${UEBERTRAGEN[@]}"; do
    case "${STATUS[$c]}" in
      ok) e="übertragen" ;;
      angepasst) e="übertragen, Versionsnummer im Text auf $NEU_VERSION angepasst" ;;
      version) e="neu gesetzt: \`${DETAIL[$c]}\`" ;;
      leer) e="leer – upstream enthält die Änderung schon" ;;
      konflikt) e="**Konflikt:** \`${DETAIL[$c]}\`" ;;
    esac
    echo "| $(g rev-parse --short "$c") | $(g log -1 --format=%s "$c" | sed 's/|/\\|/g') | $e |"
  done
  if [ "$FLATTEN" = 1 ]; then
    echo
    echo "**Build-Hinweis:** Upstream versioniert über \`\${revision}\` und löst das erst beim \`deploy\` auf. Der"
    echo "Versions-Commit zieht \`flatten-maven-plugin\` deshalb auf \`process-resources\` vor, sonst findet die"
    echo "Bridge nach \`mvn install\` kein Forge (\`forge:forge:pom:\${revision}\`)."
  fi
  if [ "$konflikte" -gt 0 ]; then
    echo
    echo "Übersprungene Commits fehlen im Zweig ganz. Spätere Commits, die auf ihnen aufbauen, können deshalb"
    echo "ebenfalls im Konflikt stehen, ohne selbst das Problem zu sein. Tests zu den fehlenden Änderungen schlagen"
    echo "erwartbar fehl."
  fi
} > "$BERICHT"
echo "Bericht: $BERICHT"
[ "$konflikte" -eq 0 ] || exit 3
