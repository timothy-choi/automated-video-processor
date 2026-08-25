package broker

const (
	Exchange             = "media.operations"
	Queue                = "media.operations.execute"
	RoutingKey           = "operation.execute"
	DeadLetterExchange   = "media.operations.dlx"
	DeadLetterQueue      = "media.operations.execute.dlq"
	DeadLetterRoutingKey = "operation.execute.dead"
)
