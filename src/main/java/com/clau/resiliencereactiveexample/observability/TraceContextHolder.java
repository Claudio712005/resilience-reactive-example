package com.clau.resiliencereactiveexample.observability;

import reactor.core.publisher.Mono;
import reactor.util.context.Context;

public final class TraceContextHolder {

    private TraceContextHolder() {
    }

    public static Context write(Context context, TraceContext traceContext) {
        return context.put(TraceContextThreadLocalAccessor.KEY, traceContext);
    }

    public static Mono<TraceContext> current() {
        return Mono.deferContextual(contextView -> Mono.just(
                contextView.getOrDefault(TraceContextThreadLocalAccessor.KEY,
                        TraceContext.create(null, null, null, null, "internal", "internal"))));
    }
}
