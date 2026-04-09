# TemiApplication

Android app for a **Temi** robot that listens to **Firebase Realtime Database** at the **database root** (`location`, `status`, `orders`, …), drives **Charging → Pantry → Gaming** flows, speaks at delivery, and returns **home to Charging** after the guest taps **OK** at the gaming zone (or goes to **Pantry** again if more orders are pending). The manifest registers a **Temi skill** named *Temi Beverage Delivery*.

## Customer web app (default ordering UI)

- **`index.html`** (project root): customer **QR / mobile web** interface — **voice intro** (Web Speech API after tap), **5 beverages + 5 snacks** (Coke Can, Water, Living Labs rack snacks, etc.), **picture** (emoji or optional `imageUrl` from Firebase), **+ / −** quantity, **Submit**.
- On submit: creates `orders/{orderId}` with `status: "pending"`, sets `admin/notification_pending` to `true` and `admin/latest_order_id` for the admin app.
- **Waiting** overlay (spinner) until admin sets the order to **`ongoing`** or **`accepted`** (or `complete`); then shows **✓** and *“Order accepted, Delivery is on the way!”*
- **Inventory seed:** import **`database/inventory-seed.json`** into Realtime Database (or merge `inventory` + `admin` nodes) so stock matches production; the page also **falls back** to embedded defaults if `inventory` is unreadable.

## Inventory database (Firebase)

- **10 SKUs** in `database/inventory-seed.json`: **5 beverages** (includes **Coke Can**, **Water**) and **5 snacks** (including **Living Labs rack** items).
- Each item: `name`, `emoji`, optional `imageUrl`, `category` (`beverage` | `snack`), `quantity`, optional `customizable`.

## Three-location map (conceptual)

| Location (code constants) | Role |
|---------------------------|------|
| `Charging` (`LOC_CHARGING`) | Home / idle / dock — must match the **exact** name in Temi’s map |
| `Pantry` (`LOC_PANTRY`) | Stock / load tray |
| `Gaming` (`LOC_GAMING`) | Customer pickup / delivery stop |

**Important:** Edit `LOC_CHARGING`, `LOC_PANTRY`, and `LOC_GAMING` in `MainActivity.java` if your map uses different labels (e.g. "Charging Area", "Gaming Zone"). On robot ready, logcat prints **`TEMI_LOCATIONS`** with `robot.getLocations()` — spelling and capitalization must match.

## Intended operator flow

1. **Idle** — Temi at **Charging**; `status` = `idle`, `location` = `none`.
2. **Order placed** — External system sets `location` = `Pantry` → Temi goes **Charging → Pantry**.
3. **Arrived at Pantry** — App sets `status` = `arrived_pantry`, clears `location` to `none`, shows waiting UI. Staff loads tray; admin sets `location` = `Gaming` when ready.
4. **Arrived at Gaming** — App sets `status` = `arrived_gaming`, speaks TTS, shows **OK** button, clears `location` to `none`.
5. **Guest taps OK** — App reads `orders` once: if **any order still needs service** (see below), sets `location` = `Pantry`; else sets `location` = `Charging` to return home.
6. **Arrived at Charging** — App sets `status` = `idle`, `location` = `none`, idle UI.

There is **no** automatic 10s return to Pantry; **Gaming** exit is controlled by **OK** or by external Firebase writes.

## Firebase (flat root)

```
root/
├── location: "none" | "Pantry" | "Gaming" | "Charging" | ...
├── status: "idle" | "arrived_pantry" | "arrived_gaming" | ...
├── admin/
│   └── notification_pending: false
├── inventory/
│   └── ... (item SKUs)
└── orders/
    └── {orderId}/
        └── status: "pending" | "ongoing" | "accepted" | "complete" | "delivered" | "cancelled" | ...
```

- **`location` / `status`:** Same as before; app reads/writes both.
- **`orders`:** Used when the user taps **OK** at Gaming. The app treats an order as **still needing service** if `status` is missing, empty, or anything other than `delivered` / `cancelled` (case-insensitive).
- **Order coordination:** Before the guest taps **OK**, the **current** order should be marked **`delivered`** (or removed) in Firebase so `orders` only reflects **new** work. Otherwise the same `pending` order will keep sending Temi back to **Pantry**.

## Architecture

| Piece | Role |
|--------|------|
| `MainActivity` | Firebase listeners, Temi listeners, navigation, TTS, OK button |
| Firebase Realtime Database | `location`, `status`, `orders` |
| Temi `Robot` API | `goTo`, `stopMovement`, `tiltAngle`, `speak(TtsRequest)`, kiosk, listeners |

## Navigation behavior (summary)

- **Idle / ignore:** `location` is `null`, `none`, or same as last handled command while not moving.
- **New target:** If the name exists in `robot.getLocations()`, after **3 s** delay → `goTo(target)`.
- **Arrival (`complete`):** Branch on **Pantry** / **Gaming** / **Charging** (see code); set `location` to `none` where appropriate; **no** auto timer to Pantry.
- **Failure (`abort` / `reject`):** After **7 s**, clear `location` then **500 ms** later retry the same destination.

## Temi integration

- **Permissions:** `INTERNET`, `MAP`, `LOCATIONS`, `SETTINGS`, `NAVIGATION` (`com.robotemi.permission.*`).
- **Metadata:** `com.robotemi.sdk.metadata.SKU` = `v1`; skill *Temi Beverage Delivery*.
- **Activity:** `singleInstance`, kiosk on ready, keep screen on, hide top bar.

## UI

- `activity_main.xml`: dark background (`#0F172A`), status line, subtitle, **OK** (visible only at Gaming after arrival).

## Project layout

```
app/
  src/main/java/com/infy/temiapplication/MainActivity.java
  src/main/AndroidManifest.xml
  src/main/res/layout/activity_main.xml
  google-services.json
```

## Build and run

- **JDK:** 11 · **SDK:** `compileSdk` / `targetSdk` 34, `minSdk` 24.
- **Firebase:** Realtime Database enabled; rules must allow the app to read/write `location`, `status`, and `orders` as needed.
- **Device:** Deploy to a **Temi** for full behavior.

## Tests

- `ExampleInstrumentedTest` asserts package `com.infy.temiapplication`.
