# Reliability and allocation verification

The baseline for this change is commit
`ee401d4a57a0d005d2f8b59955ee6622a5edf9c4`. Its existing build and 12 simulation
tasks passed with JDK 17 and Gradle 8.11. Detection thresholds, replay file
formats, plugin metadata, and the shaded/obfuscated packaging are preserved.

## Fixed failures

| Area | Failure | Verification |
| --- | --- | --- |
| Transaction publication | `confirmed` became visible before its timestamp was stored. | The existing cross-thread publication simulation is now part of `check`; it failed against the baseline implementation and passes with the write order corrected. |
| Transaction history | Evicting old entries from the 100-entry pending queue lost their sequence numbers, leaving teleport/camera compensation behind acknowledged packets. | The tracker simulation sends 101 transactions, acknowledges retained entries, and checks both original sequence numbers and `PlayerPacketData` compensation. |
| Transaction IDs | Rollover emitted ID 30000 twice in succession; narrowing a full-width pong could accept an unrelated ID. | The tracker simulation checks 10,003 sends across rollover and rejects unknown, duplicate, and full-width alias replies. Each of the new tracker scenario groups failed against the baseline. |
| Replay input | Header sniffing or GZIP initialization could throw before a reader owned the open stream. | Close-tracking streams cover read failure, truncated GZIP, suppressed close errors, and successful reader ownership. Plain and compressed legacy replay headers remain readable. |
| Replay task lifecycle | Finishing a session before its repeating-task handle returned could leave the task running. | A latch-controlled simulation finishes the actual recording session before attaching its handle, then checks exactly-once cancellation and repeated finish calls. |
| Profile history | History loading retained every entry until the whole file had been parsed, then trimmed each profile. Counting flags also copied the entire profile. | A 10,000-line simulation checks that each profile retains at most its configured limit while parsing and keeps the newest entries in order. Independent players, malformed lines, and nonpositive limits are covered. Counting now reads the synchronized list's size directly. |
| Aim deltas | Each derivative copied its new list again and empty results continued allocating through the requested depth. | Six edge-case inputs and 2,000 seeded random inputs are compared with the original algorithm over depths -2 through 10. Empty input with maximum depth terminates immediately. |

The transaction tests use an actual Netty `EmbeddedChannel`; replay lifecycle
tests exercise the production session type. No Minecraft server is started by
these checks. Profile retention bounds the entries held for each player; the log
is still read in full and the on-disk history is not truncated.

## Reproduce

```sh
./gradlew clean build
./gradlew aimAllocationBenchmark
```

`check` includes the 12 existing simulation tasks plus
`transactionEntryConcurrency`, `transactionTrackerRegression`,
`replayRecorderLifecycleRegression`, and `profileHistoryRegression`. Replay and
aim regression tasks also contain additional cases. The optional allocation
benchmark is deliberately outside `check`.

On Temurin 17.0.21, the allocation benchmark reported **4,848 bytes/op for the
original algorithm and 2,952 bytes/op for the current implementation** with 25
samples, derivative depth 5, and 100,000 measured operations after warmup. This
is a 39.1% allocation reduction for this method and workload. It does not measure
server CPU use, total memory, tick time, or client FPS. The numeric output of the
calculation, including its existing sentinel behavior, is unchanged.

## Server acceptance still needed

Before a production rollout, run a staging server with the intended Minecraft
version and server implementation. Check normal and delayed packet replies,
teleports and spectator camera transitions, replay start/stop/retirement,
replay loading, and plugin shutdown/reload. On Folia, include player movement
between regions and retirement while recording. Confirm that normal gameplay
does not introduce new flags.

The simulations and build do not establish compatibility across every advertised
Spigot/Paper/Folia/Canvas version. Packet listener startup ordering and ownership
of player state across reconnects remain separate review areas; this change does
not redesign packet ingress or alter detection thresholds.
