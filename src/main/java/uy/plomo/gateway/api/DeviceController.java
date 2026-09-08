package uy.plomo.gateway.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST controller for per-device operations.
 *
 * {dev} is a HAv1 device group id (see GET /summary) — {cmd} is one of that group's advertised
 * "actions", resolved against whichever entity in the group actually provides it (see
 * HomeAssistantController.handleDeviceCommand()), not a fixed verb assumed to match one domain.
 *
 * Routes (more-specific Spring routes in other controllers take priority):
 *   GET    /{dev}
 *   DELETE /{dev}
 *   *      /{dev}/{cmd}
 *   *      /{dev}/{cmd}/{id}
 */
@Tag(name = "02. Devices", description = "Per-device read and command dispatch")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DeviceController {

    private final GatewayApiService api;

    @GetMapping("/{dev}")
    public Map<String, Object> getDevice(@PathVariable String dev) {
        return api.getDevice(dev);
    }

    @DeleteMapping("/{dev}")
    public Map<String, Object> deleteDevice(@PathVariable String dev) {
        return api.deleteDevice(dev);
    }

    @Operation(summary = "Device command", description =
            "Dispatches an action to the device group — see that device's \"actions\" list in " +
            "GET /summary for what's actually available on it (varies per device: a lock offers " +
            "lock/unlock/pincode, a dimmable light offers turn_on/turn_off/toggle/set_level, a " +
            "thermostat offers set_temperature/set_hvac_mode, etc. — collisions within a group " +
            "are suffixed _1/_2/...). Common ones:\n" +
            "- turn_on / turn_off / toggle: switch, light, fan\n" +
            "- set_level: POST { value: 0-99 } — dimmable lights only\n" +
            "- lock / unlock: locks\n" +
            "- pincode: POST { slot, code } to set; DELETE { slot } to remove; GET { slot } to read\n" +
            "- set_temperature: POST { heat, cool } or { temperature }\n" +
            "- set_hvac_mode: POST { mode }\n" +
            "- open / close / stop / set_position: covers\n" +
            "- set_speed / oscillate / set_direction: fans\n" +
            "- service: escape hatch, POST { domain, service, data?, entity_id? }\n" +
            "- name: POST { value: string } to rename\n" +
            "- fwd_event: POST { ev: cmdName } to subscribe; DELETE { ev } to unsubscribe")
    @RequestMapping(value = "/{dev}/{cmd}", method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE, RequestMethod.PUT})
    public Map<String, Object> deviceCmd(
            @PathVariable String dev,
            @PathVariable String cmd,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestParam(required = false) Map<String, String> params,
            HttpServletRequest request) {

        Map<String, Object> merged = merge(body, params);
        return api.handleDeviceCommand(dev, cmd, null, request.getMethod().toUpperCase(), merged);
    }

    @RequestMapping(value = "/{dev}/{cmd}/{id}", method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE, RequestMethod.PUT})
    public Map<String, Object> deviceCmdId(
            @PathVariable String dev,
            @PathVariable String cmd,
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestParam(required = false) Map<String, String> params,
            HttpServletRequest request) {

        Map<String, Object> merged = merge(body, params);
        return api.handleDeviceCommand(dev, cmd, id, request.getMethod().toUpperCase(), merged);
    }

    private Map<String, Object> merge(Map<String, Object> body, Map<String, String> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (params != null) m.putAll(params);
        if (body   != null) m.putAll(body);
        return m;
    }
}
