/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.os.Handler;
import android.os.Message;

/**
 * {@hide}
 */

public interface IccCard {
    /* The extra data for broacasting intent INTENT_ICC_STATE_CHANGE */
    static public final String INTENT_KEY_ICC_STATE = "ss";
    /* NOT_READY means the ICC interface is not ready (eg, radio is off or powering on) */
    static public final String INTENT_VALUE_ICC_NOT_READY = "NOT_READY";
    /* ABSENT means ICC is missing */
    static public final String INTENT_VALUE_ICC_ABSENT = "ABSENT";
    /* CARD_IO_ERROR means for three consecutive times there was SIM IO error */
    static public final String INTENT_VALUE_ICC_CARD_IO_ERROR = "CARD_IO_ERROR";
    /* LOCKED means ICC is locked by pin or by network */
    static public final String INTENT_VALUE_ICC_LOCKED = "LOCKED";
    /* READY means ICC is ready to access */
    static public final String INTENT_VALUE_ICC_READY = "READY";
    /* IMSI means ICC IMSI is ready in property */
    static public final String INTENT_VALUE_ICC_IMSI = "IMSI";
    /* LOADED means all ICC records, including IMSI, are loaded */
    static public final String INTENT_VALUE_ICC_LOADED = "LOADED";
    /* The extra data for broacasting intent INTENT_ICC_STATE_CHANGE */
    static public final String INTENT_KEY_LOCKED_REASON = "reason";
    /* PIN means ICC is locked on PIN1 */
    static public final String INTENT_VALUE_LOCKED_ON_PIN = "PIN";
    /* PUK means ICC is locked on PUK1 */
    static public final String INTENT_VALUE_LOCKED_ON_PUK = "PUK";
    /* NETWORK means ICC is locked on NETWORK PERSONALIZATION */
    static public final String INTENT_VALUE_LOCKED_NETWORK = "SIM NETWORK";
    /* NETWORK SUBSET means ICC is locked on NETWORK SUBSET PERSONALIZATION */
    static public final String INTENT_VALUE_LOCKED_NETWORK_SUBSET = "SIM NETWORK SUBSET";
    /* CORPORATE means ICC is locked on CORPORATE PERSONALIZATION */
    static public final String INTENT_VALUE_LOCKED_CORPORATE = "SIM CORPORATE";
    /* SERVICE PROVIDER means ICC is locked on SERVICE PROVIDER PERSONALIZATION */
    static public final String INTENT_VALUE_LOCKED_SERVICE_PROVIDER = "SIM SERVICE PROVIDER";
    /* SIM means ICC is locked on SIM PERSONALIZATION */
    static public final String INTENT_VALUE_LOCKED_SIM = "SIM SIM";
    /* RUIM NETWORK1 means ICC is locked on RUIM NETWORK1 PERSONALIZATION */
    static public final String INTENT_VALUE_LOCKED_RUIM_NETWORK1 = "RUIM NETWORK1";
    /* RUIM NETWORK2 means ICC is locked on RUIM NETWORK2 PERSONALIZATION */
    static public final String INTENT_VALUE_LOCKED_RUIM_NETWORK2 = "RUIM NETWORK2";
    /* RUIM HRPD means ICC is locked on RUIM HRPD PERSONALIZATION */
    static public final String INTENT_VALUE_LOCKED_RUIM_HRPD = "RUIM HRPD";
    /* RUIM CORPORATE means ICC is locked on RUIM CORPORATE PERSONALIZATION */
    static public final String INTENT_VALUE_LOCKED_RUIM_CORPORATE = "RUIM CORPORATE";
    /* RUIM SERVICE PROVIDER means ICC is locked on RUIM SERVICE PROVIDER PERSONALIZATION */
    static public final String INTENT_VALUE_LOCKED_RUIM_SERVICE_PROVIDER = "RUIM SERVICE PROVIDER";
    /* RUIM RUIM means ICC is locked on RUIM RUIM PERSONALIZATION */
    static public final String INTENT_VALUE_LOCKED_RUIM_RUIM = "RUIM RUIM";

    /*
      UNKNOWN is a transient state, for example, after user inputs ICC pin under
      PIN_REQUIRED state, the query for ICC status returns UNKNOWN before it
      turns to READY
     */
    public enum State {
        UNKNOWN,
        ABSENT,
        PIN_REQUIRED,
        PUK_REQUIRED,
        NETWORK_LOCKED,
        READY,
        CARD_IO_ERROR,
        SIM_NETWORK_SUBSET_LOCKED,
        SIM_CORPORATE_LOCKED,
        SIM_SERVICE_PROVIDER_LOCKED,
        SIM_SIM_LOCKED,
        RUIM_NETWORK1_LOCKED,
        RUIM_NETWORK2_LOCKED,
        RUIM_HRPD_LOCKED,
        RUIM_CORPORATE_LOCKED,
        RUIM_SERVICE_PROVIDER_LOCKED,
        RUIM_RUIM_LOCKED,
        NOT_READY;

        public boolean isPinLocked() {
            return ((this == PIN_REQUIRED) || (this == PUK_REQUIRED));
        }
    }

    public State getState();

    public void dispose();

    /**
     * Notifies handler of any transition into State.ABSENT
     */
    public void registerForAbsent(Handler h, int what, Object obj);

    public void unregisterForAbsent(Handler h);

    /**
     * Notifies handler of any transition into State.NETWORK_LOCKED
     */
    public void registerForNetworkLocked(Handler h, int what, Object obj);

    public void unregisterForNetworkLocked(Handler h);

    /**
     * Notifies handler of any transition into State.isPinLocked()
     */
    public void registerForLocked(Handler h, int what, Object obj);

    public void unregisterForLocked(Handler h);

    /**
     * Supply the ICC PIN to the ICC
     *
     * When the operation is complete, onComplete will be sent to its
     * Handler.
     *
     * onComplete.obj will be an AsyncResult
     *
     * ((AsyncResult)onComplete.obj).exception == null on success
     * ((AsyncResult)onComplete.obj).exception != null on fail
     *
     * If the supplied PIN is incorrect:
     * ((AsyncResult)onComplete.obj).exception != null
     * && ((AsyncResult)onComplete.obj).exception
     *       instanceof com.android.internal.telephony.gsm.CommandException)
     * && ((CommandException)(((AsyncResult)onComplete.obj).exception))
     *          .getCommandError() == CommandException.Error.PASSWORD_INCORRECT
     *
     *
     */

    public void supplyPin(String pin, Message onComplete);

    public void supplyPuk(String puk, String newPin, Message onComplete);

    public void supplyPin2(String pin2, Message onComplete);

    public void supplyPuk2(String puk2, String newPin2, Message onComplete);

    public void supplyNetworkDepersonalization(String pin, Message onComplete);


    /**
     * Check whether ICC pin lock is enabled
     * This is a sync call which returns the cached pin enabled state
     *
     * @return true for ICC locked enabled
     *         false for ICC locked disabled
     */
    public boolean getIccLockEnabled();

    /**
     * Check whether ICC fdn (fixed dialing number) is enabled
     * This is a sync call which returns the cached pin enabled state
     *
     * @return true for ICC fdn enabled
     *         false for ICC fdn disabled
     */
    public boolean getIccFdnEnabled();

     /**
      * Set the ICC pin lock enabled or disabled
      * When the operation is complete, onComplete will be sent to its handler
      *
      * @param enabled "true" for locked "false" for unlocked.
      * @param password needed to change the ICC pin state, aka. Pin1
      * @param onComplete
      *        onComplete.obj will be an AsyncResult
      *        ((AsyncResult)onComplete.obj).exception == null on success
      *        ((AsyncResult)onComplete.obj).exception != null on fail
      */
    public void setIccLockEnabled(boolean enabled, String password, Message onComplete);

     /**
      * Set the ICC fdn enabled or disabled
      * When the operation is complete, onComplete will be sent to its handler
      *
      * @param enabled "true" for locked "false" for unlocked.
      * @param password needed to change the ICC fdn enable, aka Pin2
      * @param onComplete
      *        onComplete.obj will be an AsyncResult
      *        ((AsyncResult)onComplete.obj).exception == null on success
      *        ((AsyncResult)onComplete.obj).exception != null on fail
      */
    public void setIccFdnEnabled(boolean enabled, String password, Message onComplete);
     /**
      * Change the ICC password used in ICC pin lock
      * When the operation is complete, onComplete will be sent to its handler
      *
      * @param oldPassword is the old password
      * @param newPassword is the new password
      * @param onComplete
      *        onComplete.obj will be an AsyncResult
      *        ((AsyncResult)onComplete.obj).exception == null on success
      *        ((AsyncResult)onComplete.obj).exception != null on fail
      */
    public void changeIccLockPassword(String oldPassword, String newPassword, Message onComplete);
     /**
      * Change the ICC password used in ICC fdn enable
      * When the operation is complete, onComplete will be sent to its handler
      *
      * @param oldPassword is the old password
      * @param newPassword is the new password
      * @param onComplete
      *        onComplete.obj will be an AsyncResult
      *        ((AsyncResult)onComplete.obj).exception == null on success
      *        ((AsyncResult)onComplete.obj).exception != null on fail
      */
    public void changeIccFdnPassword(String oldPassword, String newPassword, Message onComplete);

    /**
     * Returns service provider name stored in ICC card.
     * If there is no service provider name associated or the record is not
     * yet available, null will be returned <p>
     *
     * Please use this value when display Service Provider Name in idle mode <p>
     *
     * Usage of this provider name in the UI is a common carrier requirement.
     *
     * Also available via Android property "gsm.sim.operator.alpha"
     *
     * @return Service Provider Name stored in ICC card
     *         null if no service provider name associated or the record is not
     *         yet available
     *
     */
    public String getServiceProviderName();

    public void broadcastIccStateChangedIntent(String value, String reason);

    public State getIccCardState();

    public boolean isApplicationOnIcc(UiccConstants.AppType type);

    /**
     * @return true if a ICC card is present
     */

    public boolean hasIccCard();
}
