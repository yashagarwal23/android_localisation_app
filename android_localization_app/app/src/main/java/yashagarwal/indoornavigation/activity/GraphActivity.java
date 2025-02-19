package yashagarwal.indoornavigation.activity;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Matrix;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.RotateAnimation;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.mapbox.geojson.Point;
import com.mapbox.geojson.Polygon;
import com.mapbox.mapboxsdk.Mapbox;
import com.mapbox.mapboxsdk.maps.MapView;
import com.mapbox.mapboxsdk.maps.MapboxMap;
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback;
import com.mapbox.mapboxsdk.maps.Style;
import com.mapbox.mapboxsdk.style.layers.FillLayer;
import com.mapbox.mapboxsdk.style.layers.LineLayer;
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource;
import com.mapbox.turf.TurfJoins;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import yashagarwal.indoornavigation.R;
import yashagarwal.indoornavigation.extra.ExtraFunctions;
import yashagarwal.indoornavigation.filewriting.DataFileWriter;
import yashagarwal.indoornavigation.graph.ScatterPlot;
import yashagarwal.indoornavigation.orientation.GyroscopeDeltaOrientation;
import yashagarwal.indoornavigation.orientation.GyroscopeEulerOrientation;
import yashagarwal.indoornavigation.orientation.MagneticFieldOrientation;
import yashagarwal.indoornavigation.stepcounting.DynamicStepCounter;

import static com.mapbox.mapboxsdk.style.expressions.Expression.exponential;
import static com.mapbox.mapboxsdk.style.expressions.Expression.interpolate;
import static com.mapbox.mapboxsdk.style.expressions.Expression.stop;
import static com.mapbox.mapboxsdk.style.expressions.Expression.zoom;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.fillColor;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.fillOpacity;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.lineColor;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.lineOpacity;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.lineWidth;

public class GraphActivity extends AppCompatActivity implements SensorEventListener, LocationListener {

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
    private float[] mRotation = null;
    private float rMat[] = new float[16];
    private float orientation[] = new float[3];
    private float mAzimuth = 0.0f;

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

    private GeoJsonSource indoorBuildingSource;

    private List<List<Point>> boundingBoxList;
    private View levelButtons;
    private MapView mapView;

    @TargetApi(Build.VERSION_CODES.KITKAT)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Mapbox.getInstance(this, getString(R.string.access_token));

        setContentView(R.layout.activity_map);

        // get location permission
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(GraphActivity.this, new String[] {
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

        //getting global settings
//        strideLength = getIntent().getFloatExtra("stride_length", 2.5f);
//        isCalibrated = getIntent().getBooleanExtra("is_calibrated", false);
//        gyroBias = getIntent().getFloatArrayExtra("gyro_bias");
//        magBias = getIntent().getFloatArrayExtra("mag_bias");

        strideLength = 0.9f;
        isCalibrated = false;
        gyroBias = new float[3];
        magBias = new float[3];

        //using user_name to get index of user in userList, which is also the index of the user's stride_length
//        counterSensitivity = getIntent().getStringExtra("preferred_step_counter");
//
//        //usingDefaultCounter is counterSensitivity = "default" and sensor is available
//        usingDefaultCounter = counterSensitivity.equals("default") &&
//                getIntent().getBooleanExtra("step_detector", false);

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
//        Toast.makeText(GraphActivity.this, "Stride Length: " + strideLength, Toast.LENGTH_SHORT).show();

        //starting GPS location tracking
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, GraphActivity.this);

        //starting sensors
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        sensorManager.registerListener(GraphActivity.this,
                sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY),
                SensorManager.SENSOR_DELAY_FASTEST);

        if (isCalibrated) {
            sensorManager.registerListener(GraphActivity.this,
                    sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED),
                    SensorManager.SENSOR_DELAY_FASTEST);
            sensorManager.registerListener(GraphActivity.this,
                    sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED),
                    SensorManager.SENSOR_DELAY_FASTEST);
        } else {
            sensorManager.registerListener(GraphActivity.this,
                    sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
                    SensorManager.SENSOR_DELAY_FASTEST);
            sensorManager.registerListener(GraphActivity.this,
                    sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
                    SensorManager.SENSOR_DELAY_FASTEST);
        }

        if (usingDefaultCounter) {
            sensorManager.registerListener(GraphActivity.this,
                    sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR),
                    SensorManager.SENSOR_DELAY_FASTEST);
        } else {
            sensorManager.registerListener(GraphActivity.this,
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

                    fabButton.setImageDrawable(ContextCompat.getDrawable(GraphActivity.this, R.drawable.ic_pause_black_24dp));

                } else {

                    firstRun = true;
                    isRunning = false;

                    fabButton.setImageDrawable(ContextCompat.getDrawable(GraphActivity.this, R.drawable.ic_play_arrow_black_24dp));

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

                scatterPlot.addPoint(rPointX, rPointY);

                mLinearLayout.removeAllViews();
                mLinearLayout.addView(scatterPlot.getGraphView(getApplicationContext()));

            }
        });

//        final ImageView imageView = (ImageView)findViewById(R.id.arrow_icon);
//        final Handler handler = new Handler();
//        final long period = 250;
//        new Timer().schedule(new TimerTask() {
//            @Override
//            public void run() {
//                // do your task here
//                imageView.setRotation(current);
//                current += 2;
//            }
//        }, 0, period);

//        load_map();
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
                ActivityCompat.requestPermissions(GraphActivity.this, new String[] {
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                },0);
                finish();
            }

            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, GraphActivity.this);

            if (isCalibrated) {
                sensorManager.registerListener(GraphActivity.this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED),
                        SensorManager.SENSOR_DELAY_FASTEST);
                sensorManager.registerListener(GraphActivity.this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED),
                        SensorManager.SENSOR_DELAY_FASTEST);
            } else {
                sensorManager.registerListener(GraphActivity.this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
                        SensorManager.SENSOR_DELAY_FASTEST);
                sensorManager.registerListener(GraphActivity.this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
                        SensorManager.SENSOR_DELAY_FASTEST);
            }

            if (usingDefaultCounter) {
                sensorManager.registerListener(GraphActivity.this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR),
                        SensorManager.SENSOR_DELAY_FASTEST);
            } else {
                sensorManager.registerListener(GraphActivity.this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION),
                        SensorManager.SENSOR_DELAY_FASTEST);
            }

            fabButton.setImageDrawable(ContextCompat.getDrawable(GraphActivity.this, R.drawable.ic_pause_black_24dp));

        } else {

            fabButton.setImageDrawable(ContextCompat.getDrawable(GraphActivity.this, R.drawable.ic_play_arrow_black_24dp));

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
//        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
//            mRotation = event.values.clone();
//            if (mRotation != null) {
//                float RmatVector[] = new float[16];
//                SensorManager.getRotationMatrixFromVector(RmatVector, mRotation);
//                SensorManager.remapCoordinateSystem(RmatVector, SensorManager.AXIS_X, SensorManager.AXIS_Z, rMat);
//                SensorManager.getOrientation(rMat, orientation);
//                mAzimuth = (float)Math.toDegrees((double)orientation[0]);  //azimuth
//                if (mAzimuth < 0) {
//                    mAzimuth = 360 + mAzimuth;
//                }
//            }
//            Toast.makeText(this, "Direction Changed", Toast.LENGTH_SHORT).show();
//            mRotation = null;
//            float rotation = -mAzimuth * 360 / (2 * 3.14159f);
//            arrowImageView.setRotation(rotation);
//        }

//        mRotation = event.values.clone();
//        if (mRotation != null) {
//            float RmatVector[] = new float[16];
//            SensorManager.getRotationMatrixFromVector(RmatVector, mRotation);
//            SensorManager.remapCoordinateSystem(RmatVector, SensorManager.AXIS_X, SensorManager.AXIS_Z, rMat);
//            SensorManager.getOrientation(rMat, orientation);
//            mAzimuth = (float)Math.toDegrees((double)orientation[0]);  //azimuth
//            if (mAzimuth < 0) {
//                mAzimuth = 360 + mAzimuth;
//            }
//        }
//        mRotation = null;

        float degree = Math.round(event.values[0]);
        RotateAnimation ra = new RotateAnimation(
                current,
                -degree,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF,
                0.5f);

        ra.setDuration(210);

        ra.setFillAfter(true);
        if (Math.abs(current + degree) > 10)
            arrowImageView.startAnimation(ra);
        current = -degree;

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
                    Toast.makeText(GraphActivity.this, "Thank you for providing permission!", Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    Toast.makeText(GraphActivity.this, "Need location permission to create tour.", Toast.LENGTH_LONG).show();
                    finish();
                }
                break;
        }
    }

    private void load_map() {
        mapView = findViewById(R.id.mapView);
        mapView.getMapAsync(new OnMapReadyCallback() {
            @Override
            public void onMapReady(@NonNull final MapboxMap mapboxMap) {
                mapboxMap.setStyle(Style.MAPBOX_STREETS, new Style.OnStyleLoaded() {
                    @Override
                    public void onStyleLoaded(@NonNull Style style) {

                        final List<Point> boundingBox = new ArrayList<>();

                        boundingBox.add(Point.fromLngLat(-77.03791, 38.89715));
                        boundingBox.add(Point.fromLngLat(-77.03791, 38.89811));
                        boundingBox.add(Point.fromLngLat(-77.03532, 38.89811));
                        boundingBox.add(Point.fromLngLat(-77.03532, 38.89708));

                        boundingBoxList = new ArrayList<>();
                        boundingBoxList.add(boundingBox);

                        mapboxMap.addOnCameraMoveListener(new MapboxMap.OnCameraMoveListener() {
                            @Override
                            public void onCameraMove() {
                                if (mapboxMap.getCameraPosition().zoom > 16) {
                                    if (TurfJoins.inside(Point.fromLngLat(mapboxMap.getCameraPosition().target.getLongitude(),
                                            mapboxMap.getCameraPosition().target.getLatitude()), Polygon.fromLngLats(boundingBoxList))) {
                                        if (levelButtons.getVisibility() != View.VISIBLE) {
//                                            showLevelButton();
                                        }
                                    } else {
                                        if (levelButtons.getVisibility() == View.VISIBLE) {
//                                            hideLevelButton();
                                        }
                                    }
                                } else if (levelButtons.getVisibility() == View.VISIBLE) {
//                                    hideLevelButton();
                                }
                            }
                        });
                        indoorBuildingSource = new GeoJsonSource(
                                "indoor-building", loadJsonFromAsset("white_house_lvl_0.geojson"));
                        style.addSource(indoorBuildingSource);

// Add the building layers since we know zoom levels in range
                        loadBuildingLayer(style);
                    }
                });

//                Button buttonSecondLevel = findViewById(R.id.second_level_button);
//                buttonSecondLevel.setOnClickListener(new View.OnClickListener() {
//                    @Override
//                    public void onClick(View view) {
//                        indoorBuildingSource.setGeoJson(loadJsonFromAsset("white_house_lvl_1.geojson"));
//                    }
//                });
//
//                Button buttonGroundLevel = findViewById(R.id.ground_level_button);
//                buttonGroundLevel.setOnClickListener(new View.OnClickListener() {
//                    @Override
//                    public void onClick(View view) {
//                        indoorBuildingSource.setGeoJson(loadJsonFromAsset("white_house_lvl_0.geojson"));
//                    }
//                });
            }
        });
    }

    private void loadBuildingLayer(@NonNull Style style) {
// Method used to load the indoor layer on the map. First the fill layer is drawn and then the
// line layer is added.

        FillLayer indoorBuildingLayer = new FillLayer("indoor-building-fill", "indoor-building").withProperties(
                fillColor(Color.parseColor("#eeeeee")),
// Function.zoom is used here to fade out the indoor layer if zoom level is beyond 16. Only
// necessary to show the indoor map at high zoom levels.
                fillOpacity(interpolate(exponential(1f), zoom(),
                        stop(16f, 0f),
                        stop(16.5f, 0.5f),
                        stop(17f, 1f))));

        style.addLayer(indoorBuildingLayer);

        LineLayer indoorBuildingLineLayer = new LineLayer("indoor-building-line", "indoor-building").withProperties(
                lineColor(Color.parseColor("#50667f")),
                lineWidth(0.5f),
                lineOpacity(interpolate(exponential(1f), zoom(),
                        stop(16f, 0f),
                        stop(16.5f, 0.5f),
                        stop(17f, 1f))));
        style.addLayer(indoorBuildingLineLayer);
    }

    private String loadJsonFromAsset(String filename) {
// Using this method to load in GeoJSON files from the assets folder.

        try {
            InputStream is = getAssets().open(filename);
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            return new String(buffer, Charset.forName("UTF-8"));

        } catch (IOException ex) {
            ex.printStackTrace();
            return null;
        }
    }

}

