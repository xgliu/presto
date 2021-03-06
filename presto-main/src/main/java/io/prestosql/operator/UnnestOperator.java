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
import io.prestosql.spi.Page;
import io.prestosql.spi.PageBuilder;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.type.ArrayType;
import io.prestosql.spi.type.MapType;
import io.prestosql.spi.type.RowType;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.planner.plan.PlanNodeId;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static java.util.Objects.requireNonNull;

public class UnnestOperator
        implements Operator
{
    public static class UnnestOperatorFactory
            implements OperatorFactory
    {
        private final int operatorId;
        private final PlanNodeId planNodeId;
        private final List<Integer> replicateChannels;
        private final List<Type> replicateTypes;
        private final List<Integer> unnestChannels;
        private final List<Type> unnestTypes;
        private final boolean withOrdinality;
        private boolean closed;

        public UnnestOperatorFactory(int operatorId, PlanNodeId planNodeId, List<Integer> replicateChannels, List<Type> replicateTypes, List<Integer> unnestChannels, List<Type> unnestTypes, boolean withOrdinality)
        {
            this.operatorId = operatorId;
            this.planNodeId = requireNonNull(planNodeId, "planNodeId is null");
            this.replicateChannels = ImmutableList.copyOf(requireNonNull(replicateChannels, "replicateChannels is null"));
            this.replicateTypes = ImmutableList.copyOf(requireNonNull(replicateTypes, "replicateTypes is null"));
            checkArgument(replicateChannels.size() == replicateTypes.size(), "replicateChannels and replicateTypes do not match");
            this.unnestChannels = ImmutableList.copyOf(requireNonNull(unnestChannels, "unnestChannels is null"));
            this.unnestTypes = ImmutableList.copyOf(requireNonNull(unnestTypes, "unnestTypes is null"));
            checkArgument(unnestChannels.size() == unnestTypes.size(), "unnestChannels and unnestTypes do not match");
            this.withOrdinality = withOrdinality;
        }

        @Override
        public Operator createOperator(DriverContext driverContext)
        {
            checkState(!closed, "Factory is already closed");
            OperatorContext operatorContext = driverContext.addOperatorContext(operatorId, planNodeId, UnnestOperator.class.getSimpleName());
            return new UnnestOperator(operatorContext, replicateChannels, replicateTypes, unnestChannels, unnestTypes, withOrdinality);
        }

        @Override
        public void noMoreOperators()
        {
            closed = true;
        }

        @Override
        public OperatorFactory duplicate()
        {
            return new UnnestOperatorFactory(operatorId, planNodeId, replicateChannels, replicateTypes, unnestChannels, unnestTypes, withOrdinality);
        }
    }

    private final OperatorContext operatorContext;
    private final List<Integer> replicateChannels;
    private final List<Type> replicateTypes;
    private final List<Integer> unnestChannels;
    private final List<Type> unnestTypes;
    private final boolean withOrdinality;
    private final PageBuilder pageBuilder;
    private final List<Unnester> unnesters;
    private boolean finishing;
    private Page currentPage;
    private int currentPosition;
    private int ordinalityCount;

    public UnnestOperator(OperatorContext operatorContext, List<Integer> replicateChannels, List<Type> replicateTypes, List<Integer> unnestChannels, List<Type> unnestTypes, boolean withOrdinality)
    {
        this.operatorContext = requireNonNull(operatorContext, "operatorContext is null");
        this.replicateChannels = ImmutableList.copyOf(requireNonNull(replicateChannels, "replicateChannels is null"));
        this.replicateTypes = ImmutableList.copyOf(requireNonNull(replicateTypes, "replicateTypes is null"));
        this.unnestChannels = ImmutableList.copyOf(requireNonNull(unnestChannels, "unnestChannels is null"));
        this.unnestTypes = ImmutableList.copyOf(requireNonNull(unnestTypes, "unnestTypes is null"));
        this.withOrdinality = withOrdinality;
        checkArgument(replicateChannels.size() == replicateTypes.size(), "replicate channels or types has wrong size");
        checkArgument(unnestChannels.size() == unnestTypes.size(), "unnest channels or types has wrong size");
        ImmutableList.Builder<Type> outputTypesBuilder = ImmutableList.<Type>builder()
                .addAll(replicateTypes)
                .addAll(getUnnestedTypes(unnestTypes));
        if (withOrdinality) {
            outputTypesBuilder.add(BIGINT);
        }
        this.pageBuilder = new PageBuilder(outputTypesBuilder.build());
        this.unnesters = new ArrayList<>(unnestTypes.size());
        for (Type type : unnestTypes) {
            if (type instanceof ArrayType) {
                Type elementType = ((ArrayType) type).getElementType();
                if (elementType instanceof RowType) {
                    unnesters.add(new ArrayOfRowsUnnester(elementType));
                }
                else {
                    unnesters.add(new ArrayUnnester(elementType));
                }
            }
            else if (type instanceof MapType) {
                MapType mapType = (MapType) type;
                unnesters.add(new MapUnnester(mapType.getKeyType(), mapType.getValueType()));
            }
            else {
                throw new IllegalArgumentException("Cannot unnest type: " + type);
            }
        }
    }

    private static List<Type> getUnnestedTypes(List<Type> types)
    {
        ImmutableList.Builder<Type> builder = ImmutableList.builder();
        for (Type type : types) {
            checkArgument(type instanceof ArrayType || type instanceof MapType, "Can only unnest map and array types");
            if (type instanceof ArrayType && ((ArrayType) type).getElementType() instanceof RowType) {
                builder.addAll(((ArrayType) type).getElementType().getTypeParameters());
            }
            else {
                builder.addAll(type.getTypeParameters());
            }
        }
        return builder.build();
    }

    @Override
    public OperatorContext getOperatorContext()
    {
        return operatorContext;
    }

    @Override
    public void finish()
    {
        finishing = true;
    }

    @Override
    public boolean isFinished()
    {
        return finishing && pageBuilder.isEmpty() && currentPage == null;
    }

    @Override
    public boolean needsInput()
    {
        return !finishing && !pageBuilder.isFull() && currentPage == null;
    }

    @Override
    public void addInput(Page page)
    {
        checkState(!finishing, "Operator is already finishing");
        requireNonNull(page, "page is null");
        checkState(currentPage == null, "currentPage is not null");
        checkState(!pageBuilder.isFull(), "Page buffer is full");

        currentPage = page;
        currentPosition = 0;
        fillUnnesters();
    }

    private void fillUnnesters()
    {
        for (int i = 0; i < unnestTypes.size(); i++) {
            Type type = unnestTypes.get(i);
            int channel = unnestChannels.get(i);
            Block block = null;
            if (!currentPage.getBlock(channel).isNull(currentPosition)) {
                block = (Block) type.getObject(currentPage.getBlock(channel), currentPosition);
            }
            unnesters.get(i).setBlock(block);
        }
        ordinalityCount = 0;
    }

    private boolean anyUnnesterHasData()
    {
        for (Unnester unnester : unnesters) {
            if (unnester.hasNext()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Page getOutput()
    {
        while (!pageBuilder.isFull() && currentPage != null) {
            // Advance until we find data to unnest
            while (!anyUnnesterHasData()) {
                currentPosition++;
                if (currentPosition == currentPage.getPositionCount()) {
                    currentPage = null;
                    currentPosition = 0;
                    break;
                }
                fillUnnesters();
            }
            while (!pageBuilder.isFull() && anyUnnesterHasData()) {
                // Copy all the channels marked for replication
                for (int replicateChannel = 0; replicateChannel < replicateTypes.size(); replicateChannel++) {
                    Type type = replicateTypes.get(replicateChannel);
                    int channel = replicateChannels.get(replicateChannel);
                    type.appendTo(currentPage.getBlock(channel), currentPosition, pageBuilder.getBlockBuilder(replicateChannel));
                }
                int offset = replicateTypes.size();

                pageBuilder.declarePosition();
                for (Unnester unnester : unnesters) {
                    if (unnester.hasNext()) {
                        unnester.appendNext(pageBuilder, offset);
                    }
                    else {
                        for (int unnesterChannelIndex = 0; unnesterChannelIndex < unnester.getChannelCount(); unnesterChannelIndex++) {
                            pageBuilder.getBlockBuilder(offset + unnesterChannelIndex).appendNull();
                        }
                    }
                    offset += unnester.getChannelCount();
                }

                if (withOrdinality) {
                    ordinalityCount++;
                    BIGINT.writeLong(pageBuilder.getBlockBuilder(offset), ordinalityCount);
                }
            }
        }

        if ((!finishing && !pageBuilder.isFull()) || pageBuilder.isEmpty()) {
            return null;
        }

        Page page = pageBuilder.build();
        pageBuilder.reset();
        return page;
    }
}
