/*
 * Copyright (c) 2010, Code Aurora Forum. All rights reserved.
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

package com.android.internal.telephony;

import java.util.ArrayList;
import java.util.HashMap;

import android.content.Context;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.telephony.DataConnectionTracker.State;
import com.android.internal.telephony.Phone.IPVersion;
import com.android.internal.telephony.DataProfile.DataProfileType;

/*
 * for each data service type (APN type), an instance of the following
 * class will be maintained by the data profile tracker class.
 */

public class DataServiceInfo {

    Context mContext;

    // data service type (apn type) that this instance handles.
    private DataServiceType mServiceType;

    // set to true, if someone has requested for this profile to be active.
    private boolean isEnabled;

    private boolean mOmhEnabled = SystemProperties.getBoolean(
                                    TelephonyProperties.PROPERTY_OMH_ENABLED, false);

    /*
     * indicates the underlying data call/pdp connection and data profile
     * through which this type is active (one for each ip version). the
     * service can be active even if it is not enabled, as is the case when
     * a profile that is activated for some other service, also happen to support
     * this service.
     */
    //TODO: change this into a hashmap!
    private DataConnection activeIpv4Dc;
    private DataConnection activeIpv6Dc;
    private State ipv4State = State.IDLE;
    private State ipv6State = State.IDLE;

    // list of data profiles that supports this service type
    // populated by DataProfileTracker
    private ArrayList<DataProfile> mDataProfileList;

    /** Retry configuration: A doubling of retry times from 5secs to 30 minutes */
    private static final String DEFAULT_DATA_RETRY_CONFIG = "default_randomization=2000,"
            + "5000,10000,20000,40000,80000:5000,160000:5000,"
            + "320000:5000,640000:5000,1280000:5000,1800000:5000";

    /** Retry configuration for secondary networks: 4 tries in 20 sec */
    private  static final String SECONDARY_DATA_RETRY_CONFIG = "max_retries=3, 5000, 5000, 5000";

    private HashMap <IPVersion, RetryManager> mRetryMgr;

    public DataServiceInfo(Context context, DataServiceType serviceType) {
        super();

        mContext = context;
        this.mServiceType = serviceType;

        mDataProfileList = new ArrayList<DataProfile>();

        /* Two retry managers as v4 and v6 could be tried in parallel */
        mRetryMgr = new HashMap<Phone.IPVersion, RetryManager>();
        mRetryMgr.put(IPVersion.IPV4, createRetryManager(serviceType));
        mRetryMgr.put(IPVersion.IPV6, createRetryManager(serviceType));

        clear();
    }

    private RetryManager createRetryManager(DataServiceType serviceType) {
        RetryManager retryMgr = new RetryManager();
        if (serviceType == DataServiceType.SERVICE_TYPE_DEFAULT) {
            if (!retryMgr.configure(SystemProperties.get("ro.gsm.data_retry_config"))) {
                if (!retryMgr.configure(DEFAULT_DATA_RETRY_CONFIG)) {
                    // Should never happen, log an error and default to a simple
                    // linear sequence.
                    loge("Could not configure using DEFAULT_DATA_RETRY_CONFIG="
                            + DEFAULT_DATA_RETRY_CONFIG);
                    retryMgr.configure(20, 2000, 1000);
                }
            }
        } else { // secondary service type
            if (!retryMgr.configure(SystemProperties.get("ro.gsm.2nd_data_retry_config"))) {
                if (!retryMgr.configure(SECONDARY_DATA_RETRY_CONFIG)) {
                    // Should never happen, log an error and default to a simple
                    // sequence.
                    loge("Could not configure using SECONDARY_DATA_RETRY_CONFIG="
                            + SECONDARY_DATA_RETRY_CONFIG);
                    retryMgr.configure("max_retries=3, 333, 333, 333");
                }
            }
        }
        return retryMgr;
    }

    private void clear() {
        isEnabled = false;
        activeIpv4Dc = null;
        activeIpv6Dc = null;
        resetServiceConnectionState();
    }

    void resetServiceConnectionState() {
        if (ipv4State == State.FAILED || ipv4State == State.WAITING_ALARM)
            setState(State.IDLE, IPVersion.IPV4);
        if (ipv6State == State.FAILED || ipv6State == State.WAITING_ALARM)
            setState(State.IDLE, IPVersion.IPV6);
        mRetryMgr.get(IPVersion.IPV4).resetRetryCount();
        mRetryMgr.get(IPVersion.IPV6).resetRetryCount();
    }

    RetryManager getRetryManager(IPVersion ipv) {
        return mRetryMgr.get(ipv);
    }

    // gets the next data profile of the specified data profile type and ip
    // version that needs to be tried out.
    DataProfile getNextWorkingDataProfile(DataProfileType profileType, IPVersion ipv) {
        DataProfile dp;

        dp = getNextArbitratedProfile(profileType, ipv);

        if (dp == null) {
            // If modem profiles dint have the right profile, look in android
            dp = getNextWorkingAndroidProfile(profileType, ipv);
        }

        return dp;
    }

    /**
     * Search through the modem profiles and choose the one with the highest
     * priority.
     *
     * The arbitration logic can be changed/enhanced
     * within this function.
     */
    DataProfile getNextArbitratedProfile(DataProfileType profileType, IPVersion ipv) {

        if (mOmhEnabled && profileType == DataProfileType.PROFILE_TYPE_3GPP2_OMH) {
            logi("[OMH] Looking at OMH profiles");
            DataProfile profile = null;
            for (DataProfile dp : mDataProfileList) {
                if (dp.getDataProfileType() == profileType && dp.isWorking(ipv) == true
                        && dp.canSupportIpVersion(ipv)) {

                    if (!((DataProfileOmh)dp).isValidPriority()) {
                        logi("[OMH] Invalid priority, skipping profile...");
                        continue;
                    }

                    if (profile == null) {
                        profile = dp; // first hit
                    } else {
                        if (mServiceType == DataServiceType.SERVICE_TYPE_SUPL) {
                            // Choose the profile with lower priority for SUPL (for OMH)
                            profile =
                                ((DataProfileOmh)dp).isPriorityLower(((DataProfileOmh)profile).getPriority())
                                ? dp : profile;
                        } else {
                            // Choose the profile with higher priority
                            profile =
                                ((DataProfileOmh)dp).isPriorityHigher(((DataProfileOmh)profile).getPriority())
                                ? dp : profile;
                        }
                    }
                }
            }
            return profile;
        }

        return null;
    }

    /* Search through the android profiles for a match */
    DataProfile getNextWorkingAndroidProfile(DataProfileType profileType, IPVersion ipv) {

        if (mOmhEnabled && profileType == DataProfileType.PROFILE_TYPE_3GPP2_OMH) {
                // If we have reached here, then there are no OMH profiles available
                // falling back to CDMA NAI profiles (if OMH profile was requested)
                logi("No suitable OMH profiles found, fallback to CDMA NAI");
                profileType = DataProfileType.PROFILE_TYPE_3GPP2_NAI;
        }

        for (DataProfile dp : mDataProfileList) {
            if (dp.getDataProfileType() == profileType && dp.isWorking(ipv) == true
                    && dp.canSupportIpVersion(ipv)) {
                logi("Data Profile Chosen:" + dp.getDataProfileType());
                return dp;
            }
        }
        return null;
    }

    /*
     * Mark the specified APN/data service type as requested / not requested.
     */
    void setServiceTypeEnabled(boolean enable) {
        logi("Service enabled = " + enable);
        this.isEnabled = enable;
    }

    boolean isDataServiceTypeEnabled() {
        return this.isEnabled;
    }

    /*
     * Dc should be valid when this function is called. It can be called even if
     * service is not enabled. State is set to CONNECTED!
     */
    void setDataServiceTypeAsActive(DataConnection dc, IPVersion ipv) {
        if (dc == null || ipv == null) {
            loge("service set as active with null parameters!");
            return;
        }

        logi("Service is active on " + ipv);
        logv(" dc : " + dc.toString());

        if (ipv == IPVersion.IPV6) {
            this.activeIpv6Dc = dc;
        } else if (ipv == IPVersion.IPV4) {
            this.activeIpv4Dc = dc;
        }
        setState(State.CONNECTED, ipv);
    }

    void setDataServiceTypeAsInactive(IPVersion ipv) {

        logi("Service is inactive on " + ipv);

        if (ipv == IPVersion.IPV6) {
            this.activeIpv6Dc = null;
        } else if (ipv == IPVersion.IPV4) {
            this.activeIpv4Dc = null;
        }
        setState(State.IDLE, ipv);
    }

    DataConnection getActiveDataConnection(IPVersion ipv) {
        if (ipv == IPVersion.IPV4)
            return activeIpv4Dc;
        else if (ipv == IPVersion.IPV6)
            return activeIpv6Dc;
        return null;
    }

    boolean isServiceTypeActive(IPVersion ipVersion) {
        if (ipVersion == IPVersion.IPV6) {
            return ipv6State == State.CONNECTED;
        } else if (ipVersion == IPVersion.IPV4) {
            return ipv4State == State.CONNECTED;
        }
        return false;
    }

    boolean isServiceTypeActive() {
        return isServiceTypeActive(IPVersion.IPV4) || isServiceTypeActive(IPVersion.IPV6);
    }

    public synchronized void setState(State newState, IPVersion ipv) {
        State oldState = ipv == IPVersion.IPV4 ? ipv4State : ipv6State;

        if (newState != oldState) {
            if (ipv == IPVersion.IPV6) {
                ipv6State = newState;
            } else if (ipv == IPVersion.IPV4) {
                ipv4State = newState;
            }
        }
    }

    public State getState(IPVersion ipv) {
        if (ipv == IPVersion.IPV4)
            return ipv4State;
        else
            return ipv6State;
    }

    public void clearDataProfiles() {
        mDataProfileList.clear();
        return;
    }

    public void addDataProfile(DataProfile dp) {
        mDataProfileList.add(dp);
        return;
    }

    public ArrayList<DataProfile> getDataProfiles() {
        return mDataProfileList;
    }

    static private final String LOG_TAG = "DATA";

    String getLogPrefix() {
        return "[" + this.mServiceType + "] ";
    }

    void loge(String string) {
        Log.e(LOG_TAG, getLogPrefix() + string);
    }

    void logi(String string) {
        Log.i(LOG_TAG, getLogPrefix() + string);
    }

    void logv(String string) {
        Log.v(LOG_TAG, getLogPrefix() + string);
    }
}
