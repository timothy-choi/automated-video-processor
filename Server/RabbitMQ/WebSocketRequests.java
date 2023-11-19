package RabbitMQ.WebSocketRequests;

import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.util.concurrent.ConcurrentHashMap;

@ServerEndpoint("/NotifyRequest")
public class WebSocketRequests {
    private static final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session session) {
        sessions.put(session.getId(), session);
        session.getBasicRemote().sendText(session.getId());
    }

    private void sendRequest(Session session, Map reqInfo) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            String request = objectMapper.writeValueAsString(reqInfo);
            session.getBasicRemote().sendText(request);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void sendMessage(String clientId, Map reqInfo) {
        sendRequest(sessions.get(clientId), reqInfo);
    }

    @OnClose
    public void onClose(Session session) {
        sessions.remove(session);
    }
}
