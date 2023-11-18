package RabbitMQ.Producer;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

public class Producer {
    public static void sendMessage(String routingKey, String msg) {
        ConnectionFactory factory = new ConnectionFactory();

        factory.setHost("localhost");

        bool err = false;

        try (Connection conn = factory.newConnection(); Channel channel = conn.createChannel()) {
            channel.exchangeDeclare("processing_routes", "direct", true);

            channel.queueDeclare("partition_processing_queue", true, false, false, null);
            channel.queueDeclare("video_processing_queue", true, false, false, null);
            channel.queueDeclare("youtube_upload_queue", true, false, false, null);

            channel.queueBind("partition_processing_queue", "processing_routes", "partition");
            channel.queueBind("video_processing_queue", "processing_routes", "video");
            channel.queueBind("youtube_upload_queue", "processing_routes", "youtube");

            channel.basicPublish("processing_routes", routingKey, null, msg.getBytes());
        } catch (Exception e) {
            err = true;
        }

        if (err) {
            throw new Exception("Couldn't save request");
        }
    }
}