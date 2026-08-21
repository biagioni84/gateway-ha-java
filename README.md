# gateway-side

Runs on the physical gateway device (Raspberry Pi / embedded Linux). Manages Z-Wave, Zigbee, and Matter devices, persists state to a local SQLite database, and communicates with the cloud via AWS IoT MQTT5 (mTLS).

---

## Architecture overview

```
Cloud (AWS IoT) ──MQTT5/mTLS──► MqttService
                                     │
                               MqttDispatcher
                                     │
                          GatewayApiService  ◄──── REST HTTP (port 9096)
                         /       |        \        \
              ZWaveController    │  ZigbeeController  MatterController
                      │          │        │               │
              ZWaveInterface    DB  ZigbeeInterface  MatterInterface
               (UDP/ZIP)      SQLite  (Z3Gateway CLI)  (WebSocket WS)
                      │                  │               │
          UnsolicitedListener         readLoop()    python-matter-server
                      │
     ZWaveReportHandler   ZigbeeReportHandler   MatterReportHandler
                      │          │                    │
                 DeviceService  DeviceService     DeviceService
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
| `mqtt` | `AsyncCommandDispatcher` | `@Async` offload — keeps the AWS event loop free during blocking Z-Wave/Zigbee/Matter calls. |
| `mqtt` | `GatewayExecutorConfig` | Defines the `gw-cmd-*` thread pool (core=10, max=20, queue=50). |
| `api` | `GatewayApiService` | Central business logic. Used by REST controllers AND MqttDispatcher. |
| `api` | `NetworkController` | REST: `/summary`, `/include`, `/exclude`, `/timezone` |
| `api` | `DeviceController` | REST: `/:dev`, `/:dev/:cmd`, `/:dev/:cmd/:id` |
| `api` | `CameraRestController` | REST: `/cameras`, `/cameras/discover`, `/:dev/snapshot` |
| `api` | `SequenceController` | REST: `/sequences`, `/sequences/:id`, `/sequences/:id/run` |
| `api` | `ScheduleController` | REST: `/schedule`, `/schedule/:id` (stub) |
| `camera` | `CameraService` | go2rtc REST API wrapper (streams, snapshots, HLS). |
| `camera` | `OnvifDiscoveryService` | WS-Discovery UDP multicast probe — finds ONVIF cameras on LAN. |
| `telemetry` | `TelemetryBuffer` | Thread-safe event queue — flushes batched events to MQTT on schedule. |
| `zwave` | `ZWaveInterface` | UDP send/receive to zipgateway over IPv6. Hook system for async responses. |
| `zwave` | `ZWaveController` | Z-Wave device commands: lock, pincode, thermostat, switch, dimmer. |
| `zwave` | `ZWaveReportHandler` | Processes unsolicited Z-Wave reports, updates DB, forwards MQTT events. |
| `zwave` | `UnsolicitedListener` | UDP server on port 41231 — receives unsolicited frames from zipgateway. |
| `zigbee` | `ZigbeeInterface` | Manages Z3Gateway subprocess (stdin/stdout). |
| `zigbee` | `ZigbeeController` | Zigbee device commands: on/off, lock, pincode, schedules. |
| `zigbee` | `ZigbeeReportHandler` | Processes Z3Gateway output messages, updates DB, forwards MQTT events. |
| `matter` | `MatterInterface` | WebSocket client to python-matter-server. Hook system for async responses. |
| `matter` | `MatterController` | Matter device commands: on/off, level, lock, commission, remove. |
| `matter` | `MatterReportHandler` | Processes Matter events, auto-creates DB entries, forwards MQTT events. |
| `device` | `DeviceService` | CRUD for devices, attributes, pincodes. |
| `sequence` | `SequenceService` | CRUD for named device command sequences. |
| `platform` | `PlatformService` | Serial number, timezone, SSH public key. |
| `config` | `AppConfig` | Loads `provisioned.creds` (EDN or JSON). |

---

## Prerequisites

- Java 17
- Gradle 9+
- A provisioned `provisioned.creds` file (JSON or legacy EDN)
- Z-Wave: `zipgateway` running and accessible over IPv6
- Zigbee: `Z3Gateway` binary available at the configured path
- Matter: `python-matter-server` reachable at the configured WebSocket URL
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

---

## Configuration

All configuration lives in `src/main/resources/application.properties`. Override any property via environment variable or a local `application.properties` next to the JAR.

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `9098` | HTTP port |
| `server.address` | `127.0.0.1` | Bind address — change to `0.0.0.0` for LAN access |
| `spring.datasource.url` | `jdbc:sqlite:./gateway.db` | SQLite database path |
| `aws.iot.endpoint` | *(set in file)* | AWS IoT endpoint URL |
| `zwave.zipgateway.host.prefix` | `fd00:bbbb::` | IPv6 prefix for Z-Wave nodes |
| `zwave.unsolicited.port` | `41231` | UDP port for unsolicited Z-Wave frames |
| `zigbee.z3gateway.executable` | `Z3Gateway` | Path to Z3Gateway binary |
| `zigbee.z3gateway.args` | `-n 1 -p /dev/ttyUSB1 -b 115200` | Z3Gateway CLI arguments |
| `zigbee.enabled` | `true` | Set to `false` to disable Zigbee subsystem |
| `matter.server.url` | `ws://localhost:5580/ws` | python-matter-server WebSocket URL |
| `matter.enabled` | `true` | Set to `false` to disable Matter subsystem |
| `camera.enabled` | `true` | Set to `false` to disable camera subsystem |
| `camera.go2rtc.url` | `http://localhost:1984` | go2rtc REST API URL |
| `camera.max.streams` | `8` | Maximum simultaneous go2rtc streams |
| `telemetry.flush.interval.seconds` | `60` | How often the telemetry buffer flushes to MQTT |
| `gateway.auth.username` | `admin` | REST API login username |
| `gateway.auth.password` | `changeme` | REST API login password — **change before LAN exposure** |
| `gateway.auth.jwt.secret` | *(blank)* | JWT signing secret — random generated on startup if blank |
| `gateway.auth.jwt.expiry.hours` | `24` | JWT token lifetime in hours |
| `gateway.creds.path` | `./provisioned.creds` | Path to provisioning credentials |
| `gateway.devices.path` | `./devices` | Filesystem directory for device descriptor overrides |

### Disabling subsystems for local development

```properties
aws.iot.endpoint=disabled
zigbee.enabled=false
matter.enabled=false
```

---

## Credentials file (`provisioned.creds`)

Written by the provisioning flow. Can be JSON (new format) or EDN (legacy format).

**JSON format:**
```json
{
  "name": "gateway-device-001",
  "certPem": "-----BEGIN CERTIFICATE-----\n...",
  "privateKey": "-----BEGIN RSA PRIVATE KEY-----\n...",
  "certId": "abc123",
  "serialNumber": "10000000abcdef01"
}
```

If the file is absent, the gateway starts without MQTT connectivity and logs a warning.

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
| `GET` | `/summary` | — | Gateway status, device list (all protocols), serial number, time |
| `POST` | `/include` | `{protocol, command, blocking}` | Start/stop device inclusion |
| `POST` | `/exclude` | `{protocol, command, blocking}` | Start/stop device exclusion |
| `POST` | `/timezone` | `{timezone}` | Set system timezone |
| `GET` | `/zwave/region` | — | Get Z-Wave RF region |
| `POST` | `/zwave/region` | `{region}` | Set Z-Wave RF region (`0x00` EU, `0x01` US) |
| `POST` | `/zwave/update_network` | — | Refresh node list from zipgateway |
| `GET` | `/tunnel` | — | List running SSH reverse tunnels |
| `POST` | `/tunnel` | `{cmd, ...}` | Manage SSH reverse tunnels |

#### Inclusion example

```json
POST /include
{ "protocol": "zwave", "command": "start", "blocking": false }
```

`protocol` values: `zwave` | `zigbee` | `matter`.
Z-Wave `command` values: `start` | `start_s2` | `stop`. Zigbee/Matter: `start` | `stop`.

For Matter, `start` opens the commissioning window; `stop` closes it. Use `/matter/commission` to pair with a specific code.

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
|--------|------|-------------|
| `GET` | `/:dev` | Get device details |
| `DELETE` | `/:dev` | Remove device |
| `POST` | `/:dev/name` | `{value}` — rename device |
| `POST` | `/:dev/fwd_event` | `{ev}` — subscribe to event forwarding |
| `DELETE` | `/:dev/fwd_event` | `{ev}` — unsubscribe from event forwarding |

#### Z-Wave device commands

| Method | Path | Body | Description |
|--------|------|------|-------------|
| `POST` | `/:dev/lock` | `{value: "lock"\|"unlock"}` | Lock or unlock door |
| `GET` | `/:dev/lock` | — | Get current lock state |
| `GET` | `/:dev/pincode/:slot` | — | Read PIN code from slot |
| `POST` | `/:dev/pincode/:slot` | `{code}` | Set PIN code |
| `DELETE` | `/:dev/pincode/:slot` | — | Clear PIN code slot |
| `GET` | `/:dev/poll_pincodes` | — | Return cached pincodes map |
| `POST` | `/:dev/thermostat` | `{heat?, cool?, mode?}` | Set thermostat setpoint / mode |
| `POST` | `/:dev/switch` | `{value: "on"\|"off"}` | Binary switch |
| `POST` | `/:dev/level` | `{value: 0–99}` | Multilevel dimmer |
| `POST` | `/:dev/setup` | — | Re-run device setup (association, interview) |

#### Zigbee device commands

| Method | Path | Body | Description |
|--------|------|------|-------------|
| `POST` | `/:dev/on` | — | Turn on |
| `POST` | `/:dev/off` | — | Turn off |
| `POST` | `/:dev/lock` | `{value: "lock"\|"unlock"}` | Lock or unlock door |
| `GET` | `/:dev/pincode/:slot` | — | Read PIN code |
| `POST` | `/:dev/pincode/:slot` | `{code, type?}` | Set PIN code |
| `DELETE` | `/:dev/pincode/:slot` | — | Clear PIN code |

#### Matter device commands

| Method | Path | Body | Description |
|--------|------|------|-------------|
| `POST` | `/:dev/on` | — | Turn on (OnOff cluster, endpoint 1) |
| `POST` | `/:dev/off` | — | Turn off |
| `POST` | `/:dev/toggle` | — | Toggle on/off |
| `POST` | `/:dev/level` | `{value: 0–254}` | Set brightness level (LevelControl cluster) |
| `POST` | `/:dev/lock` | `{value: "lock"\|"unlock"}` | Lock or unlock (DoorLock cluster) |
| `POST` | `/:dev/command` | `{endpoint, cluster, command, args?}` | Send arbitrary cluster command |

---

### Matter network commands

| Method | Path | Body | Description |
|--------|------|------|-------------|
| `POST` | `/matter/commission` | `{code}` | Commission a new device by pairing code or QR string |
| `POST` | `/matter/remove` | `{node}` | Remove a commissioned node by node ID |
| `GET` | `/matter/nodes` | — | List all nodes from python-matter-server |

**Commission example:**
```json
POST /matter/commission
{ "code": "MT:Y.K9042C00KA0648G00" }
```

---

### Cameras

Requires [go2rtc](https://github.com/AlexxIT/go2rtc) running locally (`camera.go2rtc.url`).

| Method | Path | Body | Description |
|--------|------|------|-------------|
| `GET` | `/api/v1/cameras` | — | List all registered cameras |
| `POST` | `/api/v1/cameras` | `{type, name, ...}` | Add camera (see below) |
| `POST` | `/api/v1/cameras/discover` | — | Discover ONVIF cameras on LAN (no credentials needed) |
| `DELETE` | `/api/v1/cameras/:dev` | — | Remove camera |
| `GET` | `/api/v1/:dev/snapshot` | — | Proxy JPEG snapshot from go2rtc |

**Add ONVIF camera:**
```json
POST /api/v1/cameras
{ "type": "ONVIF", "name": "Front Door", "ip": "192.168.1.50",
  "username": "admin", "password": "12345" }
```

**Add raw RTSP stream:**
```json
POST /api/v1/cameras
{ "name": "Parking", "src": "rtsp://192.168.1.60:554/stream1" }
```

**Discovery response:**
```json
{ "cameras": [{ "ip": "192.168.1.50", "managementUrl": "http://...", "registered": false }] }
```
Use the `ip` from discovery with `POST /api/v1/cameras` + credentials to register.

---

### Sequences

A sequence is a named list of API calls executed with per-step delays.

| Method | Path | Body | Description |
|--------|------|------|-------------|
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

Cron-style API call scheduling (stub — Phase 7).

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/schedule` | List scheduled jobs |
| `POST` | `/schedule` | Create scheduled job |
| `GET` | `/schedule/:id` | Get job detail |
| `DELETE` | `/schedule/:id` | Delete job |

---

## MQTT protocol

### Topics

| Direction | Topic | Description |
|-----------|-------|-------------|
| Subscribe | `iot/v1/{name}/request/#` | Incoming commands from cloud |
| Subscribe | `$aws/things/{name}/tunnels/notify` | AWS Secure Tunneling notifications |
| Publish | `iot/v1/{name}/response/{requestId}` | Response to a command |
| Publish | `iot/v1/{name}/event/{timestamp}` | Unsolicited device event |

### Message format

**Incoming command:**
```json
{ "path": "GET:/summary", "command": "{}" }
{ "path": "POST:/uuid1/lock", "command": "{\"value\":\"lock\"}" }
```

`path` format: `METHOD:PATH`. The `command` field is a JSON-encoded string containing the request body.

**Response / event:** plain JSON object.

### Event envelope

All unsolicited events share a common envelope:

```json
{
  "type": "zwave" | "zigbee" | "matter",
  "node-id": "<node hex or IEEE addr or Matter node ID>",
  "payload": { "cmd": "<event>", ...fields }
}
```

Notable events:

| `payload.cmd` | When | Fields |
|---|---|---|
| `INTERVIEW_COMPLETE` | Z-Wave descriptor resolved after interview | `descriptor`, `source`, `deviceType` |
| `ReportAttributes` | Zigbee attribute report | `fields` map |
| `OperationEventNotification` | Zigbee lock operation | `OperationEventSource`, `OperationEventCode`, `UserID` |
| `attribute_updated` | Matter attribute changed | `node_id`, `endpoint`, `cluster`, `attribute`, `value` |
| *(any in `fwdEvents`)* | Device sends a matching command | `cmd`, `fields` |

---

## Device summary format

`GET /summary` returns a list of devices. Each device has a consistent flat structure regardless of protocol:

```json
{
  "id":             "uuid",
  "protocol":       "zwave" | "zigbee" | "matter",
  "name":           "Front Door Lock",
  "node":           "0x12" | "0xD46F" | "9",
  "type":           "lock" | "switch" | "dimmer" | "thermostat" | "sensor-contact" | ...,
  "manufacturer":   "ASSA ABLOY",
  "manufacturerId": "0x0129",
  "modelId":        "YRD256",
  "available":      true,
  "status":         "locked" | "unlocked" | "on" | "off" | 85 | null,
  "battery":        72
}
```

- `node`: Z-Wave uses hex (`0x12`), Zigbee uses short NWK address (`0xD46F`), Matter uses decimal node ID (`"9"`)
- `status`: lock → `"locked"`/`"unlocked"`, switch → `"on"`/`"off"`, dimmer → level integer or `"off"`, others → `null`
- `battery`: percentage integer or `null` if unsupported / not yet read
- `available`: whether the node is reachable right now (Matter uses python-matter-server availability flag)

---

## Z-Wave network

The gateway communicates with `zipgateway` over IPv6 UDP (port 4123).

- **Node addresses:** `fd00:bbbb::{nodeHex}` — e.g. node `0x12` → `fd00:bbbb::12`
- **Management plane:** `fd00:aaaa::3` (zipgateway controller address)
- **Unsolicited frames:** gateway listens on UDP port `41231`
- **Codec:** encoding via `zipgateway-codec`, decoding via `zipparser-java` (local JARs in `libs/`)

### Interview flow

1. On startup, `NODE_LIST_GET` is sent to the controller. The `NODE_LIST_REPORT` response lists all active node IDs.
2. For each node without a known descriptor, the gateway fires:
   - `MANUFACTURER_SPECIFIC_GET` — to get manufacturer/product IDs for descriptor matching
   - `NODE_INFO_CACHED_GET` — to get supported command classes
3. Responses arrive asynchronously via the unsolicited listener. The report handler resolves the descriptor, then applies it: lifeline associations, configuration parameters, interview attribute requests, and init tasks.
4. Sleeping nodes queue commands at the controller and respond on their next wake-up. `WAKE_UP_NOTIFICATION` triggers re-processing; `WAKE_UP_NO_MORE_INFORMATION` is sent after all commands are dispatched.
5. A 10-minute duplicate interview guard prevents repeated in-flight interviews for the same node.

### Device descriptor resolution

Descriptors are loaded from two sources (filesystem overrides classpath):

1. `{gateway.devices.path}/zwave/*.json`
2. Classpath `devices/zwave/*.json` (bundled in the jar)

Matching priority:
1. `manufacturerId` + `productTypeId` + `productId` (exact)
2. `manufacturerId` + `productTypeId` (partial)
3. `manufacturerId` only
4. `type` field as a generic fallback (e.g. `"lock"`, `"switch"`)

**Z-Wave descriptor format:**

```json
{
  "protocol": "zwave",
  "type": "lock",
  "manufacturer": "ASSA ABLOY",
  "manufacturerId": "0x0129",
  "devices": [
    { "productType": "0x0002", "productId": "0x0600" }
  ],
  "associations": {
    "1": { "label": "Lifeline", "maxNodes": 5, "isLifeline": true }
  },
  "interview": [
    { "class": "DOOR_LOCK", "cmd": "DOOR_LOCK_OPERATION_GET" },
    { "class": "BATTERY",   "cmd": "BATTERY_GET" }
  ],
  "configuration": [
    { "param": "02", "value": ["FF"] },
    { "param": "03", "value": null }
  ],
  "init": [
    { "fn": "poll-pincodes", "params": { "start": 1, "end": 15 } },
    { "fn": "config-time",   "params": {} }
  ],
  "metadata": {
    "wakeup": "Touch the keypad to wake the lock",
    "inclusion": "...",
    "manual": "https://..."
  }
}
```

Supported `init` functions: `poll-pincodes` (`start`, `end`), `config-time` (syncs device clock to gateway time).

---

## Zigbee network

The gateway spawns `Z3Gateway` as a subprocess and communicates via stdin/stdout.

- Commands are written line-by-line to stdin
- Responses are parsed from stdout using regex patterns
- Parsed messages are dispatched to async hooks (`CompletableFuture`) or to `ZigbeeReportHandler`
- The hook system supports timeouts (default 30 s)

### Interview flow

1. On startup, `plugin device-table print` is run. The NWK short address (e.g. `0x96E4`) is stored in `device.node`; the IEEE EUI-64 is the stable DB key.
2. Devices without a resolved descriptor are interviewed: Basic cluster attributes `ManufacturerName` and `ModelIdentifier` are read to match a descriptor file.
3. Once matched: bindings, interview attribute reads, and reporting configuration are applied asynchronously.
4. Sleeping ZEDs that miss startup are re-interviewed on their first unsolicited frame. A 10-minute duplicate guard applies.
5. Trust-center join events trigger an interview for newly joined devices.

### Device descriptor resolution

Descriptors are loaded from two sources (filesystem overrides classpath):

1. `{gateway.devices.path}/zigbee/*.json`
2. Classpath `devices/zigbee/*.json` (bundled in the jar)

Matching: both `manufacturer` and `modelId` must match (case-insensitive).

**Zigbee descriptor format:**

```json
{
  "protocol": "zigbee",
  "type": "lock",
  "manufacturer": "Yale",
  "modelId": "YRD226 TSDB",
  "interview": [
    { "cluster": "PowerConfiguration", "attributes": ["BatteryPercentageRemaining"] },
    { "cluster": "DoorLock",           "attributes": ["LockState"] }
  ],
  "bindings": [
    { "sep": "01", "dep": "01", "cluster": "0x0101" }
  ],
  "reporting": [
    { "sep": "01", "dep": "01", "cluster": "DoorLock", "attribute": "LockState",
      "minTime": 1, "maxTime": 300, "delta": "01" }
  ],
  "init": [
    { "fn": "poll-pincodes", "params": { "start": 1, "end": 10 } }
  ],
  "fwdEvents": ["OperationEventNotification"]
}
```

---

## Matter network

The gateway connects to [python-matter-server](https://github.com/home-assistant-libs/python-matter-server) via its WebSocket JSON-RPC API (`ws://<host>:5580/ws`).

- `MatterInterface` maintains a persistent WebSocket connection with automatic reconnect (10 s interval)
- On connect, `start_listening` is sent; the response contains all currently commissioned nodes
- Spontaneous events (`node_added`, `node_removed`, `attribute_updated`) are dispatched to `MatterReportHandler`
- All outgoing commands use `CompletableFuture` hooks keyed by `message_id`
- The WebSocket buffer is set to 10 MB to accommodate large `start_listening` responses

### Startup flow

1. `MatterInterface` connects and sends `start_listening`; the response populates an in-memory node cache
2. `MatterReportHandler.init()` registers itself as the event handler and triggers `onInitialNodes()` immediately if the cache is already populated (handles the common case where WebSocket connects before Spring finishes initializing all beans)
3. For each node, `MatterReportHandler.setup()` checks the DB, creates a `Device` record if absent (protocol=`matter`, node=decimal node ID string), and extracts name/manufacturer/model from the Basic Information cluster (`0/40/*`)
4. Device type is inferred from the Descriptor cluster (`N/29/0`) and stored on the `Device`

### Device type inference

Device types are inferred from Matter device type IDs in the Descriptor cluster. Known mappings:

| Matter Device Type ID | Logical type |
|-----------------------|-------------|
| 16, 259, 266 | `switch` |
| 17, 257, 260, 267, 269 | `dimmer` |
| 514 | `lock` |
| 768 | `thermostat` |
| 262, 1028 | `sensor-occupancy` |
| 263, 1026 | `sensor-contact` |
| 770, 1029 | `sensor-temperature` |

### Attribute path format

Matter attributes are stored and accessed using the path `endpoint/cluster/attribute`:

- `0/40/1` — Basic Information: VendorName
- `0/40/2` — Basic Information: VendorID
- `0/40/3` — Basic Information: ProductName
- `1/6/0` — OnOff cluster: OnOff attribute
- `1/8/0` — LevelControl cluster: CurrentLevel
- `1/257/0` — DoorLock cluster: LockState (1=unlocked, 2=locked)
- `N/29/0` — Descriptor cluster: DeviceTypeList

---

## Database

SQLite at `./gateway.db`. Schema managed by Hibernate (`ddl-auto=update`).

| Table | Description |
|-------|-------------|
| `devices` | Z-Wave, Zigbee, and Matter devices with attributes, pincodes, fwdEvents |
| `sequences` | Named device command sequences |

Device attributes are stored as nested JSON: `{ cluster → { attrName → value } }`.

Matter devices use `device.node` as the decimal string of the Matter node ID (e.g. `"9"`).

---

## Project structure

```
src/main/java/uy/plomo/gateway/
├── GatewayApplication.java
├── api/
│   ├── GatewayApiService.java      # central routing (REST + MQTT)
│   ├── NetworkController.java
│   ├── DeviceController.java
│   ├── CameraRestController.java
│   ├── SequenceController.java
│   └── ScheduleController.java
├── auth/
│   ├── AuthController.java         # POST /auth/login
│   ├── JwtService.java             # token generation and validation
│   ├── JwtFilter.java              # Bearer token filter (OncePerRequestFilter)
│   └── SecurityConfig.java         # Spring Security configuration
├── camera/
│   ├── CameraController.java       # camera commands + summary view
│   ├── CameraService.java          # go2rtc REST API wrapper
│   └── OnvifDiscoveryService.java  # WS-Discovery UDP multicast probe
├── config/
│   ├── AppConfig.java              # loads provisioned.creds
│   └── ProvisionedCreds.java
├── device/
│   ├── Device.java
│   ├── DeviceRepository.java
│   └── DeviceService.java
├── matter/
│   ├── MatterController.java       # Matter commands + summary view
│   ├── MatterException.java
│   ├── MatterInterface.java        # WebSocket client to python-matter-server
│   ├── MatterMessage.java          # JSON-RPC message DTO
│   ├── MatterNode.java             # live node snapshot record
│   ├── MatterProtocol.java         # cluster/attribute name maps, fwdEvent matching
│   └── MatterReportHandler.java    # event handler, auto device creation
├── mqtt/
│   ├── AsyncCommandDispatcher.java # @Async offload of MQTT commands off event loop
│   ├── GatewayExecutorConfig.java  # gw-cmd-* thread pool (core=10, max=20)
│   ├── MqttDispatcher.java
│   └── MqttService.java            # AWS IoT MQTT5 client
├── platform/
│   └── PlatformService.java        # serial number, timezone, SSH tunnels
├── sequence/
│   ├── Sequence.java
│   ├── SequenceRepository.java
│   └── SequenceService.java
├── telemetry/
│   └── TelemetryBuffer.java        # batched event buffer → MQTT flush
├── util/
│   └── JsonConverter.java          # JPA converters for JSON columns
├── zigbee/
│   ├── ZigbeeController.java
│   ├── ZigbeeInterface.java        # Z3Gateway subprocess
│   ├── ZigbeeMessage.java
│   ├── ZigbeeProtocol.java
│   └── ZigbeeReportHandler.java
└── zwave/
    ├── UnsolicitedListener.java    # UDP server port 41231
    ├── ZWaveController.java
    ├── ZWaveInterface.java         # UDP client to zipgateway
    └── ZWaveReportHandler.java
libs/
    zipgateway-codec-*.jar          # frame encoding (local)
    zipparser-java-*.jar            # frame decoding (local)
```
