package uy.plomo.gateway.zigbee;

import java.util.List;
import java.util.Map;

/**
 * Parsed message from the Z3Gateway CLI.
 *
 * type values:
 *   "frame"              — ZCL frame received from a device
 *   "buffer"             — raw buffer ack line
 *   "zdo-bind-req"       — ZDO bind request response
 *   "inclusion_started"  — network opened for joining
 *   "inclusion_finished" — network closed
 *   "trust_center"       — trust center event (device join/leave)
 *   "ieee-address"       — IEEE address response
 *   "nwk-address"        — NWK address response
 *   "in-clusters"        — ZDO simple descriptor clusters
 */
public class ZigbeeMessage {

    private final String       type;
    private final String       node;        // source node, e.g. "0xD46F"
    private final String       cluster;     // cluster name, e.g. "DoorLock"
    private final String       clusterHex;  // cluster hex, e.g. "0101"
    private final String       command;     // command name, e.g. "GetPINCodeResponse"
    private final String       commandHex;  // command hex, e.g. "06"
    private final String       srcEp;       // source endpoint
    private final String       dstEp;       // dest endpoint
    private final List<String> rawPayload;  // payload bytes as "XX" strings
    /** Decoded fields (field name → value string). */
    private final Map<String, String> fields;
    /** For non-frame types: the raw line data captured by the regex. */
    private final String rawData;

    private ZigbeeMessage(Builder b) {
        this.type       = b.type;
        this.node       = b.node;
        this.cluster    = b.cluster;
        this.clusterHex = b.clusterHex;
        this.command    = b.command;
        this.commandHex = b.commandHex;
        this.srcEp      = b.srcEp;
        this.dstEp      = b.dstEp;
        this.rawPayload = b.rawPayload;
        this.fields     = b.fields;
        this.rawData    = b.rawData;
    }

    public String            getType()       { return type; }
    public String            getNode()       { return node; }
    public String            getCluster()    { return cluster; }
    public String            getClusterHex() { return clusterHex; }
    public String            getCommand()    { return command; }
    public String            getCommandHex() { return commandHex; }
    public String            getSrcEp()      { return srcEp; }
    public String            getDstEp()      { return dstEp; }
    public List<String>      getRawPayload() { return rawPayload; }
    public Map<String,String> getFields()    { return fields; }
    public String            getRawData()    { return rawData; }

    /** Convenience: get a decoded field value, or null. */
    public String field(String name) {
        return fields != null ? fields.get(name) : null;
    }

    @Override
    public String toString() {
        return "ZigbeeMessage{type=" + type + ", node=" + node
                + ", cluster=" + cluster + ", cmd=" + command
                + ", fields=" + fields + "}";
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static Builder builder(String type) { return new Builder(type); }

    public static final class Builder {
        private final String type;
        private String       node, cluster, clusterHex, command, commandHex, srcEp, dstEp, rawData;
        private List<String>       rawPayload;
        private Map<String,String> fields;

        private Builder(String type) { this.type = type; }

        public Builder node(String v)       { node = v;       return this; }
        public Builder cluster(String v)    { cluster = v;    return this; }
        public Builder clusterHex(String v) { clusterHex = v; return this; }
        public Builder command(String v)    { command = v;    return this; }
        public Builder commandHex(String v) { commandHex = v; return this; }
        public Builder srcEp(String v)      { srcEp = v;      return this; }
        public Builder dstEp(String v)      { dstEp = v;      return this; }
        public Builder rawPayload(List<String> v) { rawPayload = v; return this; }
        public Builder fields(Map<String,String> v) { fields = v; return this; }
        public Builder rawData(String v)    { rawData = v;    return this; }

        public ZigbeeMessage build() { return new ZigbeeMessage(this); }
    }
}
