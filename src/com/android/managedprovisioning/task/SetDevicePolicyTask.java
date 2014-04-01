/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.managedprovisioning.task;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.text.TextUtils;

import com.android.managedprovisioning.ProvisionLogger;

public class SetDevicePolicyTask {
    public static final int ERROR_PACKAGE_NOT_INSTALLED = 0;
    public static final int ERROR_OTHER = 1;

    private Callback mCallback;
    private Context mContext;
    private String mPackageName;
    private String mAdminReceiver;
    private String mOwner;
    private PackageManager mPackageManager;
    private DevicePolicyManager mDevicePolicyManager;

    public SetDevicePolicyTask(Context context, String packageName, String adminReceiver,
            String owner) {
        mContext = context;
        mPackageName = packageName;
        mAdminReceiver = adminReceiver;
        mOwner = owner;
        mPackageManager = mContext.getPackageManager();
        mDevicePolicyManager = (DevicePolicyManager) mContext.
                getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    public void run() {
        // Check whether package is installed.
        // Relevant when it is not downloaded and installed by the DeviceOwnerProvisioningActivity.
        if (!isPackageInstalled()) {
            mCallback.onError(ERROR_PACKAGE_NOT_INSTALLED);
            return;
        } else {
            enableDevicePolicyApp();
            setActiveAdmin();
            setDeviceOwner();
            mCallback.onSuccess();
        }
    }

    private boolean isPackageInstalled() {
        try {
            mPackageManager.getPackageInfo(mPackageName, 0);
        } catch (NameNotFoundException e) {
            return false;
        }
        return true;
    }

    private void enableDevicePolicyApp() {
        int enabledSetting = mPackageManager
                .getApplicationEnabledSetting(mPackageName);
        if (enabledSetting != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            mPackageManager.setApplicationEnabledSetting(mPackageName,
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, 0);
        }
    }

    public void setActiveAdmin() {
        ProvisionLogger.logd("Setting " + mPackageName + " as active admin.");
        ComponentName component = new ComponentName(mPackageName, mAdminReceiver);
        mDevicePolicyManager.setActiveAdmin(component, true);
    }

    public void setDeviceOwner() {
        ProvisionLogger.logd("Setting " + mPackageName + " as device owner " + mOwner + ".");
        if (!mDevicePolicyManager.isDeviceOwner(mPackageName)) {
            mDevicePolicyManager.setDeviceOwner(mPackageName, mOwner);
        }
    }

    public abstract static class Callback {
        public abstract void onSuccess();
        public abstract void onError(int errorCode);
    }
}
