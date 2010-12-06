/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.RemoteException;
import android.os.Handler;
import android.os.ServiceManager;

import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.Phone.DataState;
import com.android.internal.telephony.Phone.IPVersion;

import android.net.NetworkInfo.DetailedState;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.text.TextUtils;

/**
 * Track the state of mobile data connectivity. This is done by
 * receiving broadcast intents from the Phone process whenever
 * the state of data connectivity changes.
 *
 * {@hide}
 */
public class MobileDataStateTracker extends NetworkStateTracker {

    private static final String TAG = "MobileDataStateTracker";
    private static final boolean DBG = false;

    private ITelephony mPhoneService;

    private String mApnType;
    private String mApnTypeToWatchFor;

    class MobileInfo {

        String mInterfaceName = null;
        Phone.DataState mState = DataState.DISCONNECTED;
        String mApnName = null;
        InetAddress mIpAddress = null;
        InetAddress mGateway = null;

        public String toString() {
            StringBuilder r = new StringBuilder();
            r.append("[");
            r.append("state=").append(mState).append(", ");
            r.append("iface=").append(mInterfaceName).append(", ");
            r.append("mApnName=").append(mApnName).append(", ");
            r.append("mIpAddress=").append(mIpAddress).append(", ");
            r.append("mGateway=").append(mGateway);
            r.append("]");
            return r.toString();
        }
    }

    HashMap<IPVersion, MobileInfo> mMobileInfo;

    private boolean mEnabled;
    private BroadcastReceiver mStateReceiver;

    // DEFAULT and HIPRI are the same connection.  If we're one of these we need to check if
    // the other is also disconnected before we reset sockets
    private boolean mIsDefaultOrHipri = false;

    /**
     * Create a new MobileDataStateTracker
     * @param context the application context of the caller
     * @param target a message handler for getting callbacks about state changes
     * @param netType the ConnectivityManager network type
     * @param apnType the Phone apnType
     * @param tag the name of this network
     */
    public MobileDataStateTracker(Context context, Handler target, int netType, String tag) {
        super(context, target, netType,
                TelephonyManager.getDefault().getNetworkType(), tag,
                TelephonyManager.getDefault().getNetworkTypeName());
        mApnType = networkTypeToApnType(netType);
        mApnTypeToWatchFor = mApnType;

        if (netType == ConnectivityManager.TYPE_MOBILE ||
                netType == ConnectivityManager.TYPE_MOBILE_HIPRI) {
            mIsDefaultOrHipri = true;
        }

        mPhoneService = null;
        if(netType == ConnectivityManager.TYPE_MOBILE) {
            mEnabled = true;
        } else {
            mEnabled = false;
        }

        logv("instance created. netType=" + netType + ", mApnType=" + mApnType
                + ", mApnTypeToWatchFor=" + mApnTypeToWatchFor);

        mMobileInfo= new HashMap<IPVersion, MobileInfo>();
        mMobileInfo.put(IPVersion.IPV4, new MobileInfo());
        mMobileInfo.put(IPVersion.IPV6, new MobileInfo());
    }

    /**
     * Begin monitoring mobile data connectivity.
     */
    public void startMonitoring() {

        /* TODO: ACTION_ANY_DATA_CONNECTION_STATE_CHANGED intent is broadcasted
         * once for each <apn_type, ipVersion>. This intent is sticky, so the last
         * intent sent out is cached. But this may not be the intent that this instance
         * of mobile data state tracker is interested in. One way to fix this would be by
         * querying data connection tracker directly at startup - but no such interface exists
         * today!
         */
        IntentFilter filter =
                new IntentFilter(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
        filter.addAction(TelephonyIntents.ACTION_DATA_CONNECTION_FAILED);
        filter.addAction(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED);

        mStateReceiver = new MobileDataStateReceiver();
        Intent intent = mContext.registerReceiver(mStateReceiver, filter);
        if (intent != null && intent.getAction().equals(
                        TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED)) {
            IPVersion ipv = getIpVersionFromIntent(intent);
            mMobileInfo.get(ipv).mState = getMobileDataState(intent);
        }

        logv("initial state. v4=" + mMobileInfo.get(IPVersion.IPV4).mState +
                ", v6=" + mMobileInfo.get(IPVersion.IPV6).mState);
    }

    private Phone.DataState getMobileDataState(Intent intent) {
        String str = intent.getStringExtra(Phone.DATA_APN_TYPE_STATE);
        if (str != null) {
            String apnTypeList =
                    intent.getStringExtra(Phone.DATA_APN_TYPES_KEY);
            if (isApnTypeIncluded(apnTypeList)) {
                return Enum.valueOf(Phone.DataState.class, str);
            }
        }
        return Phone.DataState.DISCONNECTED;
    }

    private IPVersion getIpVersionFromIntent(Intent intent) {
        String str = intent.getStringExtra(Phone.DATA_IPVERSION_KEY);
        return Enum.valueOf(IPVersion.class, str);
    }

    private boolean isApnTypeIncluded(String typeList) {
        /* comma seperated list - split and check */
        if (typeList == null)
            return false;

        String[] list = typeList.split(",");
        for(int i=0; i< list.length; i++) {
            if (TextUtils.equals(list[i], mApnTypeToWatchFor) ||
                TextUtils.equals(list[i], Phone.APN_TYPE_ALL)) {
                return true;
            }
        }
        return false;
    }

    private class MobileDataStateReceiver extends BroadcastReceiver {
        ConnectivityManager mConnectivityManager;
        public void onReceive(Context context, Intent intent) {
            synchronized(this) {
                // update state and roaming before we set the state - only state changes are
                // noticed
                TelephonyManager tm = TelephonyManager.getDefault();
                setRoamingStatus(tm.isNetworkRoaming());
                setSubtype(tm.getNetworkType(), tm.getNetworkTypeName());
                if (intent.getAction().equals(TelephonyIntents.
                        ACTION_ANY_DATA_CONNECTION_STATE_CHANGED)) {

                    String apnTypeList = intent.getStringExtra(Phone.DATA_APN_TYPES_KEY);
                    boolean unavailable = intent.getBooleanExtra(Phone.NETWORK_UNAVAILABLE_KEY, false);

                    // set this regardless of the apnTypeList or IpVersion. It's
                    // all the same radio/network underneath
                    mNetworkInfo.setIsAvailable(!unavailable);

                    if (isApnTypeIncluded(apnTypeList) == false)
                        return; //not what we are looking for.

                    boolean doReset = true;
                    if (mIsDefaultOrHipri == true) {
                        // both default and hipri must go down before we reset
                        int typeToCheck = (Phone.APN_TYPE_DEFAULT.equals(mApnType) ?
                                ConnectivityManager.TYPE_MOBILE_HIPRI :
                                ConnectivityManager.TYPE_MOBILE);
                        if (mConnectivityManager == null) {
                            mConnectivityManager =
                                    (ConnectivityManager)context.getSystemService(
                                    Context.CONNECTIVITY_SERVICE);
                        }
                        if (mConnectivityManager != null) {
                            NetworkInfo info = mConnectivityManager.getNetworkInfo(
                                        typeToCheck);
                            if (info != null && info.isConnected() == true) {
                                doReset = false;
                            }
                        }

                        //TODO: doReset is not handled!() - FIX THIS!
                    }

                    boolean needDetailedStateUpdate = updateMobileInfoFromIntent(intent);
                    if (needDetailedStateUpdate == false) {
                        return;
                    }

                    String reason = intent.getStringExtra(Phone.STATE_CHANGE_REASON_KEY);

                    /*
                     * We keep separate states for v4 and v6 in mobile data state tracker, but
                     * mNetworkinfo needs just one state. So we say CONNECTED if either v4 or v6
                     * is connected. It doesn't matter which apnName is used though!
                     */
                    DataState state = getMobileDataState(intent);
                    if (mMobileInfo.get(IPVersion.IPV4).mState == DataState.CONNECTED
                            || mMobileInfo.get(IPVersion.IPV6).mState == DataState.CONNECTED) {
                        state = DataState.CONNECTED;
                    } else if (mMobileInfo.get(IPVersion.IPV4).mState == DataState.SUSPENDED
                            || mMobileInfo.get(IPVersion.IPV6).mState == DataState.SUSPENDED) {
                        state = DataState.SUSPENDED;
                    } else if (mMobileInfo.get(IPVersion.IPV4).mState == DataState.CONNECTING
                            || mMobileInfo.get(IPVersion.IPV6).mState == DataState.CONNECTING) {
                        state = DataState.CONNECTING;
                    }

                    String extraInfo = null;
                    if (mMobileInfo.get(IPVersion.IPV4).mState == DataState.CONNECTED) {
                        extraInfo = mMobileInfo.get(IPVersion.IPV4).mApnName;
                    }

                    if (needDetailedStateUpdate) {
                        switch (state) {
                            case DISCONNECTED:
                                if(isTeardownRequested()) {
                                    //DISCONNECTED as a result of tear down
                                    mEnabled = false;
                                    setTeardownRequested(false);
                                }
                                setDetailedState(DetailedState.DISCONNECTED, false, false, reason, extraInfo);
                                break;
                            case CONNECTING:
                                setDetailedState(DetailedState.CONNECTING, false, false, reason, extraInfo);
                                break;
                            case SUSPENDED:
                                setDetailedState(DetailedState.SUSPENDED, false, false, reason, extraInfo);
                                break;
                            case CONNECTED:
                                setDetailedState(
                                        DetailedState.CONNECTED,
                                        mMobileInfo.get(IPVersion.IPV4).mState == DataState.CONNECTED,
                                        mMobileInfo.get(IPVersion.IPV6).mState == DataState.CONNECTED,
                                        reason, extraInfo);
                                break;
                        }
                    }
                } else if (intent.getAction().
                        equals(TelephonyIntents.ACTION_DATA_CONNECTION_FAILED)) {
                    mEnabled = false;
                    String reason = intent.getStringExtra(Phone.FAILURE_REASON_KEY);
                    String apnName = intent.getStringExtra(Phone.DATA_APN_KEY);
                    logi("Received " + intent.getAction() + " broadcast" +
                            reason == null ? "" : "(" + reason + ")");
                    setDetailedState(DetailedState.FAILED,
                            mMobileInfo.get(IPVersion.IPV4).mState == DataState.CONNECTED,
                            mMobileInfo.get(IPVersion.IPV6).mState == DataState.CONNECTED, reason, apnName);
                }
            }
        }
    }

    private boolean updateMobileInfoFromIntent(Intent intent) {

        DataState newState = getMobileDataState(intent);
        IPVersion ipv = getIpVersionFromIntent(intent);

        logi("dc state change intent received for " + mApnType + "/" + ipv
                + " with state  " + newState + ". enabled=" + mEnabled);

        if (mMobileInfo.get(ipv).mState == newState) {
            // no change - nothing needs to be done.
            return false;
        }

        MobileInfo newInfo = mMobileInfo.get(ipv);
        newInfo.mState = newState;
        if (newInfo.mState == DataState.CONNECTED) {
            newInfo.mApnName = intent.getStringExtra(Phone.DATA_APN_KEY);
            newInfo.mInterfaceName = intent.getStringExtra(Phone.DATA_IFACE_NAME_KEY);
            try {
                newInfo.mIpAddress = InetAddress.getByName(intent
                        .getStringExtra(Phone.DATA_IP_ADDRESS_KEY));
                newInfo.mGateway = InetAddress.getByName(intent
                        .getStringExtra(Phone.DATA_GW_ADDRESS_KEY));
            } catch (UnknownHostException e) {
                loge("interface connected with invalid parameters : ip=" + newInfo.mIpAddress
                        + ", gw=" + newInfo.mGateway);
            }
        } else {
            if (newInfo.mState == DataState.DISCONNECTED) {
                if (newInfo.mInterfaceName != null) {
                    NetworkUtils.resetConnections(mMobileInfo.get(ipv).mInterfaceName);
                }
                /*
                 * When network disconnects the data call, the routing table
                 * entries corresponding to this interface are removed
                 * automatically - update our flags to reflect this. Ideally
                 * connectivity service should do this, but it may not if the
                 * other IP version is active.
                 */
                if (mApnType.equals(Phone.APN_TYPE_DEFAULT)) {
                    removeDefaultRoute(ipv);
                } else {
                    removePrivateDnsRoutes(ipv);
                }
            }
        }

        logv("updated mobile state info for " + ipv + " : " + mMobileInfo.get(ipv));

        return true;
    }

    private void getPhoneService(boolean forceRefresh) {
        if ((mPhoneService == null) || forceRefresh) {
            mPhoneService = ITelephony.Stub.asInterface(ServiceManager.getService("phone"));
        }
    }

    /**
     * Report whether data connectivity is possible.
     */
    public boolean isAvailable() {
        getPhoneService(false);

        /*
         * If the phone process has crashed in the past, we'll get a
         * RemoteException and need to re-reference the service.
         */
        for (int retry = 0; retry < 2; retry++) {
            if (mPhoneService == null) break;

            try {
                return mPhoneService.isDataConnectivityPossible();
            } catch (RemoteException e) {
                // First-time failed, get the phone service again
                if (retry == 0) getPhoneService(true);
            }
        }

        return false;
    }

    /**
     * {@inheritDoc}
     * The mobile data network subtype indicates what generation network technology is in effect,
     * e.g., GPRS, EDGE, UMTS, etc.
     */
    public int getNetworkSubtype() {
        return TelephonyManager.getDefault().getNetworkType();
    }

    /**
     * Return the system properties name associated with the tcp buffer sizes
     * for this network.
     */
    public String getTcpBufferSizesPropName() {
        String networkTypeStr = "unknown";
        TelephonyManager tm = new TelephonyManager(mContext);
        //TODO We have to edit the parameter for getNetworkType regarding CDMA
        switch(tm.getNetworkType()) {
        case TelephonyManager.NETWORK_TYPE_GPRS:
            networkTypeStr = "gprs";
            break;
        case TelephonyManager.NETWORK_TYPE_EDGE:
            networkTypeStr = "edge";
            break;
        case TelephonyManager.NETWORK_TYPE_UMTS:
            networkTypeStr = "umts";
            break;
        case TelephonyManager.NETWORK_TYPE_HSDPA:
            networkTypeStr = "hsdpa";
            break;
        case TelephonyManager.NETWORK_TYPE_HSUPA:
            networkTypeStr = "hsupa";
            break;
        case TelephonyManager.NETWORK_TYPE_HSPA:
            networkTypeStr = "hspa";
            break;
        case TelephonyManager.NETWORK_TYPE_CDMA:
            networkTypeStr = "cdma";
            break;
        case TelephonyManager.NETWORK_TYPE_1xRTT:
            networkTypeStr = "1xrtt";
            break;
        case TelephonyManager.NETWORK_TYPE_EVDO_0:
            networkTypeStr = "evdo";
            break;
        case TelephonyManager.NETWORK_TYPE_EVDO_A:
            networkTypeStr = "evdo";
            break;
        case TelephonyManager.NETWORK_TYPE_EVDO_B:
            networkTypeStr = "evdo_b";
            break;
        case TelephonyManager.NETWORK_TYPE_EHRPD:
            networkTypeStr = "ehrpd";
            break;
        case TelephonyManager.NETWORK_TYPE_LTE:
            networkTypeStr = "lte";
        }
        return "net.tcp.buffersize." + networkTypeStr;
    }

    /**
     * Tear down mobile data connectivity, i.e., disable the ability to create
     * mobile data connections.
     */
    @Override
    public boolean teardown() {
        // since we won't get a notification currently (TODO - per APN notifications)
        // we won't get a disconnect message until all APN's on the current connection's
        // APN list are disabled.  That means privateRoutes for DNS and such will remain on -
        // not a problem since that's all shared with whatever other APN is still on, but
        // ugly.
        setTeardownRequested(true);
        return (setEnableApn(mApnType, false) != Phone.APN_REQUEST_FAILED);
    }

    /**
     * Re-enable mobile data connectivity after a {@link #teardown()}.
     */
    public boolean reconnect() {
        setTeardownRequested(false);
        /*
         * enable first, so that intents are processed, as soon as
         * setEnableApn() is called
         */
        mEnabled = true;

        /*
         * Following will force a connectivity action event to be send, even if
         * state change has not occurred.
         */
        mMobileInfo.get(IPVersion.IPV4).mState = DataState.CONNECTING;
        mMobileInfo.get(IPVersion.IPV6).mState = DataState.CONNECTING;

        switch (setEnableApn(mApnType, true)) {
            case Phone.APN_ALREADY_ACTIVE:
                logv("dct reports apn already active. " + this);
                //we will be sent intents again.

                break;
            case Phone.APN_REQUEST_STARTED:
                logv("dct reports apn request started " + this);
                // no need to do anything - we're already due some status update
                // intents
                break;
            case Phone.APN_REQUEST_FAILED:
                logv("dct reports apn request failed " + this);
                if (mPhoneService == null && mApnType == Phone.APN_TYPE_DEFAULT) {
                    // on startup we may try to talk to the phone before it's ready
                    // since the phone will come up enabled, go with that.
                    // TODO - this also comes up on telephony crash: if we think mobile data is
                    // off and the telephony stuff crashes and has to restart it will come up
                    // enabled (making a data connection).  We will then be out of sync.
                    // A possible solution is a broadcast when telephony restarts.
                    mEnabled = true;
                    return false;
                }
                // else fall through
            case Phone.APN_TYPE_NOT_AVAILABLE:
                // Default is always available, but may be off due to
                // AirplaneMode or E-Call or whatever..
                logv("dct reports apn type not available " + this);
                if (mApnType != Phone.APN_TYPE_DEFAULT) {
                    mEnabled = false;
                }
                break;
            default:
                Log.e(TAG, "Error in reconnect - unexpected response.");
                mEnabled = false;
                break;
        }
        return mEnabled;
    }

    /**
     * Turn on or off the mobile radio. No connectivity will be possible while the
     * radio is off. The operation is a no-op if the radio is already in the desired state.
     * @param turnOn {@code true} if the radio should be turned on, {@code false} if
     */
    public boolean setRadio(boolean turnOn) {
        getPhoneService(false);
        /*
         * If the phone process has crashed in the past, we'll get a
         * RemoteException and need to re-reference the service.
         */
        for (int retry = 0; retry < 2; retry++) {
            if (mPhoneService == null) {
                Log.w(TAG,
                    "Ignoring mobile radio request because could not acquire PhoneService");
                break;
            }

            try {
                return mPhoneService.setRadio(turnOn);
            } catch (RemoteException e) {
                if (retry == 0) getPhoneService(true);
            }
        }

        Log.w(TAG, "Could not set radio power to " + (turnOn ? "on" : "off"));
        return false;
    }

    /**
     * Tells the phone sub-system that the caller wants to
     * begin using the named feature. The only supported features at
     * this time are {@code Phone.FEATURE_ENABLE_MMS}, which allows an application
     * to specify that it wants to send and/or receive MMS data, and
     * {@code Phone.FEATURE_ENABLE_SUPL}, which is used for Assisted GPS.
     * @param feature the name of the feature to be used
     * @param callingPid the process ID of the process that is issuing this request
     * @param callingUid the user ID of the process that is issuing this request
     * @return an integer value representing the outcome of the request.
     * The interpretation of this value is feature-specific.
     * specific, except that the value {@code -1}
     * always indicates failure. For {@code Phone.FEATURE_ENABLE_MMS},
     * the other possible return values are
     * <ul>
     * <li>{@code Phone.APN_ALREADY_ACTIVE}</li>
     * <li>{@code Phone.APN_REQUEST_STARTED}</li>
     * <li>{@code Phone.APN_TYPE_NOT_AVAILABLE}</li>
     * <li>{@code Phone.APN_REQUEST_FAILED}</li>
     * </ul>
     */
    public int startUsingNetworkFeature(String feature, int callingPid, int callingUid) {
        return -1;
    }

    /**
     * Tells the phone sub-system that the caller is finished
     * using the named feature. The only supported feature at
     * this time is {@code Phone.FEATURE_ENABLE_MMS}, which allows an application
     * to specify that it wants to send and/or receive MMS data.
     * @param feature the name of the feature that is no longer needed
     * @param callingPid the process ID of the process that is issuing this request
     * @param callingUid the user ID of the process that is issuing this request
     * @return an integer value representing the outcome of the request.
     * The interpretation of this value is feature-specific, except that
     * the value {@code -1} always indicates failure.
     */
    public int stopUsingNetworkFeature(String feature, int callingPid, int callingUid) {
        return -1;
    }

    /**
     * Ensure that a network route exists to deliver traffic to the specified
     * host via the mobile data network.
     * @param hostAddress the IP address of the host to which the route is desired.
     * @return {@code true} on success, {@code false} on failure
     */
    @Override
    public boolean requestRouteToHost(InetAddress hostAddress) {
        String interfaceName = null;
        if (hostAddress instanceof Inet4Address) {
            interfaceName = getInterfaceName(IPVersion.IPV4);
        } else if (hostAddress instanceof Inet6Address) {
            interfaceName = getInterfaceName(IPVersion.IPV6);
        }

        logv("Requested host route to " + hostAddress.getHostAddress() +
                " for " + mApnType + "(" + interfaceName + ")");

        if (interfaceName != null) {
            return NetworkUtils.addHostRoute(interfaceName, hostAddress);
        } else {
            return false;
        }
    }

    /**
     * Return the IP addresses of the DNS servers available for the current
     * network interface.
     * @return a list of DNS addresses, with no holes.
     */
    @Override
    public String[] getNameServers() {
        //null interfaces are fine - taken care of by getNameServerList()
        String[] dnsPropNames = new String[] {
                /* static list - emulator etc.. */
                "net.eth0.dns1",
                "net.eth0.dns2",
                "net.eth0.dns3",
                "net.eth0.dns4",
                "net.gprs.dns1",
                "net.gprs.dns2",
                "net.ppp0.dns1",
                "net.ppp0.dns2",
                /* dynamic */
                "net." + getInterfaceName(IPVersion.IPV4) + ".dns1",
                "net." + getInterfaceName(IPVersion.IPV4) + ".dns2",
                "net." + getInterfaceName(IPVersion.IPV6) + ".dns1",
                "net." + getInterfaceName(IPVersion.IPV6) + ".dns2"
            };
        return getNameServerList(dnsPropNames);
    }

    @Override
    public String getInterfaceName(IPVersion ipv) {
        return mMobileInfo.get(ipv).mInterfaceName;
    }

    @Override
    public InetAddress getGateway(IPVersion ipv) {
        return mMobileInfo.get(ipv).mGateway;
    }

    @Override
    public InetAddress getIpAdress(IPVersion ipv) {
        return mMobileInfo.get(ipv).mIpAddress;
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer("Mobile data state: IPV4=");
        sb.append(mMobileInfo.get(IPVersion.IPV4));
        sb.append(", IPV6=");
        sb.append(mMobileInfo.get(IPVersion.IPV6));
        return sb.toString();
    }

   /**
     * Internal method supporting the ENABLE_MMS feature.
     * @param apnType the type of APN to be enabled or disabled (e.g., mms)
     * @param enable {@code true} to enable the specified APN type,
     * {@code false} to disable it.
     * @return an integer value representing the outcome of the request.
     */
    private int setEnableApn(String apnType, boolean enable) {
        getPhoneService(false);
        /*
         * If the phone process has crashed in the past, we'll get a
         * RemoteException and need to re-reference the service.
         */
        for (int retry = 0; retry < 2; retry++) {
            if (mPhoneService == null) {
                Log.w(TAG,
                    "Ignoring feature request because could not acquire PhoneService");
                break;
            }

            try {
                if (enable) {
                    return mPhoneService.enableApnType(apnType);
                } else {
                    return mPhoneService.disableApnType(apnType);
                }
            } catch (RemoteException e) {
                if (retry == 0) getPhoneService(true);
            }
        }

        Log.w(TAG, "Could not " + (enable ? "enable" : "disable")
                + " APN type \"" + apnType + "\"");
        return Phone.APN_REQUEST_FAILED;
    }

    public static String networkTypeToApnType(int netType) {
        switch(netType) {
            case ConnectivityManager.TYPE_MOBILE:
                return Phone.APN_TYPE_DEFAULT;  // TODO - use just one of these
            case ConnectivityManager.TYPE_MOBILE_MMS:
                return Phone.APN_TYPE_MMS;
            case ConnectivityManager.TYPE_MOBILE_SUPL:
                return Phone.APN_TYPE_SUPL;
            case ConnectivityManager.TYPE_MOBILE_DUN:
                return Phone.APN_TYPE_DUN;
            case ConnectivityManager.TYPE_MOBILE_HIPRI:
                return Phone.APN_TYPE_HIPRI;
            default:
                Log.e(TAG, "Error mapping networkType " + netType + " to apnType.");
                return null;
        }
    }

    void loge(String string) {
        Log.e(TAG, "[" + mApnType + "] " + string);
    }

    void logw(String string) {
        Log.w(TAG, "[" + mApnType + "] " + string);
    }

    void logd(String string) {
        Log.d(TAG, "[" + mApnType + "] " + string);
    }

    void logv(String string) {
        if (DBG)
            Log.v(TAG, "[" + mApnType + "] " + string);
    }

    void logi(String string) {
        if (DBG)
            Log.i(TAG, "[" + mApnType + "] " + string);
    }
}
