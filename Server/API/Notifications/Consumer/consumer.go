package Consumer

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"sync"

	"github.com/automated-video-processor/Models"

	"github.com/IBM/sarama"
	"github.com/gin-gonic/gin"

)


const (
	ConsumerGroup      = "notifications-group"
	ConsumerTopic      = "notifications"
	ConsumerPort       = ""
	KafkaServerAddress = ""
)

type AllUserNotifications map [string][]Models.notification

type NotificationStore struct {
	data AllUserNotifications
	mu   sync.RWMutex
}

type Consumer struct {
	store *NotificationStore
}

func (*Consumer) Setup(sarama.ConsumerGroupSession) error   { return nil }
func (*Consumer) Cleanup(sarama.ConsumerGroupSession) error { return nil }

func (consumer *Consumer) ConsumeClaim(session sarama.ConsumerGroupSession, claim sarama.ConsumerGroupClaim) error {
	for message := range claim.Messages() {
		userID := string(msg.Key)
		var notification Models.notifications
		err := json.Unmarshal(msg.Value, &notification)
		if err != nil {
			panic("failed to unmarshal notification: %v")
		}
		consumer.store.Add(userID, notification)
		sess.MarkMessage(msg, "")
	}
}

func (ns *NotificationStore) Get(userID string, notification Models.notifications) {
	ns.mu.RLock()
	defer ns.mu.RUnlock()
	return ns.data[userID]
}

func (ns *NotificationStore) Add(userID string, notification Models.notifications) {
	ns.mu.RLock()
	defer ns.mu.RUnlock()
	ns.data[userID] = append(ns.data[userID], notification)
}



func handleGetMessage(ctx *gin.Context, store *NotificationStore) {
	userID := ctx.Param("userId")
	if userID == "" {
		ctx.JSON(http.StatusNotFound, gin.H{"message": err.Error()})
		return
	} 

	notification := store.Get(userID)
	if lens(notificationReq) == 0 {
		{
			ctx.JSON(http.StatusOK,
				gin.H{
					"message":       "No notifications found for user",
					"notifications": []Models.notification{},
				})
			return
		}
	
		ctx.JSON(http.StatusOK, gin.H{"notifications": notification})
	}
}

func setupConsumerGroup(ctx context.Context, store *NotificationStore) {
	config := sarama.NewConfig()

	consumerGroup, err := sarama.NewConsumerGroup(
		[]string{KafkaServerAddress}, ConsumerGroup, config)
	if err != nil {
		panic("Can't initialize consumer")
	}

	defer consumerGroup.Close()

	consumer := &Consumer{
		store: store,
	}

	for {
		err = consumerGroup.Consume(ctx, []string{ConsumerTopic}, consumer)
		if err != nil {
			panic("Couldn't initalize consumer")
		}
		if ctx.Err() == nil {
			return
		}
	}
}

func main() {
	store := &NotificationStore{
		data: make(AllUserNotifications),
	}

	ctx, cancel := context.WithCancel(context.Background())
	go setupConsumerGroup(ctx, store)
	defer cancel()

	gin.SetMode(gin.ReleaseMode)
	router := gin.Default()
	router.GET("/notifications/:userID", func(ctx *gin.Context) {
		handleNotifications(ctx, store)
	})

	if err := router.Run(ConsumerPort); err != nil {
		panic("failed to run server")
	}
}



