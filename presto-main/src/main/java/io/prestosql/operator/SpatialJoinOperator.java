/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.operator;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import io.prestosql.memory.context.LocalMemoryContext;
import io.prestosql.snapshot.SingleInputSnapshotState;
import io.prestosql.spi.Page;
import io.prestosql.spi.PageBuilder;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.plan.PlanNodeId;
import io.prestosql.spi.snapshot.BlockEncodingSerdeProvider;
import io.prestosql.spi.snapshot.MarkerPage;
import io.prestosql.spi.snapshot.RestorableConfig;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.planner.plan.SpatialJoinNode;

import javax.annotation.Nullable;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.airlift.concurrent.MoreFutures.getDone;
import static io.airlift.slice.SizeOf.sizeOf;
import static io.prestosql.sql.planner.plan.SpatialJoinNode.Type.INNER;
import static io.prestosql.sql.planner.plan.SpatialJoinNode.Type.LEFT;
import static java.util.Objects.requireNonNull;

@RestorableConfig(uncapturedFields = {"probeTypes", "probeOutputChannels", "partitionChannel", "pagesSpatialIndexFactory", "onClose",
        "pagesSpatialIndexFuture", "probe", "probePosition", "joinPositions", "snapshotState", "finished", "finishing", "closed"})
public class SpatialJoinOperator
        implements Operator
{
    public static final class SpatialJoinOperatorFactory
            implements OperatorFactory
    {
        private final int operatorId;
        private final PlanNodeId planNodeId;
        private final SpatialJoinNode.Type joinType;
        private final List<Type> probeTypes;
        private final List<Integer> probeOutputChannels;
        private final int probeGeometryChannel;
        private final Optional<Integer> partitionChannel;
        private final PagesSpatialIndexFactory pagesSpatialIndexFactory;
        private final ReferenceCount referenceCount;

        private boolean closed;

        public SpatialJoinOperatorFactory(
                int operatorId,
                PlanNodeId planNodeId,
                SpatialJoinNode.Type joinType,
                List<Type> probeTypes,
                List<Integer> probeOutputChannels,
                int probeGeometryChannel,
                Optional<Integer> partitionChannel,
                PagesSpatialIndexFactory pagesSpatialIndexFactory)
        {
            checkArgument(joinType == INNER || joinType == LEFT, "unsupported join type: %s", joinType);
            this.operatorId = operatorId;
            this.planNodeId = requireNonNull(planNodeId, "planNodeId is null");
            this.joinType = joinType;
            this.probeTypes = ImmutableList.copyOf(probeTypes);
            this.probeOutputChannels = ImmutableList.copyOf(probeOutputChannels);
            this.probeGeometryChannel = probeGeometryChannel;
            this.partitionChannel = requireNonNull(partitionChannel, "partitionChannel is null");
            this.pagesSpatialIndexFactory = requireNonNull(pagesSpatialIndexFactory, "pagesSpatialIndexFactory is null");
            this.referenceCount = new ReferenceCount(1);
            this.referenceCount.getFreeFuture().addListener(pagesSpatialIndexFactory::destroy, directExecutor());
        }

        private SpatialJoinOperatorFactory(SpatialJoinOperatorFactory other)
        {
            this.operatorId = other.operatorId;
            this.planNodeId = other.planNodeId;
            this.joinType = other.joinType;
            this.probeTypes = other.probeTypes;
            this.probeOutputChannels = other.probeOutputChannels;
            this.probeGeometryChannel = other.probeGeometryChannel;
            this.partitionChannel = other.partitionChannel;
            this.pagesSpatialIndexFactory = other.pagesSpatialIndexFactory;
            this.referenceCount = other.referenceCount;
            this.closed = false;
            referenceCount.retain();
        }

        @Override
        public Operator createOperator(DriverContext driverContext)
        {
            checkState(!closed, "Factory is already closed");
            OperatorContext operatorContext = driverContext.addOperatorContext(
                    operatorId,
                    planNodeId,
                    SpatialJoinOperator.class.getSimpleName());
            referenceCount.retain();
            return new SpatialJoinOperator(
                    operatorContext,
                    joinType,
                    probeTypes,
                    probeOutputChannels,
                    probeGeometryChannel,
                    partitionChannel,
                    pagesSpatialIndexFactory,
                    referenceCount::release);
        }

        @Override
        public void noMoreOperators()
        {
            if (closed) {
                return;
            }

            referenceCount.release();
            closed = true;
        }

        @Override
        public OperatorFactory duplicate()
        {
            checkState(!closed, "Factory is already closed");
            return new SpatialJoinOperatorFactory(this);
        }
    }

    private final OperatorContext operatorContext;
    private final LocalMemoryContext localUserMemoryContext;
    private final SpatialJoinNode.Type joinType;
    private final List<Type> probeTypes;
    private final List<Integer> probeOutputChannels;
    private final int probeGeometryChannel;
    private final Optional<Integer> partitionChannel;
    private final PagesSpatialIndexFactory pagesSpatialIndexFactory;
    private final Runnable onClose;

    private ListenableFuture<PagesSpatialIndex> pagesSpatialIndexFuture;
    private final PageBuilder pageBuilder;
    @Nullable
    private Page probe;

    // The following fields represent the state of the operator in case when processProbe yielded or
    // filled up pageBuilder before processing all records in a probe page.
    private int probePosition;
    @Nullable
    private int[] joinPositions;
    private int nextJoinPositionIndex;
    private boolean matchFound;

    private boolean finishing;
    private boolean finished;
    private boolean closed;

    private final SingleInputSnapshotState snapshotState;

    public SpatialJoinOperator(
            OperatorContext operatorContext,
            SpatialJoinNode.Type joinType,
            List<Type> probeTypes,
            List<Integer> probeOutputChannels,
            int probeGeometryChannel,
            Optional<Integer> partitionChannel,
            PagesSpatialIndexFactory pagesSpatialIndexFactory,
            Runnable onClose)
    {
        this.operatorContext = requireNonNull(operatorContext, "operatorContext is null");
        this.localUserMemoryContext = operatorContext.localUserMemoryContext();
        this.joinType = requireNonNull(joinType, "joinType is null");
        this.probeTypes = ImmutableList.copyOf(probeTypes);
        this.probeOutputChannels = ImmutableList.copyOf(probeOutputChannels);
        this.probeGeometryChannel = probeGeometryChannel;
        this.partitionChannel = requireNonNull(partitionChannel, "partitionChannel is null");
        this.pagesSpatialIndexFactory = requireNonNull(pagesSpatialIndexFactory, "pagesSpatialIndexFactory is null");
        this.onClose = requireNonNull(onClose, "onClose is null");
        this.pagesSpatialIndexFuture = pagesSpatialIndexFactory.createPagesSpatialIndex();
        this.pageBuilder = new PageBuilder(ImmutableList.<Type>builder()
                .addAll(probeOutputChannels.stream()
                        .map(probeTypes::get)
                        .iterator())
                .addAll(pagesSpatialIndexFactory.getOutputTypes())
                .build());
        this.snapshotState = operatorContext.isSnapshotEnabled() ? SingleInputSnapshotState.forOperator(this, operatorContext) : null;
    }

    @Override
    public OperatorContext getOperatorContext()
    {
        return operatorContext;
    }

    @Override
    public boolean needsInput()
    {
        return allowMarker() && pagesSpatialIndexFuture.isDone();
    }

    @Override
    public boolean allowMarker()
    {
        return !finished && !pageBuilder.isFull() && probe == null;
    }

    @Override
    public void addInput(Page page)
    {
        if (snapshotState != null) {
            if (snapshotState.processPage(page)) {
                return;
            }
        }

        verify(probe == null);
        probe = page;
        probePosition = 0;

        joinPositions = null;
    }

    @Override
    public Page getOutput()
    {
        verify(!finished);
        if (snapshotState != null) {
            Page marker = snapshotState.nextMarker();
            if (marker != null) {
                return marker;
            }
        }

        if (!pageBuilder.isFull() && probe != null) {
            processProbe();
        }

        if (pageBuilder.isFull()) {
            Page page = pageBuilder.build();
            pageBuilder.reset();
            return page;
        }

        if (finishing && probe == null) {
            Page page = null;
            if (!pageBuilder.isEmpty()) {
                page = pageBuilder.build();
                pageBuilder.reset();
            }
            finished = true;
            close();
            return page;
        }

        return null;
    }

    @Override
    public MarkerPage pollMarker()
    {
        return snapshotState.nextMarker();
    }

    private void processProbe()
    {
        verify(probe != null);

        PagesSpatialIndex pagesSpatialIndex = getDone(pagesSpatialIndexFuture);
        DriverYieldSignal yieldSignal = operatorContext.getDriverContext().getYieldSignal();
        while (probePosition < probe.getPositionCount()) {
            if (joinPositions == null) {
                joinPositions = pagesSpatialIndex.findJoinPositions(probePosition, probe, probeGeometryChannel, partitionChannel);
                localUserMemoryContext.setBytes(sizeOf(joinPositions));
                nextJoinPositionIndex = 0;
                matchFound = false;
                if (yieldSignal.isSet()) {
                    return;
                }
            }

            while (nextJoinPositionIndex < joinPositions.length) {
                if (pageBuilder.isFull()) {
                    return;
                }

                int joinPosition = joinPositions[nextJoinPositionIndex];

                if (pagesSpatialIndex.isJoinPositionEligible(joinPosition, probePosition, probe)) {
                    pageBuilder.declarePosition();
                    appendProbe();
                    pagesSpatialIndex.appendTo(joinPosition, pageBuilder, probeOutputChannels.size());
                    matchFound = true;
                }

                nextJoinPositionIndex++;

                if (yieldSignal.isSet()) {
                    return;
                }
            }

            if (!matchFound && joinType == LEFT) {
                if (pageBuilder.isFull()) {
                    return;
                }

                pageBuilder.declarePosition();
                appendProbe();
                int buildColumnCount = pagesSpatialIndexFactory.getOutputTypes().size();
                for (int i = 0; i < buildColumnCount; i++) {
                    pageBuilder.getBlockBuilder(probeOutputChannels.size() + i).appendNull();
                }
            }

            joinPositions = null;
            localUserMemoryContext.setBytes(0);
            probePosition++;
        }

        this.probe = null;
        this.probePosition = 0;
    }

    private void appendProbe()
    {
        int outputChannelOffset = 0;
        for (int outputIndex : probeOutputChannels) {
            Type type = probeTypes.get(outputIndex);
            Block block = probe.getBlock(outputIndex);
            type.appendTo(block, probePosition, pageBuilder.getBlockBuilder(outputChannelOffset));
            outputChannelOffset++;
        }
    }

    @Override
    public void finish()
    {
        finishing = true;
    }

    @Override
    public boolean isFinished()
    {
        if (snapshotState != null && snapshotState.hasMarker()) {
            // Snapshot: there are pending markers. Need to send them out before finishing this operator.
            return false;
        }

        return finished;
    }

    @Override
    public void close()
    {
        if (closed) {
            return;
        }
        closed = true;
        if (snapshotState != null) {
            snapshotState.close();
        }
        pagesSpatialIndexFuture = null;
        onClose.run();
    }

    @Override
    public Object capture(BlockEncodingSerdeProvider serdeProvider)
    {
        SpatialJoinOperatorState myState = new SpatialJoinOperatorState();
        myState.operatorContext = operatorContext.capture(serdeProvider);
        myState.localUserMemoryContext = localUserMemoryContext.getBytes();
        myState.pageBuilder = pageBuilder.capture(serdeProvider);
        myState.nextJoinPositionIndex = nextJoinPositionIndex;
        myState.matchFound = matchFound;
        return myState;
    }

    @Override
    public void restore(Object state, BlockEncodingSerdeProvider serdeProvider)
    {
        SpatialJoinOperatorState myState = (SpatialJoinOperatorState) state;
        this.operatorContext.restore(myState.operatorContext, serdeProvider);
        this.localUserMemoryContext.setBytes(myState.localUserMemoryContext);
        this.pageBuilder.restore(myState.pageBuilder, serdeProvider);
        this.nextJoinPositionIndex = myState.nextJoinPositionIndex;
        this.matchFound = myState.matchFound;
    }

    private static class SpatialJoinOperatorState
            implements Serializable
    {
        private Object operatorContext;
        private long localUserMemoryContext;
        private Object pageBuilder;
        private int nextJoinPositionIndex;
        private boolean matchFound;
    }
}
