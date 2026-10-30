package com.antielytratarget.netty;

public class TransactionEntry {

    public final short id;
    public final long serverTick;
    public final long sendTimeNanos;
    final int sequence;
    private volatile boolean confirmed;
    private volatile long confirmTimeNanos;

    public TransactionEntry(short id, long serverTick, long sendTimeNanos) {
        this(id, serverTick, sendTimeNanos, 0);
    }

    TransactionEntry(short id, long serverTick, long sendTimeNanos, int sequence) {
        this.id = id;
        this.serverTick = serverTick;
        this.sendTimeNanos = sendTimeNanos;
        this.sequence = sequence;
        this.confirmed = false;
    }

    public void confirm() {
        this.confirmTimeNanos = System.nanoTime();
        this.confirmed = true;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

public long getRttMs() {
        if (!confirmed) return -1;
        return (confirmTimeNanos - sendTimeNanos) / 1_000_000L;
    }

public long getRttNanos() {
        if (!confirmed) return -1;
        return confirmTimeNanos - sendTimeNanos;
    }
}
