package net.birb.crackers.cracker.storage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A set that stages additions until the solver is between runs.
 * <p>
 * Finders add from chunk-scanning threads while the solver iterates on its own
 * threads, so nothing hands out a live view any more: {@link #snapshot} copies
 * under the lock and callers iterate the copy. The previous version returned
 * {@code baseSet.iterator()} straight out of a synchronized method, which
 * synchronizes exactly nothing once the caller starts iterating.
 */
public class ScheduledSet<T> implements Iterable<T> {

    private final Set<T> baseSet = new LinkedHashSet<>();
    private final Set<T> scheduledSet = new LinkedHashSet<>();

    public synchronized void scheduleAdd(T e) {
        this.scheduledSet.add(e);
    }

    public synchronized void dump() {
        this.baseSet.addAll(this.scheduledSet);
        this.scheduledSet.clear();
    }

    public synchronized boolean contains(T e) {
        return this.baseSet.contains(e) || this.scheduledSet.contains(e);
    }

    /** An independent copy. Iterate this, never the set itself. */
    public synchronized List<T> snapshot() {
        return new ArrayList<>(this.baseSet);
    }

    public synchronized void removeIf(Predicate<T> predicate) {
        this.baseSet.removeIf(predicate);
        this.scheduledSet.removeIf(predicate);
    }

    public synchronized void clear() {
        this.baseSet.clear();
        this.scheduledSet.clear();
    }

    @Override
    public Iterator<T> iterator() {
        return snapshot().iterator();
    }

    public synchronized int size() {
        return this.baseSet.size();
    }
}
