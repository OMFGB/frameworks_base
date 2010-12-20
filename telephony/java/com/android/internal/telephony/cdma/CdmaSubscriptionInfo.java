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

package com.android.internal.telephony.cdma;

import android.util.Log;

public class CdmaSubscriptionInfo {

    private static final String LOG_TAG = "CdmaSubscriptionInfo";

    public int mHomeSystemId[] = null;
    public int mHomeNetworkId[] = null;
    public String mPrlVersion;
    public String mMdn;
    public String mMin;

    public CdmaSubscriptionInfo() {
    }

    public void populateSubscriptionInfoFromRegistrationState(String cdmaSubscriptionArray[]) {
        mMdn = null;
        mHomeSystemId = null;
        mHomeNetworkId = null;
        mMin = null;
        mPrlVersion = null;

        /* MDN */
        try {
            mMdn = cdmaSubscriptionArray[0];
        } catch (Exception ex) {
            Log.e(LOG_TAG, "error parsing mdn: ", ex);
        }

        /* Home System ID */
        try {
            String[] sid = cdmaSubscriptionArray[1].split(",");
            mHomeSystemId = new int[sid.length];
            for (int i = 0; i < sid.length; i++) {
                try {
                    mHomeSystemId[i] = Integer.parseInt(sid[i]);
                } catch (NumberFormatException ex) {
                    Log.e(LOG_TAG, "error parsing system id: ", ex);
                    /* continue parsing */
                }
            }
        } catch (Exception ex) {
            Log.e(LOG_TAG, "error parsing system id: ", ex);
        }

        /* Home Network ID */
        try {
            String[] nid = cdmaSubscriptionArray[2].split(",");
            mHomeNetworkId = new int[nid.length];
            for (int i = 0; i < nid.length; i++) {
                try {
                    mHomeNetworkId[i] = Integer.parseInt(nid[i]);
                } catch (NumberFormatException ex) {
                    Log.e(LOG_TAG, "error parsing network id: ", ex);
                    /* continue parsing */
                }
            }
        } catch (Exception ex) {
            Log.e(LOG_TAG, "error parsing network id: ", ex);
        }

        /* MIN */
        try {
            mMin = cdmaSubscriptionArray[3];
        } catch (Exception ex) {
            Log.e(LOG_TAG, "error parsing min: ", ex);
        }

        /* TODO: prl version will go away soon */
        try {
            mPrlVersion = cdmaSubscriptionArray[4]; /* will change */
            ;
        } catch (Exception ex) {
            Log.e(LOG_TAG, "error parsing prl version: ", ex);
        }
    }

    public boolean isSidsAllZeros() {
        if (mHomeSystemId != null) {
            for (int i=0; i < mHomeSystemId.length; i++) {
                if (mHomeSystemId[i] != 0) {
                    return false;
                }
            }
        }
        return true;
    }


    /**
     * Check whether a specified system ID that matches one of the home system IDs.
     */
    public boolean isHomeSid(int sid) {
        if (mHomeSystemId != null) {
            for (int i=0; i < mHomeSystemId.length; i++) {
                if (sid == mHomeSystemId[i]) {
                    return true;
                }
            }
        }
        return false;
    }

}
