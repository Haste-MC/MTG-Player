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
