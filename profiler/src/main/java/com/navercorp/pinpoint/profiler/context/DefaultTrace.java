/*
 * Copyright 2014 NAVER Corp.
 *
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

package com.navercorp.pinpoint.profiler.context;

import com.navercorp.pinpoint.bootstrap.config.ProfilerConfig;
import com.navercorp.pinpoint.bootstrap.context.*;
import com.navercorp.pinpoint.bootstrap.context.scope.TraceScope;
import com.navercorp.pinpoint.profiler.AgentInformation;
import com.navercorp.pinpoint.profiler.context.scope.DefaultTraceScopePool;
import com.navercorp.pinpoint.profiler.metadata.SqlMetaDataService;
import com.navercorp.pinpoint.profiler.metadata.StringMetaDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.navercorp.pinpoint.exception.PinpointException;
import com.navercorp.pinpoint.profiler.context.storage.Storage;

/**
 * @author netspider
 * @author emeroad
 * @author jaehong.kim
 */
public final class DefaultTrace implements Trace {

    private static final Logger logger = LoggerFactory.getLogger(DefaultTrace.class.getName());
    private static final boolean isTrace = logger.isTraceEnabled();
    private static final boolean isWarn = logger.isWarnEnabled();

    private final boolean sampling;

    private final long localTransactionId;
    private final TraceId traceId;
    private final CallStack callStack;

    private final Storage storage;

    private final WrappedSpanEventRecorder spanEventRecorder;
    private final DefaultSpanRecorder spanRecorder;
    private final AsyncIdGenerator asyncIdGenerator;

    private boolean closed = false;

    private Thread bindThread;
    private final DefaultTraceScopePool scopePool = new DefaultTraceScopePool();

    public DefaultTrace(ProfilerConfig profilerConfig, Storage storage, TraceId traceId, long localTransactionId, AsyncIdGenerator asyncIdGenerator, boolean sampling,
                        AgentInformation agentInformation, StringMetaDataService stringMetaDataService, SqlMetaDataService sqlMetaDataService) {
        if (profilerConfig == null) {
            throw new NullPointerException("profilerConfig must not be null");
        }
        if (storage == null) {
            throw new NullPointerException("storage must not be null");
        }
        if (traceId == null) {
            throw new NullPointerException("continueTraceId must not be null");
        }
        if (asyncIdGenerator == null) {
            throw new NullPointerException("asyncIdGenerator must not be null");
        }
        if (agentInformation == null) {
            throw new NullPointerException("agentInformation must not be null");
        }
                if (stringMetaDataService == null) {
            throw new NullPointerException("stringMetaDataService must not be null");
        }
        if (sqlMetaDataService == null) {
            throw new NullPointerException("sqlMetaDataService must not be null");
        }

        this.storage = storage;
        this.traceId = traceId;
        this.localTransactionId = localTransactionId;
        this.sampling = sampling;
        final Span span = createSpan(agentInformation);
        this.spanRecorder = new DefaultSpanRecorder(span, this.traceId, sampling, stringMetaDataService, sqlMetaDataService);
        this.spanRecorder.recordTraceId(this.traceId);
        this.spanEventRecorder = new WrappedSpanEventRecorder(stringMetaDataService, sqlMetaDataService);
        this.callStack = createCallStack(profilerConfig, span);

        this.asyncIdGenerator = asyncIdGenerator;
        setCurrentThread();
    }

    private CallStack createCallStack(ProfilerConfig profilerConfig, Span span) {
        if (profilerConfig != null) {
            final int maxCallStackDepth = profilerConfig.getCallStackMaxDepth();
            return new CallStack(span, maxCallStackDepth);
        } else {
            return new CallStack(span);
        }
    }

    private Span createSpan(AgentInformation agentInformation) {
        Span span = new Span();
        span.setAgentId(agentInformation.getAgentId());
        span.setApplicationName(agentInformation.getApplicationName());
        span.setAgentStartTime(agentInformation.getStartTime());
        span.setApplicationServiceType(agentInformation.getServerType().getCode());
        span.markBeforeTime();

        return span;
    }

    private SpanEventRecorder wrappedSpanEventRecorder(SpanEvent spanEvent) {
        final WrappedSpanEventRecorder spanEventRecorder = this.spanEventRecorder;
        spanEventRecorder.setWrapped(spanEvent);
        return spanEventRecorder;
    }

    public Span getSpan() {
        return this.spanRecorder.getSpan();
    }

    @Override
    public SpanEventRecorder traceBlockBegin() {
        return traceBlockBegin(DEFAULT_STACKID);
    }

    @Override
    public SpanEventRecorder traceBlockBegin(final int stackId) {
        // Set properties for the case when stackFrame is not used as part of Span.
        final SpanEvent spanEvent = new SpanEvent(spanRecorder.getSpan());
        spanEvent.markStartTime();
        spanEvent.setStackId(stackId);

        if (this.closed) {
            if (isWarn) {
                PinpointException exception = new PinpointException("already closed trace.");
                logger.warn("[DefaultTrace] Corrupted call stack found.", exception);
            }
        } else {
            callStack.push(spanEvent);
        }

        return wrappedSpanEventRecorder(spanEvent);
    }

    @Override
    public void traceBlockEnd() {
        traceBlockEnd(DEFAULT_STACKID);
    }

    @Override
    public void traceBlockEnd(int stackId) {
        if (this.closed) {
            if (isWarn) {
                final PinpointException exception = new PinpointException("already closed trace.");
                logger.warn("[DefaultTrace] Corrupted call stack found.", exception);
            }
            return;
        }

        final SpanEvent spanEvent = callStack.pop();
        if (spanEvent == null) {
            if (isWarn) {
                PinpointException exception = new PinpointException("call stack is empty.");
                logger.warn("[DefaultTrace] Corrupted call stack found.", exception);
            }
            return;
        }

        if (spanEvent.getStackId() != stackId) {
            // stack dump will make debugging easy.
            if (isWarn) {
                PinpointException exception = new PinpointException("not matched stack id. expected=" + stackId + ", current=" + spanEvent.getStackId());
                logger.warn("[DefaultTrace] Corrupted call stack found.", exception);
            }
        }

        if (spanEvent.isTimeRecording()) {
            spanEvent.markAfterTime();
        }
        logSpan(spanEvent);
    }

    @Override
    public void close() {
        if (closed) {
            logger.warn("Already closed trace.");
            return;
        }
        closed = true;

        if (!callStack.empty()) {
            if (isWarn) {
                PinpointException exception = new PinpointException("not empty call stack.");
                logger.warn("[DefaultTrace] Corrupted call stack found.", exception);
            }
            // skip
        } else {
            final Span span = spanRecorder.getSpan();
            if (span.isTimeRecording()) {
                span.markAfterTime();
            }
            logSpan(span);
        }

        this.storage.close();

    }

    @Override
    public void flush() {
        this.storage.flush();
    }

    /**
     * Get current TraceID. If it was not set this will return null.
     *
     * @return
     */
    @Override
    public TraceId getTraceId() {
        return this.traceId;
    }

    @Override
    public long getId() {
        return this.localTransactionId;
    }

    @Override
    public long getStartTime() {
        final DefaultSpanRecorder copy = this.spanRecorder;
        if (copy == null) {
            return 0;
        }
        return copy.getSpan().getStartTime();
    }

    @Override
    public Thread getBindThread() {
        return bindThread;
    }

    private void setCurrentThread() {
        this.setBindThread(Thread.currentThread());
    }

    private void setBindThread(Thread thread) {
        bindThread = thread;
    }


    public boolean canSampled() {
        return this.sampling;
    }

    public boolean isRoot() {
        return getTraceId().isRoot();
    }

    private void logSpan(SpanEvent spanEvent) {
        if (isTrace) {
            final Thread th = Thread.currentThread();
            logger.trace("[DefaultTrace] Write {} thread{id={}, name={}}", spanEvent, th.getId(), th.getName());
        }
        storage.store(spanEvent);
    }

    private void logSpan(Span span) {
        if (isTrace) {
            final Thread th = Thread.currentThread();
            logger.trace("[DefaultTrace] Write {} thread{id={}, name={}}", span, th.getId(), th.getName());
        }
        this.storage.store(span);
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    @Override
    public boolean isRootStack() {
        return callStack.empty();
    }

    @Override
    public AsyncTraceId getAsyncTraceId() {
        return getAsyncTraceId(false);
    }

    @Override
    public AsyncTraceId getAsyncTraceId(boolean closeable) {
        // ignored closeable.
        return new DefaultAsyncTraceId(traceId, asyncIdGenerator.nextAsyncId(), spanRecorder.getSpan().getStartTime());
    }

    @Override
    public SpanRecorder getSpanRecorder() {
        return spanRecorder;
    }

    @Override
    public SpanEventRecorder currentSpanEventRecorder() {
        SpanEvent spanEvent = callStack.peek();
        if (spanEvent == null) {
            if (isWarn) {
                PinpointException exception = new PinpointException("call stack is empty");
                logger.warn("[DefaultTrace] Corrupted call stack found.", exception);
            }
            // make dummy.
            spanEvent = new SpanEvent(spanRecorder.getSpan());
        }

        return wrappedSpanEventRecorder(spanEvent);
    }

    @Override
    public int getCallStackFrameId() {
        final SpanEvent spanEvent = callStack.peek();
        if(spanEvent == null) {
            return ROOT_STACKID;
        } else {
            return spanEvent.getStackId();
        }
    }

    @Override
    public TraceScope getScope(String name) {
        return scopePool.get(name);
    }

    @Override
    public TraceScope addScope(String name) {
        return scopePool.add(name);
    }
}