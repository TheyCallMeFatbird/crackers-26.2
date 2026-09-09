package net.birb.crackers.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Every background thread Crackers owns.
 * <p>
 * All of them are <b>daemon</b> threads. The previous code used
 * {@code Executors.newFixedThreadPool(5)} twice, which uses the default thread
 * factory and therefore creates <i>non-daemon</i> threads: the JVM refuses to
 * exit while they are alive, so closing the game hung until the shutdown
 * watchdog force-crashed it. That is the whole content of the two crash reports
 * in {@code run/crash-reports} - the only non-daemon threads left in either
 * dump were this mod's two pools.
 * <p>
 * The pools are also split by role. Solver work used to be submitted to the
 * same five-thread pool that the solver coordinator itself was running on, so a
 * coordinator plus its four workers filled the pool exactly and anything else
 * queued behind them deadlocked.
 */
public final class Pools {

    private Pools() {
    }

    /**
     * Scans loaded chunks. Deliberately small and low priority: this competes
     * with the render thread and the work is throughput-insensitive.
     */
    public static final ExecutorService FINDERS =
            fixed("crackers-finder", Math.max(2, Runtime.getRuntime().availableProcessors() / 4), Thread.MIN_PRIORITY + 2);

    /**
     * Runs the actual seed search. Leaves two cores to the game so a lift does
     * not turn the client into a slideshow.
     */
    public static final ExecutorService SOLVER =
            fixed("crackers-solver", solverThreads(), Thread.NORM_PRIORITY - 2);

    /**
     * Drives a solve from start to finish. Separate from {@link #SOLVER} so a
     * coordinator can block on its own workers without being able to starve
     * them.
     */
    public static final ExecutorService COORDINATOR =
            fixed("crackers-coordinator", 1, Thread.NORM_PRIORITY - 2);

    /** Worker count for a parallel search. Never zero, never all of them. */
    public static int solverThreads() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
    }

    /** Stops everything. Called when the client shuts down. */
    public static void shutdown() {
        for (ExecutorService service : new ExecutorService[]{FINDERS, SOLVER, COORDINATOR}) {
            service.shutdownNow();
        }
        for (ExecutorService service : new ExecutorService[]{FINDERS, SOLVER, COORDINATOR}) {
            try {
                service.awaitTermination(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static ExecutorService fixed(String name, int threads, int priority) {
        return Executors.newFixedThreadPool(threads, factory(name, priority));
    }

    private static ThreadFactory factory(String name, int priority) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(priority);
            return thread;
        };
    }
}
