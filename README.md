# Flutter Device apps plugin

Plugin to get the list of installed applications (iOS is not supported yet).

## Getting Started

First, you have to import the package in your dart files with:
```dart
import 'package:device_apps/device_apps.dart';
```

## List of installed applications

To get the list of the apps installed on the device:

```dart
List<Application> apps = await DeviceApps.getInstalledApplications();
```

You can filter system apps if necessary.
Note: The list of apps is not ordered!


## Get an application

To get a specific app by package name:

```dart
Application app = await DeviceApps.getApp('com.frandroid.app');
```

## Check if an application is installed

To check if an app is installed (via its package name):

```dart
bool isInstalled = await DeviceApps.isAppInstalled('com.frandroid.app');
```

## Open an application

To open an application
```dart
DeviceApps.openApp('com.frandroid.app');
```

## Displaying app icon

App Icon is received as a BASE64 Encoded string. To display the icon. `dart:convert` is required to decode base64 string.

```dart
import 'dart:convert';
Image.memory(base64.decode(app.icon))
```




