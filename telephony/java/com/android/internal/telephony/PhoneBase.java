/*
 * Copyright (C) 2007 The Android Open Source Project
 * Copyright (c) 2009-2010, Code Aurora Forum. All rights reserved.
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

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.telephony.CellLocation;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.telephony.DataPhone.DataActivityState;
import com.android.internal.telephony.DataPhone.DataState;
import com.android.internal.telephony.DataPhone.IPVersion;
import com.android.internal.telephony.VoicePhone.State;
import com.android.internal.telephony.gsm.NetworkInfo;
import com.android.internal.telephony.test.SimulatedRadioControl;

import java.util.List;
import java.util.Locale;


/**
 * (<em>Not for SDK use</em>)
 * A base implementation for the com.android.internal.telephony.Phone interface.
 *
 * Note that implementations of Phone.java are expected to be used
 * from a single application thread. This should be the same thread that
 * originally called PhoneFactory to obtain the interface.
 *
 *  {@hide}
 *
 */

public abstract class PhoneBase extends Handler implements VoicePhone {
    private static final String LOG_TAG = "PHONE";
    private static final boolean LOCAL_DEBUG = true;

    // Key used to read and write the saved network selection numeric value
    public static final String NETWORK_SELECTION_KEY = "network_selection_key";
    // Key used to read and write the saved network selection operator name
    public static final String NETWORK_SELECTION_NAME_KEY = "network_selection_name_key";

    /* Event Constants */
    protected static final int EVENT_RADIO_AVAILABLE             = 1;
    /** Supplementary Service Notification received. */
    protected static final int EVENT_SSN                         = 2;
    protected static final int EVENT_SIM_RECORDS_LOADED          = 3;
    protected static final int EVENT_MMI_DONE                    = 4;
    protected static final int EVENT_RADIO_ON                    = 5;
    protected static final int EVENT_GET_BASEBAND_VERSION_DONE   = 6;
    protected static final int EVENT_USSD                        = 7;
    protected static final int EVENT_RADIO_OFF_OR_NOT_AVAILABLE  = 8;
    protected static final int EVENT_GET_IMEI_DONE               = 9;
    protected static final int EVENT_GET_IMEISV_DONE             = 10;
    protected static final int EVENT_GET_SIM_STATUS_DONE         = 11;
    protected static final int EVENT_SET_CALL_FORWARD_DONE       = 12;
    protected static final int EVENT_GET_CALL_FORWARD_DONE       = 13;
    protected static final int EVENT_CALL_RING                   = 14;
    protected static final int EVENT_CALL_RING_CONTINUE          = 15;

    // Used to intercept the carrier selection calls so that
    // we can save the values.
    protected static final int EVENT_SET_NETWORK_MANUAL_COMPLETE    = 16;
    protected static final int EVENT_SET_NETWORK_AUTOMATIC_COMPLETE = 17;
    protected static final int EVENT_SET_CLIR_COMPLETE              = 18;
    protected static final int EVENT_REGISTERED_TO_NETWORK          = 19;
    protected static final int EVENT_SET_VM_NUMBER_DONE             = 20;
    protected static final int EVENT_GET_NETWORKS_DONE              = 28;
    // Events for CDMA support
    protected static final int EVENT_GET_DEVICE_IDENTITY_DONE       = 21;
    protected static final int EVENT_RUIM_RECORDS_LOADED            = 22;
    protected static final int EVENT_NV_READY                       = 23;
    protected static final int EVENT_SET_ENHANCED_VP                = 24;
    protected static final int EVENT_EMERGENCY_CALLBACK_MODE_ENTER  = 25;
    protected static final int EVENT_EXIT_EMERGENCY_CALLBACK_RESPONSE = 26;
    protected static final int EVENT_GET_CDMA_SUBSCRIPTION_SOURCE    = 27;
    protected static final int EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED= 28;
    //other
    protected static final int EVENT_ICC_CHANGED                    = 29;
    protected static final int EVENT_SET_NETWORK_AUTOMATIC          = 30;
    protected static final int EVENT_ICC_RECORD_EVENTS              = 31;

    // Key used to read/write current CLIR setting
    public static final String CLIR_KEY = "clir_key";

    /* Instance Variables */
    public CommandsInterface mCM;

    boolean mDoesRilSendMultipleCallRing;
    int mCallRingContinueToken = 0;
    int mCallRingDelay;
    public boolean mIsTheCurrentActivePhone = true;
    private boolean mModemPowerSaveStatus = false;

    /**
     * Set a system property, unless we're in unit test mode
     */
    public void
    setSystemProperty(String property, String value) {
        if(getUnitTestMode()) {
            return;
        }
        SystemProperties.set(property, value);
    }


    protected final RegistrantList mPreciseCallStateRegistrants
            = new RegistrantList();

    protected final RegistrantList mNewRingingConnectionRegistrants
            = new RegistrantList();

    protected final RegistrantList mIncomingRingRegistrants
            = new RegistrantList();

    protected final RegistrantList mDisconnectRegistrants
            = new RegistrantList();

    protected final RegistrantList mVoiceServiceStateRegistrants
            = new RegistrantList();

    protected final RegistrantList mMmiCompleteRegistrants
            = new RegistrantList();

    protected final RegistrantList mMmiRegistrants
            = new RegistrantList();

    protected final RegistrantList mUnknownConnectionRegistrants
            = new RegistrantList();

    protected final RegistrantList mSuppServiceFailedRegistrants
            = new RegistrantList();

    protected Looper mLooper; /* to insure registrants are in correct thread*/

    protected Context mContext;

    /**
     * PhoneNotifier is an abstraction for all system-wide
     * state change notification. DefaultPhoneNotifier is
     * used here unless running we're inside a unit test.
     */
    protected PhoneNotifier mNotifier;

    protected SimulatedRadioControl mSimulatedRadioControl;

    boolean mUnitTestMode;

    /**
     * Constructs a PhoneBase in normal (non-unit test) mode.
     *
     * @param context Context object from hosting application
     * @param notifier An instance of DefaultPhoneNotifier,
     * unless unit testing.
     */
    protected PhoneBase(PhoneNotifier notifier, Context context, CommandsInterface ci) {
        this(notifier, context, ci, false);
    }

    /**
     * Constructs a PhoneBase in normal (non-unit test) mode.
     *
     * @param context Context object from hosting application
     * @param notifier An instance of DefaultPhoneNotifier,
     * unless unit testing.
     * @param unitTestMode when true, prevents notifications
     * of state change events
     */
    protected PhoneBase(PhoneNotifier notifier, Context context, CommandsInterface ci,
            boolean unitTestMode) {
        this.mNotifier = notifier;
        this.mContext = context;
        mLooper = Looper.myLooper();
        mCM = ci;

        setPropertiesByCarrier();

        setUnitTestMode(unitTestMode);

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        mCM.setOnCallRing(this, EVENT_CALL_RING, null);

        /**
         *  Some RIL's don't always send RIL_UNSOL_CALL_RING so it needs
         *  to be generated locally. Ideally all ring tones should be loops
         * and this wouldn't be necessary. But to minimize changes to upper
         * layers it is requested that it be generated by lower layers.
         *
         * By default old phones won't have the property set but do generate
         * the RIL_UNSOL_CALL_RING so the default if there is no property is
         * true.
         */
        mDoesRilSendMultipleCallRing = SystemProperties.getBoolean(
                TelephonyProperties.PROPERTY_RIL_SENDS_MULTIPLE_CALL_RING, true);
        Log.d(LOG_TAG, "mDoesRilSendMultipleCallRing=" + mDoesRilSendMultipleCallRing);

        mCallRingDelay = SystemProperties.getInt(
                TelephonyProperties.PROPERTY_CALL_RING_DELAY, 3000);
        Log.d(LOG_TAG, "mCallRingDelay=" + mCallRingDelay);
    }

    public void dispose() {
        synchronized(PhoneProxy.lockForRadioTechnologyChange) {
            mCM.unSetOnCallRing(this);
            mIsTheCurrentActivePhone = false;
        }
    }

    /**
     * When overridden the derived class needs to call
     * super.handleMessage(msg) so this method has a
     * a chance to process the message.
     *
     * @param msg
     */
    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;

        if (!mIsTheCurrentActivePhone) {
            Log.e(LOG_TAG, "Received message " + msg +
                    "[" + msg.what + "] while being destroyed. Ignoring.");
            return;
        }
        switch(msg.what) {
            case EVENT_CALL_RING:
                Log.d(LOG_TAG, "Event EVENT_CALL_RING Received state=" + getState());
                ar = (AsyncResult)msg.obj;
                if (ar.exception == null) {
                    VoicePhone.State state = getState();
                    if ((!mDoesRilSendMultipleCallRing)
                            && ((state == VoicePhone.State.RINGING) || (state == VoicePhone.State.IDLE))) {
                        mCallRingContinueToken += 1;
                        sendIncomingCallRingNotification(mCallRingContinueToken);
                    } else {
                        notifyIncomingRing();
                    }
                }
                break;

            case EVENT_CALL_RING_CONTINUE:
                Log.d(LOG_TAG, "Event EVENT_CALL_RING_CONTINUE Received stat=" + getState());
                if (getState() == VoicePhone.State.RINGING) {
                    sendIncomingCallRingNotification(msg.arg1);
                }
                break;

            default:
                throw new RuntimeException("unexpected event not handled: " + msg.what);
        }
    }

    // Inherited documentation suffices.
    public Context getContext() {
        return mContext;
    }

    // Inherited documentation suffices.
    public void registerForPreciseCallStateChanged(Handler h, int what, Object obj) {
        checkCorrectThread(h);

        mPreciseCallStateRegistrants.addUnique(h, what, obj);
    }

    // Inherited documentation suffices.
    public void unregisterForPreciseCallStateChanged(Handler h) {
        mPreciseCallStateRegistrants.remove(h);
    }

    /**
     * Subclasses of Phone probably want to replace this with a
     * version scoped to their packages
     */
    protected void notifyPreciseCallStateChangedP() {
        AsyncResult ar = new AsyncResult(null, this, null);
        mPreciseCallStateRegistrants.notifyRegistrants(ar);
    }

    // Inherited documentation suffices.
    public void registerForUnknownConnection(Handler h, int what, Object obj) {
        checkCorrectThread(h);

        mUnknownConnectionRegistrants.addUnique(h, what, obj);
    }

    // Inherited documentation suffices.
    public void unregisterForUnknownConnection(Handler h) {
        mUnknownConnectionRegistrants.remove(h);
    }

    // Inherited documentation suffices.
    public void registerForNewRingingConnection(
            Handler h, int what, Object obj) {
        checkCorrectThread(h);

        mNewRingingConnectionRegistrants.addUnique(h, what, obj);
    }

    // Inherited documentation suffices.
    public void unregisterForNewRingingConnection(Handler h) {
        mNewRingingConnectionRegistrants.remove(h);
    }

    // Inherited documentation suffices.
    public void registerForInCallVoicePrivacyOn(Handler h, int what, Object obj){
        mCM.registerForInCallVoicePrivacyOn(h,what,obj);
    }

    // Inherited documentation suffices.
    public void unregisterForInCallVoicePrivacyOn(Handler h){
        mCM.unregisterForInCallVoicePrivacyOn(h);
    }

    // Inherited documentation suffices.
    public void registerForInCallVoicePrivacyOff(Handler h, int what, Object obj){
        mCM.registerForInCallVoicePrivacyOff(h,what,obj);
    }

    // Inherited documentation suffices.
    public void unregisterForInCallVoicePrivacyOff(Handler h){
        mCM.unregisterForInCallVoicePrivacyOff(h);
    }

    public void setOnUnsolOemHookExtApp(Handler h, int what, Object obj) {
        mCM.setOnUnsolOemHookExtApp(h, what, obj);
    }

    public void unSetOnUnsolOemHookExtApp(Handler h) {
        mCM.unSetOnUnsolOemHookExtApp(h);
    }

    public void registerForCallReestablishInd(Handler h, int what, Object obj) {
        mCM.registerForCallReestablishInd(h, what, obj);
    }

    public void unregisterForCallReestablishInd(Handler h) {
        mCM.unregisterForCallReestablishInd(h);
    }

    // Inherited documentation suffices.
    public void registerForIncomingRing(
            Handler h, int what, Object obj) {
        checkCorrectThread(h);

        mIncomingRingRegistrants.addUnique(h, what, obj);
    }

    // Inherited documentation suffices.
    public void unregisterForIncomingRing(Handler h) {
        mIncomingRingRegistrants.remove(h);
    }

    // Inherited documentation suffices.
    public void registerForDisconnect(Handler h, int what, Object obj) {
        checkCorrectThread(h);

        mDisconnectRegistrants.addUnique(h, what, obj);
    }

    // Inherited documentation suffices.
    public void unregisterForDisconnect(Handler h) {
        mDisconnectRegistrants.remove(h);
    }

    // Inherited documentation suffices.
    public void registerForSuppServiceFailed(Handler h, int what, Object obj) {
        checkCorrectThread(h);

        mSuppServiceFailedRegistrants.addUnique(h, what, obj);
    }

    // Inherited documentation suffices.
    public void unregisterForSuppServiceFailed(Handler h) {
        mSuppServiceFailedRegistrants.remove(h);
    }

    // Inherited documentation suffices.
    public void registerForMmiInitiate(Handler h, int what, Object obj) {
        checkCorrectThread(h);

        mMmiRegistrants.addUnique(h, what, obj);
    }

    // Inherited documentation suffices.
    public void unregisterForMmiInitiate(Handler h) {
        mMmiRegistrants.remove(h);
    }

    // Inherited documentation suffices.
    public void registerForMmiComplete(Handler h, int what, Object obj) {
        checkCorrectThread(h);

        mMmiCompleteRegistrants.addUnique(h, what, obj);
    }

    // Inherited documentation suffices.
    public void unregisterForMmiComplete(Handler h) {
        checkCorrectThread(h);

        mMmiCompleteRegistrants.remove(h);
    }

    /**
     * Method to retrieve the saved operator id from the Shared Preferences
     */
    private String getSavedNetworkSelection() {
        // open the shared preferences and search with our key.
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        return sp.getString(NETWORK_SELECTION_KEY, "");
    }

    /**
     * Method to restore the previously saved operator id, or reset to
     * automatic selection, all depending upon the value in the shared
     * preferences.
     */
    public void restoreSavedNetworkSelection(Message response) {
        // retrieve the operator id
        String networkSelection = getSavedNetworkSelection();

        // set to auto if the id is empty, otherwise select the network.
        if (TextUtils.isEmpty(networkSelection)) {
            mCM.setNetworkSelectionModeAutomatic(response);
        } else {
            mCM.setNetworkSelectionModeManual(networkSelection, response);
        }
    }

    // Inherited documentation suffices.
    public void setUnitTestMode(boolean f) {
        mUnitTestMode = f;
    }

    // Inherited documentation suffices.
    public boolean getUnitTestMode() {
        return mUnitTestMode;
    }

    /**
     * To be invoked when a voice call Connection disconnects.
     *
     * Subclasses of Phone probably want to replace this with a
     * version scoped to their packages
     */
    protected void notifyDisconnectP(Connection cn) {
        AsyncResult ar = new AsyncResult(null, cn, null);
        mDisconnectRegistrants.notifyRegistrants(ar);
    }

    // Inherited documentation suffices.
    public void registerForVoiceServiceStateChanged(
            Handler h, int what, Object obj) {
        checkCorrectThread(h);

        mVoiceServiceStateRegistrants.add(h, what, obj);
    }

    // Inherited documentation suffices.
    public void unregisterForVoiceServiceStateChanged(Handler h) {
        mVoiceServiceStateRegistrants.remove(h);
    }

    // Inherited documentation suffices.
    public void registerForRingbackTone(Handler h, int what, Object obj) {
        mCM.registerForRingbackTone(h,what,obj);
    }

    // Inherited documentation suffices.
    public void unregisterForRingbackTone(Handler h) {
        mCM.unregisterForRingbackTone(h);
    }

    // Inherited documentation suffices.
    public void registerForResendIncallMute(Handler h, int what, Object obj) {
        mCM.registerForResendIncallMute(h,what,obj);
    }

    // Inherited documentation suffices.
    public void unregisterForResendIncallMute(Handler h) {
        mCM.unregisterForResendIncallMute(h);
    }

    public void setEchoSuppressionEnabled(boolean enabled) {
        // no need for regular phone
    }

    /**
     * Subclasses of Phone probably want to replace this with a
     * version scoped to their packages
     */
    protected void notifyServiceStateChangedP(ServiceState ss) {
        AsyncResult ar = new AsyncResult(null, ss, null);
        mVoiceServiceStateRegistrants.notifyRegistrants(ar);

        mNotifier.notifyVoiceServiceState(this);
    }

    // Inherited documentation suffices.
    public SimulatedRadioControl getSimulatedRadioControl() {
        return mSimulatedRadioControl;
    }

    /**
     * Verifies the current thread is the same as the thread originally
     * used in the initialization of this instance. Throws RuntimeException
     * if not.
     *
     * @exception RuntimeException if the current thread is not
     * the thread that originally obtained this PhoneBase instance.
     */
    private void checkCorrectThread(Handler h) {
        if (h.getLooper() != mLooper) {
            throw new RuntimeException(
                    "com.android.internal.telephony.Phone must be used from within one thread");
        }
    }

    /**
     * Set the properties by matching the carrier string in
     * a string-array resource
     */
    private void setPropertiesByCarrier() {
        String carrier = SystemProperties.get("ro.carrier");

        if (null == carrier || 0 == carrier.length() || "unknown".equals(carrier)) {
            return;
        }

        CharSequence[] carrierLocales = mContext.
                getResources().getTextArray(R.array.carrier_properties);

        for (int i = 0; i < carrierLocales.length; i+=3) {
            String c = carrierLocales[i].toString();
            if (carrier.equals(c)) {
                String l = carrierLocales[i+1].toString();
                int wifiChannels = 0;
                try {
                    wifiChannels = Integer.parseInt(
                            carrierLocales[i+2].toString());
                } catch (NumberFormatException e) { }

                String language = l.substring(0, 2);
                String country = "";
                if (l.length() >=5) {
                    country = l.substring(3, 5);
                }
                MccTable.setSystemLocale(mContext, language, country);

                if (wifiChannels != 0) {
                    try {
                        Settings.Secure.getInt(mContext.getContentResolver(),
                                Settings.Secure.WIFI_NUM_ALLOWED_CHANNELS);
                    } catch (Settings.SettingNotFoundException e) {
                        // note this is not persisting
                        WifiManager wM = (WifiManager)
                                mContext.getSystemService(Context.WIFI_SERVICE);
                        wM.setNumAllowedChannels(wifiChannels, false);
                    }
                }
                return;
            }
        }
    }

    /**
     * Get state
     */
    public abstract VoicePhone.State getState();

    /**
     * Returns the ICC card interface for this phone, or null
     * if not applicable to underlying technology.
     */
    public UiccCard getUiccCard() {
        Log.e(LOG_TAG, "getUiccCard: not supported for " + getPhoneName());
        return null;
    }

    /**
     * Retrieves the IccFileHandler of the Phone instance
     */
    public abstract IccFileHandler getIccFileHandler();

    /*
     * Retrieves the Handler of the Phone instance
     */
    public Handler getHandler() {
        return this;
    }

    /**
     *  Query the status of the CDMA roaming preference
     */
    public void queryCdmaRoamingPreference(Message response) {
        mCM.queryCdmaRoamingPreference(response);
    }

    /**
     *  Set the status of the CDMA roaming preference
     */
    public void setCdmaRoamingPreference(int cdmaRoamingType, Message response) {
        mCM.setCdmaRoamingPreference(cdmaRoamingType, response);
    }

    /**
     *  Set the status of the CDMA subscription mode
     */
    public void setCdmaSubscription(int cdmaSubscriptionType, Message response) {
        mCM.setCdmaSubscription(cdmaSubscriptionType, response);
    }

    /**
     *  Set the preferred Network Type: Global, CDMA only or GSM/UMTS only
     */
    public void setPreferredNetworkType(int networkType, Message response) {
        mCM.setPreferredNetworkType(networkType, response);
    }

    public void getPreferredNetworkType(Message response) {
        mCM.getPreferredNetworkType(response);
    }

    public void getSmscAddress(Message result) {
        mCM.getSmscAddress(result);
    }

    public void setSmscAddress(String address, Message result) {
        mCM.setSmscAddress(address, result);
    }

    /**
     * Set the TTY mode
     */
    public void setTTYMode(int ttyMode, Message onComplete) {
        mCM.setTTYMode(ttyMode, onComplete);
    }

    /**
     * Queries the TTY mode
     */
    public void queryTTYMode(Message onComplete) {
        mCM.queryTTYMode(onComplete);
    }

    public void enableEnhancedVoicePrivacy(boolean enable, Message onComplete) {
        // This function should be overridden by the class CDMAPhone. Not implemented in GSMPhone.
        logUnexpectedCdmaMethodCall("enableEnhancedVoicePrivacy");
    }

    public void getEnhancedVoicePrivacy(Message onComplete) {
        // This function should be overridden by the class CDMAPhone. Not implemented in GSMPhone.
        logUnexpectedCdmaMethodCall("getEnhancedVoicePrivacy");
    }

    public void setBandMode(int bandMode, Message response) {
        mCM.setBandMode(bandMode, response);
    }

    public void queryAvailableBandMode(Message response) {
        mCM.queryAvailableBandMode(response);
    }

    public void invokeOemRilRequestRaw(byte[] data, Message response) {
        mCM.invokeOemRilRequestRaw(data, response);
    }

    public void invokeDepersonalization(String pin, int type, Message response) {
        mCM.invokeDepersonalization(pin, type, response);
    }

    public void invokeOemRilRequestStrings(String[] strings, Message response) {
        mCM.invokeOemRilRequestStrings(strings, response);
    }

    public void notifyMessageWaitingIndicator() {
        // This function is added to send the notification to DefaultPhoneNotifier.
        mNotifier.notifyMessageWaitingChanged(this);
    }

    public abstract String getPhoneName();

    public abstract int getPhoneType();

    /** @hide */
    public int getVoiceMessageCount(){
        return 0;
    }

    /**
     * Returns the CDMA ERI icon index to display
     */
    public int getCdmaEriIconIndex() {
        logUnexpectedCdmaMethodCall("getCdmaEriIconIndex");
        return -1;
    }

    /**
     * Returns the CDMA ERI icon mode,
     * 0 - ON
     * 1 - FLASHING
     */
    public int getCdmaEriIconMode() {
        logUnexpectedCdmaMethodCall("getCdmaEriIconMode");
        return -1;
    }

    /**
     * Returns the CDMA ERI text,
     */
    public String getCdmaEriText() {
        logUnexpectedCdmaMethodCall("getCdmaEriText");
        return "GSM nw, no ERI";
    }

    public String getCdmaMin() {
        // This function should be overridden by the class CDMAPhone. Not implemented in GSMPhone.
        logUnexpectedCdmaMethodCall("getCdmaMin");
        return null;
    }

    public boolean isMinInfoReady() {
        // This function should be overridden by the class CDMAPhone. Not implemented in GSMPhone.
        logUnexpectedCdmaMethodCall("isMinInfoReady");
        return false;
    }

    public String getCdmaPrlVersion(){
        //  This function should be overridden by the class CDMAPhone. Not implemented in GSMPhone.
        logUnexpectedCdmaMethodCall("getCdmaPrlVersion");
        return null;
    }

    public void sendBurstDtmf(String dtmfString, int on, int off, Message onComplete) {
        // This function should be overridden by the class CDMAPhone. Not implemented in GSMPhone.
        logUnexpectedCdmaMethodCall("sendBurstDtmf");
    }

    public void exitEmergencyCallbackMode() {
        // This function should be overridden by the class CDMAPhone. Not implemented in GSMPhone.
        logUnexpectedCdmaMethodCall("exitEmergencyCallbackMode");
    }

    public void registerForCdmaOtaStatusChange(Handler h, int what, Object obj) {
        // This function should be overridden by the class CDMAPhone. Not implemented in GSMPhone.
        logUnexpectedCdmaMethodCall("registerForCdmaOtaStatusChange");
    }

    public void unregisterForCdmaOtaStatusChange(Handler h) {
        // This function should be overridden by the class CDMAPhone. Not implemented in GSMPhone.
        logUnexpectedCdmaMethodCall("unregisterForCdmaOtaStatusChange");
    }

    public void registerForSubscriptionInfoReady(Handler h, int what, Object obj) {
        // This function should be overridden by the class CDMAPhone. Not implemented in GSMPhone.
        logUnexpectedCdmaMethodCall("registerForSubscriptionInfoReady");
    }

    public void unregisterForSubscriptionInfoReady(Handler h) {
        // This function should be overridden by the class CDMAPhone. Not implemented in GSMPhone.
        logUnexpectedCdmaMethodCall("unregisterForSubscriptionInfoReady");
    }

    public  boolean isOtaSpNumber(String dialStr) {
        // This function should be overridden by the class CDMAPhone. Not implemented in GSMPhone.
        logUnexpectedCdmaMethodCall("isOtaSpNumber");
        return false;
    }

    public void registerForCallWaiting(Handler h, int what, Object obj){
        // This function should be overridden by the class CDMAPhone. Not implemented in GSMPhone.
        logUnexpectedCdmaMethodCall("registerForCallWaiting");
    }

    public void unregisterForCallWaiting(Handler h){
        // This function should be overridden by the class CDMAPhone. Not implemented in GSMPhone.
        logUnexpectedCdmaMethodCall("unregisterForCallWaiting");
    }

    public void registerForEcmTimerReset(Handler h, int what, Object obj) {
        // This function should be overridden by the class CDMAPhone. Not implemented in GSMPhone.
        logUnexpectedCdmaMethodCall("registerForEcmTimerReset");
    }

    public void unregisterForEcmTimerReset(Handler h) {
        // This function should be overridden by the class CDMAPhone. Not implemented in GSMPhone.
        logUnexpectedCdmaMethodCall("unregisterForEcmTimerReset");
    }

    public void registerForSignalInfo(Handler h, int what, Object obj) {
        mCM.registerForSignalInfo(h, what, obj);
    }

    public void unregisterForSignalInfo(Handler h) {
        mCM.unregisterForSignalInfo(h);
    }

    public void registerForDisplayInfo(Handler h, int what, Object obj) {
        mCM.registerForDisplayInfo(h, what, obj);
    }

     public void unregisterForDisplayInfo(Handler h) {
         mCM.unregisterForDisplayInfo(h);
     }

    public void registerForNumberInfo(Handler h, int what, Object obj) {
        mCM.registerForNumberInfo(h, what, obj);
    }

    public void unregisterForNumberInfo(Handler h) {
        mCM.unregisterForNumberInfo(h);
    }

    public void registerForRedirectedNumberInfo(Handler h, int what, Object obj) {
        mCM.registerForRedirectedNumberInfo(h, what, obj);
    }

    public void unregisterForRedirectedNumberInfo(Handler h) {
        mCM.unregisterForRedirectedNumberInfo(h);
    }

    public void registerForLineControlInfo(Handler h, int what, Object obj) {
        mCM.registerForLineControlInfo( h, what, obj);
    }

    public void unregisterForLineControlInfo(Handler h) {
        mCM.unregisterForLineControlInfo(h);
    }

    public void registerFoT53ClirlInfo(Handler h, int what, Object obj) {
        mCM.registerFoT53ClirlInfo(h, what, obj);
    }

    public void unregisterForT53ClirInfo(Handler h) {
        mCM.unregisterForT53ClirInfo(h);
    }

    public void registerForT53AudioControlInfo(Handler h, int what, Object obj) {
        mCM.registerForT53AudioControlInfo( h, what, obj);
    }

    public void unregisterForT53AudioControlInfo(Handler h) {
        mCM.unregisterForT53AudioControlInfo(h);
    }

     public void setOnEcbModeExitResponse(Handler h, int what, Object obj){
         // This function should be overridden by the class CDMAPhone. Not implemented in GSMPhone.
         logUnexpectedCdmaMethodCall("setOnEcbModeExitResponse");
     }

     public void unsetOnEcbModeExitResponse(Handler h){
        // This function should be overridden by the class CDMAPhone. Not implemented in GSMPhone.
         logUnexpectedCdmaMethodCall("unsetOnEcbModeExitResponse");
     }

    /**
     * Notify registrants of a new ringing Connection.
     * Subclasses of Phone probably want to replace this with a
     * version scoped to their packages
     */
    protected void notifyNewRingingConnectionP(Connection cn) {
        AsyncResult ar = new AsyncResult(null, cn, null);
        mNewRingingConnectionRegistrants.notifyRegistrants(ar);
    }

    /**
     * Notify registrants of a RING event.
     */
    private void notifyIncomingRing() {
        AsyncResult ar = new AsyncResult(null, this, null);
        mIncomingRingRegistrants.notifyRegistrants(ar);
    }

    /**
     * Send the incoming call Ring notification if conditions are right.
     */
    private void sendIncomingCallRingNotification(int token) {
        if (!mDoesRilSendMultipleCallRing && (token == mCallRingContinueToken)) {
            Log.d(LOG_TAG, "Sending notifyIncomingRing");
            notifyIncomingRing();
            sendMessageDelayed(
                    obtainMessage(EVENT_CALL_RING_CONTINUE, token, 0), mCallRingDelay);
        } else {
            Log.d(LOG_TAG, "Ignoring ring notification request,"
                    + " mDoesRilSendMultipleCallRing=" + mDoesRilSendMultipleCallRing
                    + " token=" + token
                    + " mCallRingContinueToken=" + mCallRingContinueToken);
        }
    }

    public boolean isCspPlmnEnabled() {
        // This function should be overridden by the class GSMPhone.
        // Not implemented in CDMAPhone.
        logUnexpectedGsmMethodCall("isCspPlmnEnabled");
        return false;
    }

    /**
     * Common error logger method for unexpected calls to GSM/WCDMA-only methods.
     */
    private void logUnexpectedGsmMethodCall(String name) {
        Log.e(LOG_TAG, "Error! " + name + "() in PhoneBase should not be " +
                "called, GSMPhone inactive.");
    }

    /**
     * Common error logger method for unexpected calls to CDMA-only methods.
     */
    private void logUnexpectedCdmaMethodCall(String name)
    {
        Log.e(LOG_TAG, "Error! " + name + "() in PhoneBase should not be " +
                "called, CDMAPhone inactive.");
    }

    public int getPhoneTypeFromNetworkType() {

        int preferredNetworkMode = RILConstants.PREFERRED_NETWORK_MODE;
        Context context = getContext();
        int networkMode = Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.PREFERRED_NETWORK_MODE, preferredNetworkMode);

        if ((networkMode == RILConstants.NETWORK_MODE_CDMA)
                || (networkMode == RILConstants.NETWORK_MODE_CDMA_NO_EVDO)
                || (networkMode == RILConstants.NETWORK_MODE_EVDO_NO_CDMA)
                || (networkMode == RILConstants.NETWORK_MODE_CDMA_AND_LTE_EVDO)) {
            return RILConstants.CDMA_PHONE;
        } else if ((networkMode == RILConstants.NETWORK_MODE_WCDMA_PREF)
                || (networkMode == RILConstants.NETWORK_MODE_GSM_ONLY)
                || (networkMode == RILConstants.NETWORK_MODE_WCDMA_ONLY)
                || (networkMode == RILConstants.NETWORK_MODE_GSM_UMTS)
                || (networkMode == RILConstants.NETWORK_MODE_GSM_WCDMA_LTE)) {
            return RILConstants.GSM_PHONE;
        } else if ((networkMode == RILConstants.NETWORK_MODE_GLOBAL)
                || (networkMode == RILConstants.NETWORK_MODE_GLOBAL_LTE)
                || (networkMode == RILConstants.NETWORK_MODE_LTE_ONLY)) {
            if (getPhoneType() == VoicePhone.PHONE_TYPE_CDMA) {
                return RILConstants.CDMA_PHONE;
            } else if (getPhoneType() == VoicePhone.PHONE_TYPE_GSM) {
                return RILConstants.GSM_PHONE;
            }
        }

        return RILConstants.NO_PHONE;
    }

    /**
     * Checks whether the modem is in power save mode
     * @return true if modem is in power save mode
     */
    public boolean isModemPowerSave() {
        return mModemPowerSaveStatus;
    }

    /**
     * Update modem power save status flag as per the argument passed
     */
    public void setPowerSaveStatus(boolean value) {
        mModemPowerSaveStatus = value;
    }

    PhoneAdapter mAsPhoneAdapter = null;

    public Phone asPhone() {
        if (mAsPhoneAdapter == null) {
            mAsPhoneAdapter = new PhoneAdapter(this);
        }
        return mAsPhoneAdapter;
    }

    class PhoneAdapter implements Phone {
        private VoicePhone mVoicePhone;

        PhoneAdapter(VoicePhone phone) {
            mVoicePhone = phone;
        }
        public void acceptCall() throws CallStateException {
            mVoicePhone.acceptCall();
        }

        public void activateCellBroadcastSms(int activate, Message response) {
            mVoicePhone.activateCellBroadcastSms(activate, response);
        }

        public Phone asPhone() {
            return mVoicePhone.asPhone();
        }

        public boolean canConference() {
            return mVoicePhone.canConference();
        }

        public boolean canTransfer() {
            return mVoicePhone.canTransfer();
        }

        public void clearDisconnected() {
            mVoicePhone.clearDisconnected();
        }

        public void conference() throws CallStateException {
            mVoicePhone.conference();
        }

        public Connection dial(String dialString, UUSInfo uusInfo) throws CallStateException {
            return mVoicePhone.dial(dialString, uusInfo);
        }

        public Connection dial(String dialString) throws CallStateException {
            return mVoicePhone.dial(dialString);
        }

        public void disableLocationUpdates() {
            mVoicePhone.disableLocationUpdates();
        }

        public void enableEnhancedVoicePrivacy(boolean enable, Message onComplete) {
            mVoicePhone.enableEnhancedVoicePrivacy(enable, onComplete);
        }

        public void enableLocationUpdates() {
            mVoicePhone.enableLocationUpdates();
        }

        public void exitEmergencyCallbackMode() {
            mVoicePhone.exitEmergencyCallbackMode();
        }

        public void explicitCallTransfer() throws CallStateException {
            mVoicePhone.explicitCallTransfer();
        }

        public void getAvailableNetworks(Message response) {
            mVoicePhone.getAvailableNetworks(response);
        }

        public Call getBackgroundCall() {
            return mVoicePhone.getBackgroundCall();
        }

        public boolean getCallForwardingIndicator() {
            return mVoicePhone.getCallForwardingIndicator();
        }

        public void getCallForwardingOption(int commandInterfaceCFReason, Message onComplete) {
            mVoicePhone.getCallForwardingOption(commandInterfaceCFReason, onComplete);
        }

        public void getCallWaiting(Message onComplete) {
            mVoicePhone.getCallWaiting(onComplete);
        }

        public int getCdmaEriIconIndex() {
            return mVoicePhone.getCdmaEriIconIndex();
        }

        public int getCdmaEriIconMode() {
            return mVoicePhone.getCdmaEriIconMode();
        }

        public String getCdmaEriText() {
            return mVoicePhone.getCdmaEriText();
        }

        public String getCdmaMin() {
            return mVoicePhone.getCdmaMin();
        }

        public String getCdmaPrlVersion() {
            return mVoicePhone.getCdmaPrlVersion();
        }

        public void getCellBroadcastSmsConfig(Message response) {
            mVoicePhone.getCellBroadcastSmsConfig(response);
        }

        public CellLocation getCellLocation() {
            return mVoicePhone.getCellLocation();
        }

        public Context getContext() {
            return mVoicePhone.getContext();
        }

        public String getDeviceId() {
            return mVoicePhone.getDeviceId();
        }

        public String getDeviceSvn() {
            return mVoicePhone.getDeviceSvn();
        }

        public void getEnhancedVoicePrivacy(Message onComplete) {
            mVoicePhone.getEnhancedVoicePrivacy(onComplete);
        }

        public String getEsn() {
            return mVoicePhone.getEsn();
        }

        public Call getForegroundCall() {
            return mVoicePhone.getForegroundCall();
        }

        public IccPhoneBookInterfaceManager getIccPhoneBookInterfaceManager() {
            return mVoicePhone.getIccPhoneBookInterfaceManager();
        }

        public boolean getIccRecordsLoaded() {
            return mVoicePhone.getIccRecordsLoaded();
        }

        public String getIccSerialNumber() {
            return mVoicePhone.getIccSerialNumber();
        }

        public String getLine1AlphaTag() {
            return mVoicePhone.getLine1AlphaTag();
        }

        public String getLine1Number() {
            return mVoicePhone.getLine1Number();
        }

        public String getMeid() {
            return mVoicePhone.getMeid();
        }

        public boolean getMessageWaitingIndicator() {
            return mVoicePhone.getMessageWaitingIndicator();
        }

        public boolean getMute() {
            return mVoicePhone.getMute();
        }

        public void getNeighboringCids(Message response) {
            mVoicePhone.getNeighboringCids(response);
        }

        public void getOutgoingCallerIdDisplay(Message onComplete) {
            mVoicePhone.getOutgoingCallerIdDisplay(onComplete);
        }

        public List<? extends MmiCode> getPendingMmiCodes() {
            return mVoicePhone.getPendingMmiCodes();
        }

        public String getPhoneName() {
            return mVoicePhone.getPhoneName();
        }

        public PhoneSubInfo getPhoneSubInfo() {
            return mVoicePhone.getPhoneSubInfo();
        }

        public int getPhoneType() {
            return mVoicePhone.getPhoneType();
        }

        public void getPreferredNetworkType(Message response) {
            mVoicePhone.getPreferredNetworkType(response);
        }

        public Call getRingingCall() {
            return mVoicePhone.getRingingCall();
        }

        public SignalStrength getSignalStrength() {
            return mVoicePhone.getSignalStrength();
        }

        public SimulatedRadioControl getSimulatedRadioControl() {
            return mVoicePhone.getSimulatedRadioControl();
        }

        public void getSmscAddress(Message result) {
            mVoicePhone.getSmscAddress(result);
        }

        public State getState() {
            return mVoicePhone.getState();
        }

        public String getSubscriberId() {
            return mVoicePhone.getSubscriberId();
        }

        public boolean getUnitTestMode() {
            return mVoicePhone.getUnitTestMode();
        }

        public String getVoiceMailAlphaTag() {
            return mVoicePhone.getVoiceMailAlphaTag();
        }

        public String getVoiceMailNumber() {
            return mVoicePhone.getVoiceMailNumber();
        }

        public int getVoiceMessageCount() {
            return mVoicePhone.getVoiceMessageCount();
        }

        public ServiceState getVoiceServiceState() {
            return mVoicePhone.getVoiceServiceState();
        }

        public boolean handleInCallMmiCommands(String command) throws CallStateException {
            return mVoicePhone.handleInCallMmiCommands(command);
        }

        public boolean handlePinMmi(String dialString) {
            return mVoicePhone.handlePinMmi(dialString);
        }

        public void invokeDepersonalization(String pin, int type, Message response) {
            mVoicePhone.invokeDepersonalization(pin, type, response);
        }

        public void invokeOemRilRequestRaw(byte[] data, Message response) {
            mVoicePhone.invokeOemRilRequestRaw(data, response);
        }

        public void invokeOemRilRequestStrings(String[] strings, Message response) {
            mVoicePhone.invokeOemRilRequestStrings(strings, response);
        }

        public boolean isCspPlmnEnabled() {
            return mVoicePhone.isCspPlmnEnabled();
        }

        public boolean isMinInfoReady() {
            return mVoicePhone.isMinInfoReady();
        }

        public boolean isModemPowerSave() {
            return mVoicePhone.isModemPowerSave();
        }

        public boolean isOtaSpNumber(String dialStr) {
            return mVoicePhone.isOtaSpNumber(dialStr);
        }

        public void queryAvailableBandMode(Message response) {
            mVoicePhone.queryAvailableBandMode(response);
        }

        public void queryCdmaRoamingPreference(Message response) {
            mVoicePhone.queryCdmaRoamingPreference(response);
        }

        public void queryTTYMode(Message onComplete) {
            mVoicePhone.queryTTYMode(onComplete);
        }

        public void registerForCallReestablishInd(Handler h, int what, Object obj) {
            mVoicePhone.registerForCallReestablishInd(h, what, obj);
        }

        public void registerForCallWaiting(Handler h, int what, Object obj) {
            mVoicePhone.registerForCallWaiting(h, what, obj);
        }

        public void registerForCdmaOtaStatusChange(Handler h, int what, Object obj) {
            mVoicePhone.registerForCdmaOtaStatusChange(h, what, obj);
        }

        public void registerForDisconnect(Handler h, int what, Object obj) {
            mVoicePhone.registerForDisconnect(h, what, obj);
        }

        public void registerForDisplayInfo(Handler h, int what, Object obj) {
            mVoicePhone.registerForDisplayInfo(h, what, obj);
        }

        public void registerForEcmTimerReset(Handler h, int what, Object obj) {
            mVoicePhone.registerForEcmTimerReset(h, what, obj);
        }

        public void registerForInCallVoicePrivacyOff(Handler h, int what, Object obj) {
            mVoicePhone.registerForInCallVoicePrivacyOff(h, what, obj);
        }

        public void registerForInCallVoicePrivacyOn(Handler h, int what, Object obj) {
            mVoicePhone.registerForInCallVoicePrivacyOn(h, what, obj);
        }

        public void registerForIncomingRing(Handler h, int what, Object obj) {
            mVoicePhone.registerForIncomingRing(h, what, obj);
        }

        public void registerForLineControlInfo(Handler h, int what, Object obj) {
            mVoicePhone.registerForLineControlInfo(h, what, obj);
        }

        public void registerForMmiComplete(Handler h, int what, Object obj) {
            mVoicePhone.registerForMmiComplete(h, what, obj);
        }

        public void registerForMmiInitiate(Handler h, int what, Object obj) {
            mVoicePhone.registerForMmiInitiate(h, what, obj);
        }

        public void registerForNewRingingConnection(Handler h, int what, Object obj) {
            mVoicePhone.registerForNewRingingConnection(h, what, obj);
        }

        public void registerForNumberInfo(Handler h, int what, Object obj) {
            mVoicePhone.registerForNumberInfo(h, what, obj);
        }

        public void registerForPreciseCallStateChanged(Handler h, int what, Object obj) {
            mVoicePhone.registerForPreciseCallStateChanged(h, what, obj);
        }

        public void registerForRedirectedNumberInfo(Handler h, int what, Object obj) {
            mVoicePhone.registerForRedirectedNumberInfo(h, what, obj);
        }

        public void registerForResendIncallMute(Handler h, int what, Object obj) {
            mVoicePhone.registerForResendIncallMute(h, what, obj);
        }

        public void registerForRingbackTone(Handler h, int what, Object obj) {
            mVoicePhone.registerForRingbackTone(h, what, obj);
        }

        public void registerForSignalInfo(Handler h, int what, Object obj) {
            mVoicePhone.registerForSignalInfo(h, what, obj);
        }

        public void registerForSubscriptionInfoReady(Handler h, int what, Object obj) {
            mVoicePhone.registerForSubscriptionInfoReady(h, what, obj);
        }

        public void registerForSuppServiceFailed(Handler h, int what, Object obj) {
            mVoicePhone.registerForSuppServiceFailed(h, what, obj);
        }

        public void registerForSuppServiceNotification(Handler h, int what, Object obj) {
            mVoicePhone.registerForSuppServiceNotification(h, what, obj);
        }

        public void registerForT53AudioControlInfo(Handler h, int what, Object obj) {
            mVoicePhone.registerForT53AudioControlInfo(h, what, obj);
        }

        public void registerForUnknownConnection(Handler h, int what, Object obj) {
            mVoicePhone.registerForUnknownConnection(h, what, obj);
        }

        public void registerForVoiceServiceStateChanged(Handler h, int what, Object obj) {
            mVoicePhone.registerForVoiceServiceStateChanged(h, what, obj);
        }

        public void registerFoT53ClirlInfo(Handler h, int what, Object obj) {
            mVoicePhone.registerFoT53ClirlInfo(h, what, obj);
        }

        public void rejectCall() throws CallStateException {
            mVoicePhone.rejectCall();
        }

        public void selectNetworkManually(NetworkInfo network, Message response) {
            mVoicePhone.selectNetworkManually(network, response);
        }

        public void sendBurstDtmf(String dtmfString, int on, int off, Message onComplete) {
            mVoicePhone.sendBurstDtmf(dtmfString, on, off, onComplete);
        }

        public void sendDtmf(char c) {
            mVoicePhone.sendDtmf(c);
        }

        public void sendUssdResponse(String ussdMessge) {
            mVoicePhone.sendUssdResponse(ussdMessge);
        }

        public void setBandMode(int bandMode, Message response) {
            mVoicePhone.setBandMode(bandMode, response);
        }

        public void setCallForwardingOption(int commandInterfaceCFReason,
                int commandInterfaceCFAction, String dialingNumber, int timerSeconds,
                Message onComplete) {
            mVoicePhone.setCallForwardingOption(commandInterfaceCFReason, commandInterfaceCFAction,
                    dialingNumber, timerSeconds, onComplete);
        }

        public void setCallWaiting(boolean enable, Message onComplete) {
            mVoicePhone.setCallWaiting(enable, onComplete);
        }

        public void setCdmaRoamingPreference(int cdmaRoamingType, Message response) {
            mVoicePhone.setCdmaRoamingPreference(cdmaRoamingType, response);
        }

        public void setCdmaSubscription(int cdmaSubscriptionType, Message response) {
            mVoicePhone.setCdmaSubscription(cdmaSubscriptionType, response);
        }

        public void setCellBroadcastSmsConfig(int[] configValuesArray, Message response) {
            mVoicePhone.setCellBroadcastSmsConfig(configValuesArray, response);
        }

        public void setEchoSuppressionEnabled(boolean enabled) {
            mVoicePhone.setEchoSuppressionEnabled(enabled);
        }

        public void setLine1Number(String alphaTag, String number, Message onComplete) {
            mVoicePhone.setLine1Number(alphaTag, number, onComplete);
        }

        public void setMute(boolean muted) {
            mVoicePhone.setMute(muted);
        }

        public void setNetworkSelectionModeAutomatic(Message response) {
            mVoicePhone.setNetworkSelectionModeAutomatic(response);
        }

        public void setOnEcbModeExitResponse(Handler h, int what, Object obj) {
            mVoicePhone.setOnEcbModeExitResponse(h, what, obj);
        }

        public void setOnPostDialCharacter(Handler h, int what, Object obj) {
            mVoicePhone.setOnPostDialCharacter(h, what, obj);
        }

        public void setOnUnsolOemHookExtApp(Handler h, int what, Object obj) {
            mVoicePhone.setOnUnsolOemHookExtApp(h, what, obj);
        }

        public void setOutgoingCallerIdDisplay(int commandInterfaceCLIRMode, Message onComplete) {
            mVoicePhone.setOutgoingCallerIdDisplay(commandInterfaceCLIRMode, onComplete);
        }

        public void setPreferredNetworkType(int networkType, Message response) {
            mVoicePhone.setPreferredNetworkType(networkType, response);
        }

        public void setSmscAddress(String address, Message result) {
            mVoicePhone.setSmscAddress(address, result);
        }

        public void setTTYMode(int ttyMode, Message onComplete) {
            mVoicePhone.setTTYMode(ttyMode, onComplete);
        }

        public void setUnitTestMode(boolean f) {
            mVoicePhone.setUnitTestMode(f);
        }

        public void setVoiceMailNumber(String alphaTag, String voiceMailNumber, Message onComplete) {
            mVoicePhone.setVoiceMailNumber(alphaTag, voiceMailNumber, onComplete);
        }

        public void startDtmf(char c) {
            mVoicePhone.startDtmf(c);
        }

        public void stopDtmf() {
            mVoicePhone.stopDtmf();
        }

        public void switchHoldingAndActive() throws CallStateException {
            mVoicePhone.switchHoldingAndActive();
        }

        public void unregisterForCallReestablishInd(Handler h) {
            mVoicePhone.unregisterForCallReestablishInd(h);
        }

        public void unregisterForCallWaiting(Handler h) {
            mVoicePhone.unregisterForCallWaiting(h);
        }

        public void unregisterForCdmaOtaStatusChange(Handler h) {
            mVoicePhone.unregisterForCdmaOtaStatusChange(h);
        }

        public void unregisterForDisconnect(Handler h) {
            mVoicePhone.unregisterForDisconnect(h);
        }

        public void unregisterForDisplayInfo(Handler h) {
            mVoicePhone.unregisterForDisplayInfo(h);
        }

        public void unregisterForEcmTimerReset(Handler h) {
            mVoicePhone.unregisterForEcmTimerReset(h);
        }

        public void unregisterForInCallVoicePrivacyOff(Handler h) {
            mVoicePhone.unregisterForInCallVoicePrivacyOff(h);
        }

        public void unregisterForInCallVoicePrivacyOn(Handler h) {
            mVoicePhone.unregisterForInCallVoicePrivacyOn(h);
        }

        public void unregisterForIncomingRing(Handler h) {
            mVoicePhone.unregisterForIncomingRing(h);
        }

        public void unregisterForLineControlInfo(Handler h) {
            mVoicePhone.unregisterForLineControlInfo(h);
        }

        public void unregisterForMmiComplete(Handler h) {
            mVoicePhone.unregisterForMmiComplete(h);
        }

        public void unregisterForMmiInitiate(Handler h) {
            mVoicePhone.unregisterForMmiInitiate(h);
        }

        public void unregisterForNewRingingConnection(Handler h) {
            mVoicePhone.unregisterForNewRingingConnection(h);
        }

        public void unregisterForNumberInfo(Handler h) {
            mVoicePhone.unregisterForNumberInfo(h);
        }

        public void unregisterForPreciseCallStateChanged(Handler h) {
            mVoicePhone.unregisterForPreciseCallStateChanged(h);
        }

        public void unregisterForRedirectedNumberInfo(Handler h) {
            mVoicePhone.unregisterForRedirectedNumberInfo(h);
        }

        public void unregisterForResendIncallMute(Handler h) {
            mVoicePhone.unregisterForResendIncallMute(h);
        }

        public void unregisterForRingbackTone(Handler h) {
            mVoicePhone.unregisterForRingbackTone(h);
        }

        public void unregisterForSignalInfo(Handler h) {
            mVoicePhone.unregisterForSignalInfo(h);
        }

        public void unregisterForSubscriptionInfoReady(Handler h) {
            mVoicePhone.unregisterForSubscriptionInfoReady(h);
        }

        public void unregisterForSuppServiceFailed(Handler h) {
            mVoicePhone.unregisterForSuppServiceFailed(h);
        }

        public void unregisterForSuppServiceNotification(Handler h) {
            mVoicePhone.unregisterForSuppServiceNotification(h);
        }

        public void unregisterForT53AudioControlInfo(Handler h) {
            mVoicePhone.unregisterForT53AudioControlInfo(h);
        }

        public void unregisterForT53ClirInfo(Handler h) {
            mVoicePhone.unregisterForT53ClirInfo(h);
        }

        public void unregisterForUnknownConnection(Handler h) {
            mVoicePhone.unregisterForUnknownConnection(h);
        }

        public void unregisterForVoiceServiceStateChanged(Handler h) {
            mVoicePhone.unregisterForVoiceServiceStateChanged(h);
        }

        public void unsetOnEcbModeExitResponse(Handler h) {
            mVoicePhone.unsetOnEcbModeExitResponse(h);
        }

        public void unSetOnUnsolOemHookExtApp(Handler h) {
            mVoicePhone.unSetOnUnsolOemHookExtApp(h);
        }

        public void updateServiceLocation() {
            mVoicePhone.updateServiceLocation();
        }

        @Override
        public IccCard getIccCard() {
            return null;
        }

        @Override
        public ServiceState getServiceState() {
            return mVoicePhone.getVoiceServiceState();
        }

        @Override
        public void registerForServiceStateChanged(Handler h, int what, Object obj) {
            mVoicePhone.registerForVoiceServiceStateChanged(h, what, obj);
        }

        @Override
        public void setRadioPower(boolean power) {
            logWrongPhone("setRadioPower");
            PhoneFactory.getDefaultPhone().setRadioPower(power);
        }

        @Override
        public void unregisterForServiceStateChanged(Handler h) {
            mVoicePhone.unregisterForVoiceServiceStateChanged(h);
        }

        @Override
        public int disableApnType(String type) {
            logWrongPhone("disableApnType");
            return 0;
        }

        @Override
        public boolean disableDataConnectivity() {
            logWrongPhone("disableDataConnectivity");
            return false;
        }

        @Override
        public void disableDnsCheck(boolean b) {
            logWrongPhone("disableDnsCheck");
        }

        @Override
        public int enableApnType(String type) {
            logWrongPhone("enableApnType");
            return 0;
        }

        @Override
        public boolean enableDataConnectivity() {
            logWrongPhone("enableDataConnectivity");
            return false;
        }

        @Override
        public String getActiveApn() {
            logWrongPhone("getActiveApn");
            return null;
        }

        @Override
        public String getActiveApn(String type, IPVersion ipv) {
            logWrongPhone("getActiveApn(type,ipv)");
            return null;
        }

        @Override
        public String[] getActiveApnTypes() {
            logWrongPhone("getActiveApnTypes");
            return null;
        }

        @Override
        public List<DataConnection> getCurrentDataConnectionList() {
            logWrongPhone("getCurrentDataConnectionList");
            return null;
        }

        @Override
        public DataActivityState getDataActivityState() {
            logWrongPhone("getDataActivityState");
            return null;
        }

        @Override
        public void getDataCallList(Message response) {
            logWrongPhone("getDataCallList");
        }

        @Override
        public DataState getDataConnectionState() {
            logWrongPhone("getDataConnectionState");
            return null;
        }

        @Override
        public DataState getDataConnectionState(String type, IPVersion ipv) {
            logWrongPhone("getDataConnectionState(type,ipv)");
            return null;
        }

        @Override
        public boolean getDataRoamingEnabled() {
            logWrongPhone("getDataRoamingEnabled");
            return false;
        }

        @Override
        public ServiceState getDataServiceState() {
            logWrongPhone("getDataServiceState");
            return null;
        }

        @Override
        public String[] getDnsServers(String apnType) {
            logWrongPhone("getDnsServers");
            return null;
        }

        @Override
        public String[] getDnsServers(String apnType, IPVersion ipv) {
            logWrongPhone("getDnsServers");
            return null;
        }

        @Override
        public String getGateway(String apnType) {
            logWrongPhone("getGateway");
            return null;
        }

        @Override
        public String getGateway(String apnType, IPVersion ipv) {
            logWrongPhone("getGateway");
            return null;
        }

        @Override
        public String getInterfaceName(String apnType) {
            logWrongPhone("getInterfaceName");
            return null;
        }

        @Override
        public String getInterfaceName(String apnType, IPVersion ipv) {
            logWrongPhone("getInterfaceName");
            return null;
        }

        @Override
        public String getIpAddress(String apnType) {
            logWrongPhone("getIpAddress");
            return null;
        }

        @Override
        public String getIpAddress(String apnType, IPVersion ipv) {
            logWrongPhone("getIpAddress(apnType,ipv)");
            return null;
        }

        @Override
        public boolean isDataConnectivityEnabled() {
            logWrongPhone("isDataConnectivityEnabled");
            return false;
        }

        @Override
        public boolean isDataConnectivityPossible() {
            logWrongPhone("isDataConnectivityPossible");
            return false;
        }

        @Override
        public boolean isDnsCheckDisabled() {
            logWrongPhone("isDnsCheckDisabled");
            return false;
        }

        @Override
        public void notifyDataActivity() {
            logWrongPhone("notifyDataActivity");
        }

        @Override
        public void registerForDataServiceStateChanged(Handler h, int what, Object obj) {
            logWrongPhone("registerForDataServiceStateChanged");
        }

        @Override
        public void setDataRoamingEnabled(boolean enable) {
            logWrongPhone("setDataRoamingEnabled");
        }

        @Override
        public void unregisterForDataServiceStateChanged(Handler h) {
            logWrongPhone("unregisterForDataServiceStateChanged");
        }

        private void logWrongPhone(String fname) {
            Log.w(LOG_TAG, "[" + fname + "] called on a VoicePhone");
        }
    }
}
