package com.antielytratarget.netty;

import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;

public final class TransactionTrackerRegressionSimulation {

    private TransactionTrackerRegressionSimulation() {
    }

    public static void main(String[] args) {
        verifyCumulativeAndUnknownAcknowledgements();
        verifyEvictionPreservesSendSequence();
        verifyCompensationAfterEviction();
        verifyIdWrap();
        System.out.println("Transaction tracker regression simulations passed.");
    }

    private static void verifyCumulativeAndUnknownAcknowledgements() {
        try (Fixture fixture = new Fixture()) {
            int first = fixture.send(10L);
            int second = fixture.send(11L);
            int third = fixture.send(12L);
            TransactionTracker tracker = fixture.tracker;

            require(tracker.pendingCount() == 3, "Three transactions should be pending");
            require(!tracker.onPong(first + 65536), "A full-width pong must not alias a short ID");
            require(!tracker.onPong(-1), "An unknown pong must be ignored");
            require(tracker.getLastTransactionReceived() == 0,
                    "Unknown pongs must not advance confirmation");
            require(tracker.pendingCount() == 3, "Unknown pongs must not drain pending entries");

            require(tracker.onPong(second), "A pending pong should be accepted");
            require(tracker.getLastTransactionReceived() == 2,
                    "A pong acknowledges the preceding transaction too");
            require(tracker.getLastConfirmedTick() == 11L,
                    "Confirmed tick should belong to the acknowledged transaction");
            require(tracker.pendingCount() == 1, "A later transaction must remain pending");
            require(!tracker.onPong(first) && !tracker.onPong(second),
                    "Already acknowledged IDs must not be accepted twice");
            require(tracker.getLastTransactionReceived() == 2,
                    "Duplicate pongs must not advance confirmation");

            require(tracker.onPong(third), "The last pending pong should be accepted");
            require(tracker.getLastTransactionReceived() == 3 && tracker.pendingCount() == 0,
                    "All sent transactions should now be confirmed");
            require(!tracker.onPong(third), "The final pong must also be accepted only once");
        }
    }

    private static void verifyEvictionPreservesSendSequence() {
        try (Fixture fixture = new Fixture()) {
            int[] ids = new int[101];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = fixture.send(i + 1L);
            }
            TransactionTracker tracker = fixture.tracker;
            require(tracker.getLastTransactionSent() == 101 && tracker.pendingCount() == 100,
                    "Pending storage should be bounded without losing the sent count");
            require(!tracker.onPong(ids[0]), "An evicted ID must not acknowledge retained entries");
            require(tracker.getLastTransactionReceived() == 0 && tracker.pendingCount() == 100,
                    "Eviction alone must not confirm any transaction");

            require(tracker.onPong(ids[1]), "The first retained ID should still be accepted");
            require(tracker.getLastTransactionReceived() == 2,
                    "The first retained entry must preserve its original send sequence");
            require(tracker.pendingCount() == 99, "Partial acknowledgement should retain later entries");

            require(tracker.onPong(ids[100]), "The latest pong should be accepted");
            require(tracker.getLastTransactionReceived() == 101,
                    "The received sequence must catch up after pending eviction");
            require(tracker.getLastConfirmedTick() == 101L && tracker.pendingCount() == 0,
                    "The final acknowledgement should confirm the latest tick and drain pending entries");
            require(!tracker.onPong(ids[100]) && !tracker.onPong(ids[0]),
                    "Duplicate and evicted IDs must remain unknown after the drain");
            require(tracker.getLastTransactionReceived() == 101,
                    "Ignored IDs must preserve the final confirmation sequence");
        }
    }

    private static void verifyCompensationAfterEviction() {
        PlayerPacketData data = new PlayerPacketData();
        try (Fixture fixture = new Fixture(data.transactionTracker)) {
            int latest = 0;
            for (int i = 0; i < 101; i++) {
                latest = fixture.send(i);
            }
            int sequence = data.transactionTracker.getLastTransactionSent();
            data.queueCameraState(false, sequence);
            data.queueTeleport(10.0, 64.0, -5.0, 90.0f, 20.0f,
                    RelativeFlag.NONE, sequence);
            data.applyTransactionState();
            require(data.cameraEntitySelf.get(), "Camera state must wait for its acknowledgement");
            require(!data.checkTeleport(10.0, 64.0, -5.0, 90.0f, 20.0f),
                    "A queued teleport must not match before acknowledgement");

            require(data.transactionTracker.onPong(latest), "The latest pong should be accepted");
            data.applyTransactionState();
            require(!data.cameraEntitySelf.get(),
                    "Acknowledged camera state must apply even after pending eviction");
            require(data.checkTeleport(10.0, 64.0, -5.0, 90.0f, 20.0f),
                    "Acknowledged teleport must retain the same transaction sequence");
            require(!data.checkTeleport(10.0, 64.0, -5.0, 90.0f, 20.0f),
                    "A compensated teleport must be consumed only once");
        }
    }

    private static void verifyIdWrap() {
        try (Fixture fixture = new Fixture()) {
            int previous = -1;
            int first = -1;
            for (int i = 0; i < 10003; i++) {
                int id = fixture.send(i);
                if (i == 0) first = id;
                require(id >= 20000 && id <= 30000, "Transaction ID left the supported range");
                require(id != previous, "Consecutive transactions reused an ID at the wrap boundary");
                if (i < 10001) {
                    require(id == 30000 - i, "Transaction IDs must descend throughout the first cycle");
                } else {
                    require(id == first - (i - 10001),
                            "Wrapped IDs must immediately resume the descending sequence");
                }
                previous = id;
            }
            require(fixture.tracker.pendingCount() == 100, "Storage must remain bounded across ID wraps");
            require(fixture.tracker.onPong(previous), "The final wrapped pong should be accepted");
            require(fixture.tracker.getLastTransactionReceived() == 10003,
                    "Acknowledgement sequence must be independent of wire ID wrap and eviction");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Fixture implements AutoCloseable {
        private final TransactionTracker tracker;
        private final EmbeddedChannel channel = new EmbeddedChannel();

        private Fixture() {
            this(new TransactionTracker());
        }

        private Fixture(TransactionTracker tracker) {
            this.tracker = tracker;
        }

        private int send(long tick) {
            tracker.sendTransaction(channel, tick);
            channel.runPendingTasks();
            ByteBuf packet = channel.readOutbound();
            require(packet != null, "A transaction should write an outbound ping");
            try {
                require(packet.readUnsignedByte() == 0x36 && packet.readableBytes() == 4,
                        "Unexpected transaction wire format");
                return packet.readInt();
            } finally {
                packet.release();
            }
        }

        @Override
        public void close() {
            channel.finishAndReleaseAll();
        }
    }
}
