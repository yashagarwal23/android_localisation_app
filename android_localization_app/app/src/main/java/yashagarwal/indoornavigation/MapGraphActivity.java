package yashagarwal.indoornavigation;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.FragmentActivity;
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.RotateAnimation;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.maps.android.data.geojson.GeoJsonLayer;
import com.mapbox.geojson.Point;
import com.mapbox.mapboxsdk.maps.MapView;
import com.mapbox.mapboxsdk.plugins.annotation.LineOptions;
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import yashagarwal.indoornavigation.activity.GraphActivity;
import yashagarwal.indoornavigation.extra.ExtraFunctions;
import yashagarwal.indoornavigation.filewriting.DataFileWriter;
import yashagarwal.indoornavigation.graph.ScatterPlot;
import yashagarwal.indoornavigation.orientation.GyroscopeDeltaOrientation;
import yashagarwal.indoornavigation.orientation.GyroscopeEulerOrientation;
import yashagarwal.indoornavigation.orientation.MagneticFieldOrientation;
import yashagarwal.indoornavigation.stepcounting.DynamicStepCounter;

public class MapGraphActivity extends FragmentActivity implements OnMapReadyCallback, SensorEventListener, LocationListener {

    private GoogleMap mMap;
    private static final long GPS_SECONDS_PER_WEEK = 511200L;

    private static final float GYROSCOPE_INTEGRATION_SENSITIVITY = 0.0025f;

    private static final String FOLDER_NAME = "Indoor_Navigation/Graph_Activity";
    private static final String[] DATA_FILE_NAMES = {
            "Initial_Orientation",
            "Linear_Acceleration",
            "Gyroscope_Uncalibrated",
            "Magnetic_Field_Uncalibrated",
            "Gravity",
            "XY_Data_Set"
    };
    private static final String[] DATA_FILE_HEADINGS = {
            "Initial_Orientation",
            "Linear_Acceleration" + "\n" + "t;Ax;Ay;Az;findStep",
            "Gyroscope_Uncalibrated" + "\n" + "t;uGx;uGy;uGz;xBias;yBias;zBias;heading",
            "Magnetic_Field_Uncalibrated" + "\n" + "t;uMx;uMy;uMz;xBias;yBias;zBias;heading",
            "Gravity" + "\n" + "t;gx;gy;gz",
            "XY_Data_Set" + "\n" + "weekGPS;secGPS;t;strideLength;magHeading;gyroHeading;originalPointX;originalPointY;rotatedPointX;rotatedPointY"
    };

//    Pah

    float current = 0;

    private DynamicStepCounter dynamicStepCounter;
    private GyroscopeDeltaOrientation gyroscopeDeltaOrientation;
    private GyroscopeEulerOrientation gyroscopeEulerOrientation;
    private DataFileWriter dataFileWriter;
    private ScatterPlot scatterPlot;

    private FloatingActionButton fabButton;
    private LinearLayout mLinearLayout;

    private SensorManager sensorManager;
    private LocationManager locationManager;
    ImageView arrowImageView = null;

    float[] gyroBias;
    float[] magBias;
    float[] currGravity; //current gravity
    float[] currMag; //current magnetic field

    private boolean isRunning;
    private boolean isCalibrated;
    private boolean usingDefaultCounter;
    private boolean areFilesCreated;
    private float strideLength;
    private float gyroHeading;
    private float magHeading;
    private float weeksGPS;
    private float secondsGPS;

    private long startTime;
    private boolean firstRun;

    private float initialHeading;
    LatLng prev;
    Polyline myLocation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map_graph);
        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(MapGraphActivity.this, new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            },0);
            finish();
        }

        arrowImageView = findViewById(R.id.arrow_icon);

        //defining needed variables
        gyroBias = null;
        magBias = null;
        currGravity = null;
        currMag = null;

        String counterSensitivity;

        isRunning = isCalibrated = usingDefaultCounter = areFilesCreated = false;
        firstRun = true;
        strideLength = 0;
        initialHeading = gyroHeading = magHeading = 0;
        weeksGPS = secondsGPS = 0;
        startTime = 0;

        strideLength = 0.2f;
        isCalibrated = false;
        gyroBias = new float[3];
        magBias = new float[3];


        counterSensitivity = "0";
        usingDefaultCounter = false;

        //initializing needed classes
        gyroscopeDeltaOrientation = new GyroscopeDeltaOrientation(GYROSCOPE_INTEGRATION_SENSITIVITY, gyroBias);
        if (usingDefaultCounter) //if using default TYPE_STEP_DETECTOR, don't need DynamicStepCounter
            dynamicStepCounter = null;
        else if (!counterSensitivity.equals("default"))
            dynamicStepCounter = new DynamicStepCounter(Double.parseDouble(counterSensitivity));
        else //if cannot use TYPE_STEP_DETECTOR but sensitivity = "default", use 1.0 sensitivity until user calibrates
            dynamicStepCounter = new DynamicStepCounter(1.0);

        //defining views
        fabButton = findViewById(R.id.fab);
        mLinearLayout = findViewById(R.id.linearLayoutGraph);

        //setting up graph with origin
        scatterPlot = new ScatterPlot("");
        scatterPlot.addPoint(0, 0);

        mLinearLayout.addView(scatterPlot.getGraphView(getApplicationContext()));

        //message user w/ user_name and stride_length info
//        Toast.makeText(MapGraphActivity.this, "Stride Length: " + strideLength, Toast.LENGTH_SHORT).show();

        //starting GPS location tracking
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, MapGraphActivity.this);

        //starting sensors
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        sensorManager.registerListener(MapGraphActivity.this,
                sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY),
                SensorManager.SENSOR_DELAY_FASTEST);

        if (isCalibrated) {
            sensorManager.registerListener(MapGraphActivity.this,
                    sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED),
                    SensorManager.SENSOR_DELAY_FASTEST);
            sensorManager.registerListener(MapGraphActivity.this,
                    sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED),
                    SensorManager.SENSOR_DELAY_FASTEST);
        } else {
            sensorManager.registerListener(MapGraphActivity.this,
                    sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
                    SensorManager.SENSOR_DELAY_FASTEST);
            sensorManager.registerListener(MapGraphActivity.this,
                    sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
                    SensorManager.SENSOR_DELAY_FASTEST);
        }

        if (usingDefaultCounter) {
            sensorManager.registerListener(MapGraphActivity.this,
                    sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR),
                    SensorManager.SENSOR_DELAY_FASTEST);
        } else {
            sensorManager.registerListener(MapGraphActivity.this,
                    sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION),
                    SensorManager.SENSOR_DELAY_FASTEST);
        }

//        load_map();

        //setting up buttons
        fabButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                if (!isRunning) {

                    isRunning = true;

                    createFiles();

                    if (usingDefaultCounter)
                        dataFileWriter.writeToFile("Linear_Acceleration",
                                "TYPE_LINEAR_ACCELERATION will not be recorded, since the TYPE_STEP_DETECTOR is being used instead."
                        );

                    float[][] initialOrientation = MagneticFieldOrientation.getOrientationMatrix(currGravity, currMag, magBias);
                    initialHeading = MagneticFieldOrientation.getHeading(currGravity, currMag, magBias);

                    //saving initial orientation data
                    dataFileWriter.writeToFile("Initial_Orientation", "init_Gravity: " + Arrays.toString(currGravity));
                    dataFileWriter.writeToFile("Initial_Orientation", "init_Mag: " + Arrays.toString(currMag));
                    dataFileWriter.writeToFile("Initial_Orientation", "mag_Bias: " + Arrays.toString(magBias));
                    dataFileWriter.writeToFile("Initial_Orientation", "gyro_Bias: " + Arrays.toString(gyroBias));
                    dataFileWriter.writeToFile("Initial_Orientation", "init_Orientation: " + Arrays.deepToString(initialOrientation));
                    dataFileWriter.writeToFile("Initial_Orientation", "init_Heading: " + initialHeading);

//                Log.d("init_heading", "" + initialHeading);

                    //TODO: fix rotation matrix
                    //gyroscopeEulerOrientation = new GyroscopeEulerOrientation(initialOrientation);

                    gyroscopeEulerOrientation = new GyroscopeEulerOrientation(ExtraFunctions.IDENTITY_MATRIX);

                    dataFileWriter.writeToFile("XY_Data_Set", "Initial_orientation: " +
                            Arrays.deepToString(initialOrientation));
                    dataFileWriter.writeToFile("Gyroscope_Uncalibrated", "Gyroscope_bias: " +
                            Arrays.toString(gyroBias));
                    dataFileWriter.writeToFile("Magnetic_Field_Uncalibrated", "Magnetic_field_bias:" +
                            Arrays.toString(magBias));

                    fabButton.setImageDrawable(ContextCompat.getDrawable(MapGraphActivity.this, R.drawable.ic_pause_black_24dp));

                } else {

                    firstRun = true;
                    isRunning = false;

                    fabButton.setImageDrawable(ContextCompat.getDrawable(MapGraphActivity.this, R.drawable.ic_play_arrow_black_24dp));

                }
            }
        });

        mLinearLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                //complimentary filter
                float compHeading = ExtraFunctions.calcCompHeading(magHeading, gyroHeading);

//                Log.d("comp_heading", "" + compHeading);

                //getting and rotating the previous XY points so North 0 on unit circle
                float oPointX = scatterPlot.getLastYPoint();
                float oPointY = -scatterPlot.getLastXPoint();

                //calculating XY points from heading and stride_length
                oPointX += ExtraFunctions.getXFromPolar(strideLength, compHeading);
                oPointY += ExtraFunctions.getYFromPolar(strideLength, compHeading);

                //rotating points by 90 degrees, so north is up
                float rPointX = -oPointY;
                float rPointY = oPointX;

                LatLng next = new LatLng(prev.latitude + rPointY/(6*1e5), prev.longitude + rPointX/(6*1e5));
                UpdatePoints(next);

                scatterPlot.addPoint(rPointX, rPointY);

                mLinearLayout.removeAllViews();
                mLinearLayout.addView(scatterPlot.getGraphView(getApplicationContext()));

            }
        });

    }

    @Override
    protected void onStop() {
        super.onStop();
        sensorManager.unregisterListener(this);
        locationManager.removeUpdates(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (isRunning) {

            // get location permission
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    || ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(MapGraphActivity.this, new String[] {
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                },0);
                finish();
            }

            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, MapGraphActivity.this);

            if (isCalibrated) {
                sensorManager.registerListener(MapGraphActivity.this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED),
                        SensorManager.SENSOR_DELAY_FASTEST);
                sensorManager.registerListener(MapGraphActivity.this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED),
                        SensorManager.SENSOR_DELAY_FASTEST);
            } else {
                sensorManager.registerListener(MapGraphActivity.this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
                        SensorManager.SENSOR_DELAY_FASTEST);
                sensorManager.registerListener(MapGraphActivity.this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
                        SensorManager.SENSOR_DELAY_FASTEST);
            }

            if (usingDefaultCounter) {
                sensorManager.registerListener(MapGraphActivity.this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR),
                        SensorManager.SENSOR_DELAY_FASTEST);
            } else {
                sensorManager.registerListener(MapGraphActivity.this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION),
                        SensorManager.SENSOR_DELAY_FASTEST);
            }

            fabButton.setImageDrawable(ContextCompat.getDrawable(MapGraphActivity.this, R.drawable.ic_pause_black_24dp));

        } else {

            fabButton.setImageDrawable(ContextCompat.getDrawable(MapGraphActivity.this, R.drawable.ic_play_arrow_black_24dp));

        }

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onSensorChanged(SensorEvent event) {

        if(firstRun) {
            startTime = event.timestamp;
            firstRun = false;
        }

        if (event.sensor.getType() == Sensor.TYPE_GRAVITY) {
            currGravity = event.values;
//            Log.d("gravity_values", Arrays.toString(event.values));
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD ||
                event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) {
            currMag = event.values;
//            Log.d("mag_values", Arrays.toString(event.values));
        }

        if (isRunning) {
            if (event.sensor.getType() == Sensor.TYPE_GRAVITY) {
                ArrayList<Float> dataValues = ExtraFunctions.arrayToList(event.values);
                dataValues.add(0, (float)(event.timestamp - startTime));
                dataFileWriter.writeToFile("Gravity", dataValues);
            } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD || event.sensor.getType() ==
                    Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) {

                magHeading = MagneticFieldOrientation.getHeading(currGravity, currMag, magBias);

//                Log.d("mag_heading", "" + magHeading);

                //saving magnetic field data
                ArrayList<Float> dataValues = ExtraFunctions.createList(
                        event.values[0], event.values[1], event.values[2],
                        magBias[0], magBias[1], magBias[2]
                );
                dataValues.add(0, (float)(event.timestamp - startTime));
                dataValues.add(magHeading);
                dataFileWriter.writeToFile("Magnetic_Field_Uncalibrated", dataValues);

            } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE ||
                    event.sensor.getType() == Sensor.TYPE_GYROSCOPE_UNCALIBRATED) {

                float[] deltaOrientation = gyroscopeDeltaOrientation.calcDeltaOrientation(event.timestamp, event.values);

                gyroHeading = gyroscopeEulerOrientation.getHeading(deltaOrientation);
                gyroHeading += initialHeading;

//                Log.d("gyro_heading", "" + gyroHeading);

                //saving gyroscope data
                ArrayList<Float> dataValues = ExtraFunctions.createList(
                        event.values[0], event.values[1], event.values[2],
                        gyroBias[0], gyroBias[1], gyroBias[2]
                );
                dataValues.add(0, (float)(event.timestamp - startTime));
                dataValues.add(gyroHeading);
                dataFileWriter.writeToFile("Gyroscope_Uncalibrated", dataValues);

            } else if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {

                float norm = ExtraFunctions.calcNorm(
                        event.values[0] +
                                event.values[1] +
                                event.values[2]
                );

                //if step is found, findStep == true
                boolean stepFound = dynamicStepCounter.findStep(norm);

                if (stepFound) {

                    //saving linear acceleration data
                    ArrayList<Float> dataValues = ExtraFunctions.arrayToList(event.values);
                    dataValues.add(0, (float)(event.timestamp - startTime));
                    dataValues.add(1f);
                    dataFileWriter.writeToFile("Linear_Acceleration", dataValues);

                    //complimentary filter
                    float compHeading = ExtraFunctions.calcCompHeading(magHeading, gyroHeading);

                    //Log.d("comp_heading", "" + compHeading);

                    //getting and rotating the previous XY points so North 0 on unit circle
                    float oPointX = scatterPlot.getLastYPoint();
                    float oPointY = -scatterPlot.getLastXPoint();

                    //calculating XY points from heading and stride_length
                    oPointX += ExtraFunctions.getXFromPolar(strideLength, gyroHeading);
                    oPointY += ExtraFunctions.getYFromPolar(strideLength, gyroHeading);

                    //rotating points by 90 degrees, so north is up
                    float rPointX = -oPointY;
                    float rPointY = oPointX;

                    LatLng next = new LatLng(prev.latitude + rPointY/(6*1e5), prev.longitude + rPointX/(6*1e5));
                    UpdatePoints(next);

                    scatterPlot.addPoint(rPointX, rPointY);

                    //saving XY location data
                    dataFileWriter.writeToFile("XY_Data_Set",
                            weeksGPS,
                            secondsGPS,
                            (event.timestamp - startTime),
                            strideLength,
                            magHeading,
                            gyroHeading,
                            oPointX,
                            oPointY,
                            rPointX,
                            rPointY);

                    mLinearLayout.removeAllViews();
                    mLinearLayout.addView(scatterPlot.getGraphView(getApplicationContext()));

                    //if step is not found
                } else {
                    //saving linear acceleration data
                    ArrayList<Float> dataValues = ExtraFunctions.arrayToList(event.values);
                    dataValues.add(0, (float) event.timestamp);
                    dataValues.add(0f);
                    dataFileWriter.writeToFile("Linear_Acceleration", dataValues);
                }

            } else if (event.sensor.getType() == Sensor.TYPE_STEP_DETECTOR) {

                boolean stepFound = (event.values[0] == 1);

                if (stepFound) {

                    //complimentary filter
                    float compHeading = ExtraFunctions.calcCompHeading(magHeading, gyroHeading);

                    //Log.d("comp_heading", "" + compHeading);

                    //getting and rotating the previous XY points so North 0 on unit circle
                    float oPointX = scatterPlot.getLastYPoint();
                    float oPointY = -scatterPlot.getLastXPoint();

                    //calculating XY points from heading and stride_length
                    oPointX += ExtraFunctions.getXFromPolar(strideLength, gyroHeading);
                    oPointY += ExtraFunctions.getYFromPolar(strideLength, gyroHeading);

                    //rotating points by 90 degrees, so north is up
                    float rPointX = -oPointY;
                    float rPointY = oPointX;

                    LatLng next = new LatLng(prev.latitude + rPointY/(6*1e5), prev.longitude + rPointX/(6*1e5));
                    UpdatePoints(next);

                    scatterPlot.addPoint(rPointX, rPointY);

                    //saving XY location data
                    dataFileWriter.writeToFile("XY_Data_Set",
                            weeksGPS,
                            secondsGPS,
                            (event.timestamp - startTime),
                            strideLength,
                            magHeading,
                            gyroHeading,
                            oPointX,
                            oPointY,
                            rPointX,
                            rPointY);

                    mLinearLayout.removeAllViews();
                    mLinearLayout.addView(scatterPlot.getGraphView(getApplicationContext()));
                }

            }
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        long GPSTimeSec = location.getTime() / 1000;
        weeksGPS = GPSTimeSec / GPS_SECONDS_PER_WEEK;
        secondsGPS = GPSTimeSec % GPS_SECONDS_PER_WEEK;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {}

    private void createFiles() {
        if (!areFilesCreated) {
            try {
                dataFileWriter = new DataFileWriter(FOLDER_NAME, DATA_FILE_NAMES, DATA_FILE_HEADINGS);
            } catch (IOException e) {
                Log.e("GraphActivity", e.toString());
            }
            areFilesCreated = true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode){
            case 0:
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(MapGraphActivity.this, "Thank you for providing permission!", Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    Toast.makeText(MapGraphActivity.this, "Need location permission to create tour.", Toast.LENGTH_LONG).show();
                    finish();
                }
                break;
        }
    }

    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera. In this case,
     * we just add a marker near Sydney, Australia.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;

        LatLng start = new LatLng(38.89770, -77.03670);
        LatLng mid = new LatLng(38.89767024, -77.03670);
        LatLng destination = new LatLng(38.89767024, -77.03640063);
        mMap.addMarker(new MarkerOptions().position(destination).title("Destination"));

        mMap.setMapStyle(
                MapStyleOptions.loadRawResourceStyle(
                        this, R.raw.style_json));

        mMap.addPolyline(new PolylineOptions()
                .add(start, mid, destination)
                .width(10)
                .color(Color.RED));

        PolylineOptions routes = new PolylineOptions().width(10).color(Color.BLUE);
        myLocation = mMap.addPolyline(routes);

        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(start, 20));
        mMap.setIndoorEnabled(false);

        GeoJsonLayer layer = null;
        try {
            layer = new GeoJsonLayer(mMap, R.raw.white_house_lvl_0,
                    this);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (JSONException e) {
            e.printStackTrace();
        }
        layer.addLayerToMap();
    }
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event){

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP){
            float degree = current-90;
            current -= 90;
            RotateAnimation ra = new RotateAnimation(
                    current,
                    degree,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF,
                    0.5f);
            ra.setDuration(210);
            ra.setFillAfter(true);
            arrowImageView.startAnimation(ra);
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN){
            float degree = current+90;
            current += 90;
            RotateAnimation ra = new RotateAnimation(
                    current,
                    degree,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF,
                    0.5f);
            ra.setDuration(210);
            ra.setFillAfter(true);
            arrowImageView.startAnimation(ra);
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }
    private void UpdatePoints(LatLng newlatlng) {
        List<LatLng> points = myLocation.getPoints();
        points.add(newlatlng);
        myLocation.setPoints(points);
    }
}
