# AndroControl
AndroControl is an app that allows you to control your linux system's mouse and keyboard through your android smart phone.
This is still in early stages of development, thus the functionality is still limited and no security features are implemented.

## Architecture
The solution follows a client-server architecture where a python based listener
running on the target system to receive control signals through a TCP socket
and a java based android app to send mouse and keyboard inputs to the listener.

The listener interprets the control signals sent by the application and send
appropriate commands through python-uinput library.

## What Works
Currently the application only support linux environments and mouse controls only.

## TODOs
+ Implement proper keyboard controls.
+ Functionality to remember servers on frontend.
+ Implementing an authentication mechanism.
+ Adding TLS to secure the socket communication.

## How To Install
### Prerequisities
+ Make sure that you have the "uinput" kernel module installed with "sudo modprobe -i uinput".
+ To load uinput module on system boot add it to "/etc/modules".
+ To verify the module installation run "lsmod | grep uinput", this should output something like "uinput                 20480  1".
+ Both the target linux system and the smartphone must be in the same local area network and firewall must allow TCP traffic via port 5050.

### Setting up the Listner
+ Download the server.py and move it to your desired location.
+ Create a python virtual environment with "python -m venv .venv".
+ Activate the virtual env with "source .venv/bin/activate".
+ Install "uinput" with "pip install python-uinput".
+ Start the listener with "python server.py".

### Setting up the App
+ Download the APK and install it on your desired smartphone (must be higher than android lolipop).
+ Enter the IP address of the linux system where the server is listening.
+ Enjoy :)

## Functionality
+ Use the Mouse Area to move the cursor arround.
+ You can use the buttons below to simulate button click events.
+ Also you can tap anywhere on the touchpad to send a left click event.
+ You can use two finger swipe to scroll.

## Screenshots
<img src="https://github.com/Arana-Jayavihan/AndroControl/blob/main/Assets/UI.png?raw=true"  width="200" height="400" >

## Discliamer
I will be not responsible for any potential damanges or risks involved with this application so use it at your own risk.
