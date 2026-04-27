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

import com.google.android.material.button.MaterialButton;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Temi map location names must match the robot's saved map exactly (see Logcat: TEMI_LOCATIONS).
 * Edit the three constants below if your map uses different labels (e.g. "Charging Area", "Gaming Zone").
 */
public class MainActivity extends AppCompatActivity implements
        OnRobotReadyListener,
        OnGoToLocationStatusChangedListener {

    /** Home / idle / charging dock — must match {@link Robot#getLocations()} exactly. */
    private static final String LOC_CHARGING = "home base";
    private static final String LOC_PANTRY = "pantry";
    private static final String LOC_GAMING = "gaming";

    private Robot robot;
    private TextView statusText;
    private TextView txtWaiting;
    private MaterialButton btnOk;

    private DatabaseReference locRef;
    private DatabaseReference statusRef;
    private DatabaseReference ordersRef;
    private DatabaseReference activeOrderIdRef;
    private DatabaseReference robotStateRef;
    private DatabaseReference currentDeliveringRoundRef;
    private DatabaseReference roundsRef;

    private boolean isMoving = false;
    private String lastCommand = "";
    private final Handler navHandler = new Handler(Looper.getMainLooper());
    /** Cancelled when a new navigation target arrives so stale 3s timers cannot call goTo. */
    @Nullable
    private Runnable pendingGoToRunnable;
    /** After guest OK: delayed read of orders/; cancelled if rescheduled. */
    private final Runnable postGoodbyeOrdersReadRunnable = this::runPostGoodbyeOrdersDecision;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        txtWaiting = findViewById(R.id.txtWaiting);
        btnOk = findViewById(R.id.btnOk);
        robot = Robot.getInstance();

        FirebaseDatabase db = FirebaseDatabase.getInstance();
        locRef = db.getReference("location");
        statusRef = db.getReference("status");
        ordersRef = db.getReference("orders");
        activeOrderIdRef = db.getReference("active_order_id");
        robotStateRef = db.getReference("robot_state");
        currentDeliveringRoundRef = db.getReference("current_delivering_round");
        roundsRef = db.getReference("rounds");

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

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
            }
        });

        btnOk.setOnClickListener(v -> onGamingOkPressed());
    }

    private void checkAndNavigate(String target) {
        List<String> knownLocations = robot.getLocations();
        if (knownLocations == null) {
            Log.e("Nav", "Robot locations not ready yet.");
            statusRef.setValue("Error: Robot locations not ready");
            return;
        }
        boolean exists = false;
        for (String loc : knownLocations) {
            if (loc.equalsIgnoreCase(target)) {
                exists = true;
                startNavigationSequence(loc);
                break;
            }
        }
        if (!exists) {
            Log.e("Nav", "Location " + target + " not found!");
            statusRef.setValue("Error: Location not found");
        }
    }

    private void startNavigationSequence(String location) {
        isMoving = true;
        lastCommand = location;

        robot.stopMovement();
        robot.tiltAngle(0);

        statusRef.setValue("Preparing to move...");
        robotStateRef.setValue("moving");
        statusText.setText(R.string.status_preparing);
        hideGamingOk();

        if (pendingGoToRunnable != null) {
            navHandler.removeCallbacks(pendingGoToRunnable);
        }
        pendingGoToRunnable = () -> {
            pendingGoToRunnable = null;
            Log.d("NavSystem", "Sending goTo: " + location);
            robot.goTo(location);
            statusRef.setValue("Moving to " + location);
            statusText.setText(getString(R.string.status_moving_to, location));
            if (txtWaiting != null) {
                txtWaiting.setVisibility(View.GONE);
            }
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
            Log.d("NavStatus", "Loc: " + location + " Status: " + status);
            if (status.equalsIgnoreCase("complete")) {
                handleArrival(location);
            } else if (status.equalsIgnoreCase("abort") || status.equalsIgnoreCase("reject")) {
                handleNavigationFailure(location);
            }
        });
    }

    private void handleArrival(String location) {
        isMoving = false;
        lastCommand = "";
        robot.stopMovement();

        if (equalsLoc(location, LOC_PANTRY)) {
            statusRef.setValue("arrived_pantry");
            robotStateRef.setValue("arrived_pantry");
            statusText.setText(R.string.status_arrived_pantry);
            txtWaiting.setText(R.string.subtitle_waiting_pantry);
            txtWaiting.setVisibility(View.VISIBLE);
            robot.cancelAllTtsRequests();
            robot.speak(TtsRequest.create("Arrived at pantry. Waiting for staff to load items.", false));
            hideGamingOk();
            locRef.setValue("none");
            return;
        }

        if (equalsLoc(location, LOC_GAMING)) {
            statusRef.setValue("arrived_gaming");
            robotStateRef.setValue("arrived_gaming");
            statusText.setText(R.string.status_arrived_gaming);
            txtWaiting.setText(R.string.subtitle_collect);
            txtWaiting.setVisibility(View.VISIBLE);
            locRef.setValue("none");

            robot.cancelAllTtsRequests();
            robot.speak(TtsRequest.create(getString(R.string.tts_gaming_delivery), false));
            showGamingOk();
            return;
        }

        if (equalsLoc(location, LOC_CHARGING)) {
            hideGamingOk();
            locRef.setValue("none");
            checkClosedRoundsAndDecide(false); // handles idle OR pantry
            return;
        }

        statusText.setText(getString(R.string.status_arrived_generic, location));
        statusRef.setValue("Arrived at " + location);
        locRef.setValue("none");
        hideGamingOk();
    }

    private void showGamingOk() {
        btnOk.setVisibility(View.VISIBLE);
        btnOk.setEnabled(true);
    }

    private void hideGamingOk() {
        btnOk.setVisibility(View.GONE);
        btnOk.setEnabled(false);
    }

    /**
     * After delivery at Gaming: if any order is still pending, go to Pantry next; otherwise return to home base.
     * Orders: each child under {@code orders/} may have {@code status}. Terminal = delivered, complete, cancelled.
     */
    private void onGamingOkPressed() {
        btnOk.setEnabled(false);
        hideGamingOk();

        robot.cancelAllTtsRequests();
        robot.speak(TtsRequest.create(getString(R.string.tts_gaming_goodbye), false));

        currentDeliveringRoundRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                String roundId = snap.getValue(String.class);
                String rid = roundId == null ? "" : roundId.trim();
                if (rid.isEmpty()) {
                    schedulePostGoodbyeOrdersRead();
                    return;
                }
                
                roundsRef.child(rid).child("orderIds").addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot orderIdsSnap) {
                        Map<String, Object> updates = new HashMap<>();
                        if (orderIdsSnap.exists()) {
                            for (DataSnapshot idSnap : orderIdsSnap.getChildren()) {
                                String oId = idSnap.getKey();
                                updates.put("orders/" + oId + "/status", "complete");
                                updates.put("orders/" + oId + "/completedAt", ServerValue.TIMESTAMP);
                            }
                        }
                        
                        updates.put("rounds/" + rid + "/status", "done");
                        updates.put("current_delivering_round", "");
                        updates.put("active_order_id", "");
                        
                        FirebaseDatabase.getInstance().getReference().updateChildren(updates, (error, ref) -> {
                            if (error != null) {
                                Log.e("Nav", "Bulk round complete failed: " + error.getMessage());
                            }
                            schedulePostGoodbyeOrdersRead();
                        });
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        schedulePostGoodbyeOrdersRead();
                    }
                });
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                schedulePostGoodbyeOrdersRead();
            }
        });
    }

    /** After TTS buffer, read orders once and route to pantry or home base. */
    private void schedulePostGoodbyeOrdersRead() {
        navHandler.removeCallbacks(postGoodbyeOrdersReadRunnable);
        navHandler.postDelayed(postGoodbyeOrdersReadRunnable, 1800);
    }

    private void runPostGoodbyeOrdersDecision() {
        checkClosedRoundsAndDecide(true);
    }

    private void checkClosedRoundsAndDecide(boolean goHomeIfNone) {
        roundsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String nextRoundId = null;
                if (snapshot.exists()) {
                    for (DataSnapshot roundSnap : snapshot.getChildren()) {
                        String st = roundSnap.child("status").getValue(String.class);
                        if ("closed".equalsIgnoreCase(st) || "locked".equalsIgnoreCase(st)) {
                            nextRoundId = roundSnap.getKey();
                            break;
                        }
                    }
                }

                if (nextRoundId != null) {
                    Log.d("Nav", "Found round " + nextRoundId + " to deliver, heading to pantry");
                    String roundSt = snapshot.child(nextRoundId).child("status").getValue(String.class);
                    if ("closed".equalsIgnoreCase(roundSt)) {
                        Map<String, Object> updates = new HashMap<>();
                        updates.put("current_delivering_round", nextRoundId);
                        updates.put("rounds/" + nextRoundId + "/status", "locked");
                        FirebaseDatabase.getInstance().getReference().updateChildren(updates);
                    }

                    statusRef.setValue("order_queue_next_leg_pantry");
                    locRef.setValue(LOC_PANTRY);
                } else {
                    Log.d("Nav", "No closed rounds found.");
                    if (goHomeIfNone) {
                        statusRef.setValue("returning_home");
                        locRef.setValue(LOC_CHARGING);
                    } else {
                        statusRef.setValue("idle");
                        robotStateRef.setValue("idle");
                        locRef.setValue("none");
                        if (statusText != null) statusText.setText(R.string.status_idle_home);
                        if (txtWaiting != null) {
                            txtWaiting.setText(R.string.subtitle_idle);
                            txtWaiting.setVisibility(View.VISIBLE);
                        }
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                if (goHomeIfNone) {
                    statusRef.setValue("returning_home");
                    locRef.setValue(LOC_CHARGING);
                } else {
                    statusRef.setValue("idle");
                    robotStateRef.setValue("idle");
                    locRef.setValue("none");
                    if (statusText != null) statusText.setText(R.string.status_idle_home);
                    if (txtWaiting != null) {
                        txtWaiting.setText(R.string.subtitle_idle);
                        txtWaiting.setVisibility(View.VISIBLE);
                    }
                }
            }
        });
    }



    private void handleNavigationFailure(String location) {
        isMoving = false;
        lastCommand = "";
        robot.stopMovement();
        statusRef.setValue("Path Blocked! Retrying...");
        robotStateRef.setValue("blocked");
        statusText.setText(R.string.status_blocked);
        hideGamingOk();

        navHandler.postDelayed(() -> {
            if (!isMoving) {
                locRef.setValue("none");
                navHandler.postDelayed(() -> locRef.setValue(location), 500);
            }
        }, 7000);
    }

    private static boolean equalsLoc(@Nullable String fromRobot, String constant) {
        return fromRobot != null && fromRobot.equalsIgnoreCase(constant);
    }

    @Override
    public void onRobotReady(boolean ready) {
        if (ready) {
            List<String> locs = robot.getLocations();
            Log.d("TEMI_LOCATIONS", locs != null ? locs.toString() : "null");

            robot.hideTopBar(true);
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            robot.requestToBeKioskApp();

            robotStateRef.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    String currentState = snap.getValue(String.class);
                    boolean midDelivery = currentState != null &&
                        (currentState.equals("moving") ||
                         currentState.equals("arrived_pantry") ||
                         currentState.equals("arrived_gaming"));
                    if (!midDelivery) {
                        checkClosedRoundsAndDecide(false);
                    }
                }
                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    checkClosedRoundsAndDecide(false);
                }
            });
        }
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
        navHandler.removeCallbacks(postGoodbyeOrdersReadRunnable);
        super.onDestroy();
    }
}
