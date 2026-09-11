# gateway-side

Runs alongside Home Assistant (either as an add-on, or as a standalone process pointed at a
reachable Home Assistant instance). Delegates all device control — locks, switches, dimmers,
thermostats, sensors, cameras — to Home Assistant, persists a local device cache to SQLite, and
communicates with the cloud via AWS IoT MQTT5 (mTLS) plus a local REST API.

This project used to talk directly to Z-Wave (zipgateway), Zigbee (Z3Gateway) and Matter
(python-matter-server) radios and to go2rtc for cameras. That direct-protocol code has been
removed — Home Assistant now owns all of it. The cloud-facing contract (MQTT topics/payloads,
REST API shape) stays as close as possible to what it was, so the mobile app / cloud side needs
minimal changes.

---

## Architecture overview

```
Cloud (AWS IoT) ──MQTT5/mTLS──► MqttService
                                     │
                               MqttDispatcher
                                     │
                          GatewayApiService  ◄──── REST HTTP (port 9098)
                                     │
                        HomeAssistantController  ◄──── LockCodeProvider (pincodes)
                                     │                  HomeAssistantCameraController (cameras)
                        HomeAssistantInterface
                          (WebSocket, HA Core API)
                                     │
                        HomeAssistantReportHandler
                                     │
                                DeviceService
                                     │
                                 SQLite DB
```

### Key components

| Package | Class | Role |
|---------|-------|------|
| `auth` | `AuthController` | `POST /auth/login` — issues JWT tokens. |
| `auth` | `JwtService` | Signs and validates JWT tokens (HS256). |
| `auth` | `JwtFilter` | `OncePerRequestFilter` — validates `Authorization: Bearer` on every request. |
| `auth` | `SecurityConfig` | Spring Security config — stateless JWT, permits `/auth/login`. |
| `mqtt` | `MqttService` | AWS IoT MQTT5 client (mTLS). Subscribe/publish topics. |
| `mqtt` | `MqttDispatcher` | Routes MQTT commands to `GatewayApiService`. |
| `mqtt` | `AsyncCommandDispatcher` | `@Async` offload — keeps the AWS event loop free during blocking Home Assistant calls. |
| `mqtt` | `GatewayExecutorConfig` | Defines the `gw-cmd-*` thread pool (core=10, max=20, queue=50). |
| `api` | `GatewayApiService` | Central business logic. Used by REST controllers AND MqttDispatcher. |
| `api` | `NetworkController` | REST: `/summary`, `/include`, `/exclude`, `/timezone` |
| `api` | `DeviceController` | REST: `/:dev`, `/:dev/:cmd`, `/:dev/:cmd/:id` |
| `api` | `CameraRestController` | REST: `/cameras`, `/:dev/snapshot` |
| `api` | `SequenceController` | REST: `/sequences`, `/sequences/:id`, `/sequences/:id/run` |
| `api` | `ScheduleController` | REST: `/schedule`, `/schedule/:id` (stub) |
| `api` | `ProvisioningController` | REST: `GET`/`POST /api/v1/provisioning` — manual credential loading, backs `/setup.html`. |
| `homeassistant` | `HomeAssistantConnectionConfig` | Resolves HA URL/token — Supervisor addon mode vs. standalone dev mode. |
| `homeassistant` | `HomeAssistantInterface` | Persistent WebSocket client to Home Assistant's Core API (auth handshake, `subscribe_events`, `call_service`, `get_states`). |
| `homeassistant` | `HomeAssistantReportHandler` | Processes `state_changed` events, updates the device cache, forwards MQTT telemetry. |
| `homeassistant` | `HomeAssistantController` | Groups entities into HAv1 device summaries (status/actions) and dispatches an action against whichever entity in the group provides it. |
| `homeassistant` | `HomeAssistantTypeMapper` | Maps an HA entity's domain/device_class to the gateway's logical device `type`. |
| `homeassistant` | `HomeAssistantEntityRegistry` | Resolves which HA integration owns an entity, plus (bulk-cached) its device_id, area, entity_category, and the device registry's manufacturer/model/area. |
| `homeassistant.lock` | `LockCodeProvider` / `HomeAssistantLockCodeProvider` | Lock control + PIN code management, routed per owning integration. |
| `homeassistant.camera` | `HomeAssistantCameraController` / `HomeAssistantCameraService` | Camera summary/commands and snapshot proxying via HA's REST API. |
| `camera` | *(none — removed)* | Direct go2rtc integration was removed; cameras are Home Assistant `camera.*` entities now. |
| `device` | `DeviceService` | CRUD for devices, attributes, pincodes. Fully protocol-agnostic. |
| `sequence` | `SequenceService` | CRUD for named device command sequences. |
| `platform` | `PlatformService` | Serial number (provisioned → `/proc/cpuinfo` → persisted UUID fallback), timezone, SSH public key. |
| `config` | `AppConfig` | Loads `provisioned.creds` (JSON). |

---

## Prerequisites

- Java 17
- Gradle 9+
- A provisioned `provisioned.creds` file (JSON)
- A reachable Home Assistant instance — either:
  - Supervisor-managed: this project running as an HA add-on (auto-detected via the
    `SUPERVISOR_TOKEN` environment variable), or
  - Standalone: a Home Assistant URL + long-lived access token (see Configuration below)
- AWS IoT endpoint and device certificate (written by the provisioning flow)

---

## Building

```bash
./gradlew build
```

This produces two artifacts in `build/libs/`:

| File | Description |
|------|-------------|
| `gateway-0.0.1-SNAPSHOT-lean.jar` | Thin JAR (no embedded deps) |
| `lib/` | All runtime dependencies (copied by `copyDependencies` task) |

Run the thin JAR:

```bash
java -jar build/libs/gateway-0.0.1-SNAPSHOT-lean.jar
```

This is the only deployment mode with working MQTT-triggered OTA (see `OTA.md`) — it requires
systemd. The two modes below are containerized and have OTA disabled (see "Container mode" in
`OTA.md`); updates there come from the add-on store or `docker pull` + recreate instead.

---

## Running as a Home Assistant add-on

This repo doubles as a local-build HA add-on repository (`config.yaml`/`repository.yaml` at the
root, `Dockerfile` builds the whole project — no pre-published image, no registry needed):

1. In Home Assistant: **Settings → Add-ons → Add-on store → ⋮ → Repositories**, add this repo's
   URL.
2. Install "Plomo Gateway" from the store — the Supervisor clones the repo and builds the
   `Dockerfile` on-device (amd64 and aarch64 supported).
3. Start it. `HomeAssistantConnectionConfig` auto-detects Supervisor mode via `SUPERVISOR_TOKEN` —
   no HA URL/token configuration needed.
4. Open `http://<host>:9098/setup.html` to load the AWS IoT endpoint and device certificate (see
   [Provisioning](#provisioning)) — the add-on doesn't expose AWS IoT settings in its options,
   this is the only way to provision it.
5. Auth username/password and log level are configurable from the add-on's **Configuration** tab
   in the HA UI (mapped from `config.yaml`'s `options`).

## Running as a standalone Docker container

For a Home Assistant that's itself just a Docker container (no Supervisor/HAOS) — see
`docker-compose.standalone.example.yml` for a full example. Key points:

- Set `HOMEASSISTANT_URL`/`HOMEASSISTANT_TOKEN` env vars (standalone mode — see below).
- Mount a volume at `/data` (where `gateway.db`, `provisioned.creds`, etc. live — same convention
  the add-on uses for its persistent storage).
- `server.address` still defaults to `127.0.0.1`; set `SERVER_ADDRESS=0.0.0.0` if you need
  `/setup.html` or the REST API reachable from the host (the container's own network isolation is
  the boundary that `127.0.0.1` provides on bare-metal, so this isn't a security downgrade).
- Provision AWS IoT credentials the same way as the add-on: `/setup.html` after startup.

---

## Configuration

All configuration lives in `src/main/resources/application.properties`. Override any property via environment variable or a local `application.properties` next to the JAR.

| Property | Default | Description |
|----------|---------|--------------|
| `server.port` | `9098` | HTTP port |
| `server.address` | `127.0.0.1` | Bind address — change to `0.0.0.0` for LAN access |
| `spring.datasource.url` | `jdbc:sqlite:./gateway.db` | SQLite database path |
| `aws.iot.endpoint` | *(blank)* | AWS IoT endpoint URL — fallback used only if `provisioned.creds` has no `iotEndpoint`. Set via env var or a local `application.properties`; never commit a real value. |
| `homeassistant.enabled` | `true` | Set to `false` to disable the Home Assistant subsystem |
| `homeassistant.url` | `http://localhost:8123` | Home Assistant base URL — standalone/dev mode only |
| `homeassistant.token` | *(blank)* | Home Assistant long-lived access token — standalone/dev mode only |
| `homeassistant.ws.path` | `/api/websocket` | Home Assistant WebSocket API path |
| `telemetry.flush.interval.seconds` | `60` | How often the telemetry buffer flushes to MQTT |
| `gateway.auth.username` | `admin` | REST API login username |
| `gateway.auth.password` | `changeme` | REST API login password — **change before LAN exposure** |
| `gateway.auth.jwt.secret` | *(blank)* | JWT signing secret — random generated on startup if blank |
| `gateway.auth.jwt.expiry.hours` | `24` | JWT token lifetime in hours |
| `gateway.creds.path` | `./provisioned.creds` | Path to provisioning credentials |
| `gateway.serial.path` | `./gateway.serial` | Last-resort `gw_id` fallback — a UUID generated once and persisted here, used only when `provisioned.creds` has no `serialNumber` and `/proc/cpuinfo` has no `Serial` line (i.e. anything that isn't a Raspberry Pi/Broadcom board) |
| `gateway.deployment.mode` | `bare-metal` | Set to `container` (done automatically by the Dockerfile) to disable OTA — see `OTA.md` |

### Home Assistant connection modes

`HomeAssistantConnectionConfig` picks one of two modes at startup:

- **Supervisor-managed add-on**: if the `SUPERVISOR_TOKEN` environment variable is present, the
  gateway talks to Home Assistant through the Supervisor's internal proxy
  (`http://supervisor/core`, `ws://supervisor/core/websocket`) using that token. This is
  automatic — no `homeassistant.url`/`homeassistant.token` configuration needed.
- **Standalone**: otherwise, `homeassistant.url` + `homeassistant.token` (a long-lived access
  token, generated from your Home Assistant user profile page) are used. This is the mode for
  local development or a bare-metal deployment talking to a separately-hosted Home Assistant.

### Disabling subsystems for local development

```properties
aws.iot.endpoint=disabled
homeassistant.enabled=false
```

---

## Credentials file (`provisioned.creds`)

Written by the provisioning flow (JSON).

**JSON format:**
```json
{
  "name": "gateway-device-001",
  "certPem": "-----BEGIN CERTIFICATE-----\n...",
  "privateKey": "-----BEGIN RSA PRIVATE KEY-----\n...",
  "certId": "abc123",
  "serialNumber": "10000000abcdef01",
  "iotEndpoint": "xxxxxxxxxxxxxx-ats.iot.us-east-1.amazonaws.com"
}
```

`iotEndpoint` is optional — omit it when the external fleet-provisioning flow already sets
`aws.iot.endpoint` via the environment. See [Provisioning](#provisioning) below for loading these
fields by hand instead of via fleet provisioning.

If the file is absent, or no AWS IoT endpoint can be resolved, the gateway starts without MQTT
connectivity and logs a warning.

---

## Provisioning

For installs that don't go through the external fleet-provisioning flow, open
`http://<gateway-host>:9098/setup.html` in a browser to load the AWS IoT endpoint and device
certificate by hand:

1. Log in with the REST API credentials (`gateway.auth.username`/`password`).
2. Paste the gateway name, AWS IoT endpoint, cert ID, serial number, certificate PEM, and private
   key PEM, then save.
3. Restart the gateway process — credentials are only read at startup (`MqttService` doesn't
   reconnect live), so nothing takes effect until then.

Under the hood this is just `GET`/`POST /api/v1/provisioning`, which reads/writes the same
`provisioned.creds` file described above (`GET` never returns `certPem`/`privateKey`). The page
itself is reachable without a token (it has its own login form), but the API it calls requires the
same JWT Bearer auth as every other endpoint.

---

## Security

### REST API — JWT authentication

All REST endpoints (except `/auth/login` and Swagger UI) require a valid JWT Bearer token.

**Login:**
```http
POST /auth/login
Content-Type: application/json

{ "username": "admin", "password": "changeme" }
```

**Response:**
```json
{ "token": "<jwt>", "expiresIn": 86400 }
```

**Subsequent requests:**
```
Authorization: Bearer <jwt>
```

Tokens expire after `gateway.auth.jwt.expiry.hours` (default 24 h). Change `gateway.auth.username` and `gateway.auth.password` in `application.properties` before exposing the API on the LAN.

If `gateway.auth.jwt.secret` is left blank, a random signing key is generated on startup — tokens are invalidated on restart.

MQTT commands bypass JWT entirely; MQTT access is controlled by AWS IoT Core access policies.

### SSH tunnels

SSH reverse tunnels use `StrictHostKeyChecking=yes`. Do not disable this.

### AWS IoT

The gateway authenticates to AWS IoT Core with an X.509 certificate (mTLS). The private key is stored in `provisioned.creds` — protect this file.

---

## REST API

All responses are `application/json`. The HTTP method is significant (GET / POST / DELETE).

### Network / platform

| Method | Path | Body | Description |
|--------|------|------|-------------|
| `GET` | `/summary` | — | Gateway status, device list, serial number, time |
| `POST` | `/include` | `{protocol, command, blocking}` | Start/stop device inclusion, routed through the matching Home Assistant integration |
| `POST` | `/exclude` | `{protocol, command, blocking}` | Start/stop device exclusion, routed through the matching Home Assistant integration |
| `POST` | `/timezone` | `{timezone}` | Set system timezone |
| `GET` | `/tunnel` | — | List running SSH reverse tunnels |
| `POST` | `/tunnel` | `{cmd, ...}` | Manage SSH reverse tunnels |

#### Inclusion / exclusion example

```json
POST /include
{ "protocol": "zwave", "command": "start", "blocking": false }
```

`protocol` selects which Home Assistant integration's pairing flow to invoke — it no longer
selects a direct radio connection:

| `protocol` | Routed to | Notes |
|---|---|---|
| `zwave` | Z-Wave JS (`zwave_js/add_node`, `.../remove_node`, `.../stop_inclusion`, `.../stop_exclusion`) | Not a `call_service` action — a dedicated WebSocket command needing the integration's config entry, resolved automatically. `command: start_s2` is not yet distinguished from `start` (falls back to HA's default inclusion strategy). |
| `zigbee` | ZHA (`zha.permit` for inclusion) | ZHA has no service to close the join window early (`command: stop` returns `status: not_supported`) and no network-wide exclusion mode (`/exclude` returns `status: not_supported` — remove a specific device via `DELETE /:dev` instead). |
| `matter` | *(manual)* | Home Assistant's Matter integration commissions through an interactive UI flow with no headless equivalent — `/include` returns `status: manual` pointing at the Home Assistant UI. Once commissioned there, the device appears automatically via `GET /summary`. |

---

### SSH tunnels

Two independent tunnelling mechanisms are supported.

#### 1. SSH reverse tunnels (`POST /tunnel`)

| `cmd` | Body fields | Description |
|-------|-------------|-------------|
| `start` | `src-addr`, `src-port`, `dst-addr`, `dst-port` | Start a reverse tunnel |
| `stop` | — | Kill all running tunnels |
| `list` | — | List running tunnel PIDs and port specs |

```json
POST /tunnel
{ "cmd": "start", "src-addr": "127.0.0.1", "src-port": 22,
  "dst-addr": "bastion.example.com", "dst-port": 2222 }
```

#### 2. AWS Secure Tunneling (MQTT)

AWS IoT Secure Tunneling is initiated from the cloud side. When a tunnel opens, AWS publishes to `$aws/things/{name}/tunnels/notify` and the gateway automatically starts `localproxy` in destination mode.

---

### Devices

| Method | Path | Description |
|--------|------|--------------|
| `GET` | `/:dev` | Get device details |
| `DELETE` | `/:dev` | Remove the local device record — does **not** un-pair the device from Home Assistant/its radio network; use the Home Assistant UI (or the device's own reset procedure) for that |
| `POST` | `/:dev/name` | `{value}` — rename device |
| `POST` | `/:dev/fwd_event` | `{ev}` — subscribe to event forwarding |
| `DELETE` | `/:dev/fwd_event` | `{ev}` — unsubscribe from event forwarding |

#### Device commands

All commands below are dispatched by `HomeAssistantController`, which maps them onto Home
Assistant service calls based on the entity's domain (`lock.*`, `light.*`, `switch.*`,
`climate.*`, `camera.*`, ...).

| Method | Path | Body | Description |
|--------|------|------|--------------|
| `POST` | `/:dev/on` | — | Turn on (`<domain>.turn_on`) |
| `POST` | `/:dev/off` | — | Turn off (`<domain>.turn_off`) |
| `POST` | `/:dev/toggle` | — | Toggle (`<domain>.toggle`) |
| `GET`/`POST` | `/:dev/switch` | `{value: "on"\|"off"}` | Read or set on/off state |
| `POST` | `/:dev/level` | `{value: 0–99}` | Set brightness (lights only — normalized to the existing 0–99 convention; HA's native scale is 0–255) |
| `GET`/`POST` | `/:dev/lock` | `{value: "lock"\|"unlock"}` | Read or set lock state |
| `GET`/`POST`/`DELETE` | `/:dev/pincode/:slot` | `{code}` (POST) | Get/set/delete a lock PIN code slot — see **Lock PIN codes** below |
| `POST` | `/:dev/thermostat` | `{heat?, cool?, mode?}` | Set target temperature and/or HVAC mode |
| `POST` | `/:dev/service` | `{domain, service, data?, entity_id?}` | Generic escape hatch — calls any Home Assistant service directly (covers `cover.*`, `fan.*`, and anything else without a dedicated verb above) |
| `GET` | `/:dev/stream` | — | Camera only — HLS stream URL from Home Assistant's `camera/stream` |
| `GET` | `/:dev/snapshot` | — | Camera only — JPEG snapshot, proxied server-side from Home Assistant |

#### Lock PIN codes

PIN code management is resolved per-lock, live, based on which Home Assistant integration owns
the entity (`HomeAssistantEntityRegistry`):

- **Z-Wave JS** locks: fully supported, via `zwave_js.get_lock_usercode` / `set_lock_usercode` /
  `clear_lock_usercode` — a clean, documented, per-lock API.
- **ZHA** (Zigbee) locks: no dedicated service exists in Home Assistant
  ([zigpy/zha#729](https://github.com/zigpy/zha/issues/729), still open), so this falls back to
  `zha.issue_zigbee_cluster_command`, sending the DoorLock cluster's own
  `SetPINCode`(0x05)/`GetPINCode`(0x06)/`ClearPINCode`(0x07) commands directly — the same
  commands the old direct-radio code used to send over serial, just issued through HA instead.
  **Set/delete should be reliable** (fire-and-forget commands); **get is best-effort** — its
  response depends on the specific lock model's ZHA "quirk" correctly relaying the reply, and
  real-world reports show this failing on some hardware. If it fails for your lock, the error
  message says so explicitly rather than returning a fabricated code.
- **Zigbee2MQTT** locks (via Home Assistant's generic MQTT integration + MQTT discovery — Z2M
  isn't a native HA integration): no HA service either, so PIN codes are set by asking HA to
  publish an MQTT message (`mqtt.publish`) to the device's own Z2M `.../set` topic, matching the
  payload shape used by several Z2M-supported lock models. **Verify the exact payload fields
  against your specific lock's page on zigbee2mqtt.io** — Z2M's PIN code payload isn't
  perfectly uniform across all lock models the way Z-Wave JS's API is. Reading a code back
  isn't implemented for this path (check the lock's state in Home Assistant or Z2M's own UI
  instead).
- Basic lock/unlock (`/:dev/lock`) works for any Home Assistant lock entity regardless of
  integration.
- Weekday/yearday schedule restrictions (previously supported for direct Z-Wave/Zigbee locks)
  have no Home Assistant equivalent and are not implemented.

If you're choosing a Zigbee stack and lock PIN codes matter, Zigbee2MQTT's per-model device
support is generally more mature for this than ZHA's generic raw-command fallback — worth
weighing if you're not already committed to ZHA.

See `LockCodeProvider` in `homeassistant/lock/` for the extension point a future direct-to-lock
fallback (bypassing Home Assistant entirely) would plug into for gaps none of the above cover.

---

### Cameras

Cameras are Home Assistant `camera.*` entities. Adding/configuring a camera happens in the
Home Assistant UI (any camera integration Home Assistant supports — ONVIF, generic RTSP,
manufacturer-specific, etc.) — this gateway only lists and proxies.

| Method | Path | Description |
|--------|------|--------------|
| `GET` | `/api/v1/cameras` | List all camera devices |
| `DELETE` | `/api/v1/cameras/:dev` | Remove the local device record |
| `GET` | `/api/v1/:dev/snapshot` | Proxy a JPEG snapshot from Home Assistant |
| `GET` | `/:dev/stream` | HLS stream URL from Home Assistant |

**Known limitation:** the stream URL returned by `/:dev/stream` is relative to
`homeassistant.url`. In Supervisor add-on mode that resolves to the internal-only
`http://supervisor/core` proxy, which an external client can't reach — live streaming from
inside an add-on needs follow-up work (e.g. proxying HLS segments through the gateway itself,
the way `/snapshot` already proxies single frames). Snapshot proxying works today regardless of
deployment mode, since the gateway fetches it server-side.

---

### Sequences

A sequence is a named list of API calls executed with per-step delays.

| Method | Path | Body | Description |
|--------|------|------|--------------|
| `GET` | `/sequences` | — | List all sequences |
| `POST` | `/sequences` | `{name, steps}` | Create sequence |
| `GET` | `/sequences/:id` | — | Get sequence |
| `PUT` | `/sequences/:id` | `{name?, steps?}` | Update sequence |
| `DELETE` | `/sequences/:id` | — | Delete sequence |
| `POST` | `/sequences/:id/run` | — | Execute sequence |

**Step format:**
```json
{
  "name": "morning routine",
  "steps": [
    { "delay": 0, "api-call": { "uri": "/uuid1/lock", "method": "POST", "body": {"value":"lock"} } },
    { "delay": 5, "api-call": { "uri": "/uuid2/on",   "method": "POST" } }
  ]
}
```

---

### Schedule

Cron-style API call scheduling (stub — not yet implemented).

| Method | Path | Description |
|--------|------|--------------|
| `GET` | `/schedule` | List scheduled jobs |
| `POST` | `/schedule` | Create scheduled job |
| `GET` | `/schedule/:id` | Get job detail |
| `DELETE` | `/schedule/:id` | Delete job |

---

## MQTT protocol

### Topics

| Direction | Topic | Description |
|-----------|-------|--------------|
| Subscribe | `iot/v1/{name}/request/#` | Incoming commands from cloud |
| Subscribe | `$aws/things/{name}/tunnels/notify` | AWS Secure Tunneling notifications |
| Publish | `iot/v1/{name}/response/{requestId}` | Response to a command |
| Publish | `iot/v1/{name}/event/{timestamp}` | Unsolicited device event |
| Publish | `iot/v1/{name}/telemetry/{timestamp}` | Batched telemetry (state changes, new-entity notifications) |

### Message format

**Incoming command:**
```json
{ "path": "GET:/summary", "command": "{}" }
{ "path": "POST:/uuid1/lock", "command": "{\"value\":\"lock\"}" }
```

`path` format: `METHOD:PATH`. The `command` field is a JSON-encoded string containing the request body.

**Response / event:** plain JSON object.

### Event envelope

Unsolicited events share a common envelope:

```json
{
  "type": "ha",
  "node-id": "<home assistant entity_id>",
  "payload": { "cmd": "state_changed", "state": "<new state string>" }
}
```

An entity is only forwarded this way if its owning `Device.fwdEvents` list contains `"*"` or
`"state"` — Home Assistant's `state_changed` event has no finer-grained sub-classification the
way the old Z-Wave/Zigbee/Matter attribute reports did, so forwarding is opt-in per device
rather than per attribute.

A device appearing in Home Assistant for the first time is reported once via the telemetry
batch as:

```json
{ "type": "ha", "event": "entity_added", "node-id": "<entity_id>", "name": "...", "dev_type": "..." }
```

---

## Device summary format (HAv1)

`GET /summary` groups Home Assistant **entities into physical devices** — a device with several
entities (e.g. a multi-sensor reporting occupancy + illuminance + battery + tamper as four
separate HA entities) appears as **one** entry, not four. The top-level response carries
`"version": "HAv1"` so a client can tell this shape apart from anything older. Entities with no HA
device_id (helpers, some templates) get their own group of one, keyed by their row id instead of
a device_id.

```json
{
  "gw_id": "...", "fw_version": "0.1", "version": "HAv1", "time": "...", "timezone": "...",
  "devices": {
    "<ha_device_id or fallback>": {
      "id":           "<ha_device_id or fallback>",
      "protocol":     "ha",
      "name":         "Front Door Lock",
      "type":         "lock",
      "manufacturer": "ASSA ABLOY",
      "modelId":      "YRD226",
      "areaId":       "front_entry",
      "areaName":     "Front Entry",
      "available":    true,
      "status":  { "lock": "locked", "battery": "63" },
      "actions": ["lock", "unlock", "pincode"]
    }
  }
}
```

- **`type`/`available`/`name`**: taken from the group's "primary" entity — picked by domain
  priority (`lock > climate > switch > light > cover > fan > camera > binary_sensor > sensor`),
  since Home Assistant itself has no "primary entity" concept for a multi-entity device. Ties
  within the same domain prefer a non-`diagnostic`/`config` entity, then break alphabetically by
  `entity_id` for determinism.
- **`status`**: one entry per entity in the group, passed through Home Assistant's own state
  string as-is. Keyed by a short label (usually the `device_class`, e.g. `battery`, `illuminance`,
  `temperature`; the domain itself for non-sensor entities, e.g. `lock`, `climate`). **If two
  entities in the same group produce the same label** (real example: a multi-sensor with two
  `occupancy`-classed binary sensors), both are kept — sorted by `entity_id` and suffixed `_1`,
  `_2`, ... instead of one silently overwriting the other.
- **`actions`**: what you can `POST /:dev/{action}` on this device — computed from the real HA
  capability attributes of whichever entities are in the group (`supported_features`,
  `supported_color_modes`), not a fixed list per type — e.g. `set_level` only appears for lights
  that actually support brightness. Same `_1`/`_2` suffixing as `status` if two entities in a
  group offer the same action (rare, but see `nspanel Relay 1` on a real instance: three switch
  entities on one device produce `turn_on_1`/`turn_on_2`/`turn_on_3`, etc.). Read-only domains
  (`sensor`, `binary_sensor`) never contribute actions. See `DeviceController`'s command endpoint
  docs (Swagger) for the request shape each action expects.
- **`manufacturer`/`modelId`/`areaId`/`areaName`**: resolved from Home Assistant's device/area
  registries at sync time (`HomeAssistantEntityRegistry.primeCache()`), cached until the next
  resync — not live-refreshed on every request.
- Cameras keep their own existing summary shape (`HomeAssistantCameraController`), unaffected by
  the grouping above.

---

## Database

SQLite at `./gateway.db`. Schema managed by Hibernate (`ddl-auto=update`).

| Table | Description |
|-------|--------------|
| `devices` | Devices, with attributes, pincodes, fwdEvents |
| `sequences` | Named device command sequences |

Device attributes are stored as nested JSON: `{ domain → { attrName → value } }`, mirroring the
raw `attributes` object Home Assistant reports for each entity's state.

---

## Home Assistant integration notes

- `HomeAssistantInterface` maintains a persistent WebSocket connection to Home Assistant's Core
  API, with the `auth_required`/`auth`/`auth_ok` handshake Home Assistant requires (redone on
  every reconnect), exponential backoff (5s–300s, jittered), and a 5-minute watchdog — the same
  reconnection shape used throughout this project's WebSocket clients.
- On connect: subscribes to `state_changed` events, then fetches the full state via
  `get_states` — **dispatched off the WebSocket's own I/O thread** (via the shared
  `gatewayExecutor`), since the initial sync makes its own blocking Home Assistant calls
  (`HomeAssistantEntityRegistry.primeCache()`) that would otherwise deadlock waiting on a
  response only that same thread could read. `HomeAssistantReportHandler` turns each entity into
  (or updates) a `Device` row (still one row per HA entity — see [Device summary format
  (HAv1)](#device-summary-format-hav1) for how these get grouped back into physical devices at
  read time); only entities in a domain `HomeAssistantTypeMapper` recognizes (`lock`, `switch`,
  `light`, `climate`, `camera`, `cover`, `fan`, `binary_sensor`, `sensor`) become gateway devices
  — automations, scripts, zones, persons, and other non-device entities are skipped.
  Only Z-Wave JS is verified end-to-end for pincode management; the WebSocket API shapes,
  Z-Wave JS's inclusion/exclusion commands, and the ZHA/Matter gaps documented above were all
  checked against Home Assistant's own source and documentation, not assumed.
