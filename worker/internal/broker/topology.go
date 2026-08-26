package broker

const (
	Exchange             = "media.operations"
	Queue                = "media.operations.execute"
	RoutingKey           = "operation.execute"
	DeadLetterExchange   = "media.operations.dlx"
	DeadLetterQueue      = "media.operations.execute.dlq"
	DeadLetterRoutingKey = "operation.execute.dead"
)

func WorkerQueue(workerID string) string {
	return "media.worker." + workerID
}

func WorkerRoutingKey(workerID string) string {
	return "worker." + workerID
}
