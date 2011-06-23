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


import android.app.ActivityManagerNative;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.telephony.CellLocation;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.util.Log;

import com.android.internal.telephony.CommandsInterface.RadioTechnologyFamily;
import com.android.internal.telephony.cdma.CDMAPhone;
import com.android.internal.telephony.gsm.GSMPhone;
import com.android.internal.telephony.gsm.NetworkInfo;
import com.android.internal.telephony.test.SimulatedRadioControl;

import java.util.List;

public class PhoneProxy extends Handler implements Phone {
    public final static Object lockForRadioTechnologyChange = new Object();

    private Phone mActivePhone;
    private CDMAPhone mCDMAPhone;
    private GSMPhone mGSMPhone;
    private CommandsInterface mCi;
    private IccSmsInterfaceManagerProxy mIccSmsInterfaceManagerProxy;
    private IccPhoneBookInterfaceManagerProxy mIccPhoneBookInterfaceManagerProxy;
    private PhoneSubInfoProxy mPhoneSubInfoProxy;

    private boolean mResetModemOnRadioTechnologyChange = false;
    private int mVoiceTechQueryContext = 0;

    private static final int EVENT_VOICE_RADIO_TECHNOLOGY_CHANGED = 1;
    private static final int EVENT_RADIO_ON = 2;
    private static final int EVENT_REQUEST_VOICE_RADIO_TECH_DONE = 3;

    private static final String LOG_TAG = "PHONE";

    //***** Class Methods
    public PhoneProxy(Phone phone) {
        mActivePhone = phone;
        if (mActivePhone.getPhoneType() == PHONE_TYPE_CDMA) {
            mCDMAPhone = (CDMAPhone) mActivePhone;
        } else if (mActivePhone.getPhoneType() == PHONE_TYPE_GSM) {
            mGSMPhone = (GSMPhone) mActivePhone;
        }
        mResetModemOnRadioTechnologyChange = SystemProperties.getBoolean(
                TelephonyProperties.PROPERTY_RESET_ON_RADIO_TECH_CHANGE, false);
        mIccSmsInterfaceManagerProxy = new IccSmsInterfaceManagerProxy(
                phone.getIccSmsInterfaceManager());
        mIccPhoneBookInterfaceManagerProxy = new IccPhoneBookInterfaceManagerProxy(
                phone.getIccPhoneBookInterfaceManager());
        mPhoneSubInfoProxy = new PhoneSubInfoProxy(phone.getPhoneSubInfo());
        mCi = ((PhoneBase)mActivePhone).mCM;

        mCi.registerForOn(this, EVENT_RADIO_ON, null);
        mCi.registerForVoiceRadioTechChanged(this, EVENT_VOICE_RADIO_TECHNOLOGY_CHANGED, null);
    }

    @Override
    public void handleMessage(Message msg) {
        switch(msg.what) {

        case EVENT_RADIO_ON:
        case EVENT_VOICE_RADIO_TECHNOLOGY_CHANGED:
            /* Proactively query voice radio technologies */
            mVoiceTechQueryContext++;
            mCi.getVoiceRadioTechnology(this.obtainMessage(
                            EVENT_REQUEST_VOICE_RADIO_TECH_DONE, mVoiceTechQueryContext));
            break;

        case EVENT_REQUEST_VOICE_RADIO_TECH_DONE:
                AsyncResult ar = (AsyncResult) msg.obj;

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
            Log.e(LOG_TAG,"Error! This handler was not registered for this message type. Message: "
                    + msg.what);
            break;
        }
        super.handleMessage(msg);
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

    private void updatePhoneObject(RadioTechnologyFamily newVoiceRadioTech) {

        if (mActivePhone != null
                && ((newVoiceRadioTech.isCdma() && mActivePhone.getPhoneType() == PHONE_TYPE_CDMA))
                || ((newVoiceRadioTech.isGsm() && mActivePhone.getPhoneType() == PHONE_TYPE_GSM))) {
            // Nothing changed. Keep phone as it is.
            Log.v(LOG_TAG, "Ignoring voice radio technology changed message. newVoiceRadioTech = "
                    + newVoiceRadioTech + "Active Phone = " + mActivePhone.getPhoneName());
            return;
        }

        if (newVoiceRadioTech.isUnknown()) {
            // We need some voice phone object to be active always, so never
            // delete the phone without anything to replace it with!
            Log.i(LOG_TAG,
                    "Ignoring voice radio technology changed message. newVoiceRadioTech = Unknown."
                            + "Active Phone = " + mActivePhone.getPhoneName());
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
        mIccSmsInterfaceManagerProxy.setmIccSmsInterfaceManager(
                mActivePhone.getIccSmsInterfaceManager());
        mIccPhoneBookInterfaceManagerProxy.setmIccPhoneBookInterfaceManager(mActivePhone
                .getIccPhoneBookInterfaceManager());
        mPhoneSubInfoProxy.setmPhoneSubInfo(this.mActivePhone.getPhoneSubInfo());

        mCi = ((PhoneBase)mActivePhone).mCM;

        // Send an Intent to the PhoneApp that we had a radio technology change
        Intent intent = new Intent(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(Phone.PHONE_NAME_KEY, mActivePhone.getPhoneName());
        ActivityManagerNative.broadcastStickyIntent(intent, null);

    }

    private void deleteAndCreatePhone(RadioTechnologyFamily newVoiceRadioTech) {

        String mOutgoingPhoneName = "Unknown";

        if (mActivePhone != null) {
            mOutgoingPhoneName = ((PhoneBase) mActivePhone).getPhoneName();
        }

        Log.i(LOG_TAG, "Switching Voice Phone : " + mOutgoingPhoneName + " >>> "
                + (newVoiceRadioTech.isGsm() ? "GSM" : "CDMA"));

        if (mActivePhone != null) {
            Log.v(LOG_TAG, "Disposing old phone..");
            if (mActivePhone instanceof GSMPhone) {
                ((GSMPhone) mActivePhone).dispose();
            } else if (mActivePhone instanceof CDMAPhone) {
                ((CDMAPhone) mActivePhone).dispose();
            }
        }

        Phone oldPhone = mActivePhone;

        // Give the garbage collector a hint to start the garbage collection
        // asap NOTE this has been disabled since radio technology change could
        // happen during e.g. a multimedia playing and could slow the system.
        // Tests needs to be done to see the effects of the GC call here when
        // system is busy.
        // System.gc();

        if (newVoiceRadioTech.isCdma()) {
            if (mCDMAPhone != null) {
                mActivePhone = mCDMAPhone;
            } else {
                mActivePhone = PhoneFactory.getCdmaPhone();
                mCDMAPhone = (CDMAPhone) mActivePhone;
            }
            if (null != oldPhone) {
                //((GSMPhone) oldPhone).removeReferences();
            }
        } else if (newVoiceRadioTech.isGsm()) {
            if (mGSMPhone != null) {
                mActivePhone = mGSMPhone;
            } else {
                mActivePhone = PhoneFactory.getGsmPhone();
            }
            if (null != oldPhone) {
                //((CDMAPhone) oldPhone).removeReferences();
            }
        }

        oldPhone = null;
    }

    public ServiceState getServiceState() {
        return mActivePhone.getServiceState();
    }

    public CellLocation getCellLocation() {
        return mActivePhone.getCellLocation();
    }

    public DataState getDataConnectionState() {
        return mActivePhone.getDataConnectionState();
    }

    public DataActivityState getDataActivityState() {
        return mActivePhone.getDataActivityState();
    }

    public Context getContext() {
        return mActivePhone.getContext();
    }

    public void disableDnsCheck(boolean b) {
        mActivePhone.disableDnsCheck(b);
    }

    public boolean isDnsCheckDisabled() {
        return mActivePhone.isDnsCheckDisabled();
    }

    public State getState() {
        return mActivePhone.getState();
    }

    public String getPhoneName() {
        return mActivePhone.getPhoneName();
    }

    public int getPhoneType() {
        return mActivePhone.getPhoneType();
    }

    public String[] getActiveApnTypes() {
        return mActivePhone.getActiveApnTypes();
    }

    public String getActiveApn() {
        return mActivePhone.getActiveApn();
    }

    public SignalStrength getSignalStrength() {
        return mActivePhone.getSignalStrength();
    }

    public void registerForUnknownConnection(Handler h, int what, Object obj) {
        mActivePhone.registerForUnknownConnection(h, what, obj);
    }

    public void unregisterForUnknownConnection(Handler h) {
        mActivePhone.unregisterForUnknownConnection(h);
    }

    public void registerForPreciseCallStateChanged(Handler h, int what, Object obj) {
        mActivePhone.registerForPreciseCallStateChanged(h, what, obj);
    }

    public void unregisterForPreciseCallStateChanged(Handler h) {
        mActivePhone.unregisterForPreciseCallStateChanged(h);
    }

    public void registerForNewRingingConnection(Handler h, int what, Object obj) {
        mActivePhone.registerForNewRingingConnection(h, what, obj);
    }

    public void unregisterForNewRingingConnection(Handler h) {
        mActivePhone.unregisterForNewRingingConnection(h);
    }

    public void registerForIncomingRing(Handler h, int what, Object obj) {
        mActivePhone.registerForIncomingRing(h, what, obj);
    }

    public void unregisterForIncomingRing(Handler h) {
        mActivePhone.unregisterForIncomingRing(h);
    }

    public void registerForDisconnect(Handler h, int what, Object obj) {
        mActivePhone.registerForDisconnect(h, what, obj);
    }

    public void unregisterForDisconnect(Handler h) {
        mActivePhone.unregisterForDisconnect(h);
    }

    public void registerForMmiInitiate(Handler h, int what, Object obj) {
        mActivePhone.registerForMmiInitiate(h, what, obj);
    }

    public void unregisterForMmiInitiate(Handler h) {
        mActivePhone.unregisterForMmiInitiate(h);
    }

    public void registerForMmiComplete(Handler h, int what, Object obj) {
        mActivePhone.registerForMmiComplete(h, what, obj);
    }

    public void unregisterForMmiComplete(Handler h) {
        mActivePhone.unregisterForMmiComplete(h);
    }

    public List<? extends MmiCode> getPendingMmiCodes() {
        return mActivePhone.getPendingMmiCodes();
    }

    public void sendUssdResponse(String ussdMessge) {
        mActivePhone.sendUssdResponse(ussdMessge);
    }

    public void registerForServiceStateChanged(Handler h, int what, Object obj) {
        mActivePhone.registerForServiceStateChanged(h, what, obj);
    }

    public void unregisterForServiceStateChanged(Handler h) {
        mActivePhone.unregisterForServiceStateChanged(h);
    }

    public void registerForSuppServiceNotification(Handler h, int what, Object obj) {
        mActivePhone.registerForSuppServiceNotification(h, what, obj);
    }

    public void unregisterForSuppServiceNotification(Handler h) {
        mActivePhone.unregisterForSuppServiceNotification(h);
    }

    public void registerForSuppServiceFailed(Handler h, int what, Object obj) {
        mActivePhone.registerForSuppServiceFailed(h, what, obj);
    }

    public void unregisterForSuppServiceFailed(Handler h) {
        mActivePhone.unregisterForSuppServiceFailed(h);
    }

    public void registerForInCallVoicePrivacyOn(Handler h, int what, Object obj){
        mActivePhone.registerForInCallVoicePrivacyOn(h,what,obj);
    }

    public void unregisterForInCallVoicePrivacyOn(Handler h){
        mActivePhone.unregisterForInCallVoicePrivacyOn(h);
    }

    public void registerForInCallVoicePrivacyOff(Handler h, int what, Object obj){
        mActivePhone.registerForInCallVoicePrivacyOff(h,what,obj);
    }

    public void unregisterForInCallVoicePrivacyOff(Handler h){
        mActivePhone.unregisterForInCallVoicePrivacyOff(h);
    }

    public void registerForCdmaOtaStatusChange(Handler h, int what, Object obj) {
        mActivePhone.registerForCdmaOtaStatusChange(h,what,obj);
    }

    public void unregisterForCdmaOtaStatusChange(Handler h) {
         mActivePhone.unregisterForCdmaOtaStatusChange(h);
    }

    public void registerForSubscriptionInfoReady(Handler h, int what, Object obj) {
        mActivePhone.registerForSubscriptionInfoReady(h, what, obj);
    }

    public void unregisterForSubscriptionInfoReady(Handler h) {
        mActivePhone.unregisterForSubscriptionInfoReady(h);
    }

    public void registerForEcmTimerReset(Handler h, int what, Object obj) {
        mActivePhone.registerForEcmTimerReset(h,what,obj);
    }

    public void unregisterForEcmTimerReset(Handler h) {
        mActivePhone.unregisterForEcmTimerReset(h);
    }

    public void registerForRingbackTone(Handler h, int what, Object obj) {
        mActivePhone.registerForRingbackTone(h,what,obj);
    }

    public void unregisterForRingbackTone(Handler h) {
        mActivePhone.unregisterForRingbackTone(h);
    }

    public void registerForResendIncallMute(Handler h, int what, Object obj) {
        mActivePhone.registerForResendIncallMute(h,what,obj);
    }

    public void unregisterForResendIncallMute(Handler h) {
        mActivePhone.unregisterForResendIncallMute(h);
    }

    public boolean getIccRecordsLoaded() {
        return mActivePhone.getIccRecordsLoaded();
    }

    public IccCard getIccCard() {
        return mActivePhone.getIccCard();
    }

    public void acceptCall() throws CallStateException {
        mActivePhone.acceptCall();
    }

    public void rejectCall() throws CallStateException {
        mActivePhone.rejectCall();
    }

    public void switchHoldingAndActive() throws CallStateException {
        mActivePhone.switchHoldingAndActive();
    }

    public boolean canConference() {
        return mActivePhone.canConference();
    }

    public void conference() throws CallStateException {
        mActivePhone.conference();
    }

    public void enableEnhancedVoicePrivacy(boolean enable, Message onComplete) {
        mActivePhone.enableEnhancedVoicePrivacy(enable, onComplete);
    }

    public void getEnhancedVoicePrivacy(Message onComplete) {
        mActivePhone.getEnhancedVoicePrivacy(onComplete);
    }

    public boolean canTransfer() {
        return mActivePhone.canTransfer();
    }

    public void explicitCallTransfer() throws CallStateException {
        mActivePhone.explicitCallTransfer();
    }

    public void clearDisconnected() {
        mActivePhone.clearDisconnected();
    }

    public Call getForegroundCall() {
        return mActivePhone.getForegroundCall();
    }

    public Call getBackgroundCall() {
        return mActivePhone.getBackgroundCall();
    }

    public Call getRingingCall() {
        return mActivePhone.getRingingCall();
    }

    public Connection dial(String dialString) throws CallStateException {
        return mActivePhone.dial(dialString);
    }

    public Connection dial(String dialString, UUSInfo uusInfo) throws CallStateException {
        return mActivePhone.dial(dialString, uusInfo);
    }

    public boolean handlePinMmi(String dialString) {
        return mActivePhone.handlePinMmi(dialString);
    }

    public boolean handleInCallMmiCommands(String command) throws CallStateException {
        return mActivePhone.handleInCallMmiCommands(command);
    }

    public void sendDtmf(char c) {
        mActivePhone.sendDtmf(c);
    }

    public void startDtmf(char c) {
        mActivePhone.startDtmf(c);
    }

    public void stopDtmf() {
        mActivePhone.stopDtmf();
    }

    public void setRadioPower(boolean power) {
        mActivePhone.setRadioPower(power);
    }

    public boolean getMessageWaitingIndicator() {
        return mActivePhone.getMessageWaitingIndicator();
    }

    public boolean getCallForwardingIndicator() {
        return mActivePhone.getCallForwardingIndicator();
    }

    public String getLine1Number() {
        return mActivePhone.getLine1Number();
    }

    public String getCdmaMin() {
        return mActivePhone.getCdmaMin();
    }

    public boolean isMinInfoReady() {
        return mActivePhone.isMinInfoReady();
    }

    public String getCdmaPrlVersion() {
        return mActivePhone.getCdmaPrlVersion();
    }

    public String getLine1AlphaTag() {
        return mActivePhone.getLine1AlphaTag();
    }

    public void setLine1Number(String alphaTag, String number, Message onComplete) {
        mActivePhone.setLine1Number(alphaTag, number, onComplete);
    }

    public String getVoiceMailNumber() {
        return mActivePhone.getVoiceMailNumber();
    }

     /** @hide */
    public int getVoiceMessageCount(){
        return mActivePhone.getVoiceMessageCount();
    }

    public String getVoiceMailAlphaTag() {
        return mActivePhone.getVoiceMailAlphaTag();
    }

    public void setVoiceMailNumber(String alphaTag,String voiceMailNumber,
            Message onComplete) {
        mActivePhone.setVoiceMailNumber(alphaTag, voiceMailNumber, onComplete);
    }

    public void getCallForwardingOption(int commandInterfaceCFReason,
            Message onComplete) {
        mActivePhone.getCallForwardingOption(commandInterfaceCFReason,
                onComplete);
    }

    public void setCallForwardingOption(int commandInterfaceCFReason,
            int commandInterfaceCFAction, String dialingNumber,
            int timerSeconds, Message onComplete) {
        mActivePhone.setCallForwardingOption(commandInterfaceCFReason,
            commandInterfaceCFAction, dialingNumber, timerSeconds, onComplete);
    }

    public void getOutgoingCallerIdDisplay(Message onComplete) {
        mActivePhone.getOutgoingCallerIdDisplay(onComplete);
    }

    public void setOutgoingCallerIdDisplay(int commandInterfaceCLIRMode,
            Message onComplete) {
        mActivePhone.setOutgoingCallerIdDisplay(commandInterfaceCLIRMode,
                onComplete);
    }

    public void getCallWaiting(Message onComplete) {
        mActivePhone.getCallWaiting(onComplete);
    }

    public void setCallWaiting(boolean enable, Message onComplete) {
        mActivePhone.setCallWaiting(enable, onComplete);
    }

    public void getAvailableNetworks(Message response) {
        mActivePhone.getAvailableNetworks(response);
    }

    public void setNetworkSelectionModeAutomatic(Message response) {
        mActivePhone.setNetworkSelectionModeAutomatic(response);
    }

    public void selectNetworkManually(NetworkInfo network, Message response) {
        mActivePhone.selectNetworkManually(network, response);
    }

    public void setPreferredNetworkType(int networkType, Message response) {
        mActivePhone.setPreferredNetworkType(networkType, response);
    }

    public void getPreferredNetworkType(Message response) {
        mActivePhone.getPreferredNetworkType(response);
    }

    public void getNeighboringCids(Message response) {
        mActivePhone.getNeighboringCids(response);
    }

    public void setOnPostDialCharacter(Handler h, int what, Object obj) {
        mActivePhone.setOnPostDialCharacter(h, what, obj);
    }

    public void setMute(boolean muted) {
        mActivePhone.setMute(muted);
    }

    public boolean getMute() {
        return mActivePhone.getMute();
    }

    public void setEchoSuppressionEnabled(boolean enabled) {
        mActivePhone.setEchoSuppressionEnabled(enabled);
    }

    public void invokeOemRilRequestRaw(byte[] data, Message response) {
        mActivePhone.invokeOemRilRequestRaw(data, response);
    }

    public void invokeOemRilRequestStrings(String[] strings, Message response) {
        mActivePhone.invokeOemRilRequestStrings(strings, response);
    }

    public void setOnUnsolOemHookExtApp(Handler h, int what, Object obj) {
        mActivePhone.setOnUnsolOemHookExtApp(h, what, obj);
    }

    public void unSetOnUnsolOemHookExtApp(Handler h) {
        mActiveVoicePhone.unSetOnUnsolOemHookExtApp(h);
    }

    public void getDataCallList(Message response) {
        mActivePhone.getDataCallList(response);
    }

    public List<DataConnection> getCurrentDataConnectionList() {
        return mActivePhone.getCurrentDataConnectionList();
    }

    public void updateServiceLocation() {
        mActivePhone.updateServiceLocation();
    }

    public void enableLocationUpdates() {
        mActivePhone.enableLocationUpdates();
    }

    public void disableLocationUpdates() {
        mActivePhone.disableLocationUpdates();
    }

    public void setUnitTestMode(boolean f) {
        mActivePhone.setUnitTestMode(f);
    }

    public boolean getUnitTestMode() {
        return mActivePhone.getUnitTestMode();
    }

    public void setBandMode(int bandMode, Message response) {
        mActivePhone.setBandMode(bandMode, response);
    }

    public void queryAvailableBandMode(Message response) {
        mActivePhone.queryAvailableBandMode(response);
    }

    public boolean getDataRoamingEnabled() {
        return mActivePhone.getDataRoamingEnabled();
    }

    public void setDataRoamingEnabled(boolean enable) {
        mActivePhone.setDataRoamingEnabled(enable);
    }

    public void queryCdmaRoamingPreference(Message response) {
        mActivePhone.queryCdmaRoamingPreference(response);
    }

    public void setCdmaRoamingPreference(int cdmaRoamingType, Message response) {
        mActivePhone.setCdmaRoamingPreference(cdmaRoamingType, response);
    }

    public void setCdmaSubscription(int cdmaSubscriptionType, Message response) {
        mActivePhone.setCdmaSubscription(cdmaSubscriptionType, response);
    }

    public SimulatedRadioControl getSimulatedRadioControl() {
        return mActivePhone.getSimulatedRadioControl();
    }

    public boolean enableDataConnectivity() {
        return mActivePhone.enableDataConnectivity();
    }

    public boolean disableDataConnectivity() {
        return mActivePhone.disableDataConnectivity();
    }

    public int enableApnType(String type) {
        return mActivePhone.enableApnType(type);
    }

    public int disableApnType(String type) {
        return mActivePhone.disableApnType(type);
    }

    public boolean isDataConnectivityEnabled() {
        return mActivePhone.isDataConnectivityEnabled();
    }

    public boolean isDataConnectivityPossible() {
        return mActivePhone.isDataConnectivityPossible();
    }

    public String getInterfaceName(String apnType) {
        return mActivePhone.getInterfaceName(apnType);
    }

    public String getIpAddress(String apnType) {
        return mActivePhone.getIpAddress(apnType);
    }

    public String getGateway(String apnType) {
        return mActivePhone.getGateway(apnType);
    }

    public String[] getDnsServers(String apnType) {
        return mActivePhone.getDnsServers(apnType);
    }

    public String getDeviceId() {
        return mActivePhone.getDeviceId();
    }

    public String getDeviceSvn() {
        return mActivePhone.getDeviceSvn();
    }

    public String getSubscriberId() {
        return mActivePhone.getSubscriberId();
    }

    public String getIccSerialNumber() {
        return mActivePhone.getIccSerialNumber();
    }

    public String getEsn() {
        return mActivePhone.getEsn();
    }

    public String getMeid() {
        return mActivePhone.getMeid();
    }

    public PhoneSubInfo getPhoneSubInfo(){
        return mActivePhone.getPhoneSubInfo();
    }

    public IccSmsInterfaceManager getIccSmsInterfaceManager(){
        return mActivePhone.getIccSmsInterfaceManager();
    }

    public IccPhoneBookInterfaceManager getIccPhoneBookInterfaceManager(){
        return mActivePhone.getIccPhoneBookInterfaceManager();
    }

    public void setTTYMode(int ttyMode, Message onComplete) {
        mActivePhone.setTTYMode(ttyMode, onComplete);
    }

    public void queryTTYMode(Message onComplete) {
        mActivePhone.queryTTYMode(onComplete);
    }

    public void activateCellBroadcastSms(int activate, Message response) {
        mActivePhone.activateCellBroadcastSms(activate, response);
    }

    public void getCellBroadcastSmsConfig(Message response) {
        mActivePhone.getCellBroadcastSmsConfig(response);
    }

    public void setCellBroadcastSmsConfig(int[] configValuesArray, Message response) {
        mActivePhone.setCellBroadcastSmsConfig(configValuesArray, response);
    }

    public void notifyDataActivity() {
         mActivePhone.notifyDataActivity();
    }

    public void getSmscAddress(Message result) {
        mActivePhone.getSmscAddress(result);
    }

    public void setSmscAddress(String address, Message result) {
        mActivePhone.setSmscAddress(address, result);
    }

    public int getCdmaEriIconIndex() {
         return mActivePhone.getCdmaEriIconIndex();
    }

     public String getCdmaEriText() {
         return mActivePhone.getCdmaEriText();
     }

    public int getCdmaEriIconMode() {
         return mActivePhone.getCdmaEriIconMode();
    }

    public void sendBurstDtmf(String dtmfString, int on, int off, Message onComplete){
        mActivePhone.sendBurstDtmf(dtmfString, on, off, onComplete);
    }

    public void exitEmergencyCallbackMode(){
        mActivePhone.exitEmergencyCallbackMode();
    }

    public boolean isOtaSpNumber(String dialStr){
        return mActivePhone.isOtaSpNumber(dialStr);
    }

    public void registerForCallWaiting(Handler h, int what, Object obj){
        mActivePhone.registerForCallWaiting(h,what,obj);
    }

    public void unregisterForCallWaiting(Handler h){
        mActivePhone.unregisterForCallWaiting(h);
    }

    public void registerForSignalInfo(Handler h, int what, Object obj) {
        mActivePhone.registerForSignalInfo(h,what,obj);
    }

    public void unregisterForSignalInfo(Handler h) {
        mActivePhone.unregisterForSignalInfo(h);
    }

    public void registerForDisplayInfo(Handler h, int what, Object obj) {
        mActivePhone.registerForDisplayInfo(h,what,obj);
    }

    public void unregisterForDisplayInfo(Handler h) {
        mActivePhone.unregisterForDisplayInfo(h);
    }

    public void registerForNumberInfo(Handler h, int what, Object obj) {
        mActivePhone.registerForNumberInfo(h, what, obj);
    }

    public void unregisterForNumberInfo(Handler h) {
        mActivePhone.unregisterForNumberInfo(h);
    }

    public void registerForRedirectedNumberInfo(Handler h, int what, Object obj) {
        mActivePhone.registerForRedirectedNumberInfo(h, what, obj);
    }

    public void unregisterForRedirectedNumberInfo(Handler h) {
        mActivePhone.unregisterForRedirectedNumberInfo(h);
    }

    public void registerForLineControlInfo(Handler h, int what, Object obj) {
        mActivePhone.registerForLineControlInfo( h, what, obj);
    }

    public void unregisterForLineControlInfo(Handler h) {
        mActivePhone.unregisterForLineControlInfo(h);
    }

    public void registerFoT53ClirlInfo(Handler h, int what, Object obj) {
        mActivePhone.registerFoT53ClirlInfo(h, what, obj);
    }

    public void unregisterForT53ClirInfo(Handler h) {
        mActivePhone.unregisterForT53ClirInfo(h);
    }

    public void registerForT53AudioControlInfo(Handler h, int what, Object obj) {
        mActivePhone.registerForT53AudioControlInfo( h, what, obj);
    }

    public void unregisterForT53AudioControlInfo(Handler h) {
        mActivePhone.unregisterForT53AudioControlInfo(h);
    }

    public void setOnEcbModeExitResponse(Handler h, int what, Object obj){
        mActivePhone.setOnEcbModeExitResponse(h,what,obj);
    }

    public void unsetOnEcbModeExitResponse(Handler h){
        mActivePhone.unsetOnEcbModeExitResponse(h);
    }

    public boolean isCspPlmnEnabled() {
        return mActivePhone.isCspPlmnEnabled();
    }

    public void invokeDepersonalization(String pin, int type, Message response) {
        mActivePhone.invokeDepersonalization(pin, type, response);
    }

    public boolean isModemPowerSave() {
        return mActivePhone.isModemPowerSave();
    }
}
