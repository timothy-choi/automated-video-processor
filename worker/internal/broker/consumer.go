package broker

import (
	"context"
	"fmt"
	"log"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"

	"github.com/timothy-choi/automated-video-processor/worker/internal/consumer"
)

type Config struct {
	URL                 string
	WorkerID            string
	Prefetch            int
	SupportedOperations []string
}

type Consumer struct {
	cfg  Config
	ctrl consumer.Control
	exec consumer.Executor
}

func New(cfg Config, ctrl consumer.Control, exec consumer.Executor) *Consumer {
	if cfg.Prefetch <= 0 {
		cfg.Prefetch = 1
	}
	if cfg.WorkerID == "" {
		cfg.WorkerID = "worker"
	}
	return &Consumer{cfg: cfg, ctrl: ctrl, exec: exec}
}

func (c *Consumer) Run(ctx context.Context) error {
	backoff := time.Second
	for {
		if err := ctx.Err(); err != nil {
			log.Printf("worker=%s event=shutdown", c.cfg.WorkerID)
			return err
		}
		err := c.consumeSession(ctx)
		if ctx.Err() != nil {
			return ctx.Err()
		}
		log.Printf("worker=%s event=rabbitmq_disconnected err=%v retry_in=%s", c.cfg.WorkerID, err, backoff)
		timer := time.NewTimer(backoff)
		select {
		case <-ctx.Done():
			timer.Stop()
			return ctx.Err()
		case <-timer.C:
		}
		backoff *= 2
		if backoff > 15*time.Second {
			backoff = 15 * time.Second
		}
	}
}

func (c *Consumer) consumeSession(ctx context.Context) error {
	conn, err := amqp.DialConfig(c.cfg.URL, amqp.Config{
		Heartbeat: 10 * time.Second,
		Locale:    "en_US",
	})
	if err != nil {
		return fmt.Errorf("dial rabbitmq: %w", err)
	}
	defer conn.Close()

	ch, err := conn.Channel()
	if err != nil {
		return fmt.Errorf("open channel: %w", err)
	}
	defer ch.Close()

	if err := DeclareTopology(ch); err != nil {
		return err
	}
	if err := ch.Qos(c.cfg.Prefetch, 0, false); err != nil {
		return fmt.Errorf("qos: %w", err)
	}

	msgs, err := ch.Consume(Queue, c.cfg.WorkerID, false, false, false, false, nil)
	if err != nil {
		return fmt.Errorf("consume: %w", err)
	}
	log.Printf("worker=%s event=consuming queue=%s prefetch=%d", c.cfg.WorkerID, Queue, c.cfg.Prefetch)

	connClosed := conn.NotifyClose(make(chan *amqp.Error, 1))
	chClosed := ch.NotifyClose(make(chan *amqp.Error, 1))
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case amqpErr := <-connClosed:
			if amqpErr == nil {
				return fmt.Errorf("rabbitmq connection closed")
			}
			return amqpErr
		case amqpErr := <-chClosed:
			if amqpErr == nil {
				return fmt.Errorf("rabbitmq channel closed")
			}
			return amqpErr
		case delivery, ok := <-msgs:
			if !ok {
				return fmt.Errorf("rabbitmq deliveries closed")
			}
			c.handleDelivery(ctx, &delivery)
		}
	}
}

func (c *Consumer) handleDelivery(ctx context.Context, delivery *amqp.Delivery) {
	workCtx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	decision := consumer.HandleWithCapabilities(workCtx, c.cfg.WorkerID, c.cfg.SupportedOperations, delivery.Body, c.ctrl, c.exec)
	switch decision {
	case consumer.Ack:
		if err := delivery.Ack(false); err != nil {
			log.Printf("worker=%s event=ack_failed err=%v", c.cfg.WorkerID, err)
		} else {
			log.Printf("worker=%s event=ack", c.cfg.WorkerID)
		}
	case consumer.NackRequeue:
		select {
		case <-ctx.Done():
		case <-time.After(2 * time.Second):
		}
		if err := delivery.Nack(false, true); err != nil {
			log.Printf("worker=%s event=nack_requeue_failed err=%v", c.cfg.WorkerID, err)
		} else {
			log.Printf("worker=%s event=nack_requeue", c.cfg.WorkerID)
		}
	default:
		if err := delivery.Nack(false, false); err != nil {
			log.Printf("worker=%s event=nack_drop_failed err=%v", c.cfg.WorkerID, err)
		} else {
			log.Printf("worker=%s event=nack_drop", c.cfg.WorkerID)
		}
	}
}

func DeclareTopology(ch *amqp.Channel) error {
	if err := ch.ExchangeDeclare(Exchange, "direct", true, false, false, false, nil); err != nil {
		return fmt.Errorf("declare exchange: %w", err)
	}
	if err := ch.ExchangeDeclare(DeadLetterExchange, "direct", true, false, false, false, nil); err != nil {
		return fmt.Errorf("declare dlx: %w", err)
	}
	_, err := ch.QueueDeclare(Queue, true, false, false, false, amqp.Table{
		"x-dead-letter-exchange":    DeadLetterExchange,
		"x-dead-letter-routing-key": DeadLetterRoutingKey,
	})
	if err != nil {
		return fmt.Errorf("declare queue: %w", err)
	}
	_, err = ch.QueueDeclare(DeadLetterQueue, true, false, false, false, nil)
	if err != nil {
		return fmt.Errorf("declare dlq: %w", err)
	}
	if err := ch.QueueBind(Queue, RoutingKey, Exchange, false, nil); err != nil {
		return fmt.Errorf("bind queue: %w", err)
	}
	if err := ch.QueueBind(DeadLetterQueue, DeadLetterRoutingKey, DeadLetterExchange, false, nil); err != nil {
		return fmt.Errorf("bind dlq: %w", err)
	}
	return nil
}
