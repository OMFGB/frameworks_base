/*
 * Copyright (C) 2008 The Android Open Source Project
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


import java.util.List;

import android.app.ActivityManagerNative;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.CellLocation;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.util.Log;

import com.android.internal.telephony.CommandsInterface.RadioTechnologyFamily;
import com.android.internal.telephony.cdma.CDMAPhone;
import com.android.internal.telephony.gsm.GSMPhone;
import com.android.internal.telephony.gsm.NetworkInfo;
import com.android.internal.telephony.test.SimulatedRadioControl;

public class PhoneProxy extends Handler implements Phone {
    public final static Object lockForRadioTechnologyChange = new Object();

    private VoicePhone mActiveVoicePhone;
    private DataPhone mActiveDataPhone;
    private CDMAPhone mCDMAPhone;
    private GSMPhone mGSMPhone;

    private CommandsInterface mCi;
    private IccSmsInterfaceManagerProxy mIccSmsInterfaceManagerProxy;
    private IccPhoneBookInterfaceManagerProxy mIccPhoneBookInterfaceManagerProxy;
    private PhoneSubInfoProxy mPhoneSubInfoProxy;
    private IccCardProxy mIccProxy;

    private boolean mResetModemOnRadioTechnologyChange = false;
    private int mVoiceTechQueryContext = 0;

    private boolean mDesiredPowerState;

    private static final int EVENT_VOICE_RADIO_TECHNOLOGY_CHANGED = 1;
    private static final int EVENT_RADIO_STATE_CHANGED = 2;
    private static final int EVENT_REQUEST_VOICE_RADIO_TECH_DONE = 3;
    private static final int EVENT_SET_RADIO_POWER = 4;

    private static final String LOG_TAG = "PHONE";

    public PhoneProxy(VoicePhone voicePhone, DataPhone dataPhone) {

        mActiveVoicePhone = voicePhone;
        mActiveDataPhone = dataPhone;

        if (mActiveVoicePhone.getPhoneType() == PHONE_TYPE_CDMA) {
            mCDMAPhone = (CDMAPhone) mActiveVoicePhone;
        } else if (mActiveVoicePhone.getPhoneType() == PHONE_TYPE_GSM) {
            mGSMPhone = (GSMPhone) mActiveVoicePhone;
        }
        mResetModemOnRadioTechnologyChange = SystemProperties.getBoolean(
                TelephonyProperties.PROPERTY_RESET_ON_RADIO_TECH_CHANGE, false);
        mIccSmsInterfaceManagerProxy = new IccSmsInterfaceManagerProxy(voicePhone
                .getIccSmsInterfaceManager());
        mIccPhoneBookInterfaceManagerProxy = new IccPhoneBookInterfaceManagerProxy(voicePhone
                .getIccPhoneBookInterfaceManager());
        mPhoneSubInfoProxy = new PhoneSubInfoProxy(voicePhone.getPhoneSubInfo());

        // TODO: fusion - gets commands interface from voice rt now, might change later
        mCi = ((PhoneBase) mActiveVoicePhone).mCM;
        mCi.registerForRadioStateChanged(this, EVENT_RADIO_STATE_CHANGED, null);
        mCi.registerForVoiceRadioTechChanged(this, EVENT_VOICE_RADIO_TECHNOLOGY_CHANGED, null);

        mIccProxy = new IccCardProxy(voicePhone.getContext(), mCi);
        mIccProxy.setVoiceRadioTech(
                voicePhone.getPhoneType() == VoicePhone.PHONE_TYPE_CDMA ?
                        RadioTechnologyFamily.RADIO_TECH_3GPP2
                        : RadioTechnologyFamily.RADIO_TECH_3GPP);

        // system setting property AIRPLANE_MODE_ON is set in Settings.
        int airplaneMode = Settings.System.getInt(voicePhone.getContext().getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON, 0);
        mDesiredPowerState = !(airplaneMode > 0);

        UiccManager.getInstance(voicePhone.getContext(), mCi);
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;
        switch (msg.what) {

            case EVENT_RADIO_STATE_CHANGED:

                logv("EVENT_RADIO_STATE_CHANGED : newState = " + mCi.getRadioState());

                setPowerStateToDesired();

                if (mDesiredPowerState && mCi.getRadioState().isOn()) {
                    /* Proactively query voice radio technologies */
                    mVoiceTechQueryContext++;
                    mCi.getVoiceRadioTechnology(this.obtainMessage(
                            EVENT_REQUEST_VOICE_RADIO_TECH_DONE, mVoiceTechQueryContext));
                }
                break;

            case EVENT_SET_RADIO_POWER:
                boolean newPowerState = (Boolean) msg.obj;
                logv("EVENT_SET_RADIO_POWER : newPowerState = " + newPowerState);
                mCi.setRadioPower(newPowerState, null);
                break;

            case EVENT_VOICE_RADIO_TECHNOLOGY_CHANGED:
                mVoiceTechQueryContext++;
                mCi.getVoiceRadioTechnology(this
                        .obtainMessage(EVENT_REQUEST_VOICE_RADIO_TECH_DONE, mVoiceTechQueryContext));
                break;

            case EVENT_REQUEST_VOICE_RADIO_TECH_DONE:
                ar = (AsyncResult) msg.obj;

                if ((Integer)ar.userObj != mVoiceTechQueryContext) return;

                if (ar.exception == null) { 
                    RadioTechnologyFamily newVoiceTech = 
                            RadioTechnologyFamily.getRadioTechFamilyFromInt(((int[]) ar.result)[0]); 
                    updatePhoneObject(newVoiceTech); 
                } else {
                    loge("Voice Radio Technology query failed!");
                }
                break;

            default:
                Log.e(LOG_TAG,
                        "Error! This handler was not registered for this message type. Message: "
                                + msg.what);
                break;
        }
    }

    private void updatePhoneObject(RadioTechnologyFamily newVoiceRadioTech) {

        if (mActiveVoicePhone != null
                && ((newVoiceRadioTech.isCdma() && mActiveVoicePhone.getPhoneType() == PHONE_TYPE_CDMA))
                || ((newVoiceRadioTech.isGsm() && mActiveVoicePhone.getPhoneType() == PHONE_TYPE_GSM))) {
            // Nothing changed. Keep phone as it is.
            Log.v(LOG_TAG, "Ignoring voice radio technology changed message. newVoiceRadioTech = "
                    + newVoiceRadioTech + "Active Phone = " + mActiveVoicePhone.getPhoneName());
            return;
        }

        if (newVoiceRadioTech.isUnknown()) {
            // We need some voice phone object to be active always, so never
            // delete the phone without anything to replace it with!
            Log.i(LOG_TAG,
                    "Ignoring voice radio technology changed message. newVoiceRadioTech = Unknown."
                            + "Active Phone = " + mActiveVoicePhone.getPhoneName());
            return;
        }

        boolean oldPowerState = false; // old power state to off
        if (mResetModemOnRadioTechnologyChange) {
            if (mCi.getRadioState().isOn()) {
                oldPowerState = true;
                logd("Setting Radio Power to Off");
                mCi.setRadioPower(false, null);
            }
        }

        deleteAndCreatePhone(newVoiceRadioTech);

        if (mResetModemOnRadioTechnologyChange) { // restore power state
            logd("Resetting Radio");
            mCi.setRadioPower(oldPowerState, null);
        }

        // Set the new interfaces in the proxy's
        mIccSmsInterfaceManagerProxy.setmIccSmsInterfaceManager(mActiveVoicePhone
                .getIccSmsInterfaceManager());
        mIccPhoneBookInterfaceManagerProxy.setmIccPhoneBookInterfaceManager(mActiveVoicePhone
                .getIccPhoneBookInterfaceManager());
        mPhoneSubInfoProxy.setmPhoneSubInfo(this.mActiveVoicePhone.getPhoneSubInfo());
        mIccProxy.setVoiceRadioTech(newVoiceRadioTech);

        mCi = ((PhoneBase)mActiveVoicePhone).mCM;

        // Send an Intent to the PhoneApp that we had a radio technology change
        Intent intent = new Intent(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(Phone.PHONE_NAME_KEY, mActiveVoicePhone.getPhoneName());
        ActivityManagerNative.broadcastStickyIntent(intent, null);

    }

    private void logv(String msg) {
        Log.v(LOG_TAG, "[PhoneProxy] " + msg);
    }

    private void logd(String msg) {
        Log.d(LOG_TAG, "[PhoneProxy] " + msg);
    }

    private void logw(String msg) {
        Log.w(LOG_TAG, "[PhoneProxy] " + msg);
    }

    private void loge(String msg) {
        Log.e(LOG_TAG, "[PhoneProxy] " + msg);
    }

    private void deleteAndCreatePhone(RadioTechnologyFamily newVoiceRadioTech) {

        String mOutgoingPhoneName = "Unknown";

        if (mActiveVoicePhone != null) {
            mOutgoingPhoneName = ((PhoneBase) mActiveVoicePhone).getPhoneName();
        }

        Log.i(LOG_TAG, "Switching Voice Phone : " + mOutgoingPhoneName + " >>> "
                + (newVoiceRadioTech.isGsm() ? "GSM" : "CDMA"));

        if (mActiveVoicePhone != null) {
            Log.v(LOG_TAG, "Disposing old phone..");
            if (mActiveVoicePhone instanceof GSMPhone) {
                ((GSMPhone) mActiveVoicePhone).dispose();
            } else if (mActiveVoicePhone instanceof CDMAPhone) {
                ((CDMAPhone) mActiveVoicePhone).dispose();
            }
        }

        Phone oldPhone = mActiveVoicePhone;

        // Give the garbage collector a hint to start the garbage collection
        // asap NOTE this has been disabled since radio technology change could
        // happen during e.g. a multimedia playing and could slow the system.
        // Tests needs to be done to see the effects of the GC call here when
        // system is busy.
        // System.gc();

        if (newVoiceRadioTech.isCdma()) {
            if (mCDMAPhone != null) {
                mActiveVoicePhone = mCDMAPhone;
            } else {
                mActiveVoicePhone = PhoneFactory.getCdmaPhone();
                mCDMAPhone = (CDMAPhone) mActiveVoicePhone;
            }
            if (null != oldPhone) {
                //((GSMPhone) oldPhone).removeReferences();
            }
        } else if (newVoiceRadioTech.isGsm()) {
            if (mGSMPhone != null) {
                mActiveVoicePhone = mGSMPhone;
            } else {
                mActiveVoicePhone = PhoneFactory.getGsmPhone();
            }
            if (null != oldPhone) {
                //((CDMAPhone) oldPhone).removeReferences();
            }
        }

        oldPhone = null;
    }

    public void setRadioPower(boolean power) {
        mDesiredPowerState = power;
        setPowerStateToDesired();
    }

    private synchronized void setPowerStateToDesired() {
        logv("setPowerStateToDesired : mDesiredPowerState = " + mDesiredPowerState
                + ", getRadioState() = " + mCi.getRadioState());

        // If we want it on and it's off, turn it on
        if (mDesiredPowerState
                && mCi.getRadioState() == CommandsInterface.RadioState.RADIO_OFF) {
            mCi.setRadioPower(true, null);
        } else if (!mDesiredPowerState && mCi.getRadioState().isOn()) {
            Message powerOffMsg = obtainMessage(EVENT_SET_RADIO_POWER, mDesiredPowerState);
            // we want it off, but might need data to be disconnected.
            if (mActiveDataPhone == null) {
                sendMessage(powerOffMsg);
            } else {
                ((DataConnectionTracker) mActiveDataPhone).setDataConnectionAsDesired(
                        mDesiredPowerState, powerOffMsg);
            }
        }
    }

    public ServiceState getServiceState() {
        return mActiveVoicePhone.getServiceState();
    }

    public CellLocation getCellLocation() {
        return mActiveVoicePhone.getCellLocation();
    }

    public Context getContext() {
        return mActiveVoicePhone.getContext();
    }

    public State getState() {
        return (VoicePhone.State)mActiveVoicePhone.getState();
    }

    public String getPhoneName() {
        return mActiveVoicePhone.getPhoneName();
    }

    public int getPhoneType() {
        return mActiveVoicePhone.getPhoneType();
    }

    public SignalStrength getSignalStrength() {
        return mActiveVoicePhone.getSignalStrength();
    }

    public void registerForUnknownConnection(Handler h, int what, Object obj) {
        mActiveVoicePhone.registerForUnknownConnection(h, what, obj);
    }

    public void unregisterForUnknownConnection(Handler h) {
        mActiveVoicePhone.unregisterForUnknownConnection(h);
    }

    public void registerForPreciseCallStateChanged(Handler h, int what, Object obj) {
        mActiveVoicePhone.registerForPreciseCallStateChanged(h, what, obj);
    }

    public void unregisterForPreciseCallStateChanged(Handler h) {
        mActiveVoicePhone.unregisterForPreciseCallStateChanged(h);
    }

    public void registerForNewRingingConnection(Handler h, int what, Object obj) {
        mActiveVoicePhone.registerForNewRingingConnection(h, what, obj);
    }

    public void unregisterForNewRingingConnection(Handler h) {
        mActiveVoicePhone.unregisterForNewRingingConnection(h);
    }

    public void registerForIncomingRing(Handler h, int what, Object obj) {
        mActiveVoicePhone.registerForIncomingRing(h, what, obj);
    }

    public void unregisterForIncomingRing(Handler h) {
        mActiveVoicePhone.unregisterForIncomingRing(h);
    }

    public void registerForDisconnect(Handler h, int what, Object obj) {
        mActiveVoicePhone.registerForDisconnect(h, what, obj);
    }

    public void unregisterForDisconnect(Handler h) {
        mActiveVoicePhone.unregisterForDisconnect(h);
    }

    public void registerForMmiInitiate(Handler h, int what, Object obj) {
        mActiveVoicePhone.registerForMmiInitiate(h, what, obj);
    }

    public void unregisterForMmiInitiate(Handler h) {
        mActiveVoicePhone.unregisterForMmiInitiate(h);
    }

    public void registerForMmiComplete(Handler h, int what, Object obj) {
        mActiveVoicePhone.registerForMmiComplete(h, what, obj);
    }

    public void unregisterForMmiComplete(Handler h) {
        mActiveVoicePhone.unregisterForMmiComplete(h);
    }

    public List<? extends MmiCode> getPendingMmiCodes() {
        return mActiveVoicePhone.getPendingMmiCodes();
    }

    public void sendUssdResponse(String ussdMessge) {
        mActiveVoicePhone.sendUssdResponse(ussdMessge);
    }

    public void registerForServiceStateChanged(Handler h, int what, Object obj) {
        mActiveVoicePhone.registerForServiceStateChanged(h, what, obj);
    }

    public void unregisterForServiceStateChanged(Handler h) {
        mActiveVoicePhone.unregisterForServiceStateChanged(h);
    }

    public void registerForSuppServiceNotification(Handler h, int what, Object obj) {
        mActiveVoicePhone.registerForSuppServiceNotification(h, what, obj);
    }

    public void unregisterForSuppServiceNotification(Handler h) {
        mActiveVoicePhone.unregisterForSuppServiceNotification(h);
    }

    public void registerForSuppServiceFailed(Handler h, int what, Object obj) {
        mActiveVoicePhone.registerForSuppServiceFailed(h, what, obj);
    }

    public void unregisterForSuppServiceFailed(Handler h) {
        mActiveVoicePhone.unregisterForSuppServiceFailed(h);
    }

    public void registerForInCallVoicePrivacyOn(Handler h, int what, Object obj){
        mActiveVoicePhone.registerForInCallVoicePrivacyOn(h,what,obj);
    }

    public void unregisterForInCallVoicePrivacyOn(Handler h){
        mActiveVoicePhone.unregisterForInCallVoicePrivacyOn(h);
    }

    public void registerForInCallVoicePrivacyOff(Handler h, int what, Object obj){
        mActiveVoicePhone.registerForInCallVoicePrivacyOff(h,what,obj);
    }

    public void unregisterForInCallVoicePrivacyOff(Handler h){
        mActiveVoicePhone.unregisterForInCallVoicePrivacyOff(h);
    }

    public void registerForCdmaOtaStatusChange(Handler h, int what, Object obj) {
        mActiveVoicePhone.registerForCdmaOtaStatusChange(h,what,obj);
    }

    public void unregisterForCdmaOtaStatusChange(Handler h) {
         mActiveVoicePhone.unregisterForCdmaOtaStatusChange(h);
    }

    public void registerForSubscriptionInfoReady(Handler h, int what, Object obj) {
        mActiveVoicePhone.registerForSubscriptionInfoReady(h, what, obj);
    }

    public void unregisterForSubscriptionInfoReady(Handler h) {
        mActiveVoicePhone.unregisterForSubscriptionInfoReady(h);
    }

    public void registerForEcmTimerReset(Handler h, int what, Object obj) {
        mActiveVoicePhone.registerForEcmTimerReset(h,what,obj);
    }

    public void unregisterForEcmTimerReset(Handler h) {
        mActiveVoicePhone.unregisterForEcmTimerReset(h);
    }

    public void registerForRingbackTone(Handler h, int what, Object obj) {
        mActiveVoicePhone.registerForRingbackTone(h,what,obj);
    }

    public void unregisterForRingbackTone(Handler h) {
        mActiveVoicePhone.unregisterForRingbackTone(h);
    }

    public void registerForResendIncallMute(Handler h, int what, Object obj) {
        mActiveVoicePhone.registerForResendIncallMute(h, what, obj);
    }

    public void unregisterForResendIncallMute(Handler h) {
        mActiveVoicePhone.unregisterForResendIncallMute(h);
    }

    public boolean getIccRecordsLoaded() {
        return mActiveVoicePhone.getIccRecordsLoaded();
    }

    public IccCard getIccCard() {
        return mIccProxy;
    }

    public void acceptCall() throws CallStateException {
        mActiveVoicePhone.acceptCall();
    }

    public void rejectCall() throws CallStateException {
        mActiveVoicePhone.rejectCall();
    }

    public void switchHoldingAndActive() throws CallStateException {
        mActiveVoicePhone.switchHoldingAndActive();
    }

    public boolean canConference() {
        return mActiveVoicePhone.canConference();
    }

    public void conference() throws CallStateException {
        mActiveVoicePhone.conference();
    }

    public void enableEnhancedVoicePrivacy(boolean enable, Message onComplete) {
        mActiveVoicePhone.enableEnhancedVoicePrivacy(enable, onComplete);
    }

    public void getEnhancedVoicePrivacy(Message onComplete) {
        mActiveVoicePhone.getEnhancedVoicePrivacy(onComplete);
    }

    public boolean canTransfer() {
        return mActiveVoicePhone.canTransfer();
    }

    public void explicitCallTransfer() throws CallStateException {
        mActiveVoicePhone.explicitCallTransfer();
    }

    public void clearDisconnected() {
        mActiveVoicePhone.clearDisconnected();
    }

    public Call getForegroundCall() {
        return mActiveVoicePhone.getForegroundCall();
    }

    public Call getBackgroundCall() {
        return mActiveVoicePhone.getBackgroundCall();
    }

    public Call getRingingCall() {
        return mActiveVoicePhone.getRingingCall();
    }

    public Connection dial(String dialString) throws CallStateException {
        return mActiveVoicePhone.dial(dialString);
    }

    public Connection dial(String dialString, UUSInfo uusInfo) throws CallStateException {
        return mActiveVoicePhone.dial(dialString, uusInfo);
    }

    public boolean handlePinMmi(String dialString) {
        return mActiveVoicePhone.handlePinMmi(dialString);
    }

    public boolean handleInCallMmiCommands(String command) throws CallStateException {
        return mActiveVoicePhone.handleInCallMmiCommands(command);
    }

    public void sendDtmf(char c) {
        mActiveVoicePhone.sendDtmf(c);
    }

    public void startDtmf(char c) {
        mActiveVoicePhone.startDtmf(c);
    }

    public void stopDtmf() {
        mActiveVoicePhone.stopDtmf();
    }

    public boolean getMessageWaitingIndicator() {
        return mActiveVoicePhone.getMessageWaitingIndicator();
    }

    public boolean getCallForwardingIndicator() {
        return mActiveVoicePhone.getCallForwardingIndicator();
    }

    public String getLine1Number() {
        return mActiveVoicePhone.getLine1Number();
    }

    public String getCdmaMin() {
        return mActiveVoicePhone.getCdmaMin();
    }

    public boolean isMinInfoReady() {
        return mActiveVoicePhone.isMinInfoReady();
    }

    public String getCdmaPrlVersion() {
        return mActiveVoicePhone.getCdmaPrlVersion();
    }

    public String getLine1AlphaTag() {
        return mActiveVoicePhone.getLine1AlphaTag();
    }

    public void setLine1Number(String alphaTag, String number, Message onComplete) {
        mActiveVoicePhone.setLine1Number(alphaTag, number, onComplete);
    }

    public String getVoiceMailNumber() {
        return mActiveVoicePhone.getVoiceMailNumber();
    }

     /** @hide */
    public int getVoiceMessageCount(){
        return mActiveVoicePhone.getVoiceMessageCount();
    }

    public String getVoiceMailAlphaTag() {
        return mActiveVoicePhone.getVoiceMailAlphaTag();
    }

    public void setVoiceMailNumber(String alphaTag,String voiceMailNumber,
            Message onComplete) {
        mActiveVoicePhone.setVoiceMailNumber(alphaTag, voiceMailNumber, onComplete);
    }

    public void getCallForwardingOption(int commandInterfaceCFReason,
            Message onComplete) {
        mActiveVoicePhone.getCallForwardingOption(commandInterfaceCFReason,
                onComplete);
    }

    public void setCallForwardingOption(int commandInterfaceCFReason,
            int commandInterfaceCFAction, String dialingNumber,
            int timerSeconds, Message onComplete) {
        mActiveVoicePhone.setCallForwardingOption(commandInterfaceCFReason,
            commandInterfaceCFAction, dialingNumber, timerSeconds, onComplete);
    }

    public void getOutgoingCallerIdDisplay(Message onComplete) {
        mActiveVoicePhone.getOutgoingCallerIdDisplay(onComplete);
    }

    public void setOutgoingCallerIdDisplay(int commandInterfaceCLIRMode,
            Message onComplete) {
        mActiveVoicePhone.setOutgoingCallerIdDisplay(commandInterfaceCLIRMode,
                onComplete);
    }

    public void getCallWaiting(Message onComplete) {
        mActiveVoicePhone.getCallWaiting(onComplete);
    }

    public void setCallWaiting(boolean enable, Message onComplete) {
        mActiveVoicePhone.setCallWaiting(enable, onComplete);
    }

    public void getAvailableNetworks(Message response) {
        mActiveVoicePhone.getAvailableNetworks(response);
    }

    public void setNetworkSelectionModeAutomatic(Message response) {
        mActiveVoicePhone.setNetworkSelectionModeAutomatic(response);
    }

    public void selectNetworkManually(NetworkInfo network, Message response) {
        mActiveVoicePhone.selectNetworkManually(network, response);
    }

    public void setPreferredNetworkType(int networkType, Message response) {
        mActiveVoicePhone.setPreferredNetworkType(networkType, response);
    }

    public void getPreferredNetworkType(Message response) {
        mActiveVoicePhone.getPreferredNetworkType(response);
    }

    public void getNeighboringCids(Message response) {
        mActiveVoicePhone.getNeighboringCids(response);
    }

    public void setOnPostDialCharacter(Handler h, int what, Object obj) {
        mActiveVoicePhone.setOnPostDialCharacter(h, what, obj);
    }

    public void setMute(boolean muted) {
        mActiveVoicePhone.setMute(muted);
    }

    public boolean getMute() {
        return mActiveVoicePhone.getMute();
    }

    public void setEchoSuppressionEnabled(boolean enabled) {
        mActiveVoicePhone.setEchoSuppressionEnabled(enabled);
    }

    public void invokeOemRilRequestRaw(byte[] data, Message response) {
        mActiveVoicePhone.invokeOemRilRequestRaw(data, response);
    }

    public void invokeOemRilRequestStrings(String[] strings, Message response) {
        mActiveVoicePhone.invokeOemRilRequestStrings(strings, response);
    }

    public void setOnUnsolOemHookExtApp(Handler h, int what, Object obj) {
        mActiveVoicePhone.setOnUnsolOemHookExtApp(h, what, obj);
    }

    public void unSetOnUnsolOemHookExtApp(Handler h) {
        mActiveVoicePhone.unSetOnUnsolOemHookExtApp(h);
    }

    public void registerForCallReestablishInd(Handler h, int what, Object obj) {
        mActiveVoicePhone.registerForCallReestablishInd(h, what, obj);
    }

    public void unregisterForCallReestablishInd(Handler h) {
        mActiveVoicePhone.unregisterForCallReestablishInd(h);
    }

    public void updateServiceLocation() {
        mActiveVoicePhone.updateServiceLocation();
    }

    public void enableLocationUpdates() {
        mActiveVoicePhone.enableLocationUpdates();
    }

    public void disableLocationUpdates() {
        mActiveVoicePhone.disableLocationUpdates();
    }

    public void setUnitTestMode(boolean f) {
        mActiveVoicePhone.setUnitTestMode(f);
    }

    public boolean getUnitTestMode() {
        return mActiveVoicePhone.getUnitTestMode();
    }

    public void setBandMode(int bandMode, Message response) {
        mActiveVoicePhone.setBandMode(bandMode, response);
    }

    public void queryAvailableBandMode(Message response) {
        mActiveVoicePhone.queryAvailableBandMode(response);
    }

    public void queryCdmaRoamingPreference(Message response) {
        mActiveVoicePhone.queryCdmaRoamingPreference(response);
    }

    public void setCdmaRoamingPreference(int cdmaRoamingType, Message response) {
        mActiveVoicePhone.setCdmaRoamingPreference(cdmaRoamingType, response);
    }

    public void setCdmaSubscription(int cdmaSubscriptionType, Message response) {
        mActiveVoicePhone.setCdmaSubscription(cdmaSubscriptionType, response);
    }

    public SimulatedRadioControl getSimulatedRadioControl() {
        return mActiveVoicePhone.getSimulatedRadioControl();
    }

    public String getDeviceId() {
        return mActiveVoicePhone.getDeviceId();
    }

    public String getDeviceSvn() {
        return mActiveVoicePhone.getDeviceSvn();
    }

    public String getSubscriberId() {
        return mActiveVoicePhone.getSubscriberId();
    }

    public String getIccSerialNumber() {
        return mActiveVoicePhone.getIccSerialNumber();
    }

    public String getEsn() {
        return mActiveVoicePhone.getEsn();
    }

    public String getMeid() {
        return mActiveVoicePhone.getMeid();
    }

    public PhoneSubInfo getPhoneSubInfo(){
        return mActiveVoicePhone.getPhoneSubInfo();
    }

    public IccSmsInterfaceManager getIccSmsInterfaceManager(){
        return mActiveVoicePhone.getIccSmsInterfaceManager();
    }

    public IccPhoneBookInterfaceManager getIccPhoneBookInterfaceManager(){
        return mActiveVoicePhone.getIccPhoneBookInterfaceManager();
    }

    public void setTTYMode(int ttyMode, Message onComplete) {
        mActiveVoicePhone.setTTYMode(ttyMode, onComplete);
    }

    public void queryTTYMode(Message onComplete) {
        mActiveVoicePhone.queryTTYMode(onComplete);
    }

    public void activateCellBroadcastSms(int activate, Message response) {
        mActiveVoicePhone.activateCellBroadcastSms(activate, response);
    }

    public void getCellBroadcastSmsConfig(Message response) {
        mActiveVoicePhone.getCellBroadcastSmsConfig(response);
    }

    public void setCellBroadcastSmsConfig(int[] configValuesArray, Message response) {
        mActiveVoicePhone.setCellBroadcastSmsConfig(configValuesArray, response);
    }

    public void getSmscAddress(Message result) {
        mActiveVoicePhone.getSmscAddress(result);
    }

    public void setSmscAddress(String address, Message result) {
        mActiveVoicePhone.setSmscAddress(address, result);
    }

    public int getCdmaEriIconIndex() {
         return mActiveVoicePhone.getCdmaEriIconIndex();
    }

     public String getCdmaEriText() {
         return mActiveVoicePhone.getCdmaEriText();
     }

    public int getCdmaEriIconMode() {
         return mActiveVoicePhone.getCdmaEriIconMode();
    }

    public void sendBurstDtmf(String dtmfString, int on, int off, Message onComplete){
        mActiveVoicePhone.sendBurstDtmf(dtmfString, on, off, onComplete);
    }

    public void exitEmergencyCallbackMode(){
        mActiveVoicePhone.exitEmergencyCallbackMode();
    }

    public boolean isOtaSpNumber(String dialStr){
        return mActiveVoicePhone.isOtaSpNumber(dialStr);
    }

    public void registerForCallWaiting(Handler h, int what, Object obj){
        mActiveVoicePhone.registerForCallWaiting(h,what,obj);
    }

    public void unregisterForCallWaiting(Handler h){
        mActiveVoicePhone.unregisterForCallWaiting(h);
    }

    public void registerForSignalInfo(Handler h, int what, Object obj) {
        mActiveVoicePhone.registerForSignalInfo(h,what,obj);
    }

    public void unregisterForSignalInfo(Handler h) {
        mActiveVoicePhone.unregisterForSignalInfo(h);
    }

    public void registerForDisplayInfo(Handler h, int what, Object obj) {
        mActiveVoicePhone.registerForDisplayInfo(h,what,obj);
    }

    public void unregisterForDisplayInfo(Handler h) {
        mActiveVoicePhone.unregisterForDisplayInfo(h);
    }

    public void registerForNumberInfo(Handler h, int what, Object obj) {
        mActiveVoicePhone.registerForNumberInfo(h, what, obj);
    }

    public void unregisterForNumberInfo(Handler h) {
        mActiveVoicePhone.unregisterForNumberInfo(h);
    }

    public void registerForRedirectedNumberInfo(Handler h, int what, Object obj) {
        mActiveVoicePhone.registerForRedirectedNumberInfo(h, what, obj);
    }

    public void unregisterForRedirectedNumberInfo(Handler h) {
        mActiveVoicePhone.unregisterForRedirectedNumberInfo(h);
    }

    public void registerForLineControlInfo(Handler h, int what, Object obj) {
        mActiveVoicePhone.registerForLineControlInfo( h, what, obj);
    }

    public void unregisterForLineControlInfo(Handler h) {
        mActiveVoicePhone.unregisterForLineControlInfo(h);
    }

    public void registerFoT53ClirlInfo(Handler h, int what, Object obj) {
        mActiveVoicePhone.registerFoT53ClirlInfo(h, what, obj);
    }

    public void unregisterForT53ClirInfo(Handler h) {
        mActiveVoicePhone.unregisterForT53ClirInfo(h);
    }

    public void registerForT53AudioControlInfo(Handler h, int what, Object obj) {
        mActiveVoicePhone.registerForT53AudioControlInfo( h, what, obj);
    }

    public void unregisterForT53AudioControlInfo(Handler h) {
        mActiveVoicePhone.unregisterForT53AudioControlInfo(h);
    }

    public void setOnEcbModeExitResponse(Handler h, int what, Object obj){
        mActiveVoicePhone.setOnEcbModeExitResponse(h,what,obj);
    }

    public void unsetOnEcbModeExitResponse(Handler h){
        mActiveVoicePhone.unsetOnEcbModeExitResponse(h);
    }

    public boolean isCspPlmnEnabled() {
        return mActiveVoicePhone.isCspPlmnEnabled();
    }

    public boolean isModemPowerSave() {
        return mActiveVoicePhone.isModemPowerSave();
    }

    public void invokeDepersonalization(String pin, int type, Message response) {
        mActiveVoicePhone.invokeDepersonalization(pin, type, response);
    }

    public int disableApnType(String apnType) {
        return mActiveDataPhone.disableApnType(apnType);
    }

    public boolean disableDataConnectivity() {
        return mActiveDataPhone.disableDataConnectivity();
    }

    public void disableDnsCheck(boolean b) {
        mActiveDataPhone.disableDnsCheck(b);
    }

    public int enableApnType(String apnType) {
        return mActiveDataPhone.enableApnType(apnType);
    }

    public boolean enableDataConnectivity() {
        return mActiveDataPhone.enableDataConnectivity();
    }

    public String getActiveApn() {
        return mActiveDataPhone.getActiveApn();
    }

    public String getActiveApn(String type, IPVersion ipv) {
        return mActiveDataPhone.getActiveApn(type, ipv);
    }

    public String[] getActiveApnTypes() {
        return mActiveDataPhone.getActiveApnTypes();
    }

    public List<DataConnection> getCurrentDataConnectionList() {
        return mActiveDataPhone.getCurrentDataConnectionList();
    }

    public DataActivityState getDataActivityState() {
        return mActiveDataPhone.getDataActivityState();
    }

    public void getDataCallList(Message response) {
        mActiveDataPhone.getDataCallList(response);
    }

    public DataState getDataConnectionState() {
        return mActiveDataPhone.getDataConnectionState();
    }

    public DataState getDataConnectionState(String type, IPVersion ipv) {
        return mActiveDataPhone.getDataConnectionState(type, ipv);
    }

    public boolean getDataRoamingEnabled() {
        return mActiveDataPhone.getDataRoamingEnabled();
    }

    public ServiceState getDataServiceState() {
        return mActiveDataPhone.getDataServiceState();
    }

    public String[] getDnsServers(String apnType) {
        return mActiveDataPhone.getDnsServers(apnType);
    }

    public String[] getDnsServers(String apnType, IPVersion ipv) {
        return mActiveDataPhone.getDnsServers(apnType, ipv);
    }

    public String getGateway(String apnType) {
        return mActiveDataPhone.getGateway(apnType);
    }

    public String getGateway(String apnType, IPVersion ipv) {
        return mActiveDataPhone.getGateway(apnType, ipv);
    }

    public String getInterfaceName(String apnType) {
        return mActiveDataPhone.getInterfaceName(apnType);
    }

    public String getInterfaceName(String apnType, IPVersion ipv) {
        return mActiveDataPhone.getInterfaceName(apnType, ipv);
    }

    public String getIpAddress(String apnType) {
        return mActiveDataPhone.getIpAddress(apnType);
    }

    public String getIpAddress(String apnType, IPVersion ipv) {
        return mActiveDataPhone.getIpAddress(apnType, ipv);
    }

    public boolean isDataConnectivityEnabled() {
        return mActiveDataPhone.isDataConnectivityEnabled();
    }

    public boolean isDataConnectivityPossible() {
        return mActiveDataPhone.isDataConnectivityPossible();
    }

    public boolean isDnsCheckDisabled() {
        return mActiveDataPhone.isDnsCheckDisabled();
    }

    public void notifyDataActivity() {
        mActiveDataPhone.notifyDataActivity();
    }

    public void setDataRoamingEnabled(boolean enable) {
        mActiveDataPhone.setDataRoamingEnabled(enable);
    }
}
