/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settings.wifi.calling;

import static android.app.slice.Slice.EXTRA_TOGGLE_STATE;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.ims.ImsConfig;
import com.android.ims.ImsManager;
import com.android.internal.annotations.VisibleForTesting;
import com.android.settings.R;
import com.android.settings.Utils;
import com.android.settings.slices.SettingsSliceProvider;
import com.android.settings.slices.SliceBroadcastReceiver;
import com.android.settings.slices.SliceBuilderUtils;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import androidx.core.graphics.drawable.IconCompat;
import androidx.slice.Slice;
import androidx.slice.builders.ListBuilder;
import androidx.slice.builders.ListBuilder.RowBuilder;
import androidx.slice.builders.SliceAction;


/**
 * Helper class to control slices for wifi calling settings.
 */
public class WifiCallingSliceHelper {

    private static final String TAG = "WifiCallingSliceHelper";

    /**
     * Settings slice path to wifi calling setting.
     */
    public static final String PATH_WIFI_CALLING = "wifi_calling";

    /**
     * Settings slice path to wifi calling preference setting.
     */
    public static final String PATH_WIFI_CALLING_PREFERENCE =
            "wifi_calling_preference";

    /**
     * Action passed for changes to wifi calling slice (toggle).
     */
    public static final String ACTION_WIFI_CALLING_CHANGED =
            "com.android.settings.wifi.calling.action.WIFI_CALLING_CHANGED";

    /**
     * Action passed when user selects wifi only preference.
     */
    public static final String ACTION_WIFI_CALLING_PREFERENCE_WIFI_ONLY =
            "com.android.settings.slice.action.WIFI_CALLING_PREFERENCE_WIFI_ONLY";
    /**
     * Action passed when user selects wifi preferred preference.
     */
    public static final String ACTION_WIFI_CALLING_PREFERENCE_WIFI_PREFERRED =
            "com.android.settings.slice.action.WIFI_CALLING_PREFERENCE_WIFI_PREFERRED";
    /**
     * Action passed when user selects cellular preferred preference.
     */
    public static final String ACTION_WIFI_CALLING_PREFERENCE_CELLULAR_PREFERRED =
            "com.android.settings.slice.action.WIFI_CALLING_PREFERENCE_CELLULAR_PREFERRED";

    /**
     * Action for Wifi calling Settings activity which
     * allows setting configuration for Wifi calling
     * related settings
     */
    public static final String ACTION_WIFI_CALLING_SETTINGS_ACTIVITY =
            "android.settings.WIFI_CALLING_SETTINGS";

    /**
     * Full {@link Uri} for the Wifi Calling Slice.
     */
    public static final Uri WIFI_CALLING_URI = new Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(SettingsSliceProvider.SLICE_AUTHORITY)
            .appendPath(PATH_WIFI_CALLING)
            .build();

    /**
     * Full {@link Uri} for the Wifi Calling Preference Slice.
     */
    public static final Uri WIFI_CALLING_PREFERENCE_URI = new Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(SettingsSliceProvider.SLICE_AUTHORITY)
            .appendPath(PATH_WIFI_CALLING_PREFERENCE)
            .build();

    /**
     * Timeout for querying wifi calling setting from ims manager.
     */
    private static final int TIMEOUT_MILLIS = 2000;

    private final Context mContext;

    @VisibleForTesting
    public WifiCallingSliceHelper(Context context) {
        mContext = context;
    }

    /**
     * Returns Slice object for wifi calling settings.
     *
     * If wifi calling is being turned on and if wifi calling activation is needed for the current
     * carrier, this method will return Slice with instructions to go to Settings App.
     *
     * If wifi calling is not supported for the current carrier, this method will return slice with
     * not supported message.
     *
     * If wifi calling setting can be changed, this method will return the slice to toggle wifi
     * calling option with ACTION_WIFI_CALLING_CHANGED as endItem.
     */
    public Slice createWifiCallingSlice(Uri sliceUri) {
        final int subId = getDefaultVoiceSubId();

        if (subId <= SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            Log.d(TAG, "Invalid subscription Id");
            return null;
        }

        final ImsManager imsManager = getImsManager(subId);

        if (!imsManager.isWfcEnabledByPlatform()
                || !imsManager.isWfcProvisionedOnDevice()) {
            Log.d(TAG, "Wifi calling is either not provisioned or not enabled by Platform");
            return null;
        }

        try {
            final boolean isWifiCallingEnabled = isWifiCallingEnabled(imsManager);
            final Intent activationAppIntent =
                    getWifiCallingCarrierActivityIntent(subId);

            // Send this actionable wifi calling slice to toggle the setting
            // only when there is no need for wifi calling activation with the server
            if (activationAppIntent != null && !isWifiCallingEnabled) {
                Log.d(TAG, "Needs Activation");
                // Activation needed for the next action of the user
                // Give instructions to go to settings app
                return getNonActionableWifiCallingSlice(
                        mContext.getText(R.string.wifi_calling_settings_title),
                        mContext.getText(
                                R.string.wifi_calling_settings_activation_instructions),
                        sliceUri, getActivityIntent(ACTION_WIFI_CALLING_SETTINGS_ACTIVITY));
            }
            return getWifiCallingSlice(sliceUri, isWifiCallingEnabled);
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            Log.e(TAG, "Unable to read the current WiFi calling status", e);
            return null;
        }
    }

    private boolean isWifiCallingEnabled(ImsManager imsManager)
            throws InterruptedException, ExecutionException, TimeoutException {
        final FutureTask<Boolean> isWifiOnTask = new FutureTask<>(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return imsManager.isWfcEnabledByUser();
            }
        });
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(isWifiOnTask);

        return isWifiOnTask.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                && imsManager.isNonTtyOrTtyOnVolteEnabled();
    }

    /**
     * Builds a toggle slice where the intent takes you to the wifi calling page and the toggle
     * enables/disables wifi calling.
     */
    private Slice getWifiCallingSlice(Uri sliceUri, boolean isWifiCallingEnabled) {
        final IconCompat icon = IconCompat.createWithResource(mContext, R.drawable.wifi_signal);

        return new ListBuilder(mContext, sliceUri, ListBuilder.INFINITY)
                .setAccentColor(Utils.getColorAccentDefaultColor(mContext))
                .addRow(b -> b
                        .setTitle(mContext.getText(R.string.wifi_calling_settings_title))
                        .addEndItem(
                                new SliceAction(
                                        getBroadcastIntent(ACTION_WIFI_CALLING_CHANGED),
                                        null /* actionTitle */, isWifiCallingEnabled))
                        .setPrimaryAction(new SliceAction(
                                getActivityIntent(ACTION_WIFI_CALLING_SETTINGS_ACTIVITY),
                                icon,
                                mContext.getText(R.string.wifi_calling_settings_title))))
                .build();
    }

    /**
     * Returns Slice object for wifi calling preference.
     *
     * If wifi calling is not turned on, this method will return a slice to turn on wifi calling.
     *
     * If wifi calling preference is not user editable, this method will return a slice to display
     * appropriate message.
     *
     * If wifi calling preference can be changed, this method will return a slice with 3 or 4 rows:
     * Header Row: current preference settings
     * Row 1: wifi only option with ACTION_WIFI_CALLING_PREFERENCE_WIFI_ONLY, if wifi only option
     * is editable
     * Row 2: wifi preferred option with ACTION_WIFI_CALLING_PREFERENCE_WIFI_PREFERRED
     * Row 3: cellular preferred option with ACTION_WIFI_CALLING_PREFERENCE_CELLULAR_PREFERRED
     */
    public Slice createWifiCallingPreferenceSlice(Uri sliceUri) {
        final int subId = getDefaultVoiceSubId();

        if (subId <= SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            Log.d(TAG, "Invalid Subscription Id");
            return null;
        }

        final boolean isWifiCallingPrefEditable = isCarrierConfigManagerKeyEnabled(
                CarrierConfigManager.KEY_EDITABLE_WFC_MODE_BOOL, subId,false);
        final boolean isWifiOnlySupported = isCarrierConfigManagerKeyEnabled(
                CarrierConfigManager.KEY_CARRIER_WFC_SUPPORTS_WIFI_ONLY_BOOL, subId, true);
        final ImsManager imsManager = getImsManager(subId);

        if (!imsManager.isWfcEnabledByPlatform()
                || !imsManager.isWfcProvisionedOnDevice()) {
            Log.d(TAG, "Wifi calling is either not provisioned or not enabled by platform");
            return null;
        }

        if (!isWifiCallingPrefEditable) {
            Log.d(TAG, "Wifi calling preference is not editable");
            return null;
        }

        boolean isWifiCallingEnabled = false;
        int wfcMode = -1;
        try {
            isWifiCallingEnabled = isWifiCallingEnabled(imsManager);
            wfcMode = getWfcMode(imsManager);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Log.e(TAG, "Unable to get wifi calling preferred mode", e);
            return null;
        }
        if (!isWifiCallingEnabled) {
            // wifi calling is not enabled. Ask user to enable wifi calling
            return getNonActionableWifiCallingSlice(
                    mContext.getText(R.string.wifi_calling_mode_title),
                    mContext.getText(R.string.wifi_calling_turn_on),
                    sliceUri, getActivityIntent(ACTION_WIFI_CALLING_SETTINGS_ACTIVITY));
        }
        // Return the slice to change wifi calling preference
        return getWifiCallingPreferenceSlice(
                isWifiOnlySupported, wfcMode, sliceUri);
    }

    /**
     * Returns actionable wifi calling preference slice.
     *
     * @param isWifiOnlySupported adds row for wifi only if this is true
     * @param currentWfcPref current Preference {@link ImsConfig}
     * @param sliceUri sliceUri
     * @return Slice for actionable wifi calling preference settings
     */
    private Slice getWifiCallingPreferenceSlice(boolean isWifiOnlySupported,
            int currentWfcPref,
            Uri sliceUri) {
        final IconCompat icon = IconCompat.createWithResource(mContext, R.drawable.wifi_signal);
        // Top row shows information on current preference state
        ListBuilder listBuilder = new ListBuilder(mContext, sliceUri, ListBuilder.INFINITY)
                .setAccentColor(Utils.getColorAccentDefaultColor(mContext));
        listBuilder.setHeader(new ListBuilder.HeaderBuilder(listBuilder)
                        .setTitle(mContext.getText(R.string.wifi_calling_mode_title))
                        .setSubtitle(getWifiCallingPreferenceSummary(currentWfcPref))
                        .setPrimaryAction(new SliceAction(
                                getActivityIntent(ACTION_WIFI_CALLING_SETTINGS_ACTIVITY),
                                icon,
                                mContext.getText(R.string.wifi_calling_mode_title))));

        if (isWifiOnlySupported) {
            listBuilder.addRow(wifiPreferenceRowBuilder(listBuilder,
                    com.android.internal.R.string.wfc_mode_wifi_only_summary,
                    ACTION_WIFI_CALLING_PREFERENCE_WIFI_ONLY,
                    currentWfcPref == ImsConfig.WfcModeFeatureValueConstants.WIFI_ONLY));
        }

        listBuilder.addRow(wifiPreferenceRowBuilder(listBuilder,
                com.android.internal.R.string.wfc_mode_wifi_preferred_summary,
                ACTION_WIFI_CALLING_PREFERENCE_WIFI_PREFERRED,
                currentWfcPref == ImsConfig.WfcModeFeatureValueConstants.WIFI_PREFERRED));

        listBuilder.addRow(wifiPreferenceRowBuilder(listBuilder,
                com.android.internal.R.string.wfc_mode_cellular_preferred_summary,
                ACTION_WIFI_CALLING_PREFERENCE_CELLULAR_PREFERRED,
                currentWfcPref == ImsConfig.WfcModeFeatureValueConstants.CELLULAR_PREFERRED));

        return listBuilder.build();
    }

    /**
     * Returns RowBuilder for a new row containing specific wifi calling preference.
     *
     * @param listBuilder ListBuilder that will be the parent for this RowBuilder
     * @param preferenceTitleResId resource Id for the preference row title
     * @param action action to be added for the row
     * @return RowBuilder for the row
     */
    private RowBuilder wifiPreferenceRowBuilder(ListBuilder listBuilder,
            int preferenceTitleResId, String action, boolean checked) {
        final IconCompat icon =
                IconCompat.createWithResource(mContext, R.drawable.radio_button_check);
        return new RowBuilder(listBuilder)
                .setTitle(mContext.getText(preferenceTitleResId))
                .setTitleItem(new SliceAction(getBroadcastIntent(action),
                        icon, mContext.getText(preferenceTitleResId), checked));
    }


    /**
     * Returns the String describing wifi calling preference mentioned in wfcMode
     *
     * @param wfcMode ImsConfig constant for the preference {@link ImsConfig}
     * @return summary/name of the wifi calling preference
     */
    private CharSequence getWifiCallingPreferenceSummary(int wfcMode) {
        switch (wfcMode) {
            case ImsConfig.WfcModeFeatureValueConstants.WIFI_ONLY:
                return mContext.getText(
                        com.android.internal.R.string.wfc_mode_wifi_only_summary);
            case ImsConfig.WfcModeFeatureValueConstants.WIFI_PREFERRED:
                return mContext.getText(
                        com.android.internal.R.string.wfc_mode_wifi_preferred_summary);
            case ImsConfig.WfcModeFeatureValueConstants.CELLULAR_PREFERRED:
                return mContext.getText(
                        com.android.internal.R.string.wfc_mode_cellular_preferred_summary);
            default:
                return null;
        }
    }

    protected ImsManager getImsManager(int subId) {
        return ImsManager.getInstance(mContext, SubscriptionManager.getPhoneId(subId));
    }

    private int getWfcMode(ImsManager imsManager)
            throws InterruptedException, ExecutionException, TimeoutException {
        FutureTask<Integer> wfcModeTask = new FutureTask<>(new Callable<Integer>() {
            @Override
            public Integer call() {
                return imsManager.getWfcMode(false);
            }
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(wfcModeTask);
        return wfcModeTask.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * Handles wifi calling setting change from wifi calling slice and posts notification. Should be
     * called when intent action is ACTION_WIFI_CALLING_CHANGED. Executed in @WorkerThread
     *
     * @param intent action performed
     */
    public void handleWifiCallingChanged(Intent intent) {
        final int subId = getDefaultVoiceSubId();

        if (subId > SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            final ImsManager imsManager = getImsManager(subId);
            if (imsManager.isWfcEnabledByPlatform()
                    && imsManager.isWfcProvisionedOnDevice()) {
                final boolean currentValue = imsManager.isWfcEnabledByUser()
                        && imsManager.isNonTtyOrTtyOnVolteEnabled();
                final boolean newValue = intent.getBooleanExtra(EXTRA_TOGGLE_STATE,
                        currentValue);
                final Intent activationAppIntent =
                        getWifiCallingCarrierActivityIntent(subId);
                if (!newValue || activationAppIntent == null) {
                    // If either the action is to turn off wifi calling setting
                    // or there is no activation involved - Update the setting
                    if (newValue != currentValue) {
                        imsManager.setWfcSetting(newValue);
                    }
                }
            }
        }
        // notify change in slice in any case to get re-queried. This would result in displaying
        // appropriate message with the updated setting.
        final Uri uri = SliceBuilderUtils.getUri(PATH_WIFI_CALLING, false /*isPlatformSlice*/);
        mContext.getContentResolver().notifyChange(uri, null);
    }

    /**
     * Handles wifi calling preference Setting change from wifi calling preference Slice and posts
     * notification for the change. Should be called when intent action is one of the below
     * ACTION_WIFI_CALLING_PREFERENCE_WIFI_ONLY
     * ACTION_WIFI_CALLING_PREFERENCE_WIFI_PREFERRED
     * ACTION_WIFI_CALLING_PREFERENCE_CELLULAR_PREFERRED
     *
     * @param intent intent
     */
    public void handleWifiCallingPreferenceChanged(Intent intent) {
        final int subId = getDefaultVoiceSubId();
        final int errorValue = -1;

        if (subId > SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            final boolean isWifiCallingPrefEditable = isCarrierConfigManagerKeyEnabled(
                    CarrierConfigManager.KEY_EDITABLE_WFC_MODE_BOOL, subId,false);
            final boolean isWifiOnlySupported = isCarrierConfigManagerKeyEnabled(
                    CarrierConfigManager.KEY_CARRIER_WFC_SUPPORTS_WIFI_ONLY_BOOL, subId, true);

            ImsManager imsManager = getImsManager(subId);
            if (isWifiCallingPrefEditable
                    && imsManager.isWfcEnabledByPlatform()
                    && imsManager.isWfcProvisionedOnDevice()
                    && imsManager.isWfcEnabledByUser()
                    && imsManager.isNonTtyOrTtyOnVolteEnabled()) {
                // Change the preference only when wifi calling is enabled
                // And when wifi calling preference is editable for the current carrier
                final int currentValue = imsManager.getWfcMode(false);
                int newValue = errorValue;
                switch (intent.getAction()) {
                    case ACTION_WIFI_CALLING_PREFERENCE_WIFI_ONLY:
                        if (isWifiOnlySupported) {
                            // change to wifi_only when wifi_only is enabled.
                            newValue = ImsConfig.WfcModeFeatureValueConstants.WIFI_ONLY;
                        }
                        break;
                    case ACTION_WIFI_CALLING_PREFERENCE_WIFI_PREFERRED:
                        newValue = ImsConfig.WfcModeFeatureValueConstants.WIFI_PREFERRED;
                        break;
                    case ACTION_WIFI_CALLING_PREFERENCE_CELLULAR_PREFERRED:
                        newValue = ImsConfig.WfcModeFeatureValueConstants.CELLULAR_PREFERRED;
                        break;
                }
                if (newValue != errorValue && newValue != currentValue) {
                    // Update the setting only when there is a valid update
                    imsManager.setWfcMode(newValue, false);
                }
            }
        }

        // notify change in slice in any case to get re-queried. This would result in displaying
        // appropriate message.
        final Uri uri = SliceBuilderUtils.getUri(PATH_WIFI_CALLING_PREFERENCE,
                false /*isPlatformSlice*/);
        mContext.getContentResolver().notifyChange(uri, null);
    }

    /**
     * Returns Slice with the title and subtitle provided as arguments with wifi signal Icon.
     *
     * @param title Title of the slice
     * @param subtitle Subtitle of the slice
     * @param sliceUri slice uri
     * @return Slice with title and subtitle
     */
    private Slice getNonActionableWifiCallingSlice(CharSequence title, CharSequence subtitle,
            Uri sliceUri, PendingIntent primaryActionIntent) {
        final IconCompat icon = IconCompat.createWithResource(mContext, R.drawable.wifi_signal);
        return new ListBuilder(mContext, sliceUri, ListBuilder.INFINITY)
                .setAccentColor(Utils.getColorAccentDefaultColor(mContext))
                .addRow(b -> b
                        .setTitle(title)
                        .setSubtitle(subtitle)
                        .setPrimaryAction(new SliceAction(
                                primaryActionIntent, icon,
                                title)))
                .build();
    }

    /**
     * Returns {@code true} when the key is enabled for the carrier, and {@code false} otherwise.
     */
    protected boolean isCarrierConfigManagerKeyEnabled(String key, int subId,
            boolean defaultValue) {
        final CarrierConfigManager configManager = getCarrierConfigManager(mContext);
        boolean ret = false;
        if (configManager != null) {
            final PersistableBundle bundle = configManager.getConfigForSubId(subId);
            if (bundle != null) {
                ret = bundle.getBoolean(key, defaultValue);
            }
        }
        return ret;
    }

    protected CarrierConfigManager getCarrierConfigManager(Context mContext) {
        return mContext.getSystemService(CarrierConfigManager.class);
    }

    /**
     * Returns the current default voice subId obtained from SubscriptionManager
     */
    protected int getDefaultVoiceSubId() {
        return SubscriptionManager.getDefaultVoiceSubscriptionId();
    }

    /**
     * Returns Intent of the activation app required to activate wifi calling or null if there is no
     * need for activation.
     */
    protected Intent getWifiCallingCarrierActivityIntent(int subId) {
        final CarrierConfigManager configManager = getCarrierConfigManager(mContext);
        if (configManager == null) {
            return null;
        }

        final PersistableBundle bundle = configManager.getConfigForSubId(subId);
        if (bundle == null) {
            return null;
        }

        final String carrierApp = bundle.getString(
                CarrierConfigManager.KEY_WFC_EMERGENCY_ADDRESS_CARRIER_APP_STRING);
        if (TextUtils.isEmpty(carrierApp)) {
            return null;
        }

        final ComponentName componentName = ComponentName.unflattenFromString(carrierApp);
        if (componentName == null) {
            return null;
        }

        final Intent intent = new Intent();
        intent.setComponent(componentName);
        return intent;
    }

    /**
     * @return {@link PendingIntent} to the Settings home page.
     */
    public static PendingIntent getSettingsIntent(Context context) {
        final Intent intent = new Intent(Settings.ACTION_SETTINGS);
        return PendingIntent.getActivity(context, 0 /* requestCode */, intent, 0 /* flags */);
    }

    private PendingIntent getBroadcastIntent(String action) {
        final Intent intent = new Intent(action);
        intent.setClass(mContext, SliceBroadcastReceiver.class);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        return PendingIntent.getBroadcast(mContext, 0 /* requestCode */, intent,
                PendingIntent.FLAG_CANCEL_CURRENT);
    }

    /**
     * Returns PendingIntent to start activity specified by action
     */
    private PendingIntent getActivityIntent(String action) {
        final Intent intent = new Intent(action);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(mContext, 0 /* requestCode */, intent, 0 /* flags */);
    }
}