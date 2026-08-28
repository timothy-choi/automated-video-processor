package otelx

import (
	"context"

	amqp "github.com/rabbitmq/amqp091-go"
	"go.opentelemetry.io/otel"
)

func ExtractAMQP(ctx context.Context, headers amqp.Table) context.Context {
	return otel.GetTextMapPropagator().Extract(ctx, amqpCarrier(headers))
}

func InjectAMQP(ctx context.Context, headers amqp.Table) {
	if headers == nil {
		return
	}
	otel.GetTextMapPropagator().Inject(ctx, amqpCarrier(headers))
}

type tableCarrier amqp.Table

func amqpCarrier(headers amqp.Table) tableCarrier {
	if headers == nil {
		return tableCarrier{}
	}
	return tableCarrier(headers)
}

func (c tableCarrier) Get(key string) string {
	if c == nil {
		return ""
	}
	value, ok := amqp.Table(c)[key]
	if !ok {
		return ""
	}
	switch typed := value.(type) {
	case string:
		return typed
	case []byte:
		return string(typed)
	default:
		return ""
	}
}

func (c tableCarrier) Set(key, value string) {
	if c == nil {
		return
	}
	amqp.Table(c)[key] = value
}

func (c tableCarrier) Keys() []string {
	keys := make([]string, 0, len(c))
	for key := range amqp.Table(c) {
		keys = append(keys, key)
	}
	return keys
}
