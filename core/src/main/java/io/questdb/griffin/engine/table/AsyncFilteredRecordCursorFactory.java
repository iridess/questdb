/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin.engine.table;

import io.questdb.MessageBus;
import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.GenericRecordMetadata;
import io.questdb.cairo.sql.*;
import io.questdb.cairo.sql.async.PageFrameReduceTask;
import io.questdb.cairo.sql.async.PageFrameReducer;
import io.questdb.cairo.sql.async.PageFrameSequence;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.mp.SCSequence;
import io.questdb.mp.Sequence;
import io.questdb.std.DirectLongList;
import io.questdb.std.MemoryTag;
import io.questdb.std.Misc;
import org.jetbrains.annotations.Nullable;

import static io.questdb.cairo.sql.DataFrameCursorFactory.*;

public class AsyncFilteredRecordCursorFactory implements RecordCursorFactory {

    private static final PageFrameReducer REDUCER = AsyncFilteredRecordCursorFactory::filter;

    private final RecordCursorFactory base;
    private final RecordMetadata baseMetadata;
    private final AsyncFilteredRecordCursor cursor;
    private final AsyncFilteredNegativeLimitRecordCursor negativeLimitCursor;
    private final Function filter;
    private final PageFrameSequence<Function> frameSequence;
    private final SCSequence collectSubSeq = new SCSequence();
    private final Function limitLoFunction;
    private final int maxNegativeLimit;
    private DirectLongList negativeLimitRows;

    public AsyncFilteredRecordCursorFactory(
            CairoConfiguration configuration,
            MessageBus messageBus,
            RecordCursorFactory base,
            Function filter,
            @Nullable Function limitLoFunction
    ) {
        assert !(base instanceof AsyncFilteredRecordCursorFactory);
        this.base = base;
        // TODO: revisit timestamp index returned for ORDER BY DESC queries with no filter; see OrderByDescRowSkippingTest
        if (base.hasDescendingOrder()) {
            // Copy metadata and erase timestamp index in case of ORDER BY DESC.
            GenericRecordMetadata copy = GenericRecordMetadata.copyOf(base.getMetadata());
            copy.setTimestampIndex(-1);
            this.baseMetadata = copy;
        } else {
            this.baseMetadata = base.getMetadata();
        }
        this.cursor = new AsyncFilteredRecordCursor(filter, base.hasDescendingOrder());
        this.negativeLimitCursor = new AsyncFilteredNegativeLimitRecordCursor();
        this.filter = filter;
        this.frameSequence = new PageFrameSequence<>(configuration, messageBus, REDUCER);
        this.limitLoFunction = limitLoFunction;
        this.maxNegativeLimit = configuration.getSqlMaxNegativeLimit();
    }

    @Override
    public void close() {
        Misc.free(base);
        Misc.free(filter);
        Misc.free(frameSequence);
        Misc.free(negativeLimitRows);
    }

    @Override
    public boolean followedLimitAdvice() {
        return limitLoFunction != null;
    }

    @Override
    public RecordCursor getCursor(SqlExecutionContext executionContext) throws SqlException {
        long rowsRemaining;
        final int order;
        if (limitLoFunction != null) {
            limitLoFunction.init(frameSequence.getSymbolTableSource(), executionContext);
            rowsRemaining = limitLoFunction.getLong(null);
            // on negative limit we will be looking for positive number of rows
            // while scanning table from the highest timestamp to the lowest
            if (rowsRemaining > -1) {
                order = ORDER_ASC;
            } else {
                order = ORDER_DESC;
                rowsRemaining = -rowsRemaining;
            }
        } else {
            rowsRemaining = Long.MAX_VALUE;
            order = ORDER_ANY;
        }

        if (order == ORDER_DESC) {
            if (rowsRemaining > maxNegativeLimit) {
                throw SqlException.position(0).put("absolute LIMIT value is too large, maximum allowed value: ").put(maxNegativeLimit);
            }
            if (negativeLimitRows == null) {
                negativeLimitRows = new DirectLongList(maxNegativeLimit, MemoryTag.NATIVE_OFFLOAD);
            }
            negativeLimitCursor.of(collectSubSeq, execute(executionContext, collectSubSeq, order), rowsRemaining, negativeLimitRows);
            return negativeLimitCursor;
        }

        cursor.of(collectSubSeq, execute(executionContext, collectSubSeq, order), rowsRemaining);
        return cursor;
    }

    @Override
    public RecordMetadata getMetadata() {
        return baseMetadata;
    }

    @Override
    public PageFrameSequence<Function> execute(SqlExecutionContext executionContext, Sequence collectSubSeq, int direction) throws SqlException {
        return frameSequence.dispatch(base, executionContext, collectSubSeq, filter, direction);
    }

    @Override
    public boolean recordCursorSupportsRandomAccess() {
        return base.recordCursorSupportsRandomAccess();
    }

    @Override
    public boolean supportsUpdateRowId(CharSequence tableName) {
        return base.supportsUpdateRowId(tableName);
    }

    @Override
    public boolean usesCompiledFilter() {
        return base.usesCompiledFilter();
    }

    @Override
    public boolean hasDescendingOrder() {
        return base.hasDescendingOrder();
    }

    private static void filter(PageAddressCacheRecord record, PageFrameReduceTask task) {
        final DirectLongList rows = task.getRows();
        final long frameRowCount = task.getFrameRowCount();
        final Function filter = task.getFrameSequence(Function.class).getAtom();

        rows.clear();
        for (long r = 0; r < frameRowCount; r++) {
            record.setRowIndex(r);
            if (filter.getBool(record)) {
                rows.add(r);
            }
        }
    }
}
