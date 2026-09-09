package net.birb.crackers.cracker.storage;

import net.birb.crackers.config.Config;
import net.birb.crackers.util.Log;

/**
 * Reports how far a search has got, in chat, at a rate a human can read.
 * <p>
 * Called from every solver worker, so it has to be cheap and thread-safe: the
 * common case is a compare-and-set that decides not to print anything.
 */
public class ProgressListener {

    private static final int STEP_PERCENT = 10;

    private volatile int lastReported = -1;

    /** @param fraction completed work in [0, 1] */
    public void setFraction(double fraction) {
        if (!Config.get().debug) return;
        int bucket = (int) (Math.min(1.0D, Math.max(0.0D, fraction)) * 100.0D) / STEP_PERCENT;
        if (bucket <= this.lastReported) return;
        synchronized (this) {
            if (bucket <= this.lastReported) return;
            this.lastReported = bucket;
        }
        Log.debug(Log.translate("tmachine.progress") + ": " + (bucket * STEP_PERCENT) + "%");
    }
}
