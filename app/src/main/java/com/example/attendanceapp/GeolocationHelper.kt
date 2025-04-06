@file:JvmName("GeolocationHelper")
package com.example.attendanceapp.utilities

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.firebase.database.FirebaseDatabase

class GeolocationHelper @JvmOverloads constructor(private val context: Context) {

    private val TAG = "GeolocationHelper"
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val database = FirebaseDatabase.getInstance().reference.child("users").child("teachers")
    private var locationListener: LocationListener? = null
    private var timeoutHandler: Handler? = null
    private var timeoutRunnable: Runnable = Runnable { }

    fun hasLocationPermission(): Boolean {
        val hasPermission = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "Location permission check: $hasPermission")
        return hasPermission
    }

    private fun isGpsEnabled(): Boolean {
        return try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            Log.e(TAG, "GPS check failed", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun verifyAttendance(teacherId: String, callback: (Boolean) -> Unit) {
        if (!hasLocationPermission()) {
            callback(false)
            return
        }

        if (!isGpsEnabled()) {
            Log.w(TAG, "GPS is disabled")
        }

        Log.d(TAG, "Fetching teacher location from Firebase for ID: $teacherId")
        database.child(teacherId).get().addOnSuccessListener { snapshot ->
            val teacherLat = snapshot.child("latitude").getValue(Double::class.java)
            val teacherLng = snapshot.child("longitude").getValue(Double::class.java)

            if (teacherLat == null || teacherLng == null) {
                Log.e(TAG, "Missing teacher location in Firebase")
                callback(false)
                return@addOnSuccessListener
            }

            val lastLocation = getLastKnownLocation()
            if (lastLocation != null && isLocationFresh(lastLocation)) {
                processLocation(lastLocation, teacherLat, teacherLng, callback)
                return@addOnSuccessListener
            }

            requestLiveLocation(teacherLat, teacherLng, callback)

        }.addOnFailureListener {
            Log.e(TAG, "Failed to get teacher location from Firebase", it)
            callback(false)
        }
    }

    private fun isLocationFresh(location: Location): Boolean {
        val locationAge = System.currentTimeMillis() - location.time
        return locationAge < 2 * 60 * 1000 // 2 minutes
    }

    private fun processLocation(studentLocation: Location, teacherLat: Double, teacherLng: Double, callback: (Boolean) -> Unit) {
        val teacherLocation = Location("teacher").apply {
            latitude = teacherLat
            longitude = teacherLng
        }
        val distance = studentLocation.distanceTo(teacherLocation)
        Log.d(TAG, "Distance to teacher: $distance meters")
        callback(distance <= 10)
    }

    @SuppressLint("MissingPermission")
    private fun requestLiveLocation(teacherLat: Double, teacherLng: Double, callback: (Boolean) -> Unit) {
        stopLocationUpdates()
        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                processLocation(location, teacherLat, teacherLng, callback)
                stopLocationUpdates()
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, locationListener!!, Looper.getMainLooper())
            setLocationTimeout(callback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request location updates", e)
            callback(false)
        }
    }

    private fun setLocationTimeout(callback: (Boolean) -> Unit) {
        timeoutHandler?.removeCallbacks(timeoutRunnable)
        timeoutHandler = Handler(Looper.getMainLooper())
        timeoutRunnable = Runnable {
            val lastLocation = getLastKnownLocation()
            if (lastLocation != null) {
                Log.d(TAG, "Using last known location after timeout")
                processLocation(lastLocation, 0.0, 0.0, callback) // May be adjusted if needed
            } else {
                stopLocationUpdates()
                callback(false)
            }
        }
        timeoutHandler?.postDelayed(timeoutRunnable, 15000)
    }

    fun stopLocationUpdates() {
        locationListener?.let {
            locationManager.removeUpdates(it)
        }
        locationListener = null
        timeoutHandler?.removeCallbacks(timeoutRunnable)
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(): Location? {
        return if (hasLocationPermission()) {
            try {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting last known location", e)
                null
            }
        } else null
    }
}
