package uy.plomo.gateway.diagnostics;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded in-memory store of unhandled frames across all protocols.
 *
 * Purpose: not a log — a live collection showing which data the gateway
 * receives but does not yet process, keyed per (protocol, nodeId, class, command).
 * Useful for writing new handlers and device descriptors.
 *
 * Deduplication: same (protocol, nodeId, commandClass, command) updates
 * count + lastSeen instead of adding a new entry.
 * Capacity: last MAX_ENTRIES unique combinations; oldest evicted on overflow.
 */
@Component
public class UnhandledFrameStore {

    private static final int MAX_ENTRIES = 200;

    public record Entry(
            String protocol,
            String nodeId,
            String commandClass,
            String command,
            Map<String, Object> sample,
            long firstSeen,
            long lastSeen,
            int count
    ) {}

    // key → entry; LinkedHashMap for insertion-order eviction
    private final Map<String, Entry> store =
            Collections.synchronizedMap(new LinkedHashMap<>(MAX_ENTRIES, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    public void record(String protocol, String nodeId,
                       String commandClass, String command,
                       Map<String, Object> fields) {
        String key = protocol + "|" + nodeId + "|" + commandClass + "|" + command;
        long now = Instant.now().toEpochMilli();
        store.compute(key, (k, existing) -> {
            if (existing == null) {
                return new Entry(protocol, nodeId, commandClass, command,
                        fields != null ? new LinkedHashMap<>(fields) : Map.of(),
                        now, now, 1);
            }
            return new Entry(existing.protocol(), existing.nodeId(),
                    existing.commandClass(), existing.command(),
                    existing.sample(), existing.firstSeen(), now, existing.count() + 1);
        });
    }

    public List<Map<String, Object>> getAll() {
        List<Map<String, Object>> result = new ArrayList<>();
        synchronized (store) {
            store.forEach((key, e) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("protocol",     e.protocol());
                m.put("nodeId",       e.nodeId());
                m.put("commandClass", e.commandClass());
                m.put("command",      e.command());
                m.put("count",        e.count());
                m.put("firstSeen",    Instant.ofEpochMilli(e.firstSeen()).toString());
                m.put("lastSeen",     Instant.ofEpochMilli(e.lastSeen()).toString());
                m.put("sample",       e.sample());
                result.add(m);
            });
        }
        // Most recently seen first
        result.sort((a, b) -> b.get("lastSeen").toString().compareTo(a.get("lastSeen").toString()));
        return result;
    }

    public void clear() {
        store.clear();
    }
}
