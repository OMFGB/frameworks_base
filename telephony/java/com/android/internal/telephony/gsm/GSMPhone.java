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

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.SQLException;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Telephony;
import android.telephony.CellLocation;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.text.TextUtils;
import android.util.Log;

import static com.android.internal.telephony.CommandsInterface.CF_ACTION_DISABLE;
import static com.android.internal.telephony.CommandsInterface.CF_ACTION_ENABLE;
import static com.android.internal.telephony.CommandsInterface.CF_ACTION_ERASURE;
import static com.android.internal.telephony.CommandsInterface.CF_ACTION_REGISTRATION;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_ALL;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_ALL_CONDITIONAL;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_NO_REPLY;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_NOT_REACHABLE;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_BUSY;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_UNCONDITIONAL;
import static com.android.internal.telephony.CommandsInterface.SERVICE_CLASS_VOICE;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_BASEBAND_VERSION;

import com.android.internal.telephony.cat.CatService;
import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallForwardInfo;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.IccFileHandler;
import com.android.internal.telephony.IccPhoneBookInterfaceManager;
import com.android.internal.telephony.IccSmsInterfaceManager;
import com.android.internal.telephony.MmiCode;
import com.android.internal.telephony.UiccCard;
import com.android.internal.telephony.UiccCardApplication;
import com.android.internal.telephony.UiccManager;
import com.android.internal.telephony.VoicePhone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneNotifier;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.PhoneSubInfo;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.UiccManager.AppFamily;
import com.android.internal.telephony.UUSInfo;
import com.android.internal.telephony.test.SimulatedRadioControl;
import com.android.internal.telephony.IccVmNotSupportedException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * {@hide}
 */
public class GSMPhone extends PhoneBase {
    // NOTE that LOG_TAG here is "GSM", which means that log messages
    // from this file will go into the radio log rather than the main
    // log.  (Use "adb logcat -b radio" to see them.)
    static final String LOG_TAG = "GSM";
    private static final boolean LOCAL_DEBUG = true;

    // Key used to read/write current ciphering state
    public static final String CIPHERING_KEY = "ciphering_key";
    // Key used to read/write voice mail number
    public static final String VM_NUMBER = "vm_number_key";
    // Key used to read/write the SIM IMSI used for storing the voice mail
    public static final String VM_SIM_IMSI = "vm_sim_imsi_key";

    // Instance Variables
    GsmCallTracker mCT;
    GsmServiceStateTracker mSST;
    GsmSMSDispatcher mSMS;

    ArrayList <GsmMmiCode> mPendingMMIs = new ArrayList<GsmMmiCode>();
    SimPhoneBookInterfaceManager mSimPhoneBookIntManager;
    SimSmsInterfaceManager mSimSmsIntManager;
    PhoneSubInfo mSubInfo;

    /* icc stuff */
    UiccManager mUiccManager = null;
    UiccCardApplication m3gppApplication = null;
    UiccCard mSimCard = null;
    SIMRecords mSIMRecords = null;

    Registrant mPostDialHandler;

    /** List of Registrants to receive Supplementary Service Notifications. */
    RegistrantList mSsnRegistrants = new RegistrantList();

    Thread debugPortThread;
    ServerSocket debugSocket;

    private String mImei;
    private String mImeiSv;
    private String mVmNumber;


    // Constructors

    public
    GSMPhone (Context context, CommandsInterface ci, PhoneNotifier notifier) {
        this(context,ci,notifier, false);
    }

    public
    GSMPhone (Context context, CommandsInterface ci, PhoneNotifier notifier, boolean unitTestMode) {
        super(notifier, context, ci, unitTestMode);

        if (ci instanceof SimulatedRadioControl) {
            mSimulatedRadioControl = (SimulatedRadioControl) ci;
        }

        mUiccManager = UiccManager.getInstance(context, mCM);
        mUiccManager.registerForIccChanged(this, EVENT_ICC_CHANGED, null);

        mCM.setPhoneType(VoicePhone.PHONE_TYPE_GSM);
        mCT = new GsmCallTracker(this);
        mSST = new GsmServiceStateTracker (this);
        mSMS = new GsmSMSDispatcher(this);

        if (!unitTestMode) {
            //TODO: fusion - SimPhoneBookInterfaceManager expects msim records at start.
            //mSimPhoneBookIntManager = new SimPhoneBookInterfaceManager(this);
            mSimSmsIntManager = new SimSmsInterfaceManager(this);
            mSubInfo = new PhoneSubInfo(this);
        }

        mCM.registerForAvailable(this, EVENT_RADIO_AVAILABLE, null);
        mCM.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        mCM.registerForOn(this, EVENT_RADIO_ON, null);
        mCM.setOnUSSD(this, EVENT_USSD, null);
        mCM.setOnSuppServiceNotification(this, EVENT_SSN, null);
        mSST.registerForNetworkAttach(this, EVENT_REGISTERED_TO_NETWORK, null);

        if (false) {
            try {
                //debugSocket = new LocalServerSocket("com.android.internal.telephony.debug");
                debugSocket = new ServerSocket();
                debugSocket.setReuseAddress(true);
                debugSocket.bind (new InetSocketAddress("127.0.0.1", 6666));

                debugPortThread
                    = new Thread(
                        new Runnable() {
                            public void run() {
                                for(;;) {
                                    try {
                                        Socket sock;
                                        sock = debugSocket.accept();
                                        Log.i(LOG_TAG, "New connection; resetting radio");
                                        mCM.resetRadio(null);
                                        sock.close();
                                    } catch (IOException ex) {
                                        Log.w(LOG_TAG,
                                            "Exception accepting socket", ex);
                                    }
                                }
                            }
                        },
                        "GSMPhone debug");

                debugPortThread.start();

            } catch (IOException ex) {
                Log.w(LOG_TAG, "Failure to open com.android.internal.telephony.debug socket", ex);
            }
        }

        //Change the system property
        SystemProperties.set(TelephonyProperties.CURRENT_ACTIVE_PHONE,
                new Integer(VoicePhone.PHONE_TYPE_GSM).toString());
    }

    public void dispose() {
        synchronized(PhoneProxy.lockForRadioTechnologyChange) {
            super.dispose();

            //Unregister from all former registered events
            mCM.unregisterForAvailable(this); //EVENT_RADIO_AVAILABLE
            mCM.unregisterForOffOrNotAvailable(this); //EVENT_RADIO_OFF_OR_NOT_AVAILABLE
            mCM.unregisterForOn(this); //EVENT_RADIO_ON
            mSST.unregisterForNetworkAttach(this); //EVENT_REGISTERED_TO_NETWORK
            mCM.unSetOnUSSD(this);
            mCM.unSetOnSuppServiceNotification(this);

            mPendingMMIs.clear();

            //Force all referenced classes to unregister their former registered events
            mCT.dispose();
            mSST.dispose();

            //TODO - fusion
            //mSimPhoneBookIntManager.dispose();
            mSimSmsIntManager.dispose();
            mSubInfo.dispose();

            //cleanup icc stuff
            mUiccManager.unregisterForIccChanged(this);
            if(mSIMRecords != null) {
                unregisterForSimRecordEvents();
            }
        }
    }

    public void removeReferences() {
            this.mSimulatedRadioControl = null;
            this.mSimPhoneBookIntManager = null;
            this.mSimSmsIntManager = null;
            this.mSMS = null;
            this.mSubInfo = null;
            this.mCT = null;
            this.mSST = null;

            this.m3gppApplication = null;
            this.mUiccManager = null;
            this.mSimCard = null;
            this.mSIMRecords = null;
    }

    protected void finalize() {
        if(LOCAL_DEBUG) Log.d(LOG_TAG, "GSMPhone finalized");
    }


    public ServiceState
    getVoiceServiceState() {
        return mSST.ss;
    }

    public CellLocation getCellLocation() {
        return mSST.cellLoc;
    }

    public VoicePhone.State getState() {
        return mCT.state;
    }

    public String getPhoneName() {
        return "GSM";
    }

    public int getPhoneType() {
        return VoicePhone.PHONE_TYPE_GSM;
    }

    public SignalStrength getSignalStrength() {
        return mSST.mSignalStrength;
    }

    public boolean getMessageWaitingIndicator() {
        if (mSIMRecords != null) {
            return mSIMRecords.getVoiceMessageWaiting();
        }
        return false;
    }

    public boolean getCallForwardingIndicator() {
        if (mSIMRecords != null) {
            return mSIMRecords.getVoiceCallForwardingFlag();
        }
        return false;
    }

    public List<? extends MmiCode>
    getPendingMmiCodes() {
        return mPendingMMIs;
    }

    /**
     * Notify any interested party of a Phone state change {@link Phone.State}
     */
    /*package*/ void notifyPhoneStateChanged() {
        mNotifier.notifyPhoneState(this);
    }

    /**
     * Notify registrants of a change in the call state. This notifies changes in {@link Call.State}
     * Use this when changes in the precise call state are needed, else use notifyPhoneStateChanged.
     */
    /*package*/ void notifyPreciseCallStateChanged() {
        /* we'd love it if this was package-scoped*/
        super.notifyPreciseCallStateChangedP();
    }

    /*package*/ void
    notifyNewRingingConnection(Connection c) {
        /* we'd love it if this was package-scoped*/
        super.notifyNewRingingConnectionP(c);
    }

    /*package*/ void
    notifyDisconnect(Connection cn) {
        mDisconnectRegistrants.notifyResult(cn);
    }

    void notifyUnknownConnection() {
        mUnknownConnectionRegistrants.notifyResult(this);
    }

    void notifySuppServiceFailed(SuppService code) {
        mSuppServiceFailedRegistrants.notifyResult(code);
    }

    /*package*/ void
    notifyServiceStateChanged(ServiceState ss) {
        super.notifyServiceStateChangedP(ss);
    }

    /*package*/
    void notifyLocationChanged() {
        mNotifier.notifyCellLocation(this);
    }

    /*package*/ void
    notifySignalStrength() {
        mNotifier.notifySignalStrength(this);
    }

    /*package*/ void
    updateMessageWaitingIndicator(boolean mwi) {
        // this also calls notifyMessageWaitingIndicator()
        if (mSIMRecords != null)
            mSIMRecords.setVoiceMessageWaiting(1, mwi ? -1 : 0);
    }

    public void
    notifyCallForwardingIndicator() {
        mNotifier.notifyCallForwardingChanged(this);
    }

    // override for allowing access from other classes of this package
    /**
     * {@inheritDoc}
     */
    public final void
    setSystemProperty(String property, String value) {
        super.setSystemProperty(property, value);
    }

    public void registerForSuppServiceNotification(
            Handler h, int what, Object obj) {
        mSsnRegistrants.addUnique(h, what, obj);
        if (mSsnRegistrants.size() == 1) mCM.setSuppServiceNotifications(true, null);
    }

    public void unregisterForSuppServiceNotification(Handler h) {
        mSsnRegistrants.remove(h);
        if (mSsnRegistrants.size() == 0) mCM.setSuppServiceNotifications(false, null);
    }

    public void
    acceptCall() throws CallStateException {
        mCT.acceptCall();
    }

    public void
    rejectCall() throws CallStateException {
        mCT.rejectCall();
    }

    public void
    switchHoldingAndActive() throws CallStateException {
        mCT.switchWaitingOrHoldingAndActive();
    }

    public boolean canConference() {
        return mCT.canConference();
    }

    public boolean canDial() {
        return mCT.canDial();
    }

    public void conference() throws CallStateException {
        mCT.conference();
    }

    public void clearDisconnected() {
        mCT.clearDisconnected();
    }

    public boolean canTransfer() {
        return mCT.canTransfer();
    }

    public void explicitCallTransfer() throws CallStateException {
        mCT.explicitCallTransfer();
    }

    public GsmCall
    getForegroundCall() {
        return mCT.foregroundCall;
    }

    public GsmCall
    getBackgroundCall() {
        return mCT.backgroundCall;
    }

    public GsmCall
    getRingingCall() {
        return mCT.ringingCall;
    }

    private boolean handleCallDeflectionIncallSupplementaryService(
            String dialString) throws CallStateException {
        if (dialString.length() > 1) {
            return false;
        }

        if (getRingingCall().getState() != GsmCall.State.IDLE) {
            if (LOCAL_DEBUG) Log.d(LOG_TAG, "MmiCode 0: rejectCall");
            try {
                mCT.rejectCall();
            } catch (CallStateException e) {
                if (LOCAL_DEBUG) Log.d(LOG_TAG,
                    "reject failed", e);
                notifySuppServiceFailed(VoicePhone.SuppService.REJECT);
            }
        } else if (getBackgroundCall().getState() != GsmCall.State.IDLE) {
            if (LOCAL_DEBUG) Log.d(LOG_TAG,
                    "MmiCode 0: hangupWaitingOrBackground");
            mCT.hangupWaitingOrBackground();
        }

        return true;
    }

    private boolean handleCallWaitingIncallSupplementaryService(
            String dialString) throws CallStateException {
        int len = dialString.length();

        if (len > 2) {
            return false;
        }

        GsmCall call = (GsmCall) getForegroundCall();

        try {
            if (len > 1) {
                char ch = dialString.charAt(1);
                int callIndex = ch - '0';

                if (callIndex >= 1 && callIndex <= GsmCallTracker.MAX_CONNECTIONS) {
                    if (LOCAL_DEBUG) Log.d(LOG_TAG,
                            "MmiCode 1: hangupConnectionByIndex " +
                            callIndex);
                    mCT.hangupConnectionByIndex(call, callIndex);
                }
            } else {
                if (call.getState() != GsmCall.State.IDLE) {
                    if (LOCAL_DEBUG) Log.d(LOG_TAG,
                            "MmiCode 1: hangup foreground");
                    //mCT.hangupForegroundResumeBackground();
                    mCT.hangup(call);
                } else {
                    if (LOCAL_DEBUG) Log.d(LOG_TAG,
                            "MmiCode 1: switchWaitingOrHoldingAndActive");
                    mCT.switchWaitingOrHoldingAndActive();
                }
            }
        } catch (CallStateException e) {
            if (LOCAL_DEBUG) Log.d(LOG_TAG,
                "hangup failed", e);
            notifySuppServiceFailed(VoicePhone.SuppService.HANGUP);
        }

        return true;
    }

    private boolean handleCallHoldIncallSupplementaryService(String dialString)
            throws CallStateException {
        int len = dialString.length();

        if (len > 2) {
            return false;
        }

        GsmCall call = (GsmCall) getForegroundCall();

        if (len > 1) {
            try {
                char ch = dialString.charAt(1);
                int callIndex = ch - '0';
                GsmConnection conn = mCT.getConnectionByIndex(call, callIndex);

                // gsm index starts at 1, up to 5 connections in a call,
                if (conn != null && callIndex >= 1 && callIndex <= GsmCallTracker.MAX_CONNECTIONS) {
                    if (LOCAL_DEBUG) Log.d(LOG_TAG, "MmiCode 2: separate call "+
                            callIndex);
                    mCT.separate(conn);
                } else {
                    if (LOCAL_DEBUG) Log.d(LOG_TAG, "separate: invalid call index "+
                            callIndex);
                    notifySuppServiceFailed(VoicePhone.SuppService.SEPARATE);
                }
            } catch (CallStateException e) {
                if (LOCAL_DEBUG) Log.d(LOG_TAG,
                    "separate failed", e);
                notifySuppServiceFailed(VoicePhone.SuppService.SEPARATE);
            }
        } else {
            try {
                if (getRingingCall().getState() != GsmCall.State.IDLE) {
                    if (LOCAL_DEBUG) Log.d(LOG_TAG,
                    "MmiCode 2: accept ringing call");
                    mCT.acceptCall();
                } else {
                    if (LOCAL_DEBUG) Log.d(LOG_TAG,
                    "MmiCode 2: switchWaitingOrHoldingAndActive");
                    mCT.switchWaitingOrHoldingAndActive();
                }
            } catch (CallStateException e) {
                if (LOCAL_DEBUG) Log.d(LOG_TAG,
                    "switch failed", e);
                notifySuppServiceFailed(VoicePhone.SuppService.SWITCH);
            }
        }

        return true;
    }

    private boolean handleMultipartyIncallSupplementaryService(
            String dialString) throws CallStateException {
        if (dialString.length() > 1) {
            return false;
        }

        if (LOCAL_DEBUG) Log.d(LOG_TAG, "MmiCode 3: merge calls");
        try {
            conference();
        } catch (CallStateException e) {
            if (LOCAL_DEBUG) Log.d(LOG_TAG,
                "conference failed", e);
            notifySuppServiceFailed(VoicePhone.SuppService.CONFERENCE);
        }
        return true;
    }

    private boolean handleEctIncallSupplementaryService(String dialString)
            throws CallStateException {

        int len = dialString.length();

        if (len != 1) {
            return false;
        }

        if (LOCAL_DEBUG) Log.d(LOG_TAG, "MmiCode 4: explicit call transfer");
        try {
            explicitCallTransfer();
        } catch (CallStateException e) {
            if (LOCAL_DEBUG) Log.d(LOG_TAG,
                "transfer failed", e);
            notifySuppServiceFailed(VoicePhone.SuppService.TRANSFER);
        }
        return true;
    }

    private boolean handleCcbsIncallSupplementaryService(String dialString)
            throws CallStateException {
        if (dialString.length() > 1) {
            return false;
        }

        Log.i(LOG_TAG, "MmiCode 5: CCBS not supported!");
        // Treat it as an "unknown" service.
        notifySuppServiceFailed(VoicePhone.SuppService.UNKNOWN);
        return true;
    }

    public boolean handleInCallMmiCommands(String dialString)
            throws CallStateException {
        if (!isInCall()) {
            return false;
        }

        if (TextUtils.isEmpty(dialString)) {
            return false;
        }

        boolean result = false;
        char ch = dialString.charAt(0);
        switch (ch) {
            case '0':
                result = handleCallDeflectionIncallSupplementaryService(
                        dialString);
                break;
            case '1':
                result = handleCallWaitingIncallSupplementaryService(
                        dialString);
                break;
            case '2':
                result = handleCallHoldIncallSupplementaryService(dialString);
                break;
            case '3':
                result = handleMultipartyIncallSupplementaryService(dialString);
                break;
            case '4':
                result = handleEctIncallSupplementaryService(dialString);
                break;
            case '5':
                result = handleCcbsIncallSupplementaryService(dialString);
                break;
            default:
                break;
        }

        return result;
    }

    boolean isInCall() {
        GsmCall.State foregroundCallState = getForegroundCall().getState();
        GsmCall.State backgroundCallState = getBackgroundCall().getState();
        GsmCall.State ringingCallState = getRingingCall().getState();

       return (foregroundCallState.isAlive() ||
                backgroundCallState.isAlive() ||
                ringingCallState.isAlive());
    }

    public Connection
    dial(String dialString) throws CallStateException {
        return dial(dialString, null);
    }

    public Connection
    dial (String dialString, UUSInfo uusInfo) throws CallStateException {
        // Need to make sure dialString gets parsed properly
        String newDialString = PhoneNumberUtils.stripSeparators(dialString);

        // handle in-call MMI first if applicable
        if (handleInCallMmiCommands(newDialString)) {
            return null;
        }

        // Only look at the Network portion for mmi
        String networkPortion = PhoneNumberUtils.extractNetworkPortionAlt(newDialString);
        GsmMmiCode mmi = GsmMmiCode.newFromDialString(networkPortion, this, m3gppApplication);
        if (LOCAL_DEBUG) Log.d(LOG_TAG,
                               "dialing w/ mmi '" + mmi + "'...");

        if (mmi == null) {
            return mCT.dial(newDialString, uusInfo);
        } else if (mmi.isTemporaryModeCLIR()) {
            return mCT.dial(mmi.dialingNumber, mmi.getCLIRMode(), uusInfo);
        } else {
            mPendingMMIs.add(mmi);
            mMmiRegistrants.notifyRegistrants(new AsyncResult(null, mmi, null));
            mmi.processCode();

            // FIXME should this return null or something else?
            return null;
        }
    }

    public boolean handlePinMmi(String dialString) {
        GsmMmiCode mmi = GsmMmiCode.newFromDialString(dialString, this, m3gppApplication);

        if (mmi != null && mmi.isPinCommand()) {
            mPendingMMIs.add(mmi);
            mMmiRegistrants.notifyRegistrants(new AsyncResult(null, mmi, null));
            mmi.processCode();
            return true;
        }

        return false;
    }

    public void sendUssdResponse(String ussdMessge) {
        GsmMmiCode mmi = GsmMmiCode.newFromUssdUserInput(ussdMessge, this, m3gppApplication);
        mPendingMMIs.add(mmi);
        mMmiRegistrants.notifyRegistrants(new AsyncResult(null, mmi, null));
        mmi.sendUssd(ussdMessge);
    }

    public void
    sendDtmf(char c) {
        if (!PhoneNumberUtils.is12Key(c)) {
            Log.e(LOG_TAG,
                    "sendDtmf called with invalid character '" + c + "'");
        } else {
            if (mCT.state ==  VoicePhone.State.OFFHOOK) {
                mCM.sendDtmf(c, null);
            }
        }
    }

    public void
    startDtmf(char c) {
        if (!PhoneNumberUtils.is12Key(c)) {
            Log.e(LOG_TAG,
                "startDtmf called with invalid character '" + c + "'");
        } else {
            mCM.startDtmf(c, null);
        }
    }

    public void
    stopDtmf() {
        mCM.stopDtmf(null);
    }

    public void
    sendBurstDtmf(String dtmfString) {
        Log.e(LOG_TAG, "[GSMPhone] sendBurstDtmf() is a CDMA method");
    }

    private void storeVoiceMailNumber(String number) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(VM_NUMBER, number);
        editor.apply();
        setVmSimImsi(getSubscriberId());
    }

    public String getVoiceMailNumber() {
        // Read from the SIM. If its null, try reading from the shared preference area.
        String number = null;
        if (mSIMRecords != null)
            number = mSIMRecords.getVoiceMailNumber();
        if (TextUtils.isEmpty(number)) {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
            number = sp.getString(VM_NUMBER, null);
        }
        return number;
    }

    private String getVmSimImsi() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        return sp.getString(VM_SIM_IMSI, null);
    }

    private void setVmSimImsi(String imsi) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(VM_SIM_IMSI, imsi);
        editor.apply();
    }

    public String getVoiceMailAlphaTag() {
        String ret = null;

        if (mSIMRecords != null)
            ret = mSIMRecords.getVoiceMailAlphaTag();

        if (ret == null || ret.length() == 0) {
            return mContext.getText(
                com.android.internal.R.string.defaultVoiceMailAlphaTag).toString();
        }

        return ret;
    }

    public String getDeviceId() {
        return mImei;
    }

    public String getDeviceSvn() {
        return mImeiSv;
    }

    public String getEsn() {
        Log.e(LOG_TAG, "[GSMPhone] getEsn() is a CDMA method");
        return "0";
    }

    public String getMeid() {
        Log.e(LOG_TAG, "[GSMPhone] getMeid() is a CDMA method");
        return "0";
    }

    public String getSubscriberId() {
        return mSIMRecords != null ? mSIMRecords.getIMSI() : null;
    }

    public String getIccSerialNumber() {
        return mSIMRecords != null ? mSIMRecords.iccid : null;
    }

    public String getLine1Number() {
        return mSIMRecords != null ? mSIMRecords.getMsisdnNumber() : null;
    }

    public String getLine1AlphaTag() {
        return mSIMRecords != null ? mSIMRecords.getMsisdnAlphaTag() : null;
    }

    public void setLine1Number(String alphaTag, String number, Message onComplete) {
        if (mSIMRecords != null) {
            mSIMRecords.setMsisdnNumber(alphaTag, number, onComplete);
        } else if (onComplete != null) {
            Exception e = new RuntimeException("Sim is absent.");
            AsyncResult.forMessage(onComplete).exception = e;
            onComplete.sendToTarget();
        }
    }

    public void setVoiceMailNumber(String alphaTag,
                            String voiceMailNumber,
                            Message onComplete) {

        if (mSIMRecords != null) {
            Message resp;
            mVmNumber = voiceMailNumber;
            resp = obtainMessage(EVENT_SET_VM_NUMBER_DONE, 0, 0, onComplete);
            mSIMRecords.setVoiceMailNumber(alphaTag, mVmNumber, resp);
            return;
        }

        if (onComplete != null) {
            Exception e = new RuntimeException("Sim is absent.");
            AsyncResult.forMessage(onComplete).exception = e;
            onComplete.sendToTarget();
        }
    }

    private boolean isValidCommandInterfaceCFReason (int commandInterfaceCFReason) {
        switch (commandInterfaceCFReason) {
        case CF_REASON_UNCONDITIONAL:
        case CF_REASON_BUSY:
        case CF_REASON_NO_REPLY:
        case CF_REASON_NOT_REACHABLE:
        case CF_REASON_ALL:
        case CF_REASON_ALL_CONDITIONAL:
            return true;
        default:
            return false;
        }
    }

    private boolean isValidCommandInterfaceCFAction (int commandInterfaceCFAction) {
        switch (commandInterfaceCFAction) {
        case CF_ACTION_DISABLE:
        case CF_ACTION_ENABLE:
        case CF_ACTION_REGISTRATION:
        case CF_ACTION_ERASURE:
            return true;
        default:
            return false;
        }
    }

    protected  boolean isCfEnable(int action) {
        return (action == CF_ACTION_ENABLE) || (action == CF_ACTION_REGISTRATION);
    }

    public void getCallForwardingOption(int commandInterfaceCFReason, Message onComplete) {
        if (isValidCommandInterfaceCFReason(commandInterfaceCFReason)) {
            if (LOCAL_DEBUG) Log.d(LOG_TAG, "requesting call forwarding query.");
            Message resp;
            if (commandInterfaceCFReason == CF_REASON_UNCONDITIONAL) {
                resp = obtainMessage(EVENT_GET_CALL_FORWARD_DONE, onComplete);
            } else {
                resp = onComplete;
            }
            mCM.queryCallForwardStatus(commandInterfaceCFReason,0,null,resp);
        }
    }

    public void setCallForwardingOption(int commandInterfaceCFAction,
            int commandInterfaceCFReason,
            String dialingNumber,
            int timerSeconds,
            Message onComplete) {
        if (    (isValidCommandInterfaceCFAction(commandInterfaceCFAction)) &&
                (isValidCommandInterfaceCFReason(commandInterfaceCFReason))) {

            Message resp;
            if (commandInterfaceCFReason == CF_REASON_UNCONDITIONAL) {
                resp = obtainMessage(EVENT_SET_CALL_FORWARD_DONE,
                        isCfEnable(commandInterfaceCFAction) ? 1 : 0, 0, onComplete);
            } else {
                resp = onComplete;
            }
            mCM.setCallForward(commandInterfaceCFAction,
                    commandInterfaceCFReason,
                    CommandsInterface.SERVICE_CLASS_VOICE,
                    dialingNumber,
                    timerSeconds,
                    resp);
        }
    }

    public void getOutgoingCallerIdDisplay(Message onComplete) {
        mCM.getCLIR(onComplete);
    }

    public void setOutgoingCallerIdDisplay(int commandInterfaceCLIRMode,
                                           Message onComplete) {
        mCM.setCLIR(commandInterfaceCLIRMode,
                obtainMessage(EVENT_SET_CLIR_COMPLETE, commandInterfaceCLIRMode, 0, onComplete));
    }

    public void getCallWaiting(Message onComplete) {
        mCM.queryCallWaiting(CommandsInterface.SERVICE_CLASS_VOICE, onComplete);
    }

    public void setCallWaiting(boolean enable, Message onComplete) {
        mCM.setCallWaiting(enable, CommandsInterface.SERVICE_CLASS_VOICE, onComplete);
    }

    public boolean
    getIccRecordsLoaded() {
        if (mSIMRecords != null) {
            return mSIMRecords.getRecordsLoaded();
        }
        return false;
    }


    public UiccCard getUiccCard() {
        return mSimCard;
    }

    public IccCard getIccCard() {
        throw new RuntimeException("getIccCard function in phone object should never be called. Use PhoneProxy instead.");
    }

    public void
    getAvailableNetworks(Message response) {
        Message msg;
        msg = obtainMessage(EVENT_GET_NETWORKS_DONE,response);
        mCM.getAvailableNetworks(msg);
    }

    /**
     * Small container class used to hold information relevant to
     * the carrier selection process. operatorNumeric can be ""
     * if we are looking for automatic selection. operatorAlphaLong is the
     * corresponding operator name.
     */
    private static class NetworkSelectMessage {
        public Message message;
        public String operatorNumeric;
        public String operatorAlphaLong;
    }

    public void
    setNetworkSelectionModeAutomatic(Message response) {
        // wrap the response message in our own message along with
        // an empty string (to indicate automatic selection) for the
        // operator's id.
        NetworkSelectMessage nsm = new NetworkSelectMessage();
        nsm.message = response;
        nsm.operatorNumeric = "";
        nsm.operatorAlphaLong = "";

        // get the message
        Message msg = obtainMessage(EVENT_SET_NETWORK_AUTOMATIC_COMPLETE, nsm);
        if (LOCAL_DEBUG)
            Log.d(LOG_TAG, "wrapping and sending message to connect automatically");

        mCM.setNetworkSelectionModeAutomatic(msg);
    }

    public void
    selectNetworkManually(com.android.internal.telephony.gsm.NetworkInfo network,
            Message response) {
        // wrap the response message in our own message along with
        // the operator's id.
        NetworkSelectMessage nsm = new NetworkSelectMessage();
        nsm.message = response;
        nsm.operatorNumeric = network.operatorNumeric;
        nsm.operatorAlphaLong = network.operatorAlphaLong;

        // get the message
        Message msg = obtainMessage(EVENT_SET_NETWORK_MANUAL_COMPLETE, nsm);

        mCM.setNetworkSelectionModeManual(network.operatorNumeric, msg);
    }

    public void
    getNeighboringCids(Message response) {
        mCM.getNeighboringCids(response);
    }

    public void setOnPostDialCharacter(Handler h, int what, Object obj) {
        mPostDialHandler = new Registrant(h, what, obj);
    }

    public void setMute(boolean muted) {
        mCT.setMute(muted);
    }

    public boolean getMute() {
        return mCT.getMute();
    }

    public void updateServiceLocation() {
        mSST.enableSingleLocationUpdate();
    }

    public void enableLocationUpdates() {
        mSST.enableLocationUpdates();
    }

    public void disableLocationUpdates() {
        mSST.disableLocationUpdates();
    }

    /**
     * Removes the given MMI from the pending list and notifies
     * registrants that it is complete.
     * @param mmi MMI that is done
     */
    /*package*/ void
    onMMIDone(GsmMmiCode mmi) {
        /* Only notify complete if it's on the pending list.
         * Otherwise, it's already been handled (eg, previously canceled).
         * The exception is cancellation of an incoming USSD-REQUEST, which is
         * not on the list.
         */
        if (mPendingMMIs.remove(mmi) || mmi.isUssdRequest()) {
            mMmiCompleteRegistrants.notifyRegistrants(
                new AsyncResult(null, mmi, null));
        }
    }


    private void
    onNetworkInitiatedUssd(GsmMmiCode mmi) {
        mMmiCompleteRegistrants.notifyRegistrants(
            new AsyncResult(null, mmi, null));
    }


    /** ussdMode is one of CommandsInterface.USSD_MODE_* */
    private void
    onIncomingUSSD (int ussdMode, String ussdMessage) {
        boolean isUssdError;
        boolean isUssdRequest;

        isUssdRequest
            = (ussdMode == CommandsInterface.USSD_MODE_REQUEST);

        isUssdError
            = (ussdMode != CommandsInterface.USSD_MODE_NOTIFY
                && ussdMode != CommandsInterface.USSD_MODE_REQUEST);

        // See comments in GsmMmiCode.java
        // USSD requests aren't finished until one
        // of these two events happen
        GsmMmiCode found = null;
        for (int i = 0, s = mPendingMMIs.size() ; i < s; i++) {
            if(mPendingMMIs.get(i).isPendingUSSD()) {
                found = mPendingMMIs.get(i);
                break;
            }
        }

        if (found != null) {
            // Complete pending USSD

            if (isUssdError) {
                found.onUssdFinishedError();
            } else {
                found.onUssdFinished(ussdMessage, isUssdRequest);
            }
        } else { // pending USSD not found
            // The network may initiate its own USSD request

            // ignore everything that isnt a Notify or a Request
            // also, discard if there is no message to present
            if (!isUssdError && ussdMessage != null) {
                GsmMmiCode mmi;
                mmi = GsmMmiCode.newNetworkInitiatedUssd(ussdMessage,
                                                   isUssdRequest,
                                                   GSMPhone.this,
                                                   m3gppApplication);
                onNetworkInitiatedUssd(mmi);
            }
        }
    }

    /**
     * Make sure the network knows our preferred setting.
     */
    protected  void syncClirSetting() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        int clirSetting = sp.getInt(CLIR_KEY, -1);
        if (clirSetting >= 0) {
            mCM.setCLIR(clirSetting, null);
        }
    }

    @Override
    public void handleMessage (Message msg) {
        AsyncResult ar;
        Message onComplete;

        if (!mIsTheCurrentActivePhone) {
            Log.e(LOG_TAG, "Received message " + msg +
                    "[" + msg.what + "] while being destroyed. Ignoring.");
            return;
        }
        switch (msg.what) {
            case EVENT_RADIO_AVAILABLE: {
                mCM.getBasebandVersion(
                        obtainMessage(EVENT_GET_BASEBAND_VERSION_DONE));

                mCM.getIMEI(obtainMessage(EVENT_GET_IMEI_DONE));
                mCM.getIMEISV(obtainMessage(EVENT_GET_IMEISV_DONE));
            }
            break;

            case EVENT_RADIO_ON:
            break;

            case EVENT_REGISTERED_TO_NETWORK:
                syncClirSetting();
                break;

            case EVENT_SIM_RECORDS_LOADED:
                updateCurrentCarrierInProvider();

                // Check if this is a different SIM than the previous one. If so unset the
                // voice mail number.
                String imsi = getVmSimImsi();
                if (imsi != null && !getSubscriberId().equals(imsi)) {
                    storeVoiceMailNumber(null);
                    setVmSimImsi(null);
                }

            break;

            case EVENT_GET_BASEBAND_VERSION_DONE:
                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {
                    break;
                }

                if (LOCAL_DEBUG) Log.d(LOG_TAG, "Baseband version: " + ar.result);
                setSystemProperty(PROPERTY_BASEBAND_VERSION, (String)ar.result);
            break;

            case EVENT_GET_IMEI_DONE:
                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {
                    break;
                }

                mImei = (String)ar.result;
            break;

            case EVENT_GET_IMEISV_DONE:
                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {
                    break;
                }

                mImeiSv = (String)ar.result;
            break;

            case EVENT_ICC_CHANGED:
                updateIccAvailability();
                break;

            case EVENT_USSD:
                ar = (AsyncResult)msg.obj;

                String[] ussdResult = (String[]) ar.result;

                if (ussdResult.length > 1) {
                    try {
                        onIncomingUSSD(Integer.parseInt(ussdResult[0]), ussdResult[1]);
                    } catch (NumberFormatException e) {
                        Log.w(LOG_TAG, "error parsing USSD");
                    }
                }
            break;

            case EVENT_RADIO_OFF_OR_NOT_AVAILABLE:
                // Some MMI requests (eg USSD) are not completed
                // within the course of a CommandsInterface request
                // If the radio shuts off or resets while one of these
                // is pending, we need to clean up.

                for (int i = 0, s = mPendingMMIs.size() ; i < s; i++) {
                    if (mPendingMMIs.get(i).isPendingUSSD()) {
                        mPendingMMIs.get(i).onUssdFinishedError();
                    }
                }
            break;

            case EVENT_SSN:
                ar = (AsyncResult)msg.obj;
                SuppServiceNotification not = (SuppServiceNotification) ar.result;
                mSsnRegistrants.notifyRegistrants(ar);
            break;

            case EVENT_SET_CALL_FORWARD_DONE:
                ar = (AsyncResult)msg.obj;
                if (ar.exception == null && mSIMRecords != null) {
                    mSIMRecords.setVoiceCallForwardingFlag(1, msg.arg1 == 1);
                }
                if (mSIMRecords == null) {
                    if (ar.exception == null) {
                        Log.w(LOG_TAG, "setVoiceCallForwardingFlag() aborted. icc absent?");
                        ar.exception = new RuntimeException("Sim card is absent.");
                    }
                }
                onComplete = (Message) ar.userObj;
                if (onComplete != null) {
                    AsyncResult.forMessage(onComplete, ar.result, ar.exception);
                    onComplete.sendToTarget();
                }
                break;

            case EVENT_SET_VM_NUMBER_DONE:
                ar = (AsyncResult)msg.obj;
                if (IccVmNotSupportedException.class.isInstance(ar.exception)) {
                    storeVoiceMailNumber(mVmNumber);
                    ar.exception = null;
                }
                onComplete = (Message) ar.userObj;
                if (onComplete != null) {
                    AsyncResult.forMessage(onComplete, ar.result, ar.exception);
                    onComplete.sendToTarget();
                }
                break;


            case EVENT_GET_CALL_FORWARD_DONE:
                ar = (AsyncResult)msg.obj;
                if (ar.exception == null) {
                    handleCfuQueryResult((CallForwardInfo[])ar.result);
                }
                onComplete = (Message) ar.userObj;
                if (onComplete != null) {
                    AsyncResult.forMessage(onComplete, ar.result, ar.exception);
                    onComplete.sendToTarget();
                }
                break;

            case EVENT_NEW_ICC_SMS:
                ar = (AsyncResult)msg.obj;
                mSMS.dispatchMessage((SmsMessage)ar.result);
                break;

            case EVENT_SET_NETWORK_AUTOMATIC:
                ar = (AsyncResult)msg.obj;
                setNetworkSelectionModeAutomatic((Message)ar.result);
                break;

            case EVENT_ICC_RECORD_EVENTS:
                ar = (AsyncResult)msg.obj;
                processIccRecordEvents((Integer)ar.result);
                break;

            // handle the select network completion callbacks.
            case EVENT_SET_NETWORK_MANUAL_COMPLETE:
            case EVENT_SET_NETWORK_AUTOMATIC_COMPLETE:
                handleSetSelectNetwork((AsyncResult) msg.obj);
                break;

            case EVENT_SET_CLIR_COMPLETE:
                ar = (AsyncResult)msg.obj;
                if (ar.exception == null) {
                    saveClirSetting(msg.arg1);
                }
                onComplete = (Message) ar.userObj;
                if (onComplete != null) {
                    AsyncResult.forMessage(onComplete, ar.result, ar.exception);
                    onComplete.sendToTarget();
                }
                break;

            case EVENT_GET_NETWORKS_DONE:
                ArrayList<NetworkInfo> eonsNetworkNames = null;

                ar = (AsyncResult)msg.obj;
                if (ar.exception == null && mSIMRecords != null) {
                    eonsNetworkNames =
                       mSIMRecords.getEonsForAvailableNetworks((ArrayList<NetworkInfo>)ar.result);
                }
                if (mSIMRecords == null) {
                    Log.w(LOG_TAG, "getEonsAvailableNetworks() aborted. icc absent?");
                }
                if (eonsNetworkNames != null) {
                    Log.i(LOG_TAG, "[EONS] Populated EONS for available networks.");
                } else {
                    eonsNetworkNames = (ArrayList<NetworkInfo>)ar.result;
                }

                onComplete = (Message) ar.userObj;
                if (onComplete != null) {
                    AsyncResult.forMessage(onComplete, eonsNetworkNames, ar.exception);
                    onComplete.sendToTarget();
                } else {
                    Log.e(LOG_TAG, "[EONS] In EVENT_GET_NETWORKS_DONE, onComplete is null!");
                }
                break;

             default:
                 super.handleMessage(msg);
        }
    }

    private void processIccRecordEvents(int eventCode) {
        switch (eventCode) {
            case SIMRecords.EVENT_CFI:
                notifyCallForwardingIndicator();
                break;
            case SIMRecords.EVENT_MWI:
                notifyMessageWaitingIndicator();
                break;
            case SIMRecords.EVENT_SPN:
                mSST.updateSpnDisplay();
                break;
            case SIMRecords.EVENT_EONS:
                mSST.updateEons();
                break;
        }
    }

    void updateIccAvailability() {
        if (mUiccManager == null ) {
            return;
        }

        UiccCardApplication new3gppApplication = mUiccManager
                .getCurrentApplication(AppFamily.APP_FAM_3GPP);

        if (m3gppApplication != new3gppApplication) {
            if (m3gppApplication != null) {
                Log.d(LOG_TAG, "Removing stale 3gpp Application.");
                if (mSIMRecords != null) {
                    unregisterForSimRecordEvents();
                    mSIMRecords = null;
                    //TODO: fusion - mSimPhoneBookIntManager.setSIMRecords(mSIMRecords);
                }
                m3gppApplication = null;
                mSimCard = null;
            }
            if (new3gppApplication != null) {
                Log.d(LOG_TAG, "New 3gpp application found");
                m3gppApplication = new3gppApplication;
                mSimCard = new3gppApplication.getCard();
                mSIMRecords = (SIMRecords) m3gppApplication.getApplicationRecords();
                registerForSimRecordEvents();
                //TODO: fusion - mSimPhoneBookIntManager.setSIMRecords(mSIMRecords);
            }
        }
    }

    /**
     * Sets the "current" field in the telephony provider according to the SIM's operator
     *
     * @return true for success; false otherwise.
     */
    boolean updateCurrentCarrierInProvider() {
        if (mSIMRecords != null) {
            try {
                Uri uri = Uri.withAppendedPath(Telephony.Carriers.CONTENT_URI, "current");
                ContentValues map = new ContentValues();
                map.put(Telephony.Carriers.NUMERIC, mSIMRecords.getSIMOperatorNumeric());
                mContext.getContentResolver().insert(uri, map);
                return true;
            } catch (SQLException e) {
                Log.e(LOG_TAG, "Can't store current operator", e);
            }
        }
        return false;
    }

    private void processIccEonsRecordsUpdated(int eventCode) {
        switch (eventCode) {
            case SIMRecords.EVENT_SPN:
                mSST.updateSpnDisplay();
                break;
            case SIMRecords.EVENT_EONS:
                mSST.updateEons();
                break;
        }
    }

    /**
     * Used to track the settings upon completion of the network change.
     */
    private void handleSetSelectNetwork(AsyncResult ar) {
        // look for our wrapper within the asyncresult, skip the rest if it
        // is null.
        if (!(ar.userObj instanceof NetworkSelectMessage)) {
            if (LOCAL_DEBUG) Log.d(LOG_TAG, "unexpected result from user object.");
            return;
        }

        NetworkSelectMessage nsm = (NetworkSelectMessage) ar.userObj;

        // found the object, now we send off the message we had originally
        // attached to the request.
        if (nsm.message != null) {
            if (LOCAL_DEBUG) Log.d(LOG_TAG, "sending original message to recipient");
            AsyncResult.forMessage(nsm.message, ar.result, ar.exception);
            nsm.message.sendToTarget();
        }

        // open the shared preferences editor, and write the value.
        // nsm.operatorNumeric is "" if we're in automatic.selection.
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(NETWORK_SELECTION_KEY, nsm.operatorNumeric);
        editor.putString(NETWORK_SELECTION_NAME_KEY, nsm.operatorAlphaLong);

        // commit and log the result.
        if (! editor.commit()) {
            Log.e(LOG_TAG, "failed to commit network selection preference");
        }

    }

    /**
     * Saves CLIR setting so that we can re-apply it as necessary
     * (in case the RIL resets it across reboots).
     */
    public void saveClirSetting(int commandInterfaceCLIRMode) {
        // open the shared preferences editor, and write the value.
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        SharedPreferences.Editor editor = sp.edit();
        editor.putInt(CLIR_KEY, commandInterfaceCLIRMode);

        // commit and log the result.
        if (! editor.commit()) {
            Log.e(LOG_TAG, "failed to commit CLIR preference");
        }
    }

    private void handleCfuQueryResult(CallForwardInfo[] infos) {

        if (mSIMRecords == null) {
            Log.w(LOG_TAG, "handleCfuQueryResult() called when mSIMRecords is null.");
            return; // will eventually fail anyway.
        }

        if (infos == null || infos.length == 0) {
            // Assume the default is not active
            // Set unconditional CFF in SIM to false
            mSIMRecords.setVoiceCallForwardingFlag(1, false);
        } else {
            for (int i = 0, s = infos.length; i < s; i++) {
                if ((infos[i].serviceClass & SERVICE_CLASS_VOICE) != 0) {
                    mSIMRecords.setVoiceCallForwardingFlag(1, (infos[i].status == 1));
                    // should only have the one
                    break;
                }
            }
        }
    }

    /**
     * Retrieves the PhoneSubInfo of the GSMPhone
     */
    public PhoneSubInfo getPhoneSubInfo(){
        return mSubInfo;
    }

    /**
     * Retrieves the IccSmsInterfaceManager of the GSMPhone
     */
    public IccSmsInterfaceManager getIccSmsInterfaceManager(){
        return mSimSmsIntManager;
    }

    /**
     * Retrieves the IccPhoneBookInterfaceManager of the GSMPhone
     */
    public IccPhoneBookInterfaceManager getIccPhoneBookInterfaceManager(){
        return mSimPhoneBookIntManager;
    }

    /**
     * {@inheritDoc}
     */
    public IccFileHandler getIccFileHandler() {
        if (m3gppApplication != null) {
            return m3gppApplication.getIccFileHandler();
        }
        return null;
    }

    public void activateCellBroadcastSms(int activate, Message response) {
        Log.e(LOG_TAG, "Error! This functionality is not implemented for GSM.");
    }

    public void getCellBroadcastSmsConfig(Message response) {
        Log.e(LOG_TAG, "Error! This functionality is not implemented for GSM.");
    }

    public void setCellBroadcastSmsConfig(int[] configValuesArray, Message response){
        Log.e(LOG_TAG, "Error! This functionality is not implemented for GSM.");
    }

    public boolean isCspPlmnEnabled() {
        return mSIMRecords.isCspPlmnEnabled();
    }

    private void registerForSimRecordEvents() {
        mSIMRecords.registerForNetworkSelectionModeAutomatic(this, EVENT_SET_NETWORK_AUTOMATIC, null);
        mSIMRecords.registerForNewSms(this, EVENT_NEW_ICC_SMS, null);
        mSIMRecords.registerForRecordsEvents(this, EVENT_ICC_RECORD_EVENTS, null);
        mSIMRecords.registerForRecordsLoaded(this, EVENT_SIM_RECORDS_LOADED, null);
    }

    private void unregisterForSimRecordEvents() {
        mSIMRecords.unregisterForNetworkSelectionModeAutomatic(this);
        mSIMRecords.unregisterForNewSms(this);
        mSIMRecords.unregisterForRecordsEvents(this);
        mSIMRecords.unregisterForRecordsLoaded(this);
    }
}
