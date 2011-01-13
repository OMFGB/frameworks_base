/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.content.Context;
import android.os.AsyncResult;
import android.os.Message;
import android.util.Log;

import com.android.internal.telephony.AdnRecordCache;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.IccConstants;
import com.android.internal.telephony.UiccApplicationRecords;
import com.android.internal.telephony.UiccRecords;
import com.android.internal.telephony.UiccConstants.AppType;
import com.android.internal.telephony.UiccCardApplication;
import com.android.internal.telephony.MccTable;

// can't be used since VoiceMailConstants is not public
//import com.android.internal.telephony.gsm.VoiceMailConstants;
import com.android.internal.telephony.IccException;
import com.android.internal.telephony.IccUtils;


/**
 * {@hide}
 */
public final class RuimRecords extends UiccApplicationRecords {
    static final String LOG_TAG = "CDMA";

    private static final boolean DBG = true;
    private boolean  m_ota_commited=false;

    // ***** Instance Variables

    private String mMyMobileNumber;
    private String mMin2Min1;

    // ***** Event Constants

    private static final int EVENT_RADIO_OFF_OR_NOT_AVAILABLE = 2;
    private static final int EVENT_GET_DEVICE_IDENTITY_DONE = 4;
    private static final int EVENT_GET_ICCID_DONE = 5;
    private static final int EVENT_GET_CDMA_SUBSCRIPTION_DONE = 10;
    private static final int EVENT_UPDATE_DONE = 14;
    private static final int EVENT_GET_SST_DONE = 17;
    private static final int EVENT_GET_ALL_SMS_DONE = 18;
    private static final int EVENT_MARK_SMS_READ_DONE = 19;

    private static final int EVENT_SMS_ON_RUIM = 21;
    private static final int EVENT_GET_SMS_DONE = 22;

    private static final int EVENT_RUIM_REFRESH = 31;


    public RuimRecords(UiccCardApplication parent, UiccRecords ur, Context c, CommandsInterface ci) {
        super(parent, c, ci, ur);

        adnCache = new AdnRecordCache(mFh);

        recordsRequested = false;  // No load request is made till SIM ready

        // recordsToLoad is set to 0 because no requests are made yet
        recordsToLoad = 0;

        //TODO: This probably is not required anymore - this whole object will be
        //destroyed once this event is received by UiccManager
        mCi.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        // NOTE the EVENT_SMS_ON_RUIM is not registered
        mCi.setOnIccRefresh(this, EVENT_RUIM_REFRESH, null);

        // Start off by setting empty state
        onRadioOffOrNotAvailable();

    }

    public void dispose() {
        //Unregister for all events
        mCi.unregisterForOffOrNotAvailable( this);
        mCi.unSetOnIccRefresh(this);
    }

    @Override
    protected void finalize() {
        if(DBG) Log.d(LOG_TAG, "RuimRecords finalized");
    }

    @Override
    protected void onRadioOffOrNotAvailable() {
        mncLength = UNINITIALIZED;
        iccid = null;

        adnCache.reset();

        // recordsRequested is set to false indicating that the SIM
        // read requests made so far are not valid. This is set to
        // true only when fresh set of read requests are made.
        recordsRequested = false;
    }

    public String getMdnNumber() {
        return mMyMobileNumber;
    }

    public String getCdmaMin() {
         return mMin2Min1;
    }

    @Override
    public void setVoiceMailNumber(String alphaTag, String voiceNumber, Message onComplete){
        // In CDMA this is Operator/OEM dependent
        AsyncResult.forMessage((onComplete)).exception =
                new IccException("setVoiceMailNumber not implemented");
        onComplete.sendToTarget();
        Log.e(LOG_TAG, "method setVoiceMailNumber is not implemented");
    }

    public void setImsi(String imsi) {
        mImsi = imsi;
        mImsiReadyRegistrants.notifyRegistrants();
    }

    /**
     * Called by CCAT Service when REFRESH is received.
     * @param fileChanged indicates whether any files changed
     * @param fileList if non-null, a list of EF files that changed
     */
    @Override
    public void onRefresh(boolean fileChanged, int[] fileList) {
        if (fileChanged) {
            // A future optimization would be to inspect fileList and
            // only reload those files that we care about.  For now,
            // just re-fetch all RUIM records that we cache.
            fetchRuimRecords();
        }
    }

    /**
     * Returns the 5 or 6 digit MCC/MNC of the operator that
     *  provided the RUIM card. Returns null of RUIM is not yet ready
     */
    public String getRUIMOperatorNumeric() {
        if (mImsi == null) {
            return null;
        }

        if (mncLength != UNINITIALIZED && mncLength != UNKNOWN) {
            // Length = length of MCC + length of MNC
            // length of mcc = 3 (3GPP2 C.S0005 - Section 2.3)
            return mImsi.substring(0, 3 + mncLength);
        }

        // Guess the MNC length based on the MCC if we don't
        // have a valid value in ef[ad]

        int mcc = Integer.parseInt(mImsi.substring(0,3));
        return mImsi.substring(0, 3 + MccTable.smallestDigitsMccForMnc(mcc));
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;

        byte data[];

        boolean isRecordLoadResponse = false;

        if (mDestroyed) {
            Log.e(LOG_TAG, "Received message " + msg +
                    "[" + msg.what + "] while being destroyed. Ignoring.");
            return;
        }

        try { switch (msg.what) {
            case EVENT_APP_READY:
                onRuimReady();
            break;

            case EVENT_RADIO_OFF_OR_NOT_AVAILABLE:
                onRadioOffOrNotAvailable();
            break;

            case EVENT_GET_DEVICE_IDENTITY_DONE:
                Log.d(LOG_TAG, "Event EVENT_GET_DEVICE_IDENTITY_DONE Received");
            break;

            /* IO events */

            case EVENT_GET_CDMA_SUBSCRIPTION_DONE:
                ar = (AsyncResult)msg.obj;
                String localTemp[] = (String[])ar.result;
                if (ar.exception != null) {
                    break;
                }

                mMyMobileNumber = localTemp[0];
                mMin2Min1 = localTemp[3];
                Log.d(LOG_TAG, "MDN: " + mMyMobileNumber + " MIN: " + mMin2Min1);

            break;

            case EVENT_GET_ICCID_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;

                if (ar.exception != null) {
                    break;
                }

                iccid = IccUtils.bcdToString(data, 0, data.length);

                Log.d(LOG_TAG, "iccid: " + iccid);

            break;

            case EVENT_UPDATE_DONE:
                ar = (AsyncResult)msg.obj;
                if (ar.exception != null) {
                    Log.i(LOG_TAG, "RuimRecords update failed", ar.exception);
                }
            break;

            case EVENT_GET_ALL_SMS_DONE:
            case EVENT_MARK_SMS_READ_DONE:
            case EVENT_SMS_ON_RUIM:
            case EVENT_GET_SMS_DONE:
                Log.w(LOG_TAG, "Event not supported: " + msg.what);
                break;

            // TODO: probably EF_CST should be read instead
            case EVENT_GET_SST_DONE:
                Log.d(LOG_TAG, "Event EVENT_GET_SST_DONE Received");
            break;

            case EVENT_RUIM_REFRESH:
                isRecordLoadResponse = false;
                ar = (AsyncResult)msg.obj;
                if (ar.exception == null) {
                    handleRuimRefresh((int[])(ar.result));
                }
                break;

        }}catch (RuntimeException exc) {
            // I don't want these exceptions to be fatal
            Log.w(LOG_TAG, "Exception parsing RUIM record", exc);
        } finally {
            // Count up record load responses even if they are fails
            if (isRecordLoadResponse) {
                onRecordLoaded();
            }
        }
    }

    @Override
    protected void onRecordLoaded() {
        // One record loaded successfully or failed, In either case
        // we need to update the recordsToLoad count
        recordsToLoad -= 1;
        Log.v(LOG_TAG, "RuimRecords:onRecordLoaded " + recordsToLoad + " requested: " + recordsRequested);

        if (recordsToLoad == 0 && recordsRequested == true) {
            onAllRecordsLoaded();
        } else if (recordsToLoad < 0) {
            Log.e(LOG_TAG, "RuimRecords: recordsToLoad <0, programmer error suspected");
            recordsToLoad = 0;
        }
    }

    @Override
    protected void onAllRecordsLoaded() {
        Log.d(LOG_TAG, "RuimRecords: record load complete");

        // Further records that can be inserted are Operator/OEM dependent

        recordsLoadedRegistrants.notifyRegistrants(
            new AsyncResult(null, null, null));
    }

    private void onRuimReady() {
        fetchRuimRecords();
        mCi.getCDMASubscription(obtainMessage(EVENT_GET_CDMA_SUBSCRIPTION_DONE));
    }


    private void fetchRuimRecords() {
        recordsRequested = true;

        Log.v(LOG_TAG, "RuimRecords:fetchRuimRecords " + recordsToLoad);

        mFh.loadEFTransparent(IccConstants.EF_ICCID,
                obtainMessage(EVENT_GET_ICCID_DONE));
        recordsToLoad++;

        // Further records that can be inserted are Operator/OEM dependent
    }

    @Override
    protected int getDisplayRule(String plmn) {
        // TODO together with spn
        return 0;
    }

    @Override
    public void setVoiceMessageWaiting(int line, int countWaiting, Message onComplete) {
        //Will be used in future to store voice mail count in UIM
        //C.S0023-D_v1.0 does not have a file id in UIM for MWI
        Log.d(LOG_TAG, "RuimRecords:setVoiceMessageWaiting - NOP for CDMA");
    }

    private void handleRuimRefresh(int[] result) {
        if (result == null || result.length == 0) {
            if (DBG) log("handleRuimRefresh without input");
            return;
        }

        switch ((result[0])) {
            case CommandsInterface.SIM_REFRESH_FILE_UPDATED:
                if (DBG) log("handleRuimRefresh with SIM_REFRESH_FILE_UPDATED");
                adnCache.reset();
                fetchRuimRecords();
                break;
            case CommandsInterface.SIM_REFRESH_INIT:
                if (DBG) log("handleRuimRefresh with SIM_REFRESH_INIT");
                // need to reload all files (that we care about)
                fetchRuimRecords();
                break;
            case CommandsInterface.SIM_REFRESH_RESET:
                if (DBG) log("handleRuimRefresh with SIM_REFRESH_RESET");
                onIccRefreshReset();
                break;
            default:
                // unknown refresh operation
                if (DBG) log("handleRuimRefresh with unknown operation");
                break;
        }
    }

    @Override
    protected void log(String s) {
        Log.d(LOG_TAG, "[RuimRecords] " + s);
    }

}
