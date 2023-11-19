package RabbitMQ.WebSocketClient;

import javax.websocket.OnMessage;
import javax.websocket.Session;

import RabbitMQ.WebSocketRequests.WebSocketRequests;

import java.util.*;

@ClientEndpoint
public class WebSocketClient {
    public Map message;
    @OnMessage
    public void onMessage(String sessionId, Session session) {
        WebSocketRequests.sendMessage(sessionId, message);

        session.close();
    }

    public void setMessage(Map newMsg) {
        message = newMsq;
    }
}
