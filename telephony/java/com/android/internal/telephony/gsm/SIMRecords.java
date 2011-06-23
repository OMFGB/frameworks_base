/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.gsm;

import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC;
import android.content.Context;
import android.os.AsyncResult;
import android.os.Message;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.telephony.AdnRecord;
import com.android.internal.telephony.AdnRecordCache;
import com.android.internal.telephony.AdnRecordLoader;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.IccCardApplication;
import com.android.internal.telephony.IccConstants;
import com.android.internal.telephony.IccUtils;
import com.android.internal.telephony.IccVmFixedException;
import com.android.internal.telephony.IccVmNotSupportedException;
import com.android.internal.telephony.MccTable;
import com.android.internal.telephony.IccRefreshResponse;
import com.android.internal.telephony.UiccApplicationRecords;
import com.android.internal.telephony.UiccCardApplication;
import com.android.internal.telephony.UiccRecords;
import com.android.internal.telephony.UiccConstants.AppType;
import com.android.internal.telephony.gsm.Eons.CphsType;

import java.util.ArrayList;


/**
 * {@hide}
 */
public final class SIMRecords extends UiccApplicationRecords {
    static final String LOG_TAG = "GSM";

    private static final boolean CRASH_RIL = false;

    private static final boolean DBG = true;

    // ***** Instance Variables

    VoiceMailConstants mVmConfig;


    SpnOverride mSpnOverride;

    Eons mEons;

    // ***** Cached SIM State; cleared on channel close

    boolean callForwardingEnabled;


    /**
     * States only used by getSpnFsm FSM
     */
    private Get_Spn_Fsm_State spnState;

    /** CPHS service information (See CPHS 4.2 B.3.1.1)
     *  It will be set in onSimReady if reading GET_CPHS_INFO successfully
     *  mCphsInfo[0] is CPHS Phase
     *  mCphsInfo[1] and mCphsInfo[2] is CPHS Service Table
     */
    private byte[] mCphsInfo = null;
    boolean mCspPlmnEnabled = true;

    byte[] efMWIS = null;
    byte[] efCPHS_MWI =null;
    byte[] mEfCff = null;
    byte[] mEfCfis = null;


    int spnDisplayCondition;
    // Numeric network codes listed in TS 51.011 EF[SPDI]
    ArrayList<String> spdiNetworks = null;

    String pnnHomeName = null;

    // ***** Constants

    // Bitmasks for SPN display rules.
    static final int SPN_RULE_SHOW_SPN  = 0x01;
    static final int SPN_RULE_SHOW_PLMN = 0x02;

    // From TS 51.011 EF[SPDI] section
    static final int TAG_SPDI_PLMN_LIST = 0x80;

    // Full Name IEI from TS 24.008
    static final int TAG_FULL_NETWORK_NAME = 0x43;

    // Short Name IEI from TS 24.008
    static final int TAG_SHORT_NETWORK_NAME = 0x45;

    // active CFF from CPHS 4.2 B.4.5
    static final int CFF_UNCONDITIONAL_ACTIVE = 0x0a;
    static final int CFF_UNCONDITIONAL_DEACTIVE = 0x05;
    static final int CFF_LINE1_MASK = 0x0f;
    static final int CFF_LINE1_RESET = 0xf0;

    // CPHS Service Table (See CPHS 4.2 B.3.1)
    private static final int CPHS_SST_MBN_MASK = 0x30;
    private static final int CPHS_SST_MBN_ENABLED = 0x30;

    // ***** Event Constants

    private static final int EVENT_GET_IMSI_DONE = 3;
    private static final int EVENT_GET_ICCID_DONE = 4;
    private static final int EVENT_GET_MBI_DONE = 5;
    private static final int EVENT_GET_MBDN_DONE = 6;
    private static final int EVENT_GET_MWIS_DONE = 7;
    private static final int EVENT_GET_VOICE_MAIL_INDICATOR_CPHS_DONE = 8;
    private static final int EVENT_GET_AD_DONE = 9; // Admin data on SIM
    private static final int EVENT_GET_MSISDN_DONE = 10;
    private static final int EVENT_GET_CPHS_MAILBOX_DONE = 11;
    private static final int EVENT_GET_SPN_DONE = 12;
    private static final int EVENT_GET_SPDI_DONE = 13;
    private static final int EVENT_UPDATE_DONE = 14;
    private static final int EVENT_GET_PNN_DONE = 15;
    private static final int EVENT_GET_SST_DONE = 17;
    private static final int EVENT_GET_ALL_SMS_DONE = 18;
    private static final int EVENT_MARK_SMS_READ_DONE = 19;
    private static final int EVENT_SET_MBDN_DONE = 20;
    private static final int EVENT_SMS_ON_SIM = 21;
    private static final int EVENT_GET_SMS_DONE = 22;
    private static final int EVENT_GET_CFF_DONE = 24;
    private static final int EVENT_SET_CPHS_MAILBOX_DONE = 25;
    private static final int EVENT_GET_INFO_CPHS_DONE = 26;
    private static final int EVENT_SET_MSISDN_DONE = 30;
    private static final int EVENT_GET_CFIS_DONE = 32;
    private static final int EVENT_GET_CSP_CPHS_DONE = 33;
    private static final int EVENT_GET_ALL_OPL_RECORDS_DONE = 34;
    private static final int EVENT_GET_ALL_PNN_RECORDS_DONE = 35;
    private static final int EVENT_GET_SPN = 36;
    private static final int EVENT_GET_SPN_CPHS_DONE = 37;
    private static final int EVENT_GET_SPN_SHORT_CPHS_DONE = 38;

    // Lookup table for carriers known to produce SIMs which incorrectly indicate MNC length.

    private static final String[] MCCMNC_CODES_HAVING_3DIGITS_MNC = {
        "405025", "405026", "405027", "405028", "405029", "405030", "405031", "405032",
        "405033", "405034", "405035", "405036", "405037", "405038", "405039", "405040",
        "405041", "405042", "405043", "405044", "405045", "405046", "405047", "405750",
        "405751", "405752", "405753", "405754", "405755", "405756", "405799", "405800",
        "405801", "405802", "405803", "405804", "405805", "405806", "405807", "405808",
        "405809", "405810", "405811", "405812", "405813", "405814", "405815", "405816",
        "405817", "405818", "405819", "405820", "405821", "405822", "405823", "405824",
        "405825", "405826", "405827", "405828", "405829", "405830", "405831", "405832",
        "405833", "405834", "405835", "405836", "405837", "405838", "405839", "405840",
        "405841", "405842", "405843", "405844", "405845", "405846", "405847", "405848",
        "405849", "405850", "405851", "405852", "405853", "405875", "405876", "405877",
        "405878", "405879", "405880", "405881", "405882", "405883", "405884", "405885",
        "405886", "405908", "405909", "405910", "405911", "405925", "405926", "405927",
        "405928", "405929", "405932"
    };

    private static final int EVENT_SET_MWIS_DONE = 39;
    private static final int EVENT_SET_CPHS_MWIS_DONE = 40;
    // ***** Constructor

    public SIMRecords(UiccCardApplication parent, UiccRecords ur, Context c, CommandsInterface ci) {
        super(parent, c, ci, ur);

        adnCache = new AdnRecordCache(mFh);

        mVmConfig = new VoiceMailConstants();
        mSpnOverride = new SpnOverride();

        mEons = new Eons();

        recordsRequested = false;  // No load request is made till SIM ready

        // recordsToLoad is set to 0 because no requests are made yet
        recordsToLoad = 0;

        mCi.setOnSmsOnSim(this, EVENT_SMS_ON_SIM, null);

        // Start off by setting empty state
        resetRecords();

    }

    public void dispose() {
        Log.d(LOG_TAG, "Disposing SIMRecords " + this);
        //Unregister for all events
        mCi.unregisterForOffOrNotAvailable( this);
        resetRecords();
    }

    protected void finalize() {
        if(DBG) Log.d(LOG_TAG, "SIMRecords finalized");
    }

    protected void resetRecords() {
        mImsi = null;
        msisdn = null;
        voiceMailNum = null;
        mncLength = UNINITIALIZED;
        iccid = null;
        // -1 means no EF_SPN found; treat accordingly.
        spnDisplayCondition = -1;
        efMWIS = null;
        efCPHS_MWI = null;
        spdiNetworks = null;
        pnnHomeName = null;

        adnCache.reset();
        mEons.reset();

        SystemProperties.set(PROPERTY_ICC_OPERATOR_NUMERIC, null);
        SystemProperties.set(PROPERTY_ICC_OPERATOR_ALPHA, null);
        SystemProperties.set(PROPERTY_ICC_OPERATOR_ISO_COUNTRY, null);

        // recordsRequested is set to false indicating that the SIM
        // read requests made so far are not valid. This is set to
        // true only when fresh set of read requests are made.
        recordsRequested = false;
    }


    //***** Public Methods

    /** Returns null if SIM is not yet ready */
    public String getIMSI() {
        return mImsi;
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

        if(DBG) log("Set MSISDN: " + msisdnTag + " " + /*msisdn*/ "xxxxxxx");


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
    public void setVoiceMailNumber(String alphaTag, String voiceNumber,
            Message onComplete) {
        if (isVoiceMailFixed) {
            AsyncResult.forMessage((onComplete)).exception =
                    new IccVmFixedException("Voicemail number is fixed by operator");
            onComplete.sendToTarget();
            return;
        }

        newVoiceMailNum = voiceNumber;
        newVoiceMailTag = alphaTag;

        AdnRecord adn = new AdnRecord(newVoiceMailTag, newVoiceMailNum);

        if (mailboxIndex != 0 && mailboxIndex != 0xff) {

            new AdnRecordLoader(mFh).updateEF(adn, IccConstants.EF_MBDN, IccConstants.EF_EXT6,
                    mailboxIndex, null,
                    obtainMessage(EVENT_SET_MBDN_DONE, onComplete));

        } else if (isCphsMailboxEnabled()) {

            new AdnRecordLoader(mFh).updateEF(adn, IccConstants.EF_MAILBOX_CPHS,
                    IccConstants.EF_EXT1, 1, null,
                    obtainMessage(EVENT_SET_CPHS_MAILBOX_DONE, onComplete));

        } else {
            AsyncResult.forMessage((onComplete)).exception =
                    new IccVmNotSupportedException("Update SIM voice mailbox error");
            onComplete.sendToTarget();
        }
    }

    public String getVoiceMailAlphaTag()
    {
        return voiceMailTag;
    }

    /**
     * Sets the SIM voice message waiting indicator records
     * @param line GSM Subscriber Profile Number, one-based. Only '1' is supported
     * @param countWaiting The number of messages waiting, if known. Use
     *                     -1 to indicate that an unknown number of
     *                      messages are waiting
     * @param onComplete Message that needs to be posted back to the caller on
     *            completion. Used to propagate errors from the response to the
     *            request originator
     */
    public void
    setVoiceMessageWaiting(int line, int countWaiting, Message onComplete) {
        if (line != 1) {
            // only profile 1 is supported
            return;
        }

        try {
            if (efMWIS != null) {
                // TS 51.011 10.3.45

                // lsb of byte 0 is 'voicemail' status
                efMWIS[0] = (byte)((efMWIS[0] & 0xfe)
                                    | (countWaiting == 0 ? 0 : 1));

                // byte 1 is the number of voice messages waiting
                if (countWaiting < 0) {
                    // The spec does not define what this should be
                    // if we don't know the count
                    efMWIS[1] = 0;
                } else {
                    efMWIS[1] = (byte) countWaiting;
                }

                mFh.updateEFLinearFixed(
                    IccConstants.EF_MWIS, 1, efMWIS, null,
                    obtainMessage (EVENT_SET_MWIS_DONE, IccConstants.EF_MWIS, 0, onComplete ));
            } else if (efCPHS_MWI != null) {
                    // Refer CPHS4_2.WW6 B4.2.3
                efCPHS_MWI[0] = (byte)((efCPHS_MWI[0] & 0xf0)
                            | (countWaiting == 0 ? 0x5 : 0xa));
                mFh.updateEFTransparent(
                     IccConstants.EF_VOICE_MAIL_INDICATOR_CPHS,
                        efCPHS_MWI,
                        obtainMessage(EVENT_SET_CPHS_MWIS_DONE, IccConstants.EF_VOICE_MAIL_INDICATOR_CPHS, 0,
                                onComplete));
            } else {
                AsyncResult.forMessage((onComplete)).exception =
                    new IccVmNotSupportedException(
                        "SIM does not support EF_MWIS & EF_CPHS_MWIS");
                onComplete.sendToTarget();
            }

        } catch (ArrayIndexOutOfBoundsException ex) {
            Log.w(LOG_TAG,
                "Error saving voice mail state to SIM. Probably malformed SIM record", ex);
        }
    }

    /**
     * Check if call forward info is stored on sim
     * @return true if call forward info is stored on sim.
     */
    public boolean isCallForwardStatusStored() {
        return (mEfCfis != null) || (mEfCff != null);
    }

    public boolean getVoiceCallForwardingFlag() {
        return callForwardingEnabled;
    }

    public void setVoiceCallForwardingFlag(int line, boolean enable) {

        if (line != 1) return; // only line 1 is supported

        callForwardingEnabled = enable;

        mRecordsEventsRegistrants.notifyResult(EVENT_CFI);

        try {
            if (mEfCfis != null) {
                // lsb is of byte 1 is voice status
                if (enable) {
                    mEfCfis[1] |= 1;
                } else {
                    mEfCfis[1] &= 0xfe;
                }

                // TODO: Should really update other fields in EF_CFIS, eg,
                // dialing number.  We don't read or use it right now.

                mFh.updateEFLinearFixed(
                        IccConstants.EF_CFIS, 1, mEfCfis, null,
                        obtainMessage (EVENT_UPDATE_DONE, IccConstants.EF_CFIS));
            }

            if (mEfCff != null) {
                if (enable) {
                    mEfCff[0] = (byte) ((mEfCff[0] & CFF_LINE1_RESET)
                            | CFF_UNCONDITIONAL_ACTIVE);
                } else {
                    mEfCff[0] = (byte) ((mEfCff[0] & CFF_LINE1_RESET)
                            | CFF_UNCONDITIONAL_DEACTIVE);
                }

                mFh.updateEFTransparent(
                        IccConstants.EF_CFF_CPHS, mEfCff,
                        obtainMessage (EVENT_UPDATE_DONE, IccConstants.EF_CFF_CPHS));
            }
        } catch (ArrayIndexOutOfBoundsException ex) {
            Log.w(LOG_TAG,
                    "Error saving call fowarding flag to SIM. "
                            + "Probably malformed SIM record", ex);

        }
    }

    /**
     * Called by STK Service when REFRESH is received.
     * @param fileChanged indicates whether any files changed
     * @param fileList if non-null, a list of EF files that changed
     */
    public void onRefresh(boolean fileChanged, int[] fileList) {
        if (fileChanged) {
            // A future optimization would be to inspect fileList and
            // only reload those files that we care about.  For now,
            // just re-fetch all SIM records that we cache.
            fetchSimRecords();
        }
    }

    /** Returns the 5 or 6 digit MCC/MNC of the operator that
     *  provided the SIM card. Returns null of SIM is not yet ready
     */
    public String getSIMOperatorNumeric() {
        if (mImsi == null || mncLength == UNINITIALIZED || mncLength == UNKNOWN) {
            return null;
        }

        // Length = length of MCC + length of MNC
        // length of mcc = 3 (TS 23.003 Section 2.2)
        return mImsi.substring(0, 3 + mncLength);
    }

    // ***** Overridden from Handler
    public void handleMessage(Message msg) {
        AsyncResult ar;
        AdnRecord adn;

        byte data[];

        boolean isRecordLoadResponse = false;

        if (mDestroyed) {
            Log.e(LOG_TAG, "Received message " + msg +
                    "[" + msg.what + "] while being destroyed. Ignoring.");
            return;
        }

        try { switch (msg.what) {
            case EVENT_APP_READY:
                onSimReady();
            break;

            /* IO events */
            case EVENT_GET_IMSI_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {
                    Log.e(LOG_TAG, "Exception querying IMSI, Exception:" + ar.exception);
                    break;
                }

                mImsi = (String) ar.result;

                // IMSI (MCC+MNC+MSIN) is at least 6 digits, but not more
                // than 15 (and usually 15).
                if (mImsi != null && (mImsi.length() < 6 || mImsi.length() > 15)) {
                    Log.e(LOG_TAG, "invalid IMSI " + mImsi);
                    mImsi = null;
                }

                Log.d(LOG_TAG, "IMSI: " + mImsi.substring(0, 6) + "xxxxxxx");

                if (((mncLength == UNKNOWN) || (mncLength == 2)) &&
                        ((imsi != null) && (imsi.length() >= 6))) {
                    String mccmncCode = imsi.substring(0, 6);
                    for (String mccmnc : MCCMNC_CODES_HAVING_3DIGITS_MNC) {
                        if (mccmnc.equals(mccmncCode)) {
                            mncLength = 3;
                            break;
                        }
                    }
                }

                if (mncLength == UNKNOWN) {
                    // the SIM has told us all it knows, but it didn't know the mnc length.
                    // guess using the mcc
                    try {
                        int mcc = Integer.parseInt(mImsi.substring(0,3));
                        mncLength = MccTable.smallestDigitsMccForMnc(mcc);
                    } catch (NumberFormatException e) {
                        mncLength = UNKNOWN;
                        Log.e(LOG_TAG, "SIMRecords: Corrupt IMSI!");
                    }
                }

                if (mncLength != UNKNOWN && mncLength != UNINITIALIZED) {
                    // finally have both the imsi and the mncLength and can parse the imsi properly
                    MccTable.updateMccMncConfiguration(mContext, mImsi.substring(0, 3 + mncLength));
                }
                mImsiReadyRegistrants.notifyRegistrants();
            break;

            case EVENT_GET_MBI_DONE:
                boolean isValidMbdn;
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[]) ar.result;

                isValidMbdn = false;
                if (ar.exception == null) {
                    // Refer TS 51.011 Section 10.3.44 for content details
                    Log.d(LOG_TAG, "EF_MBI: " +
                            IccUtils.bytesToHexString(data));

                    // Voice mail record number stored first
                    mailboxIndex = (int)data[0] & 0xff;

                    // check if dailing numbe id valid
                    if (mailboxIndex != 0 && mailboxIndex != 0xff) {
                        Log.d(LOG_TAG, "Got valid mailbox number for MBDN");
                        isValidMbdn = true;
                    }
                }

                // one more record to load
                recordsToLoad += 1;

                if (isValidMbdn) {
                    // Note: MBDN was not included in NUM_OF_SIM_RECORDS_LOADED
                    new AdnRecordLoader(mFh).loadFromEF(IccConstants.EF_MBDN, IccConstants.EF_EXT6,
                            mailboxIndex, obtainMessage(EVENT_GET_MBDN_DONE));
                } else {
                    // If this EF not present, try mailbox as in CPHS standard
                    // CPHS (CPHS4_2.WW6) is a european standard.
                    new AdnRecordLoader(mFh).loadFromEF(IccConstants.EF_MAILBOX_CPHS,
                            IccConstants.EF_EXT1, 1,
                            obtainMessage(EVENT_GET_CPHS_MAILBOX_DONE));
                }

                break;
            case EVENT_GET_CPHS_MAILBOX_DONE:
            case EVENT_GET_MBDN_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {

                    Log.d(LOG_TAG, "Invalid or missing EF"
                        + ((msg.what == EVENT_GET_CPHS_MAILBOX_DONE) ? "[MAILBOX]" : "[MBDN]"));

                    // Bug #645770 fall back to CPHS
                    // FIXME should use SST to decide

                    if (msg.what == EVENT_GET_MBDN_DONE) {
                        //load CPHS on fail...
                        // FIXME right now, only load line1's CPHS voice mail entry

                        recordsToLoad += 1;
                        new AdnRecordLoader(mFh).loadFromEF(
                                IccConstants.EF_MAILBOX_CPHS, IccConstants.EF_EXT1, 1,
                                obtainMessage(EVENT_GET_CPHS_MAILBOX_DONE));
                    }
                    break;
                }

                adn = (AdnRecord)ar.result;

                Log.d(LOG_TAG, "VM: " + adn +
                        ((msg.what == EVENT_GET_CPHS_MAILBOX_DONE) ? " EF[MAILBOX]" : " EF[MBDN]"));

                if (adn.isEmpty() && msg.what == EVENT_GET_MBDN_DONE) {
                    // Bug #645770 fall back to CPHS
                    // FIXME should use SST to decide
                    // FIXME right now, only load line1's CPHS voice mail entry
                    recordsToLoad += 1;
                    new AdnRecordLoader(mFh).loadFromEF(
                            IccConstants.EF_MAILBOX_CPHS, IccConstants.EF_EXT1, 1,
                            obtainMessage(EVENT_GET_CPHS_MAILBOX_DONE));

                    break;
                }

                voiceMailNum = adn.getNumber();
                voiceMailTag = adn.getAlphaTag();
            break;

            case EVENT_GET_MSISDN_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {
                    Log.d(LOG_TAG, "Invalid or missing EF[MSISDN]");
                    break;
                }

                adn = (AdnRecord)ar.result;

                msisdn = adn.getNumber();
                msisdnTag = adn.getAlphaTag();

                Log.d(LOG_TAG, "MSISDN: " + /*msisdn*/ "xxxxxxx");
            break;

            case EVENT_SET_MSISDN_DONE:
                isRecordLoadResponse = false;
                ar = (AsyncResult)msg.obj;

                if (ar.userObj != null) {
                    AsyncResult.forMessage(((Message) ar.userObj)).exception
                            = ar.exception;
                    ((Message) ar.userObj).sendToTarget();
                }
                break;

            case EVENT_GET_MWIS_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;

                Log.d(LOG_TAG, "EF_MWIS : " + IccUtils.bytesToHexString(data));

                if (ar.exception != null) {
                    Log.d(LOG_TAG, "EVENT_GET_MWIS_DONE exception = "
                            + ar.exception);
                    break;
                }

                if ((data[0] & 0xff) == 0xff) {
                    Log.d(LOG_TAG, "SIMRecords: Uninitialized record MWIS");
                    break;
                }

                efMWIS = data;
                break;

            case EVENT_SET_MWIS_DONE:
            case EVENT_SET_CPHS_MWIS_DONE: {
                ar = (AsyncResult) msg.obj;
                Message onComplete = (Message) ar.userObj;
                if (onComplete == null) {
                    break;
                }
                if (ar.exception != null) {
                    AsyncResult.forMessage((onComplete)).exception =
                        new IccVmNotSupportedException(
                            "SIM update failed for EF_MWIS/EF_CPHS_MWIS");
                } else {
                    AsyncResult.forMessage((onComplete)).exception = null;
                }
                onComplete.sendToTarget();
                break;
            }

            case EVENT_GET_VOICE_MAIL_INDICATOR_CPHS_DONE:
                isRecordLoadResponse = true;
                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;

                Log.d(LOG_TAG, "EF_CPHS_MWI: " + IccUtils.bytesToHexString(data));

                if (ar.exception != null) {
                    Log.d(LOG_TAG, "EVENT_GET_VOICE_MAIL_INDICATOR_CPHS_DONE exception = "
                            + ar.exception);
                    break;
                }
                efCPHS_MWI = data;
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


            case EVENT_GET_AD_DONE:
                try {
                    isRecordLoadResponse = true;

                    ar = (AsyncResult)msg.obj;
                    data = (byte[])ar.result;

                    if (ar.exception != null) {
                        break;
                    }

                    Log.d(LOG_TAG, "EF_AD: " +
                            IccUtils.bytesToHexString(data));

                    if (data.length < 3) {
                        Log.d(LOG_TAG, "SIMRecords: Corrupt AD data on SIM");
                        break;
                    }

                    if (data.length == 3) {
                        Log.d(LOG_TAG, "SIMRecords: MNC length not present in EF_AD");
                        break;
                    }

                    mncLength = (int)data[3] & 0xf;

                    if (mncLength == 0xf) {
                        mncLength = UNKNOWN;
                    }
                } finally {
                    if (((mncLength == UNINITIALIZED) || (mncLength == UNKNOWN) ||
                            (mncLength == 2)) && ((imsi != null) && (imsi.length() >= 6))) {
                        String mccmncCode = imsi.substring(0, 6);
                        for (String mccmnc : MCCMNC_CODES_HAVING_3DIGITS_MNC) {
                            if (mccmnc.equals(mccmncCode)) {
                                mncLength = 3;
                                break;
                            }
                        }
                    }

                    if (mncLength == UNKNOWN || mncLength == UNINITIALIZED) {
                        if (mImsi != null) {
                            try {
                                int mcc = Integer.parseInt(mImsi.substring(0,3));

                                mncLength = MccTable.smallestDigitsMccForMnc(mcc);
                            } catch (NumberFormatException e) {
                                mncLength = UNKNOWN;
                                Log.e(LOG_TAG, "SIMRecords: Corrupt IMSI!");
                            }
                        } else {
                            // Indicate we got this info, but it didn't contain the length.
                            mncLength = UNKNOWN;

                            Log.d(LOG_TAG, "SIMRecords: MNC length not present in EF_AD");
                        }
                    }
                    if (mImsi != null && mncLength != UNKNOWN) {
                        // finally have both imsi and the length of the mnc and can parse
                        // the imsi properly
                        MccTable.updateMccMncConfiguration(mContext, mImsi.substring(0, 3 + mncLength));
                    }
                }
            break;

            case EVENT_GET_SPN_DONE:
                isRecordLoadResponse = true;
                ar = (AsyncResult) msg.obj;
                getSpnFsm(false, ar);
            break;

            case EVENT_GET_CFF_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult) msg.obj;
                data = (byte[]) ar.result;

                if (ar.exception != null) {
                    break;
                }

                Log.d(LOG_TAG, "EF_CFF_CPHS: " +
                        IccUtils.bytesToHexString(data));
                mEfCff = data;

                if (mEfCfis == null) {
                    callForwardingEnabled =
                        ((data[0] & CFF_LINE1_MASK) == CFF_UNCONDITIONAL_ACTIVE);

                    mRecordsEventsRegistrants.notifyResult(EVENT_CFI);
                }
                break;

            case EVENT_GET_SPDI_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;

                if (ar.exception != null) {
                    break;
                }

                parseEfSpdi(data);
            break;

            case EVENT_UPDATE_DONE:
                ar = (AsyncResult)msg.obj;
                if (ar.exception != null) {
                    Log.i(LOG_TAG, "SIMRecords update failed", ar.exception);
                }
            break;

            case EVENT_GET_PNN_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;

                if (ar.exception != null) {
                    break;
                }

                SimTlv tlv = new SimTlv(data, 0, data.length);

                for ( ; tlv.isValidObject() ; tlv.nextObject()) {
                    if (tlv.getTag() == TAG_FULL_NETWORK_NAME) {
                        pnnHomeName
                            = IccUtils.networkNameToString(
                                tlv.getData(), 0, tlv.getData().length);
                        break;
                    }
                }
            break;

            case EVENT_GET_ALL_SMS_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                if (ar.exception != null)
                    break;

                handleSmses((ArrayList) ar.result);
                break;

            case EVENT_MARK_SMS_READ_DONE:
                Log.i("ENF", "marked read: sms " + msg.arg1);
                break;


            case EVENT_SMS_ON_SIM:
                isRecordLoadResponse = false;

                ar = (AsyncResult)msg.obj;

                int[] index = (int[])ar.result;

                if (ar.exception != null || index.length != 1) {
                    Log.e(LOG_TAG, "[SIMRecords] Error on SMS_ON_SIM with exp "
                            + ar.exception + " length " + index.length);
                } else {
                    Log.d(LOG_TAG, "READ EF_SMS RECORD index=" + index[0]);
                    mFh.loadEFLinearFixed(IccConstants.EF_SMS,index[0],
                            obtainMessage(EVENT_GET_SMS_DONE));
                }
                break;

            case EVENT_GET_SMS_DONE:
                isRecordLoadResponse = false;
                ar = (AsyncResult)msg.obj;
                if (ar.exception == null) {
                    handleSms((byte[])ar.result);
                } else {
                    Log.e(LOG_TAG, "[SIMRecords] Error on GET_SMS with exp "
                            + ar.exception);
                }
                break;
            case EVENT_GET_SST_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;

                if (ar.exception != null) {
                    break;
                }

                //Log.d(LOG_TAG, "SST: " + IccUtils.bytesToHexString(data));
            break;

            case EVENT_GET_INFO_CPHS_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {
                    break;
                }

                mCphsInfo = (byte[])ar.result;

                if (DBG) log("iCPHS: " + IccUtils.bytesToHexString(mCphsInfo));
            break;

            case EVENT_SET_MBDN_DONE:
                isRecordLoadResponse = false;
                ar = (AsyncResult)msg.obj;

                if (ar.exception == null) {
                    voiceMailNum = newVoiceMailNum;
                    voiceMailTag = newVoiceMailTag;
                }

                if (isCphsMailboxEnabled()) {
                    adn = new AdnRecord(voiceMailTag, voiceMailNum);
                    Message onCphsCompleted = (Message) ar.userObj;

                    /* write to cphs mailbox whenever it is available but
                    * we only need notify caller once if both updating are
                    * successful.
                    *
                    * so if set_mbdn successful, notify caller here and set
                    * onCphsCompleted to null
                    */
                    if (ar.exception == null && ar.userObj != null) {
                        AsyncResult.forMessage(((Message) ar.userObj)).exception
                                = null;
                        ((Message) ar.userObj).sendToTarget();

                        if (DBG) log("Callback with MBDN successful.");

                        onCphsCompleted = null;
                    }

                    new AdnRecordLoader(mFh).
                            updateEF(adn, IccConstants.EF_MAILBOX_CPHS, IccConstants.EF_EXT1, 1, null,
                            obtainMessage(EVENT_SET_CPHS_MAILBOX_DONE,
                                    onCphsCompleted));
                } else {
                    if (ar.userObj != null) {
                        AsyncResult.forMessage(((Message) ar.userObj)).exception
                                = ar.exception;
                        ((Message) ar.userObj).sendToTarget();
                    }
                }
                break;
            case EVENT_SET_CPHS_MAILBOX_DONE:
                isRecordLoadResponse = false;
                ar = (AsyncResult)msg.obj;
                if(ar.exception == null) {
                    voiceMailNum = newVoiceMailNum;
                    voiceMailTag = newVoiceMailTag;
                } else {
                    if (DBG) log("Set CPHS MailBox with exception: "
                            + ar.exception);
                }
                if (ar.userObj != null) {
                    if (DBG) log("Callback with CPHS MB successful.");
                    AsyncResult.forMessage(((Message) ar.userObj)).exception
                            = ar.exception;
                    ((Message) ar.userObj).sendToTarget();
                }
                break;
            case EVENT_ICC_REFRESH:
                isRecordLoadResponse = false;
                ar = (AsyncResult)msg.obj;
                if (DBG) log("Sim REFRESH with exception: " + ar.exception);
                if (ar.exception == null) {
                    handleSimRefresh(ar);
                }
                break;
            case EVENT_GET_CFIS_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;

                if (ar.exception != null) {
                    break;
                }

                Log.d(LOG_TAG, "EF_CFIS: " +
                   IccUtils.bytesToHexString(data));

                mEfCfis = data;

                // Refer TS 51.011 Section 10.3.46 for the content description
                callForwardingEnabled = ((data[1] & 0x01) != 0);

                mRecordsEventsRegistrants.notifyResult(EVENT_CFI);
                break;

            case EVENT_GET_CSP_CPHS_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {
                    Log.e(LOG_TAG,"Exception in fetching EF_CSP data " + ar.exception);
                    break;
                }

                data = (byte[])ar.result;

                Log.i(LOG_TAG,"EF_CSP: " + IccUtils.bytesToHexString(data));
                handleEfCspData(data);
                break;

            case EVENT_GET_ALL_OPL_RECORDS_DONE:
                isRecordLoadResponse = true;
                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {
                    Log.e(LOG_TAG, "[EONS] Exception in fetching OPL Records: " + ar.exception);
                    mEons.resetOplData();
                    break;
                }

                mEons.setOplData((ArrayList<byte[]>)ar.result);
                mRecordsEventsRegistrants.notifyResult(EVENT_EONS);
                break;

            case EVENT_GET_ALL_PNN_RECORDS_DONE:
                isRecordLoadResponse = true;
                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {
                    Log.e(LOG_TAG, "[EONS] Exception in fetching PNN Records: " + ar.exception);
                    mEons.resetPnnData();
                    break;
                }

                mEons.setPnnData((ArrayList<byte[]>)ar.result);
                mRecordsEventsRegistrants.notifyResult(EVENT_EONS);
                break;

            case EVENT_GET_SPN_CPHS_DONE:
                isRecordLoadResponse = true;
                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {
                    Log.e(LOG_TAG, "[EONS] Exception in reading EF_SPN_CPHS: " + ar.exception);
                    mEons.resetCphsData(CphsType.LONG);
                    break;
                }

                data = (byte[]) ar.result;
                mEons.setCphsData(CphsType.LONG, data);
                break;

            case EVENT_GET_SPN_SHORT_CPHS_DONE:
                isRecordLoadResponse = true;
                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {
                    Log.e(LOG_TAG, "[EONS] Exception in reading EF_SPN_SHORT_CPHS: " + ar.exception);
                    mEons.resetCphsData(CphsType.SHORT);
                    break;
                }

                data = (byte[]) ar.result;
                mEons.setCphsData(CphsType.SHORT, data);
                break;

             case EVENT_GET_SPN:
                isRecordLoadResponse = true;
                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {
                    Log.e(LOG_TAG, "[EONS] Exception in reading EF_SPN: " + ar.exception);
                    spnDisplayCondition = -1;
                    break;
                }

                data = (byte[]) ar.result;
                spnDisplayCondition = 0xff & data[0];
                spn = IccUtils.adnStringFieldToString(data, 1, data.length - 1);

                SystemProperties.set(PROPERTY_ICC_OPERATOR_ALPHA, spn);

                // When device enters or exits Home Zone, certain operators update
                // EF_SPN file. This helps to know if the device is in Home Zone or
                // not. Hence SPN display should be updated on EF_SPN refresh.
                mRecordsEventsRegistrants.notifyResult(EVENT_SPN);
                break;
        }}catch (RuntimeException exc) {
            // I don't want these exceptions to be fatal
            Log.w(LOG_TAG, "Exception parsing SIM record", exc);
        } finally {
            // Count up record load responses even if they are fails
            if (isRecordLoadResponse) {
                onRecordLoaded();
            }
        }
    }

    private void handleFileUpdate(int efid) {
        switch(efid) {
            case IccConstants.EF_MBDN:
                recordsToLoad++;
                new AdnRecordLoader(mFh).loadFromEF(IccConstants.EF_MBDN, IccConstants.EF_EXT6,
                        mailboxIndex, obtainMessage(EVENT_GET_MBDN_DONE));
                break;
            case IccConstants.EF_MAILBOX_CPHS:
                recordsToLoad++;
                new AdnRecordLoader(mFh).loadFromEF(IccConstants.EF_MAILBOX_CPHS, IccConstants.EF_EXT1,
                        1, obtainMessage(EVENT_GET_CPHS_MAILBOX_DONE));
                break;
            case IccConstants.EF_CSP_CPHS:
                recordsToLoad++;
                Log.i(LOG_TAG,"CSP: SIM Refresh called for EF_CSP_CPHS");
                mFh.loadEFTransparent(IccConstants.EF_CSP_CPHS,
                        obtainMessage(EVENT_GET_CSP_CPHS_DONE));
                break;
            case IccConstants.EF_OPL:
                if (DBG) log("[EONS] SIM Refresh for EF_OPL");
                recordsToLoad++;
                mFh.loadEFLinearFixedAll(IccConstants.EF_OPL,
                      obtainMessage(EVENT_GET_ALL_OPL_RECORDS_DONE));
                break;
            case IccConstants.EF_PNN:
                if (DBG) log("[EONS] SIM Refresh for EF_PNN");
                recordsToLoad++;
                mFh.loadEFLinearFixedAll(IccConstants.EF_PNN,
                      obtainMessage(EVENT_GET_ALL_PNN_RECORDS_DONE));
                break;
            case IccConstants.EF_SPN:
                if (DBG) log("[EONS] SIM Refresh for EF_SPN");
                recordsToLoad++;
                mFh.loadEFTransparent(IccConstants.EF_SPN,
                        obtainMessage(EVENT_GET_SPN));
                break;
            case IccConstants.EF_SPN_CPHS:
                if (DBG) log("[EONS] SIM Refresh for EF_SPN_CPHS");
                recordsToLoad++;
                mFh.loadEFTransparent(IccConstants.EF_SPN_CPHS,
                        obtainMessage(EVENT_GET_SPN_CPHS_DONE));
                break;
            case IccConstants.EF_SPN_SHORT_CPHS:
                if (DBG) log("[EONS] SIM Refresh for EF_SPN_SHORT_CPHS");
                recordsToLoad++;
                mFh.loadEFTransparent(IccConstants.EF_SPN_SHORT_CPHS,
                        obtainMessage(EVENT_GET_SPN_SHORT_CPHS_DONE));
                break;
            default:
                // For now, fetch all records if this is not
                // one of above handled files.
                // TODO: Handle other cases, instead of fetching all.
                adnCache.reset();
                fetchSimRecords();
                break;
        }
    }

    private void handleSimRefresh(AsyncResult ar) {
        IccRefreshResponse state = (IccRefreshResponse)ar.result;
        if (state == null) {
            if (DBG) log("handleSimRefresh received without input");
            return;
        }

        switch (state.refreshResult) {
            case SIM_FILE_UPDATE:
                if (DBG) log("handleSimRefresh with SIM_FILE_UPDATED");
                handleFileUpdate(state.efId);
                break;
            case SIM_INIT:
                if (DBG) log("handleSimRefresh with SIM_INIT, Delay SIM IO until SIM_READY");
                // need to reload all files (that we care about after SIM_READY)
                adnCache.reset();
                break;
            case SIM_RESET:
                if (DBG) log("handleSimRefresh with SIM_RESET");
                mCi.setRadioPower(false, null);
                /* Note: no need to call setRadioPower(true).  Assuming the desired
                * radio power state is still ON (as tracked by ServiceStateTracker),
                * ServiceStateTracker will call setRadioPower when it receives the
                * RADIO_STATE_CHANGED notification for the power off.  And if the
                * desired power state has changed in the interim, we don't want to
                * override it with an unconditional power on.
                */
                break;
            default:
                // unknown refresh operation
                if (DBG) log("handleSimRefresh with unknown operation");
                break;
        }
    }

    private void handleSms(byte[] ba) {
        if (ba[0] != 0)
            Log.d("ENF", "status : " + ba[0]);

        // 3GPP TS 51.011 v5.0.0 (20011-12)  10.5.3
        // 3 == "received by MS from network; message to be read"
        if (ba[0] == 3) {
            int n = ba.length;

            // Note: Data may include trailing FF's.  That's OK; message
            // should still parse correctly.
            byte[] pdu = new byte[n - 1];
            System.arraycopy(ba, 1, pdu, 0, n - 1);
            SmsMessage message = SmsMessage.createFromPdu(pdu);

            mNewSmsRegistrants.notifyResult(message);
        }
    }


    private void handleSmses(ArrayList messages) {
        int count = messages.size();

        for (int i = 0; i < count; i++) {
            byte[] ba = (byte[]) messages.get(i);

            if (ba[0] != 0)
                Log.i("ENF", "status " + i + ": " + ba[0]);

            // 3GPP TS 51.011 v5.0.0 (20011-12)  10.5.3
            // 3 == "received by MS from network; message to be read"

            if (ba[0] == 3) {
                int n = ba.length;

                // Note: Data may include trailing FF's.  That's OK; message
                // should still parse correctly.
                byte[] pdu = new byte[n - 1];
                System.arraycopy(ba, 1, pdu, 0, n - 1);
                SmsMessage message = SmsMessage.createFromPdu(pdu);

                mNewSmsRegistrants.notifyResult(message);

                // 3GPP TS 51.011 v5.0.0 (20011-12)  10.5.3
                // 1 == "received by MS from network; message read"

                ba[0] = 1;

                if (false) { // XXX writing seems to crash RdoServD
                    mFh.updateEFLinearFixed(IccConstants.EF_SMS,
                            i, ba, null, obtainMessage(EVENT_MARK_SMS_READ_DONE, i));
                }
            }
        }
    }

    protected void onRecordLoaded() {
        // One record loaded successfully or failed, In either case
        // we need to update the recordsToLoad count
        recordsToLoad -= 1;
        Log.v(LOG_TAG, "SIMRecords:onRecordLoaded " + recordsToLoad + " requested: " + recordsRequested);

        if (recordsToLoad == 0 && recordsRequested == true) {
            onAllRecordsLoaded();
        } else if (recordsToLoad < 0) {
            Log.e(LOG_TAG, "SIMRecords: recordsToLoad <0, programmer error suspected");
            recordsToLoad = 0;
        }
    }

    protected void onAllRecordsLoaded() {
        Log.d(LOG_TAG, "SIMRecords: record load complete");

        String operator = getSIMOperatorNumeric();

        // Some fields require more than one SIM record to set
        SystemProperties.set(PROPERTY_ICC_OPERATOR_NUMERIC, operator);

        if (mImsi != null) {
            SystemProperties.set(PROPERTY_ICC_OPERATOR_ISO_COUNTRY,
                    MccTable.countryCodeForMcc(Integer.parseInt(mImsi.substring(0,3))));
        }
        else {
            Log.e("SIM", "[SIMRecords] onAllRecordsLoaded: imsi is NULL!");
        }

        setVoiceMailByCountry(operator);
        setSpnFromConfig(operator);

        recordsLoadedRegistrants.notifyRegistrants(
            new AsyncResult(null, null, null));
    }

    //***** Private methods

    private void setSpnFromConfig(String carrier) {
        if (mSpnOverride.containsCarrier(carrier)) {
            spn = mSpnOverride.getSpn(carrier);
        }
    }


    private void setVoiceMailByCountry (String spn) {
        if (mVmConfig.containsCarrier(spn)) {
            isVoiceMailFixed = true;
            voiceMailNum = mVmConfig.getVoiceMailNumber(spn);
            voiceMailTag = mVmConfig.getVoiceMailTag(spn);
        }
    }

    private void onSimReady() {
        fetchSimRecords();
    }

    private void fetchSimRecords() {
        recordsRequested = true;
        //IccFileHandler iccFh = mFh;

        Log.v(LOG_TAG, "SIMRecords:fetchSimRecords " + recordsToLoad);

        mCi.getIMSI(mParentApp.getCard().getSlotId(), mParentApp.getAid(),obtainMessage(EVENT_GET_IMSI_DONE));
        recordsToLoad++;

        mFh.loadEFTransparent(IccConstants.EF_ICCID, obtainMessage(EVENT_GET_ICCID_DONE));
        recordsToLoad++;

        // FIXME should examine EF[MSISDN]'s capability configuration
        // to determine which is the voice/data/fax line
        new AdnRecordLoader(mFh).loadFromEF(IccConstants.EF_MSISDN, IccConstants.EF_EXT1, 1,
                    obtainMessage(EVENT_GET_MSISDN_DONE));
        recordsToLoad++;

        // Record number is subscriber profile
        mFh.loadEFLinearFixed(IccConstants.EF_MBI, 1, obtainMessage(EVENT_GET_MBI_DONE));
        recordsToLoad++;

        mFh.loadEFTransparent(IccConstants.EF_AD, obtainMessage(EVENT_GET_AD_DONE));
        recordsToLoad++;

        // Record number is subscriber profile
        mFh.loadEFLinearFixed(IccConstants.EF_MWIS, 1, obtainMessage(EVENT_GET_MWIS_DONE));
        recordsToLoad++;


        // Also load CPHS-style voice mail indicator, which stores
        // the same info as EF[MWIS]. If both exist, both are updated
        // but the EF[MWIS] data is preferred
        // Please note this must be loaded after EF[MWIS]
        mFh.loadEFTransparent(
                IccConstants.EF_VOICE_MAIL_INDICATOR_CPHS,
                obtainMessage(EVENT_GET_VOICE_MAIL_INDICATOR_CPHS_DONE));
        recordsToLoad++;

        // Same goes for Call Forward Status indicator: fetch both
        // EF[CFIS] and CPHS-EF, with EF[CFIS] preferred.
        mFh.loadEFLinearFixed(IccConstants.EF_CFIS, 1, obtainMessage(EVENT_GET_CFIS_DONE));
        recordsToLoad++;
        mFh.loadEFTransparent(IccConstants.EF_CFF_CPHS, obtainMessage(EVENT_GET_CFF_DONE));
        recordsToLoad++;


        getSpnFsm(true, null);

        mFh.loadEFTransparent(IccConstants.EF_SPDI, obtainMessage(EVENT_GET_SPDI_DONE));
        recordsToLoad++;

        mFh.loadEFLinearFixed(IccConstants.EF_PNN, 1, obtainMessage(EVENT_GET_PNN_DONE));
        recordsToLoad++;

        mFh.loadEFLinearFixedAll(IccConstants.EF_OPL, obtainMessage(EVENT_GET_ALL_OPL_RECORDS_DONE));
        recordsToLoad++;

        mFh.loadEFLinearFixedAll(IccConstants.EF_PNN, obtainMessage(EVENT_GET_ALL_PNN_RECORDS_DONE));
        recordsToLoad++;

        mFh.loadEFTransparent(IccConstants.EF_SPN_CPHS, obtainMessage(EVENT_GET_SPN_CPHS_DONE));
        recordsToLoad++;

        mFh.loadEFTransparent(IccConstants.EF_SPN_SHORT_CPHS, obtainMessage(EVENT_GET_SPN_SHORT_CPHS_DONE));
        recordsToLoad++;

        mFh.loadEFTransparent(IccConstants.EF_SST, obtainMessage(EVENT_GET_SST_DONE));
        recordsToLoad++;

        mFh.loadEFTransparent(IccConstants.EF_INFO_CPHS, obtainMessage(EVENT_GET_INFO_CPHS_DONE));
        recordsToLoad++;

        mFh.loadEFTransparent(IccConstants.EF_CSP_CPHS,obtainMessage(EVENT_GET_CSP_CPHS_DONE));
        recordsToLoad++;

        // XXX should seek instead of examining them all
        if (false) { // XXX
            mFh.loadEFLinearFixedAll(IccConstants.EF_SMS, obtainMessage(EVENT_GET_ALL_SMS_DONE));
            recordsToLoad++;
        }

        if (CRASH_RIL) {
            String sms = "0107912160130310f20404d0110041007030208054832b0120"
                         + "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                         + "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                         + "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                         + "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                         + "ffffffffffffffffffffffffffffff";
            byte[] ba = IccUtils.hexStringToBytes(sms);

            mFh.updateEFLinearFixed(IccConstants.EF_SMS, 1, ba, null,
                            obtainMessage(EVENT_MARK_SMS_READ_DONE, 1));
        }
        Log.d(LOG_TAG, "SIMRecords:fetchSimRecords " + recordsToLoad + " requested: " + recordsRequested);

    }

    /**
     * Returns the SpnDisplayRule based on settings on the SIM and the
     * specified plmn (currently-registered PLMN).  See TS 22.101 Annex A
     * and TS 51.011 10.3.11 for details.
     *
     * If the SPN is not found on the SIM, the rule is always PLMN_ONLY.
     */
    protected int getDisplayRule(String plmn) {
        int rule;
        if (spn == null || spnDisplayCondition == -1) {
            // EF_SPN was not found on the SIM, or not yet loaded.  Just show ONS.
            rule = SPN_RULE_SHOW_PLMN;
        } else if (isOnMatchingPlmn(plmn)) {
            rule = SPN_RULE_SHOW_SPN;
            if ((spnDisplayCondition & 0x01) == 0x01) {
                // ONS required when registered to HPLMN or PLMN in EF_SPDI
                rule |= SPN_RULE_SHOW_PLMN;
            }
        } else {
            rule = SPN_RULE_SHOW_PLMN;
            if ((spnDisplayCondition & 0x02) == 0x00) {
                // SPN required if not registered to HPLMN or PLMN in EF_SPDI
                rule |= SPN_RULE_SHOW_SPN;
            }
        }
        return rule;
    }

    /**
     * Checks if plmn is HPLMN or on the spdiNetworks list.
     */
    private boolean isOnMatchingPlmn(String plmn) {
        if (plmn == null) return false;

        if (plmn.equals(getSIMOperatorNumeric())) {
            return true;
        }

        if (spdiNetworks != null) {
            for (String spdiNet : spdiNetworks) {
                if (plmn.equals(spdiNet)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * States of Get SPN Finite State Machine which only used by getSpnFsm()
     */
    private enum Get_Spn_Fsm_State {
        IDLE,               // No initialized
        INIT,               // Start FSM
        READ_SPN_3GPP,      // Load EF_SPN firstly
        READ_SPN_CPHS,      // Load EF_SPN_CPHS secondly
        READ_SPN_SHORT_CPHS // Load EF_SPN_SHORT_CPHS last
    }

    /**
     * Finite State Machine to load Service Provider Name , which can be stored
     * in either EF_SPN (3GPP), EF_SPN_CPHS, or EF_SPN_SHORT_CPHS (CPHS4.2)
     *
     * After starting, FSM will search SPN EFs in order and stop after finding
     * the first valid SPN
     *
     * @param start set true only for initialize loading
     * @param ar the AsyncResult from loadEFTransparent
     *        ar.exception holds exception in error
     *        ar.result is byte[] for data in success
     */
    private void getSpnFsm(boolean start, AsyncResult ar) {
        byte[] data;

        if (start) {
            spnState = Get_Spn_Fsm_State.INIT;
        }

        switch(spnState){
            case INIT:
                spn = null;

                mFh.loadEFTransparent( IccConstants.EF_SPN,
                        obtainMessage(EVENT_GET_SPN_DONE));
                recordsToLoad++;

                spnState = Get_Spn_Fsm_State.READ_SPN_3GPP;
                break;
            case READ_SPN_3GPP:
                if (ar != null && ar.exception == null) {
                    data = (byte[]) ar.result;
                    spnDisplayCondition = 0xff & data[0];
                    spn = IccUtils.adnStringFieldToString(data, 1, data.length - 1);

                    if (DBG) log("Load EF_SPN: " + spn
                            + " spnDisplayCondition: " + spnDisplayCondition);
                    SystemProperties.set(PROPERTY_ICC_OPERATOR_ALPHA, spn);

                    spnState = Get_Spn_Fsm_State.IDLE;
                } else {
                    mFh.loadEFTransparent( IccConstants.EF_SPN_CPHS,
                            obtainMessage(EVENT_GET_SPN_DONE));
                    recordsToLoad++;

                    spnState = Get_Spn_Fsm_State.READ_SPN_CPHS;

                    // See TS 51.011 10.3.11.  Basically, default to
                    // show PLMN always, and SPN also if roaming.
                    spnDisplayCondition = -1;
                }
                break;
            case READ_SPN_CPHS:
                if (ar != null && ar.exception == null) {
                    data = (byte[]) ar.result;
                    spn = IccUtils.adnStringFieldToString(
                            data, 0, data.length - 1 );

                    if (DBG) log("Load EF_SPN_CPHS: " + spn);
                    SystemProperties.set(PROPERTY_ICC_OPERATOR_ALPHA, spn);

                    spnState = Get_Spn_Fsm_State.IDLE;
                } else {
                    mFh.loadEFTransparent(
                            IccConstants.EF_SPN_SHORT_CPHS, obtainMessage(EVENT_GET_SPN_DONE));
                    recordsToLoad++;

                    spnState = Get_Spn_Fsm_State.READ_SPN_SHORT_CPHS;
                }
                break;
            case READ_SPN_SHORT_CPHS:
                if (ar != null && ar.exception == null) {
                    data = (byte[]) ar.result;
                    spn = IccUtils.adnStringFieldToString(
                            data, 0, data.length - 1);

                    if (DBG) log("Load EF_SPN_SHORT_CPHS: " + spn);
                    SystemProperties.set(PROPERTY_ICC_OPERATOR_ALPHA, spn);
                }else {
                    if (DBG) log("No SPN loaded in either CHPS or 3GPP");
                }

                spnState = Get_Spn_Fsm_State.IDLE;
                break;
            default:
                spnState = Get_Spn_Fsm_State.IDLE;
        }
    }

    /**
     * Parse TS 51.011 EF[SPDI] record
     * This record contains the list of numeric network IDs that
     * are treated specially when determining SPN display
     */
    private void
    parseEfSpdi(byte[] data) {
        SimTlv tlv = new SimTlv(data, 0, data.length);

        byte[] plmnEntries = null;

        // There should only be one TAG_SPDI_PLMN_LIST
        for ( ; tlv.isValidObject() ; tlv.nextObject()) {
            if (tlv.getTag() == TAG_SPDI_PLMN_LIST) {
                plmnEntries = tlv.getData();
                break;
            }
        }

        if (plmnEntries == null) {
            return;
        }

        spdiNetworks = new ArrayList<String>(plmnEntries.length / 3);

        for (int i = 0 ; i + 2 < plmnEntries.length ; i += 3) {
            String plmnCode;
            plmnCode = IccUtils.bcdToString(plmnEntries, i, 3);

            // Valid operator codes are 5 or 6 digits
            if (plmnCode.length() >= 5) {
                log("EF_SPDI network: " + plmnCode);
                spdiNetworks.add(plmnCode);
            }
        }
    }

    /**
     * check to see if Mailbox Number is allocated and activated in CPHS SST
     */
    private boolean isCphsMailboxEnabled() {
        if (mCphsInfo == null)  return false;
        return ((mCphsInfo[1] & CPHS_SST_MBN_MASK) == CPHS_SST_MBN_ENABLED );
    }

    /**
     * Get the EONS name derived from EF_OPL/EF_PNN or EF_CPHS_ONS/EF_CPHS_ONS_SHORT
     * files for registered operator.
     * @return Enhanced Operator Name String (EONS) if it can be derived and
     * null otherwise.
     */
    public String getEons() {
        return mEons.getEons();
    }

    /**
     * When there is a change in LAC or Service State, update EONS
     * for registered plmn.
     * @param regOperator is the registered operator PLMN
     * @param lac is current lac
     * @return returns true if operator name display needs updation, false
     * otherwise
     */
    public boolean updateEons(String regOperator, int lac) {
        return mEons.updateEons(regOperator, lac, getSIMOperatorNumeric());
    }

    /**
     * Fetch EONS for Available Networks from EF_PNN data.
     * @param avlNetworks, ArrayList of Available Networks
     * @return ArrayList Available Networks with EONS if
     * success, otherwise null
     */
    public ArrayList<NetworkInfo> getEonsForAvailableNetworks(ArrayList<NetworkInfo> avlNetworks) {
        return mEons.getEonsForAvailableNetworks(avlNetworks);
    }

    protected void log(String s) {
        Log.d(LOG_TAG, "[SIMRecords] " + s);
    }

    /**
     * Return true if "Restriction of menu options for manual PLMN selection"
     * bit is set or EF_CSP data is unavailable, return false otherwise.
     */
    public boolean isCspPlmnEnabled() {
        return mCspPlmnEnabled;
    }

    /**
     * Parse EF_CSP data and check if
     * "Restriction of menu options for manual PLMN selection" is
     * Enabled/Disabled
     *
     * @param data EF_CSP hex data.
     */
    private void handleEfCspData(byte[] data) {
        // As per spec CPHS4_2.WW6, CPHS B.4.7.1, EF_CSP contains CPHS defined
        // 18 bytes (i.e 9 service groups info) and additional data specific to
        // operator. The valueAddedServicesGroup is not part of standard
        // services. This is operator specific and can be programmed any where.
        // Normally this is programmed as 10th service after the standard
        // services.
        int usedCspGroups = data.length / 2;
        // This is the "Servive Group Number" of "Value Added Services Group".
        byte valueAddedServicesGroup = (byte)0xC0;

        mCspPlmnEnabled = true;
        for (int i = 0; i < usedCspGroups; i++) {
             if (data[2 * i] == valueAddedServicesGroup) {
                 Log.i(LOG_TAG, "[CSP] found ValueAddedServicesGroup, value "
                       + data[(2 * i) + 1]);
                 if ((data[(2 * i) + 1] & 0x80) == 0x80) {
                     // Bit 8 is for
                     // "Restriction of menu options for manual PLMN selection".
                     // Operator Selection menu should be enabled.
                     mCspPlmnEnabled = true;
                 } else {
                     mCspPlmnEnabled = false;
                     // Operator Selection menu should be disabled.
                     // Operator Selection Mode should be set to Automatic.
                     Log.i(LOG_TAG,"[CSP] Set Automatic Network Selection");
                 }
                 return;
             }
        }

        Log.w(LOG_TAG, "[CSP] Value Added Service Group (0xC0), not found!");
    }

    public int getVoiceMessageCount() {
        boolean voiceMailWaiting = false;
        int countVoiceMessages = 0;

        if (efMWIS != null) {
            // Use this data if the EF[MWIS] exists and
            // has been loaded
            // Refer TS 51.011 Section 10.3.45 for the content description
            voiceMailWaiting = ((efMWIS[0] & 0x01) != 0);
            countVoiceMessages = efMWIS[1] & 0xff;

            if (voiceMailWaiting && countVoiceMessages == 0) {
                // Unknown count = -1
                countVoiceMessages = -1;
            }
            Log.d(LOG_TAG, " VoiceMessageCount from SIM MWIS = " + countVoiceMessages);
        } else if (efCPHS_MWI != null) {
            // use voice mail count from CPHS
            int indicator = (int) (efCPHS_MWI[0] & 0xf);

            // Refer CPHS4_2.WW6 B4.2.3
            if (indicator == 0xA) {
                // Unknown count = -1
                countVoiceMessages = -1;
            } else if (indicator == 0x5) {
                countVoiceMessages = 0;
            }
            Log.d(LOG_TAG, " VoiceMessageCount from SIM CPHS = " + countVoiceMessages);
        }
        return countVoiceMessages;
    }
}
