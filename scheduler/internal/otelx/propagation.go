package otelx

import (
	"context"
	"net/http"
	"strings"

	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/trace"
)

type MapCarrier map[string]string

func (c MapCarrier) Get(key string) string {
	if c == nil {
		return ""
	}
	return c[key]
}

func (c MapCarrier) Set(key, value string) {
	if c == nil {
		return
	}
	c[key] = value
}

func (c MapCarrier) Keys() []string {
	keys := make([]string, 0, len(c))
	for key := range c {
		keys = append(keys, key)
	}
	return keys
}

func ExtractMap(ctx context.Context, headers map[string]string) context.Context {
	return otel.GetTextMapPropagator().Extract(ctx, MapCarrier(headers))
}

func InjectMap(ctx context.Context, headers map[string]string) {
	if headers == nil {
		headers = map[string]string{}
	}
	otel.GetTextMapPropagator().Inject(ctx, MapCarrier(headers))
}

func InjectHTTP(ctx context.Context, header http.Header) {
	otel.GetTextMapPropagator().Inject(ctx, propagation.HeaderCarrier(header))
}

func ExtractHTTP(ctx context.Context, header http.Header) context.Context {
	return otel.GetTextMapPropagator().Extract(ctx, propagation.HeaderCarrier(header))
}

func Continue(ctx context.Context, traceparent, tracestate string) context.Context {
	if strings.TrimSpace(traceparent) == "" {
		return ctx
	}
	carrier := MapCarrier{"traceparent": traceparent}
	if strings.TrimSpace(tracestate) != "" {
		carrier["tracestate"] = tracestate
	}
	return ExtractMap(ctx, carrier)
}

func WrapTransport(base http.RoundTripper) http.RoundTripper {
	if base == nil {
		base = http.DefaultTransport
	}
	return otelhttp.NewTransport(base, otelhttp.WithFilter(func(r *http.Request) bool {
		path := r.URL.Path
		if strings.Contains(path, "/heartbeat") || strings.HasSuffix(path, "/renew") {
			return false
		}
		if strings.HasSuffix(path, "/snapshot") {
			return trace.SpanFromContext(r.Context()).SpanContext().IsValid()
		}
		return true
	}))
}
