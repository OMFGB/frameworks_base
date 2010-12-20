/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.content.Context;
import android.os.SystemProperties;
import android.util.Log;
import android.util.Patterns;

import com.android.internal.telephony.Phone.IPVersion;
import com.android.internal.telephony.DataProfile.DataProfileType;

/**
 * {@hide}
 */
public class MMDataConnection extends DataConnection {

    boolean DBG = true;
    DataConnectionTracker mDct;

    private static final String LOG_TAG = "DATA";

    private MMDataConnection(DataConnectionTracker dct, Context context, CommandsInterface ci, String name) {   
        super(context, ci, name);
        this.mDct = dct;
    }

    static MMDataConnection makeDataConnection(DataConnectionTracker dct) {
        synchronized (mCountLock) {
            mCount += 1;
        }
        MMDataConnection dc = new MMDataConnection(dct, dct.mContext, dct.mCm, "MMDC -"
                + mCount);
        dc.start();
        return dc;
    }

    /**
     * Setup a data call with the specified data profile
     *
     * @param mDataProfile for this connection.
     * @param onCompleted notify success or not after down
     */
    protected void onConnect(ConnectionParams cp) {

        logi("Connecting : dataProfile = " + cp.dp.toString());

        int radioTech = cp.radioTech.isCdma() ? 1 : 0;

        /* case APN */
        if (cp.dp.getDataProfileType() == DataProfileType.PROFILE_TYPE_3GPP_APN) {
            ApnSetting apn = (ApnSetting) cp.dp;

            setHttpProxy(apn.proxy, apn.port);

            int authType = apn.authType;
            if (authType == -1) {
                authType = (apn.user != null) ? RILConstants.SETUP_DATA_AUTH_PAP_CHAP
                        : RILConstants.SETUP_DATA_AUTH_NONE;
            }
            this.mCM.setupDataCall(
                    Integer.toString(radioTech),
                    Integer.toString(0), apn.apn, apn.user, apn.password, Integer.toString(authType),
                    Integer.toString(cp.ipv == IPVersion.IPV6 ? 1 : 0),
                    obtainMessage(EVENT_SETUP_DATA_CONNECTION_DONE, cp));
        } else if (cp.dp.getDataProfileType() == DataProfileType.PROFILE_TYPE_3GPP2_NAI) {
            this.mCM.setupDataCall(
                    Integer.toString(radioTech),
                    //TODO - fill in this from DP
                    Integer.toString(0), null, null, null, Integer
                    .toString(RILConstants.SETUP_DATA_AUTH_PAP_CHAP), Integer
                    .toString(cp.ipv == IPVersion.IPV6 ? 1 : 0),
                    obtainMessage(EVENT_SETUP_DATA_CONNECTION_DONE, cp));
        }
    }

    private void setHttpProxy(String httpProxy, String httpPort) {
        if (httpProxy == null || httpProxy.length() == 0) {
            SystemProperties.set("net.gprs.http-proxy", null);
            return;
        }

        if (httpPort == null || httpPort.length() == 0) {
            httpPort = "8080"; // Default to port 8080
        }

        SystemProperties.set("net.gprs.http-proxy", "http://" + httpProxy + ":" + httpPort
                        + "/");
    }

    @Override
    protected boolean isDnsOk(String[] domainNameServers) {
        if (NULL_IP.equals(dnsServers[0]) && NULL_IP.equals(dnsServers[1])
                && !mDct.isDnsCheckDisabled()) {
            // Work around a race condition where QMI does not fill in DNS:
            // Deactivate PDP and let DataConnectionTracker retry.
            // Do not apply the race condition workaround for MMS APN
            // if Proxy is an IP-address.
            // Otherwise, the default APN will not be restored anymore.
            if (mDataProfile.getDataProfileType() == DataProfileType.PROFILE_TYPE_3GPP_APN
                    && mDataProfile.canHandleServiceType(DataServiceType.SERVICE_TYPE_MMS)
                    && isIpAddress(((ApnSetting)mDataProfile).mmsProxy)) {
                return false;
            }
        }
        return true;
    }

    /* TODO: Fix this function - also add support for IPV6 */
    private boolean isIpAddress(String address) {
        if (address == null)
            return false;

        return Patterns.IP_ADDRESS.matcher(((ApnSetting)mDataProfile).mmsProxy).matches();
    }

    void logd(String logString) {
        if (DBG) {
            Log.d(LOG_TAG, "[DC cid = " + cid + "]" + logString);
        }
    }

    void logv(String logString) {
        if (DBG) {
            Log.d(LOG_TAG, "[DC cid = " + cid + "]" + logString);
        }
    }

    void logi(String logString) {
        Log.i(LOG_TAG, "[DC cid = " + cid + "]" + logString);
    }

    void loge(String logString) {
        Log.e(LOG_TAG, "[DC cid = " + cid + "]" + logString);
    }

    public String toString() {
        return "Cid=" + cid + ", State=" + getStateAsString() + ", ipv=" + mIpv + ", create="
                + createTime + ", lastFail=" + lastFailTime + ", lastFailCause=" + lastFailCause
                + ", dp=" + mDataProfile;
    }

    @Override
    protected void log(String s) {
        logv(s);
    }
}
