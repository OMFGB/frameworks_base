/*
 * Copyright (c) 2010, Code Aurora Forum. All rights reserved.
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
import android.util.Log;

import com.android.internal.telephony.UiccConstants.AppState;
import com.android.internal.telephony.UiccConstants.AppType;
import com.android.internal.telephony.UiccConstants.PersoSubState;
import com.android.internal.telephony.UiccConstants.PinState;
import com.android.internal.telephony.cdma.RuimFileHandler;
import com.android.internal.telephony.cdma.RuimRecords;
import com.android.internal.telephony.gsm.SIMFileHandler;
import com.android.internal.telephony.gsm.SIMRecords;
/** This class will handle PIN, PUK, etc
 * Every user of this class will be registered for Unavailable with every
 * object it gets reference to. It is the user's responsibility to unregister
 * and remove reference to object, once UNAVAILABLE callback is received.
 */
public class UiccCardApplication {
    private static final int EVENT_PIN1PUK1_DONE = 1;
    private static final int EVENT_CHANGE_FACILITY_LOCK_DONE = 2;
    private static final int EVENT_CHANGE_PIN1_DONE = 3;
    private static final int EVENT_CHANGE_PIN2_DONE = 4;
    private static final int EVENT_QUERY_FACILITY_FDN_DONE = 5;
    private static final int EVENT_CHANGE_FACILITY_FDN_DONE = 6;
    private static final int EVENT_PIN2PUK2_DONE = 7;

    private String mLogTag = "RIL_UiccCardApplication";
    protected boolean mDbg;

    private UiccCard mUiccCard; //parent
    private int mSlotId; //Icc slot number of the Icc this app resides on
    private AppState      mAppState;
    private AppType       mAppType;
    private PersoSubState mPersoSubState;
    private String        mAid;
    private String        mAppLabel;
    private boolean       mPin1Replaced;
    private PinState      mPin1State;
    private PinState      mPin2State;
    private boolean       mIccFdnEnabled = false; // Default to disabled.
    private boolean       mIccFdnAvailable = true; // Default is enabled.
    private int mPin1RetryCount = -1;
    private int mPin2RetryCount = -1;

    private UiccApplicationRecords mUiccApplicationRecords;
    private IccFileHandler mIccFh;

    private boolean mDesiredFdnEnabled;
    private boolean mDestroyed = false; //set to true once this App is commanded to be disposed of.

    private CommandsInterface mCi;
    private Context mContext;

    private RegistrantList mReadyRegistrants = new RegistrantList();
    private RegistrantList mUnavailableRegistrants = new RegistrantList();
    private RegistrantList mLockedRegistrants = new RegistrantList();
    private RegistrantList mPersoSubstateRegistrants = new RegistrantList();

    UiccCardApplication(UiccCard uiccCard, UiccCardStatusResponse.CardStatus.AppStatus as, UiccRecords ur, Context c, CommandsInterface ci) {
        Log.d(mLogTag, "Creating UiccApp: " + as);
        mSlotId = uiccCard.getSlotId();
        mUiccCard = uiccCard;
        mAppState = as.app_state;
        mAppType = as.app_type;
        mPersoSubState = as.perso_substate;
        mAid = as.aid;
        mAppLabel = as.app_label;
        mPin1Replaced = (as.pin1_replaced != 0);
        mPin1State = as.pin1;
        mPin2State = as.pin2;

        mContext = c;
        mCi = ci;

        mIccFh = createUiccFileHandler(as.app_type);
        mUiccApplicationRecords = createUiccApplicationRecords(as.app_type, ur, mContext, mCi);
        if (mAppState == UiccConstants.AppState.APPSTATE_READY) {
            queryFdnAvailable();
        }
    }

    void update (UiccCardStatusResponse.CardStatus.AppStatus as, UiccRecords ur, Context c, CommandsInterface ci) {
        if (mDestroyed) {
            Log.e(mLogTag, "Application updated after destroyed! Fix me!");
            return;
        }
        Log.d(mLogTag, mAppType + " update. New " + as);
        mContext = c;
        mCi = ci;

        if (as.app_type != mAppType) {
            mUiccApplicationRecords.dispose();
            mUiccApplicationRecords = createUiccApplicationRecords(as.app_type, ur, c, ci);
            mAppType = as.app_type;
        }

        if (mPersoSubState != as.perso_substate) {
            mPersoSubState = as.perso_substate;
            notifyPersoSubstateRegistrants();
        }

        mAid = as.aid;
        mAppLabel = as.app_label;
        mPin1Replaced = (as.pin1_replaced != 0);
        mPin1State = as.pin1;
        mPin2State = as.pin2;
        if (mAppState != as.app_state) {
            Log.d(mLogTag, mAppType + " changed state: " + mAppState + " -> " + as.app_state);
            // If the app state turns to APPSTATE_READY, then query FDN status,
            //as it might have failed in earlier attempt.
            if (as.app_state == UiccConstants.AppState.APPSTATE_READY) {
                queryFdnAvailable();
            }
            mAppState = as.app_state;
            notifyLockedRegistrants();
            notifyReadyRegistrants();
        }
    }

    synchronized void dispose() {
        mDestroyed = true;
        mUiccApplicationRecords.dispose();
        mUiccApplicationRecords = null;
        mIccFh = null;
        notifyUnavailableRegistrants();
    }

    private UiccApplicationRecords createUiccApplicationRecords(AppType type, UiccRecords ur, Context c, CommandsInterface ci) {
        if (type == AppType.APPTYPE_USIM || type == AppType.APPTYPE_SIM) {
            return new SIMRecords(this, ur, c, ci);
        } else {
            return new RuimRecords(this, ur, c, ci);
        }
    }

    private IccFileHandler createUiccFileHandler(AppType type) {
        switch (type) {
            case APPTYPE_SIM:
                return new SIMFileHandler(this, mSlotId, mAid, mCi);
            case APPTYPE_RUIM:
                return new RuimFileHandler(this, mSlotId, mAid, mCi);
            case APPTYPE_USIM:
                return new UsimFileHandler(this, mSlotId, mAid, mCi);
            case APPTYPE_CSIM:
                return new CsimFileHandler(this, mSlotId, mAid, mCi);
            default:
                return null;
        }
    }

    public AppType getType() {
        return mAppType;
    }

    public synchronized UiccApplicationRecords getApplicationRecords() {
        return mUiccApplicationRecords;
    }

    public synchronized IccFileHandler getIccFileHandler() {
        return mIccFh;
    }

    public synchronized UiccCard getCard() {
        return mUiccCard;
    }

    public AppState getState()
    {
        return mAppState;
    }

    public PersoSubState getPersonalizationState() {
        return mPersoSubState;
    }

    public PinState getPin1State() {
        if (mPin1Replaced) {
            return mUiccCard.getUniversalPinState();
        } else {
            return mPin1State;
        }
    }

    public PinState getPin2State() {
        return mPin2State;
    }

    public String getAid() {
        return mAid;
    }

    public String getAppLabel() {
        return mAppLabel;
    }

    private synchronized void notifyAllRegistrants() {
        notifyUnavailableRegistrants();
        notifyLockedRegistrants();
        notifyReadyRegistrants();
        notifyPersoSubstateRegistrants();
    }

    /** Notifies specified registrant.
     *
     * @param r Registrant to be notified. If null - all registrants will be notified
     */
    private synchronized void notifyUnavailableRegistrants(Registrant r) {
        if (mDestroyed) {
            if (r == null) {
                mUnavailableRegistrants.notifyRegistrants();
            } else {
                r.notifyRegistrant(new AsyncResult(null, null, null));
            }
            return;
        }
    }

    private synchronized void notifyUnavailableRegistrants() {
        notifyUnavailableRegistrants(null);
    }


    /** Notifies specified registrant.
     *
     * @param r Registrant to be notified. If null - all registrants will be notified
     */
    private synchronized void notifyLockedRegistrants(Registrant r) {
        if (mDestroyed) {
            return;
        }

        if (mAppState == AppState.APPSTATE_PIN ||
            mAppState == AppState.APPSTATE_PUK) {
            if (mPin1State == PinState.PINSTATE_ENABLED_VERIFIED || mPin1State == PinState.PINSTATE_DISABLED) {
                Log.e(mLogTag, "Sanity check failed! APPSTATE is locked while PIN1 is not!!!");
                //Don't notify if application is in insane state
                return;
            }
            if (r == null) {
                Log.d(mLogTag, "Notifying registrants: LOCKED");
                mLockedRegistrants.notifyRegistrants();
            } else {
                Log.d(mLogTag, "Notifying 1 registrant: LOCKED");
                r.notifyRegistrant(new AsyncResult(null, null, null));
            }
        }
    }

    private synchronized void notifyPersoSubstateRegistrants() {
        notifyPersoSubstateRegistrants(null);
    }

    /** Notifies specified registrant.
    *
    * @param r Registrant to be notified. If null - all registrants will be notified
    */
   private synchronized void notifyPersoSubstateRegistrants(Registrant r) {
       if (mDestroyed) {
           return;
       }

       if (mAppState == AppState.APPSTATE_SUBSCRIPTION_PERSO) {
           if (r == null) {
               Log.d(mLogTag, "Notifying registrants: PERSO_LOCKED");
               mPersoSubstateRegistrants.notifyRegistrants();
           } else {
               Log.d(mLogTag, "Notifying 1 registrant: PERSO_LOCKED");
               r.notifyRegistrant(new AsyncResult(null, null, null));
           }
       }
   }

   private synchronized void notifyLockedRegistrants() {
       notifyLockedRegistrants(null);
   }

    /** Notifies specified registrant.
     *
     * @param r Registrant to be notified. If null - all registrants will be notified
     */
    private synchronized void notifyReadyRegistrants(Registrant r) {
        if (mDestroyed) {
            return;
        }
        if (mAppState == AppState.APPSTATE_READY) {
            if (mPin1State == PinState.PINSTATE_ENABLED_NOT_VERIFIED || mPin1State == PinState.PINSTATE_ENABLED_BLOCKED || mPin1State == PinState.PINSTATE_ENABLED_PERM_BLOCKED) {
                Log.e(mLogTag, "Sanity check failed! APPSTATE is ready while PIN1 is not verified!!!");
                //Don't notify if application is in insane state
                return;
            }
            if (r == null) {
                Log.d(mLogTag, "Notifying registrants: READY");
                mReadyRegistrants.notifyRegistrants();
            } else {
                Log.d(mLogTag, "Notifying 1 registrant: READY");
                r.notifyRegistrant(new AsyncResult(null, null, null));
            }
        }
    }

    private synchronized void notifyReadyRegistrants() {
        notifyReadyRegistrants(null);
    }

    public synchronized void registerForReady(Handler h, int what, Object obj) {
        if (mDestroyed) {
            return;
        }

        Registrant r = new Registrant (h, what, obj);
        mReadyRegistrants.add(r);

        notifyReadyRegistrants(r);
    }
    public synchronized void unregisterForReady(Handler h) {
        mReadyRegistrants.remove(h);
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

    protected void finalize() {
        if(mDbg) Log.d(mLogTag, mAppType + " Finalized");
    }

    /**
     * Notifies handler of any changes to PersoSubstate
     */
    public void registerForPersoSubstate(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        mPersoSubstateRegistrants.add(r);
        notifyPersoSubstateRegistrants(r);
    }

    public void unregisterForPersoSubstate(Handler h) {
        mPersoSubstateRegistrants.remove(h);
    }

    /**
     * Notifies handler of any transition into State.isPinLocked()
     */
    public void registerForLocked(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        mLockedRegistrants.add(r);
        notifyLockedRegistrants(r);
    }

    public void unregisterForLocked(Handler h) {
        mLockedRegistrants.remove(h);
    }


    /**
     * Supply the ICC PIN to the ICC
     *
     * When the operation is complete, onComplete will be sent to it's
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

    public void supplyPin (String pin, Message onComplete) {
        mCi.supplyIccPin(mSlotId, mAid, pin, mHandler.obtainMessage(EVENT_PIN1PUK1_DONE, onComplete));
    }

    public void supplyPuk (String puk, String newPin, Message onComplete) {
        mCi.supplyIccPuk(mSlotId, mAid, puk, newPin,
                mHandler.obtainMessage(EVENT_PIN1PUK1_DONE, onComplete));
    }

    public void supplyPin2 (String pin2, Message onComplete) {
        mCi.supplyIccPin2(mSlotId, mAid, pin2,
                mHandler.obtainMessage(EVENT_PIN2PUK2_DONE, onComplete));
    }

    public void supplyPuk2 (String puk2, String newPin2, Message onComplete) {
        mCi.supplyIccPuk2(mSlotId, mAid, puk2, newPin2,
                mHandler.obtainMessage(EVENT_PIN2PUK2_DONE, onComplete));
    }

    /**
     * Check whether fdn (fixed dialing number) service is available.
     * @return true if ICC fdn service available
     *         false if ICC fdn service not available
     */
    public boolean getIccFdnAvailable() {
        return mIccFdnAvailable;
    }

    /**
     * Check whether ICC pin lock is enabled
     * This is a sync call which returns the cached pin enabled state
     *
     * @return true for ICC locked enabled
     *         false for ICC locked disabled
     */
    public boolean getIccLockEnabled() {
        return mPin1State == PinState.PINSTATE_ENABLED_NOT_VERIFIED ||
               mPin1State == PinState.PINSTATE_ENABLED_VERIFIED ||
               mPin1State == PinState.PINSTATE_ENABLED_BLOCKED ||
               mPin1State == PinState.PINSTATE_ENABLED_PERM_BLOCKED;
     }

    /**
     * Check whether ICC fdn (fixed dialing number) is enabled
     * This is a sync call which returns the cached pin enabled state
     *
     * @return true for ICC fdn enabled
     *         false for ICC fdn disabled
     */
     public boolean getIccFdnEnabled() {
         return mIccFdnEnabled;
     }

     /**
     * @return No. of Attempts remaining to unlock PIN1/PUK1
     */
    public int getIccPin1RetryCount() {
    return mPin1RetryCount;
    }

    /**
     * @return No. of Attempts remaining to unlock PIN2/PUK2
     */
    public int getIccPin2RetryCount() {
    return mPin2RetryCount;
    }


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
     public void setIccLockEnabled (boolean enabled,
             String password, Message onComplete) {
         int serviceClassX;
         serviceClassX = CommandsInterface.SERVICE_CLASS_VOICE +
                 CommandsInterface.SERVICE_CLASS_DATA +
                 CommandsInterface.SERVICE_CLASS_FAX;

         mCi.setFacilityLock(mSlotId, mAid, CommandsInterface.CB_FACILITY_BA_SIM,
                 enabled, password, serviceClassX,
                 mHandler.obtainMessage(EVENT_CHANGE_FACILITY_LOCK_DONE, onComplete));
     }

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
     public void setIccFdnEnabled (boolean enabled,
             String password, Message onComplete) {
         int serviceClassX;
         mDesiredFdnEnabled = enabled;
         serviceClassX = CommandsInterface.SERVICE_CLASS_VOICE +
                 CommandsInterface.SERVICE_CLASS_DATA +
                 CommandsInterface.SERVICE_CLASS_FAX +
                 CommandsInterface.SERVICE_CLASS_SMS;

         mCi.setFacilityLock(mSlotId, mAid, CommandsInterface.CB_FACILITY_BA_FD,
                 enabled, password, serviceClassX,
                 mHandler.obtainMessage(EVENT_CHANGE_FACILITY_FDN_DONE, onComplete));
     }

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
     public void changeIccLockPassword(String oldPassword, String newPassword,
             Message onComplete) {
         if(mDbg) log("Change Pin1 old: " + oldPassword + " new: " + newPassword);
         mCi.changeIccPin(mSlotId, mAid, oldPassword, newPassword,
                 mHandler.obtainMessage(EVENT_CHANGE_PIN1_DONE, onComplete));

     }

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
     public void changeIccFdnPassword(String oldPassword, String newPassword,
             Message onComplete) {
         if(mDbg) log("Change Pin2 old: " + oldPassword + " new: " + newPassword);
         mCi.changeIccPin2(mSlotId, mAid, oldPassword, newPassword,
                 mHandler.obtainMessage(EVENT_CHANGE_PIN2_DONE, onComplete));

     }

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
    //TODO: Fusion - seems like no one uses this
    //public abstract String getServiceProviderName();

    private void queryFdnAvailable() {
        //This shouldn't change run-time. So needs to be called only once.
        int serviceClassX;

        serviceClassX = CommandsInterface.SERVICE_CLASS_VOICE +
                        CommandsInterface.SERVICE_CLASS_DATA +
                        CommandsInterface.SERVICE_CLASS_FAX;
        mCi.queryFacilityLock (mSlotId, mAid,
                CommandsInterface.CB_FACILITY_BA_FD, "", serviceClassX,
                mHandler.obtainMessage(EVENT_QUERY_FACILITY_FDN_DONE));
    }

    /**
     * Interperate EVENT_QUERY_FACILITY_LOCK_DONE
     * @param ar is asyncResult of Query_Facility_Locked
     */
    private void onQueryFdnAvailable(AsyncResult ar) {
        if(ar.exception != null) {
            if(mDbg) log("Error in querying facility lock:" + ar.exception);
            return;
        }

        int[] ints = (int[])ar.result;
        if (ints.length != 0) {
            //0 - Available & Disabled, 1-Available & Enabled, 2-Unavailable.
            if (ints[0] == 2) {
                mIccFdnEnabled = false;
                mIccFdnAvailable = false;
            } else {
                mIccFdnEnabled = (ints[0] == 1) ? true : false;
                mIccFdnAvailable = true;
            }
            if (mDbg) {
                log("Query facility FDN : FDN service available: "+ mIccFdnAvailable
                                                     +" enabled: "  + mIccFdnEnabled);
            }
        } else {
            Log.e(mLogTag, "Bogus facility lock response");
        }
    }

    /**
     * Parse the error response to obtain No of attempts remaining to unlock PIN1/PUK1
     */
    private void parsePinPukErrorResult(AsyncResult ar, boolean isPin1) {
        int[] intArray = (int[]) ar.result;
        int length = intArray.length;
        mPin1RetryCount = -1;
        mPin2RetryCount = -1;
        if (length > 0) {
            if (isPin1) {
                mPin1RetryCount = intArray[0];
            } else {
                mPin2RetryCount = intArray[0];
            }
        }
    }

    protected Handler mHandler = new Handler() {
        private void sendResultToTarget(Message m, Throwable e) {
            AsyncResult.forMessage(m).exception = e;
            m.sendToTarget();
        }

        @Override
        public void handleMessage(Message msg){
            AsyncResult ar;

            //TODO: Fusion - make sure this is not a problem
            //if (!mPhone.mIsTheCurrentActivePhone) {
            //    Log.e(mLogTag, "Received message " + msg +
            //            "[" + msg.what + "] while being destroyed. Ignoring.");
            //    return;
            //}

            switch (msg.what) {
                case EVENT_PIN1PUK1_DONE:
                case EVENT_PIN2PUK2_DONE:
                    // a PIN/PUK/PIN2/PUK2/Network Personalization
                    // request has completed. ar.userObj is the response Message
                    ar = (AsyncResult)msg.obj;
                    // TODO should abstract these exceptions
                    if ((ar.exception != null) && (ar.result != null)) {
                        if (msg.what == EVENT_PIN1PUK1_DONE) {
                            parsePinPukErrorResult(ar, true);
                        } else {
                            parsePinPukErrorResult(ar, false);
                        }
                    }
                    sendResultToTarget((Message)ar.userObj, ar.exception);
                    break;
                case EVENT_QUERY_FACILITY_FDN_DONE:
                    ar = (AsyncResult)msg.obj;
                    onQueryFdnAvailable(ar);
                    break;
                case EVENT_CHANGE_FACILITY_LOCK_DONE:
                    ar = (AsyncResult)msg.obj;
                    if (ar.exception == null) {
                        if (mDbg) log( "EVENT_CHANGE_FACILITY_LOCK_DONE ");
                    } else {
                        if (ar.result != null) {
                            parsePinPukErrorResult(ar, true);
                        }
                        Log.e(mLogTag, "Error change facility sim lock with exception "
                            + ar.exception);
                    }
                    sendResultToTarget((Message)ar.userObj, ar.exception);
                    break;
                case EVENT_CHANGE_FACILITY_FDN_DONE:
                    ar = (AsyncResult)msg.obj;

                    if (ar.exception == null) {
                        mIccFdnEnabled = mDesiredFdnEnabled;
                        if (mDbg) log("EVENT_CHANGE_FACILITY_FDN_DONE: " +
                                "Enabled= " + mIccFdnEnabled);
                    } else {
                        if (ar.result != null) {
                            parsePinPukErrorResult(ar, false);
                        }
                        Log.e(mLogTag, "Error change facility fdn with exception "
                                + ar.exception);
                    }
                    sendResultToTarget((Message)ar.userObj, ar.exception);
                    break;
                case EVENT_CHANGE_PIN1_DONE:
                    ar = (AsyncResult)msg.obj;
                    if(ar.exception != null) {
                        Log.e(mLogTag, "Error in change icc app password with exception"
                            + ar.exception);
                        if (ar.result != null) {
                            parsePinPukErrorResult(ar, true);
                        }
                    }
                    sendResultToTarget((Message)ar.userObj, ar.exception);
                    break;
                case EVENT_CHANGE_PIN2_DONE:
                    ar = (AsyncResult)msg.obj;
                    if(ar.exception != null) {
                        Log.e(mLogTag, "Error in change icc app password with exception"
                            + ar.exception);
                        if (ar.result != null) {
                            parsePinPukErrorResult(ar, false);
                        }
                    }
                    sendResultToTarget((Message)ar.userObj, ar.exception);
                    break;
                default:
                    Log.e(mLogTag, "[IccCard] Unknown Event " + msg.what);
            }
        }
    };

    /**
     * @return true if ICC card is PIN2 blocked
     */
    public boolean getIccPin2Blocked() {
        return mPin2State == PinState.PINSTATE_ENABLED_BLOCKED;
    }

    /**
     * @return true if ICC card is PUK2 blocked
     */
    public boolean getIccPuk2Blocked() {
        return mPin2State == PinState.PINSTATE_ENABLED_PERM_BLOCKED;
    }

    private void log(String msg) {
        Log.d(mLogTag, "[IccCard] " + msg);
    }

 }