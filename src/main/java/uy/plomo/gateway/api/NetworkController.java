package uy.plomo.gateway.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import uy.plomo.gateway.zwave.ZWaveController;

import java.util.Map;

/**
 * REST controller for gateway-level operations.
 *
 * Routes:
 *   GET  /summary
 *   POST /include   { protocol, command, blocking }
 *   POST /exclude   { protocol, command, blocking }
 *   POST /timezone  { timezone }
 */
@Tag(name = "01. Network", description = "Gateway summary, device inclusion/exclusion, timezone, tunnels")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class NetworkController {

    private final GatewayApiService api;
    private final ZWaveController   zwaveController;

    @Operation(summary = "Gateway summary", description = "Returns gateway info, firmware version, timezone and all paired devices with their current state.")
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return api.getSummary();
    }

    @Operation(summary = "Start/stop inclusion mode", description = "Body: { \"protocol\": \"zwave\"|\"zigbee\", \"command\": \"start\"|\"stop\", \"blocking\": false }")
    @PostMapping("/include")
    public Map<String, Object> include(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) body = Map.of();
        return api.inclusion(api.str(body, "protocol"), api.str(body, "command"), api.bool(body, "blocking"), body);
    }

    @Operation(summary = "Start/stop exclusion mode (Z-Wave only)", description = "Body: { \"protocol\": \"zwave\", \"command\": \"start\"|\"stop\", \"blocking\": false }")
    @PostMapping("/exclude")
    public Map<String, Object> exclude(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) body = Map.of();
        return api.exclusion(api.str(body, "protocol"), api.str(body, "command"), api.bool(body, "blocking"));
    }

    @PostMapping("/timezone")
    public Map<String, Object> timezone(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) body = Map.of();
        return api.setTimezone(api.str(body, "timezone"));
    }

    /** SSH reverse tunnel management and tunnel list.
     *  POST { cmd: "start", src-addr, src-port, dst-addr, dst-port }
     *  POST { cmd: "stop" }
     *  POST { cmd: "list" }  or GET /tunnel */
    @Operation(summary = "Manage SSH reverse tunnels", description = "Body: { \"cmd\": \"start\"|\"stop\"|\"list\", \"src-addr\": \"...\", \"src-port\": 22, \"dst-addr\": \"...\", \"dst-port\": 2222 }")
    @PostMapping("/tunnel")
    public Map<String, Object> tunnel(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) body = Map.of();
        return api.handleTunnel(body);
    }

    @GetMapping("/tunnel")
    public Map<String, Object> listTunnels() {
        return api.handleTunnel(Map.of("cmd", "list"));
    }

    /** Debug/test endpoint. GET returns ping; POST accepts { cmd, ... } */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) body = Map.of();
        return api.handleTest(body);
    }

    @GetMapping("/test")
    public Map<String, Object> testGet() {
        return api.handleTest(Map.of("cmd", "ping"));
    }

    /** Set association group on a node. POST /zwave/association/{nodeId}  { "group": 1 } */
    @PostMapping("/zwave/association/{nodeId}")
    public Map<String, Object> zwaveAssociation(
            @PathVariable String nodeId,
            @RequestBody(required = false) Map<String, Object> body) {
        if (body == null) body = Map.of();
        return api.handleZwaveNetwork("association/" + nodeId, "POST", body);
    }

    /** Trigger a full Z-Wave interview for a node by its decimal or hex ID.
     *  POST /zwave/interview/42  or  POST /zwave/interview/0x2A */
    @PostMapping("/zwave/interview/{nodeId}")
    public Map<String, Object> zwaveInterview(@PathVariable String nodeId) {
        try {
            int id = nodeId.startsWith("0x") || nodeId.startsWith("0X")
                    ? Integer.parseUnsignedInt(nodeId.substring(2), 16)
                    : Integer.parseInt(nodeId);
            return zwaveController.interview(id);
        } catch (NumberFormatException e) {
            return Map.of("error", "invalid nodeId: " + nodeId);
        }
    }
}
