package uy.plomo.gateway.descriptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Loads and resolves device descriptors from JSON files.
 *
 * Loading order (filesystem wins over classpath for same filename):
 *   1. Filesystem: {gateway.devices.path}/{protocol}/*.json  (operator overrides/additions)
 *   2. Classpath: devices/{protocol}/*.json  (bundled defaults in the JAR)
 *
 * If a file with the same name exists in both places, the filesystem version
 * replaces the classpath version. New files on the filesystem are appended.
 *
 * Resolution strategy:
 *   Return the first descriptor whose matches() returns true for the criteria map.
 */
@Service
@Slf4j
public class DeviceDescriptorService {

    private static final String CLASSPATH_PATTERN = "classpath:devices/%s/*.json";

    private final ObjectMapper mapper;
    private final Path devicesRoot;
    private final PathMatchingResourcePatternResolver classpathResolver =
            new PathMatchingResourcePatternResolver();

    /** Per-protocol cache: protocol → ordered list of resolved descriptors (filesystem first). */
    private final Map<String, List<ResolvedDescriptor>> cache = new ConcurrentHashMap<>();

    public DeviceDescriptorService(ObjectMapper mapper,
                                   @Value("${gateway.devices.path:./devices}") String devicesPath) {
        this.mapper      = mapper;
        this.devicesRoot = Paths.get(devicesPath).toAbsolutePath().normalize();
        log.info("Device descriptors root: {}", this.devicesRoot);
    }

    public Optional<ResolvedDescriptor> resolve(String protocol, Map<String, String> criteria) {
        return loadAll(protocol).stream()
                .filter(rd -> rd.content().matches(criteria))
                .findFirst();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private List<ResolvedDescriptor> loadAll(String protocol) {
        return cache.computeIfAbsent(protocol, this::loadMerged);
    }

    /**
     * Filesystem entries are loaded first and take priority in resolution order.
     * Classpath entries follow — all of them, regardless of filename overlap.
     * Resolution is purely by findFirst() on matching criteria.
     */
    private List<ResolvedDescriptor> loadMerged(String protocol) {
        List<ResolvedDescriptor> result = new ArrayList<>();

        loadFromFilesystem(protocol, result);
        int fsCount = result.size();

        loadFromClasspath(protocol, result);
        int classpathCount = result.size() - fsCount;

        log.info("Descriptors for '{}': {} from filesystem, {} from classpath ({} total)",
                protocol, fsCount, classpathCount, result.size());

        return result;
    }

    private void loadFromClasspath(String protocol, List<ResolvedDescriptor> target) {
        String pattern = CLASSPATH_PATTERN.formatted(protocol);
        try {
            Resource[] resources = classpathResolver.getResources(pattern);
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                String path = "devices/%s/%s".formatted(protocol, filename);
                try {
                    DeviceDescriptor descriptor = mapper.readValue(resource.getInputStream(), DeviceDescriptor.class);
                    target.add(new ResolvedDescriptor("default", path, descriptor));
                    log.debug("Loaded classpath descriptor: {}", path);
                } catch (IOException e) {
                    log.warn("Failed to load classpath descriptor {}", path, e);
                }
            }
        } catch (IOException e) {
            log.debug("No classpath descriptors for protocol '{}'", protocol, e);
        }
    }

    private void loadFromFilesystem(String protocol, List<ResolvedDescriptor> target) {
        Path dir = devicesRoot.resolve(protocol);
        if (!Files.isDirectory(dir)) {
            log.debug("No filesystem descriptor directory: {}", dir);
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".json"))
                 .sorted()
                 .forEach(p -> {
                     String relPath = devicesRoot.relativize(p).toString().replace('\\', '/');
                     try {
                         DeviceDescriptor descriptor = mapper.readValue(p.toFile(), DeviceDescriptor.class);
                         target.add(new ResolvedDescriptor("default", relPath, descriptor));
                         log.debug("Loaded filesystem descriptor: {}", relPath);
                     } catch (IOException e) {
                         log.warn("Failed to load filesystem descriptor {}", relPath, e);
                     }
                 });
        } catch (IOException e) {
            log.warn("Failed to list filesystem descriptors in {}", dir, e);
        }
    }
}
