package com.antielytratarget.replay;

import com.antielytratarget.utils.SchedulerUtil;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ReplayRecorderLifecycleRegressionSimulation {

    private ReplayRecorderLifecycleRegressionSimulation() {
    }

    public static void main(String[] args) throws Exception {
        verifyTaskAttachedBeforeFinish();
        verifyFinishBeforeTaskAttachment();
        verifyNullTaskAttachment();
        System.out.println("Replay recorder lifecycle regression simulation passed.");
    }

    private static void verifyTaskAttachedBeforeFinish() {
        ReplayRecorder.RecordingSession session = session();
        CountingTask task = new CountingTask();
        session.attachTickTask(task);
        require(task.cancelCount.get() == 0, "an active session must keep its recording task running");
        require(session.finish(), "the first finish must own session cleanup");
        require(task.cancelCount.get() == 1, "finishing an attached session must cancel its task");
        require(!session.finish(), "a second finish must not repeat session cleanup");
        require(task.cancelCount.get() == 1, "repeated finishes must not cancel the task twice");
    }

    private static void verifyFinishBeforeTaskAttachment() throws Exception {
        ReplayRecorder.RecordingSession session = session();
        CountingTask task = new CountingTask();
        CountDownLatch handleCreated = new CountDownLatch(1);
        CountDownLatch schedulerReturns = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread schedulingThread = new Thread(() -> {
            try {
                handleCreated.countDown();
                require(schedulerReturns.await(5, TimeUnit.SECONDS), "the simulated scheduler must be released");
                session.attachTickTask(task);
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "aet-replay-task-attachment-regression");
        schedulingThread.start();
        try {
            require(handleCreated.await(5, TimeUnit.SECONDS), "the scheduler must create its handle");
            require(session.finish(), "stop must finish the session before the scheduler returns");
            require(task.cancelCount.get() == 0, "stop cannot cancel a handle it has not received yet");
        } finally {
            schedulerReturns.countDown();
            schedulingThread.join(5000L);
        }
        require(!schedulingThread.isAlive(), "task attachment must finish without deadlock");
        if (failure.get() != null) throw new AssertionError("task attachment failed", failure.get());
        require(task.cancelCount.get() == 1,
                "a task returned after stop must be cancelled instead of left repeating");
        require(!session.finish(), "late task attachment must not revive the finished session");
        require(task.cancelCount.get() == 1, "repeated cleanup after late attachment must remain idempotent");
    }

    private static void verifyNullTaskAttachment() {
        ReplayRecorder.RecordingSession session = session();
        session.attachTickTask(null);
        require(session.finish(), "a session without a scheduler handle must still finish");
        session.attachTickTask(null);
        require(!session.finish(), "a missing late handle must not revive a session");
    }

    private static ReplayRecorder.RecordingSession session() {
        ReplayData data = new ReplayData(UUID.randomUUID(), "ReplayLifecycleTest", "regression", 1);
        return new ReplayRecorder.RecordingSession(data, 20);
    }

    private static final class CountingTask implements SchedulerUtil.TaskWrapper {
        private final AtomicInteger cancelCount = new AtomicInteger();

        @Override
        public void cancel() {
            cancelCount.incrementAndGet();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
