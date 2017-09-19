/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.test;

import com.hazelcast.config.NetworkConfig;
import com.hazelcast.jet.Processor;
import com.hazelcast.jet.ProcessorMetaSupplier;
import com.hazelcast.jet.ProcessorSupplier;
import com.hazelcast.jet.function.DistributedSupplier;
import com.hazelcast.jet.test.TestOutbox.MockData;
import com.hazelcast.nio.Address;

import javax.annotation.Nonnull;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.hazelcast.jet.Util.entry;
import static com.hazelcast.jet.test.JetAssert.assertEquals;
import static com.hazelcast.jet.test.JetAssert.assertTrue;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Utilities to write unit tests.
 */
public final class TestSupport {

    private static final Address LOCAL_ADDRESS;

    // 1ms should be enough for a cooperative call. We warn, when it's more than 5ms and
    // fail when it's more than 100ms, possibly due to other  activity in the system, such as
    // parallel tests or GC.
    private static final long COOPERATIVE_TIME_LIMIT_MS_FAIL = 100;
    private static final long COOPERATIVE_TIME_LIMIT_MS_WARN = 5;

    static {
        try {
            LOCAL_ADDRESS = new Address("localhost", NetworkConfig.DEFAULT_PORT);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    private TestSupport() {
    }

    /**
     * Convenience for {@link #testProcessor(Supplier, List, List, boolean, boolean, boolean, boolean, BiPredicate)}
     * with progress assertion and snapshot+restore enabled.
     */
    public static <T, U> void testProcessor(@Nonnull DistributedSupplier<Processor> supplier,
                                            @Nonnull List<T> input, @Nonnull List<U> expectedOutput) {
        testProcessor(supplier, input, expectedOutput, true, true, false, true, Objects::equals);
    }

    /**
     * Convenience for {@link #testProcessor(Supplier, List, List, boolean, boolean, boolean, boolean, BiPredicate)}
     * with progress assertion and snapshot+restore enabled.
     */
    public static <T, U> void testProcessor(@Nonnull ProcessorSupplier supplier,
                                            @Nonnull List<T> input, @Nonnull List<U> expectedOutput) {
        testProcessor(supplierFrom(supplier), input, expectedOutput, true, true, false, true, Objects::equals);
    }

    /**
     * Convenience for {@link #testProcessor(Supplier, List, List, boolean, boolean, boolean, boolean, BiPredicate)}
     * with progress assertion and snapshot+restore enabled.
     */
    public static <T, U> void testProcessor(@Nonnull ProcessorMetaSupplier supplier,
                                            @Nonnull List<T> input, @Nonnull List<U> expectedOutput) {
        testProcessor(supplierFrom(supplier), input, expectedOutput, true, true, false, true, Objects::equals);
    }

    /**
     * Convenience for {@link #testProcessor(Supplier, List, List, boolean, boolean, boolean, boolean, BiPredicate)}
     * with progress assertion enabled. Snapshot+restore is disabled
     * since we don't have processor supplier and cannot create new instances
     * of the processor.
     */
    public static <T, U> void testProcessor(@Nonnull Processor processor, @Nonnull List<T> input,
                                            @Nonnull List<U> expectedOutput) {
        Processor[] p = {processor};
        testProcessor(() -> {
            try {
                // return the processor only once: not suitable for snapshot testing
                return p[0];
            } finally {
                p[0] = null;
            }
        }, input, expectedOutput, true, false, false, true, Objects::equals);
    }

    /**
     * A utility to test processors. It will initialize the processor instance,
     * pass input items to it and assert the outbox contents.
     * <p>
     * This method does the following:
     * <ul>
     *     <li>initializes the processor by calling {@link Processor#init(
     *     com.hazelcast.jet.Outbox, com.hazelcast.jet.SnapshotOutbox, com.hazelcast.jet.Processor.Context)}
     *
     *     <li>does snapshot+restore (see below)
     *
     *     <li>calls {@link Processor#process(int, com.hazelcast.jet.Inbox)
     *     Processor.process(0, inbox)}, the inbox always contains one item
     *     from {@code input} parameter
     *
     *     <li>every time the inbox gets empty does does snapshot+restore
     *
     *     <li>calls {@link Processor#complete()} until it returns {@code true}
     *
     *     <li>does snapshot+restore after {@code complete()} returned {@code
     *     false}
     * </ul>
     * The snapshot+restore test procedure:
     * <ul>
     *     <li>{@code saveSnapshot()} is called
     *
     *     <li>new processor instance is created, from now on only this
     *     instance will be used
     *
     *     <li>snapshot is restored using {@code restoreSnapshot()}
     *
     *     <li>{@code finishSnapshotRestore()} is called
     * </ul>
     * <p>
     * For each call to any processing method the progress is asserted. The
     * processor must do at least one of these:<ul>
     *     <li>take something from inbox
     *     <li>put something to outbox
     *     <li>for boolean-returning methods, returning {@code true} is
     *     considered as making progress
     * </ul>
     * <h4>Cooperative processors</h4>
     * For cooperative processors a 1-capacity outbox will be provided, which
     * will additionally be full in every other call to {@code process()}. This
     * will test the edge case: the {@code process()} method is called even
     * when the outbox is full to give the processor a chance to process inbox.
     * The snapshot outbox will also have capacity of 1 for a cooperative
     * processor.
     * <p>
     * Additionally, time spent in each call to processing method must not
     * exceed {@link #COOPERATIVE_TIME_LIMIT_MS_FAIL}.
     * <h4>Not-covered cases</h4>
     * This class does not cover these cases:<ul>
     *     <li>Testing of processors which distinguish input or output edges
     *     by ordinal
     *     <li>Checking that the state of a stateful processor is empty at the
     *     end (you can do that yourself afterwards with the last instance
     *     returned from your supplier).
     *     <li>This utility never calls {@link Processor#tryProcess()}.
     * </ul>
     *
     * <h4>Example usage</h4>
     * This will test one of the jet-provided processors:
     * <pre>{@code
     * TestSupport.testProcessor(
     *         Processors.map((String s) -> s.toUpperCase()),
     *         asList("foo", "bar"),
     *         asList("FOO", "BAR")
     * );
     * }</pre>
     * @param <T> input items type
     * @param <U> output items type
     * @param supplier a processor instance to test
     * @param input input to pass
     * @param expectedOutput expected output
     * @param assertProgress if false, progress will not be asserted after
     *                       {@code process()} and {@code complete()} calls
     * @param doSnapshots if true, snapshot will be saved and restored before
     *                    first item and after each {@code process()} and {@code
     *                    complete()} call. The normal test will be performed too (as
     *                    if this parameter was {@code false})
     * @param logInputOutput if {@code true}, input and output objects
     *                       will be logged
     * @param callComplete Whether to call {@code complete()} method. Suitable for
     *                     testing of streaming processors to make sure that flushing code in
     *                     complete is not executed.
     * @param outputChecker Predicate to compare expected and actual output.
     */
    public static <T, U> void testProcessor(@Nonnull Supplier<Processor> supplier,
                                            @Nonnull List<T> input,
                                            @Nonnull List<U> expectedOutput,
                                            boolean assertProgress,
                                            boolean doSnapshots,
                                            boolean logInputOutput,
                                            boolean callComplete,
                                            @Nonnull BiPredicate<? super List<U>, ? super List<Object>> outputChecker) {
        if (doSnapshots) {
            // if we test with snapshots, also do the test without snapshots
            System.out.println("### Running the test with doSnapshots=false");
            testProcessor(supplier, input, expectedOutput, assertProgress, false, logInputOutput,
                    callComplete, outputChecker);
            System.out.println("### Running the test with doSnapshots=true");
        }

        TestInbox inbox = new TestInbox();
        Processor[] processor = {supplier.get()};
        boolean failOnTimeout = false; // TODO

        // we'll use 1-capacity outbox to test cooperative emission, if the processor is cooperative
        int outboxCapacity = processor[0].isCooperative() ? 1 : Integer.MAX_VALUE;
        TestOutbox outbox = new TestOutbox(new int[] {outboxCapacity}, outboxCapacity);
        List<Object> actualOutput = new ArrayList<>();

        // create instance of your processor and call the init() method
        processor[0].init(outbox, outbox, new TestProcessorContext());

        // do snapshot+restore before processing any item. This will test saveSnapshot() in this edge case
        snapshotAndRestore(processor, supplier, outbox, actualOutput, doSnapshots,
                assertProgress, logInputOutput, failOnTimeout);

        // call the process() method
        Iterator<T> inputIterator = input.iterator();
        while (inputIterator.hasNext() || !inbox.isEmpty()) {
            if (inbox.isEmpty()) {
                inbox.add(inputIterator.next());
                if (logInputOutput) {
                    System.out.println("Input: " + inbox.peek());
                }
            }
            checkTime("process", () -> processor[0].process(0, inbox), failOnTimeout);
            assertTrue("process() call without progress",
                    !assertProgress || inbox.isEmpty() || !outbox.queueWithOrdinal(0).isEmpty());
            if (processor[0].isCooperative() && outbox.queueWithOrdinal(0).size() == 1 && !inbox.isEmpty()) {
                // if the outbox is full, call the process() method again. Cooperative
                // processor must be able to cope with this situation and not try to put
                // more items to the outbox.
                checkTime("process", () -> processor[0].process(0, inbox), failOnTimeout);
            }
            drainOutbox(outbox.queueWithOrdinal(0), actualOutput, logInputOutput);
            if (inbox.isEmpty()) {
                snapshotAndRestore(processor, supplier, outbox, actualOutput, doSnapshots,
                        assertProgress, logInputOutput, failOnTimeout);
            }
        }

        // call the complete() method
        if (callComplete) {
            boolean[] done = {false};
            do {
                checkTime("complete", () -> done[0] = processor[0].complete(), failOnTimeout);
                assertTrue("complete() call without progress",
                        !assertProgress || done[0] || !outbox.queueWithOrdinal(0).isEmpty());
                drainOutbox(outbox.queueWithOrdinal(0), actualOutput, logInputOutput);
                snapshotAndRestore(processor, supplier, outbox, actualOutput, doSnapshots,
                        assertProgress, logInputOutput, failOnTimeout);
            } while (!done[0]);
        }

        // assert the outbox
        if (!outputChecker.test(expectedOutput, actualOutput)) {
            assertEquals("processor output doesn't match", listToString(expectedOutput), listToString(actualOutput));
        }
    }

    private static void snapshotAndRestore(Processor[] processor, Supplier<Processor> supplier,
                                           TestOutbox outbox, List<Object> actualOutput,
                                           boolean enabled, boolean assertProgress, boolean logInputOutput,
                                           boolean failOnTimeout) {
        if (!enabled) {
            return;
        }

        // save state of current processor
        TestInbox snapshotInbox = new TestInbox();
        boolean[] done = {false};
        Set<Object> keys = new HashSet<>();
        do {
            checkTime("saveSnapshot", () -> done[0] = processor[0].saveSnapshot(), failOnTimeout);
            for (Entry<MockData, MockData> entry : outbox.snapshotQueue()) {
                Object key = entry.getKey().getObject();
                assertTrue("Duplicate key produced in saveSnapshot()\n  Duplicate: " + key + "\n  Keys so far: " + keys,
                        keys.add(key));
                snapshotInbox.add(entry(key, entry.getValue().getObject()));
            }
            assertTrue("saveSnapshot() call without progress",
                    !assertProgress || done[0] || !outbox.snapshotQueue().isEmpty()
                            || !outbox.queueWithOrdinal(0).isEmpty());
            drainOutbox(outbox.queueWithOrdinal(0), actualOutput, logInputOutput);
            outbox.snapshotQueue().clear();
        } while (!done[0]);

        // restore state to new processor
        processor[0] = supplier.get();
        processor[0].init(outbox, outbox, new TestProcessorContext());

        if (snapshotInbox.isEmpty()) {
            // don't call finishSnapshotRestore, if snapshot was empty
            return;
        }
        int lastInboxSize = snapshotInbox.size();
        while (!snapshotInbox.isEmpty()) {
            checkTime("restoreSnapshot", () -> processor[0].restoreSnapshot(snapshotInbox), failOnTimeout);
            assertTrue("restoreSnapshot() call without progress",
                    !assertProgress || lastInboxSize > snapshotInbox.size() || !outbox.queueWithOrdinal(0).isEmpty());
            drainOutbox(outbox.queueWithOrdinal(0), actualOutput, logInputOutput);
            lastInboxSize = snapshotInbox.size();
        }
        do {
            checkTime("finishSnapshotRestore", () -> done[0] = processor[0].finishSnapshotRestore(), failOnTimeout);
            assertTrue("finishSnapshotRestore() call without progress",
                    !assertProgress || done[0] || !outbox.queueWithOrdinal(0).isEmpty());
            drainOutbox(outbox.queueWithOrdinal(0), actualOutput, logInputOutput);
        } while (!done[0]);
    }

    private static void checkTime(String methodName, Runnable r, boolean failOnTimeout) {
        long start = System.nanoTime();
        r.run();
        long elapsed = System.nanoTime() - start;

        if (failOnTimeout) {
            assertTrue(String.format("call to %s() took %.1fms, it should be <%dms", methodName,
                    elapsed / (double) MILLISECONDS.toNanos(1), COOPERATIVE_TIME_LIMIT_MS_FAIL),
                    elapsed < MILLISECONDS.toNanos(COOPERATIVE_TIME_LIMIT_MS_FAIL));
        }
        // print warning
        if (elapsed > MILLISECONDS.toNanos(COOPERATIVE_TIME_LIMIT_MS_WARN)) {
            System.out.println(String.format("Warning: call to %s() took %.2fms, it should be <%dms normally",
                    methodName, elapsed / (double) MILLISECONDS.toNanos(1), COOPERATIVE_TIME_LIMIT_MS_WARN));
        }
    }

    /**
     * Move all items from the outbox to the {@code outputList}.
     * @param outboxBucket the queue from Outbox to drain
     * @param outputList target list
     * @param logItems Whether to log drained items to System.out
     */
    public static void drainOutbox(Queue<Object> outboxBucket, List<Object> outputList, boolean logItems) {
        for (Object o; (o = outboxBucket.poll()) != null; ) {
            outputList.add(o);
            if (logItems) {
                System.out.println("Output: " + o);
            }
        }
    }

    /**
     * Gets single processor instance from processor supplier.
     */
    public static Supplier<Processor> supplierFrom(ProcessorSupplier supplier) {
        supplier.init(new TestProcessorSupplierContext());
        return () -> supplier.get(1).iterator().next();
    }

    /**
     * Gets single processor instance from meta processor supplier.
     */
    public static Supplier<Processor> supplierFrom(ProcessorMetaSupplier supplier) {
        supplier.init(new TestProcessorMetaSupplierContext());
        return supplierFrom(supplier.get(singletonList(LOCAL_ADDRESS)).apply(LOCAL_ADDRESS));
    }

    private static String listToString(List<?> list) {
        return list.stream()
                .map(String::valueOf)
                .collect(Collectors.joining("\n"));
    }
}
