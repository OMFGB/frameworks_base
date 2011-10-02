/*
 * Copyright (c) 2011, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 *      notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 *      copyright notice, this list of conditions and the following
 *      disclaimer in the documentation and/or other materials provided
 *      with the distribution.
 *    * Neither the name of Code Aurora Forum, Inc. nor the names of its
 *      contributors may be used to endorse or promote products derived
 *      from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.internal.telephony.cdma;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.RILConstants;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.provider.Settings;
import android.util.Log;

import java.util.Hashtable;

/**
 * Class that handles the CDMA subscription source changed events from RIL
 */
public class CdmaSubscriptionSourceManager extends Handler {
    static final String LOG_TAG = "CDMA";

    private static final int EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED = 1;

    private static final int EVENT_GET_CDMA_SUBSCRIPTION_SOURCE = 2;

    private static final int EVENT_RADIO_ON = 3;

    // ***** Instance Variables
    static Hashtable<CommandsInterface, CdmaSubscriptionSourceManager> mCdmaSSMInstances =
        new Hashtable<CommandsInterface, CdmaSubscriptionSourceManager>();

    protected CommandsInterface mCM = null;

    protected Context mContext = null;

    protected RegistrantList mCdmaSubscriptionSourceChangedRegistrants = new RegistrantList();

    int mRef = 0;

    // Type of CDMA subscription source
    protected int mCdmaSubscriptionSource = RILConstants.SUBSCRIPTION_FROM_NV;

    // Hide constructor
    private CdmaSubscriptionSourceManager(Context context, CommandsInterface ci) {
        if (context == null) {
            Log.w(LOG_TAG, "Context shouldn't be null");
        } else {
            mContext = context;
            getDefaultCdmaSubscriptionSource();
        }
        if (ci == null) {
            Log.w(LOG_TAG, "CommandsInterface shouldn't be null");
        } else {
            mCM = ci;
            mCM.registerForCdmaSubscriptionSourceChanged(this,
                    EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED, null);
            mCM.registerForOn(this, EVENT_RADIO_ON, null);
        }
    }

    /**
     * This function creates a single instance of this class
     *
     * @param context context object needed to retrieve database setting
     * @param ci commands interface to talk to RIL
     * @param registrant registrant object to register for CDMA subscription
     *            source changed event
     * @return object of type CdmaSubscriptionSourceManager
     */
    public synchronized static CdmaSubscriptionSourceManager getInstance(Context context,
            CommandsInterface ci, Registrant registrant) {
        CdmaSubscriptionSourceManager mCdmaSSM = null;

        // Check if an instance is already available
        if (null == mCdmaSSMInstances.get(ci)) {
            mCdmaSSMInstances.put(ci, new CdmaSubscriptionSourceManager(context, ci));
        }
        mCdmaSSM = (CdmaSubscriptionSourceManager) mCdmaSSMInstances.get(ci);

        // Register client for CDMA subscription source changed event
        if (null != registrant) {
            mCdmaSSM.mCdmaSubscriptionSourceChangedRegistrants.add(registrant);
        }

        // Increment the reference count to keep track of when to dispose the
        // object
        mCdmaSSM.mRef++;
        return mCdmaSSM;
    }

    /**
     * Unregisters for the registered event with RIL
     */
    public void dispose(Handler handler) {
        if (null != handler) {
            mCdmaSubscriptionSourceChangedRegistrants.remove(handler);
        }

        mRef--;
        if (mRef <= 0) {
            mCM.unregisterForCdmaSubscriptionSourceChanged(this);
            mCM.unregisterForOn(this);
            mCdmaSSMInstances.remove(mCM);
        }
    }

    /*
     * (non-Javadoc)
     * @see android.os.Handler#handleMessage(android.os.Message)
     */
    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;
        switch (msg.what) {
            case EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED: {
                Log.d(LOG_TAG, "EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED");
                mCM.getCdmaSubscriptionSource(obtainMessage(EVENT_GET_CDMA_SUBSCRIPTION_SOURCE));
            }
                break;
            case EVENT_GET_CDMA_SUBSCRIPTION_SOURCE: {
                ar = (AsyncResult) msg.obj;
                handleCdmaSubscriptionSource(ar);
            }
                break;
            case EVENT_RADIO_ON: {
                mCM.getCdmaSubscriptionSource(obtainMessage(EVENT_GET_CDMA_SUBSCRIPTION_SOURCE));
            }
                break;
            default:
                super.handleMessage(msg);
        }
    }

    /**
     * Returns the current CDMA subscription source value
     *
     * @return CDMA subscription source value
     */
    public int getCdmaSubscriptionSource() {
        return mCdmaSubscriptionSource;
    }

    /**
     * Gets the default CDMA subscription source
     *
     * @param cr
     * @return
     */
    private int getDefaultCdmaSubscriptionSource() {
        // Get the default value from the Settings
        mCdmaSubscriptionSource = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.CDMA_SUBSCRIPTION_MODE, RILConstants.PREFERRED_CDMA_SUBSCRIPTION);
        return mCdmaSubscriptionSource;
    }

    /**
     * Handles the call to get the subscription source
     *
     * @param ar AsyncResult object that contains the result of get CDMA
     *            subscription source call
     */
    private void handleCdmaSubscriptionSource(AsyncResult ar) {
        if ((ar.exception == null) && (ar.result != null)) {
            int newSubscriptionSource = ((int[]) ar.result)[0];

            if (newSubscriptionSource != mCdmaSubscriptionSource) {
                Log.v(LOG_TAG, "Subscription Source Changed : " + mCdmaSubscriptionSource + " >> "
                        + newSubscriptionSource);
                mCdmaSubscriptionSource = newSubscriptionSource;

                // Notify registrants of the new CDMA subscription source
                mCdmaSubscriptionSourceChangedRegistrants.notifyRegistrants(new AsyncResult(null,
                        null, null));
            }
        } else {
            // GET_CDMA_SUBSCRIPTION is returning Failure. Probably
            // because modem created GSM Phone. If modem created
            // GSMPhone, then PhoneProxy will trigger a change in
            // Phone objects and this object will be destroyed.
            Log.w(LOG_TAG, "Unable to get CDMA Subscription Source, Exception: " + ar.exception
                    + ", result: " + ar.result);
        }
    }
}
