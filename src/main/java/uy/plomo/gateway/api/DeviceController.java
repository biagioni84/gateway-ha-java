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
            "Dispatches a command to the device. Accepted cmd values:\n" +
            "- lock: GET → current state; POST { value: lock|unlock }\n" +
            "- switch: GET → current state; POST { value: on|off }\n" +
            "- level (Z-Wave): GET → current level; POST { value: 0-99 }\n" +
            "- thermostat (Z-Wave): GET → heat/cool/mode; POST { heat, cool, mode: off|heat|cool|auto }\n" +
            "- pincode: POST { slot, code } to set; DELETE { slot } to remove; GET { slot } to read\n" +
            "- poll_pincodes: GET → cached pincode map\n" +
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
