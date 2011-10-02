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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.telephony.ServiceState;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.UiccConstants.AppState;
import com.android.internal.telephony.UiccManager.AppFamily;
import com.android.internal.telephony.cdma.CdmaRoamingInfoHelper;
import com.android.internal.telephony.cdma.CdmaSubscriptionInfo;
import com.android.internal.telephony.cdma.CdmaSubscriptionSourceManager;
import com.android.internal.telephony.cdma.EriInfo;
import com.android.internal.telephony.cdma.RuimRecords;
import com.android.internal.telephony.gsm.RestrictedState;
import com.android.internal.telephony.gsm.SIMRecords;

import com.android.internal.telephony.CommandsInterface.RadioTechnology;

/**
 * {@hide}
 */
public class DataServiceStateTracker extends Handler {

    private static final boolean DBG = true;
    private static final String LOG_TAG = "DATA";

    private RegistrantList mDataRoamingOnRegistrants = new RegistrantList();
    private RegistrantList mDataRoamingOffRegistrants = new RegistrantList();
    private RegistrantList mDataConnectionAttachedRegistrants = new RegistrantList();
    private RegistrantList mDataConnectionDetachedRegistrants = new RegistrantList();
    private RegistrantList mRecordsLoadedRegistrants = new RegistrantList();
    private RegistrantList mPsRestrictDisabledRegistrants = new RegistrantList();
    private RegistrantList mPsRestrictEnabledRegistrants = new RegistrantList();
    private RegistrantList mDataServiceStateRegistrants = new RegistrantList();
    private RegistrantList mRadioTechChangedRegistrants = new RegistrantList();

    private static final int EVENT_RADIO_STATE_CHANGED = 1;
    private static final int EVENT_DATA_NETWORK_STATE_CHANGED = 2;
    private static final int EVENT_POLL_STATE_REGISTRATION = 3;
    private static final int EVENT_ICC_CHANGED = 10;

    /* cdma only */
    private static final int EVENT_GET_CDMA_SUBSCRIPTION_INFO = 14;
    private static final int EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED = 16;
    private static final int EVENT_GET_CDMA_PRL_VERSION = 17;
    private static final int EVENT_CDMA_PRL_VERSION_CHANGED = 18;

    /* gsm only */
    private static final int EVENT_RESTRICTED_STATE_CHANGED = 20;

    private static final int EVENT_SIM_READY = 25;  //SIM application is ready
    private static final int EVENT_RUIM_READY = 26; //RUIM application is ready
    private static final int EVENT_NV_READY = 27;

    private static final int EVENT_SIM_RECORDS_LOADED = 28; //SIM application records are loaded
    private static final int EVENT_RUIM_RECORDS_LOADED = 29;//RUIM application records are loaded

    /* Reason for registration denial. */
    private static final String REGISTRATION_DENIED_GEN = "General";
    private static final String REGISTRATION_DENIED_AUTH = "Authentication Failure";

    static final int PS_ENABLED = 1001; // Access Control blocks data service
    static final int PS_DISABLED = 1002; // Access Control enables data service
    static final int PS_NOTIFICATION = 888; // Id to update and cancel PS
                                            // restricted

    private DataConnectionTracker mDct;
    private CommandsInterface cm;
    private Context mContext;
    private UiccManager mUiccManager;

    /**
     * A unique identifier to track requests associated with a poll and ignore
     * stale responses. The value is a count-down of expected responses in this
     * pollingContext.
     */
    private int[] mPollingContext;

    /* registration state information */
    private ServiceState mSs;
    private ServiceState mNewSS;

    private int mDataConnectionState = ServiceState.STATE_OUT_OF_SERVICE;
    private int mNewDataConnectionState = ServiceState.STATE_OUT_OF_SERVICE;

    /* icc application handles */
    UiccCardApplication m3gppApp = null;
    UiccCardApplication m3gpp2App = null;
    SIMRecords mSimRecords = null;
    RuimRecords mRuimRecords = null;

    /* cdma only stuff */
    public int mCdmaSubscriptionSource = Phone.CDMA_SUBSCRIPTION_NV; /* assume NV */
    private CdmaSubscriptionInfo mCdmaSubscriptionInfo;
    private CdmaRoamingInfoHelper mCdmaRoamingInfo;
    private CdmaSubscriptionSourceManager mCdmaSSM = null;

    /* gsm only stuff */
    private RestrictedState mRs;
    private boolean mGsmRoaming = false;
    private PhoneNotifier mNotifier;

    public DataServiceStateTracker(DataConnectionTracker dct, Context context, PhoneNotifier notifier,
            CommandsInterface ci) {
        this.mDct = dct;
        this.cm = ci;
        this.mContext = context;
        this.mNotifier = notifier;

        this.mUiccManager = UiccManager.getInstance(context, this.cm);

        /* initialize */
        mSs = new ServiceState();
        mNewSS = new ServiceState();

        /* stores cdma stuff */
        mCdmaSubscriptionInfo = new CdmaSubscriptionInfo();
        mCdmaRoamingInfo = new CdmaRoamingInfoHelper();
        mCdmaSSM = CdmaSubscriptionSourceManager.getInstance(context, ci, new Registrant(this,
                EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED, null));

        mRs = new RestrictedState();

        cm.registerForRadioStateChanged(this, EVENT_RADIO_STATE_CHANGED, null);
        cm.registerForDataNetworkStateChanged(this, EVENT_DATA_NETWORK_STATE_CHANGED, null);

        //gsm only
        cm.registerForRestrictedStateChanged(this, EVENT_RESTRICTED_STATE_CHANGED, null);
        mUiccManager.registerForIccChanged(this, EVENT_ICC_CHANGED, null);

        //cdma only
        cm.registerForCdmaPrlChanged(this, EVENT_CDMA_PRL_VERSION_CHANGED, null);
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;
        ar = (AsyncResult) msg.obj;

        switch (msg.what) {
            case EVENT_RADIO_STATE_CHANGED:
                pollState("radio state changed");
                if (cm.getRadioState().isOn()) {
                    handleCdmaSubscriptionSource();
                    cm.getCDMASubscription( obtainMessage(EVENT_GET_CDMA_SUBSCRIPTION_INFO));
                    cm.getCdmaPrlVersion(obtainMessage(EVENT_GET_CDMA_PRL_VERSION));
                }
                break;

            case EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED:
                handleCdmaSubscriptionSource();
                break;

            case EVENT_ICC_CHANGED:
                updateIccAvailability();
                pollState("icc status changed");
                break;

            case EVENT_RUIM_READY:
                cm.getCDMASubscription(obtainMessage(EVENT_GET_CDMA_SUBSCRIPTION_INFO));
                cm.getCdmaPrlVersion(obtainMessage(EVENT_GET_CDMA_PRL_VERSION));
                pollState("ruim ready");
                break;

            case EVENT_NV_READY:
                cm.getCDMASubscription(obtainMessage(EVENT_GET_CDMA_SUBSCRIPTION_INFO));
                cm.getCdmaPrlVersion(obtainMessage(EVENT_GET_CDMA_PRL_VERSION));
                pollState("nv ready");
                break;

            case EVENT_SIM_READY:
                pollState("sim ready");
                break;

            case EVENT_SIM_RECORDS_LOADED:
            case EVENT_RUIM_RECORDS_LOADED:
                mRecordsLoadedRegistrants.notifyRegistrants();
                pollState("records loaded");
                break;


            case EVENT_DATA_NETWORK_STATE_CHANGED:
                pollState("data network state changed");
                break;

            case EVENT_POLL_STATE_REGISTRATION:
                ar = (AsyncResult) msg.obj;
                handlePollStateResult(msg.what, ar);
                break;

            case EVENT_GET_CDMA_SUBSCRIPTION_INFO:
                ar = (AsyncResult) msg.obj;
                if (ar.exception != null) {
                    Log.e(LOG_TAG, "Error parsing CDMA subscription information!");
                } else {
                    mCdmaSubscriptionInfo.populateSubscriptionInfoFromRegistrationState((String[]) ar.result);
                }
                break;

            case EVENT_CDMA_PRL_VERSION_CHANGED:
                cm.getCdmaPrlVersion(obtainMessage(EVENT_GET_CDMA_PRL_VERSION));
                break;

            case EVENT_GET_CDMA_PRL_VERSION:
                ar = (AsyncResult) msg.obj;
                if (ar.exception == null) {
                    mCdmaSubscriptionInfo.setPrlVersion((String) ar.result);
                }
                break;

            case EVENT_RESTRICTED_STATE_CHANGED:
                onRestrictedStateChanged(ar);
                break;

            default:
                Log.e(LOG_TAG, "Unhandled message with number: " + msg.what);
                break;
        }
    }

    /**
     * Handles the call to get the subscription source
     *
     * @param holds the new CDMA subscription source value
     */
    private void handleCdmaSubscriptionSource() {
        int newSubscriptionSource = mCdmaSSM.getCdmaSubscriptionSource();
        if (newSubscriptionSource != mCdmaSubscriptionSource) {
            mCdmaSubscriptionSource = newSubscriptionSource;
            if (newSubscriptionSource == Phone.CDMA_SUBSCRIPTION_NV) {
                // NV is ready when subscription source is NV
                sendMessage(obtainMessage(EVENT_NV_READY));
            }
            pollState("cdma subscription source changed");
        }
    }

    /**
     * A complete "service state" from our perspective is composed of a handful
     * of separate requests to the radio. We make all of these requests at once,
     * but then abandon them and start over again if the radio notifies us that
     * some event has changed
     */

    private void pollState(String reason) {

        logv("pollstate() : reason = " + reason);

        mPollingContext = new int[1];
        mPollingContext[0] = 0;

        switch (cm.getRadioState()) {
            case RADIO_UNAVAILABLE:
                mNewSS.setStateOutOfService();
                pollStateDone();
                break;

            case RADIO_OFF:
                mNewSS.setStateOff();
                pollStateDone();
                break;

            default:
                mPollingContext[0]++;
                cm.getDataRegistrationState(obtainMessage(EVENT_POLL_STATE_REGISTRATION,
                        mPollingContext));

                break;
        }
    }

    protected void handlePollStateResult (int what, AsyncResult ar) {
        // Ignore stale requests from last poll.
        if (ar.userObj != mPollingContext)
            return;

        if (ar.exception != null) {
            CommandException.Error err = null;

            if (ar.exception instanceof CommandException) {
                err = ((CommandException) (ar.exception)).getCommandError();
            }

            if (err == CommandException.Error.RADIO_NOT_AVAILABLE) {
                // Radio has crashed or turned off.
                cancelPollState();
                return;
            }

            if (!cm.getRadioState().isOn()) {
                // Radio has crashed or turned off.
                cancelPollState();
                return;
            }

            if (err != CommandException.Error.OP_NOT_ALLOWED_BEFORE_REG_NW
                    && err != CommandException.Error.OP_NOT_ALLOWED_BEFORE_REG_NW) {
                Log.e(LOG_TAG, "RIL implementation has returned an error where it must succeed",
                        ar.exception);
            }
        } else try {
            switch (what) {
            case EVENT_POLL_STATE_REGISTRATION: // Handle RIL_REQUEST_REGISTRATION_STATE.
                RegStateResponse r = (RegStateResponse)ar.result;
                String states[] =  r.getRecord(0);

                int registrationState = 4;     //[0] registrationState
                int radioTechnology = -1;      //[3] radioTechnology
                int cssIndicator = 0;          //[7] init with 0, because it is treated as a boolean
                int systemId = 0;              //[8] systemId
                int networkId = 0;             //[9] networkId
                int roamingIndicator = -1;     //[10] Roaming indicator
                int systemIsInPrl = 0;         //[11] Indicates if current system is in PRL
                int defaultRoamingIndicator = 0;  //[12] Is default roaming indicator from PRL
                int reasonForDenial = 0;       //[13] Denial reason if registrationState = 3

                if (states.length != 14) {
                    throw new RuntimeException(
                            "Warning! Wrong number of parameters returned from "
                            + "RIL_REQUEST_REGISTRATION_STATE: expected 14 got "
                            + states.length);
                }

                try {
                    if (states[0] != null) {
                        registrationState = Integer.parseInt(states[0]);
                    }
                    if (states[3] != null) {
                        radioTechnology = Integer.parseInt(states[3]);
                    }
                    if (states[7] != null) {
                        cssIndicator = Integer.parseInt(states[7]);
                    }
                    if (RadioTechnology.getRadioTechFromInt(radioTechnology).isCdma()) {
                        if (states[8] != null) {
                            systemId = Integer.parseInt(states[8]);
                        }
                        if (states[9] != null) {
                            networkId = Integer.parseInt(states[9]);
                        }
                        if (states[10] != null) {
                            roamingIndicator = Integer.parseInt(states[10]);
                        }
                        if (states[11] != null) {
                            systemIsInPrl = Integer.parseInt(states[11]);
                        }
                        if (states[12] != null) {
                            defaultRoamingIndicator = Integer.parseInt(states[12]);
                        }
                    }
                    if (states[13] != null) {
                        reasonForDenial = Integer.parseInt(states[13]);
                    }
                } catch (NumberFormatException ex) {
                    Log.w(LOG_TAG, "error parsing RegistrationState: " + ex);
                }

                mNewSS.setState(regCodeToServiceState(registrationState));
                mNewSS.setRadioTechnology(radioTechnology);
                mNewSS.setCssIndicator(cssIndicator);

                this.mNewDataConnectionState = regCodeToServiceState(registrationState);

                if (RadioTechnology.getRadioTechFromInt(radioTechnology).isCdma()) {
                    mNewSS.setSystemAndNetworkId(systemId, networkId);

                    // When registration state is roaming and TSB58
                    // roaming indicator is not in the carrier-specified
                    // list of ERIs for home system, mCdmaRoaming is
                    // true.

                    mCdmaRoamingInfo.mCdmaRoaming = regCodeIsRoaming(registrationState) && !isRoamIndForHomeSystem(states[10]);
                    mCdmaRoamingInfo.mRoamingIndicator = roamingIndicator;
                    mCdmaRoamingInfo.mIsSystemInPrl = (systemIsInPrl == 0) ? false : true;
                    mCdmaRoamingInfo.mDefaultRoamingIndicator = defaultRoamingIndicator;
                }

                if (RadioTechnology.getRadioTechFromInt(radioTechnology).isGsm()) {
                    mGsmRoaming = regCodeIsRoaming(registrationState);
                }

                if (registrationState == 3) { //registration denied
                    String mRegistrationDeniedReason = "";
                    if (reasonForDenial == 0) {
                        mRegistrationDeniedReason = REGISTRATION_DENIED_GEN;
                    } else if (reasonForDenial == 1) {
                        mRegistrationDeniedReason = REGISTRATION_DENIED_AUTH;
                    } else {
                        /* TODO: fusion - managed roaming??? */
                        mRegistrationDeniedReason = "Other : reasonForDenial = "
                            + reasonForDenial;
                    }
                   logi("Data Registration denied : " + mRegistrationDeniedReason);
                }
                break;

            default:
                Log.e(LOG_TAG, "RIL response handle in wrong phone!"
                    + " Expected CDMA RIL request and get GSM RIL request.");
            break;
            }

        } catch (RuntimeException ex) {
            Log.e(LOG_TAG, "Exception while polling service state. "
                    + "Probably malformed RIL response.", ex);
        }

        mPollingContext[0]--;

        if (mPollingContext[0] == 0) {
            if (RadioTechnology.getRadioTechFromInt(mNewSS.getRadioTechnology()).isCdma()) {
                /* update the new service state with cdma roaming indicators */
                updateCdmaRoamingInfoInServiceState(mCdmaRoamingInfo, mCdmaSubscriptionSource, mCdmaSubscriptionInfo, mNewSS);
            }
            if (RadioTechnology.getRadioTechFromInt(mNewSS.getRadioTechnology()).isGsm()) {
                updateGsmRoamingInfoInServiceState(mGsmRoaming, mNewSS);
            }
            pollStateDone();
        }
    }

    private static String networkTypeToString(int type) {
        String ret = "unknown";

        switch (type) {
            case ServiceState.RADIO_TECHNOLOGY_GPRS:
                ret = "GPRS";
                break;
            case ServiceState.RADIO_TECHNOLOGY_EDGE:
                ret = "EDGE";
                break;
            case ServiceState.RADIO_TECHNOLOGY_UMTS:
                ret = "UMTS";
                break;
            case ServiceState.RADIO_TECHNOLOGY_IS95A:
            case ServiceState.RADIO_TECHNOLOGY_IS95B:
                ret = "CDMA";
                break;
            case ServiceState.RADIO_TECHNOLOGY_1xRTT:
                ret = "CDMA - 1xRTT";
                break;
            case ServiceState.RADIO_TECHNOLOGY_EVDO_0:
                ret = "CDMA - EvDo rev. 0";
                break;
            case ServiceState.RADIO_TECHNOLOGY_EVDO_A:
                ret = "CDMA - EvDo rev. A";
                break;
            case ServiceState.RADIO_TECHNOLOGY_HSDPA:
                ret = "HSDPA";
                break;
            case ServiceState.RADIO_TECHNOLOGY_HSUPA:
                ret = "HSUPA";
                break;
            case ServiceState.RADIO_TECHNOLOGY_HSPA:
                ret = "HSPA";
                break;
            case ServiceState.RADIO_TECHNOLOGY_EVDO_B:
                ret = "CDMA - EvDo rev. B";
                break;
            case ServiceState.RADIO_TECHNOLOGY_EHRPD:
                ret = "CDMA - EHRPD";
                break;
            case ServiceState.RADIO_TECHNOLOGY_LTE:
                ret = "LTE";
                break;
            default:
                Log.e(LOG_TAG, "Wrong network type: " + type);
                break;
        }

        return ret;
    }

    private void pollStateDone() {
        logv("Poll ServiceState done: oldSS=[" + mSs + "] newSS=[" + mNewSS + "]");

        boolean hasDataConnectionAttached = this.mDataConnectionState != ServiceState.STATE_IN_SERVICE
                && this.mNewDataConnectionState == ServiceState.STATE_IN_SERVICE;

        boolean hasDataConnectionDetached = this.mDataConnectionState == ServiceState.STATE_IN_SERVICE
                && this.mNewDataConnectionState != ServiceState.STATE_IN_SERVICE;

        if (hasDataConnectionAttached
                && RadioTechnology.getRadioTechFromInt(
                        mNewSS.getRadioTechnology()) == RadioTechnology.RADIO_TECH_UNKNOWN) {
            logw("Data connection has attached when data technology is uknown.");
        }

        boolean hasDataConnectionChanged = mDataConnectionState != mNewDataConnectionState;
        boolean hasChanged = !mNewSS.equals(mSs);
        boolean hasRoamingOn = !mSs.getRoaming() && mNewSS.getRoaming();
        boolean hasRoamingOff = mSs.getRoaming() && !mNewSS.getRoaming();
        boolean hasRadioTechChanged = mNewSS.getRadioTechnology() != mSs.getRadioTechnology();

        ServiceState tss;
        tss = mSs;
        mSs = mNewSS;
        mNewSS = tss;

        // clean slate for next time
        mNewSS.setStateOutOfService();

        mDataConnectionState = mNewDataConnectionState;

        if (hasChanged) {
            notifyDataServiceStateChanged(mSs);
        }

        if (hasDataConnectionAttached) {
            mDataConnectionAttachedRegistrants.notifyRegistrants();
        }

        if (hasDataConnectionDetached) {
            mDataConnectionDetachedRegistrants.notifyRegistrants();
        }

        if (hasDataConnectionChanged) {
            mNotifier.notifyServiceState(mDct.mPhone);
        }

        if (hasRadioTechChanged) {
            mRadioTechChangedRegistrants.notifyRegistrants();

            SystemProperties.set(TelephonyProperties.PROPERTY_DATA_NETWORK_TYPE,
                    networkTypeToString(mSs.getRadioTechnology()));
        }

        if (hasRoamingOn) {
            mDataRoamingOnRegistrants.notifyRegistrants();
        }

        if (hasRoamingOff) {
            mDataRoamingOffRegistrants.notifyRegistrants();
        }
    }


    private void notifyDataServiceStateChanged(ServiceState ss) {
        mNotifier.notifyServiceState(mDct.mPhone);
        AsyncResult ar = new AsyncResult(null, ss, null);
        //notify those who registered with DSST
        mDataServiceStateRegistrants.notifyRegistrants(ar);
        //notify those who registered for service state changed with phone.
        ((PhoneBase)mDct.mPhone).notifyServiceStateChangedP(ss);
    }

    /** Cancel a pending (if any) pollState() operation */
    protected void cancelPollState() {
        // This will effectively cancel the rest of the poll requests.
        mPollingContext = new int[1];
    }

    /**
     * Reregister network through toggle perferred network type
     * This is a work aorund to deregister and register network since there is
     * no ril api to set COPS=2 (deregister) only.
     *
     * @param onComplete is dispatched when this is complete.  it will be
     * an AsyncResult, and onComplete.obj.exception will be non-null
     * on failure.
     */
    public void reRegisterNetwork(Message onComplete) {
        //TODO: fusion - do we really need this?
//        cm.getPreferredNetworkType(
//                obtainMessage(EVENT_GET_PREFERRED_NETWORK_TYPE, onComplete));
    }

    public ServiceState getDataServiceState() {
        return mSs;
    }

    /**
     * @return true if phone is camping on a technology (eg UMTS) that could
     *         support voice and data simultaneously.
     */
    boolean isConcurrentVoiceAndData() {

        // UMTS and above supports CSS.
        RadioTechnology r = RadioTechnology.getRadioTechFromInt(mSs.getRadioTechnology());
        if (r.isGsm() && r != RadioTechnology.RADIO_TECH_EDGE
                && r != RadioTechnology.RADIO_TECH_GPRS) {
            return true;
        }

        if (r != RadioTechnology.RADIO_TECH_1xRTT
                && SystemProperties.getBoolean("ro.config.svlte1x", false)) {
            //voice+data is always concurrent on 1x+LTE devices, except when data is on 1x.
            return true;
        }

        // For rest of the technologies return the state reported from the modem
        return (mSs.getCssIndicator() == 1);
    }


    /* Poll ICC Cards/Application/Application Records and update everything */
    void updateIccAvailability() {

        /* 3GPP case */
        UiccCardApplication new3gppApp = mUiccManager.getCurrentApplication(AppFamily.APP_FAM_3GPP);

        if (m3gppApp != new3gppApp) {
            if (m3gppApp != null) {
                logv("Removing stale 3gpp Application.");
                m3gppApp.unregisterForReady(this);
                if (mSimRecords != null) {
                    logv("Removing stale sim application records.");
                    mSimRecords.unregisterForRecordsLoaded(this);
                    mSimRecords = null;
                }
            }
            if (new3gppApp != null) {
                logv("New 3gpp application found");
                new3gppApp.registerForReady(this, EVENT_SIM_READY, null);
                mSimRecords = (SIMRecords) new3gppApp.getApplicationRecords();
                if (mSimRecords != null) {
                    mSimRecords.registerForRecordsLoaded(this, EVENT_SIM_RECORDS_LOADED, null);
                }
            }
            m3gppApp = new3gppApp;
        }

        /* 3GPP2 case */
        UiccCardApplication new3gpp2App = mUiccManager
                .getCurrentApplication(AppFamily.APP_FAM_3GPP2);

        if (m3gpp2App != new3gpp2App) {
            if (m3gpp2App != null) {
                logv("Removing stale 3gpp2 Application.");
                m3gpp2App.unregisterForReady(this);
                if (mRuimRecords != null) {
                    logv("Removing stale ruim application records.");
                    mRuimRecords.unregisterForRecordsLoaded(this);
                    mRuimRecords = null;
                }
            }
            if (new3gpp2App != null) {
                logv("New 3gpp2 application found");
                new3gpp2App.registerForReady(this, EVENT_RUIM_READY, null);
                mRuimRecords = (RuimRecords) new3gpp2App.getApplicationRecords();
                if (mRuimRecords != null) {
                    logv("New ruim application records found");
                    mRuimRecords.registerForRecordsLoaded(this, EVENT_RUIM_RECORDS_LOADED, null);
                }
            }
            m3gpp2App = new3gpp2App;
        }
    }

    /** code is registration state 0-5 from TS 27.007 7.2 */
    private int regCodeToServiceState(int code) {
        switch (code) {
            case 0: // Not searching and not registered
                return ServiceState.STATE_OUT_OF_SERVICE;
            case 1:
                return ServiceState.STATE_IN_SERVICE;
            case 2: // 2 is "searching", fall through
            case 3: // 3 is "registration denied", fall through
            case 4: // 4 is "unknown" no vaild in current baseband
            case 10:// same as 0, but indicates that emergency call is possible.
            case 12:// same as 2, but indicates that emergency call is possible.
            case 13:// same as 3, but indicates that emergency call is possible.
            case 14:// same as 4, but indicates that emergency call is possible.
                return ServiceState.STATE_OUT_OF_SERVICE;
            case 5:// 5 is "Registered, roaming"
                return ServiceState.STATE_IN_SERVICE;

            default:
                Log.w(LOG_TAG, "unexpected service state " + code);
                return ServiceState.STATE_OUT_OF_SERVICE;
        }
    }

    /**
     * code is registration state 0-5 from TS 27.007 7.2
     * returns true if registered roam, false otherwise
     */
    private boolean regCodeIsRoaming(int code) {

        /* TODO: handle this. ADAPT is checked only if radiotech is GSM?? */
        // //If ADAPT is enabled, roaming indication should not be displayed.
        // if (SystemProperties.getBoolean("persist.cust.tel.adapt",false)) {
        // Log.w(LOG_TAG,"Setting Roaming Status to false");
        // return false;
        // }

        // 5 is "in service -- roam"
        return 5 == code;
    }

    /**
     * Determine whether a roaming indicator is in the carrier-specified list of ERIs for
     * home system
     *
     * @param roamInd roaming indicator in String
     * @return true if the roamInd is in the carrier-specified list of ERIs for home network
     */
    private boolean isRoamIndForHomeSystem(String roamInd) {
        // retrieve the carrier-specified list of ERIs for home system
        String homeRoamIndcators = SystemProperties.get("ro.cdma.homesystem");

        if (!TextUtils.isEmpty(homeRoamIndcators)) {
            // searches through the comma-separated list for a match,
            // return true if one is found.
            for (String homeRoamInd : homeRoamIndcators.split(",")) {
                if (homeRoamInd.equals(roamInd)) {
                    return true;
                }
            }
            // no matches found against the list!
            return false;
        }

        // no system property found for the roaming indicators for home system
        return false;
    }

    /**
     * Set roaming state when cdmaRoaming is true and ons is different from spn
     *
     * @param cdmaRoaming TS 27.007 7.2 CREG registered roaming
     * @param s ServiceState hold current ons
     * @return true for roaming state set
     */
    private boolean isCdmaRoamingBetweenOperators(boolean cdmaRoaming, ServiceState s) {

        String spn = SystemProperties.get(TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA, "empty");

        // NOTE: in case of RUIM we should completely ignore the ERI data file
        // and mOperatorAlphaLong is set from RIL_REQUEST_OPERATOR response 0
        // (alpha ONS)

        String onsl = s.getOperatorAlphaLong();
        String onss = s.getOperatorAlphaShort();

        boolean equalsOnsl = onsl != null && spn.equals(onsl);
        boolean equalsOnss = onss != null && spn.equals(onss);

        return cdmaRoaming && !(equalsOnsl || equalsOnss);
    }

    /**
     * Set roaming state when gsmRoaming is true and, if operator mcc is the
     * same as sim mcc, ons is different from spn
     * @param gsmRoaming TS 27.007 7.2 CREG registered roaming
     * @param s ServiceState hold current ons
     * @return true for roaming state set
     */
    private boolean isGsmRoamingBetweenOperators(boolean gsmRoaming, ServiceState s) {
        String spn = SystemProperties.get(TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA, "empty");

        String onsl = s.getOperatorAlphaLong();
        String onss = s.getOperatorAlphaShort();

        boolean equalsOnsl = onsl != null && spn.equals(onsl);
        boolean equalsOnss = onss != null && spn.equals(onss);

        String simNumeric = SystemProperties.get(
                TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC, "");
        String  operatorNumeric = s.getOperatorNumeric();

        boolean equalsMcc = true;
        try {
            equalsMcc = simNumeric.substring(0, 3).
                    equals(operatorNumeric.substring(0, 3));
        } catch (Exception e){
        }

        return gsmRoaming && !(equalsMcc && (equalsOnsl || equalsOnss));
    }

    public void updateGsmRoamingInfoInServiceState(Boolean roaming, ServiceState ss) {
        if (roaming && !isGsmRoamingBetweenOperators(roaming, ss)) {
            roaming = false;
        }
        ss.setRoaming(roaming);
    }

    public void updateCdmaRoamingInfoInServiceState(CdmaRoamingInfoHelper cdmaRoamingInfo, int cdmaSubscriptionSource,
            CdmaSubscriptionInfo cdmaSubscriptionInfo, ServiceState ss) {

        boolean namMatch = false;
        if (!cdmaSubscriptionInfo.isSidsAllZeros()
                && cdmaSubscriptionInfo.isHomeSid(ss.getSystemId())) {
            namMatch = true;
        }

        // Setting SS Roaming (general)
        if (cdmaSubscriptionSource == Phone.CDMA_SUBSCRIPTION_RUIM_SIM) {
            ss.setRoaming(isCdmaRoamingBetweenOperators(cdmaRoamingInfo.mCdmaRoaming, ss));
        } else {
            ss.setRoaming(cdmaRoamingInfo.mCdmaRoaming);
        }

        // Setting SS CdmaRoamingIndicator and CdmaDefaultRoamingIndicator
        ss.setCdmaDefaultRoamingIndicator(cdmaRoamingInfo.mDefaultRoamingIndicator);
        ss.setCdmaRoamingIndicator(cdmaRoamingInfo.mRoamingIndicator);

        boolean isPrlLoaded = true;
        if (cdmaSubscriptionInfo != null && TextUtils.isEmpty(cdmaSubscriptionInfo.mPrlVersion)) {
            isPrlLoaded = false;
        }

        if (!isPrlLoaded) {
            ss.setCdmaRoamingIndicator(EriInfo.ROAMING_INDICATOR_FLASH);
        } else if (!cdmaSubscriptionInfo.isSidsAllZeros()) {
            if (!namMatch && !cdmaRoamingInfo.mIsSystemInPrl) {
                // Use default
                ss.setCdmaRoamingIndicator(cdmaRoamingInfo.mDefaultRoamingIndicator);
            } else if (namMatch && !cdmaRoamingInfo.mIsSystemInPrl) {
                ss.setCdmaRoamingIndicator(EriInfo.ROAMING_INDICATOR_FLASH);
            } else if (!namMatch && cdmaRoamingInfo.mIsSystemInPrl) {
                // Use the one from PRL/ERI
                ss.setCdmaRoamingIndicator(cdmaRoamingInfo.mRoamingIndicator);
            } else {
                // It means namMatch && mIsInPrl
                if ((cdmaRoamingInfo.mRoamingIndicator <= 2)) {
                    ss.setCdmaRoamingIndicator(EriInfo.ROAMING_INDICATOR_OFF);
                } else {
                    // Use the one from PRL/ERI
                    ss.setCdmaRoamingIndicator(cdmaRoamingInfo.mRoamingIndicator);
                }
            }
        }
    }

    /**
     * Set restricted state based on the OnRestrictedStateChanged notification
     * If any voice or packet restricted state changes, trigger a UI
     * notification and notify registrants when sim is ready.
     *
     * @param ar an int value of RIL_RESTRICTED_STATE_*
     */
    private void onRestrictedStateChanged(AsyncResult ar) {
        RestrictedState newRs = new RestrictedState();

        if (ar.exception == null) {
            int[] ints = (int[]) ar.result;
            int state = ints[0];

            if (m3gpp2App != null && m3gpp2App.getState() == AppState.APPSTATE_READY) {
                newRs.setPsRestricted(
                        (state & RILConstants.RIL_RESTRICTED_STATE_PS_ALL)!= 0);
            }

            if (!mRs.isPsRestricted() && newRs.isPsRestricted()) {
                mPsRestrictEnabledRegistrants.notifyRegistrants();
                setNotification(PS_ENABLED);
            } else if (mRs.isPsRestricted() && !newRs.isPsRestricted()) {
                mPsRestrictDisabledRegistrants.notifyRegistrants();
                setNotification(PS_DISABLED);
            }

            mRs = newRs;
        }
    }

    /**
     * Post a notification to NotificationManager for restricted state
     *
     * @param notifyType is one state of PS/CS_*_ENABLE/DISABLE
     */
    private void setNotification(int notifyType) {

        Notification notification = new Notification();
        notification.when = System.currentTimeMillis();
        notification.flags = Notification.FLAG_AUTO_CANCEL;
        notification.icon = com.android.internal.R.drawable.stat_sys_warning;
        Intent intent = new Intent();

        notification.contentIntent = PendingIntent.getActivity(mContext, 0, intent,
                PendingIntent.FLAG_CANCEL_CURRENT);

        CharSequence details = "";
        CharSequence title = mContext.getText(com.android.internal.R.string.RestrictedChangedTitle);
        int notificationId = PS_NOTIFICATION;

        switch (notifyType) {
            case PS_ENABLED:
                details = mContext.getText(com.android.internal.R.string.RestrictedOnData);
                break;
            case PS_DISABLED:
                break;
        }

        notification.tickerText = title;
        notification.setLatestEventInfo(mContext, title, details, notification.contentIntent);

        NotificationManager notificationManager = (NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);

        if (notifyType == PS_DISABLED) {
            // cancel previous post notification
            notificationManager.cancel(notificationId);
        } else {
            // update restricted state notification
            notificationManager.notify(notificationId, notification);
        }
    }


    public void dispose() {
        // Unregister for all events.
        cm.unregisterForRadioStateChanged(this);
        cm.unregisterForDataNetworkStateChanged(this);
        cm.unregisterForRestrictedStateChanged(this);
        mCdmaSSM.dispose(this);

        mUiccManager.unregisterForIccChanged(this);

        if (m3gppApp != null) {
            m3gppApp.unregisterForReady(this);
            m3gppApp.unregisterForUnavailable(this);
            m3gppApp = null;
        }

        if (m3gpp2App != null) {
            m3gpp2App.unregisterForReady(this);
            m3gpp2App = null;
        }

        if (mSimRecords != null) {
            mSimRecords.unregisterForRecordsLoaded(this);
            mSimRecords = null;
        }

        if (mRuimRecords != null) {
            mRuimRecords.unregisterForRecordsLoaded(this);
            mRuimRecords = null;
        }

        mUiccManager = null;
    }


    /**
     * Registration point for data roaming on
     *
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    public void registerForDataRoamingOn(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mDataRoamingOnRegistrants.add(r);

        if (mSs.getRoaming()) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForDataRoamingOn(Handler h) {
        mDataRoamingOnRegistrants.remove(h);
    }

    /**
     * Registration point for data roaming off
     *
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    public void registerForDataRoamingOff(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mDataRoamingOffRegistrants.add(r);

        if (!mSs.getRoaming()) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForDataRoamingOff(Handler h) {
        mDataRoamingOffRegistrants.remove(h);
    }

    /**
     * Registration point for transition into Data attached.
     *
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    void registerForDataConnectionAttached(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mDataConnectionAttachedRegistrants.add(r);

        if (mSs.getState() == ServiceState.STATE_IN_SERVICE) {
            r.notifyRegistrant();
        }
    }

    void unregisterForDataConnectionAttached(Handler h) {
        mDataConnectionAttachedRegistrants.remove(h);
    }

    /**
     * Registration point for transition into Data detached.
     *
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    void registerForDataConnectionDetached(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mDataConnectionDetachedRegistrants.add(r);

        if (mSs.getState() != ServiceState.STATE_IN_SERVICE) {
            r.notifyRegistrant();
        }
    }

    void unregisterForDataConnectionDetached(Handler h) {
        mDataConnectionDetachedRegistrants.remove(h);
    }

    /**
     * Registration point for SIM/RUIM records loaded indications
     *
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    void registerForRecordsLoaded(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mRecordsLoadedRegistrants.add(r);

        if (mRuimRecords != null && mRuimRecords.getRecordsLoaded()) {
            r.notifyRegistrant();
        }

        if (mSimRecords != null && mSimRecords.getRecordsLoaded()) {
            r.notifyRegistrant();
        }
    }

    void unregisterForRecordsLoaded(Handler h) {
        mRecordsLoadedRegistrants.remove(h);
    }

    public void registerForRadioTechnologyChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mRadioTechChangedRegistrants.add(r);
    }

    public void unRegisterForRadioTechnologyChanged(Handler h) {
        mRadioTechChangedRegistrants.remove(h);
    }

    /**
     * Registration point for transition out of packet service restricted zone.
     *
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    void registerForPsRestrictedDisabled(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mPsRestrictDisabledRegistrants.add(r);

        if (mRs.isPsRestricted()) {
            r.notifyRegistrant();
        }
    }

    void unregisterForPsRestrictedDisabled(Handler h) {
        mPsRestrictDisabledRegistrants.remove(h);
    }

    /**
     * Registration point for transition into packet service restricted zone.
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    void registerForPsRestrictedEnabled(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mPsRestrictEnabledRegistrants.add(r);

        if (mRs.isPsRestricted()) {
            r.notifyRegistrant();
        }
    }

    void unregisterForPsRestrictedEnabled(Handler h) {
        mPsRestrictEnabledRegistrants.remove(h);
    }

    public void registerForServiceStateChanged(Handler h, int what, Object obj) {
        mDataServiceStateRegistrants.add(h, what, obj);
    }

    public void unregisterForServiceStateChanged(Handler h) {
        mDataServiceStateRegistrants.remove(h);
    }

    void logd(String logString) {
        if (DBG) {
            Log.d(LOG_TAG, "[DSST] " + logString);
        }
    }

    void logv(String logString) {
        if (DBG) {
            Log.d(LOG_TAG, "[DSST] " + logString);
        }
    }

    void logi(String logString) {
        Log.i(LOG_TAG, "[DSST] " + logString);
    }

    void logw(String logString) {
        Log.w(LOG_TAG, "[DSST] " + logString);
    }

    void loge(String logString) {
        Log.e(LOG_TAG, "[DSST] " + logString);
    }
}
