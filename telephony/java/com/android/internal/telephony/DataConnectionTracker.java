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

package com.android.internal.telephony;

import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.telephony.Phone.DataActivityState;
import com.android.internal.telephony.Phone.DataState;
import com.android.internal.telephony.Phone.IPVersion;
import com.android.internal.telephony.DataProfile.DataProfileType;

/**
 * {@hide}
 */
public abstract class DataConnectionTracker extends Handler {
    protected static final boolean DBG = true;

    protected final String LOG_TAG = "DATA";

    /**
     * IDLE: ready to start data connection setup, default state
     * INITING: state of issued setupDefaultPDP() but not finish yet
     * CONNECTING: state of issued startPppd() but not finish yet
     * SCANNING: data connection fails with one apn but other apns are available
     *           ready to start data connection on other apns (before INITING)
     * CONNECTED: IP connection is setup
     * DISCONNECTING: Connection.disconnect() has been called, but PDP
     *                context is not yet deactivated
     * FAILED: data connection fail for all apns settings
     *
     * getDataConnectionState() maps State to DataState
     *      FAILED or IDLE : DISCONNECTED
     *      INITING or CONNECTING or SCANNING: CONNECTING
     *      CONNECTED : CONNECTED or DISCONNECTING
     */
    public enum State {
        IDLE,
        INITING,
        CONNECTING,
        SCANNING,
        WAITING_ALARM,
        CONNECTED,
        DISCONNECTING,
        FAILED
    }

    public enum Activity {
        NONE,
        DATAIN,
        DATAOUT,
        DATAINANDOUT,
        DORMANT
    }

    Context mContext;
    CommandsInterface mCm;
    PhoneNotifier mNotifier;
    DataProfileTracker mDpt;
    Phone mPhone;

    //set to false to disable *all* mobile data connections!
    boolean mMasterDataEnabled = true;
    boolean mDnsCheckDisabled = false;

    /***** Event Codes *****/
    protected static final int EVENT_UPDATE_DATA_CONNECTIONS = 1;
    protected static final int EVENT_SERVICE_TYPE_DISABLED = 2;
    protected static final int EVENT_SERVICE_TYPE_ENABLED = 3;
    protected static final int EVENT_DISCONNECT_DONE = 4;
    protected static final int EVENT_CONNECT_DONE = 5;
    protected static final int EVENT_VOICE_CALL_STARTED = 6;
    protected static final int EVENT_VOICE_CALL_ENDED = 7;
    protected static final int EVENT_RADIO_ON = 8;
    protected static final int EVENT_RADIO_OFF_OR_NOT_AVAILABLE = 9;
    protected static final int EVENT_DATA_CALL_LIST_CHANGED = 10;
    protected static final int EVENT_DATA_CONNECTION_ATTACHED = 11;
    protected static final int EVENT_DATA_CONNECTION_DETACHED = 12;
    protected static final int EVENT_ROAMING_ON = 13;
    protected static final int EVENT_ROAMING_OFF = 14;
    protected static final int EVENT_DATA_PROFILE_DB_CHANGED = 15;
    protected static final int EVENT_MASTER_DATA_ENABLED = 16;
    protected static final int EVENT_MASTER_DATA_DISABLED = 17;
    protected static final int EVENT_RADIO_TECHNOLOGY_CHANGED = 18;
    protected static final int EVENT_TETHERED_MODE_STATE_CHANGED = 19;

    protected static final int EVENT_CDMA_OTA_PROVISION = 20;
    protected static final int EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED = 21;

    /* GSM only */
    protected static final int EVENT_PS_RESTRICT_ENABLED = 25;
    protected static final int EVENT_PS_RESTRICT_DISABLED = 26;

    protected static final int EVENT_RECORDS_LOADED = 30;

   /*
     * Reasons for calling updateDataConnections()
     * TODO: This should be made in sync with those defined in Phone.java
     */
    protected static final String REASON_ROAMING_ON = "roamingOn";
    protected static final String REASON_ROAMING_OFF = "roamingOff";
    protected static final String REASON_SERVICE_TYPE_DISABLED = "apnTypeDisabled";
    protected static final String REASON_SERVICE_TYPE_ENABLED = "apnTypeEnabled";
    protected static final String REASON_MASTER_DATA_DISABLED = "masterDataDisabled";
    protected static final String REASON_MASTER_DATA_ENABLED = "masterDataEnabled";
    protected static final String REASON_ICC_RECORDS_LOADED = "iccRecordsLaded";
    protected static final String REASON_CDMA_OTA_PROVISION = "cdmaOtaPovisioning";
    protected static final String REASON_DEFAULT_DATA_DISABLED = "defaultDataDisabled";
    protected static final String REASON_DEFAULT_DATA_ENABLED = "defaultDataEnabled";
    protected static final String REASON_RADIO_ON = "radioOn";
    protected static final String REASON_RADIO_OFF = "radioOff";
    protected static final String REASON_VOICE_CALL_ENDED = "2GVoiceCallEnded";
    protected static final String REASON_VOICE_CALL_STARTED = "2GVoiceCallStarted";
    protected static final String REASON_PS_RESTRICT_ENABLED = "psRestrictEnabled";
    protected static final String REASON_PS_RESTRICT_DISABLED = "psRestrictDisabled";
    protected static final String REASON_RADIO_TECHNOLOGY_CHANGED = "radioTechnologyChanged";
    protected static final String REASON_NETWORK_DISCONNECT = "networkOrModemDisconnect";
    protected static final String REASON_DATA_NETWORK_ATTACH = "dataNetworkAttached";
    protected static final String REASON_DATA_NETWORK_DETACH = "dataNetworkDetached";
    protected static final String REASON_DATA_PROFILE_LIST_CHANGED = "dataProfileDbChanged";
    protected static final String REASON_CDMA_SUBSCRIPTION_SOURCE_CHANGED = "cdmaSubscriptionSourceChanged";
    protected static final String REASON_TETHERED_MODE_STATE_CHANGED = "tetheredModeStateChanged";
    protected static final String REASON_DATA_CONN_PROP_CHANGED = "dataConnectionPropertyChanged";

    /**
     * Default constructor
     */
    protected DataConnectionTracker(Context context, PhoneNotifier notifier, CommandsInterface ci) {
        super();
        this.mContext = context;
        this.mCm = ci;
        this.mNotifier = notifier;

        this.mDpt = new DataProfileTracker(context, ci);
    }

    public void setPhone(Phone p) {
        this.mPhone = p;
    }
    public void dispose() {
        mDpt.dispose();
        mDpt = null;
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case EVENT_RADIO_ON:
                onRadioOn();
                break;

            case EVENT_RADIO_OFF_OR_NOT_AVAILABLE:
                onRadioOff();
                break;

            case EVENT_SERVICE_TYPE_DISABLED:
                onServiceTypeDisabled((DataServiceType) msg.obj);
                break;

            case EVENT_SERVICE_TYPE_ENABLED:
                onServiceTypeEnabled((DataServiceType) msg.obj);
                break;

            case EVENT_CONNECT_DONE:
                onConnectDone((AsyncResult) msg.obj);
                break;

            case EVENT_DISCONNECT_DONE:
                onDisconnectDone((AsyncResult) msg.obj);
                break;

            case EVENT_MASTER_DATA_DISABLED:
                onMasterDataDisabled();
                break;

            case EVENT_MASTER_DATA_ENABLED:
                onMasterDataEnabled();
                break;

            case EVENT_ROAMING_OFF:
                onRoamingOff();
                break;

            case EVENT_ROAMING_ON:
                onRoamingOn();
                break;

            case EVENT_VOICE_CALL_STARTED:
                onVoiceCallStarted();
                break;

            case EVENT_VOICE_CALL_ENDED:
                onVoiceCallEnded();
                break;

            default:
                Log.e(LOG_TAG, "[DCT] Unhandle event : " + msg.what);
        }
    }

    abstract protected void onServiceTypeEnabled(DataServiceType type);
    abstract protected void onServiceTypeDisabled(DataServiceType type);
    abstract protected void onDisconnectDone(AsyncResult obj);
    abstract protected void onConnectDone(AsyncResult obj);
    abstract protected void onRoamingOff();
    abstract protected void onRoamingOn();
    abstract protected void onVoiceCallStarted();
    abstract protected void onVoiceCallEnded();
    abstract protected void onRadioOn();
    abstract protected void onRadioOff();
    abstract protected void onMasterDataEnabled();
    abstract protected void onMasterDataDisabled();
    abstract protected boolean isConcurrentVoiceAndData();
    abstract protected void setDataConnectionAsDesired(boolean desiredPowerState, Message onCompleteMsg);
    abstract public List<DataConnection> getCurrentDataConnectionList();
    abstract public DataActivityState getDataActivityState();
    abstract public ServiceState getDataServiceState();
    abstract public boolean isDataConnectivityPossible();

    synchronized public int disableApnType(String type) {

        DataServiceType serviceType = DataServiceType.apnTypeStringToServiceType(type);
        if (serviceType == null) {
            //unknown apn type!
            return Phone.APN_REQUEST_FAILED;
        }

        /* mark service type as disabled */
        mDpt.setServiceTypeEnabled(serviceType, false);

        if (mDpt.isServiceTypeActive(serviceType) == false) {
            // service type is already inactive.
            // TODO: is APN_REQUEST_FAILED appropriate? or should it be
            // APN_REQUEST_STARTED?

            /* send out disconnected notifications - no harm doing this */
            notifyDataConnection(serviceType, IPVersion.IPV4, REASON_SERVICE_TYPE_DISABLED);
            notifyDataConnection(serviceType, IPVersion.IPV6, REASON_SERVICE_TYPE_DISABLED);

            return Phone.APN_REQUEST_FAILED;
        }

        sendMessage(obtainMessage(EVENT_SERVICE_TYPE_DISABLED, serviceType));

        return Phone.APN_REQUEST_STARTED;
    }

    /*
     * (non-Javadoc)
     * @see
     * com.android.internal.telephony.Phone#enableApnType(java.lang.String)
     * Application has no way to request IPV4 or IPV6 to be enabled, so we
     * enable both depending on whether supported data profiles are available.
     */
    synchronized public int enableApnType(String type) {

        DataServiceType serviceType = DataServiceType.apnTypeStringToServiceType(type);
        if (serviceType == null) {
            //unknown apn type!
            return Phone.APN_REQUEST_FAILED;
        }

        /* mark service type as enabled */
        mDpt.setServiceTypeEnabled(serviceType, true);

        if (mDpt.isServiceTypeActive(serviceType) == true) {

            // service type is already active, send out notifications!

            notifyDataConnection(serviceType, IPVersion.IPV4, REASON_SERVICE_TYPE_ENABLED);
            notifyDataConnection(serviceType, IPVersion.IPV6, REASON_SERVICE_TYPE_ENABLED);

            /*
             * do an update data connections, just in case it was active only on
             * one IP version and not other.
             */
            sendMessage(obtainMessage(EVENT_SERVICE_TYPE_ENABLED, serviceType));

            return Phone.APN_ALREADY_ACTIVE;
        }

        sendMessage(obtainMessage(EVENT_SERVICE_TYPE_ENABLED, serviceType));

        return Phone.APN_REQUEST_STARTED;
    }

    /*
     * (non-Javadoc)
     * @see com.android.internal.telephony.Phone#disableDataConnectivity()
     * Disable ALL data!
     */
    public boolean disableDataConnectivity() {
        mMasterDataEnabled = false;
        sendMessage(obtainMessage(EVENT_MASTER_DATA_DISABLED));
        return true;
    }

    public boolean enableDataConnectivity() {
        boolean inEcm =
            SystemProperties.getBoolean(TelephonyProperties.PROPERTY_INECM_MODE, false);

        if (inEcm)
            return false;

        mMasterDataEnabled = true;
        sendMessage(obtainMessage(EVENT_MASTER_DATA_ENABLED));
        return true;
    }

    public boolean isDataConnectivityEnabled() {
        return mMasterDataEnabled;
    }

    public DataState getDataConnectionState() {
        /*
         * return state as CONNECTED, if at least one data connection is active
         * on either IPV4 or IPV6.
         */
        DataState ret = DataState.DISCONNECTED;
        if (getDataServiceState().getState() != ServiceState.STATE_IN_SERVICE) {
            // If we're out of service, open TCP sockets may still work
            // but no data will flow
            ret = DataState.DISCONNECTED;
        } else {
            for (DataServiceType ds : DataServiceType.values()) {
                if (getDataConnectionState(ds, IPVersion.IPV4) == DataState.CONNECTED
                        || getDataConnectionState(ds, IPVersion.IPV6) == DataState.CONNECTED) {
                    ret = DataState.CONNECTED;
                    break;
                } else if (getDataConnectionState(ds, IPVersion.IPV4) == DataState.SUSPENDED
                        || getDataConnectionState(ds, IPVersion.IPV6) == DataState.SUSPENDED) {
                    ret = DataState.SUSPENDED;
                    //dont break
                }
            }
        }
        return ret;
    }

    public DataState getDataConnectionState(String apnType, IPVersion ipv) {

        DataServiceType ds = DataServiceType.apnTypeStringToServiceType(apnType);
        if (ds == null || ipv == null)
            return DataState.DISCONNECTED;

        return getDataConnectionState(ds, ipv);
    }

    private DataState getDataConnectionState(DataServiceType ds, IPVersion ipv) {
        DataState ret = DataState.DISCONNECTED;

        State dsState = mDpt.getState(ds, ipv);

        if (getDataServiceState().getState() != ServiceState.STATE_IN_SERVICE) {
            // If we're out of service, open TCP sockets may still work
            // but no data will flow
            ret = DataState.DISCONNECTED;
        } else {
            switch (dsState) {
                case FAILED:
                case IDLE:
                    ret = DataState.DISCONNECTED;
                    break;

                case CONNECTED:
                case DISCONNECTING:
                    if (TelephonyManager.getDefault().getCallState() != TelephonyManager.CALL_STATE_IDLE
                            && !isConcurrentVoiceAndData()) {
                        ret = DataState.SUSPENDED;
                    } else {
                        ret = DataState.CONNECTED;
                    }
                    break;

                case INITING:
                case CONNECTING:
                case SCANNING:
                case WAITING_ALARM:
                    ret = DataState.CONNECTING;
                    break;
            }
        }

        return ret;
    }

    public String getActiveApn() {
        return getActiveApn(Phone.APN_TYPE_DEFAULT, IPVersion.IPV4);
    }

    public String getActiveApn(String apnType, IPVersion ipv) {
        DataServiceType serviceType = DataServiceType.apnTypeStringToServiceType(apnType);
        if (serviceType == null || ipv == null)
            return null;

        DataConnection dc = mDpt.getActiveDataConnection(serviceType, ipv);
        if (dc == null)
            return null;

        DataProfile dp = dc.getDataProfile();
        if (dp != null && dp.getDataProfileType() == DataProfileType.PROFILE_TYPE_3GPP_APN) {
            return ((ApnSetting) dp).apn.toString();
        }

        return null;
    }

    public String[] getActiveApnTypes() {
        ArrayList<String> result = new ArrayList<String>();
        for (DataServiceType ds : DataServiceType.values()) {
            if (mDpt.isServiceTypeActive(ds))
                result.add(ds.toApnTypeString());
        }
        String[] ret  = new String[result.size()];
        return (String[]) result.toArray(ret);
    }

    // The data roaming setting is now located in the shared preferences.
    // See if the requested preference value is the same as that stored in
    // the shared values. If it is not, then update it.
    public void setDataRoamingEnabled(boolean enabled) {
        if (getDataRoamingEnabled() != enabled) {
            Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.DATA_ROAMING, enabled ? 1 : 0);
            if (getDataServiceState().getRoaming()) {
                sendMessage(obtainMessage(EVENT_ROAMING_ON));
            }
        }
    }

    // Retrieve the data roaming setting from the shared preferences.
    public boolean getDataRoamingEnabled() {
        try {
            return Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.DATA_ROAMING) > 0;
        } catch (SettingNotFoundException snfe) {
            return false;
        }
    }

    public String[] getDnsServers(String apnType) {
        return getDnsServers(apnType, IPVersion.IPV4);
    }

    public String[] getDnsServers(String apnType, IPVersion ipv) {
        DataServiceType serviceType = DataServiceType.apnTypeStringToServiceType(apnType);
        if (serviceType == null || ipv == null)
            return null;

        DataConnection dc = mDpt.getActiveDataConnection(serviceType, ipv);
        if (dc != null) {
            return dc.getDnsServers().clone();
        }

        return null;
    }

    public String getGateway(String apnType) {
        return getGateway(apnType, IPVersion.IPV4);
    }

    public String getGateway(String apnType, IPVersion ipv) {
        DataServiceType serviceType = DataServiceType.apnTypeStringToServiceType(apnType);
        if (serviceType == null || ipv == null)
            return null;

        DataConnection dc = mDpt.getActiveDataConnection(serviceType, ipv);
        if (dc != null) {
            return dc.getGatewayAddress();
        }

        return null;
    }

    public String getInterfaceName(String apnType) {
        return getInterfaceName(apnType, IPVersion.IPV4);
    }

    public String getInterfaceName(String apnType, IPVersion ipv) {
        DataServiceType serviceType = DataServiceType.apnTypeStringToServiceType(apnType);
        if (serviceType == null || ipv == null)
            return null;

        DataConnection dc = mDpt.getActiveDataConnection(serviceType, ipv);
        if (dc != null) {
            return dc.getInterface();
        }

        return null;
    }

    public String getIpAddress(String apnType) {
        return getIpAddress(apnType, IPVersion.IPV4);
    }

    public String getIpAddress(String apnType, IPVersion ipv) {
        DataServiceType serviceType = DataServiceType.apnTypeStringToServiceType(apnType);
        if (serviceType == null || ipv == null)
            return null;

        DataConnection dc = mDpt.getActiveDataConnection(serviceType, ipv);
        if (dc != null) {
            return dc.getIpAddress();
        }

        return null;
    }

    void notifyDataConnection(DataServiceType ds, IPVersion ipv, String reason) {
        mNotifier.notifyDataConnection(mPhone, ds.toApnTypeString(), ipv, reason);
    }

    public void notifyDataActivity() {
        mNotifier.notifyDataActivity(mPhone);
    }

    protected void notifyAllDataServiceTypes(String reason) {
        for (DataServiceType ds : DataServiceType.values()) {
            notifyDataConnection(ds, IPVersion.IPV4, reason);
            notifyDataConnection(ds, IPVersion.IPV6, reason);
        }
    }

    // notify data connection as failed - applicable for default type only?
    void notifyDataConnectionFail(String reason) {
        /*
         * Notify data connection fail ONLY if no other data call is active and
         * we give up on DEFAULT, or this will cause route deletion issues in
         * network state trackers.
         */
        boolean isAnyServiceActive = false;
        for (DataServiceType ds : DataServiceType.values()) {
            if (mDpt.isServiceTypeActive(ds)) {
                isAnyServiceActive = true;
            }
        }
        if (isAnyServiceActive == false) {
            mNotifier.notifyDataConnectionFailed(mPhone, reason);
        }
    }

    public void getDataCallList(Message response) {
        mCm.getDataCallList(response);
    }

    // Key used to read/write "disable DNS server check" pref (used for testing)
    public static final String DNS_SERVER_CHECK_DISABLED_KEY = "dns_server_check_disabled_key";

    /**
     * Disables the DNS check (i.e., allows "0.0.0.0").
     * Useful for lab testing environment.
     * @param b true disables the check, false enables.
     */
    public void disableDnsCheck(boolean b) {
        mDnsCheckDisabled = b;
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        SharedPreferences.Editor editor = sp.edit();
        editor.putBoolean(DNS_SERVER_CHECK_DISABLED_KEY, b);
        editor.commit();
    }

    /**
     * Returns true if the DNS check is currently disabled.
     */
    public boolean isDnsCheckDisabled() {
        return mDnsCheckDisabled;
    }
}
