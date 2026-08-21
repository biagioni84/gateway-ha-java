package uy.plomo.gateway.matter;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Live snapshot of a commissioned Matter node, as provided by python-matter-server.
 * Updated from the start_listening dump and from node_added/node_updated events.
 */
public record MatterNode(long nodeId, boolean available, JsonNode raw) {}
