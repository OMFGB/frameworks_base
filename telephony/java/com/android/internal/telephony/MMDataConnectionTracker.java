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

import static com.android.internal.telephony.RILConstants.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.net.IConnectivityManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.telephony.CellLocation;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.telephony.cdma.CdmaCellLocation;
import android.telephony.gsm.GsmCellLocation;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;

import com.android.internal.telephony.EventLogTags;
import com.android.internal.telephony.CommandsInterface.RadioTechnology;
import com.android.internal.telephony.DataProfile.DataProfileType;
import com.android.internal.telephony.Phone.IPVersion;
import com.android.internal.telephony.Phone.DataActivityState;
import com.android.internal.telephony.cdma.CdmaSubscriptionSourceManager;

/*
 * Definitions:
 * - DataProfile(dp) :Information required to setup a connection (ex. ApnSetting)
 * - DataService(ds) : A particular feature requested by connectivity service, (MMS, GPS) etc.
 *                     also called APN Type
 * - DataConnection(dc) : Underlying PDP connection, associated with a dp.
 * - DataProfileTracker(dpt): Keeps track of services enabled, active, and dc that supports them
 * - DataServiceStateTracker(dsst) : Keeps track of network registration states, radio access tech
 *                                   roaming indications etc.
 *
 * What we know:
 * - A set of service types that needs to be enabled
 * - Data profiles needed to establish the service type
 * - Each Data profile will also tell us whether IPv4/IPv6 is possible with that data profile
 * - Priorities of services. (this can be used if MPDP is not supported, or limited # of pdp)
 * - DataServiceStateTracker will tell us the network state and preferred data radio technology
 * - dsst also keeps track of sim/ruim loaded status
 * - Desired power state
 * - For each service type, it is possible that same APN can handle ipv4 and ipv6. It is
 *   also possible that there are different APNs. This is handled.
 *
 * What we don't know:
 * - We don't know if the underlying network will support IPV6 or not.
 * - We don't know if the underlying network will support MPDP or not (even in 3GPP)
 * - If nw does support mpdp, we dont know how many pdp sessions it can handle
 * - We don't know how many PDP sessions/interfaces modem can handle
 * - We don't know if modem can disconnect existing calls in favor of new ones
 *   based on some profile priority.
 * - We don't know if IP continuity is possible or not possible across technologies.
 * - It may not pe possible to determine whether network is EVDO or EHRPD by looking at the
 *   data registration state messages. So, if this is an EHRPD capable device, then we will have
 *   to use APN if available, or fall back to NAI.
 *
 * What we assume:
 * - Modem will not tear down the data call if IP continuity is possible.
 * - A separate dataConnection exists for IPV4 and IPV6, even
 *   though it is possible that both use the same underlying rmnet
 *   interface and pdp connection as is the case in dual stack v4/v6.
 * - If modem is aware of service priority, then these priorities are in sync
 *   with what is mentioned here, or we might end up in an infinite setup/disconnect
 *   cycle!
 *
 *
 * State Handling:
 * - states are associated with <service type, ip version> tuple.
 * - this is to handle scenario such as follows,
 *   default data might be connected on ipv4,  but we might be scanning different
 *   apns for default data on ipv6
 */

public class MMDataConnectionTracker extends DataConnectionTracker {

    private static final String LOG_TAG = "DATA";

    private static final int DATA_CONNECTION_POOL_SIZE = 8;

    private static final String INTENT_RECONNECT_ALARM = "com.android.internal.telephony.gprs-reconnect";
    private static final String INTENT_RECONNECT_ALARM_EXTRA_REASON = "reason";
    private static final String INTENT_RECONNECT_ALARM_SERVICE_TYPE = "ds";
    private static final String INTENT_RECONNECT_ALARM_IP_VERSION = "ipv";

    /**
     * Constants for the data connection activity:
     * physical link down/up
     */
     private static final int DATA_CONNECTION_ACTIVE_PH_LINK_INACTIVE = 0;
     private static final int DATA_CONNECTION_ACTIVE_PH_LINK_DOWN = 1;
     private static final int DATA_CONNECTION_ACTIVE_PH_LINK_UP = 2;

    // ServiceStateTracker to keep track of network service state
    DataServiceStateTracker mDsst;

    boolean isDctActive = true;

    // keeps track of data statistics activity
    private DataNetStatistics mPollNetStat;

    // keeps track of wifi status - TODO: WHY?
    private boolean mIsWifiConnected = false;

    // Intent sent when the reconnect alarm fires.
    private PendingIntent mReconnectIntent = null;

    //following flags are used in isReadyForData()
    private boolean mNoAutoAttach = false;
    private boolean mIsPsRestricted = false;
    private boolean mDesiredPowerState = true;

    Message mPendingPowerOffCompleteMsg;

    private static final boolean SUPPORT_IPV4 = SystemProperties.getBoolean(
            "persist.telephony.support_ipv4", true);

    private static final boolean SUPPORT_IPV6 = SystemProperties.getBoolean(
            "persist.telephony.support_ipv6", true);

    private static final boolean SUPPORT_SERVICE_ARBITRATION =
        SystemProperties.getBoolean("persist.telephony.ds.arbit", false);

    boolean mIsEhrpdCapable = false;
    //used for NV+CDMA
    String mCdmaHomeOperatorNumeric = null;

    /*
     * warning: if this flag is set then all connections are disconnected when
     * updatedata connections is called
     */
    private boolean mDisconnectAllDataCalls = false;
    private boolean mDataCallSetupPending = false;

    private CdmaSubscriptionSourceManager mCdmaSSM = null;

    /*
     * context to make sure the onUpdateDataConnections doesn't get executed
     * over and over again unnecessarily.
     */
    int mUpdateDataConnectionsContext = 0;

    /**
     * mDataCallList holds all the Data connection,
     */
    private ArrayList<DataConnection> mDataConnectionList;

    BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public synchronized void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            logv("intent received :" + action);
            if (action.equals(Intent.ACTION_SCREEN_ON)) {
                mPollNetStat.notifyScreenState(true);
                stopNetStatPoll();
                startNetStatPoll();

            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                mPollNetStat.notifyScreenState(false);
                stopNetStatPoll();
                startNetStatPoll();

            } else if (action.startsWith((INTENT_RECONNECT_ALARM))) {
                String reason = intent.getStringExtra(INTENT_RECONNECT_ALARM_EXTRA_REASON);
                DataServiceType ds = DataServiceType.valueOf(intent.getStringExtra(INTENT_RECONNECT_ALARM_SERVICE_TYPE));
                IPVersion ipv = IPVersion.valueOf(intent.getStringExtra(INTENT_RECONNECT_ALARM_IP_VERSION));
                /* set state as scanning so that updateDataConnections will process the data call */
                if (mDpt.getState(ds, ipv)==State.WAITING_ALARM)
                    mDpt.setState(State.SCANNING, ds, ipv);
                updateDataConnections(reason);
            } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                final android.net.NetworkInfo networkInfo = (NetworkInfo) intent
                        .getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                mIsWifiConnected = (networkInfo != null && networkInfo.isConnected());

            } else if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                final boolean enabled = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED;
                if (!enabled) {
                    // when WIFI got disabled, the NETWORK_STATE_CHANGED_ACTION
                    // quit and wont report disconnected till next enabling.
                    mIsWifiConnected = false;
                }

            } else if (action.equals(TelephonyIntents.ACTION_VOICE_CALL_STARTED)) {
                sendMessage(obtainMessage(EVENT_VOICE_CALL_STARTED));

            } else if (action.equals(TelephonyIntents.ACTION_VOICE_CALL_ENDED)) {
                sendMessage(obtainMessage(EVENT_VOICE_CALL_ENDED));
            }
        }
    };

    protected MMDataConnectionTracker(Context context, PhoneNotifier notifier, CommandsInterface ci) {
        super(context, notifier, ci);

        mDsst = new DataServiceStateTracker(this, context, notifier, ci);
        mPollNetStat = new DataNetStatistics(this);

        // register for events.
        mCm.registerForOn(this, EVENT_RADIO_ON, null);
        mCm.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        mCm.registerForDataStateChanged(this, EVENT_DATA_CALL_LIST_CHANGED, null);
        mCm.registerForTetheredModeStateChanged(this, EVENT_TETHERED_MODE_STATE_CHANGED, null);

        mDsst.registerForDataConnectionAttached(this, EVENT_DATA_CONNECTION_ATTACHED, null);
        mDsst.registerForDataConnectionDetached(this, EVENT_DATA_CONNECTION_DETACHED, null);
        mDsst.registerForRadioTechnologyChanged(this, EVENT_RADIO_TECHNOLOGY_CHANGED, null);

        mDsst.registerForDataRoamingOn(this, EVENT_ROAMING_ON, null);
        mDsst.registerForDataRoamingOff(this, EVENT_ROAMING_OFF, null);

        /* CDMA only */
        mCm.registerForCdmaOtaProvision(this, EVENT_CDMA_OTA_PROVISION, null);
        mCdmaSSM = CdmaSubscriptionSourceManager.getInstance(context, ci, new Registrant(this,
                EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED, null));

        /* GSM only */
        mDsst.registerForPsRestrictedEnabled(this, EVENT_PS_RESTRICT_ENABLED, null);
        mDsst.registerForPsRestrictedDisabled(this, EVENT_PS_RESTRICT_DISABLED, null);

        /*
         * We let DSST worry about SIM/RUIM records to make life a little
         * simpler for us
         */
        mDsst.registerForRecordsLoaded(this, EVENT_RECORDS_LOADED, null);

        mDpt.registerForDataProfileDbChanged(this, EVENT_DATA_PROFILE_DB_CHANGED, null);

        IntentFilter filter = new IntentFilter();
        for (DataServiceType ds : DataServiceType.values()) {
            filter.addAction(getAlarmIntentName(ds, IPVersion.IPV4));
            filter.addAction(getAlarmIntentName(ds, IPVersion.IPV6));
        }
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(TelephonyIntents.ACTION_VOICE_CALL_STARTED);
        filter.addAction(TelephonyIntents.ACTION_VOICE_CALL_ENDED);

        mContext.registerReceiver(mIntentReceiver, filter, null, this);

        createDataCallList();

        // This preference tells us 1) initial condition for "dataEnabled",
        // and 2) whether the RIL will setup the baseband to auto-PS
        // attach.
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        boolean dataDisabledOnBoot = sp.getBoolean(Phone.DATA_DISABLED_ON_BOOT_KEY, false);
        mDpt.setServiceTypeEnabled(DataServiceType.SERVICE_TYPE_DEFAULT, !dataDisabledOnBoot);
        mNoAutoAttach = dataDisabledOnBoot;

        mIsEhrpdCapable = SystemProperties.getBoolean("ro.config.ehrpd", false);

        if (SystemProperties.getBoolean("persist.cust.tel.sdc.feature", false)) {
            /* use the SOCKET_DATA_CALL_ENABLE setting do determine the boot up value of
             * mMasterDataEnable - but only if persist.cust.tel.sdc.feature is on.
             */
            mMasterDataEnabled = Settings.System.getInt(
                    mContext.getContentResolver(),
                    Settings.System.SOCKET_DATA_CALL_ENABLE, 1) > 0;
        }

        /* On startup, check with ConnectivityService(CS) if mobile data has
         * been disabled from the phone settings.  CS processes this setting on
         * startup and disables all service types via the
         * PhoneInterfaceManager. In some cases the PhoneInterfaceManager is
         * not started in time for CS to disable the service types, so, double
         * checking here.
         */
        IBinder b = ServiceManager.getService(Context.CONNECTIVITY_SERVICE);
        IConnectivityManager service = IConnectivityManager.Stub.asInterface(b);

        try {
            if (service.getMobileDataEnabled() == false) {
                // Disable all data profiles
                for (DataServiceType ds : DataServiceType.values()) {
                    if (mDpt.isServiceTypeEnabled(ds)) {
                        mDpt.setServiceTypeEnabled(ds, false);
                        logd("Disabling ds" + ds);
                    }
                }
            }
        } catch(RemoteException e) {
            // Could not find ConnectivityService, nothing to do, continue.
            logw("Could not access Connectivity Service." + e);
        }

        //used in CDMA+NV case.
        mCdmaHomeOperatorNumeric = SystemProperties.get("ro.cdma.home.operator.numeric");

        logv("SUPPORT_IPV4 = " + SUPPORT_IPV4);
        logv("SUPPORT_IPV6 = " + SUPPORT_IPV6);
        logv("SUPPORT_SERVICE_ARBITRATION = " + SUPPORT_SERVICE_ARBITRATION);
    }

    public void dispose() {

        // mark DCT as disposed
        isDctActive = false;

        mCm.unregisterForAvailable(this);
        mCm.unregisterForOn(this);
        mCm.unregisterForOffOrNotAvailable(this);
        mCm.unregisterForDataStateChanged(this);
        mCm.unregisterForTetheredModeStateChanged(this);

        mCm.unregisterForCdmaOtaProvision(this);

        mDsst.unregisterForDataConnectionAttached(this);
        mDsst.unregisterForDataConnectionDetached(this);
        mDsst.unRegisterForRadioTechnologyChanged(this);

        mDsst.unregisterForRecordsLoaded(this);

        mDsst.unregisterForDataRoamingOn(this);
        mDsst.unregisterForDataRoamingOff(this);
        mDsst.unregisterForPsRestrictedEnabled(this);
        mDsst.unregisterForPsRestrictedDisabled(this);
        mCdmaSSM.dispose(this);

        mDpt.unregisterForDataProfileDbChanged(this);

        mDsst.dispose();
        mDsst = null;

        destroyDataCallList();

        mContext.unregisterReceiver(this.mIntentReceiver);

        super.dispose();
    }

    public void handleMessage(Message msg) {

        if (isDctActive == false) {
            logw("Ignoring handler messages, DCT marked as disposed.");
            return;
        }

        switch (msg.what) {
            case EVENT_UPDATE_DATA_CONNECTIONS:
                onUpdateDataConnections(((String)msg.obj), (int)msg.arg1);
                break;

            case EVENT_RECORDS_LOADED:
                onRecordsLoaded();
                break;

            case EVENT_DATA_CONNECTION_ATTACHED:
                onDataConnectionAttached();
                break;

            case EVENT_DATA_CONNECTION_DETACHED:
                onDataConnectionDetached();
                break;

            case EVENT_RADIO_TECHNOLOGY_CHANGED:
                onRadioTechnologyChanged();
                break;

            case EVENT_DATA_CALL_LIST_CHANGED:
                // unsolicited
                onDataCallListChanged((AsyncResult) msg.obj);
                break;

            case EVENT_DATA_PROFILE_DB_CHANGED:
                onDataProfileListChanged((AsyncResult) msg.obj);
                break;

            case EVENT_CDMA_OTA_PROVISION:
                onCdmaOtaProvision((AsyncResult) msg.obj);
                break;

            case EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED:
                updateDataConnections(REASON_CDMA_SUBSCRIPTION_SOURCE_CHANGED);
                break;

            case EVENT_PS_RESTRICT_ENABLED:
                logi("PS restrict enabled.");
                /**
                 * We don't need to explicitly to tear down the PDP context when
                 * PS restricted is enabled. The base band will deactive PDP
                 * context and notify us with PDP_CONTEXT_CHANGED. But we should
                 * stop the network polling and prevent reset PDP.
                 */
                stopNetStatPoll();
                mIsPsRestricted = true;
                break;

            case EVENT_PS_RESTRICT_DISABLED:
                logi("PS restrict disable.");
                /**
                 * When PS restrict is removed, we need setup PDP connection if
                 * PDP connection is down.
                 */
                mIsPsRestricted = false;
                updateDataConnections(REASON_PS_RESTRICT_DISABLED);
                break;

            case EVENT_TETHERED_MODE_STATE_CHANGED:
                onTetheredModeStateChanged((AsyncResult) msg.obj);
                break;
            default:
                super.handleMessage(msg);
                break;
        }
    }

    protected void updateDataConnections(String reason) {
        mUpdateDataConnectionsContext++;
        Message msg = obtainMessage(EVENT_UPDATE_DATA_CONNECTIONS, //what
                mUpdateDataConnectionsContext, //arg1
                0, //arg2
                reason); //userObj
        sendMessage(msg);
    }

    private void onCdmaOtaProvision(AsyncResult ar) {
        if (ar.exception != null) {
            int[] otaProvision = (int[]) ar.result;
            if ((otaProvision != null) && (otaProvision.length > 1)) {
                switch (otaProvision[0]) {
                    case Phone.CDMA_OTA_PROVISION_STATUS_COMMITTED:
                    case Phone.CDMA_OTA_PROVISION_STATUS_OTAPA_STOPPED:
                        mDpt.resetAllProfilesAsWorking();
                        mDpt.resetAllServiceStates();
                        updateDataConnections(REASON_CDMA_OTA_PROVISION);
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private void onDataProfileListChanged(AsyncResult ar) {
        String reason = (String) ((AsyncResult) ar).result;

        mDpt.resetAllProfilesAsWorking();
        mDpt.resetAllServiceStates();
        disconnectAllConnections(reason);
    }

    protected void onRecordsLoaded() {
        updateOperatorNumericInDpt(REASON_ICC_RECORDS_LOADED);
        updateDataConnections(REASON_ICC_RECORDS_LOADED);
    }

    /*
     * returns true if data profile list was changed as a result of this
     * operator numeric update
     */
    private boolean updateOperatorNumericInDpt(String reason) {

        //TODO: enable technology/subscription based operator numeric update

        /*
         * GSM+EHRPD requires MCC/MNC be used from SIMRecords. So for now, just
         * use simrecords if it is available.
         */

        if (mDsst.mSimRecords != null) {
            mDpt.updateOperatorNumeric(mDsst.mSimRecords.getSIMOperatorNumeric(), reason);
        } else if (mDsst.mRuimRecords != null) {
            mDpt.updateOperatorNumeric(mDsst.mRuimRecords.getRUIMOperatorNumeric(), reason);
        } else {
            loge("records are loaded, but both mSimrecords & mRuimRecords are null.");
        }
        return false;
    }

    protected void onDataConnectionAttached() {
        // reset all profiles as working so as to give
        // all data profiles fair chance again.
        mDpt.resetAllProfilesAsWorking();
        mDpt.resetAllServiceStates();

        /*
         * send a data connection notification update, with latest states, it is
         * possible data went out of service and came back in service without
         * data calls being disconnected
         */
        notifyAllDataServiceTypes(REASON_DATA_NETWORK_ATTACH);

        updateDataConnections(REASON_DATA_NETWORK_ATTACH);
    }

    protected void onDataConnectionDetached() {
        /*
         * Ideally, nothing needs to be done, data connections will disconnected
         * one by one, and update data connections will be done then. But that
         * might not happen, or might take time. So still need to trigger a data
         * connection state update, because data was detached and packets are
         * not going to flow anyway.
         */
          notifyAllDataServiceTypes(REASON_DATA_NETWORK_DETACH);
    }

    protected void onRadioTechnologyChanged() {

        /*
         * notify radio technology changes.
         */
        notifyAllDataServiceTypes(REASON_RADIO_TECHNOLOGY_CHANGED);
        /*
         * Reset all service states when radio technology hand over happens. Data
         * profiles not working on previous radio technologies might start
         * working now.
         */
         mDpt.resetAllProfilesAsWorking();
         mDpt.resetAllServiceStates();
         updateDataConnections(REASON_RADIO_TECHNOLOGY_CHANGED);
    }

    @Override
    protected void onRadioOn() {
        mDpt.resetAllProfilesAsWorking();
        mDpt.resetAllServiceStates();
        updateDataConnections(REASON_RADIO_ON);
    }

    @Override
    protected void onRadioOff() {
        //cleanup for next time, probably not required.
        mDpt.resetAllProfilesAsWorking();
        mDpt.resetAllServiceStates();
    }

    @Override
    protected void onRoamingOff() {
        mDpt.resetAllProfilesAsWorking();
        mDpt.resetAllServiceStates();
        updateDataConnections(REASON_ROAMING_OFF);
    }

    @Override
    protected void onRoamingOn() {
        if (getDataOnRoamingEnabled() == false) {
            disconnectAllConnections(REASON_ROAMING_ON);
        }
        updateDataConnections(REASON_ROAMING_ON);
    }

    @Override
    protected void onVoiceCallEnded() {
        mDpt.resetAllProfilesAsWorking();
        mDpt.resetAllServiceStates();
        updateDataConnections(REASON_VOICE_CALL_ENDED);
        notifyAllDataServiceTypes(REASON_VOICE_CALL_ENDED);
    }

    @Override
    protected void onVoiceCallStarted() {
        updateDataConnections(REASON_VOICE_CALL_STARTED);
        notifyAllDataServiceTypes(REASON_VOICE_CALL_STARTED);
    }

    @Override
    protected void onMasterDataDisabled() {
        mDisconnectAllDataCalls = true;
        updateDataConnections(REASON_MASTER_DATA_DISABLED);
    }

    @Override
    protected void onMasterDataEnabled() {
        mDpt.resetAllProfilesAsWorking();
        mDpt.resetAllServiceStates();
        updateDataConnections(REASON_MASTER_DATA_ENABLED);
    }

    @Override
    protected void onServiceTypeDisabled(DataServiceType type) {
        updateDataConnections(REASON_SERVICE_TYPE_DISABLED);
    }

    @Override
    protected void onServiceTypeEnabled(DataServiceType type) {
        mDpt.resetAllProfilesAsWorking();
        mDpt.resetServiceState(type);
        updateDataConnections(REASON_SERVICE_TYPE_ENABLED);
    }

    /**
     * @param explicitPoll if true, indicates that *we* polled for this update
     *            while state == CONNECTED rather than having it delivered via
     *            an unsolicited response (which could have happened at any
     *            previous state
     */
    @SuppressWarnings("unchecked")
    protected void onDataCallListChanged(AsyncResult ar) {

        ArrayList<DataCallState> dcStates;
        dcStates = (ArrayList<DataCallState>) (ar.result);

        if (ar.exception != null) {
            // This is probably "radio not available" or something
            // of that sort. If so, the whole connection is going
            // to come down soon anyway
            return;
        }

        logv("onDataCallListChanged:");
        logv("---dc state list---");
        for (DataCallState d : dcStates) {
            if (d != null && d.active != DATA_CONNECTION_ACTIVE_PH_LINK_INACTIVE)
            logv(d.toString());
        }
        dumpDataCalls();

        boolean needDataConnectionUpdate = false;
        String dataConnectionUpdateReason = null;
        boolean isDataDormant = true; // will be set to false, if atleast one
                                      // data connection is not dormant.

        for (DataConnection dc: mDataConnectionList) {

            if (dc.isActive() == false) {
                continue;
            }

            DataCallState activeDC = getDataCallStateByCid(dcStates, dc.cid);
            if (activeDC == null) {
                logi("DC has disappeared from list : dc = " + dc);
                dc.resetSynchronously(); //TODO: do this asynchronously
                // services will be marked as inactive, on data connection
                // update
                needDataConnectionUpdate = true;
                if (dataConnectionUpdateReason == null) {
                    dataConnectionUpdateReason = REASON_NETWORK_DISCONNECT;
                }
            } else if (activeDC.active == DATA_CONNECTION_ACTIVE_PH_LINK_INACTIVE) {
                DataConnectionFailCause failCause = DataConnectionFailCause
                        .getDataConnectionDisconnectCause(activeDC.inactiveReason);

                logi("DC is inactive : dc = " + dc);
                logi("   inactive cause = " + failCause);

                dc.resetSynchronously(); //TODO: do this asynchronously
                needDataConnectionUpdate = true;
                if (dataConnectionUpdateReason == null) {
                    dataConnectionUpdateReason = REASON_NETWORK_DISCONNECT;
                }
            } else if (isIpAddrChanged(activeDC, dc)) {
                /*
                * TODO: Handle Gateway / DNS sever IP changes in a
                *       similar fashion and to be  wrapped in a generic function.
                */
                logi("Ip address change detected on " + dc.toString());
                logi("new IpAddr = " + activeDC.address + ",old IpAddr" + dc.getIpAddress());

                tryDisconnectDataCall(dc, REASON_DATA_CONN_PROP_CHANGED);
            } else {
                switch (activeDC.active) {
                    /*
                     * TODO: fusion - the following code will show dormancy
                     * indications for both cdma/gsm. Is this a good thing?
                     */
                    case DATA_CONNECTION_ACTIVE_PH_LINK_UP:
                        isDataDormant = false;
                        break;

                    case DATA_CONNECTION_ACTIVE_PH_LINK_DOWN:
                        // do nothing
                        break;

                    default:
                        loge("dc.cid = " + dc.cid + ", unexpected DataCallState.active="
                                + activeDC.active);
                }
            }
        }

        if (needDataConnectionUpdate) {
            updateDataConnections(dataConnectionUpdateReason);
        }

        if (isDataDormant) {
            mPollNetStat.setActivity(Activity.DORMANT);
            stopNetStatPoll();
        } else {
            mPollNetStat.setActivity(Activity.NONE);
            startNetStatPoll();
        }
        notifyDataActivity();
    }

    void onTetheredModeStateChanged(AsyncResult ar) {
        int[] ret = (int[])ar.result;

        if (ret == null || ret.length != 1) {
            loge("Error: Invalid Tethered mode received");
            return;
        }

        int mode = ret[0];
        logd("onTetheredModeStateChanged: mode:" + mode);

        switch (mode) {
            case RIL_TETHERED_MODE_ON:
                /* Indicates that an internal data call was created in the
                 * modem. Do nothing, just information for now
                 */
                logd("Unsol Indication: RIL_TETHERED_MODE_ON");
            break;
            case RIL_TETHERED_MODE_OFF:
                logd("Unsol Indication: RIL_TETHERED_MODE_OFF");
                /* This indicates that an internal modem data call (e.g. tethered)
                 * had ended. Reset the retry count for all DataService types since
                 * all have become unblocked and stand a chance of initiating a call
                 * again.
                 */
                for (DataServiceType ds : DataServiceType.values()) {
                    mDpt.getRetryManager(ds, IPVersion.IPV4).resetRetryCount();
                    mDpt.getRetryManager(ds, IPVersion.IPV6).resetRetryCount();
                }
                updateDataConnections(REASON_TETHERED_MODE_STATE_CHANGED);
            break;
            default:
                loge("Error: Invalid Tethered mode:" + mode);
        }
    }


    private DataCallState getDataCallStateByCid(ArrayList<DataCallState> states, int cid) {
        for (int i = 0, s = states.size(); i < s; i++) {
            if (states.get(i).cid == cid)
                return states.get(i);
        }
        return null;
    }

    private boolean isIpAddrChanged(DataCallState activeDC, DataConnection dc ) {
        boolean ipaddrChanged = false;
        /* If old ip address is empty or NULL, do not treat it an as Ip Addr change.
         * The data call is just setup we are receiving the IP address for the first time
         */
        if (!TextUtils.isEmpty(dc.getIpAddress())) {
            if ((!(activeDC.address).equals(dc.getIpAddress()))) {
                ipaddrChanged = true;
            }
        }
        return ipaddrChanged;
    }

    @Override
    protected void onConnectDone(AsyncResult ar) {

        mDataCallSetupPending = false;

        CallbackData c = (CallbackData) ar.userObj;

        /*
         * If setup is successful,  ar.result will contain the MMDataConnection instance
         * if setup failure, ar.result will contain the failure reason.
         */
        if (ar.exception == null) { /* connection is up!! */

            logi("--------------------------");
            logi("Data call setup : SUCCESS");
            logi("  service type  : " + c.ds);
            logi("  data profile  : " + c.dp.toShortString());
            logi("  data call id  : " + c.dc.cid);
            logi("  ip version    : " + c.ipv);
            logi("--------------------------");

            //mark requested service type as active through this dc, or else
            //updateDataConnections() will tear it down.
            //state is set to CONNECTED internally.
            mDpt.setServiceTypeAsActive(c.ds, c.dc, c.ipv);
            notifyDataConnection(c.ds, c.ipv, c.reason);

            if (c.ds == DataServiceType.SERVICE_TYPE_DEFAULT) {
                SystemProperties.set("gsm.defaultpdpcontext.active", "true");
            }

            //we might have other things to do, so call update updateDataConnections() again.
            updateDataConnections(c.reason);
            return; //done.
        }

        //ASSERT: Data call setup has failed.

        DataConnectionFailCause cause = (DataConnectionFailCause) (ar.result);

        logi("--------------------------");
        logi("Data call setup : FAILED");
        logi("  service type  : " + c.ds);
        logi("  data profile  : " + c.dp.toShortString());
        logi("  ip version    : " + c.ipv);
        logi("  fail cause    : " + cause);
        logi("--------------------------");

        boolean needDataConnectionUpdate = true;

        /*
         * look at the error code and determine what is the best thing to do :
         * there is no guarantee that modem/network is capable of reporting the
         * correct failure reason, so we do our best to get all requested
         * services up, but somehow making sure we don't retry endlessly.
         */

        if (cause == DataConnectionFailCause.IP_VERSION_NOT_SUPPORTED) {
            /*
             * it might not be possible for us to know if its the network that
             * doesn't support IPV6 in general, or if its the profile we tried
             * that doesn't support IPV6!
             */
            logv("Disabling data profile. dp=" + c.dp.toShortString() + ", ipv=" + c.ipv);
            c.dp.setWorking(false, c.ipv);
            // set state to scanning because can try on other data
            // profiles that might work with this ds+ipv.
            mDpt.setState(State.SCANNING, c.ds, c.ipv);
        } else if (cause.isDataProfileFailure()) {
            /*
             * this profile doesn't work, mark it as not working, so that we
             * have other profiles to try with. It is possible that
             * modem/network didn't report IP_VERSION_NOT_SUPPORTED, but profile
             * might still work with other IPV.
             */
            logv("Disabling data profile. dp=" + c.dp.toShortString() + ", ipv=" + c.ipv);
            c.dp.setWorking(false, c.ipv);
            // set state to scanning because can try on other data
            // profiles that might work with this ds+ipv.
            mDpt.setState(State.SCANNING, c.ds, c.ipv);
        } else if (mDpt.isServiceTypeActive(c.ds) == false &&
                cause.isPdpAvailabilityFailure()) {
            /*
             * not every modem, or network might be able to report this but if
             * we know this is the failure reason, we know exactly what to do!
             * check if low priority services are active, if yes tear it down!
             * But do not bother de-activating low priority calls if the same service
             * is already active on other ip versions.
             */
            if (SUPPORT_SERVICE_ARBITRATION && disconnectOneLowPriorityDataCall(c.ds, c.reason)) {
                logv("Disconnected low priority data call [pdp availability failure.]");
                needDataConnectionUpdate = false;
                // will be called, when disconnect is complete.
            }
            // set state to scanning because can try on other data
            // profiles that might work with this ds+ipv.
            mDpt.setState(State.SCANNING, c.ds, c.ipv);
        } else if (SUPPORT_SERVICE_ARBITRATION && mDpt.isServiceTypeActive(c.ds) == false
                && disconnectOneLowPriorityDataCall(c.ds, c.reason)) {
            logv("Disconnected low priority data call [pdp availability failure.]");
            /*
             * We do this because there is no way to know if the failure was
             * caused because of network resources not being available! But do
             * not bother de-activating low priority calls if the same service
             * is already active on other ip versions.
             */
            needDataConnectionUpdate = false;
            // set state to scanning because can try on other data
            // profiles that might work with this ds+ipv.
            mDpt.setState(State.SCANNING, c.ds, c.ipv);
        } else if (cause.isPermanentFail()) {
            /*
             * even though modem reports permanent failure, it is not clear
             * if failure is related to data profile, ip version, mpdp etc.
             * its safer to try and exhaust all data profiles.
             */
            logv("Permanent failure. Disabling data profile. dp=" +
                    c.dp.toShortString() + ", ipv="+ c.ipv);
            c.dp.setWorking(false, c.ipv);
            // set state to scanning because can try on other data
            // profiles that might work with this ds+ipv.
            mDpt.setState(State.SCANNING, c.ds, c.ipv);
        } else {
            logv("Data call setup failure cause unknown / temporary failure.");
            /*
             * If we reach here, then it is a temporary failure and we are trying
             * to setup data call on the highest priority service that is enabled.
             * 1. Retry if possible
             * 2. If no more retries possible, disable the data profile.
             * 3. If no more valid data profiles, mark service as disabled and set state
             *    to failed, notify.
             * 4. if default is the highest priority service left enabled,
             *    it will be retried forever!
             */

            RetryManager retryManager = mDpt.getRetryManager(c.ds, c.ipv);

            boolean scheduleAlarm = false;
            long nextReconnectDelay = 0; /* if scheduleAlarm == true */

            if (retryManager.isRetryNeeded()) {
                /* 1 : we have retries left. so Retry! */
                scheduleAlarm  = true;
                nextReconnectDelay = retryManager.getRetryTimer();
                retryManager.increaseRetryCount();
                // set state to scanning because can try on other data
                // profiles that might work with this ds+ipv.
                mDpt.setState(State.WAITING_ALARM, c.ds, c.ipv);
            } else {
                /* 2 : enough of retries. disable the data profile */
                logv("No retries left, disabling data profile. dp=" +
                        c.dp.toShortString() + ", ipv = "+ c.ipv);
                c.dp.setWorking(false, c.ipv);
                if (mDpt.getNextWorkingDataProfile(c.ds, getDataProfileTypeToUse(), c.ipv) != null) {
                    // set state to scanning because can try on other data
                    // profiles that might work with this ds+ipv.
                    mDpt.setState(State.SCANNING, c.ds, c.ipv);
                } else {
                    if (c.ds != DataServiceType.SERVICE_TYPE_DEFAULT) {
                        /*
                         * No more valid data profiles, mark service as disabled
                         * and set state to failed, notify.
                         */
                        // but make sure service is not active on different IPV!
                        if (mDpt.isServiceTypeActive(c.ds) == false) {
                            logv("No data profiles left to try, disabling service  " + c.ds);
                            mDpt.setServiceTypeEnabled(c.ds, false);
                        }
                        mDpt.setState(State.FAILED, c.ds, c.ipv);
                        notifyDataConnection(c.ds, c.ipv, c.reason);
                    } else {
                        /* 4 */
                        /* we don't have any higher priority services
                         * enabled and we ran out of other profiles to try.
                         * So retry forever with the last profile we have.
                         */
                        logv("Retry forever using last disabled data profile. dp=" +
                                c.dp.toShortString() + ", ipv = " + c.ipv);
                        c.dp.setWorking(true, c.ipv);
                        mDpt.setState(State.WAITING_ALARM, c.ds, c.ipv);
                        notifyDataConnection(c.ds, c.ipv, c.reason);
                        notifyDataConnectionFail(c.reason);

                        retryManager.retryForeverUsingLastTimeout();
                        scheduleAlarm = true;
                        nextReconnectDelay = retryManager.getRetryTimer();
                        retryManager.increaseRetryCount();
                    }
                }
            }

            if (scheduleAlarm) {
                logd("Scheduling next attempt on " + c.ds + " for " + (nextReconnectDelay / 1000)
                        + "s. Retry count = " + retryManager.getRetryCount());

                AlarmManager am = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);

                Intent intent = new Intent(getAlarmIntentName(c.ds, c.ipv));
                intent.putExtra(INTENT_RECONNECT_ALARM_EXTRA_REASON, c.reason);
                intent.putExtra(INTENT_RECONNECT_ALARM_SERVICE_TYPE, c.ds.toString());
                intent.putExtra(INTENT_RECONNECT_ALARM_IP_VERSION, c.ipv.toString());

                mReconnectIntent = PendingIntent.getBroadcast(mContext, 0, intent, 0);
                // cancel any pending wakeup - TODO: does this work?
                am.cancel(mReconnectIntent);
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime()
                        + nextReconnectDelay, mReconnectIntent);
                needDataConnectionUpdate = true;
            }
        }

        if (needDataConnectionUpdate) {
            updateDataConnections(c.reason);
        }

        logDataConnectionFailure(c.ds, c.dp, c.ipv, cause);
    }

    private String getAlarmIntentName(DataServiceType ds, IPVersion ipv) {
        return (INTENT_RECONNECT_ALARM + "." + ds + "." + ipv);
    }

    private void logDataConnectionFailure(DataServiceType ds, DataProfile dp, IPVersion ipv,
            DataConnectionFailCause cause) {
        if (cause.isEventLoggable()) {
            CellLocation loc = TelephonyManager.getDefault().getCellLocation();
            int id = -1;
            if (loc != null) {
                if (loc instanceof GsmCellLocation)
                    id = ((GsmCellLocation) loc).getCid();
                else
                    id = ((CdmaCellLocation) loc).getBaseStationId();
            }

            if (getRadioTechnology().isGsm()
                    || getRadioTechnology() == RadioTechnology.RADIO_TECH_EHRPD) {
                EventLog.writeEvent(EventLogTags.PDP_NETWORK_DROP,
                        id, getRadioTechnology());
            } else {
                EventLog.writeEvent(EventLogTags.CDMA_DATA_DROP,
                        id, getRadioTechnology());
            }
        }
    }

    /* disconnect exactly one data call whos priority is lower than serviceType */
    private boolean disconnectOneLowPriorityDataCall(DataServiceType serviceType, String reason) {
        for (DataServiceType ds : DataServiceType.values()) {
            if (ds.isLowerPriorityThan(serviceType) && mDpt.isServiceTypeEnabled(ds)
                    && mDpt.isServiceTypeActive(ds)) {
                // we are clueless as to whether IPV4/IPV6 are on same network PDP or
                // different, so disconnect both.
                boolean disconnectDone = false;
                DataConnection dc;
                dc = mDpt.getActiveDataConnection(ds, IPVersion.IPV4);
                if (dc != null) {
                    tryDisconnectDataCall(dc, reason);
                    disconnectDone = true;
                }
                dc = mDpt.getActiveDataConnection(ds, IPVersion.IPV6);
                if (dc != null) {
                    tryDisconnectDataCall(dc, reason);
                    disconnectDone = true;
                }
                if (disconnectDone) {
                    return true;
                }
            }
        }
        return false;
    }

    protected void onDisconnectDone(AsyncResult ar) {
        logv("onDisconnectDone: reason=" + (String) ar.userObj);
        updateDataConnections((String) ar.userObj);
    }

    public void disconnectAllConnections(String reason) {
        mDisconnectAllDataCalls = true;
        updateDataConnections(reason);
    }

    /*
     * (non-Javadoc)
     * @seecom.android.internal.telephony.data.DataConnectionTracker#
     * onServiceTypeEnableDisable() This function does the following:
     */
    synchronized protected void onUpdateDataConnections(String reason, int context) {
        if (context != mUpdateDataConnectionsContext) {
            //we have other EVENT_UPDATE_DATA_CONNECTIONS on the way.
            logv("onUpdateDataConnections [ignored] : reason=" + reason);
            return;
        }

        logv("onUpdateDataConnections: reason=" + reason);
        dumpDataCalls();
        dumpDataServiceTypes();

        /*
         * Phase 1:
         * - Free up any data calls that we don't need.
         * - Some data calls, may have got inactive, without the data profile tracker
         *   knowing about it, so update it.
         *   TODO: we don't really need to run Phase 1 all the time, we can optimize this!
         */

        boolean wasDcDisconnected = false;

        for (DataConnection dc : mDataConnectionList) {
            if (dc.isInactive()) {
                /*
                 * 1a. Check and fix any services that think they are active
                 * through this inactive DC.
                 */
                for (DataServiceType ds : DataServiceType.values()) {
                    if (mDpt.getActiveDataConnection(ds, IPVersion.IPV4) == dc) {
                        mDpt.setServiceTypeAsInactive(ds, IPVersion.IPV4);
                        notifyDataConnection(ds, IPVersion.IPV4, reason);
                    }
                    if (mDpt.getActiveDataConnection(ds, IPVersion.IPV6) == dc) {
                        mDpt.setServiceTypeAsInactive(ds, IPVersion.IPV6);
                        notifyDataConnection(ds, IPVersion.IPV6, reason);
                    }
                }
            } else if (dc.isActive()
                    && mDataCallSetupPending == false) {
                /*
                 * 1b. If this active data call is not in use by any enabled
                 * service, bring it down.
                 */
                boolean needsTearDown = true;
                for (DataServiceType ds : DataServiceType.values()) {
                    if (mDpt.isServiceTypeEnabled(ds)
                            && mDpt.getActiveDataConnection(ds, dc.getIpVersion()) == dc) {
                        needsTearDown = false;
                        break;
                    }
                }
                if (needsTearDown || mDisconnectAllDataCalls == true) {
                    wasDcDisconnected = wasDcDisconnected | tryDisconnectDataCall(dc, reason);
                }
            }
        }

        if (wasDcDisconnected == true) {
            /*
             * if something was disconnected, then wait for at least one
             * disconnect to complete, before setting up data calls again.
             * this will ensure that all possible data connections are freed up
             * before setting up new ones.
             */
            return;
        }

        if (mDisconnectAllDataCalls) {
            /*
             * Someone had requested that all calls be torn down.
             * Either there is no calls to disconnect, or we have already asked
             * for all data calls to be disconnected, so reset the flag.
             */
            mDisconnectAllDataCalls = false;
            //check for pending power off message
            if (mPendingPowerOffCompleteMsg != null) {
                mPendingPowerOffCompleteMsg.sendToTarget();
                mPendingPowerOffCompleteMsg = null;
            }
        }

        // Check for data readiness!
        boolean isReadyForData = isReadyForData()
                                    && getDesiredPowerState()
                                    && mCm.getRadioState().isOn();

        if (isReadyForData == false) {
            logi("***** NOT Ready for data :");
            logi("   " + "getDesiredPowerState() = " + getDesiredPowerState());
            logi("   " + "mCm.getRadioState() = " + mCm.getRadioState());
            logi("   " + dumpDataReadinessinfo());
            return; // we will be called, when some event, triggers us back into
                    // readiness.
        } else {
            logi("Ready for data : ");
            logi("   " + "getDesiredPowerState() = " + getDesiredPowerState());
            logi("   " + "mCm.getRadioState() = " + mCm.getRadioState());
            logi("   " + dumpDataReadinessinfo());
        }

        /*
         * If we had issued a data call setup before, then wait for it to complete before
         * trying any new calls.
         */
        if (mDataCallSetupPending == true) {
            logi("Data Call setup pending. Not trying to bring up any new data connections.");
            return;
        }

        /*
         * Phase 2: Ensure that all requested services are active. Do setup data
         * call as required in order of decreasing service priority - highest priority
         * service gets data call setup first!
         */
        for (DataServiceType ds : DataServiceType.getPrioritySortedValues()) {
            /*
             * 2a : Poll all data calls and update the latest info.
             */
            for (DataConnection dc : mDataConnectionList) {
                if (dc.isActive()
                        && dc.getDataProfile().canHandleServiceType(ds)) {
                    IPVersion ipv = dc.getIpVersion();
                    if (mDpt.getActiveDataConnection(ds, ipv) == null) {
                        mDpt.setServiceTypeAsActive(ds, dc, ipv);
                        /*
                         * notify only if it is enabled - avoids unnecessary
                         * notifications
                         */
                        if (mDpt.isServiceTypeEnabled(ds)) {
                            notifyDataConnection(ds, ipv, reason);
                        }
                    }
                }
            }
            /*
             * 2b : Bring up data calls as required.
             */
            if (mDpt.isServiceTypeEnabled(ds) == true) {

                //IPV4
                if (SUPPORT_IPV4
                        && mDpt.isServiceTypeActive(ds, IPVersion.IPV4) == false
                        && mDpt.getState(ds, IPVersion.IPV4) != State.WAITING_ALARM) {
                    boolean setupDone = trySetupDataCall(ds, IPVersion.IPV4, reason);
                    if (setupDone)
                        return; //one at a time, in order of priority
                }

                //IPV6
                if (SUPPORT_IPV6
                        && mDpt.isServiceTypeActive(ds, IPVersion.IPV6) == false
                        && mDpt.getState(ds, IPVersion.IPV6) != State.WAITING_ALARM) {
                    boolean setupDone = trySetupDataCall(ds, IPVersion.IPV6, reason);
                    if (setupDone)
                        return; //one at a time, in order of priority
                }
            }
        }
    }

    private boolean getDesiredPowerState() {
        return mDesiredPowerState;
    }

    @Override
    public synchronized void setDataConnectionAsDesired(boolean desiredPowerState,
            Message onCompleteMsg) {

        mDesiredPowerState = desiredPowerState;
        mPendingPowerOffCompleteMsg = null;

        /*
         * TODO: fix this workaround. For 1x, we should not disconnect data call
         * before powering off.
         */

        if (mDesiredPowerState == false && getRadioTechnology() != RadioTechnology.RADIO_TECH_1xRTT) {
            mPendingPowerOffCompleteMsg = onCompleteMsg;
            disconnectAllConnections(Phone.REASON_RADIO_TURNED_OFF);
            return;
        }

        if (onCompleteMsg != null) {
            onCompleteMsg.sendToTarget();
        }
    }

    private boolean isReadyForData() {

        //TODO: Check voice call state, emergency call back info
        boolean isReadyForData = isDataConnectivityEnabled();

        boolean roaming = mDsst.getDataServiceState().getRoaming();
        isReadyForData = isReadyForData && (!roaming || getDataOnRoamingEnabled());

        int dataRegState = this.mDsst.getDataServiceState().getState();
        RadioTechnology r = getRadioTechnology();

        isReadyForData = isReadyForData
                        && ((dataRegState == ServiceState.STATE_IN_SERVICE
                                && r != RadioTechnology.RADIO_TECH_UNKNOWN)
                                || mNoAutoAttach);

        if (r.isGsm()
                || r == RadioTechnology.RADIO_TECH_EHRPD
                || (r.isUnknown() && mNoAutoAttach)) {
            isReadyForData = isReadyForData && mDsst.mSimRecords != null
                    && mDsst.mSimRecords.getRecordsLoaded() && !mIsPsRestricted;
        }

        if (r.isCdma()) {
            isReadyForData = isReadyForData
                    && (mDsst.mCdmaSubscriptionSource == Phone.CDMA_SUBSCRIPTION_NV
                            || (mDsst.mRuimRecords != null
                                    && mDsst.mRuimRecords.getRecordsLoaded()));
        }

        return isReadyForData;
    }

    /**
     * The only circumstances under which we report that data connectivity is not
     * possible are
     * <ul>
     * <li>Data roaming is disallowed and we are roaming.</li>
     * <li>The current data state is {@code DISCONNECTED} for a reason other than
     * having explicitly disabled connectivity. In other words, data is not available
     * because the phone is out of coverage or some like reason.</li>
     * </ul>
     * @return {@code true} if data connectivity is possible, {@code false} otherwise.
     */
    public boolean isDataConnectivityPossible() {
        //TODO: is there any difference from isReadyForData()?
        return isReadyForData();
    }

    private RadioTechnology getRadioTechnology() {
        return RadioTechnology.getRadioTechFromInt(mDsst.getDataServiceState()
                .getRadioTechnology());
    }

    public String dumpDataReadinessinfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("[DataRadioTech = ").append(getRadioTechnology());
        sb.append(", data network state = ").append(mDsst.getDataServiceState().getState());
        sb.append(", mMasterDataEnabled = ").append(mMasterDataEnabled);
        sb.append(", is Roaming = ").append(mDsst.getDataServiceState().getRoaming());
        sb.append(", dataOnRoamingEnable = ").append(getDataOnRoamingEnabled());
        sb.append(", isPsRestricted = ").append(mIsPsRestricted);
        sb.append(", desiredPowerState  = ").append(getDesiredPowerState());
        sb.append(", mSIMRecords = ");
        if (mDsst.mSimRecords != null)
            sb.append(mDsst.mSimRecords.getRecordsLoaded())
                    .append("/"+mDsst.mSimRecords.getSIMOperatorNumeric());
        sb.append(", cdmaSubSource = ").append(mDsst.mCdmaSubscriptionSource);
        if (mDsst.mCdmaSubscriptionSource == Phone.CDMA_SUBSCRIPTION_NV)
            sb.append("/"+mCdmaHomeOperatorNumeric);
        sb.append(", mRuimRecords = ");
        if (mDsst.mRuimRecords != null)
            sb.append(mDsst.mRuimRecords.getRecordsLoaded())
                .append("/"+mDsst.mRuimRecords.getRUIMOperatorNumeric());
        sb.append("]");
        return sb.toString();
    }

    void dumpDataCalls() {
        logv("---dc list---");
        for (DataConnection dc: mDataConnectionList) {
            if (dc.isInactive() == false) {
                StringBuilder sb = new StringBuilder();
                sb.append("cid = " + dc.cid);
                sb.append(", state = "+dc.getStateAsString());
                sb.append(", ipv = "+dc.getIpVersion());
                sb.append(", ipaddress = "+dc.getIpAddress());
                sb.append(", gw="+dc.getGatewayAddress());
                sb.append(", dns="+ Arrays.toString(dc.getDnsServers()));
                logv(sb.toString());
            }
        }
    }

    void dumpDataServiceTypes() {
        logv("---ds list---");
        for (DataServiceType ds: DataServiceType.values()) {
            StringBuilder sb = new StringBuilder();
            sb.append("ds= " + ds);
            sb.append(", enabled = "+mDpt.isServiceTypeEnabled(ds));
            sb.append(", active = v4:")
            .append(mDpt.getState(ds, IPVersion.IPV4));
            if (mDpt.isServiceTypeActive(ds, IPVersion.IPV4)) {
                sb.append("("+mDpt.getActiveDataConnection(ds, IPVersion.IPV4).cid+")");
            }
            sb.append(" v6:")
            .append(mDpt.getState(ds, IPVersion.IPV6));
            if (mDpt.isServiceTypeActive(ds, IPVersion.IPV6)) {
                sb.append("("+mDpt.getActiveDataConnection(ds, IPVersion.IPV6).cid+")");
            }
            logv(sb.toString());
        }
    }

    private boolean tryDisconnectDataCall(DataConnection dc, String reason) {
        logv("tryDisconnectDataCall : dc=" + dc + ", reason=" + reason);
        dc.disconnect(obtainMessage(EVENT_DISCONNECT_DONE, reason));
        return true;
    }

    class CallbackData {
        DataConnection dc;
        DataProfile dp;
        IPVersion ipv;
        String reason;
        DataServiceType ds;
    }

    private boolean trySetupDataCall(DataServiceType ds, IPVersion ipv, String reason) {
        logv("trySetupDataCall : ds=" + ds + ", ipv=" + ipv + ", reason=" + reason);
        DataProfile dp = mDpt.getNextWorkingDataProfile(ds, getDataProfileTypeToUse(), ipv);
        if (dp == null) {
            logw("no working data profile available to establish service type " + ds + "on " + ipv);
            mDpt.setState(State.FAILED, ds, ipv);
            notifyDataConnection(ds, ipv, reason);
            return false;
        }
        DataConnection dc = findFreeDataCall();
        if (dc == null) {
            // if this happens, it probably means that our data call list is not
            // big enough!
            boolean ret = SUPPORT_SERVICE_ARBITRATION && disconnectOneLowPriorityDataCall(ds, reason);
            // irrespective of ret, we should return true here
            // - if a call was indeed disconnected, then updateDataConnections()
            //   will take care of setting up call again
            // - if no calls were disconnected, then updateDataConnections will fail for every
            //   service type anyway.
            return true;
        }

        mDpt.setState(State.CONNECTING, ds, ipv);
        notifyDataConnection(ds, ipv, reason);

        mDataCallSetupPending = true;

        //Assertion: dc!=null && dp!=null
        CallbackData c = new CallbackData();
        c.dc = dc;
        c.dp = dp;
        c.ds = ds;
        c.ipv = ipv;
        c.reason = reason;
        dc.connect(getRadioTechnology(), dp, ipv, obtainMessage(EVENT_CONNECT_DONE, c));
        return true;
    }

    private DataProfileType getDataProfileTypeToUse() {
        DataProfileType type = null;
        RadioTechnology r = getRadioTechnology();
        if (r == RadioTechnology.RADIO_TECH_UNKNOWN || r == null) {
            type = null;
        } else if (r == RadioTechnology.RADIO_TECH_EHRPD
                || ( mIsEhrpdCapable && r.isEvdo())) {
            /*
             * It might not possible to distinguish an EVDO only network from
             * one that supports EHRPD. If THIS is an EHRPD capable device, then
             * we have to make sure that we send APN if its available!
             */
            if (mDpt.isAnyDataProfileAvailable(DataProfileType.PROFILE_TYPE_3GPP_APN)) {
                /* At least one APN is configured, do everything as per APNs available */
                type = DataProfileType.PROFILE_TYPE_3GPP_APN;
            } else {
                /*
                 * APNs are not configured - just use the default NAI
                 * as of now just one data call on IPV4 that supports all service types.
                 */
                type = DataProfileType.PROFILE_TYPE_3GPP2_NAI;
            }
        } else if (r.isGsm()) {
            type = DataProfileType.PROFILE_TYPE_3GPP_APN;
        } else {
            type = DataProfileType.PROFILE_TYPE_3GPP2_NAI;
        }
        return type;
    }

    private void createDataCallList() {
        mDataConnectionList = new ArrayList<DataConnection>();
        DataConnection dc;

        for (int i = 0; i < DATA_CONNECTION_POOL_SIZE; i++) {
            dc = MMDataConnection.makeDataConnection(this);
            mDataConnectionList.add(dc);
        }
    }

    private void destroyDataCallList() {
        if (mDataConnectionList != null) {
            mDataConnectionList.removeAll(mDataConnectionList);
        }
    }

    private MMDataConnection findFreeDataCall() {
        for (DataConnection conn : mDataConnectionList) {
            MMDataConnection dc = (MMDataConnection) conn;
            if (dc.isInactive()) {
                return dc;
            }
        }
        return null;
    }

    protected void startNetStatPoll() {
        if (mPollNetStat.isEnablePoll() == false) {
            mPollNetStat.resetPollStats();
            mPollNetStat.setEnablePoll(true);
            mPollNetStat.run();
        }
    }

    protected void stopNetStatPoll() {
        mPollNetStat.setEnablePoll(false);
        removeCallbacks(mPollNetStat);
    }

    // Retrieve the data roaming setting from the shared preferences.
    public boolean getDataOnRoamingEnabled() {
        try {
            return Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.DATA_ROAMING) > 0;
        } catch (SettingNotFoundException snfe) {
            return false;
        }
    }

    public ServiceState getDataServiceState() {
        return mDsst.getDataServiceState();
    }

    @Override
    protected boolean isConcurrentVoiceAndData() {
        return mDsst.isConcurrentVoiceAndData();
    }

    public DataActivityState getDataActivityState() {
        DataActivityState ret = DataActivityState.NONE;
        if (getDataServiceState().getState() == ServiceState.STATE_IN_SERVICE) {
            switch (mPollNetStat.getActivity()) {
                case DATAIN:
                    ret = DataActivityState.DATAIN;
                    break;
                case DATAOUT:
                    ret = DataActivityState.DATAOUT;
                    break;
                case DATAINANDOUT:
                    ret = DataActivityState.DATAINANDOUT;
                    break;
                case DORMANT:
                    ret = DataActivityState.DORMANT;
                    break;
            }
        }
        return ret;
    }

    @SuppressWarnings("unchecked")
    public List<DataConnection> getCurrentDataConnectionList() {
        ArrayList<DataConnection> dcs = (ArrayList<DataConnection>) mDataConnectionList.clone();
        return dcs;
    }

    public void registerForDataServiceStateChanged(Handler h, int what, Object obj) {
        mDsst.registerForServiceStateChanged(h, what, obj);
    }

    public void unregisterForDataServiceStateChanged(Handler h) {
        mDsst.unregisterForServiceStateChanged(h);
    }

    void loge(String string) {
        Log.e(LOG_TAG, "[DCT] " + string);
    }

    void logw(String string) {
        Log.w(LOG_TAG, "[DCT] " + string);
    }

    void logd(String string) {
        Log.d(LOG_TAG, "[DCT] " + string);
    }

    void logv(String string) {
        Log.v(LOG_TAG, "[DCT] " + string);
    }

    void logi(String string) {
        Log.i(LOG_TAG, "[DCT] " + string);
    }
}
