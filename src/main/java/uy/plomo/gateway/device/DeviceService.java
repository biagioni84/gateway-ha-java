package uy.plomo.gateway.device;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Device CRUD and attribute/pincode management.
 *
 * Mirrors the Clojure functions in devices.clj and db.clj:
 *   get-device, list-devices, update-device, delete-devices,
 *   update-attributes, update-pincode, delete-pincode.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DeviceService {

    private final DeviceRepository repo;

    // ── Query ─────────────────────────────────────────────────────────────────

    public Optional<Device> findById(String id) {
        return repo.findById(id);
    }

    public Optional<Device> findByNode(String node) {
        return repo.findByNode(node);
    }

    public Optional<Device> findByIeeeAddr(String ieeeAddr) {
        return repo.findByIeeeAddr(ieeeAddr);
    }

    public List<Device> findByProtocol(String protocol) {
        return repo.findByProtocol(protocol);
    }

    public List<Device> findAll() {
        return repo.findAll();
    }

    /** Returns {id → Device} map — mirrors (db/list-devices nil). */
    public Map<String, Device> listAll() {
        Map<String, Device> map = new LinkedHashMap<>();
        repo.findAll().forEach(d -> map.put(d.getId(), d));
        return map;
    }

    // ── Upsert ────────────────────────────────────────────────────────────────

    /**
     * Save or update a device.
     * If the device has no ID, looks up an existing record by node or ieeeAddr.
     * Mirrors (devices/update-device device).
     */
    @Transactional
    public Device save(Device device) {
        if (device.getId() == null || device.getId().isBlank()) {
            Optional<Device> existing = resolveExisting(device);
            String id = existing.map(Device::getId).orElseGet(() -> UUID.randomUUID().toString());
            device.setId(id);
        }
        log.trace("Saving device {} protocol={} node={}", device.getId(), device.getProtocol(), device.getNode());
        return repo.save(device);
    }

    private Optional<Device> resolveExisting(Device device) {
        if ("zigbee".equals(device.getProtocol())) {
            if (device.getIeeeAddr() != null) {
                Optional<Device> byIeee = repo.findByIeeeAddr(device.getIeeeAddr());
                if (byIeee.isPresent()) return byIeee;
            }
        }
        if (device.getNode() != null) {
            return repo.findByNode(device.getNode());
        }
        return Optional.empty();
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Transactional
    public void deleteById(String id) {
        repo.deleteById(id);
    }

    @Transactional
    public void deleteByIds(List<String> ids) {
        repo.deleteAllById(ids);
    }

    // ── Attribute updates ────────────────────────────────────────────────────

    /**
     * Update a single attribute value for the device identified by node.
     * Mirrors (devices/update-attributes node {:cluster c :attr-name a :value v}).
     */
    @Transactional
    public void updateAttribute(String node, String cluster, String attrName, Object value) {
        repo.findByNode(node).ifPresentOrElse(dev -> {
            dev.setAttribute(cluster, attrName, value);
            repo.save(dev);
            log.trace("Updated attribute {}/{} for node {}", cluster, attrName, node);
        }, () -> log.error("updateAttribute: no device for node {}", node));
    }

    // ── Pincode updates ───────────────────────────────────────────────────────

    /**
     * Set or update a PIN code for a given user slot.
     * Mirrors (devices/update-pincode node usr-id code).
     */
    @Transactional
    public void updatePincode(String node, String userId, String code) {
        repo.findByNode(node).ifPresentOrElse(dev -> {
            if (dev.getPincodes() == null) dev.setPincodes(new HashMap<>());
            dev.getPincodes().put(userId, code);
            repo.save(dev);
            log.trace("Updated pincode slot {} for node {}", userId, node);
        }, () -> log.error("updatePincode: no device for node {}", node));
    }

    /**
     * Remove a PIN code slot.
     * Mirrors (devices/delete-pincode node usr-id).
     */
    @Transactional
    public void deletePincode(String node, String userId) {
        repo.findByNode(node).ifPresentOrElse(dev -> {
            if (dev.getPincodes() != null) {
                dev.getPincodes().remove(userId);
                repo.save(dev);
                log.trace("Deleted pincode slot {} for node {}", userId, node);
            }
        }, () -> log.error("deletePincode: no device for node {}", node));
    }
}
