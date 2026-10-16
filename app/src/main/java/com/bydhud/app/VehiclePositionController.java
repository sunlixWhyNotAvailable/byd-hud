package com.bydhud.app;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Looper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Owns the optional GPS/Network subscription for the currently active route. */
final class VehiclePositionController {
    private static final long UPDATE_INTERVAL_MS = 1000L;
    private static final String[] PROVIDERS = {
            LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER
    };

    private final LocationManager locationManager;
    private final Runnable onChanged;
    private final Runnable onProviderStateChanged;
    private final Set<String> requestedProviders = new HashSet<>();
    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (location == null || !acquiring || routeSource == null) return;
            VehiclePosition position;
            try {
                position = positionFrom(location);
            } catch (IllegalArgumentException invalid) {
                return;
            }
            if (position.elapsedRealtimeMs < lastAcceptedElapsedRealtimeMs) return;
            lastAcceptedElapsedRealtimeMs = position.elapsedRealtimeMs;
            publish(new Snapshot(routeSource, position));
        }

        @Override
        public void onProviderEnabled(String provider) {
            refreshAfterProviderChange();
        }

        @Override
        public void onProviderDisabled(String provider) {
            refreshAfterProviderChange();
        }

        @Override
        public void onStatusChanged(String provider, int status, android.os.Bundle extras) {
        }
    };

    private volatile Snapshot snapshot = new Snapshot(null, null);
    private String routeSource;
    private boolean acquiring;
    private long lastAcceptedElapsedRealtimeMs = -1L;

    VehiclePositionController(Context context, Runnable onChanged, Runnable onProviderStateChanged) {
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        this.onChanged = onChanged;
        this.onProviderStateChanged = onProviderStateChanged;
    }

    Snapshot snapshot() {
        return snapshot;
    }

    boolean hasEnabledProvider() {
        if (locationManager == null) return false;
        try {
            List<String> enabled = locationManager.getProviders(true);
            for (String provider : PROVIDERS) {
                if (enabled.contains(provider)) return true;
            }
        } catch (SecurityException ignored) {
            return false;
        }
        return false;
    }

    void setRouteSource(String source) {
        String normalized = source == null || source.trim().isEmpty() ? null : source.trim();
        if (normalized == null ? routeSource == null : normalized.equals(routeSource)) return;
        routeSource = normalized;
        lastAcceptedElapsedRealtimeMs = -1L;
        removeUpdates();
        publish(new Snapshot(routeSource, null));
        if (acquiring && routeSource != null) refreshProviders();
    }

    boolean setAcquiring(boolean enabled) {
        boolean next = enabled && routeSource != null;
        if (!next) {
            acquiring = false;
            lastAcceptedElapsedRealtimeMs = -1L;
            removeUpdates();
            publish(new Snapshot(routeSource, null));
            return false;
        }
        if (acquiring && !requestedProviders.isEmpty()) return true;
        acquiring = true;
        return refreshProviders();
    }

    boolean refreshProviders() {
        if (!acquiring || routeSource == null || locationManager == null) return false;
        removeUpdates();
        List<String> enabled;
        try {
            enabled = locationManager.getProviders(true);
        } catch (SecurityException ignored) {
            setAcquiring(false);
            return false;
        }
        for (String provider : PROVIDERS) {
            if (!enabled.contains(provider)) continue;
            try {
                locationManager.requestLocationUpdates(
                        provider, UPDATE_INTERVAL_MS, 0f, locationListener, Looper.getMainLooper());
                requestedProviders.add(provider);
                Location lastKnown = locationManager.getLastKnownLocation(provider);
                if (lastKnown != null) locationListener.onLocationChanged(lastKnown);
            } catch (SecurityException | IllegalArgumentException ignored) {
                // Permission/provider changes can race the subscription request.
            }
        }
        if (requestedProviders.isEmpty()) {
            setAcquiring(false);
            return false;
        }
        return true;
    }

    void clear() {
        acquiring = false;
        routeSource = null;
        lastAcceptedElapsedRealtimeMs = -1L;
        removeUpdates();
        publish(new Snapshot(null, null));
    }

    private void removeUpdates() {
        requestedProviders.clear();
        if (locationManager == null) return;
        try {
            locationManager.removeUpdates(locationListener);
        } catch (SecurityException ignored) {
            // Permission may have been revoked between the callback and cleanup.
        }
    }

    private void refreshAfterProviderChange() {
        if (!refreshProviders()) setAcquiring(false);
        if (onProviderStateChanged != null) onProviderStateChanged.run();
    }

    private VehiclePosition positionFrom(Location location) {
        long elapsedNanos = location.getElapsedRealtimeNanos();
        if (elapsedNanos <= 0L) throw new IllegalArgumentException("missing monotonic fix time");
        long elapsedMs = elapsedNanos / 1_000_000L;
        if (elapsedMs <= 0L) throw new IllegalArgumentException("missing monotonic fix time");
        Float bearing = location.hasBearing() ? location.getBearing() : null;
        if (bearing != null) {
            if (!Float.isFinite(bearing) || bearing < 0f) bearing = null;
            else if (bearing >= 360f) bearing = 0f;
        }
        float accuracy = location.hasAccuracy() ? location.getAccuracy() : Float.NaN;
        if (!Float.isFinite(accuracy) || accuracy < 0f) accuracy = Float.NaN;
        boolean mock = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? location.isMock() : location.isFromMockProvider();
        return new VehiclePosition(
                location.getLatitude(), location.getLongitude(), elapsedMs,
                accuracy, bearing, mock);
    }

    private void publish(Snapshot next) {
        Snapshot previous = snapshot;
        if ((previous.routeSource == null ? next.routeSource == null
                : previous.routeSource.equals(next.routeSource))
                && previous.position == next.position) return;
        snapshot = next;
        if (onChanged != null) onChanged.run();
    }

    /** Route identity and newest immutable location, or a null position while unavailable. */
    public static final class Snapshot {
        public final String routeSource;
        public final VehiclePosition position;

        Snapshot(String routeSource, VehiclePosition position) {
            this.routeSource = routeSource;
            this.position = position;
        }
    }
}
