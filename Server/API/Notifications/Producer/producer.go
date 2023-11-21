package Producer

import (
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"strconv"

	"github.com/google/uuid"

	"github.com/automated-video-processor/Models/notifications"

	"github.com/IBM/sarama"
	"github.com/gin-gonic/gin"
)

const (
	ProducerPort       = ""
	KafkaServerAddress = ""
	KafkaTopic         = "notifications"
)

type RequestBody struct {
	UserId string `json:"userId"`
	Message string `json:"message"`
	Url string `json:"url"`
}

func sendMessageHelper(producer sarama.SyncProducer, RequestBody notificationData) error {
	notification := notifications.Notification({
		ID: uuid.New().String()
		UserID: notificationData.userId
		Message: notificationData.message
		Url: notificationData.url
	})

	notificationReq, err := json.marshal(notification)
	if err {
		panic("Couldn't process notification")
	}

	notify := &samara.ProducerMessage{
		Topic: KafkaTopic,
		Key: sarama.StringEncoder(notificationReq.ID),
		Value: sarama.StringEncoder(notificationReq)
	}

	_, _, err = producer.SendMessage(notify)

	return err
}

func sendMessage(producer sarama.SyncProducer) gin.HandlerFunc {
	return func(c *gin.Context) {
		var RequestBody NotificationRequestBody;

		if err := c.BindJSON(&NotificationRequestBody); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		res := sendMessageHelper(producer, NotificationRequestBody)

		if res != nil {
			c.JSON(http.StatusInternalServerError, gin.H{
				"message": err.Error(),
			})
			return
		}

		ctx.JSON(http.StatusOK, gin.H{
			"message": "Notification sent successfully!",
		})
	}
}

func main() {
	config := sarama.NewConfig()
	config.Producer.Return.Successes = true
	producer, err := sarama.NewSyncProducer([]string{KafkaServerAddress}, config)
	if err != nil {
		log.Fatalf("failed to initialize producer: %v", err)
	}
	defer producer.Close()

	gin.SetMode(gin.ReleaseMode)
	router := gin.Default()

	router.POST("/send/:id", sendMessage(producer))

	if err := router.Run(ProducerPort); err != nil {
		panic("Error: Couldn't process notification request")
	}
}


