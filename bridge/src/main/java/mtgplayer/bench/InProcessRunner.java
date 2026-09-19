package mtgplayer.bench;

/** Spielt im aufrufenden Thread, wie vor dem Umbau auf Kindprozesse - fuer Tests und {@code --in-process}.
 *  Keine JVM-Isolation: ein Forge-eigener Absturz kann Folgespiele in derselben JVM vergiften, s.
 *  {@link SubprocessRunner}. */
final class InProcessRunner implements GameRunner {

    @Override
    public GameRecord play(BenchArgs args, int i) {
        return Bench.playOne(args, i, line -> { });
    }
}
