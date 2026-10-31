package com.clau.resiliencereactiveexample.observability;

import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;

public class TraceContextThreadLocalAccessor implements ThreadLocalAccessor<TraceContext> {

    public static final String KEY = TraceContext.class.getName();

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
        TraceContext.create(null, null, null, null, null, null).asMdc().keySet().forEach(MDC::remove);
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
