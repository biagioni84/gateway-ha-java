# OTA Update

Over-the-air update for the gateway Spring Boot JAR, triggered via MQTT or the local REST API.

---

## Gateway-side implementation

### Trigger

```
POST /ota
{
  "url":      "https://releases.example.com/gateway/1.2.3/gateway.jar",
  "checksum": "sha256:a3f1..."
}
```

Accepted via:
- **MQTT** — `iot/v1/{gatewayId}/request/{requestId}` with path `POST:/ota`
- **REST** — `POST /api/v1/ota` (local, requires JWT)

### Update flow

```
cloud                                  gateway
  │── POST /ota {url, checksum} ──────>│
  │                                    │ 1. validate url (https only) + checksum present
  │                                    │ 2. download to /tmp/gateway-update-{ts}.jar
  │                                    │ 3. compute SHA-256 of downloaded file
  │                                    │ 4. compare to provided checksum — abort if mismatch
  │                                    │ 5. atomic move to gateway.ota.jar-path
  │<── {status:"ok", message:"..."} ───│
  │                                    │ 6. (500 ms delay) systemctl restart gateway
  │                                    │    process exits — response was already sent
  │                                    │
  │                (gateway comes back online, reconnects MQTT with backoff)
  │
```

The 500 ms sleep before restart gives the MQTT publish time to complete the in-flight write before the process exits. The response is best-effort; the cloud must not block on it.

### Configuration

In `application.properties`:

```properties
gateway.ota.jar-path=/opt/gateway/gateway.jar
gateway.ota.service-name=gateway
```

`gateway.ota.service-name` is the systemd unit name. The gateway process must have sudo permission for `systemctl restart <service-name>` without a password. Add to `/etc/sudoers.d/gateway`:

```
gateway ALL=(ALL) NOPASSWD: /bin/systemctl restart gateway
```

### Constraints

| Item | Value |
|---|---|
| Transport security | HTTPS only — HTTP URLs are rejected |
| Integrity check | SHA-256 (hex, optionally prefixed with `sha256:`) |
| Download timeout | 5 minutes |
| Connect timeout | 10 seconds |
| Temp location | `/tmp/gateway-update-{timestamp}.jar` |
| Install | atomic move (`ATOMIC_MOVE`) — partial writes never reach the live path |
| Restart | `sudo systemctl restart <service-name>` via subprocess |

### Success response

```json
{ "status": "ok", "message": "update installed — restarting" }
```

### Error responses

```json
{ "error": "url must use https://" }
{ "error": "checksum is required (SHA-256 hex)" }
{ "error": "download failed: HTTP 403" }
{ "error": "checksum mismatch" }
{ "error": "update failed: <detail>" }
```

---

## Pending work (gateway-side)

### S-OTA-1 — JAR signature verification

**Current state:** integrity is verified by SHA-256 checksum supplied in the command. The checksum itself is delivered over MQTT/mTLS, which provides transport-level authentication, but anyone who can publish to the MQTT topic can push an arbitrary JAR.

**Required:** verify a detached RSA or ECDSA signature over the JAR bytes before installing. The public key must be embedded in the running binary or stored at a read-only path on the filesystem (e.g., `/etc/gateway/ota-pubkey.pem`).

**Suggested approach:**
- Cloud signs the JAR with a private key during the release pipeline.
- The `POST /ota` payload gains a `signature` field (base64-encoded).
- `OtaService` verifies `Signature.verify(publicKey, jarBytes, signature)` before moving.
- Key rotation requires a firmware update — plan for it from the start.

### S-OTA-2 — Rollback

**Current state:** the current JAR is overwritten with no backup. If the new JAR fails to start (bad config, incompatible DB schema, crash-loop), the device is bricked until a technician intervenes.

**Required:** a two-stage install with automatic rollback on startup failure.

**Suggested approach:**

1. Before moving the new JAR, copy the current one to `gateway.jar.bak`.
2. The systemd unit uses a wrapper script instead of calling `java -jar` directly:

```bash
#!/bin/bash
# /opt/gateway/start.sh
FAILURES_FILE=/tmp/gateway-failures

failures=$(cat "$FAILURES_FILE" 2>/dev/null || echo 0)
if [ "$failures" -ge 3 ] && [ -f /opt/gateway/gateway.jar.bak ]; then
    echo "OTA rollback: too many failures, restoring backup" | systemd-cat -p err
    cp /opt/gateway/gateway.jar.bak /opt/gateway/gateway.jar
    echo 0 > "$FAILURES_FILE"
fi

java -jar /opt/gateway/gateway.jar
echo $((failures + 1)) > "$FAILURES_FILE"
```

3. On successful startup (e.g., health check passes or Spring context loads), reset `$FAILURES_FILE` to 0 via a `@PostConstruct` hook or an `ApplicationReadyEvent` listener.

---

## Cloud-side counterpart

### Release pipeline

1. Build `gateway-{version}.jar`.
2. Compute `sha256sum gateway-{version}.jar` → store as release metadata.
3. (When S-OTA-1 is implemented) sign the JAR with the OTA private key → store the base64 signature as release metadata.
4. Upload JAR to S3 (or equivalent object store).
5. Generate a **pre-signed URL** valid for a limited window (e.g., 15–30 minutes) — do not store a long-lived public URL.

### Update trigger

The cloud sends an MQTT command to the target gateway (or a fleet of gateways):

```json
{
  "path": "POST:/ota",
  "command": {
    "url":      "https://s3.amazonaws.com/releases/gateway-1.2.3.jar?X-Amz-...",
    "checksum": "sha256:a3f1c8..."
  }
}
```

Published to: `iot/v1/{gatewayId}/request/{requestId}`

### Confirm completion

The gateway returns `{ "status": "ok" }` before it exits, but this is best-effort. The reliable completion signal is the gateway reconnecting to MQTT after the restart. The cloud should:

1. Record the update as `pending` when the command is sent.
2. Start a timeout (e.g., 5 minutes — allow for slow hardware and backoff reconnect).
3. On the next MQTT connection from the gateway, check the running version via `GET:/summary` (the `fw_version` field).
4. Compare reported version to the expected version — mark the update as `succeeded` or `failed`.

`fw_version` is set in `GatewayApiService`:
```java
private static final String FW_VERSION = "0.1";
```
This constant must be bumped as part of every release and must match the artifact version.

### Fleet updates

For multiple gateways, stagger the rollout:
- Send the command to a pilot group first and confirm success before widening.
- Use a per-gateway `requestId` so responses can be correlated.
- Implement a cooldown: do not send a second update if an update is already `pending` for that gateway.

### Pre-signed URL expiry

Generate the pre-signed URL immediately before sending the MQTT command — not at pipeline time. If the gateway is offline when the command is sent (MQTT retained message or re-delivery), the URL may have expired by the time the gateway processes it. Options:
- Use a short expiry (15 min) and retry the command if the gateway was offline.
- Use a longer expiry (1–2 h) accepting that the URL could be reused within that window.
- (After S-OTA-1) replace the pre-signed URL with a permanent CDN URL — signature on the JAR provides integrity independent of URL secrecy.
