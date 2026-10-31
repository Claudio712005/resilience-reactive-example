package com.clau.resiliencereactiveexample.observability;

import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;

import java.util.List;

public class TraceContextThreadLocalAccessor implements ThreadLocalAccessor<TraceContext> {

    public static final String KEY = TraceContext.class.getName();

    private static final List<String> MDC_KEYS = List.of(
            TraceContext.MDC_REQUEST_ID,
            TraceContext.MDC_CORRELATION_ID,
            TraceContext.MDC_TRANSACTION_ID,
            TraceContext.MDC_SESSION_ID,
            TraceContext.MDC_ORIGIN_SERVICE,
            TraceContext.MDC_OPERATION);

    private static final ThreadLocal<TraceContext> HOLDER = new ThreadLocal<>();

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public TraceContext getValue() {
        return HOLDER.get();
    }

    @Override
    public void setValue(TraceContext value) {
        HOLDER.set(value);
        value.asMdc().forEach(MDC::put);
    }

    @Override
    public void setValue() {
        HOLDER.remove();
        MDC_KEYS.forEach(MDC::remove);
    }

    @Override
    public void restore(TraceContext previousValue) {
        setValue(previousValue);
    }

    @Override
    public void restore() {
        setValue();
    }
}
