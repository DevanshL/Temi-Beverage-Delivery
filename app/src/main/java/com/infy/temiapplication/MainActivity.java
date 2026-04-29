package com.infy.temiapplication;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.robotemi.sdk.Robot;
import com.robotemi.sdk.TtsRequest;
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener;
import com.robotemi.sdk.listeners.OnRobotReadyListener;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements
        OnRobotReadyListener,
        OnGoToLocationStatusChangedListener {

    // ── Location constants ──
    private static final String LOC_CHARGING = "home base";
    private static final String LOC_PANTRY   = "pantry";
    private static final String LOC_GAMING   = "gaming";
    private static final String LOC_GAMING1  = "gaming1";
    private static final String LOC_GAMING2  = "gaming2";
    private static final String LOC_STAGING  = "staging";

    // ── Patrol config ──
    private static final List<String> PATROL_STOPS = Arrays.asList(
            LOC_GAMING, LOC_GAMING1, LOC_GAMING2
    );
    private static final int PATROL_DWELL_SEC = 45;

    // ── Battery config ──
    private static final int  BATTERY_LOW_PCT        = 30;
    private static final long BATTERY_CHECK_INTERVAL = 60_000L;

    // ── Views ──
    private TextView statusText;
    private TextView txtWaiting;
    private TextView txtCountdown;

    // ── Firebase refs ──
    private DatabaseReference locRef;
    private DatabaseReference statusRef;
    private DatabaseReference ordersRef;
    private DatabaseReference robotStateRef;
    private DatabaseReference currentDeliveringRoundRef;
    private DatabaseReference roundsRef;
    private DatabaseReference patrolIndexRef;      // patrol_stop_index
    private DatabaseReference patrolInProgressRef; // patrol_in_progress

    // ── SDK ──
    private Robot robot;

    // ── Nav state ──
    private boolean isMoving    = false;
    private String  lastCommand = "";

    // ── Handlers ──
    private final Handler navHandler     = new Handler(Looper.getMainLooper());
    private final Handler batteryHandler = new Handler(Looper.getMainLooper());

    @Nullable private Runnable pendingGoToRunnable;
    private final Runnable batteryCheckRunnable = this::checkBatteryAndAct;

    // ── Patrol state (mirrored in Firebase) ──
    private int      patrolStopIndex    = 0;
    private boolean  patrolInProgress   = false;
    private int      countdownValue     = 0;
    private Runnable countdownRunnable  = null;
    private String   currentPatrolRound = "";

    // ── Charging state ──
    private boolean  isAtChargingStation = false;

    // ─────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText   = findViewById(R.id.statusText);
        txtWaiting   = findViewById(R.id.txtWaiting);
        txtCountdown = findViewById(R.id.txtCountdown);

        robot = Robot.getInstance();

        FirebaseDatabase db = FirebaseDatabase.getInstance();
        locRef                    = db.getReference("location");
        statusRef                 = db.getReference("status");
        ordersRef                 = db.getReference("orders");
        robotStateRef             = db.getReference("robot_state");
        currentDeliveringRoundRef = db.getReference("current_delivering_round");
        roundsRef                 = db.getReference("rounds");
        patrolIndexRef            = db.getReference("patrol_stop_index");
        patrolInProgressRef       = db.getReference("patrol_in_progress");

        locRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String target = snapshot.getValue(String.class);
                if (target == null || target.equalsIgnoreCase("none")) {
                    lastCommand = "";
                    return;
                }
                if (!target.equalsIgnoreCase(lastCommand) && !isMoving) {
                    checkAndNavigate(target);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        robot.addOnRobotReadyListener(this);
        robot.addOnGoToLocationStatusChangedListener(this);
    }

    @Override
    protected void onStop() {
        robot.removeOnRobotReadyListener(this);
        robot.removeOnGoToLocationStatusChangedListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (pendingGoToRunnable != null) {
            navHandler.removeCallbacks(pendingGoToRunnable);
            pendingGoToRunnable = null;
        }
        stopCountdown();
        stopBatteryMonitor();
        super.onDestroy();
    }

    // ─────────────────────────────────────────────────────────────
    // onRobotReady — crash recovery
    // ─────────────────────────────────────────────────────────────

    @Override
    public void onRobotReady(boolean ready) {
        if (!ready) return;

        List<String> locs = robot.getLocations();
        Log.d("TEMI_LOCATIONS", locs != null ? locs.toString() : "null");
        robot.hideTopBar(true);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        robot.requestToBeKioskApp();

        // Read robot_state first
        robotStateRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                String st = snap.getValue(String.class);

                // Mid-trip to pantry — locRef listener resumes automatically
                if ("moving".equals(st) || "arrived_pantry".equals(st)) {
                    Log.d("Nav", "onRobotReady: mid-delivery state=" + st);
                    return;
                }

                if ("arrived_gaming".equals(st)) {
                    // Could be mid-patrol crash — check Firebase patrol state
                    recoverPatrolState();
                    return;
                }

                // idle / staging / blocked — check rounds
                checkClosedRoundsAndDecide();
            }
            @Override
            public void onCancelled(@NonNull DatabaseError e) {
                statusRef.setValue("idle");
                locRef.setValue("none");
                robotStateRef.setValue("idle");
            }
        });
    }

    /**
     * Read patrol_in_progress + patrol_stop_index + current_delivering_round
     * from Firebase to resume patrol from exact stop where crash happened.
     */
    private void recoverPatrolState() {
        patrolInProgressRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot progSnap) {
                Boolean inProgress = progSnap.getValue(Boolean.class);
                boolean wasPatrolling = Boolean.TRUE.equals(inProgress);

                if (!wasPatrolling) {
                    // Not mid-patrol — round done, go decide next action
                    Log.d("Nav", "Recovery: patrol not in progress — deciding next");
                    runPostGoodbyeOrdersDecision();
                    return;
                }

                // Was patrolling — read which stop and round ID
                patrolIndexRef.addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot idxSnap) {
                        Integer savedIndex = idxSnap.getValue(Integer.class);
                        int resumeIndex = (savedIndex != null) ? savedIndex : 0;

                        currentDeliveringRoundRef.addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override
                            public void onDataChange(@NonNull DataSnapshot roundSnap) {
                                String rid = roundSnap.getValue(String.class);
                                currentPatrolRound = (rid != null) ? rid.trim() : "";

                                Log.d("Patrol", "Recovery: resuming at stop=" + resumeIndex
                                        + " round=" + currentPatrolRound);

                                patrolStopIndex  = resumeIndex;
                                patrolInProgress = true;

                                String resumeStop = PATROL_STOPS.get(
                                        Math.min(resumeIndex, PATROL_STOPS.size() - 1));

                                if (resumeIndex == 0) {
                                    // Crashed at gaming — restart from gaming
                                    runOnUiThread(() -> startDwellCountdown());
                                } else {
                                    // Crashed at gaming1 or gaming2 — navigate there
                                    startNavigationSequence(resumeStop);
                                }
                            }
                            @Override
                            public void onCancelled(@NonNull DatabaseError e) {
                                runPostGoodbyeOrdersDecision();
                            }
                        });
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError e) {
                        runPostGoodbyeOrdersDecision();
                    }
                });
            }
            @Override
            public void onCancelled(@NonNull DatabaseError e) {
                runPostGoodbyeOrdersDecision();
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // Navigation
    // ─────────────────────────────────────────────────────────────

    private void checkAndNavigate(String target) {
        List<String> known = robot.getLocations();
        if (known == null) {
            statusRef.setValue("Error: Robot locations not ready");
            return;
        }
        for (String loc : known) {
            if (loc.equalsIgnoreCase(target)) {
                startNavigationSequence(loc);
                return;
            }
        }
        Log.e("Nav", "Location not found: " + target);
        statusRef.setValue("Error: Location not found");
    }

    private void startNavigationSequence(String location) {
        stopBatteryMonitor();
        stopCountdown();

        isMoving    = true;
        lastCommand = location;

        robot.stopMovement();
        robot.tiltAngle(0);

        statusRef.setValue("Preparing to move...");
        robotStateRef.setValue("moving");

        runOnUiThread(() -> {
            statusText.setText(R.string.status_preparing);
            txtWaiting.setVisibility(View.GONE);
            txtCountdown.setVisibility(View.GONE);
        });

        if (pendingGoToRunnable != null) navHandler.removeCallbacks(pendingGoToRunnable);
        pendingGoToRunnable = () -> {
            pendingGoToRunnable = null;
            Log.d("Nav", "goTo: " + location);
            robot.goTo(location);
            statusRef.setValue("Moving to " + location);
            runOnUiThread(() -> statusText.setText(getString(R.string.status_moving_to, location)));
        };
        navHandler.postDelayed(pendingGoToRunnable, 3000);
    }

    @Override
    public void onGoToLocationStatusChanged(
            @NonNull String location,
            @NonNull String status,
            int id,
            @NonNull String desc) {
        runOnUiThread(() -> {
            Log.d("NavStatus", location + " → " + status);
            if (status.equalsIgnoreCase("complete")) {
                handleArrival(location);
            } else if (status.equalsIgnoreCase("abort") || status.equalsIgnoreCase("reject")) {
                handleNavigationFailure(location);
            }
        });
    }

    private void handleArrival(String location) {
        isMoving    = false;
        lastCommand = "";
        robot.stopMovement();

        // ── Pantry ──
        if (equalsLoc(location, LOC_PANTRY)) {
            isAtChargingStation = false;
            clearPatrolState(); // ✅ FIX BUG2: clear ghost patrol state
            statusRef.setValue("arrived_pantry");
            robotStateRef.setValue("arrived_pantry");
            locRef.setValue("none");
            robot.cancelAllTtsRequests();
            robot.speak(TtsRequest.create("Arrived at pantry. Waiting for staff.", false));
            runOnUiThread(() -> {
                statusText.setText(R.string.status_arrived_pantry);
                txtWaiting.setText(R.string.subtitle_waiting_pantry);
                txtWaiting.setVisibility(View.VISIBLE);
                txtCountdown.setVisibility(View.GONE);
            });
            return;
        }

        // ── Gaming patrol stops ──
        if (isPatrolStop(location)) {
            handlePatrolArrival(location);
            return;
        }

        // ── Home base ──
        if (equalsLoc(location, LOC_CHARGING)) {
            isAtChargingStation = true;
            clearPatrolState();
            locRef.setValue("none");
            statusRef.setValue("idle");
            robotStateRef.setValue("idle");
            runOnUiThread(() -> {
                statusText.setText(R.string.status_idle_home);
                txtWaiting.setText(R.string.subtitle_idle);
                txtWaiting.setVisibility(View.VISIBLE);
                txtCountdown.setVisibility(View.GONE);
            });
            startBatteryMonitor(); // Monitor battery to auto-resume staging
            return;
        }

        // ── Staging ──
        if (equalsLoc(location, LOC_STAGING)) {
            isAtChargingStation = false;
            clearPatrolState(); // ✅ FIX BUG2: clear ghost patrol state
            statusRef.setValue("idle_staging");
            robotStateRef.setValue("idle");
            locRef.setValue("none");
            runOnUiThread(() -> {
                statusText.setText(R.string.status_staging);
                txtWaiting.setText(R.string.subtitle_staging);
                txtWaiting.setVisibility(View.VISIBLE);
                txtCountdown.setVisibility(View.GONE);
            });
            startBatteryMonitor();
            return;
        }

        // ── Generic ──
        isAtChargingStation = false;
        locRef.setValue("none");
        statusRef.setValue("Arrived at " + location);
        runOnUiThread(() -> {
            statusText.setText(getString(R.string.status_arrived_generic, location));
            txtWaiting.setVisibility(View.GONE);
        });
    }

    // ─────────────────────────────────────────────────────────────
    // Patrol logic
    // ─────────────────────────────────────────────────────────────

    private void handlePatrolArrival(String location) {
        isAtChargingStation = false;
        Log.d("Patrol", "Arrived: " + location + " index=" + patrolStopIndex);
        statusRef.setValue("arrived_gaming");
        robotStateRef.setValue("arrived_gaming");
        locRef.setValue("none");

        if (equalsLoc(location, LOC_GAMING) && !patrolInProgress) {
            // First arrival at gaming — read round ID and begin patrol
            currentDeliveringRoundRef.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    String rid = snap.getValue(String.class);
                    beginPatrol(rid != null ? rid.trim() : "");
                }
                @Override
                public void onCancelled(@NonNull DatabaseError e) { beginPatrol(""); }
            });
        } else {
            // Already in patrol (gaming1 or gaming2) — dwell here
            runOnUiThread(this::startDwellCountdown);
        }
    }

    private void beginPatrol(String roundId) {
        patrolInProgress   = true;
        patrolStopIndex    = 0;
        currentPatrolRound = roundId;

        // Persist patrol state to Firebase for crash recovery
        patrolInProgressRef.setValue(true);
        patrolIndexRef.setValue(0);

        Log.d("Patrol", "Patrol started — round: " + roundId);

        // ✅ Round marked done ONLY after gaming2 (in advancePatrol when all stops done)
        // Start dwell immediately at gaming
        runOnUiThread(this::startDwellCountdown);
    }

    private void startDwellCountdown() {
        // Must run on main thread
        stopCountdown();
        countdownValue = PATROL_DWELL_SEC;

        robot.cancelAllTtsRequests();
        if (patrolStopIndex == 0) {
            robot.speak(TtsRequest.create(getString(R.string.tts_gaming_delivery), false));
        } else {
            robot.speak(TtsRequest.create(getString(R.string.tts_gaming_patrol), false));
        }

        statusText.setText(R.string.status_arrived_gaming);
        txtWaiting.setText(getString(R.string.subtitle_collect, countdownValue));
        txtWaiting.setVisibility(View.VISIBLE);
        txtCountdown.setText(String.valueOf(countdownValue));
        txtCountdown.setVisibility(View.VISIBLE);

        Log.d("Patrol", "Dwell at stop " + patrolStopIndex + " — " + PATROL_DWELL_SEC + "s");

        countdownRunnable = new Runnable() {
            @Override
            public void run() {
                countdownValue--;
                if (countdownValue > 0) {
                    txtCountdown.setText(String.valueOf(countdownValue));
                    txtWaiting.setText(getString(R.string.subtitle_collect, countdownValue));
                    navHandler.postDelayed(this, 1000);
                } else {
                    txtCountdown.setVisibility(View.GONE);
                    advancePatrol();
                }
            }
        };
        navHandler.postDelayed(countdownRunnable, 1000);
    }

    private void advancePatrol() {
        patrolStopIndex++;

        if (patrolStopIndex < PATROL_STOPS.size()) {
            // Update Firebase patrol index for crash recovery
            patrolIndexRef.setValue(patrolStopIndex);

            String nextStop = PATROL_STOPS.get(patrolStopIndex);
            Log.d("Patrol", "Moving to: " + nextStop);
            startNavigationSequence(nextStop);

        } else {
            // ✅ All stops done — NOW mark round complete
            Log.d("Patrol", "Patrol complete — marking round done");
            patrolInProgress = false;
            patrolStopIndex  = 0;

            // Clear Firebase patrol state
            patrolInProgressRef.setValue(false);
            patrolIndexRef.setValue(0);

            // Mark round complete in Firebase
            markRoundComplete(currentPatrolRound, () -> {
                robot.cancelAllTtsRequests();
                robot.speak(TtsRequest.create(getString(R.string.tts_gaming_goodbye), false));
                navHandler.postDelayed(this::runPostGoodbyeOrdersDecision, 1800);
            });
        }
    }

    /**
     * Mark round and all its orders as complete in Firebase.
     * Called ONLY after gaming2 patrol stop finishes.
     */
    private void markRoundComplete(String rid, Runnable onDone) {
        if (rid == null || rid.isEmpty()) {
            runOnUiThread(() -> { if (onDone != null) onDone.run(); });
            return;
        }
        roundsRef.child(rid).child("orderIds").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                Map<String, Object> updates = new HashMap<>();
                if (snap.exists()) {
                    for (DataSnapshot idSnap : snap.getChildren()) {
                        String oId = idSnap.getKey();
                        updates.put("orders/" + oId + "/status", "complete");
                        updates.put("orders/" + oId + "/completedAt", ServerValue.TIMESTAMP);
                    }
                }
                updates.put("rounds/" + rid + "/status", "done");
                updates.put("current_delivering_round", "");
                updates.put("active_order_id", "");
                FirebaseDatabase.getInstance().getReference().updateChildren(updates, (err, ref) -> {
                    if (err != null) Log.e("Patrol", "markRoundComplete failed: " + err.getMessage());
                    runOnUiThread(() -> { if (onDone != null) onDone.run(); });
                });
            }
            @Override
            public void onCancelled(@NonNull DatabaseError e) {
                runOnUiThread(() -> { if (onDone != null) onDone.run(); });
            }
        });
    }

    /**
     * Clears patrol state both locally and in Firebase.
     * Called whenever Temi leaves the gaming zone (arrives pantry, staging, home).
     * Prevents "ghost patrol" on next dispatch.
     */
    private void clearPatrolState() {
        patrolInProgress   = false;
        patrolStopIndex    = 0;
        currentPatrolRound = "";
        patrolInProgressRef.setValue(false);
        patrolIndexRef.setValue(0);
        Log.d("Patrol", "Patrol state cleared");
    }

    private void stopCountdown() {
        if (countdownRunnable != null) {
            navHandler.removeCallbacks(countdownRunnable);
            countdownRunnable = null;
        }
        if (txtCountdown != null) {
            runOnUiThread(() -> txtCountdown.setVisibility(View.GONE));
        }
    }

    private boolean isPatrolStop(String location) {
        for (String stop : PATROL_STOPS) {
            if (equalsLoc(location, stop)) return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    // Post-patrol decision
    // ─────────────────────────────────────────────────────────────

    private void runPostGoodbyeOrdersDecision() {
        roundsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot roundsSnap) {
                boolean hasQueued = false;
                for (DataSnapshot child : roundsSnap.getChildren()) {
                    String st = child.child("status").getValue(String.class);
                    if ("closed".equalsIgnoreCase(st) || "locked".equalsIgnoreCase(st)) {
                        hasQueued = true;
                        break;
                    }
                }
                if (hasQueued) {
                    Log.d("Nav", "Queued round — going to pantry");
                    statusRef.setValue("queued_round_detected_going_pantry");
                    robotStateRef.setValue("moving");
                    locRef.setValue(LOC_PANTRY);
                    return;
                }
                ordersRef.addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snap) {
                        if (hasPendingOrders(snap)) {
                            statusRef.setValue("order_queue_next_leg_pantry");
                            locRef.setValue(LOC_PANTRY);
                        } else {
                            decideStageOrHome();
                        }
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError e) { locRef.setValue(LOC_CHARGING); }
                });
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { locRef.setValue(LOC_CHARGING); }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // Round decision helpers
    // ─────────────────────────────────────────────────────────────

    private void checkClosedRoundsAndDecide() {
        roundsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                boolean hasQueued = false;
                for (DataSnapshot child : snap.getChildren()) {
                    String st = child.child("status").getValue(String.class);
                    if ("closed".equalsIgnoreCase(st) || "locked".equalsIgnoreCase(st)) {
                        hasQueued = true;
                        break;
                    }
                }
                if (hasQueued) {
                    statusRef.setValue("queued_round_detected_going_pantry");
                    robotStateRef.setValue("moving");
                    locRef.setValue(LOC_PANTRY);
                } else {
                    decideStageOrHome();
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError e) {
                statusRef.setValue("idle");
                robotStateRef.setValue("idle");
                locRef.setValue("none");
            }
        });
    }

    private void decideStageOrHome() {
        try {
            Robot.BatteryData data = robot.getBatteryData();
            if (data == null) {
                Log.w("Battery", "null — defaulting to staging");
                statusRef.setValue("going_to_staging");
                robotStateRef.setValue("moving");
                locRef.setValue(LOC_STAGING);
                return;
            }
            int pct = data.getBatteryPercentage();
            Log.d("Battery", "decideStageOrHome — " + pct + "%");
            if (pct <= BATTERY_LOW_PCT) {
                statusRef.setValue("low_battery_returning_home");
                robotStateRef.setValue("moving");
                locRef.setValue(LOC_CHARGING);
            } else {
                statusRef.setValue("going_to_staging");
                robotStateRef.setValue("moving");
                locRef.setValue(LOC_STAGING);
            }
        } catch (Exception e) {
            Log.e("Battery", "Error: " + e.getMessage());
            locRef.setValue(LOC_STAGING);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Battery monitor
    // ─────────────────────────────────────────────────────────────

    private void startBatteryMonitor() {
        batteryHandler.removeCallbacks(batteryCheckRunnable);
        batteryHandler.postDelayed(batteryCheckRunnable, BATTERY_CHECK_INTERVAL);
        Log.d("Battery", "Monitor started");
    }

    private void stopBatteryMonitor() {
        batteryHandler.removeCallbacks(batteryCheckRunnable);
    }

    private void checkBatteryAndAct() {
        try {
            Robot.BatteryData data = robot.getBatteryData();
            if (data == null) { startBatteryMonitor(); return; }
            int pct = data.getBatteryPercentage();
            Log.d("Battery", pct + "%");
            
            if (isAtChargingStation) {
                statusRef.setValue("idle_charging_battery_" + pct + "pct");
                if (pct >= 80) {
                    statusRef.setValue("charged_going_to_staging");
                    robotStateRef.setValue("moving");
                    locRef.setValue(LOC_STAGING);
                } else {
                    startBatteryMonitor();
                }
            } else {
                statusRef.setValue("idle_staging_battery_" + pct + "pct");
                if (pct <= BATTERY_LOW_PCT) {
                    statusRef.setValue("low_battery_returning_home");
                    robotStateRef.setValue("moving");
                    locRef.setValue(LOC_CHARGING);
                } else {
                    startBatteryMonitor();
                }
            }
        } catch (Exception e) {
            Log.e("Battery", e.getMessage());
            startBatteryMonitor();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Navigation failure
    // ─────────────────────────────────────────────────────────────

    private void handleNavigationFailure(String location) {
        isMoving    = false;
        lastCommand = "";
        robot.stopMovement();
        statusRef.setValue("Path Blocked! Retrying...");
        robotStateRef.setValue("blocked");
        runOnUiThread(() -> statusText.setText(R.string.status_blocked));

        navHandler.postDelayed(() -> {
            if (!isMoving) {
                locRef.setValue("none");
                navHandler.postDelayed(() -> locRef.setValue(location), 500);
            }
        }, 7000);
    }

    // ─────────────────────────────────────────────────────────────
    // Order helpers
    // ─────────────────────────────────────────────────────────────

    private static boolean hasPendingOrders(@Nullable DataSnapshot snap) {
        if (snap == null || !snap.exists()) return false;
        for (DataSnapshot child : snap.getChildren()) {
            if (orderNeedsService(child)) return true;
        }
        return false;
    }

    private static boolean orderNeedsService(@NonNull DataSnapshot order) {
        String st = order.child("status").getValue(String.class);
        if (st == null || st.isEmpty()) return true;
        switch (st.trim().toLowerCase()) {
            case "delivered": case "complete":
            case "cancelled": case "canceled":
            case "ongoing":
                return false;
            default:
                return true;
        }
    }

    private static boolean equalsLoc(@Nullable String a, String b) {
        return a != null && a.equalsIgnoreCase(b);
    }
}