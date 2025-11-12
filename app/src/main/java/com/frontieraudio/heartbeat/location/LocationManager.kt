package com.frontieraudio.heartbeat.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class LocationManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val fusedLocationClient: FusedLocationProviderClient = 
        LocationServices.getFusedLocationProviderClient(context)
    
    private var locationCallback: LocationCallback? = null
    
    private val locationUpdatesInternal = MutableSharedFlow<LocationData>(
        replay = 1, // Keep latest location
        extraBufferCapacity = 5,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    
    val locationUpdates: SharedFlow<LocationData> = locationUpdatesInternal.asSharedFlow()

    private var isLocationUpdatesActive = false
    private var currentLocation: LocationData? = null
    
    fun hasLocationPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
               ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    
    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (!hasLocationPermissions()) {
            Log.w(TAG, "Location permissions not granted, cannot start location updates")
            return
        }
        
        if (isLocationUpdatesActive) {
            Log.d(TAG, "Location updates already active")
            return
        }
        
        Log.d(TAG, "Starting location updates")
        
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            TimeUnit.SECONDS.toMillis(30) // Update every 30 seconds
        ).apply {
            setWaitForAccurateLocation(false)
            setMinUpdateIntervalMillis(TimeUnit.SECONDS.toMillis(15)) // Minimum 15 seconds
            setMaxUpdateDelayMillis(TimeUnit.MINUTES.toMillis(2)) // Maximum 2 minutes
        }.build()
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.locations?.let { locations ->
                    for (location in locations) {
                        handleLocationUpdate(location)
                    }
                }
            }
            
            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    Log.w(TAG, "Location not available")
                }
            }
        }
        
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
            isLocationUpdatesActive = true
            
            // Get last known location immediately
            getLastKnownLocation()
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception when requesting location updates", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting location updates", e)
        }
    }
    
    fun stopLocationUpdates() {
        if (!isLocationUpdatesActive) {
            return
        }
        
        Log.d(TAG, "Stopping location updates")
        
        locationCallback?.let { callback ->
            fusedLocationClient.removeLocationUpdates(callback)
        }
        
        locationCallback = null
        isLocationUpdatesActive = false
    }
    
    fun getCurrentLocation(): LocationData? {
        if (!hasLocationPermissions()) {
            return null
        }
        
        // Note: This is a simplified version. In production, you'd want to handle
        // the asynchronous nature of location requests properly
        return try {
            // For now, return the last cached location
            // You can extend this to make a fresh location request
            currentLocation
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current location", e)
            null
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation() {
        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    location?.let { 
                        handleLocationUpdate(it)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to get last known location", e)
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception getting last known location", e)
        }
    }
    
    private fun handleLocationUpdate(location: Location) {
        val locationData = LocationData(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            timestampMillis = location.time
        )

        currentLocation = locationData

        scope.launch {
            locationUpdatesInternal.emit(locationData)
        }

        Log.d(TAG, String.format(
            "Location update: lat=%.6f, lon=%.6f, accuracy=%.1f",
            location.latitude, location.longitude, location.accuracy
        ))
    }
    
    fun cleanup() {
        stopLocationUpdates()
        scope.cancel()
    }
    
    companion object {
        private const val TAG = "LocationManager"
    }
}