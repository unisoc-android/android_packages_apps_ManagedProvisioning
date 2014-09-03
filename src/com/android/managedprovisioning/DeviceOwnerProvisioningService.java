/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.managedprovisioning;

import android.app.AlarmManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;

import com.android.internal.app.LocalePicker;
import com.android.managedprovisioning.task.AddWifiNetworkTask;
import com.android.managedprovisioning.task.DeleteNonRequiredAppsTask;
import com.android.managedprovisioning.task.DownloadPackageTask;
import com.android.managedprovisioning.task.InstallPackageTask;
import com.android.managedprovisioning.task.SetDevicePolicyTask;

import java.lang.Runnable;
import java.util.Locale;

/**
 * This service does the work for the DeviceOwnerProvisioningActivity.
 * Feedback is sent back to the activity via intents.
 *
 * <p>
 * If the corresponding activity is killed and restarted, the service is
 * called twice. The service will not start the provisioning flow a second time, but instead
 * send a status update to the activity.
 * </p>
 */
public class DeviceOwnerProvisioningService extends Service {
    /**
     * Intent action to activate the CDMA phone connection by OTASP.
     * This is not necessary for a GSM phone connection, which is activated automatically.
     * String must agree with the constants in com.android.phone.InCallScreenShowActivation.
     */
    private static final String ACTION_PERFORM_CDMA_PROVISIONING =
            "com.android.phone.PERFORM_CDMA_PROVISIONING";

    // Intent actions and extras for communication from DeviceOwnerProvisioningService to Activity.
    protected static final String EXTRA_PROVISIONING_PARAMS =
            "ProvisioningParams";

    // Intent actions and extras for communication from DeviceOwnerProvisioningActivity to Service.
    protected static final String ACTION_PROVISIONING_SUCCESS =
            "com.android.managedprovisioning.provisioning_success";
    protected static final String ACTION_PROVISIONING_ERROR =
            "com.android.managedprovisioning.error";
    protected static final String EXTRA_USER_VISIBLE_ERROR_ID_KEY =
            "UserVisibleErrorMessage-Id";
    protected static final String ACTION_PROGRESS_UPDATE =
            "com.android.managedprovisioning.progress_update";
    protected static final String EXTRA_PROGRESS_MESSAGE_ID_KEY =
            "ProgressMessageId";
    protected static final String ACTION_REQUEST_WIFI_PICK =
            "com.android.managedprovisioning.request_wifi_pick";

    // Indicates whether provisioning has started.
    private boolean mProvisioningInFlight = false;

    // MessageId of the last progress message.
    private int mLastProgressMessage = -1;

    // MessageId of the last error message.
    private int mLastErrorMessage = -1;

    // Indicates whether provisioning has finished succesfully (service waiting to stop).
    private boolean mDone = false;

    // Provisioning tasks.
    private AddWifiNetworkTask mAddWifiNetworkTask;
    private DownloadPackageTask mDownloadPackageTask;
    private InstallPackageTask mInstallPackageTask;
    private SetDevicePolicyTask mSetDevicePolicyTask;
    private DeleteNonRequiredAppsTask mDeleteNonRequiredAppsTask;

    private ProvisioningParams mParams;

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        ProvisionLogger.logd("Device owner provisioning service ONSTARTCOMMAND.");

        synchronized (this) { // Make operations on mProvisioningInFlight atomic.
            if (mProvisioningInFlight) {
                ProvisionLogger.logd("Provisioning already in flight.");

                sendProgressUpdateToActivity();

                // Send error message if currently in error state.
                if (mLastErrorMessage >= 0) {
                    sendError();
                }

                // Send success if provisioning was succesful.
                if (mDone) {
                    onProvisioningSuccess(mParams.mDeviceAdminPackageName);
                }
            } else {
                mProvisioningInFlight = true;
                ProvisionLogger.logd("First start of the service.");
                progressUpdate(R.string.progress_data_process);

                // Load the ProvisioningParams (from message in Intent).
                mParams = (ProvisioningParams) intent.getParcelableExtra(EXTRA_PROVISIONING_PARAMS);

                // Do the work on a separate thread.
                new Thread(new Runnable() {
                        public void run() {
                            initializeProvisioningEnvironment(mParams);
                            startDeviceOwnerProvisioning(mParams);
                        }
                    }).start();
            }
        }
        return START_NOT_STICKY;
    }

    /**
     * This is the core method of this class. It goes through every provisioning step.
     */
    private void startDeviceOwnerProvisioning(final ProvisioningParams params) {
        ProvisionLogger.logd("Starting device owner provisioning");

        // Construct Tasks. Do not start them yet.
        mAddWifiNetworkTask = new AddWifiNetworkTask(this, params.mWifiSsid,
                params.mWifiHidden, params.mWifiSecurityType, params.mWifiPassword,
                params.mWifiProxyHost, params.mWifiProxyPort, params.mWifiProxyBypassHosts,
                params.mWifiPacUrl, new AddWifiNetworkTask.Callback() {
                        @Override
                        public void onSuccess() {
                            progressUpdate(R.string.progress_download);
                            mDownloadPackageTask.run();
                        }

                        @Override
                        public void onError(){
                            error(R.string.device_owner_error_wifi);
                        }
                });

        mDownloadPackageTask = new DownloadPackageTask(this,
                params.mDeviceAdminPackageDownloadLocation, params.mDeviceAdminPackageChecksum,
                params.mDeviceAdminPackageDownloadCookieHeader, new DownloadPackageTask.Callback() {
                        @Override
                        public void onSuccess() {
                            String downloadLocation =
                                    mDownloadPackageTask.getDownloadedPackageLocation();
                            progressUpdate(R.string.progress_install);
                            mInstallPackageTask.run(downloadLocation);
                        }

                        @Override
                        public void onError(int errorCode) {
                            switch(errorCode) {
                                case DownloadPackageTask.ERROR_HASH_MISMATCH:
                                    error(R.string.device_owner_error_hash_mismatch);
                                    break;
                                case DownloadPackageTask.ERROR_DOWNLOAD_FAILED:
                                    error(R.string.device_owner_error_download_failed);
                                    break;
                                default:
                                    error(R.string.device_owner_error_general);
                                    break;
                            }
                        }
                    });

        mInstallPackageTask = new InstallPackageTask(this,
                params.mDeviceAdminPackageName,
                new InstallPackageTask.Callback() {
                    @Override
                    public void onSuccess() {
                        progressUpdate(R.string.progress_set_owner);
                        mSetDevicePolicyTask.run();
                    }

                    @Override
                    public void onError(int errorCode) {
                        switch(errorCode) {
                            case InstallPackageTask.ERROR_PACKAGE_INVALID:
                                error(R.string.device_owner_error_package_invalid);
                                break;
                            case InstallPackageTask.ERROR_INSTALLATION_FAILED:
                                error(R.string.device_owner_error_installation_failed);
                                break;
                            default:
                                error(R.string.device_owner_error_general);
                                break;
                        }
                    }
                });

        mSetDevicePolicyTask = new SetDevicePolicyTask(this,
                params.mDeviceAdminPackageName,
                getResources().getString(R.string.default_owned_device_username),
                new SetDevicePolicyTask.Callback() {
                    @Override
                    public void onSuccess() {
                        mDeleteNonRequiredAppsTask.run();
                    }

                    @Override
                    public void onError(int errorCode) {
                        switch(errorCode) {
                            case SetDevicePolicyTask.ERROR_PACKAGE_NOT_INSTALLED:
                                error(R.string.device_owner_error_package_not_installed);
                                break;
                            case SetDevicePolicyTask.ERROR_NO_RECEIVER:
                                error(R.string.device_owner_error_package_invalid);
                                break;
                            default:
                                error(R.string.device_owner_error_general);
                                break;
                        }
                    }
                });

        mDeleteNonRequiredAppsTask =  new DeleteNonRequiredAppsTask(
                this, params.mDeviceAdminPackageName, 0 /* primary user's UserId */,
                R.array.required_apps_managed_device, R.array.vendor_required_apps_managed_device,
                new DeleteNonRequiredAppsTask.Callback() {
                    public void onSuccess() {
                        // Done with provisioning. Success.
                        onProvisioningSuccess(params.mDeviceAdminPackageName);
                    }

                    @Override
                    public void onError() {
                        error(R.string.device_owner_error_general);
                    };
                });

        // Start first task, which starts next task in its callback, etc.
        if (!TextUtils.isEmpty(params.mDeviceAdminPackageDownloadLocation)) {
            // Device Admin has to be downloaded:
            // Connect to wifi, download, install, set as device owner, delete apps.
            progressUpdate(R.string.progress_connect_to_wifi);
            mAddWifiNetworkTask.run();
        } else {
            // Device Admin will not be downloaded (but is already present):
            // Just set as device owner, delete apps.
            progressUpdate(R.string.progress_set_owner);
            mSetDevicePolicyTask.run();
        }
    }

    private void error(int dialogMessage) {
        mLastErrorMessage = dialogMessage;
        sendError();
        // Wait for stopService() call from the activity.
    }

    private void sendError() {
        ProvisionLogger.logd("Reporting Error: " + getResources().getString(mLastErrorMessage));
        Intent intent = new Intent(ACTION_PROVISIONING_ERROR);
        intent.setClass(this, DeviceOwnerProvisioningActivity.ServiceMessageReceiver.class);
        intent.putExtra(EXTRA_USER_VISIBLE_ERROR_ID_KEY, mLastErrorMessage);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void progressUpdate(int progressMessage) {
        ProvisionLogger.logd("Reporting progress update: "
                + getResources().getString(progressMessage));
        mLastProgressMessage = progressMessage;
        sendProgressUpdateToActivity();
    }

    private void sendProgressUpdateToActivity() {
        Intent intent = new Intent(ACTION_PROGRESS_UPDATE);
        intent.putExtra(EXTRA_PROGRESS_MESSAGE_ID_KEY, mLastProgressMessage);
        intent.setClass(this, DeviceOwnerProvisioningActivity.ServiceMessageReceiver.class);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void onProvisioningSuccess(String deviceAdminPackage) {
        ProvisionLogger.logv("Reporting success.");
        mDone = true;
        Intent successIntent = new Intent(ACTION_PROVISIONING_SUCCESS);
        successIntent.putExtra(EXTRA_PROVISIONING_PARAMS, mParams);
        successIntent.setClass(this, DeviceOwnerProvisioningActivity.ServiceMessageReceiver.class);
        LocalBroadcastManager.getInstance(this).sendBroadcast(successIntent);
        // Wait for stopService() call from the activity.
    }

    private void initializeProvisioningEnvironment(ProvisioningParams params) {
        setTimeAndTimezone(params.mTimeZone, params.mLocalTime);
        setLocale(params.mLocale);

        // Start CDMA activation to enable phone calls.
        final Intent intent = new Intent(ACTION_PERFORM_CDMA_PROVISIONING);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ProvisionLogger.logv("Starting cdma activation activity");
        startActivity(intent); // Activity will be a Nop if not a CDMA device.
    }

    private void setTimeAndTimezone(String timeZone, long localTime) {
        try {
            final AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (timeZone != null) {
                ProvisionLogger.logd("Setting time zone to " + timeZone);
                am.setTimeZone(timeZone);
            }
            if (localTime > 0) {
                ProvisionLogger.logd("Setting time to " + localTime);
                am.setTime(localTime);
            }
        } catch (Exception e) {
            ProvisionLogger.loge("Alarm manager failed to set the system time/timezone.");
            // Do not stop provisioning process, but ignore this error.
        }
    }

    private void setLocale(Locale locale) {
        if (locale == null || locale.equals(Locale.getDefault())) {
            return;
        }
        try {
            ProvisionLogger.logd("Setting locale to " + locale);
            // If locale is different from current locale this results in a configuration change,
            // which will trigger the restarting of the activity.
            LocalePicker.updateLocale(locale);
        } catch (Exception e) {
            ProvisionLogger.loge("Failed to set the system locale.");
            // Do not stop provisioning process, but ignore this error.
        }
    }

    @Override
    public void onCreate () {
        ProvisionLogger.logd("Device owner provisioning service ONCREATE.");
    }

    @Override
    public void onDestroy () {
        ProvisionLogger.logd("Device owner provisioning service ONDESTROY");
        if (mAddWifiNetworkTask != null) {
            mAddWifiNetworkTask.cleanUp();
        }
        if (mDownloadPackageTask != null) {
            mDownloadPackageTask.cleanUp();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

