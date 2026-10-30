package com.antielytratarget.netty;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowConfirmation;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class TransactionTracker {

private static final int PKT_CLIENTBOUND_PING = 0x36;
    private static final int PKT_SERVERBOUND_PONG = 0x26;
    private static final int MIN_TRANSACTION_ID = 20000;
    private static final int MAX_TRANSACTION_ID = 30000;

private final AtomicInteger idCounter = new AtomicInteger(MAX_TRANSACTION_ID + 1);

private final ConcurrentLinkedDeque<TransactionEntry> pending = new ConcurrentLinkedDeque<>();

private final AtomicLong lastConfirmedTick = new AtomicLong(-1);
    private final AtomicLong latencyNanos = new AtomicLong(0);
    private final AtomicInteger lastTransactionSent = new AtomicInteger(0);
    private final AtomicInteger lastTransactionReceived = new AtomicInteger(0);
    private volatile long lastPongTimeNanos = 0;

public synchronized void sendTransaction(Channel channel, long serverTick) {
        if (channel == null || !channel.isActive()) return;

        int id = nextId();
        addPending((short) id, serverTick);

channel.eventLoop().execute(() -> {
            if (!channel.isActive()) return;
            ByteBuf buf = Unpooled.buffer(5);
            writeVarInt(buf, PKT_CLIENTBOUND_PING);
            buf.writeInt(id);
            channel.writeAndFlush(buf);
        });
    }

    public synchronized void sendTransaction(User user, long serverTick) {
        if (user == null) return;

        int id = nextId();
        addPending((short) id, serverTick);

        try {
            ServerVersion version = PacketEvents.getAPI().getServerManager().getVersion();
            if (version.isNewerThanOrEquals(ServerVersion.V_1_17)) {
                user.sendPacketSilently(new WrapperPlayServerPing(id));
            } else {
                user.sendPacketSilently(
                        new WrapperPlayServerWindowConfirmation(
                                0, (short) id, false));
            }
        } catch (Throwable ignored) {
        }
    }

public synchronized boolean onPong(int pongId) {
        if (pongId != (short) pongId) return false;

        TransactionEntry target = null;
        for (TransactionEntry entry : pending) {
            if (entry.id == (short) pongId) {
                target = entry;
                break;
            }
        }
        if (target == null) return false;

        long now = System.nanoTime();

        TransactionEntry entry;
while ((entry = pending.pollFirst()) != null) {

            entry.confirm();
            lastConfirmedTick.set(entry.serverTick);
            latencyNanos.set(now - entry.sendTimeNanos);
            lastPongTimeNanos = now;
            // Pending entries may have been evicted while awaiting a reply. A
            // confirmed packet still acknowledges its original send sequence.
            lastTransactionReceived.set(entry.sequence);

            if (entry == target) {
                return true;
            }
        }

        return false;
    }

public boolean handleInbound(int packetId, ByteBuf buf) {
        if (packetId != PKT_SERVERBOUND_PONG) return false;
        if (buf.readableBytes() < 4) return false;

        int pongId = buf.readInt();
        return onPong(pongId);
    }

public long getLastConfirmedTick() {
        return lastConfirmedTick.get();
    }

public long getLatencyMs() {
        return latencyNanos.get() / 1_000_000L;
    }

public int getLatencyTicks() {
        long ms = getLatencyMs();
        return Math.max(1, (int) (ms / 50L));
    }

public long timeSinceLastPongMs() {
        if (lastPongTimeNanos == 0) return Long.MAX_VALUE;
        return (System.nanoTime() - lastPongTimeNanos) / 1_000_000L;
    }

public int pendingCount() {
        return pending.size();
    }

public boolean isCalibrated() {
        return lastConfirmedTick.get() >= 0;
    }

    public int getLastTransactionSent() {
        return lastTransactionSent.get();
    }

    public int getLastTransactionReceived() {
        return lastTransactionReceived.get();
    }

private int nextId() {
        return idCounter.updateAndGet(current ->
                current <= MIN_TRANSACTION_ID ? MAX_TRANSACTION_ID : current - 1);
    }

    private synchronized void addPending(short id, long serverTick) {
        int sequence = lastTransactionSent.incrementAndGet();
        pending.addLast(new TransactionEntry(id, serverTick, System.nanoTime(), sequence));

        while (pending.size() > 100) {
            pending.pollFirst();
        }
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }
}
