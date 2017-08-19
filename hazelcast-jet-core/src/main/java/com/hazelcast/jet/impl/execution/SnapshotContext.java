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

package com.hazelcast.jet.impl.execution;

import com.hazelcast.jet.config.ProcessingGuarantee;
import com.hazelcast.jet.impl.operation.SnapshotOperation;
import com.hazelcast.logging.ILogger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hazelcast.jet.impl.util.Util.formatIds;

public class SnapshotContext {

    private static final int NO_SNAPSHOT = -1;
    private final ILogger logger;

    /**
     * SnapshotId of snapshot currently being created. Source processors read
     * it and when they see changed value, they start a snapshot with that
     * ID. {@code Long.MIN_VALUE} means no snapshot is in progress.
     */
    private volatile long currentSnapshotId = NO_SNAPSHOT;

    /**
     * Current number of {@link StoreSnapshotTasklet}s in the job. It's
     * decremented as the tasklets complete (this is when they receive
     * DONE_ITEM and after all pending async ops completed).
     */
    private int numTasklets = Integer.MIN_VALUE;

    /**
     * Current number of {@link StoreSnapshotTasklet}s in the job connected
     * to higher priority vertices. It's decremented when higher priority
     * tasklet completes. Snapshot is postponed until this counter is 0.
     */
    private int numHigherPriorityTasklets = Integer.MIN_VALUE;

    /**
     * Remaining number of sinks in currently produced snapshot. When it is
     * decreased to 0, the snapshot is complete.
     */
    private final AtomicInteger numRemainingTasklets = new AtomicInteger();

    /** Future which will be completed when the current snapshot completes. */
    private volatile CompletableFuture<Void> future;

    private final long jobId;
    private final long executionId;
    private final ProcessingGuarantee guarantee;

    SnapshotContext(ILogger logger, long jobId, long executionId, ProcessingGuarantee guarantee) {
        this.jobId = jobId;
        this.executionId = executionId;
        this.guarantee = guarantee;
        this.logger = logger;
    }

    long currentSnapshotId() {
        return currentSnapshotId;
    }

    ProcessingGuarantee processingGuarantee() {
        return guarantee;
    }

    void initTaskletCount(int totalCount, int highPriorityCount) {
        assert this.numTasklets == Integer.MIN_VALUE : "Tasklet count already set once.";
        assert totalCount >= highPriorityCount : "totalCount=" + totalCount + ", highPriorityCount=" + highPriorityCount;
        assert totalCount > 0 : "totalCount=" + totalCount;
        assert highPriorityCount >= 0 : "highPriorityCount=" + highPriorityCount;

        this.numTasklets = totalCount;
        this.numHigherPriorityTasklets = highPriorityCount;
    }

    /**
     * This method is called when the member received {@link
     * SnapshotOperation}.
     */
    synchronized CompletableFuture<Void> startNewSnapshot(long snapshotId) {
        assert snapshotId == currentSnapshotId + 1
                : "new snapshotId not incremented by 1. Previous=" + currentSnapshotId + ", new=" + snapshotId;
        assert numTasklets >= 0 : "numTasklets=" + numTasklets;

        if (numTasklets == 0) {
            // member is already done with the job and master didn't know it yet - we are immediately done.
            return CompletableFuture.completedFuture(null);
        }
        boolean success = numRemainingTasklets.compareAndSet(0, numTasklets);
        assert success : "previous snapshot was not finished, numRemainingTasklets=" + numRemainingTasklets.get();
        // if there are no higher priority tasklets, start the snapshot now
        if (numHigherPriorityTasklets == 0) {
            currentSnapshotId = snapshotId;
        } else {
            logger.warning("Snapshot " + snapshotId + " for " + formatIds(jobId, executionId) + " is postponed" +
                    " until all higher priority vertices are completed (number of vertices = "
                    + numHigherPriorityTasklets + ')');
        }
        return (future = new CompletableFuture<>());
    }

    /**
     * Called when {@link StoreSnapshotTasklet} is done (received DONE_ITEM
     * from all its processors).
     *
     * @param lastSnapshotId id fo the last snapshot completed by the tasklet
     */
    synchronized void taskletDone(long lastSnapshotId, boolean isHigherPrioritySource) {
        assert numTasklets > 0;
        assert lastSnapshotId <= currentSnapshotId;

        numTasklets--;
        if (isHigherPrioritySource) {
            assert numHigherPriorityTasklets > 0;
            numHigherPriorityTasklets--;
            // after all higher priority vertices are done we can start the snapshot
            if (numHigherPriorityTasklets == 0) {
                currentSnapshotId++;
                logger.info("Postponed snapshot " + currentSnapshotId + " for " + formatIds(jobId, executionId)
                        + " started");
            }
        }
        if (currentSnapshotId < lastSnapshotId) {
            snapshotDoneForTasklet();
        }
    }

    /**
     * Called when current snapshot is done in {@link StoreSnapshotTasklet}
     * (it received barriers from all its processors and all async flush
     * operations are done).
     */
    void snapshotDoneForTasklet() {
        if (numRemainingTasklets.decrementAndGet() == 0) {
            future.complete(null);
            future = null;
        }
    }
}
