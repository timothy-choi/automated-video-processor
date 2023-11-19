package RabbitMQ.Consumer;

import com.rabbitmq.client.*;

import RabbitMQ.WebSocketClient.WebSocketClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.websocket.WebSocketContainer;
import javax.websocket.ContainerProvider;
import java.net.URI;


public class Consumer {
    public static void receiveMessage() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");

        String routingKey = "";

        ObjectMapper objectMapper = new ObjectMapper();

        bool err = false;

        Map reqInfo = new HashMap();

        try (Connection connection = factory.newConnection();
            Channel channel = connection.createChannel()) {
                Consumer msgProcess = new DefaultConsumer(channel) {
                public void handleDelivery(String consumerTag, Envelope envelope, Properties properties, byte[] body) throws IOException {
                    routingKey = envelope.getRoutingKey();
                    String request = new String(body, "UTF-8");
                    reqInfo = objectMapper.readValue(request, Map.class);
                    reqInfo.set("routingKey", routingKey);
                }
            };

            channel.basicConsume("partition_processing_queue", true, msgProcess);
            channel.basicConsume("video_processing_queue", true, msgProcess);
            channel.basicConsume("youtube_upload_queue", true, msgProcess);
        } catch (Exception e) {
            err = true;
        }

        if (err) {
            throw new Exception("Couldn't get new request");
        }

        err = false;

        URI serverUri = new URI("");

        try {
            WebSocketClient.WebSocketClient().setMessage(reqInfo);

            WebSocketContainer container = ContainerProvider.getWebSocketContainer();

            container.connectToServer(WebSocketRequests.class, serverUri);
        } catch (Exception e) {
            err = true;
        }

        if (err) {
            throw new Exception("Couldn't get new request");
        }
    }
}
