package uy.plomo.gateway.camera;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ONVIF camera discovery via WS-Discovery (SOAP over UDP multicast).
 *
 * Sends a Probe message to the well-known WS-Discovery multicast group
 * (239.255.255.250:3702), collects responses for a fixed window, then
 * extracts the XAddrs (device management service URLs) from each reply.
 *
 * The returned list of {@link DiscoveredCamera} objects contains enough
 * information to build an onvif:// source URL for go2rtc:
 *   onvif://user:pass@192.168.1.50/
 *
 * go2rtc handles profile negotiation and RTSP URI resolution itself when
 * the onvif:// scheme is used, so we do not need to implement the full
 * GetProfiles SOAP call here.
 */
@Component
@Slf4j
public class OnvifDiscoveryService {

    private static final String MULTICAST_ADDR = "239.255.255.250";
    private static final int    MULTICAST_PORT = 3702;
    private static final int    BUFFER_SIZE    = 4096;
    private static final int    COLLECT_MS     = 2000; // wait 2 s for responses

    // WS-Discovery Probe SOAP envelope — types targets ONVIF NetworkVideoTransmitter
    private static final String PROBE_TEMPLATE =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        "<Envelope xmlns=\"http://www.w3.org/2003/05/soap-envelope\"" +
        " xmlns:wsa=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\"" +
        " xmlns:wsd=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\"" +
        " xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">" +
        "<Header>" +
        "<wsa:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</wsa:Action>" +
        "<wsa:MessageID>uuid:%s</wsa:MessageID>" +
        "<wsa:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</wsa:To>" +
        "</Header>" +
        "<Body><wsd:Probe>" +
        "<wsd:Types>dn:NetworkVideoTransmitter</wsd:Types>" +
        "</wsd:Probe></Body>" +
        "</Envelope>";

    /**
     * Scan the local network for ONVIF cameras.
     *
     * @return list of discovered cameras; empty if none found or on network error
     */
    public List<DiscoveredCamera> discover() {
        List<DiscoveredCamera> result = new ArrayList<>();

        String probe = String.format(PROBE_TEMPLATE, UUID.randomUUID());
        byte[] probeBytes = probe.getBytes(StandardCharsets.UTF_8);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(COLLECT_MS);

            InetAddress group = InetAddress.getByName(MULTICAST_ADDR);
            DatagramPacket send = new DatagramPacket(
                    probeBytes, probeBytes.length, group, MULTICAST_PORT);
            socket.send(send);
            log.debug("ONVIF: sent WS-Discovery probe");

            byte[] buf = new byte[BUFFER_SIZE];
            long deadline = System.currentTimeMillis() + COLLECT_MS;

            while (System.currentTimeMillis() < deadline) {
                try {
                    DatagramPacket recv = new DatagramPacket(buf, buf.length);
                    socket.receive(recv);
                    String responseXml = new String(recv.getData(), 0, recv.getLength(),
                            StandardCharsets.UTF_8);
                    String senderIp = recv.getAddress().getHostAddress();
                    parseProbeMatch(responseXml, senderIp, result);
                } catch (SocketTimeoutException e) {
                    break; // collection window elapsed
                }
            }
        } catch (Exception e) {
            log.error("ONVIF discovery failed: {}", e.getMessage());
        }

        log.info("ONVIF: discovered {} camera(s)", result.size());
        return result;
    }

    // ── XML parsing ───────────────────────────────────────────────────────────

    private void parseProbeMatch(String xml, String senderIp, List<DiscoveredCamera> out) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // Disable external entity processing for security
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(
                    new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            // XAddrs element contains space-separated device management URLs
            NodeList xAddrNodes = doc.getElementsByTagNameNS("*", "XAddrs");
            if (xAddrNodes.getLength() == 0) return;

            String xAddrs = xAddrNodes.item(0).getTextContent().trim();
            if (xAddrs.isBlank()) return;

            // Take the first XAddr — prefer http, fallback to first
            String managementUrl = selectManagementUrl(xAddrs);
            String ip = extractIp(managementUrl, senderIp);

            out.add(new DiscoveredCamera(ip, managementUrl));
            log.debug("ONVIF: found device at {} (XAddrs={})", ip, xAddrs);

        } catch (Exception e) {
            log.debug("ONVIF: failed to parse probe match from {}: {}", senderIp, e.getMessage());
        }
    }

    private String selectManagementUrl(String xAddrs) {
        String[] parts = xAddrs.split("\\s+");
        for (String p : parts) {
            if (p.startsWith("http://")) return p;
        }
        return parts[0];
    }

    private String extractIp(String url, String fallback) {
        try {
            return new URI(url).getHost();
        } catch (Exception e) {
            return fallback;
        }
    }

    // ── Discovered camera record ──────────────────────────────────────────────

    /**
     * Minimal information about a discovered ONVIF device.
     * Enough to build an onvif:// source URL for go2rtc.
     */
    public record DiscoveredCamera(String ip, String managementUrl) {

        /**
         * Build the onvif:// URL for go2rtc, embedding credentials.
         * go2rtc resolves profiles and picks the best RTSP URI automatically.
         *
         * @param username ONVIF username (may be null)
         * @param password ONVIF password (may be null)
         */
        public String toGo2rtcSrc(String username, String password) {
            if (username != null && !username.isBlank()) {
                return "onvif://" + username + ":" + (password != null ? password : "") + "@" + ip + "/";
            }
            return "onvif://" + ip + "/";
        }

        /** Derive a safe go2rtc stream name from the IP (dots → underscores). */
        public String toStreamName() {
            return "cam_" + ip.replace(".", "_");
        }
    }
}
