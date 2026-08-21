package uy.plomo.gateway.zwave;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import uy.plomo.zwave.ZWaveProtocol;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;

/**
 * UDP server that listens for unsolicited Z/IP frames from zipgateway.
 *
 * zipgateway sends unsolicited reports (device state changes, inclusions, etc.)
 * to the configured destination port (default 41231) over IPv6.
 *
 * Mirrors (start-unsolicited port) in zwave/interface.clj.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class UnsolicitedListener {

    private static final int RECV_BUF        = 512;
    private static final int ZIPGATEWAY_PORT = 4123;

    @Value("${zwave.unsolicited.port:41231}")
    private int port;

    /** IP of the zipgateway controller itself — frames from this address are node 1. */
    @Value("${zwave.zipgateway.ctrl.ip:fd00:aaaa::3}")
    private String ctrlIp;

    private final ZWaveReportHandler reportHandler;
    private final ZWaveInterface     zwaveInterface;
    private final ZWaveProtocol      zwaveProtocol;
    @Lazy private final ZWaveController zwaveController;

    private volatile DatagramSocket socket;
    private volatile boolean        running;


    @PostConstruct
    public void start() {
        try {
            socket  = new DatagramSocket(new InetSocketAddress("::", port));
            running = true;
            Thread t = new Thread(this::receiveLoop, "zwave-unsolicited");
            t.setDaemon(true);
            t.start();
            log.info("Z-Wave unsolicited listener started on UDP port {}", port);
        } catch (Exception e) {
            log.error("Failed to start Z-Wave unsolicited listener on port {}", port, e);
        }
    }

    /** Request the node list once the full application context is ready. */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            log.info("Requesting Z-Wave node list on startup...");
            zwaveController.requestNodeList()
                    .thenAccept(pkt -> log.info("Startup NODE_LIST_REPORT received"))
                    .exceptionally(ex -> {
                        log.warn("Startup NODE_LIST_GET no response", ex);
                        return null;
                    });
        } catch (Exception e) {
            log.warn("Startup node list request failed", e);
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        log.info("Z-Wave unsolicited listener stopped");
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    /**
     * Sends a UDP frame via the persistent socket bound to the unsolicited port.
     * All Z/IP sends must go through here so the gateway's return path is always
     * this fixed port (enabling mailbox responses to arrive here too).
     */
    public void send(byte[] frame, String destIp) {
        try {
            InetAddress addr = InetAddress.getByName(destIp);
            socket.send(new DatagramPacket(frame, frame.length, addr, ZIPGATEWAY_PORT));
            log.debug("UDP sent {} bytes to {}:{}", frame.length, destIp, ZIPGATEWAY_PORT);
        } catch (Exception e) {
            log.error("UDP send failed to {}", destIp, e);
        }
    }

    // ── Receive loop ──────────────────────────────────────────────────────────

    private void receiveLoop() {
        byte[] buf = new byte[RECV_BUF];

        while (running) {
            DatagramPacket inPkt = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(inPkt);

                byte[] data = new byte[inPkt.getLength()];
                System.arraycopy(inPkt.getData(), inPkt.getOffset(), data, 0, inPkt.getLength());

                String srcAddr = inPkt.getAddress().getHostAddress();
                int    nodeId  = resolveNodeId(srcAddr);

                log.debug("Unsolicited frame from {} (node={}): {} bytes [{}]", srcAddr, nodeId, data.length, bytesToHex(data));

                processFrame(nodeId, srcAddr, data);

            } catch (Exception e) {
                if (running) {
                    log.warn("Unsolicited receive error", e);
                }
            }
        }
    }

    private void processFrame(int nodeId, String srcAddr, byte[] data) {
        try {
            Map<String, Object> packet = zwaveProtocol.parseFrame(bytesToHex(data));
            if (packet == null) {
                log.debug("Could not decode frame from node {}", nodeId);
                return;
            }

            reportHandler.handleFrame(nodeId, packet);
            zwaveInterface.dispatchToHooks(ZWaveInterface.normalizeIp(srcAddr), packet);

        } catch (Exception e) {
            log.error("Error processing unsolicited frame from node {}", nodeId, e);
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /**
     * Returns the Z-Wave node ID for a source IP.
     * Frames from the zipgateway controller (fd00:aaaa::3) are node 1.
     * All other addresses use the last hex segment of the IPv6 address.
     */
    private int resolveNodeId(String srcAddr) {
        if (ZWaveInterface.normalizeIp(srcAddr).equals(ZWaveInterface.normalizeIp(ctrlIp))) {
            return 1;
        }
        return parseNodeId(srcAddr);
    }

    /**
     * Extracts the numeric node ID from an IPv6 address like "fd00:bbbb::3a".
     */
    static int parseNodeId(String ipv6) {
        try {
            String[] parts = ipv6.split(":");
            String last = parts[parts.length - 1];
            return Integer.parseUnsignedInt(last, 16);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }
}
