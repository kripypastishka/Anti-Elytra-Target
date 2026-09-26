package com.antielytratarget.netty;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class TransactionEntryConcurrencySimulation {

    private TransactionEntryConcurrencySimulation() {
    }

    public static void main(String[] args) throws Exception {
        TransactionEntry initial = new TransactionEntry((short) 7, 12L, System.nanoTime());
        require(!initial.isConfirmed() && initial.getRttNanos() == -1 && initial.getRttMs() == -1,
                "Unconfirmed RTT must remain unavailable");
        initial.confirm();
        long firstRtt = initial.getRttNanos();
        initial.confirm();
        require(initial.isConfirmed() && firstRtt >= 0 && initial.getRttNanos() >= firstRtt,
                "Repeated confirmation must continue to refresh the timestamp");

        TransactionEntry[] entries = new TransactionEntry[100_000];
        for (int i = 0; i < entries.length; i++) {
            entries[i] = new TransactionEntry((short) i, i, System.nanoTime());
        }
        AtomicInteger consumed = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> reader = executor.submit(() -> {
                for (int i = 0; i < entries.length; i++) {
                    TransactionEntry entry = entries[i];
                    while (!entry.isConfirmed()) spin();
                    long rtt = entry.getRttNanos();
                    require(rtt >= 0 && rtt <= System.nanoTime() - entry.sendTimeNanos,
                            "Confirmed transaction exposed an uninitialized timestamp: " + rtt);
                    require(entry.getRttMs() == rtt / 1_000_000L,
                            "RTT units changed");
                    consumed.set(i + 1);
                }
            });
            Future<?> writer = executor.submit(() -> {
                for (int i = 0; i < entries.length; i++) {
                    entries[i].confirm();
                    while (consumed.get() <= i) spin();
                }
            });
            reader.get(30, TimeUnit.SECONDS);
            writer.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            require(executor.awaitTermination(5, TimeUnit.SECONDS), "Worker threads did not stop");
        }
        System.out.println("Transaction entry concurrency simulation passed.");
    }

    private static void spin() {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Interrupted");
        Thread.onSpinWait();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
