# Indoor Mapping and Navigation
This project includes the opensource code for mapping indoor environments using Intel Realsense Cameras and navigation on Android devices. 

> Please note that the project is under active planning and development phase. The developers are exploring various strategies and expect drastic changes throughout the next few months.

## Mapping
So far, we have explored various techniques for the mapping urban indoor environments. We have the Intel RealSense Depth Camera - The D435i and T265 available. 

We have settled upon a modified RTABMAP algorithm for mapping the database. Included are the source files which can be compiled using the procedure available at [RTABMAP repo](https://github.com/introlab/rtabmap.git).

 Place the files in directory ``rtabmap/src`` within a cloned repository for Rtabmap before compiling. 
 
 
Now, install the [RTABMAP-ROS](https://github.com/introlab/rtabmap-ros.git) ROS package within your catkin workspace and build it. You may also use the official packages available on apt on ubuntu.

To enable tracking using a RealSense T265 along with SLAM using the D435i, use the launch file d400_t265_camera.launch in ``launch`` directory.

```roslaunch launch/d400_t265_camera.launch```

To launch Rtabmap ROS node and an RVIZ visualizer, use the RTABMAP.launch launch file

```roslaunch launch/RTABMAP.launch```


## Android Odometry
Android odometry is achieved using an extended kalman filter with the gyroscope, accelerometer and magnetometers available on board. 
The ``android_localization_app`` directory includes the source code for this purpose. We are working on importing maps saved using RTAB-Map on non-rooted android. Our preferred strategy involves creating a 2D occupancy grid for this purpose with location markers across the environment.

