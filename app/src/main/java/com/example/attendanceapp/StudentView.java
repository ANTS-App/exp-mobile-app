package com.example.attendanceapp;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.attendanceapp.utilities.ChildNameGenerator;
import com.example.attendanceapp.utilities.GeolocationHelper;
import com.example.attendanceapp.utilities.StatusBarUtils;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.firebase.database.*;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class StudentView extends AppCompatActivity {

    private static final String TAG = "StudentView";
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 100;

    private BluetoothAdapter bluetoothAdapter;
    private String teacherMacAddress;
    private boolean isTeacherNearby = false;

    private GeolocationHelper geolocationHelper;
    private DatabaseReference databaseReference, databaseReference1;
    private CountDownTimer countDownTimer;

    private TextView o1, o2, o3, o4, o5, timeTV, nameTV, dateTV;
    private LinearProgressIndicator progressBar;
    private Button myButton;

    private String uid = "S12345";
    private long teacherNumber;
    private long selectedCode = -1;
    private final String teacherId = "3";

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && device.getAddress().equalsIgnoreCase(teacherMacAddress)) {
                    isTeacherNearby = true;
                    Log.i(TAG, "Teacher device found via Bluetooth");
                    unregisterReceiver(this);
                    Toast.makeText(context, "Teacher found nearby!", Toast.LENGTH_SHORT).show();
                    enableCodeSelection();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_student_view);

        StatusBarUtils.customizeStatusBar(this, R.color.white, true);

        geolocationHelper = new GeolocationHelper(this);
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;

        initializeViews();
        displayCurrentDate();
        startProgress();
        setupFirebase();
        requestPermissionsAndStartBluetoothScan();
    }

    private void initializeViews() {
        progressBar = findViewById(R.id.progressBar);
        timeTV = findViewById(R.id.timeTextView);
        progressBar.setMax(60);

        o1 = findViewById(R.id.t1);
        o2 = findViewById(R.id.t2);
        o3 = findViewById(R.id.t3);
        o4 = findViewById(R.id.t4);
        o5 = findViewById(R.id.t0);

        nameTV = findViewById(R.id.nametv);
        dateTV = findViewById(R.id.datetv);
        myButton = findViewById(R.id.markAttendanceButton);
        myButton.setEnabled(false);

        myButton.setOnClickListener(view -> handleAttendance());
    }

    private void setupFirebase() {
        String childName = ChildNameGenerator.getDynamicChildName();
        databaseReference = FirebaseDatabase.getInstance().getReference("attendance_sessions").child("202503240717");
        databaseReference1 = FirebaseDatabase.getInstance().getReference("students").child(uid);

        databaseReference.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                teacherNumber = snapshot.child("teacher_number").getValue(Long.class);
                o5.setText(String.valueOf(teacherNumber));

                teacherMacAddress = snapshot.child("bluetoothSignature").getValue(String.class);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Firebase error", error.toException());
            }
        });

        databaseReference1.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                nameTV.setText(snapshot.child("name").getValue(String.class));
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void enableCodeSelection() {
        setCodeClick(o1);
        setCodeClick(o2);
        setCodeClick(o3);
        setCodeClick(o4);
        setCodeClick(o5);
        myButton.setEnabled(true);
    }

    private void setCodeClick(TextView view) {
        view.setOnClickListener(v -> {
            resetSelection();
            view.setBackgroundColor(getColor(R.color.lightGreen));
            selectedCode = Long.parseLong(view.getText().toString());
        });
    }

    private void resetSelection() {
        o1.setBackgroundColor(Color.TRANSPARENT);
        o2.setBackgroundColor(Color.TRANSPARENT);
        o3.setBackgroundColor(Color.TRANSPARENT);
        o4.setBackgroundColor(Color.TRANSPARENT);
        o5.setBackgroundColor(Color.TRANSPARENT);
    }

    private void handleAttendance() {
        if (selectedCode != teacherNumber) {
            myButton.setText("Wrong Code Selected");
            myButton.setEnabled(false);
            stopProgress();
            return;
        }

        if (!geolocationHelper.hasLocationPermission()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
        } else {
            geolocationHelper.verifyAttendance(teacherId, isWithin -> {
                if (isWithin) markAttendance();
                else Toast.makeText(this, "Not in range of teacher.", Toast.LENGTH_LONG).show();
                return null;
            });
        }
    }

    private void markAttendance() {
        DatabaseReference attnRef = FirebaseDatabase.getInstance().getReference("attendance_sessions")
                .child("202503240710").child("attn_marked").child(uid);

        attnRef.setValue("marked").addOnSuccessListener(aVoid -> {
            myButton.setText("Attendance Marked");
            myButton.setEnabled(false);
            stopProgress();
        });
    }

    private void startProgress() {
        countDownTimer = new CountDownTimer(60000, 1000) {
            public void onTick(long millisUntilFinished) {
                int sec = (int) (millisUntilFinished / 1000);
                timeTV.setText(sec + "s");
                progressBar.setProgress(sec);
            }

            public void onFinish() {
                myButton.setEnabled(false);
                myButton.setText("Time Expired");
            }
        }.start();
    }

    private void stopProgress() {
        if (countDownTimer != null) countDownTimer.cancel();
        timeTV.setText("Done");
        progressBar.setProgress(0);
    }

    private void displayCurrentDate() {
        SimpleDateFormat sdf = new SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault());
        dateTV.setText(sdf.format(Calendar.getInstance().getTime()));
    }

    private void requestPermissionsAndStartBluetoothScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    }, 200);
            return;
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }

        registerReceiver(bluetoothReceiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));
        bluetoothAdapter.startDiscovery();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(bluetoothReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Receiver already unregistered");
        }
        if (countDownTimer != null) countDownTimer.cancel();
        if (geolocationHelper != null) geolocationHelper.stopLocationUpdates();
    }
}
