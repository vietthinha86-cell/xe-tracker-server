package com.xetheodoi.xe_tracker_server;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cổng TCP tiếp nhận trực tiếp các cục camera hành trình / MDVR chuẩn JT/T 808 (tương thích Jimi / Navicom / YIMS).
 */
@Component
public class Jt808TcpServer {

    private static final Logger log = LoggerFactory.getLogger(Jt808TcpServer.class);

    private final TelemetryService telemetryService;
    private final boolean enabled;
    private final int port;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger replyFlowSeq = new AtomicInteger(1);
    private final ConcurrentHashMap<String, Instant> activeTerminals = new ConcurrentHashMap<>();

    private ServerSocket serverSocket;
    private ExecutorService clientPool;
    private Thread acceptThread;

    public Jt808TcpServer(
            TelemetryService telemetryService,
            @Value("${app.jt808.enabled:true}") boolean enabled,
            @Value("${app.jt808.port:6808}") int port
    ) {
        this.telemetryService = telemetryService;
        this.enabled = enabled;
        this.port = port;
    }

    @PostConstruct
    public void start() {
        if (!enabled || port <= 0) {
            return;
        }
        try {
            serverSocket = new ServerSocket(port);
            running.set(true);
            clientPool = Executors.newCachedThreadPool();
            acceptThread = new Thread(this::acceptLoop, "jt808-tcp-acceptor");
            acceptThread.setDaemon(true);
            acceptThread.start();
            log.info("JT808 TCP Gateway listening on port {}", port);
        } catch (IOException e) {
            log.warn("Không thể mở cổng JT808 TCP {} ({}). Cổng JT808 tạm tắt.", port, e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }
        if (clientPool != null) {
            clientPool.shutdownNow();
        }
    }

    private void acceptLoop() {
        while (running.get() && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                client.setSoTimeout(180_000);
                clientPool.submit(() -> handleClient(client));
            } catch (IOException e) {
                if (running.get()) {
                    log.debug("JT808 accept loop error: {}", e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        String lastPhone = null;
        try (socket) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            ByteArrayOutputStream frameBuf = new ByteArrayOutputStream();
            boolean inFrame = false;
            byte[] readBuf = new byte[2048];
            int len;
            while (running.get() && (len = in.read(readBuf)) != -1) {
                for (int i = 0; i < len; i++) {
                    byte b = readBuf[i];
                    if (b == Jt808Codec.DELIMITER) {
                        if (inFrame && frameBuf.size() > 0) {
                            String processedPhone = processFrame(frameBuf.toByteArray(), out);
                            if (processedPhone != null) {
                                lastPhone = processedPhone;
                            }
                            frameBuf.reset();
                        }
                        inFrame = true;
                    } else if (inFrame) {
                        if (frameBuf.size() < 8192) {
                            frameBuf.write(b);
                        } else {
                            frameBuf.reset();
                            inFrame = false;
                        }
                    }
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (lastPhone != null) {
                activeTerminals.remove(lastPhone);
            }
        }
    }

    private String processFrame(byte[] payloadBetween7E, OutputStream out) throws IOException {
        Jt808Codec.DecodedFrame frame = Jt808Codec.parseFrame(payloadBetween7E);
        if (frame == null) {
            return null;
        }
        String phone = frame.terminalPhone();
        activeTerminals.put(phone, Instant.now());
        int seq = replyFlowSeq.getAndIncrement() & 0xFFFF;

        switch (frame.msgId()) {
            case 0x0100 -> {
                byte[] ack = Jt808Codec.buildRegisterAck(frame, seq, 0, "XETK_" + phone);
                out.write(ack);
                out.flush();
            }
            case 0x0102, 0x0002 -> {
                byte[] ack = Jt808Codec.buildGeneralAck(frame, seq, 0);
                out.write(ack);
                out.flush();
            }
            case 0x0200 -> {
                Jt808Codec.LocationReport report = Jt808Codec.decodeLocation0200(frame.body());
                int ackResult = 0;
                if (report != null && (report.latitude() != 0.0 || report.longitude() != 0.0)) {
                    Optional<Vehicle> vehicleOpt = telemetryService.findVehicleByTerminalId(phone);
                    if (vehicleOpt.isPresent()) {
                        Vehicle vehicle = vehicleOpt.get();
                        if (!vehicle.isPlanExpired()) {
                            telemetryService.recordTelemetry(
                                    vehicle,
                                    report.latitude(),
                                    report.longitude(),
                                    report.speedKmh(),
                                    report.bearing(),
                                    report.accOn(),
                                    report.timestamp()
                            );
                        } else {
                            ackResult = 1;
                        }
                    }
                }
                byte[] ack = Jt808Codec.buildGeneralAck(frame, seq, ackResult);
                out.write(ack);
                out.flush();
            }
            default -> {
                byte[] ack = Jt808Codec.buildGeneralAck(frame, seq, 0);
                out.write(ack);
                out.flush();
            }
        }
        return phone;
    }

    public Map<String, Object> getStatusSnapshot() {
        return Map.of(
                "enabled", enabled,
                "listening", running.get(),
                "port", port,
                "activeConnections", activeTerminals.size(),
                "terminals", activeTerminals.keySet()
        );
    }
}
