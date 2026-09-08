# Device summary format reference: legacy vs. HAv1

This document is for whoever/whatever is updating the UI (mobile app / cloud dashboard) to
support the new device summary shape. **The fleet is mixed** — gateways on older firmware still
return the legacy flat format, newer ones return HAv1. The UI must detect which one it's looking
at per response and handle both; this is not a one-time migration.

## How to tell them apart

```json
{ "gw_id": "...", "fw_version": "0.1", "version": "HAv1", "time": "...", "timezone": "...", "devices": { ... } }
```

- **`version` field present and equal to `"HAv1"`** → new grouped format (below).
- **`version` field absent** → legacy flat format (below). There is no `"version":"legacy"` or
  similar marker — absence of the field *is* the signal.

Both are returned from the same endpoints:
- `GET /api/v1/summary` — all devices.
- `GET /api/v1/{dev}` — one device by id.
- Same MQTT envelope (`{"path":"GET:/summary", ...}`) — the format distinction applies there too,
  it isn't REST-only.

---

## Legacy format (no `version` field)

One entry per **Home Assistant entity** — a physical device with several entities (e.g. a sensor
reporting occupancy + battery + illuminance as three separate HA entities) shows up as three
separate, unrelated entries with no way to tell they belong together.

```json
{
  "id":             "3f9a1c7e-....",
  "protocol":       "ha",
  "name":           "Front Door Lock",
  "node":           "lock.front_door",
  "type":           "lock",
  "manufacturer":   "ASSA ABLOY",
  "manufacturerId": null,
  "modelId":        null,
  "available":      true,
  "status":         "locked",
  "battery":        null
}
```

- `type`: one of `lock`, `switch`, `dimmer`, `thermostat`, `cover`, `fan`, `camera`,
  `sensor-contact`, `sensor-occupancy`, `sensor-temperature`, `sensor-humidity`,
  `sensor-illuminance`, `sensor-battery`, `sensor-binary`, `sensor-generic`.
- `status`: a single scalar — Home Assistant's raw state string for most types
  (`"locked"`/`"unlocked"`, `"on"`/`"off"`, ...), a 0–99 integer for `dimmer` brightness,
  `"streaming"`/`"offline"` for `camera`.
- `battery`: always `null` in practice — never resolved in this format (see HAv1 below for why).
- `manufacturer`/`manufacturerId`/`modelId`: usually `null` — not reliably resolved in this format.
- **Commands**: `POST /api/v1/{id}/{cmd}` where `cmd` is one of a small fixed set assumed to match
  the entity's own domain: `on`, `off`, `toggle`, `switch` (body `{value: "on"|"off"}`), `level`
  (body `{value: 0-99}`, lights only), `lock` (body `{value: "lock"|"unlock"}`), `thermostat`
  (body `{heat, cool, mode}`), `pincode` (GET/POST/DELETE with a slot sub-id), `service` (escape
  hatch: `{domain, service, data?, entity_id?}`), `name`, `fwd_event`.

---

## HAv1 format (`"version":"HAv1"`)

One entry per **physical Home Assistant device** — every entity belonging to the same HA
`device_id` is folded into one entry. A device with no HA `device_id` (helpers, some templates)
gets its own entry keyed by its own row id (a "group of one" — looks just like a single-entity
device, no special-casing needed on the UI side).

```json
{
  "id":           "3f9a1c7e64b04d7c9a3f8a1c7e64b04d",
  "protocol":     "ha",
  "name":         "Develco Products A/S MOSZB-140",
  "type":         "sensor-occupancy",
  "manufacturer": "Develco Products A/S",
  "modelId":      "MOSZB-140",
  "areaId":       null,
  "areaName":     null,
  "available":    true,
  "status": {
    "occupancy_1":  "off",
    "occupancy_2":  "off",
    "tamper":       "off",
    "illuminance":  "42",
    "battery":      "87",
    "temperature":  "21.3"
  },
  "actions": []
}
```

```json
{
  "id":     "aa22f2d767a385959f14e0477154bd60",
  "name":   "Z-Wave Plus Thermostat",
  "type":   "thermostat",
  "status": { "climate": "heat", "temperature": "21.0", "battery": "97.0" },
  "actions": ["set_temperature", "set_hvac_mode"]
}
```

### Fields

- **`id`**: the HA device_id (or the fallback row id for a group-of-one). This is what goes in
  the URL for `GET`/`POST /api/v1/{id}/...` — **not** an HA `entity_id` anymore.
- **`type`/`name`/`available`**: taken from the group's "primary" entity. Home Assistant itself
  has no concept of a primary entity for a multi-entity device — the gateway picks one by domain
  priority: `lock > climate > switch > light > cover > fan > camera > binary_sensor > sensor`
  (first match wins; the same type vocabulary as the legacy format).
- **`manufacturer`/`modelId`/`areaId`/`areaName`**: from HA's device/area registries. Can be
  `null` (this instance has no areas configured, for example — don't assume non-null).
- **`status`**: an object, not a scalar. One entry per entity in the group. Key is a short label:
  usually the entity's `device_class` (`battery`, `illuminance`, `temperature`, `occupancy`, ...),
  or the HA domain itself for entities with no device_class distinction (`lock`, `climate`,
  `switch`, ...). Value is HA's raw state string, same as legacy `status` was for a single entity.
  **Collision rule**: if two entities in the same group would produce the same label (real
  example above: two `occupancy`-classed binary sensors on one physical device), both are kept —
  sorted by their underlying HA `entity_id` and suffixed `_1`, `_2`, ... in that order. This is
  deterministic across requests, not order-of-arrival-dependent. Don't assume a label is always
  unsuffixed — check for a family of `label`, `label_1`, `label_2`, ... when in doubt.
- **`actions`**: a list of strings, each a valid `{action}` for `POST /api/v1/{id}/{action}` on
  *this specific device* — computed from what its entities actually support (see table below),
  not a fixed list per `type`. **Empty for read-only devices** (a device made only of `sensor`/
  `binary_sensor` entities, like the MOSZB-140 above). Same `_1`/`_2` collision suffixing as
  `status` applies here too — e.g. a device with 3 switch entities produces `turn_on_1`,
  `turn_on_2`, `turn_on_3` instead of one `turn_on` that only reaches one of them silently.

### Actions reference

| Action | Body | Notes |
|---|---|---|
| `turn_on` / `turn_off` / `toggle` | — | switch, light, fan |
| `set_level` | `{ "value": 0-99 }` | dimmable lights only — check `actions` for whether this device has it, not the `type` |
| `lock` / `unlock` | — | |
| `pincode` | `GET`/`POST { code }`/`DELETE` with a slot sub-id (`/api/v1/{id}/pincode/{slot}`) | unchanged from legacy |
| `set_temperature` | `{ "heat": n }` and/or `{ "cool": n }`, or `{ "temperature": n }` | |
| `set_hvac_mode` | `{ "mode": "heat"\|"cool"\|"auto"\|"off"\|... }` | only present if the device has more than one HVAC mode |
| `open` / `close` / `stop` | — | covers |
| `set_position` | `{ "position": 0-100 }` | covers |
| `set_speed` | `{ "percentage": 0-100 }` | fans |
| `oscillate` | `{ "value": true\|"on" }` | fans |
| `set_direction` | `{ "direction": "..." }` | fans |
| `service` | `{ "domain", "service", "data"?, "entity_id"? }` | generic escape hatch, always available on every device |
| `name` | `{ "value": "..." }` | rename, always available |
| `fwd_event` | `POST { "ev": "..." }` / `DELETE { "ev": "..." }` / `GET` | telemetry forwarding config, always available |

Requesting an action not in that device's `actions` list returns an explicit error instead of a
500 or silent no-op:
```json
{ "error": "unknown action 'foo' for device 3f9a1c7e64b04d7c9a3f8a1c7e64b04d" }
```

### Cameras

Camera devices (`type: "camera"`) keep the exact same shape they had before HAv1 — grouping
doesn't touch them, since a camera's companion entities (if any) aren't meaningful to fold into
`status`/`actions` the way a sensor cluster is.

### What did NOT change

- The URL shape: `GET /api/v1/summary`, `GET`/`POST/DELETE /api/v1/{id}/{action}` — same routes,
  same HTTP verbs. Only the `{id}` semantics (entity → device group) and the response body shape
  changed.
- Auth, MQTT envelope, telemetry event format (`entity_added` events) — unaffected.
