package fr.lepigeonnelson.player.broadcastplayer;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.kircherelectronics.fsensor.filter.averaging.MeanFilter;
import com.kircherelectronics.fsensor.observer.SensorSubject;
import com.kircherelectronics.fsensor.sensor.gyroscope.KalmanGyroscopeSensor;
import com.mapzen.android.lost.api.LocationRequest;
import com.mapzen.android.lost.api.LocationServices;
import com.mapzen.android.lost.api.LocationSettingsRequest;
import com.mapzen.android.lost.api.LocationSettingsResult;
import com.mapzen.android.lost.api.LocationSettingsStates;
import com.mapzen.android.lost.api.LostApiClient;
import com.mapzen.android.lost.api.PendingResult;
import com.mapzen.android.lost.api.Status;

import java.util.ArrayList;


public class SensorsService implements LostApiClient.ConnectionCallbacks {

    public static final int INIT_OK = 0;
    public static final int MISSING_PERMISSIONS = 1;
    public static final int RESOLUTION_REQUIRED = 2;
    public static final int ERROR_DURING_INITIALIZATION = 3;

    private static SensorsService instance = null;
    private final Context context;
    public static final int REQUEST_CHECK_SETTINGS = 100;
    private final KalmanGyroscopeSensor sensor;
    private final MeanFilter meanFilter;
    private boolean running;
    private LocationRequest request;

    private final int refreshDelayGPSms = 500;
    private final int smallestDisplacementGPS = 5;

    private float[] fusedOrientation = new float[3];


    private Location location;

    private BroadcastPlayer broadcastPlayer;
    private LostApiClient lostApiClient;

    private SensorSubject.SensorObserver sensorObserver = new SensorSubject.SensorObserver() {
        @Override
        public void onSensorChanged(float[] values) {
            updateValues(values);
        }
    };

    public boolean isLocationAvailable() {
        return locationAvailable;
    }

    private boolean locationAvailable;

    private void updateValues(float[] values) {
        fusedOrientation = values;
        fusedOrientation = meanFilter.filter(fusedOrientation);
    }

    /**
     * Singleton implementation
     */
    public static SensorsService getSensorsService(Context context) {
        if (instance == null) {
            instance = new SensorsService(context);
        }
        return instance;
    }

    public static SensorsService getSensorsService() {
        if (instance == null) {
            return null;
        }
        return instance;
    }

    public void register(BroadcastPlayer broadcastPlayer) {
        this.broadcastPlayer = broadcastPlayer;
    }

    /**
     * Local constructor
     */
    private SensorsService(Context context) {
        this.context = context;
        broadcastPlayer = null;
        this.sensor = new KalmanGyroscopeSensor(context);
        this.sensor.register(sensorObserver);
        this.sensor.start();
        this.meanFilter = new MeanFilter();
        // do we need meanFilter.setTimeConstant(...); ?

        locationAvailable = false;

        request = null;

        lostApiClient = new LostApiClient.Builder(context).addConnectionCallbacks(this).build();
        lostApiClient.connect();

        running = true;
        Log.d("LocationService", "LocationService created");
    }


    @Override
    public void onConnected() {
        Log.d("SensorsService", "connected");
        request = LocationRequest.create().setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY).
                setInterval(refreshDelayGPSms).setSmallestDisplacement(smallestDisplacementGPS);
        checkSensorsSettings();
    }

    @Override
    public void onConnectionSuspended() {
        location = null;
    }

    public void suspendDataCollection() {
        if (running) {
            this.sensor.stop();
            lostApiClient.disconnect();
            running = false;
        }
    }

    public void startDataCollection() {
        if (!running) {
            this.sensor.start();
            lostApiClient.connect();
            running = true;
        }
    }

    public Location getLocation() {
        return location;
    }

    public float getAzimuth() {
        return (float) (Math.toDegrees(fusedOrientation[0]) + 360) % 360;
    }

    public float getPitch() {
        return (float) Math.toDegrees(fusedOrientation[1]);
    }

    public float getRoll() {
        return (float) Math.toDegrees(fusedOrientation[2]);
    }

    public void checkSensorsSettings() {
        if (request != null) {
            ArrayList<LocationRequest> requests = new ArrayList<>();
            requests.add(request);
            boolean needBle = false;
            LocationSettingsRequest sRequest = new LocationSettingsRequest.Builder()
                    .addAllLocationRequests(requests)
                    .setNeedBle(needBle)
                    .build();

            PendingResult<LocationSettingsResult> result =
                    LocationServices.SettingsApi.checkLocationSettings(lostApiClient, sRequest);

            LocationSettingsResult locationSettingsResult = result.await();
            LocationSettingsStates states = locationSettingsResult.getLocationSettingsStates();
            Status status = locationSettingsResult.getStatus();
            switch (status.getStatusCode()) {
                case Status.SUCCESS:
                    Log.d("SensorsService", "success");
                    locationAvailable = true;
                    if (ActivityCompat.checkSelfPermission(context,
                            Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        // this application requires access to GPS location
                        if (broadcastPlayer != null)
                            broadcastPlayer.onSensorSettingsResult(SensorsService.MISSING_PERMISSIONS);
                        // TODO: if broadcastPlayer is not available, try again after x seconds
                        locationAvailable = false;
                        return;
                    }
                    Location loc = LocationServices.FusedLocationApi.getLastLocation(lostApiClient);
                    if (loc != null) {
                        location = loc;
                    }

                    // All location settings are satisfied. The client can make location requests here.
                    com.mapzen.android.lost.api.LocationListener listener =
                            new com.mapzen.android.lost.api.LocationListener() {
                        @Override
                        public void onLocationChanged(Location loc) {
                            // Do stuff
                            Log.d("LocationManager", "Location changed");
                            location = loc;
                            // TODO: store here last position and timestamp to estimate speed vector ?
                            // TODO: do this storage every second (or more)
                            // TODO: or use a moving average approach
                            if (broadcastPlayer != null && broadcastPlayer.isWorking())
                                broadcastPlayer.locationChanged();
                        }
                    };
                    LocationServices.FusedLocationApi.requestLocationUpdates(lostApiClient,
                            request, listener);
                    locationAvailable = true;

                    if (broadcastPlayer != null)
                        broadcastPlayer.onSensorSettingsResult(SensorsService.INIT_OK);
                    break;
                case Status.RESOLUTION_REQUIRED:
                    Log.d("SensorsService", "resolution required");
                    if (broadcastPlayer != null)
                        broadcastPlayer.onSensorSettingsResult(SensorsService.RESOLUTION_REQUIRED);
                    // TODO: if broadcastPlayer is not available, try again after x seconds

                    locationAvailable = false;
                    break;
                case Status.INTERNAL_ERROR:
                case Status.INTERRUPTED:
                case Status.TIMEOUT:
                case Status.CANCELLED:
                default:
                    Log.d("SensorsService", "error during initialization");
                    if (broadcastPlayer != null)
                        broadcastPlayer.onSensorSettingsResult(SensorsService.ERROR_DURING_INITIALIZATION);
                    // TODO: if broadcastPlayer is not available, try again after x seconds

                    // Location settings are not satisfied and cannot be resolved.
                    locationAvailable = false;
                    break;
            }
        }

    }
}