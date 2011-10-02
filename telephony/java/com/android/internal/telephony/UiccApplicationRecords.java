/*
 * Copyright (c) 2010, Code Aurora Forum. All rights reserved.
 * Copyright (c) 2010 The Android Open Source Project
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
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import com.android.internal.telephony.UiccConstants.AppType;

/** This class will handle application specific records.
 * It will be subclassed by RuimRecords, UsimRecords etc.
 * Every user of this class will be registered for Unavailable with every
 * object it gets reference to. It is the user's responsibility to unregister
 * and remove reference to object, once UNAVAILABLE callback is received.
 */
public abstract class UiccApplicationRecords extends Handler{
    protected boolean mDestroyed = false; //set to true once this object is commanded to be disposed of.
    // Reference to UiccRecords to be able to access files
    // outside if this application's DF
    protected UiccRecords mUiccRecords;
    protected Context mContext;
    protected CommandsInterface mCi;
    protected UiccCardApplication mParentApp;
    protected IccFileHandler mFh;
    protected UiccCard mParentCard;

    public static final int EVENT_CFI = 1;
    public static final int EVENT_SPN = 2;
    public static final int EVENT_EONS = 3;

    // Internal events
    protected static final int EVENT_APP_READY = 1;
    protected static final int EVENT_ICC_REFRESH = 2;

    private RegistrantList mUnavailableRegistrants = new RegistrantList();
    protected RegistrantList mRecordsEventsRegistrants = new RegistrantList();
    protected RegistrantList mNewSmsRegistrants = new RegistrantList();
    protected RegistrantList mNetworkSelectionModeAutomaticRegistrants = new RegistrantList();
    protected RegistrantList mImsiReadyRegistrants = new RegistrantList();

    public UiccApplicationRecords(UiccCardApplication parent, Context c, CommandsInterface ci, UiccRecords ur) {
        mContext = c;
        mCi = ci;
        mUiccRecords = ur;
        mParentApp = parent;
        mFh = mParentApp.getIccFileHandler();
        mParentCard = mParentApp.getCard();
        mParentApp.registerForReady(this, EVENT_APP_READY, null);
        mCi.registerForIccRefresh(this, EVENT_ICC_REFRESH, null);
    }

    public synchronized void dispose() {
        mDestroyed = true;
        mParentCard = null;
        mFh = null;
        mParentApp.unregisterForReady(this);
        mParentApp = null;
        mUiccRecords = null;
        mUnavailableRegistrants.notifyRegistrants();
        mCi.unregisterForIccRefresh(this);
        //TODO: Do we need to to anything here? Probably - once we have code here
    }

    public synchronized void registerForUnavailable(Handler h, int what, Object obj) {
        if (mDestroyed) {
            return;
        }

        Registrant r = new Registrant (h, what, obj);
        mUnavailableRegistrants.add(r);
    }
    public synchronized void unregisterForUnavailable(Handler h) {
        mUnavailableRegistrants.remove(h);
    }

    public UiccCardApplication getCardApplication() {
        return mParentApp;
    }

    public synchronized void registerForRecordsEvents(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mRecordsEventsRegistrants.add(r);
    }
    public synchronized void unregisterForRecordsEvents(Handler h) {
        mRecordsEventsRegistrants.remove(h);
    }

    public synchronized void registerForNewSms(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mNewSmsRegistrants.add(r);
    }
    public synchronized void unregisterForNewSms(Handler h) {
        mNewSmsRegistrants.remove(h);
    }

    public synchronized void registerForNetworkSelectionModeAutomatic(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mNetworkSelectionModeAutomaticRegistrants.add(r);
    }
    public synchronized void unregisterForNetworkSelectionModeAutomatic(Handler h) {
        mNetworkSelectionModeAutomaticRegistrants.remove(h);
    }

    public synchronized void registerForImsiReady(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mImsiReadyRegistrants.add(r);
        if (mImsi != null && mImsi.length() > 0) {
            r.notifyRegistrant();
        }
    }
    public synchronized void unregisterForImsiReady(Handler h) {
        mImsiReadyRegistrants.remove(h);
    }

    // IccRecords
    protected static final boolean DBG = true;
    // ***** Instance Variables

    protected RegistrantList recordsLoadedRegistrants = new RegistrantList();

    protected int recordsToLoad;  // number of pending load requests

    protected AdnRecordCache adnCache;

    // ***** Cached SIM State; cleared on channel close

    protected boolean recordsRequested = false; // true if we've made requests for the sim records

    public String iccid;
    protected String msisdn = null;  // My mobile number
    protected String msisdnTag = null;
    protected String mImsi = null;
    protected String voiceMailNum = null;
    protected String voiceMailTag = null;
    protected String newVoiceMailNum = null;
    protected String newVoiceMailTag = null;
    protected boolean isVoiceMailFixed = false;

    protected int mncLength = UNINITIALIZED;
    protected int mailboxIndex = 0; // 0 is no mailbox dailing number associated

    protected String spn;
    protected int spnDisplayCondition;

    // ***** Constants

    // Markers for mncLength
    protected static final int UNINITIALIZED = -1;
    protected static final int UNKNOWN = 0;

    // Bitmasks for SPN display rules.
    protected static final int SPN_RULE_SHOW_SPN  = 0x01;
    protected static final int SPN_RULE_SHOW_PLMN = 0x02;

    // ***** Event Constants
    protected static final int EVENT_SET_MSISDN_DONE = 30;

    //***** Public Methods
    public AdnRecordCache getAdnCache() {
        return adnCache;
    }

    public synchronized void registerForRecordsLoaded(Handler h, int what, Object obj) {
        if (mDestroyed) {
            return;
        }

        Registrant r = new Registrant(h, what, obj);
        recordsLoadedRegistrants.add(r);

        if (recordsToLoad == 0 && recordsRequested == true) {
            r.notifyRegistrant(new AsyncResult(null, null, null));
        }
    }
    public synchronized void unregisterForRecordsLoaded(Handler h) {
        recordsLoadedRegistrants.remove(h);
    }

    public String getMsisdnNumber() {
        return msisdn;
    }

    /**
     * Set subscriber number to SIM record
     *
     * The subscriber number is stored in EF_MSISDN (TS 51.011)
     *
     * When the operation is complete, onComplete will be sent to its handler
     *
     * @param alphaTag alpha-tagging of the dailing nubmer (up to 10 characters)
     * @param number dailing nubmer (up to 20 digits)
     *        if the number starts with '+', then set to international TOA
     * @param onComplete
     *        onComplete.obj will be an AsyncResult
     *        ((AsyncResult)onComplete.obj).exception == null on success
     *        ((AsyncResult)onComplete.obj).exception != null on fail
     */
    public void setMsisdnNumber(String alphaTag, String number,
            Message onComplete) {

        msisdn = number;
        msisdnTag = alphaTag;

        if(DBG) log("Set MSISDN: " + msisdnTag +" " + msisdn);


        AdnRecord adn = new AdnRecord(msisdnTag, msisdn);

        new AdnRecordLoader(mFh).updateEF(adn, IccConstants.EF_MSISDN, IccConstants.EF_EXT1, 1, null,
                obtainMessage(EVENT_SET_MSISDN_DONE, onComplete));
    }

    public String getMsisdnAlphaTag() {
        return msisdnTag;
    }

    public String getVoiceMailNumber() {
        return voiceMailNum;
    }

    /**
     * Return Service Provider Name stored in SIM (EF_SPN=0x6F46) or in RUIM (EF_RUIM_SPN=0x6F41)
     * @return null if SIM is not yet ready or no RUIM entry
     */
    public String getServiceProviderName() {
        return spn;
    }

    /**
     * Set voice mail number to SIM record
     *
     * The voice mail number can be stored either in EF_MBDN (TS 51.011) or
     * EF_MAILBOX_CPHS (CPHS 4.2)
     *
     * If EF_MBDN is available, store the voice mail number to EF_MBDN
     *
     * If EF_MAILBOX_CPHS is enabled, store the voice mail number to EF_CHPS
     *
     * So the voice mail number will be stored in both EFs if both are available
     *
     * Return error only if both EF_MBDN and EF_MAILBOX_CPHS fail.
     *
     * When the operation is complete, onComplete will be sent to its handler
     *
     * @param alphaTag alpha-tagging of the dailing nubmer (upto 10 characters)
     * @param voiceNumber dailing nubmer (upto 20 digits)
     *        if the number is start with '+', then set to international TOA
     * @param onComplete
     *        onComplete.obj will be an AsyncResult
     *        ((AsyncResult)onComplete.obj).exception == null on success
     *        ((AsyncResult)onComplete.obj).exception != null on fail
     */
    public abstract void setVoiceMailNumber(String alphaTag, String voiceNumber,
            Message onComplete);

    public String getVoiceMailAlphaTag() {
        return voiceMailTag;
    }

    /**
     * Sets the SIM voice message waiting indicator records
     * @param line GSM Subscriber Profile Number, one-based. Only '1' is supported
     * @param countWaiting The number of messages waiting, if known. Use
     *                     -1 to indicate that an unknown number of
     *                      messages are waiting
     */
    public abstract void setVoiceMessageWaiting(int line, int countWaiting, Message onComplete );

    /**
     * Called by STK Service when REFRESH is received.
     * @param fileChanged indicates whether any files changed
     * @param fileList if non-null, a list of EF files that changed
     */
    public abstract void onRefresh(boolean fileChanged, int[] fileList);


    public boolean getRecordsLoaded() {
        if (recordsToLoad == 0 && recordsRequested == true) {
            return true;
        } else {
            return false;
        }
    }

    //***** Overridden from Handler
    public abstract void handleMessage(Message msg);

    protected abstract void onRecordLoaded();

    protected abstract void onAllRecordsLoaded();

    /**
     * Returns the SpnDisplayRule based on settings on the SIM and the
     * specified plmn (currently-registered PLMN).  See TS 22.101 Annex A
     * and TS 51.011 10.3.11 for details.
     *
     * If the SPN is not found on the SIM, the rule is always PLMN_ONLY.
     */
    protected abstract int getDisplayRule(String plmn);

    protected abstract void log(String s);
}
