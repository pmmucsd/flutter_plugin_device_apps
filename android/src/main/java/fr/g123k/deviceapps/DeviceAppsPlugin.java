package fr.g123k.deviceapps;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherActivityInfo;
import android.os.UserHandle;
import android.os.UserManager;


import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fr.g123k.deviceapps.listener.DeviceAppsChangedListener;
import fr.g123k.deviceapps.listener.DeviceAppsChangedListenerInterface;
import fr.g123k.deviceapps.utils.AppDataConstants;
import fr.g123k.deviceapps.utils.AppDataEventConstants;
import fr.g123k.deviceapps.utils.IntentUtils;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

import static fr.g123k.deviceapps.utils.Base64Utils.encodeToBase64;
import static fr.g123k.deviceapps.utils.DrawableUtils.getBitmapFromDrawable;

/**
 * DeviceAppsPlugin
 */
public class DeviceAppsPlugin implements
        FlutterPlugin,
        MethodCallHandler,
        EventChannel.StreamHandler,
        DeviceAppsChangedListenerInterface {

    private static final String LOG_TAG = "DEVICE_APPS";
    private static final int SYSTEM_APP_MASK = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;

    private final AsyncWork asyncWork;

    private MethodChannel methodChannel;
    private EventChannel eventChannel;
    private DeviceAppsChangedListener appsListener;


    public DeviceAppsPlugin() {
        this.asyncWork = new AsyncWork();
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        context = binding.getApplicationContext();

        BinaryMessenger messenger = binding.getBinaryMessenger();
        methodChannel = new MethodChannel(messenger, "g123k/device_apps");
        methodChannel.setMethodCallHandler(this);

        eventChannel = new EventChannel(messenger, "g123k/device_apps_events");
        eventChannel.setStreamHandler(this);
    }

    private Context context;

    @Override
    @SuppressWarnings("ConstantConditions")
    public void onMethodCall(MethodCall call, @NonNull final Result result) {
        switch (call.method) {
            case "getInstalledApps":
                boolean systemApps = call.hasArgument("system_apps") && (Boolean) (call.argument("system_apps"));
                boolean includeAppIcons = call.hasArgument("include_app_icons") && (Boolean) (call.argument("include_app_icons"));
                boolean onlyAppsWithLaunchIntent = call.hasArgument("only_apps_with_launch_intent") && (Boolean) (call.argument("only_apps_with_launch_intent"));
                fetchInstalledApps(systemApps, includeAppIcons, onlyAppsWithLaunchIntent, new InstalledAppsCallback() {
                    @Override
                    public void onInstalledAppsListAvailable(final List<Map<String, Object>> apps) {
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                result.success(apps);
                            }
                        });
                    }
                });
                break;
            case "getApp":
                if (!call.hasArgument("package_name") || TextUtils.isEmpty(call.argument("package_name").toString())) {
                    result.error("ERROR", "Empty or null package name", null);
                } else {
                    String packageName = call.argument("package_name").toString();
                    boolean includeAppIcon = call.hasArgument("include_app_icon") && (Boolean) (call.argument("include_app_icon"));
                    result.success(getApp(packageName, includeAppIcon));
                }
                break;
            case "isAppInstalled":
                if (!call.hasArgument("package_name") || TextUtils.isEmpty(call.argument("package_name").toString())) {
                    result.error("ERROR", "Empty or null package name", null);
                } else {
                    String packageName = call.argument("package_name").toString();
                    result.success(isAppInstalled(packageName));
                }
                break;
            case "openApp":
                if (!call.hasArgument("package_name") || TextUtils.isEmpty(call.argument("package_name").toString())) {
                    result.error("ERROR", "Empty or null package name", null);
                } else if (!call.hasArgument("profile")) {
                    result.error("ERROR", "Profile not specified", null);
                } else {
                    String packageName = call.argument("package_name").toString();
                    // Assuming the profile is passed as a String or some identifiable format
                    int profileId = call.argument("profile");

                    // Convert the profileId to UserHandle (the specific implementation depends on how you're handling UserHandle ids)
                    UserHandle profile = getUserHandleForId(profileId);

                    result.success(openApp(packageName, profile));
                }
                break;
            case "openAppSettings":
                if (!call.hasArgument("package_name") || TextUtils.isEmpty(call.argument("package_name").toString())) {
                    result.error("ERROR", "Empty or null package name", null);
                } else {
                    String packageName = call.argument("package_name").toString();
                    result.success(openAppSettings(packageName));
                }
                break;
            case "uninstallApp":
                if (!call.hasArgument("package_name") || TextUtils.isEmpty(call.argument("package_name").toString())) {
                    result.error("ERROR", "Empty or null package name", null);
                } else {
                    String packageName = call.argument("package_name").toString();
                    result.success(uninstallApp(packageName));
                }
                break;
            case "getWorkProfileId":
                result.success(getWorkProfileId());
                break;
            default:
                result.notImplemented();
        }
    }

    private void fetchInstalledApps(final boolean includeSystemApps, final boolean includeAppIcons, final boolean onlyAppsWithLaunchIntent, final InstalledAppsCallback callback) {
        asyncWork.run(new Runnable() {

            @Override
            public void run() {
                List<Map<String, Object>> installedApps = getInstalledApps(includeSystemApps, includeAppIcons, onlyAppsWithLaunchIntent);

                if (callback != null) {
                    callback.onInstalledAppsListAvailable(installedApps);
                }
            }

        });
    }

    private List<Map<String, Object>> getInstalledApps(boolean includeSystemApps, boolean includeAppIcons, boolean onlyAppsWithLaunchIntent) {
        if (context == null) {
            Log.e(LOG_TAG, "Context is null");
            return new ArrayList<>(0);
        }

        LauncherApps launcherApps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        List<UserHandle> profiles = launcherApps.getProfiles();
        List<LauncherActivityInfo> personalApps = new ArrayList<>();
        List<LauncherActivityInfo> workApps = new ArrayList<>();
        List<LauncherActivityInfo> allApps = new ArrayList<>();
        UserHandle workProfileId = null;
        UserHandle nonWorkProfileId = null;

        if (!profiles.isEmpty()) {
            nonWorkProfileId = profiles.get(0); // Assign the first profile to nonWorkProfileId

            if (profiles.size() > 1) {
                workProfileId = profiles.get(1); // Assign the second profile to workProfileId
            }
        }


        for (UserHandle profile : profiles) {
            List<LauncherActivityInfo> activities = launcherApps.getActivityList(null, profile);

            // Check if the profile is a work profile
            boolean isWorkProfile = hasManagedProfile();

            if (profile == workProfileId) {
                workApps.addAll(activities);
            } else {
                personalApps.addAll(activities);
            }
        }

        allApps.addAll(personalApps);
        allApps.addAll(workApps);
        List<Map<String, Object>> installedWorkApps = new ArrayList<>();
        List<Map<String, Object>> installedApps = new ArrayList<>();
        List<Map<String, Object>> installedAllApps = new ArrayList<>();



        for (LauncherActivityInfo activityInfo : workApps) {
            ApplicationInfo appInfo = activityInfo.getApplicationInfo();

            if (!includeSystemApps && isSystemApp(appInfo)) {
                continue;
            }
            if (onlyAppsWithLaunchIntent && launcherApps.getLaunchIntentForPackage(appInfo.packageName, workProfileId) == null) {
                continue;
            }

            Map<String, Object> map = getAppData(launcherApps,
                    activityInfo,
                    appInfo,
                    includeAppIcons, workProfileId.hashCode());
            installedWorkApps.add(map);
        }

        for (LauncherActivityInfo activityInfo : personalApps) {
            ApplicationInfo appInfo = activityInfo.getApplicationInfo();
            if (!includeSystemApps && isSystemApp(appInfo)) {
                continue;
            }
            if (onlyAppsWithLaunchIntent && launcherApps.getLaunchIntentForPackage(appInfo.packageName, nonWorkProfileId) == null) {
                continue;
            }

            Map<String, Object> map = getAppData(launcherApps,
                    activityInfo,
                    appInfo,
                    includeAppIcons, nonWorkProfileId.hashCode());
            installedApps.add(map);
        }
        installedAllApps.addAll(installedWorkApps);
        installedAllApps.addAll(installedApps);
        return installedAllApps;
    }

        private boolean openApp(@NonNull String packageName, @NonNull UserHandle profile) {
            LauncherApps launcherApps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);

            // Check if the app is available under the given profile
            List<LauncherActivityInfo> activities = launcherApps.getActivityList(packageName, profile);
            for (LauncherActivityInfo activity : activities) {
                // Check if the activity is launchable
                if (activity.getApplicationInfo().packageName.equals(packageName)) {
                    Intent launchIntent = launcherApps.getLaunchIntentForPackage(packageName, profile);
                    if (launchIntent != null && IntentUtils.isIntentOpenable(launchIntent, context)) {
                        context.startActivity(launchIntent);
                        return true;
                    }
                }
            }

            Log.w(LOG_TAG, "Application with package name \"" + packageName + "\" is not installed on this device or profile");
            return false;
        }

    private boolean openAppSettings(@NonNull String packageName) {
        if (!isAppInstalled(packageName)) {
            Log.w(LOG_TAG, "Application with package name \"" + packageName + "\" is not installed on this device");
            return false;
        }

        Intent appSettingsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        appSettingsIntent.setData(Uri.parse("package:" + packageName));
        appSettingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (IntentUtils.isIntentOpenable(appSettingsIntent, context)) {
            context.startActivity(appSettingsIntent);
            return true;
        }

        return false;
    }

    private boolean isSystemApp(ApplicationInfo activityInfo) {
        if (activityInfo == null) {
            return false;
        }
        return (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    private boolean isAppInstalled(@NonNull String packageName) {
        LauncherApps launcherApps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        List<UserHandle> profiles = launcherApps.getProfiles();

        for (UserHandle profile : profiles) {
            List<LauncherActivityInfo> activities = launcherApps.getActivityList(packageName, profile);
            if (!activities.isEmpty()) {
                // Found launcher activities for this package, so the app is installed
                return true;
            }
        }

        // No launcher activities found, app is not installed
        return false;
    }

    private Map<String, Object> getApp(String packageName, boolean includeAppIcon) {
        LauncherApps launcherApps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);

        List<LauncherActivityInfo> activities = launcherApps.getActivityList(packageName);
        if (activities.isEmpty()) {
            // No activities found for the package in the given profile, return null
            return null;
        }

        // Assuming the first activity info is what we need
        LauncherActivityInfo activityInfo = activities.get(0);

        return getAppData(launcherApps,
                activityInfo,
                activityInfo.getApplicationInfo(),
                includeAppIcon);
    }

    private Map<String, Object> getAppData(LauncherApps launcherApps,
                                           LauncherActivityInfo activityInfo,
                                           ApplicationInfo applicationInfo,
                                           boolean includeAppIcon,
                                           int profileId) {
        Map<String, Object> map = new HashMap<>();
        map.put(AppDataConstants.APP_NAME, activityInfo.getLabel().toString());
        map.put(AppDataConstants.APK_FILE_PATH, applicationInfo.sourceDir);
        map.put(AppDataConstants.PACKAGE_NAME, activityInfo.packageName);
        map.put(AppDataConstants.VERSION_CODE, applicationInfo.versionCode);
        map.put(AppDataConstants.VERSION_NAME, applicationInfo.versionName);
        map.put(AppDataConstants.DATA_DIR, applicationInfo.dataDir);
        map.put(AppDataConstants.SYSTEM_APP, isSystemApp(applicationInfo));
        map.put(AppDataConstants.INSTALL_TIME, applicationInfo.firstInstallTime);
        map.put(AppDataConstants.UPDATE_TIME, applicationInfo.lastUpdateTime);
        map.put(AppDataConstants.IS_ENABLED, applicationInfo.enabled);
        map.put(AppDataConstants.PROFILE_ID, profileId);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            map.put(AppDataConstants.CATEGORY, applicationInfo.category);
        }

        if (includeAppIcon) {
            Drawable icon = activityInfo.getBadgedIcon(0);
            String encodedImage = encodeToBase64(getBitmapFromDrawable(icon), Bitmap.CompressFormat.PNG, 100);
            map.put(AppDataConstants.APP_ICON, encodedImage);
        }

        return map;
    }

    private boolean uninstallApp(@NonNull String packageName) {
        if (!isAppInstalled(packageName)) {
            Log.w(LOG_TAG, "Application with package name \"" + packageName + "\" is not installed on this device");
            return false;
        }

        Intent appSettingsIntent = new Intent(Intent.ACTION_DELETE);
        appSettingsIntent.setData(Uri.parse("package:" + packageName));
        appSettingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (IntentUtils.isIntentOpenable(appSettingsIntent, context)) {
            context.startActivity(appSettingsIntent);
            return true;
        }

        return false;
    }

    private UserHandle getUserHandleForId(int profileId) {
        UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        List<UserHandle> profiles = userManager.getUserProfiles();

        for (UserHandle profile : profiles) {
            int id = generateIdForUserHandle(profile); // This method needs to be defined
            if (id == profileId) {
                return profile;
            }
        }

        return null; // Return null if no matching profile is found
    }

    private int generateIdForUserHandle(UserHandle userHandle) {
        // Implement this method to generate a consistent integer ID for a UserHandle
        // The implementation will depend on your app's logic and requirements
        // One potential way (with limitations) could be using UserHandle.hashCode()
        return userHandle.hashCode();
    }

    private int getWorkProfileId() {
        LauncherApps launcherApps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        List<UserHandle> profiles = launcherApps.getProfiles();
        for (UserHandle profile : profiles) {
            if (UserManager.get(context).isManagedProfile(profile)) {
                return generateIdForUserHandle(profile);
            }
        }
        return -1;
    }

    private boolean hasManagedProfile() {
        UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        return userManager.isManagedProfile();
    }

    @Override
    public void onListen(Object arguments, final EventChannel.EventSink events) {
        if (context != null) {
            if (appsListener == null) {
                appsListener = new DeviceAppsChangedListener(this);
            }

            appsListener.register(context, events);
        }
    }

    @Override
    public void onPackageInstalled(String packageName, EventChannel.EventSink events) {
        events.success(getListenerData(packageName, AppDataEventConstants.EVENT_TYPE_INSTALLED));
    }

    @Override
    public void onPackageUpdated(String packageName, EventChannel.EventSink events) {
        events.success(getListenerData(packageName, AppDataEventConstants.EVENT_TYPE_UPDATED));
    }

    @Override
    public void onPackageUninstalled(String packageName, EventChannel.EventSink events) {
        events.success(getListenerData(packageName, AppDataEventConstants.EVENT_TYPE_UNINSTALLED));
    }

    @Override
    public void onPackageChanged(String packageName, EventChannel.EventSink events) {
        Map<String, Object> listenerData = getListenerData(packageName, null);

        if (listenerData.get(AppDataConstants.IS_ENABLED) == Boolean.valueOf(true)) {
            listenerData.put(AppDataEventConstants.EVENT_TYPE, AppDataEventConstants.EVENT_TYPE_DISABLED);
        } else {
            listenerData.put(AppDataEventConstants.EVENT_TYPE, AppDataEventConstants.EVENT_TYPE_ENABLED);
        }

        events.success(listenerData);
    }

    Map<String, Object> getListenerData(String packageName, String event) {
        Map<String, Object> data = getApp(packageName, false);

        // The app is not installed
        if (data == null) {
            data = new HashMap<>(2);
            data.put(AppDataEventConstants.PACKAGE_NAME, packageName);
        }

        if (event != null) {
            data.put(AppDataEventConstants.EVENT_TYPE, event);
        }

        return data;
    }

    @Override
    public void onCancel(Object arguments) {
        if (context != null && appsListener != null) {
            appsListener.unregister(context);
        }
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        asyncWork.stop();

        if (methodChannel != null) {
            methodChannel.setMethodCallHandler(null);
            methodChannel = null;
        }

        if (eventChannel != null) {
            eventChannel.setStreamHandler(null);
            eventChannel = null;
        }

        if (appsListener != null) {
            appsListener.unregister(context);
            appsListener = null;
        }

        context = null;
    }
}
