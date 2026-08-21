package uy.plomo.gateway.api;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import uy.plomo.gateway.diagnostics.UnhandledFrameStore;

import java.util.List;
import java.util.Map;

/**
 * REST controller for gateway diagnostics.
 *
 * Routes:
 *   GET    /diagnostics/unhandled         — list unhandled frames
 *   DELETE /diagnostics/unhandled         — clear the store
 */
@RestController
@RequestMapping("/api/v1/diagnostics")
@RequiredArgsConstructor
public class DiagnosticsController {

    private final UnhandledFrameStore store;

    @GetMapping("/unhandled")
    public Map<String, Object> getUnhandled() {
        List<Map<String, Object>> frames = store.getAll();
        return Map.of("count", frames.size(), "frames", frames);
    }

    @DeleteMapping("/unhandled")
    public Map<String, Object> clearUnhandled() {
        store.clear();
        return Map.of("status", "cleared");
    }
}
