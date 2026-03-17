package org.example.chatclient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Multithreaded WebSocket load-test client for Assignment 2.
 *
 * Race-condition fix over Assignment 1 version:
 *   - After sending the LEAVE frame, the worker sleeps 150 ms to let the TCP
 *     send buffer flush before initiating the close handshake.
 *   - The finally block calls closeBlocking() instead of close(), so the
 *     thread blocks until the full WebSocket close handshake completes.
 *     This guarantees every frame queued by client.send() has been delivered
 *     to (and acknowledged by) the server before the connection is torn down.
 *
 * Phases:
 *  - Warmup : 32 threads, each sends 1 000 messages then terminates.
 *  - Main   : 64 threads share the remaining ~468 000 messages.
 * Total: 500 000 messages across 20 rooms.
 */
public class LoadTestClient {

    // --- Assignment configuration ---
    private static final long TOTAL_MESSAGES              = 500_000L;
    private static final int  WARMUP_THREADS              = 32;
    private static final int  WARMUP_MESSAGES_PER_THREAD  = 1_000;
    private static final int  MAIN_THREADS                = 64;

    // Message distribution
    private static final int    MAX_USER_ID      = 100_000;
    private static final int    MIN_USERNAME_LEN = 3;
    private static final int    MAX_USERNAME_LEN = 20;
    private static final int    ROOM_COUNT       = 20;
    private static final double TEXT_PROB        = 0.90;
    private static final double JOIN_PROB        = 0.05;

    // Shared objects
    private static final ObjectMapper          OBJECT_MAPPER = new ObjectMapper();
    private static final BlockingQueue<String> MESSAGE_QUEUE = new LinkedBlockingQueue<>();

    // Counters
    private static final AtomicLong    SUCCESS_COUNT      = new AtomicLong();
    private static final AtomicLong    FAILURE_COUNT      = new AtomicLong();
    private static final AtomicLong    TOTAL_SENT         = new AtomicLong();
    private static final AtomicInteger TOTAL_CONNECTIONS  = new AtomicInteger();
    private static final AtomicInteger TOTAL_RECONNECTS   = new AtomicInteger();

    // Simple pool of 50 predefined message bodies
    private static final String[] MESSAGE_POOL = new String[50];
    static {
        for (int i = 0; i < MESSAGE_POOL.length; i++) {
            MESSAGE_POOL[i] = "Sample message " + (i + 1) + " - " + UUID.randomUUID();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java -jar client.jar <wsBaseUrl> [totalMessages]");
            System.err.println("Example (500K): java -jar client.jar ws://my-alb-xxxx.elb.amazonaws.com/chat");
            System.err.println("Example (1M):   java -jar client.jar ws://my-alb-xxxx.elb.amazonaws.com/chat 1000000");
            System.exit(1);
        }

        final String wsBaseUrl = args[0];
        if (!wsBaseUrl.startsWith("ws://") && !wsBaseUrl.startsWith("wss://")) {
            System.err.println("WebSocket base URL must start with ws:// or wss://");
            System.exit(1);
        }

        // Optional second argument overrides the hardcoded TOTAL_MESSAGES
        final long totalMessages = (args.length >= 2) ? Long.parseLong(args[1]) : TOTAL_MESSAGES;

        System.out.println("Starting load test against: " + wsBaseUrl);
        System.out.println("Total messages: " + totalMessages);

        // Single message-generator thread fills the shared queue
        Thread generatorThread = new Thread(new MessageGenerator(totalMessages), "message-generator");
        generatorThread.start();

        // --- Warmup phase ---
        long warmupStart = System.nanoTime();
        Thread[] warmupThreads = new Thread[WARMUP_THREADS];
        for (int i = 0; i < WARMUP_THREADS; i++) {
            warmupThreads[i] = new Thread(
                    new SenderWorker(wsBaseUrl, WARMUP_MESSAGES_PER_THREAD, true, totalMessages, i),
                    "warmup-sender-" + i);
            warmupThreads[i].start();
        }
        for (Thread t : warmupThreads) t.join();
        long warmupEnd  = System.nanoTime();
        long warmupSent = WARMUP_THREADS * (long) WARMUP_MESSAGES_PER_THREAD;

        System.out.println("Warmup phase complete.");
        System.out.printf("Warmup messages sent:      %d%n", warmupSent);
        System.out.printf("Warmup duration (s):       %.3f%n",
                (warmupEnd - warmupStart) / 1_000_000_000.0);
        System.out.printf("Warmup throughput (msg/s): %.2f%n",
                warmupSent / ((warmupEnd - warmupStart) / 1_000_000_000.0));

        // --- Main phase ---
        long mainStart        = System.nanoTime();
        long remainingMessages = Math.max(0, totalMessages - warmupSent);

        Thread[] mainThreads = new Thread[MAIN_THREADS];
        for (int i = 0; i < MAIN_THREADS; i++) {
            mainThreads[i] = new Thread(
                    new SenderWorker(wsBaseUrl, remainingMessages, false, totalMessages, i),
                    "main-sender-" + i);
            mainThreads[i].start();
        }
        for (Thread t : mainThreads) t.join();
        long mainEnd = System.nanoTime();

        generatorThread.join();

        long   totalSuccess    = SUCCESS_COUNT.get();
        long   totalFailure    = FAILURE_COUNT.get();
        double runTimeSeconds  = (mainEnd - warmupStart) / 1_000_000_000.0;
        double throughput      = totalSuccess / runTimeSeconds;

        System.out.println();
        System.out.println("==== Test Summary ====");
        System.out.println("Successful messages:       " + totalSuccess);
        System.out.println("Failed messages:           " + totalFailure);
        System.out.printf ("Total runtime (s):         %.3f%n", runTimeSeconds);
        System.out.printf ("Overall throughput (msg/s): %.2f%n", throughput);
        System.out.println("Total connections opened:  " + TOTAL_CONNECTIONS.get());
        System.out.println("Total reconnects:          " + TOTAL_RECONNECTS.get());
    }

    // -------------------------------------------------------------------------
    // MessageGenerator — single thread that fills MESSAGE_QUEUE
    // -------------------------------------------------------------------------
    private static class MessageGenerator implements Runnable {
        private final long    totalToGenerate;
        private final Random  random    = new Random();
        private final DateTimeFormatter formatter =
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

        MessageGenerator(long totalToGenerate) {
            this.totalToGenerate = totalToGenerate;
        }

        @Override
        public void run() {
            for (long i = 0; i < totalToGenerate; i++) {
                try {
                    MESSAGE_QUEUE.put(generateMessageJson());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (JsonProcessingException e) {
                    System.err.println("Failed to generate JSON: " + e.getMessage());
                }
            }
        }

        private String generateMessageJson() throws JsonProcessingException {
            int    userId   = 1 + random.nextInt(MAX_USER_ID);
            String username = "user" + userId;
            if (username.length() < MIN_USERNAME_LEN) username = String.format("user%02d", userId);
            if (username.length() > MAX_USERNAME_LEN) username = username.substring(0, MAX_USERNAME_LEN);

            GeneratedMessage msg = new GeneratedMessage();
            msg.userId      = String.valueOf(userId);
            msg.username    = username;
            msg.message     = MESSAGE_POOL[random.nextInt(MESSAGE_POOL.length)];
            msg.timestamp   = formatter.format(Instant.now());
            msg.messageType = pickMessageType(random.nextDouble());
            msg.roomId      = 1 + random.nextInt(ROOM_COUNT);
            return OBJECT_MAPPER.writeValueAsString(msg);
        }

        private String pickMessageType(double p) {
            if (p < TEXT_PROB)              return "TEXT";
            if (p < TEXT_PROB + JOIN_PROB)  return "JOIN";
            return "LEAVE";
        }
    }

    // -------------------------------------------------------------------------
    // GeneratedMessage — JSON DTO
    // -------------------------------------------------------------------------
    private static class GeneratedMessage {
        public String userId;
        public String username;
        public String message;
        public String timestamp;
        public String messageType;
        public int    roomId;
    }

    // -------------------------------------------------------------------------
    // SenderWorker — one WebSocket connection per thread
    //
    // Race-condition fix:
    //   1. After LEAVE is sent, sleep 150 ms so the OS TCP send buffer can
    //      flush the frame to the server before we start the close handshake.
    //   2. finally block uses closeBlocking() which blocks until the server
    //      sends its CLOSE frame back, guaranteeing all previous frames were
    //      received and processed by the server.
    // -------------------------------------------------------------------------
    private static class SenderWorker implements Runnable {
        private final String  wsBaseUrl;
        private final long    phaseMessagesTarget;
        private final boolean warmupPhase;
        private final long    totalMessages;

        private static final int  MAX_RETRIES           = 5;
        private static final long INITIAL_BACKOFF_MILLIS = 50;

        // How long to wait after the last send before closing, to let the TCP
        // send buffer drain. 150 ms is well above any realistic RTT to the ALB.
        private static final long PRE_CLOSE_DRAIN_MILLIS = 150;

        private final int threadIndex;

        SenderWorker(String wsBaseUrl, long phaseMessagesTarget, boolean warmupPhase, long totalMessages, int threadIndex) {
            this.wsBaseUrl           = wsBaseUrl;
            this.phaseMessagesTarget = phaseMessagesTarget;
            this.warmupPhase         = warmupPhase;
            this.totalMessages       = totalMessages;
            this.threadIndex         = threadIndex;
        }

        @Override
        public void run() {
            long            localSent = 0;
            WebSocketClient client    = null;
            Random          random    = new Random();
            DateTimeFormatter formatter =
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

            try {
                // Each worker connects to a different room (round-robin across 20 rooms)
                // so traffic is distributed across all room.1–room.20 queues.
                int roomNum = (threadIndex % ROOM_COUNT) + 1;
                String roomUrl = wsBaseUrl.endsWith("/") ? wsBaseUrl + roomNum : wsBaseUrl + "/" + roomNum;
                client = createClient(roomUrl);
                connectBlocking(client);

                // 1) JOIN
                String joinJson = buildControlMessageJson("JOIN", random, formatter);
                if (!sendWithRetry(client, joinJson)) {
                    FAILURE_COUNT.incrementAndGet();
                    return;
                }

                // 2) TEXT messages from the shared queue
                while (true) {
                    if (warmupPhase) {
                        if (localSent >= phaseMessagesTarget) break;
                    } else {
                        if (TOTAL_SENT.get() >= totalMessages) break;
                    }

                    String json = MESSAGE_QUEUE.poll(500, TimeUnit.MILLISECONDS);
                    if (json == null) continue;  // queue not ready yet

                    if (sendWithRetry(client, json)) {
                        SUCCESS_COUNT.incrementAndGet();
                        TOTAL_SENT.incrementAndGet();
                        localSent++;
                    } else {
                        FAILURE_COUNT.incrementAndGet();
                    }
                }

                // 3) LEAVE
                String leaveJson = buildControlMessageJson("LEAVE", random, formatter);
                if (!sendWithRetry(client, leaveJson)) {
                    FAILURE_COUNT.incrementAndGet();
                }

                // FIX: give the TCP send buffer time to flush the LEAVE frame
                // before we initiate the WebSocket close handshake.
                Thread.sleep(PRE_CLOSE_DRAIN_MILLIS);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (client != null && client.isOpen()) {
                    try {
                        // FIX: closeBlocking() waits for the server's CLOSE frame,
                        // guaranteeing all previously sent frames have been received.
                        client.closeBlocking();
                    } catch (Exception ignore) {
                    }
                }
            }
        }

        private WebSocketClient createClient(String url) {
            try {
                return new WebSocketClient(new URI(url)) {
                    @Override public void onOpen(ServerHandshake h)              { /* no-op */ }
                    @Override public void onMessage(String message)               { /* no-op */ }
                    @Override public void onClose(int code, String r, boolean rm) { /* no-op */ }
                    @Override public void onError(Exception ex) {
                        System.err.println("WebSocket error: " + ex.getMessage());
                    }
                };
            } catch (URISyntaxException e) {
                throw new RuntimeException("Invalid WebSocket URL: " + url, e);
            }
        }

        private void connectBlocking(WebSocketClient client) {
            try {
                TOTAL_CONNECTIONS.incrementAndGet();
                client.connectBlocking();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private boolean sendWithRetry(WebSocketClient client, String json) {
            long backoff = INITIAL_BACKOFF_MILLIS;
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    if (!client.isOpen()) {
                        TOTAL_RECONNECTS.incrementAndGet();
                        client.reconnectBlocking();
                    }
                    client.send(json);
                    return true;
                } catch (Exception e) {
                    if (attempt == MAX_RETRIES) {
                        System.err.println("Failed after " + MAX_RETRIES + " retries: " + e.getMessage());
                        return false;
                    }
                    try { Thread.sleep(backoff); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                    backoff *= 2;
                }
            }
            return false;
        }

        private String buildControlMessageJson(String type, Random random,
                                               DateTimeFormatter formatter) {
            try {
                int    userId   = 1 + random.nextInt(MAX_USER_ID);
                String username = "user" + userId;
                if (username.length() < MIN_USERNAME_LEN) username = String.format("user%02d", userId);
                if (username.length() > MAX_USERNAME_LEN) username = username.substring(0, MAX_USERNAME_LEN);

                GeneratedMessage msg = new GeneratedMessage();
                msg.userId      = String.valueOf(userId);
                msg.username    = username;
                msg.message     = type;
                msg.timestamp   = formatter.format(Instant.now());
                msg.messageType = type;
                msg.roomId      = 1 + random.nextInt(ROOM_COUNT);
                return OBJECT_MAPPER.writeValueAsString(msg);
            } catch (Exception e) {
                throw new RuntimeException("Failed to build control message: " + type, e);
            }
        }
    }
}
