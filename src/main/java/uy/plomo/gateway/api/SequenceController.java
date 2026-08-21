package uy.plomo.gateway.api;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for sequence management.
 *
 * Routes:
 *   GET    /sequences
 *   POST   /sequences
 *   GET    /sequences/{id}
 *   PUT    /sequences/{id}
 *   DELETE /sequences/{id}
 *   POST   /sequences/{id}/run
 */
@Tag(name = "04. Sequences", description = "Named command sequences (CRUD wired; execution engine stub)")
@RestController
@RequestMapping("/api/v1/sequences")
@RequiredArgsConstructor
public class SequenceController {

    private final GatewayApiService api;

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("sequences", api.listSequences());
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> body) {
        return api.createSequence(body != null ? body : Map.of());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return api.getSequence(id);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        return api.updateSequence(id, body != null ? body : Map.of());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        return api.deleteSequence(id);
    }

    @PostMapping("/{id}/run")
    public Map<String, Object> run(@PathVariable String id) {
        return api.runSequence(id);
    }
}
