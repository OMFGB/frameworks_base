/*
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.android.internal.telephony.gsm;

import android.os.Message;
import android.util.Log;

import com.android.internal.telephony.IccFileHandler;
import com.android.internal.telephony.IccPhoneBookInterfaceManager;

/**
 * SimPhoneBookInterfaceManager to provide an inter-process communication to
 * access ADN-like SIM records.
 */


public class SimPhoneBookInterfaceManager extends IccPhoneBookInterfaceManager {
    static final String LOG_TAG = "GSM";

    public SimPhoneBookInterfaceManager(GSMPhone phone) {
        super(phone);
        if (phone.mSIMRecords != null) {
            adnCache = phone.mSIMRecords.getAdnCache();
        }
        //NOTE service "simphonebook" added by IccSmsInterfaceManagerProxy
    }

    public void dispose() {
        super.dispose();
    }

    protected void finalize() {
        try {
            super.finalize();
        } catch (Throwable throwable) {
            Log.e(LOG_TAG, "Error while finalizing:", throwable);
        }
        if(DBG) Log.d(LOG_TAG, "SimPhoneBookInterfaceManager finalized");
    }

    public void updateSimRecords(SIMRecords simRecords) {
        if (simRecords != null) {
            adnCache = simRecords.getAdnCache();
        } else {
            adnCache = null;
        }
    }

    public int[] getAdnRecordsSize(int efid) {
        if (DBG) logd("getAdnRecordsSize: efid=" + efid);
        synchronized(mLock) {
            checkThread();
            recordSize = new int[3];

            //Using mBaseHandler, no difference in EVENT_GET_SIZE_DONE handling
            Message response = mBaseHandler.obtainMessage(EVENT_GET_SIZE_DONE);

            IccFileHandler fh = phone.getIccFileHandler();
            //IccFileHandler can be null if there is no icc card present.
            if (fh != null) {
                fh.getEFLinearRecordSize(efid, response);
                try {
                    mLock.wait();
                } catch (InterruptedException e) {
                    logd("interrupted while trying to load from the SIM");
                }
            }
        }

        return recordSize;
    }

    protected void logd(String msg) {
        Log.d(LOG_TAG, "[SimPbInterfaceManager] " + msg);
    }

    protected void loge(String msg) {
        Log.e(LOG_TAG, "[SimPbInterfaceManager] " + msg);
    }
}

