# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Install on connected Temi device
./gradlew installDebug

# Run instrumented tests (requires connected device)
./gradlew connectedAndroidTest

# Run unit tests
./gradlew test
```

**Deploy target:** Must be a Temi robot. `adb connect <temi-ip>` then `./gradlew installDebug`.

## Architecture

Three loosely coupled components share a single Firebase Realtime Database at the project root:

| Component | File | Role |
|-----------|------|------|
| Temi Android app | `app/src/main/java/com/infy/temiapplication/MainActivity.java` | Reads `location`, drives robot, writes `status` / `robot_state` |
| Customer web UI | `index.html` | QR-code ordering page; creates `orders/`, decrements `inventory/` |
| Admin web UI | `admin.html` | Firebase Auth (Email/Password); manages orders and sends Temi manually |

### Firebase schema (flat root)

```
root/
├── location          "none"|"pantry"|"gaming"|"home base"   ← admin/web writes; Android reads
├── status            "idle"|"arrived_pantry"|"arrived_gaming"|...  ← Android writes; web reads
├── robot_state       "idle"|"moving"|"arrived_pantry"|"arrived_gaming"|"blocked"
├── active_order_id   ""|"{orderId}"
├── admin/
│   ├── notification_pending  bool
│   └── latest_order_id       string
├── inventory/{sku}/  name, emoji, imageUrl?, category, quantity, customizable?
└── orders/{orderId}/ status, items{}, sessionId, inventoryDebitedAt?, acceptedAt?, startedAt?, completedAt?
```

### Android navigation state machine

`MainActivity` listens on `location`. When a non-`none` value arrives and the robot is not already moving:

1. **3 s delay** → `robot.goTo(location)` (stale timer cancelled if new command arrives)
2. On `complete`: branch on `LOC_PANTRY` / `LOC_GAMING` / `LOC_CHARGING`, write `status` + `robot_state`, clear `location = "none"`
3. On `abort`/`reject`: **7 s** → clear `location`, then **500 ms** → re-set `location` to retry

**Location constants** (`MainActivity.java:39–41`) must exactly match names saved in the Temi map:
- `LOC_CHARGING = "home base"`
- `LOC_PANTRY   = "pantry"`
- `LOC_GAMING   = "gaming"`

Check actual map names via Logcat tag `TEMI_LOCATIONS` on robot ready.

### Order lifecycle

```
pending → accepted → ongoing → complete
```

- `pending`: customer submitted; inventory already decremented (`inventoryDebitedAt` set)
- `accepted`: admin accepted; no inventory change for orders with `inventoryDebitedAt`
- `ongoing`: admin started trip; `active_order_id` set; Temi heads to pantry
- `complete`: guest pressed OK on Temi at gaming; `completedAt` set; `active_order_id` cleared

`orderNeedsService()` in `MainActivity` treats any status other than `delivered`, `complete`, `cancelled` as needing another pantry run.

### Inventory transactions

Both `index.html` and `admin.html` use Firebase transactions (`runTransaction`) to atomically read-modify-write `inventory/{sku}/quantity`. The customer web app adjusts by **delta** (new qty − old qty) on order modify so stock is not double-decremented.

## Key configuration points

- **Firebase project**: `google-services.json` at `app/google-services.json`; Realtime Database rules must allow read/write on `location`, `status`, `robot_state`, `active_order_id`, `orders`, `inventory`, `admin/`
- **Admin auth**: Firebase Console → Authentication → Email/Password; create admin account
- **Inventory seed**: import `database/inventory-seed.json` into Realtime Database root (merge `inventory` + `admin` nodes)
- **SDK**: Temi SDK `com.robotemi:sdk:1.137.1`; compileSdk/targetSdk 34, minSdk 24, JDK 11

## Web files

`index.html` and `admin.html` are standalone HTML files (no build step). They embed Firebase SDK via CDN and Bootstrap 5.3. Host them as static files or open directly from the filesystem for local testing. Firebase config is inline in each file's `<script>` block.
