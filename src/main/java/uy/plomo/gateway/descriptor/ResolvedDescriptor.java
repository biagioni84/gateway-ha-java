package uy.plomo.gateway.descriptor;

/**
 * A descriptor that has been successfully resolved and loaded.
 *
 * @param origin  "default" for bundled classpath files,
 *                "user" for externally overridden files.
 * @param file    Relative path used to load the descriptor,
 *                e.g. "devices/zwave/yaledoorlock.json".
 * @param content The deserialized descriptor (ZWaveDescriptor, ZigbeeDescriptor, …).
 */
public record ResolvedDescriptor(String origin, String file, DeviceDescriptor content) {}
