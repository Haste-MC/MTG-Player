# M0+M1: Forge-Anbindung und headless KI-Spiel – Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Forge 2.0.14 als Submodule bauen, eine Java-Bridge anlegen, die Forges Kartendatenbank lädt, und ein komplettes Commander-Spiel mit 4 KI-Spielern headless bis zum Ende laufen lassen.

**Architecture:** Die Bridge ist ein eigenständiges Maven-Projekt, das gegen die lokal gebauten Artefakte `forge:forge-gui` und `forge:forge-ai` (Version 2.0.14) kompiliert. `WebGuiBase` implementiert `IGuiBase` als No-Op, damit `FModel.initialize` läuft. `AiMatch` benutzt `Match`/`Game` aus `forge-game` direkt (kein `HostedMatch`, kein `IGuiGame` – das kommt in Plan 2 mit dem menschlichen Sitz).

**Tech Stack:** Java 17, Maven 3.9, JUnit 5, Forge 2.0.14 (Git-Submodule, Tag `forge-2.0.14`)

## Global Constraints

- Java **17** (Forge: `maven.compiler.release` 17; Enforcer verlangt ≥ 17), Maven **≥ 3.8.1**.
- Forge-Submodule bleibt **unverändert**; kein Patch, kein Fork.
- Forges Nutzer-/Cache-Daten liegen unter `~/.mtg-player/forge/` und `~/.mtg-player/cache/`, nie unter `~/.forge`.
- Bridge-Package: `mtgplayer`. Klassennamen und Signaturen aus den "Interfaces"-Blöcken sind verbindlich für spätere Tasks.
- Commit-Messages: deutsch, Kleinschreibung, Präfix `m0:`/`m1:`, Abschluss mit `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.

---

## Dateistruktur

```
MTG-Player/
├── .gitignore
├── .gitmodules
├── forge/                                   Submodule, Tag forge-2.0.14
├── bridge/
│   ├── pom.xml
│   ├── assets/
│   │   ├── res -> ../../forge/forge-gui/res  (Symlink, committet)
│   │   └── forge.profile.properties          (generiert, ignoriert)
│   └── src/
│       ├── main/java/mtgplayer/
│       │   ├── Main.java                     Einstieg: Boot + KI-Spiel
│       │   ├── forge/WebGuiBase.java         IGuiBase-No-Op
│       │   ├── forge/ForgeBoot.java          Profil schreiben, GuiBase setzen, FModel laden
│       │   ├── forge/Precons.java            Commander-Precons auflisten und laden
│       │   └── match/AiMatch.java            Match/Game mit KI-Spielern, Log, Turn-Cap
│       └── test/java/mtgplayer/
│           ├── forge/ForgeBootTest.java
│           ├── forge/PreconsTest.java
│           └── match/AiMatchTest.java
└── docs/superpowers/...
```

Verantwortungen: `ForgeBoot` ist der einzige Ort, der Forge initialisiert (idempotent). `Precons` weiß, wo Decks liegen. `AiMatch` weiß, wie ein Spiel läuft. `Main` verdrahtet nur.

---

### Task 1: Toolchain und Forge-Build

**Files:**
- Create: `.gitignore`
- Create: `.gitmodules` (via `git submodule add`)
- Create: `forge/` (Submodule)

**Interfaces:**
- Produces: Maven-Artefakte `forge:forge-core`, `forge:forge-game`, `forge:forge-ai`, `forge:forge-gui` in Version `2.0.14` im lokalen `~/.m2`.

- [ ] **Step 1: JDK 17 und Maven installieren** (braucht sudo – den Befehl dem Nutzer zum Ausführen geben, nicht selbst mit Root-Rechten laufen lassen)

```bash
sudo apt update && sudo apt install -y openjdk-17-jdk-headless maven
```

- [ ] **Step 2: Versionen prüfen**

Run: `java -version 2>&1 | head -1 && mvn -version | head -1`
Expected: `openjdk version "17...` und `Apache Maven 3.9...`

- [ ] **Step 3: .gitignore anlegen**

```gitignore
target/
node_modules/
bridge/assets/forge.profile.properties
*.log
.idea/
*.iml
```

- [ ] **Step 4: Forge als Submodule auf Tag forge-2.0.14 hinzufügen**

```bash
cd /home/kevin/projects/MTG-Player
git submodule add --depth 1 https://github.com/Card-Forge/forge.git forge
cd forge && git fetch --depth 1 origin tag forge-2.0.14 && git checkout forge-2.0.14 && cd ..
git add .gitmodules forge .gitignore
```

Run: `cd forge && git describe --tags && cd ..`
Expected: `forge-2.0.14`

- [ ] **Step 5: Forge-Module bauen und ins lokale Maven-Repo installieren** (dauert beim ersten Mal 5–15 Minuten, lädt Dependencies)

```bash
cd /home/kevin/projects/MTG-Player/forge && mvn -q -pl forge-gui -am install -DskipTests -Dcheckstyle.skip -Dmaven.javadoc.skip=true
```

Run: `ls ~/.m2/repository/forge/forge-gui/2.0.14/ ~/.m2/repository/forge/forge-ai/2.0.14/`
Expected: jeweils eine `forge-gui-2.0.14.jar` bzw. `forge-ai-2.0.14.jar`

- [ ] **Step 6: Commit**

```bash
cd /home/kevin/projects/MTG-Player
git commit -m "m0: forge 2.0.14 als submodule, gitignore

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: Bridge-Skeleton und ForgeBoot (Kartendatenbank laden)

**Files:**
- Create: `bridge/pom.xml`
- Create: `bridge/assets/res` (Symlink)
- Create: `bridge/src/main/java/mtgplayer/forge/WebGuiBase.java`
- Create: `bridge/src/main/java/mtgplayer/forge/ForgeBoot.java`
- Test: `bridge/src/test/java/mtgplayer/forge/ForgeBootTest.java`

**Interfaces:**
- Consumes: Forge-Artefakte aus Task 1.
- Produces:
  - `mtgplayer.forge.ForgeBoot.init()` – `public static synchronized void init()`; idempotent; danach ist `forge.StaticData.instance()` befüllt.
  - `mtgplayer.forge.ForgeBoot.cardCount()` – `public static int cardCount()`; Anzahl eindeutiger Karten.
  - `mtgplayer.forge.ForgeBoot.dataDir()` – `public static Path dataDir()`; `~/.mtg-player/`.

- [ ] **Step 1: pom.xml schreiben**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>mtgplayer</groupId>
    <artifactId>bridge</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.release>17</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <forge.version>2.0.14</forge.version>
        <junit.version>5.10.2</junit.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>forge</groupId>
            <artifactId>forge-gui</artifactId>
            <version>${forge.version}</version>
        </dependency>
        <dependency>
            <groupId>forge</groupId>
            <artifactId>forge-ai</artifactId>
            <version>${forge.version}</version>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
                <configuration>
                    <argLine>-Xmx4g</argLine>
                    <trimStackTrace>false</trimStackTrace>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.2.0</version>
                <configuration>
                    <mainClass>mtgplayer.Main</mainClass>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Assets-Verzeichnis mit Symlink anlegen**

```bash
mkdir -p /home/kevin/projects/MTG-Player/bridge/assets
ln -s ../../forge/forge-gui/res /home/kevin/projects/MTG-Player/bridge/assets/res
```

Run: `ls /home/kevin/projects/MTG-Player/bridge/assets/res/cardsfolder | head -3`
Expected: Verzeichnisse `a`, `b`, `c` (Kartenskripte nach Anfangsbuchstabe)

- [ ] **Step 3: Failing Test schreiben**

`bridge/src/test/java/mtgplayer/forge/ForgeBootTest.java`:

```java
package mtgplayer.forge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.StaticData;
import org.junit.jupiter.api.Test;

class ForgeBootTest {

    @Test
    void initLaedtKartendatenbank() {
        ForgeBoot.init();
        assertTrue(StaticData.instance() != null, "StaticData muss nach init existieren");
        int n = ForgeBoot.cardCount();
        assertTrue(n > 20000, "erwartet > 20000 Karten, war " + n);
    }

    @Test
    void initIstIdempotent() {
        ForgeBoot.init();
        int a = ForgeBoot.cardCount();
        ForgeBoot.init();
        assertTrue(a == ForgeBoot.cardCount());
    }
}
```

- [ ] **Step 4: Test laufen lassen, muss fehlschlagen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test -Dtest=ForgeBootTest`
Expected: COMPILATION ERROR, `ForgeBoot` nicht gefunden

- [ ] **Step 5: WebGuiBase schreiben (IGuiBase als No-Op)**

`bridge/src/main/java/mtgplayer/forge/WebGuiBase.java`:

```java
package mtgplayer.forge;

import forge.gamemodes.match.HostedMatch;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;
import org.jupnp.UpnpServiceConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Forges Andockpunkt für eine UI. Wir haben keine – der Browser kommt später
 * über IGuiGame dazu. Alles hier ist No-Op oder ein sinnvoller Default, damit
 * FModel.initialize und die Spiel-Engine ohne Swing/libGDX laufen.
 */
public final class WebGuiBase implements IGuiBase {

    private final String assetsDir;
    private final ExecutorService uiThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bridge-ui");
        t.setDaemon(true);
        return t;
    });
    private volatile Thread uiThreadRef;

    /** @param assetsDir absoluter Pfad mit abschließendem "/" – darin liegt "res/" */
    public WebGuiBase(String assetsDir) {
        this.assetsDir = assetsDir.endsWith("/") ? assetsDir : assetsDir + "/";
        uiThread.submit(() -> uiThreadRef = Thread.currentThread());
    }

    @Override public boolean isRunningOnDesktop() { return true; }
    @Override public boolean isLibgdxPort() { return false; }
    @Override public String getCurrentVersion() { return "mtg-player"; }
    @Override public String getAssetsDir() { return assetsDir; }

    @Override public void invokeInEdtNow(Runnable runnable) {
        if (isGuiThread()) { runnable.run(); } else { invokeInEdtAndWait(runnable); }
    }
    @Override public void invokeInEdtLater(Runnable runnable) { uiThread.submit(runnable); }
    @Override public void invokeInEdtAndWait(Runnable proc) {
        if (isGuiThread()) { proc.run(); return; }
        Future<?> f = uiThread.submit(proc);
        try { f.get(); } catch (Exception e) { throw new RuntimeException(e); }
    }
    @Override public void runBackgroundTask(String message, Runnable task) {
        Thread t = new Thread(task, "bridge-bg");
        t.setDaemon(true);
        t.start();
    }
    @Override public boolean isGuiThread() { return Thread.currentThread() == uiThreadRef; }

    @Override public ImageFetcher getImageFetcher() { return null; }
    @Override public ISkinImage getSkinIcon(FSkinProp skinProp) { return null; }
    @Override public ISkinImage getUnskinnedIcon(String path) { return null; }
    @Override public ISkinImage getCardArt(PaperCard card, boolean backFace) { return null; }
    @Override public ISkinImage createLayeredImage(PaperCard card, FSkinProp background, String overlayFilename, float opacity) { return null; }
    @Override public void clearImageCache() { }
    @Override public String encodeSymbols(String str, boolean formatReminderText) { return str; }
    @Override public int getAvatarCount() { return 0; }
    @Override public int getSleevesCount() { return 0; }
    @Override public float getScreenScale() { return 1f; }
    @Override public void preventSystemSleep(boolean preventSleep) { }
    @Override public void download(GuiDownloadService service, Consumer<Boolean> callback) { callback.accept(false); }
    @Override public void copyToClipboard(String text) { }
    @Override public void browseToUrl(String url) { }

    @Override public void showCardList(String title, String message, List<PaperCard> list) { }
    @Override public boolean showBoxedProduct(String title, String message, List<PaperCard> list) { return false; }
    @Override public void showBugReportDialog(String title, String text, boolean showExitAppBtn) {
        System.err.println("[forge] " + title + ": " + text);
    }
    @Override public void showImageDialog(ISkinImage image, String message, String title) { }
    @Override public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) {
        return defaultOption;
    }
    @Override public String showInputDialog(String message, String title, FSkinProp icon, String initialInput, List<String> inputOptions, boolean isNumeric) {
        if (initialInput != null) return initialInput;
        if (inputOptions != null && !inputOptions.isEmpty()) return inputOptions.get(0);
        return isNumeric ? "0" : "";
    }
    @Override public String showFileDialog(String title, String defaultDir) { return null; }
    @Override public File getSaveFile(File defaultFile) { return defaultFile; }
    @Override public <T> List<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax, List<T> sourceChoices, List<T> destChoices) {
        return new ArrayList<>(sourceChoices);
    }
    @Override public <T> List<T> getChoices(String message, int min, int max, Collection<T> choices, Collection<T> selected, FSerializableFunction<T, String> display) {
        List<T> out = new ArrayList<>();
        for (T c : choices) { if (out.size() >= Math.max(min, 1)) break; out.add(c); }
        return out;
    }
    @Override public PaperCard chooseCard(String title, String message, List<PaperCard> list) {
        return list.isEmpty() ? null : list.get(0);
    }

    @Override public boolean isSupportedAudioFormat(File file) { return false; }
    @Override public IAudioClip createAudioClip(String filename) { return null; }
    @Override public IAudioMusic createAudioMusic(String filename) { return null; }
    @Override public void startAltSoundSystem(String filename, boolean isSynchronized) { }
    @Override public void showSpellShop() { }
    @Override public void showBazaar() { }

    @Override public IGuiGame getNewGuiGame() {
        throw new UnsupportedOperationException("kein IGuiGame in M1 – kommt mit dem menschlichen Sitz");
    }
    @Override public HostedMatch hostMatch() { return new HostedMatch(); }
    @Override public UpnpServiceConfiguration getUpnpPlatformService() { return null; }
    @Override public boolean hasNetGame() { return false; }
}
```

- [ ] **Step 6: ForgeBoot schreiben**

`bridge/src/main/java/mtgplayer/forge/ForgeBoot.java`:

```java
package mtgplayer.forge;

import forge.StaticData;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Einziger Ort, der Forge hochfährt. Reihenfolge ist zwingend:
 * 1. forge.profile.properties schreiben (Forge liest sie beim ersten Zugriff auf ForgeConstants)
 * 2. GuiBase.setInterface (ForgeConstants.ASSETS_DIR kommt daher – statisch, einmalig)
 * 3. FModel.initialize (lädt ~30k Kartenskripte, dauert 20–60 s)
 */
public final class ForgeBoot {

    private static boolean initialized;

    private ForgeBoot() { }

    /** ~/.mtg-player/ – eigene Daten der Bridge und Forges Nutzerdaten darunter. */
    public static Path dataDir() {
        return Paths.get(System.getProperty("user.home"), ".mtg-player");
    }

    /** bridge/assets/ – wird relativ zum Arbeitsverzeichnis (bridge/) oder per -Dmtgplayer.assets gefunden. */
    public static Path assetsDir() {
        String override = System.getProperty("mtgplayer.assets");
        Path p = override != null ? Paths.get(override) : Paths.get("assets");
        return p.toAbsolutePath().normalize();
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        Path assets = assetsDir();
        if (!Files.isDirectory(assets.resolve("res").resolve("cardsfolder"))) {
            throw new IllegalStateException("assets/res/cardsfolder fehlt unter " + assets
                    + " – Symlink bridge/assets/res -> ../../forge/forge-gui/res vorhanden?");
        }
        writeProfile(assets);
        GuiBase.setInterface(new WebGuiBase(assets.toString() + "/"));
        FModel.initialize(null, prefs -> {
            prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            prefs.setPref(FPref.UI_LANGUAGE, "en-US");
            return null;
        });
        initialized = true;
    }

    public static int cardCount() {
        return StaticData.instance().getCommonCards().getUniqueCards().size();
    }

    private static void writeProfile(Path assets) {
        Path user = dataDir().resolve("forge");
        Path cache = dataDir().resolve("cache");
        String content = "userDir=" + user + "/\n" + "cacheDir=" + cache + "/\n";
        try {
            Files.createDirectories(user);
            Files.createDirectories(cache);
            Files.writeString(assets.resolve("forge.profile.properties"), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("kann forge.profile.properties nicht schreiben", e);
        }
    }
}
```

- [ ] **Step 7: Test laufen lassen, muss bestehen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test -Dtest=ForgeBootTest`
Expected: `Tests run: 2, Failures: 0` (Dauer 30–90 s wegen Kartenladen). Falls Forge beim Laden eine fehlende Ressource meldet, steht der Pfad in der Exception – dann stimmt der Symlink oder `assetsDir()` nicht.

- [ ] **Step 8: Prüfen, dass Forges Daten im richtigen Verzeichnis liegen**

Run: `ls ~/.mtg-player/forge ~/.mtg-player/cache && test ! -d ~/.forge && echo OK`
Expected: Unterverzeichnisse (z. B. `preferences`, `decks`) und am Ende `OK`. Existiert `~/.forge`, hat Forge die Profildatei nicht gelesen – Reihenfolge in `init()` prüfen.

- [ ] **Step 9: Commit**

```bash
cd /home/kevin/projects/MTG-Player
git add bridge/pom.xml bridge/assets/res bridge/src
git commit -m "m0: bridge-skeleton, webguibase, forgeboot laedt die kartendatenbank

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: Commander-Precons laden

**Files:**
- Create: `bridge/src/main/java/mtgplayer/forge/Precons.java`
- Test: `bridge/src/test/java/mtgplayer/forge/PreconsTest.java`

**Interfaces:**
- Consumes: `ForgeBoot.init()`.
- Produces:
  - `mtgplayer.forge.Precons.names()` – `public static List<String> names()`; sortierte Decknamen wie `"Abzan Armor [TDC] [2025]"`.
  - `mtgplayer.forge.Precons.load(String name)` – `public static forge.deck.Deck load(String name)`; wirft `IllegalArgumentException` bei unbekanntem Namen.

- [ ] **Step 1: Failing Test schreiben**

`bridge/src/test/java/mtgplayer/forge/PreconsTest.java`:

```java
package mtgplayer.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.deck.Deck;
import forge.deck.DeckSection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PreconsTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @Test
    void namesListetAlleCommanderPrecons() {
        var names = Precons.names();
        assertTrue(names.size() > 100, "erwartet > 100 Precons, war " + names.size());
        assertTrue(names.contains("Abzan Armor [TDC] [2025]"));
        assertEquals(names, names.stream().sorted().toList(), "muss sortiert sein");
    }

    @Test
    void loadLiefertVollstaendigesDeck() {
        Deck d = Precons.load("Abzan Armor [TDC] [2025]");
        assertEquals(1, d.getCommanders().size());
        assertEquals("Felothar the Steadfast", d.getCommanders().get(0).getName());
        assertEquals(99, d.get(DeckSection.Main).countAll());
    }

    @Test
    void loadUnbekanntWirft() {
        assertThrows(IllegalArgumentException.class, () -> Precons.load("Gibt es nicht"));
    }
}
```

- [ ] **Step 2: Test laufen lassen, muss fehlschlagen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test -Dtest=PreconsTest`
Expected: COMPILATION ERROR, `Precons` nicht gefunden

- [ ] **Step 3: Precons schreiben**

`bridge/src/main/java/mtgplayer/forge/Precons.java`:

```java
package mtgplayer.forge;

import forge.deck.Deck;
import forge.model.FModel;
import forge.util.storage.IStorage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Forges mitgelieferte Commander-Precons (res/quest/commanderprecons). */
public final class Precons {

    private Precons() { }

    private static IStorage<Deck> storage() {
        return FModel.getDecks().getCommanderPrecons();
    }

    public static List<String> names() {
        List<String> names = new ArrayList<>(storage().getItemNames());
        Collections.sort(names);
        return names;
    }

    public static Deck load(String name) {
        Deck d = storage().get(name);
        if (d == null) {
            throw new IllegalArgumentException("unbekanntes Precon: " + name);
        }
        return d;
    }
}
```

- [ ] **Step 4: Test laufen lassen, muss bestehen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test -Dtest=PreconsTest`
Expected: `Tests run: 3, Failures: 0`. Schlägt `countAll` mit 99 fehl, die tatsächliche Zahl ansehen: Precons mit Partner-Commandern haben 98 Main-Karten – dann ist das Test-Deck falsch gewählt, nicht der Code.

- [ ] **Step 5: Commit**

```bash
cd /home/kevin/projects/MTG-Player
git add bridge/src
git commit -m "m1: commander-precons aus forge auflisten und laden

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: AiMatch – ein KI-Spiel bis zum Ende

**Files:**
- Create: `bridge/src/main/java/mtgplayer/match/AiMatch.java`
- Test: `bridge/src/test/java/mtgplayer/match/AiMatchTest.java`

**Interfaces:**
- Consumes: `ForgeBoot.init()`, `Precons.load(String)`.
- Produces:
  - `mtgplayer.match.AiMatch.Result` – `public record Result(String winner, String reason, int turns)`; `winner` ist `null` bei Unentschieden/Abbruch.
  - `mtgplayer.match.AiMatch.play(List<Deck> decks, List<String> names, int maxTurns, Consumer<String> log)` – `public static Result play(...)`; blockiert bis Spielende; `log` bekommt jede Forge-Logzeile.

- [ ] **Step 1: Failing Test schreiben**

`bridge/src/test/java/mtgplayer/match/AiMatchTest.java`:

```java
package mtgplayer.match;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.deck.Deck;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

class AiMatchTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void vierKiSpielenCommanderZuEnde() {
        List<String> names = List.of("Abzan Armor [TDC] [2025]", "Adaptive Enchantment [C18] [2018]",
                "Ahoy Mateys [LCC] [2023]", "Animated Army [BLC] [2024]");
        List<Deck> decks = names.stream().map(Precons::load).toList();
        List<String> log = new ArrayList<>();

        AiMatch.Result r = AiMatch.play(decks, List.of("KI 1", "KI 2", "KI 3", "KI 4"), 60, log::add);

        assertNotNull(r);
        assertTrue(r.turns() > 1, "mindestens ein Zug gespielt, war " + r.turns());
        assertTrue(log.size() > 20, "Log erwartet, war " + log.size() + " Zeilen");
        assertTrue(r.turns() <= 60, "turn-cap nicht eingehalten: " + r.turns());
        System.out.println("Gewinner: " + r.winner() + " (" + r.reason() + ") nach " + r.turns() + " Zuegen");
    }
}
```

- [ ] **Step 2: Test laufen lassen, muss fehlschlagen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test -Dtest=AiMatchTest`
Expected: COMPILATION ERROR, `AiMatch` nicht gefunden

- [ ] **Step 3: AiMatch schreiben**

`bridge/src/main/java/mtgplayer/match/AiMatch.java`:

```java
package mtgplayer.match;

import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntry;
import forge.game.GameOutcome;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Observer;
import java.util.function.Consumer;

/**
 * Ein Commander-Spiel nur mit KI-Spielern, synchron auf dem aufrufenden Thread.
 * Benutzt Match/Game aus forge-game direkt – HostedMatch braucht eine IGuiGame
 * und kommt erst mit dem menschlichen Sitz.
 */
public final class AiMatch {

    public record Result(String winner, String reason, int turns) { }

    private AiMatch() { }

    /**
     * @param decks    ein Deck pro Sitz, 2–6
     * @param names    Anzeigenamen, gleiche Länge wie decks
     * @param maxTurns Spiel wird bei Überschreiten als Unentschieden beendet (KI-Spiele können sich festfahren)
     * @param log      bekommt jede Forge-Logzeile, sobald sie entsteht
     */
    public static Result play(List<Deck> decks, List<String> names, int maxTurns, Consumer<String> log) {
        if (decks.size() != names.size() || decks.size() < 2 || decks.size() > 6) {
            throw new IllegalArgumentException("2–6 Decks mit gleich vielen Namen");
        }
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < decks.size(); i++) {
            RegisteredPlayer rp = RegisteredPlayer.forCommander(decks.get(i));
            rp.setPlayer(new LobbyPlayerAi(names.get(i), null));
            players.add(rp);
        }

        GameRules rules = new GameRules(GameType.Commander);
        rules.setGamesPerMatch(1);
        Match match = new Match(rules, players, "AI Commander");
        Game game = match.createGame();

        int[] seen = {0};
        Observer observer = (o, arg) -> {
            List<GameLogEntry> all = game.getGameLog().getAllEntries();
            for (; seen[0] < all.size(); seen[0]++) {
                log.accept(all.get(seen[0]).message());
            }
        };
        game.getGameLog().addObserver(observer);

        Thread watchdog = new Thread(() -> {
            try {
                while (!game.isGameOver()) {
                    Thread.sleep(1000);
                    if (game.getPhaseHandler().getTurn() > maxTurns) {
                        log.accept("[bridge] turn-cap " + maxTurns + " erreicht, breche ab");
                        game.setGameOver(GameEndReason.Draw);
                    }
                }
            } catch (InterruptedException ignored) {
                // Spiel ist fertig
            }
        }, "ai-match-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        try {
            match.startGame(game);
        } finally {
            watchdog.interrupt();
            game.getGameLog().deleteObserver(observer);
        }

        GameOutcome outcome = game.getOutcome();
        String winner = outcome == null || outcome.isDraw() || outcome.getWinningPlayer() == null
                ? null : outcome.getWinningPlayer().getPlayer().getName();
        String reason = outcome == null ? "unbekannt" : String.valueOf(outcome.getWinCondition());
        int turns = outcome == null ? game.getPhaseHandler().getTurn() : outcome.getLastTurnNumber();
        return new Result(winner, reason, turns);
    }
}
```

- [ ] **Step 4: Test laufen lassen, muss bestehen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn test -Dtest=AiMatchTest 2>&1 | tail -30`
Expected: `Tests run: 1, Failures: 0` und eine Zeile `Gewinner: KI n (AllOpponentsLost) nach N Zuegen` (oder `Gewinner: null (Draw)` bei Turn-Cap – auch okay, dann hat der Watchdog funktioniert). Dauer typisch 2–8 Minuten.

Wenn eine Exception aus Forge kommt: Stacktrace vollständig lesen. Erwartbare Fälle:
- `NullPointerException` in etwas mit `Gui`/`FThreads`: eine Forge-Stelle erwartet doch eine UI – die Methode in `WebGuiBase` identifizieren und einen sinnvollen Default liefern, statt `null`.
- `ExceptionInInitializerError` bei `ForgeConstants`: `GuiBase.setInterface` kam zu spät – prüfen, ob irgendein statischer Import `ForgeConstants` vor `ForgeBoot.init()` anfasst.
- Ein Kartenskript wirft: das ist ein Forge-Bug, nicht unserer. Deck in `AiMatchTest` gegen ein anderes Precon tauschen und den Kartennamen im Commit notieren.

- [ ] **Step 5: Commit**

```bash
cd /home/kevin/projects/MTG-Player
git add bridge/src
git commit -m "m1: aimatch – vier ki spielen commander headless zu ende

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: Main – Boot, Kartenzahl, ein Spiel aus zufälligen Precons

**Files:**
- Create: `bridge/src/main/java/mtgplayer/Main.java`
- Create: `README.md`

**Interfaces:**
- Consumes: `ForgeBoot.init()`, `ForgeBoot.cardCount()`, `Precons.names()`, `Precons.load(String)`, `AiMatch.play(...)`.
- Produces: `mvn exec:java` im Verzeichnis `bridge/` startet ein KI-Spiel.

- [ ] **Step 1: Main schreiben**

`bridge/src/main/java/mtgplayer/Main.java`:

```java
package mtgplayer;

import forge.deck.Deck;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import mtgplayer.match.AiMatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * M1-Einstieg: Forge laden, vier zufällige Precons wählen, KI-Spiel loggen.
 * Optionales Argument: Seed für die Deckauswahl (reproduzierbare Spiele).
 */
public final class Main {

    private Main() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : System.currentTimeMillis();
        long t0 = System.currentTimeMillis();
        ForgeBoot.init();
        System.out.printf("%d Karten geladen in %.1f s%n", ForgeBoot.cardCount(), (System.currentTimeMillis() - t0) / 1000.0);

        List<String> names = new ArrayList<>(Precons.names());
        Collections.shuffle(names, new Random(seed));
        List<String> chosen = names.subList(0, 4);
        List<Deck> decks = chosen.stream().map(Precons::load).toList();
        System.out.println("Seed " + seed + ", Decks: " + chosen);

        AiMatch.Result r = AiMatch.play(decks, List.of("KI 1", "KI 2", "KI 3", "KI 4"), 60, System.out::println);
        System.out.println("=== Ergebnis: " + (r.winner() == null ? "unentschieden" : r.winner() + " gewinnt")
                + " (" + r.reason() + ") nach " + r.turns() + " Zuegen");
    }
}
```

- [ ] **Step 2: README schreiben**

`README.md`:

```markdown
# MTG-Player

Lokaler Commander-Tisch mit KI-Gegnern auf Basis von Forge, mit eigener Browser-UI.
Design: `docs/superpowers/specs/2026-09-16-mtg-player-design.md`.

## Voraussetzungen

- Java 17, Maven ≥ 3.8.1 (`sudo apt install openjdk-17-jdk-headless maven`)
- Node ≥ 20 (für das Frontend, ab M2)

## Einmalig: Forge bauen

```bash
git submodule update --init --depth 1
cd forge && mvn -q -pl forge-gui -am install -DskipTests -Dcheckstyle.skip -Dmaven.javadoc.skip=true
```

## Bridge

```bash
cd bridge
mvn -q test                 # alle Tests, inkl. eines kompletten KI-Spiels (Minuten)
mvn -q compile exec:java    # vier zufällige Precons, KI-Spiel auf stdout
mvn -q compile exec:java -Dexec.args="42"   # mit festem Seed
```

Forges Nutzerdaten liegen unter `~/.mtg-player/`.
```

- [ ] **Step 3: Manuell laufen lassen und Ausgabe prüfen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q compile exec:java -Dexec.args="42" 2>&1 | tee /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/m1-run.log | tail -15`
Expected: erste Zeile `NNNNN Karten geladen in X s`, dann `Seed 42, Decks: [...]`, viele Logzeilen (Mulligans, Landdrops, Zauber, Kampf), zuletzt `=== Ergebnis: ...`.

Run: `grep -c . /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/m1-run.log`
Expected: deutlich über 100 Zeilen.

- [ ] **Step 4: Gesamte Testsuite einmal komplett laufen lassen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test 2>&1 | tail -15`
Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
cd /home/kevin/projects/MTG-Player
git add bridge/src README.md
git commit -m "m1: main startet ein ki-spiel aus zufaelligen precons, readme

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Nach diesem Plan

M0 und M1 sind erfüllt, wenn Task 5 Step 3 die erwartete Ausgabe liefert. Danach entsteht Plan 2 (M2: WebSocket, `IGuiGame`-Implementierung, menschlicher Sitz, Minimal-UI) – die dafür nötigen Forge-Details (`AbstractGuiGame`, `InputQueue`, `PlayerControllerHuman`, `HostedMatch.startMatch` mit `guis`-Map) sind bekannt und werden dort ausgearbeitet.
