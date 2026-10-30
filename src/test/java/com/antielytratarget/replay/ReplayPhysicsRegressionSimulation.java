package com.antielytratarget.replay;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

public final class ReplayPhysicsRegressionSimulation {

    private ReplayPhysicsRegressionSimulation() {
    }

    public static void main(String[] args) throws Exception {
        verifyFireworkMetadataCompatibility();
        verifyFireworkChangesSimulatedFlight();
        verifyCompressedReplayRoundTrip();
        verifyLegacyReplayHeaders();
        verifyReaderOwnership();
        verifyDecoderInitializationFailureClosesInput();
        verifyFormatDetectionFailureClosesInput();
        verifyCloseFailureIsSuppressed();
        System.out.println("Replay physics regression simulation passed.");
    }

    private static void verifyFireworkMetadataCompatibility() {
        ReplaySnapshot original = snapshot(0.0, 100.0, 0.0, 0.0, 0.0, 0.3, 2, true);
        ReplaySnapshot decoded = ReplaySnapshot.fromLine(original.toLine());
        require(decoded != null && decoded.getFireworkPower() == 2 && decoded.isFireworkStart(),
                "new replay frames must preserve firework power and launch edge");

        String legacyLine = String.join(";",
                "0", "0", "100", "0", "0", "0", "world",
                "0", "0", "1", "0", "0",
                "AIR", "AIR", "AIR", "ELYTRA", "AIR", "AIR");
        ReplaySnapshot legacy = ReplaySnapshot.fromLine(legacyLine);
        require(legacy != null && legacy.getFireworkPower() == 0 && !legacy.isFireworkStart(),
                "legacy replay frames must remain readable");
    }

    private static void verifyFireworkChangesSimulatedFlight() {
        ReplayFlightSimulator withoutBoost = new ReplayFlightSimulator();
        ReplayFlightSimulator withBoost = new ReplayFlightSimulator();
        ReplaySnapshot first = snapshot(0.0, 100.0, 0.0, 0.0, 0.0, 0.25, 0, false);
        withoutBoost.advance(first, true);
        withBoost.advance(first, true);

        ReplaySnapshot normalFrame = snapshot(0.0, 99.95, 0.25, 0.0, -0.02, 0.25, 0, false);
        ReplaySnapshot boostFrame = snapshot(0.0, 99.95, 0.25, 0.0, -0.02, 0.25, 1, true);
        ReplayFlightSimulator.Frame normal = withoutBoost.advance(normalFrame, false);
        ReplayFlightSimulator.Frame boosted = withBoost.advance(boostFrame, false);

        require(!normal.hardCorrection() && !boosted.hardCorrection(),
                "ordinary sequential flight frames should use relative simulated movement");
        require(boosted.z() > normal.z() + 0.2,
                "an active firework must physically accelerate the replay fake player");
    }

    private static void verifyCompressedReplayRoundTrip() throws Exception {
        Path directory = Files.createTempDirectory("aet-replay-regression-");
        Path file = directory.resolve("roundtrip.replay");
        try {
            ReplayData data = new ReplayData(UUID.randomUUID(), "ReplayTest", "regression", 1);
            data.addSnapshot(snapshot(1.0, 80.0, 2.0, 0.1, -0.1, 0.3, 1, true));
            data.saveToFile(file.toFile());

            byte[] prefix = Files.readAllBytes(file);
            require(prefix.length >= 2 && (prefix[0] & 0xff) == 0x1f && (prefix[1] & 0xff) == 0x8b,
                    "new replay files must use GZIP compression");

            ReplayData loaded = ReplayData.loadFromFile(file.toFile());
            ReplayData.Summary summary = ReplayData.loadSummaryFromFile(file.toFile());
            require(loaded.snapshotCount() == 1 && loaded.getSnapshots().get(0).getFireworkPower() == 1,
                    "compressed replay payload must round-trip");
            require(summary.getSnapshotCount() == 1,
                    "compressed replay summaries must use the header frame count");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    private static void verifyLegacyReplayHeaders() throws Exception {
        Path directory = Files.createTempDirectory("aet-legacy-replay-regression-");
        UUID uuid = UUID.randomUUID();
        String legacy = "HEADER|" + uuid + "|LegacyTest|legacy|2026-10-29 18:00:00|1\n"
                + new ReplayFlagSnapshot(0, 0L, "LegacyCheck", 2).toLine() + "\n"
                + snapshot(1.0, 80.0, 2.0, 0.1, -0.1, 0.3, 0, false).toLine() + "\n";
        try {
            for (boolean compressed : new boolean[] { false, true }) {
                Path file = directory.resolve(compressed ? "legacy-gzip.replay" : "legacy-plain.replay");
                try {
                    byte[] content = legacy.getBytes(StandardCharsets.UTF_8);
                    Files.write(file, compressed ? gzip(content) : content);
                    ReplayData loaded = ReplayData.loadFromFile(file.toFile());
                    ReplayData.Summary summary = ReplayData.loadSummaryFromFile(file.toFile());
                    require(loaded.getPlayerUUID().equals(uuid) && loaded.snapshotCount() == 1,
                            "six-field legacy headers must load in plain and GZIP files");
                    require(loaded.getFlagEvents().size() == 1 && summary.getSnapshotCount() == 1,
                            "legacy summaries must count frames without counting flag lines");
                } finally {
                    Files.deleteIfExists(file);
                }
            }
        } finally {
            Files.deleteIfExists(directory);
        }
    }

    private static void verifyReaderOwnership() throws Exception {
        byte[] content = "HEADER|reader ownership\n".getBytes(StandardCharsets.UTF_8);
        for (boolean compressed : new boolean[] { false, true }) {
            CloseTrackingInputStream input = new CloseTrackingInputStream(compressed ? gzip(content) : content);
            try (BufferedReader reader = ReplayData.openReader(input)) {
                require(input.closeCount == 0,
                        "successful initialization must keep the input open for the reader");
                require("HEADER|reader ownership".equals(reader.readLine()),
                        "format detection must preserve the beginning of plain and GZIP input");
            }
            require(input.closeCount == 1, "closing the reader must close its input exactly once");
        }

        CloseTrackingInputStream empty = new CloseTrackingInputStream(new byte[0]);
        try (BufferedReader reader = ReplayData.openReader(empty)) {
            require(reader.readLine() == null, "empty input must remain readable as an empty text stream");
        }
        require(empty.closeCount == 1, "an empty input must also be owned by the reader");
    }

    private static void verifyDecoderInitializationFailureClosesInput() throws Exception {
        CloseTrackingInputStream input = new CloseTrackingInputStream(new byte[] { 0x1f, (byte) 0x8b, 0 });
        try {
            ReplayData.openReader(input);
            throw new AssertionError("a truncated GZIP header must fail during decoder initialization");
        } catch (IOException expected) {
            require(input.closeCount == 1, "failed GZIP initialization must close the input exactly once");
        }
    }

    private static void verifyFormatDetectionFailureClosesInput() throws Exception {
        CloseTrackingInputStream input = new CloseTrackingInputStream(new byte[0]);
        IOException readFailure = new IOException("format detection failed");
        input.readFailure = readFailure;
        try {
            ReplayData.openReader(input);
            throw new AssertionError("a failing input must fail format detection");
        } catch (IOException expected) {
            require(expected == readFailure, "format detection must preserve the original I/O error");
            require(input.closeCount == 1, "failed format detection must close the input exactly once");
        }
    }

    private static void verifyCloseFailureIsSuppressed() throws Exception {
        CloseTrackingInputStream sniffInput = new CloseTrackingInputStream(new byte[0]);
        IOException readFailure = new IOException("format detection failed");
        IOException closeFailure = new IOException("input close failed");
        sniffInput.readFailure = readFailure;
        sniffInput.closeFailure = closeFailure;
        try {
            ReplayData.openReader(sniffInput);
            throw new AssertionError("a failing input must fail format detection");
        } catch (IOException expected) {
            require(expected == readFailure, "a cleanup error must not replace the format detection error");
            require(expected.getSuppressed().length == 1 && expected.getSuppressed()[0] == closeFailure,
                    "format detection must retain the close error as suppressed");
        }

        CloseTrackingInputStream gzipInput = new CloseTrackingInputStream(new byte[] { 0x1f, (byte) 0x8b, 0 });
        gzipInput.closeFailure = closeFailure;
        try {
            ReplayData.openReader(gzipInput);
            throw new AssertionError("a truncated GZIP header must fail");
        } catch (IOException expected) {
            require(expected != closeFailure, "a cleanup error must not replace the GZIP initialization error");
            require(expected.getSuppressed().length == 1 && expected.getSuppressed()[0] == closeFailure,
                    "GZIP initialization must retain the close error as suppressed");
            require(gzipInput.closeCount == 1, "GZIP cleanup must attempt to close the input exactly once");
        }
    }

    private static byte[] gzip(byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(content);
        }
        return bytes.toByteArray();
    }

    private static final class CloseTrackingInputStream extends InputStream {
        private final ByteArrayInputStream input;
        private IOException readFailure;
        private IOException closeFailure;
        private int closeCount;

        private CloseTrackingInputStream(byte[] content) {
            this.input = new ByteArrayInputStream(content);
        }

        @Override
        public int read() throws IOException {
            if (readFailure != null) throw readFailure;
            return input.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (readFailure != null) throw readFailure;
            return input.read(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
            closeCount++;
            input.close();
            if (closeFailure != null) throw closeFailure;
        }
    }

    private static ReplaySnapshot snapshot(double x, double y, double z,
                                           double velocityX, double velocityY, double velocityZ,
                                           int fireworkPower, boolean fireworkStart) {
        return new ReplaySnapshot(
                x, y, z, 0.0f, 0.0f, "world",
                false, false, true, false, false,
                "AIR", "FIREWORK_ROCKET", "AIR", "ELYTRA", "AIR", "AIR",
                0L,
                velocityX, velocityY, velocityZ,
                false, true, 1L, 1L,
                Collections.emptyList(), ReplaySkinSnapshot.empty(), Collections.emptyList(),
                0.4, fireworkPower, fireworkStart);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
