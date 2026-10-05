package com.kilivana.security;

import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The budget a repeated deploy-time diagnostic spends, and the reason the previous design was
 * replaced rather than tuned.
 *
 * <p>The defect this exists to close: the diagnostic named a peer at most once, ever, and kept a
 * hard cap of eight peers. Both halves had the same effect. A platform edge is a multi-address
 * fleet, so peers 9 through N produced no output at all, and the outage that names
 * {@code TRUSTED_PROXIES} went permanently quiet while every credential request kept answering
 * 503. The information was still there; it was just no longer reachable from the log, and the
 * rate at which a condition occurs is not evidence of how much of it there is.
 *
 * <p>So the budget is <em>time</em>, not peer count. Suppression lasts one interval and then
 * expires: a peer that keeps misbehaving is named again on every interval for as long as it
 * keeps misbehaving, and a peer that stops is forgotten. A count of distinct peers can be
 * exhausted by an attacker or by a large edge fleet; a wall clock cannot, because nobody
 * controls it. This is the same mechanism as a health check: a latched condition is re-announced
 * for as long as it holds, and the announcement rate is what is bounded.
 *
 * <p>Two layers, because the two halves of the guarantee are different properties:
 * <ul>
 *   <li>{@link #shouldReport} is what stops a flood. Thousands of requests from one peer produce
 *       one line per interval, so the log cost is a function of elapsed time, never of load.
 *   <li>{@link #takeSuppressedIfDue} is what stops the silence. It reports how many events were
 *       suppressed in the interval just ended, so an operator can tell "one request, one line"
 *       apart from "twelve thousand requests, one line" without counting lines by hand. It is
 *       asked for only by a caller that has just seen the condition recur, so a deployment that
 *       fixed its configuration produces no aggregate lines at all.
 * </ul>
 *
 * <p>Both properties hold together, and the second test in every suite that uses this asserts
 * them together, because either alone is the bug being fixed. Volume cannot buy silence: a
 * thousand distinct peers inside one interval are all named, because the map evicts rather than
 * refuses. Time cannot buy a flood: one peer inside one interval is named once.
 *
 * <p>Bounded, because the key is a socket address and one behind a rotating address space could
 * otherwise grow the map without limit. The bound is enforced by <em>eviction of the stalest
 * entry</em>, never by refusing to record a new one. That distinction is the whole fix: the
 * previous cap refused, which converted a memory bound into a permanent silence. Evicting the
 * oldest entry costs at most a repeated line for whichever peer was evicted, which is the correct
 * failure direction for a diagnostic.
 *
 * <p>Thread-safe, because this is consulted on every credential request from many threads at
 * once. {@code shouldReport} is deliberately loose about exactness — two threads racing the same
 * peer can both log — because the invariant is "at most once per peer per interval, approximately",
 * and a lock on the login path to obtain the word "exactly" would be a bad trade.
 */
final class DiagnosticBudget {

    private final Clock clock;
    private final long intervalMillis;
    private final int maxTrackedKeys;

    /**
     * Key to the instant its peer was last named. Entries older than one interval are dead weight:
     * they can no longer suppress anything, because {@link #shouldReport} has already decided to
     * let their peer through again.
     */
    private final ConcurrentHashMap<String, Long> lastReportedAtMillis = new ConcurrentHashMap<>();

    private final AtomicLong suppressedSinceAggregate = new AtomicLong();

    /**
     * Starts at construction time rather than at zero so the first event is never accompanied by
     * an aggregate line reporting nothing. The first aggregate is therefore one interval in.
     */
    private final AtomicLong lastAggregateAtMillis;

    DiagnosticBudget(Clock clock, long intervalMillis, int maxTrackedKeys) {
        this.clock = clock;
        this.intervalMillis = intervalMillis;
        this.maxTrackedKeys = maxTrackedKeys;
        this.lastAggregateAtMillis = new AtomicLong(clock.millis());
    }

    /**
     * @return {@code true} when this caller should log the individual event for {@code key}.
     */
    boolean shouldReport(String key) {
        long now = clock.millis();
        Long previous = lastReportedAtMillis.get(key);
        if (previous != null && now - previous < intervalMillis) {
            suppressedSinceAggregate.incrementAndGet();
            return false;
        }
        remember(key, now);
        return true;
    }

    /**
     * @return how many events were suppressed in the interval that has just ended, or {@code -1}
     *         when no interval has elapsed. The caller logs only a positive count: asking is
     *         itself evidence the condition recurred, and a window with nothing in it is silence,
     *         which is what an operator wants to see.
     */
    long takeSuppressedIfDue() {
        long now = clock.millis();
        if (now - lastAggregateAtMillis.get() < intervalMillis) {
            return -1L;
        }
        long suppressed = suppressedSinceAggregate.getAndSet(0L);
        lastAggregateAtMillis.set(now);
        return suppressed;
    }

    /** How many peers are currently inside the suppression window, for the aggregate line. */
    int trackedKeyCount() {
        return lastReportedAtMillis.size();
    }

    private void remember(String key, long now) {
        if (lastReportedAtMillis.size() >= maxTrackedKeys) {
            makeRoom(now);
        }
        lastReportedAtMillis.put(key, now);
    }

    private void makeRoom(long now) {
        // First choice: forget everything that has already been let back through. Under any real
        // load this empties the map, because the entries are a full interval old by the time the
        // bound is reached.
        lastReportedAtMillis.values().removeIf(lastReportedAt -> now - lastReportedAt >= intervalMillis);
        if (lastReportedAtMillis.size() < maxTrackedKeys) {
            return;
        }
        // Second choice, and the one that matters: a distinct-peer flood inside a single interval
        // outruns the expiry. Drop the stalest entry and record the new one. Refusing here — the
        // previous behaviour — is what made peers nine and beyond unreportable for the life of
        // the process.
        String stalestKey = null;
        long stalestAt = Long.MAX_VALUE;
        Iterator<Map.Entry<String, Long>> entries = lastReportedAtMillis.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, Long> entry = entries.next();
            if (entry.getValue() < stalestAt) {
                stalestAt = entry.getValue();
                stalestKey = entry.getKey();
            }
        }
        if (stalestKey != null) {
            lastReportedAtMillis.remove(stalestKey);
        }
    }
}
