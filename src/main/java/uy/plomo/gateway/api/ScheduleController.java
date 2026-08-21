package uy.plomo.gateway.api;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for schedule management (stub — Phase 7).
 *
 * Routes:
 *   GET    /schedule
 *   POST   /schedule
 *   GET    /schedule/{id}
 *   DELETE /schedule/{id}
 */
@Tag(name = "03. Schedule", description = "Time-based schedules (stub — not yet implemented)")
@RestController
@RequestMapping("/api/v1/schedule")
@RequiredArgsConstructor
public class ScheduleController {

    private final GatewayApiService api;

    @GetMapping
    public Map<String, Object> list() {
        return api.getSchedule();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> body) {
        return api.createSchedule(body != null ? body : Map.of());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return api.getScheduleItem(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        return api.deleteScheduleItem(id);
    }
}
