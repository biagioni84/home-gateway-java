package uy.plomo.gateway.matter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-level WebSocket client for python-matter-server.
 *
 *   ws://<host>:5580/ws  — JSON-RPC over WebSocket
 *
 * On connect: sends start_listening, which dumps all commissioned nodes
 * and then streams live attribute_updated / node_added / node_removed events.
 *
 * Hook registry: ConcurrentHashMap<message_id, CompletableFuture<JsonNode>>.
 * Events (no message_id) are forwarded to MatterReportHandler.
 *
 * Mirrors the hook/future pattern of ZWaveInterface and ZigbeeInterface.
 */
@Component
@Slf4j
public class MatterInterface extends TextWebSocketHandler {

    private static final int DEFAULT_TIMEOUT_MS  = 10_000;
    private static final int RECONNECT_DELAY_MS  = 10_000;

    @Value("${matter.enabled:true}")
    private boolean enabled;

    @Value("${matter.server.url:ws://localhost:5580/ws}")
    private String serverUrl;

    private final ObjectMapper objectMapper;

    private volatile WebSocketSession session;
    private volatile boolean          running;

    // message_id → pending CompletableFuture
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    // nodeId → live snapshot (populated on start_listening + events)
    private final ConcurrentHashMap<Long, MatterNode> nodes = new ConcurrentHashMap<>();

    private final AtomicLong messageIdCounter = new AtomicLong(1);

    // Injected post-construction to avoid circular dep (mirrors ZigbeeInterface pattern)
    private MatterReportHandler reportHandler;

    public MatterInterface(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void setReportHandler(MatterReportHandler h) {
        this.reportHandler = h;
        // If start_listening already completed before the handler was wired in, notify now
        if (!nodes.isEmpty()) {
            h.onInitialNodes(nodes.values());
        }
    }

    public boolean isEnabled() { return enabled; }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("Matter disabled (matter.enabled=false)");
            return;
        }
        running = true;
        connect();
        Scheduler.INSTANCE.scheduleWithFixedDelay(
                this::reconnectIfNeeded,
                RECONNECT_DELAY_MS, RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        running = false;
        closeSession();
        log.info("Matter client stopped");
    }

    private void connect() {
        try {
            // python-matter-server sends a large JSON dump on start_listening (all nodes +
            // all attributes). The default Tomcat WebSocket buffer (8 KB) is too small —
            // set 10 MB to avoid CloseStatus 1009 "message too big".
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            container.setDefaultMaxTextMessageBufferSize(10 * 1024 * 1024);
            StandardWebSocketClient client = new StandardWebSocketClient(container);
            client.execute(this, serverUrl).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Matter: connect to {} failed — {}", serverUrl, e.getMessage());
        }
    }

    private void reconnectIfNeeded() {
        if (!running) return;
        if (session == null || !session.isOpen()) {
            log.info("Matter: reconnecting to {}", serverUrl);
            connect();
        }
    }

    private void closeSession() {
        WebSocketSession s = session;
        if (s != null && s.isOpen()) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    // ── WebSocketHandler callbacks ────────────────────────────────────────────

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        this.session = session;
        log.info("Matter: connected");
        // start_listening is fire-and-forget — response is handled in handleResponse()
        doSend(String.valueOf(messageIdCounter.getAndIncrement()), "start_listening", null);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.warn("Matter: connection closed — {}", status);
        this.session = null;
        pending.forEach((id, f) ->
                f.completeExceptionally(new IOException("Matter WS disconnected")));
        pending.clear();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        log.debug("Matter<< {}", payload);
        try {
            MatterMessage msg = objectMapper.readValue(payload, MatterMessage.class);
            if (msg.isResponse()) {
                handleResponse(msg);
            } else if (msg.isEvent()) {
                handleEvent(msg);
            } else {
                // Server info object sent on connect (no message_id, no event)
                log.debug("Matter: server info — {}", payload);
            }
        } catch (Exception e) {
            log.error("Matter: error parsing: {}", payload, e);
        }
    }

    // ── Response handling ─────────────────────────────────────────────────────

    private void handleResponse(MatterMessage msg) {
        JsonNode result = msg.getResult();

        // start_listening (and get_nodes) return the nodes array as the result directly
        if (result != null && result.isArray()) {
            populateNodeCache(result);
        }

        CompletableFuture<JsonNode> future = pending.remove(msg.getMessageId());
        if (future == null) return;

        Integer errorCode = msg.getErrorCode();
        if (errorCode != null && errorCode != 0) {
            future.completeExceptionally(new MatterException(errorCode, result));
        } else {
            future.complete(result);
        }
    }

    private void handleEvent(MatterMessage msg) {
        String  event = msg.getEvent();
        JsonNode data = msg.getData();
        log.debug("Matter event: {} — {}", event, data);

        switch (event) {
            case "node_added", "node_updated" -> {
                if (data != null && data.has("node_id")) {
                    long nodeId   = data.get("node_id").asLong();
                    boolean avail = !data.has("available") || data.get("available").asBoolean(true);
                    nodes.put(nodeId, new MatterNode(nodeId, avail, data));
                }
            }
            case "node_removed" -> {
                if (data != null && data.has("node_id")) {
                    nodes.remove(data.get("node_id").asLong());
                }
            }
        }

        if (reportHandler != null) {
            reportHandler.handleEvent(event, data);
        }
    }

    private void populateNodeCache(JsonNode nodesArray) {
        if (nodesArray == null || !nodesArray.isArray()) return;
        nodes.clear();
        nodesArray.forEach(n -> {
            if (n.has("node_id")) {
                long    id    = n.get("node_id").asLong();
                boolean avail = !n.has("available") || n.get("available").asBoolean(true);
                nodes.put(id, new MatterNode(id, avail, n));
            }
        });
        log.info("Matter: node cache populated — {} nodes", nodes.size());
        if (reportHandler != null) {
            reportHandler.onInitialNodes(nodes.values());
        }
    }

    // ── Command sending ───────────────────────────────────────────────────────

    /**
     * Send a command and return a future that completes with the server result.
     */
    public CompletableFuture<JsonNode> sendCommandWait(String command, Map<String, Object> args) {
        return sendCommandWait(command, args, DEFAULT_TIMEOUT_MS);
    }

    public CompletableFuture<JsonNode> sendCommandWait(
            String command, Map<String, Object> args, int timeoutMs) {

        String msgId = String.valueOf(messageIdCounter.getAndIncrement());
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(msgId, future);

        doSend(msgId, command, args);

        Scheduler.INSTANCE.schedule(() -> {
            if (future.completeExceptionally(
                    new TimeoutException("Matter '" + command + "' timed out after " + timeoutMs + "ms"))) {
                pending.remove(msgId);
                log.warn("Matter: command '{}' timed out ({}ms)", command, timeoutMs);
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);

        return future.whenComplete((r, ex) -> pending.remove(msgId));
    }

    private void doSend(String msgId, String command, Map<String, Object> args) {
        WebSocketSession s = session;
        if (s == null || !s.isOpen()) {
            log.warn("Matter: not connected — dropping: {}", command);
            CompletableFuture<JsonNode> f = pending.remove(msgId);
            if (f != null) f.completeExceptionally(new IOException("Matter WS not connected"));
            return;
        }
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("message_id", msgId);
            node.put("command", command);
            if (args != null && !args.isEmpty()) {
                node.set("args", objectMapper.valueToTree(args));
            }
            String json = objectMapper.writeValueAsString(node);
            log.debug("Matter>> {}", json);
            // WebSocketSession.sendMessage is not thread-safe for concurrent writes
            synchronized (s) {
                s.sendMessage(new TextMessage(json));
            }
        } catch (Exception e) {
            log.error("Matter: send error for '{}'", command, e);
            CompletableFuture<JsonNode> f = pending.remove(msgId);
            if (f != null) f.completeExceptionally(e);
        }
    }

    // ── Node cache access ─────────────────────────────────────────────────────

    public Map<Long, MatterNode> getNodes() { return nodes; }

    public MatterNode getNode(long nodeId) { return nodes.get(nodeId); }

    // ── Scheduler ─────────────────────────────────────────────────────────────

    private static class Scheduler {
        static final ScheduledExecutorService INSTANCE =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "matter-scheduler");
                    t.setDaemon(true);
                    return t;
                });
    }
}
