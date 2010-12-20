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

import java.io.FileWriter;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

import com.android.internal.telephony.DataPhone.IPVersion;

import android.net.NetworkInfo.DetailedState;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;


/**
 * Each subclass of this class keeps track of the state of connectivity
 * of a network interface. All state information for a network should
 * be kept in a Tracker class. This superclass manages the
 * network-type-independent aspects of network state.
 *
 * {@hide}
 */
public abstract class NetworkStateTracker extends Handler {

    protected NetworkInfo mNetworkInfo;
    protected Context mContext;
    protected Handler mTarget;
    private boolean mTeardownRequested;

    private int mCachedGatewayAddr = 0;

    private static boolean DBG = false;
    private static final String TAG = "NetworkStateTracker";

    // Share the event space with ConnectivityService (which we can't see, but
    // must send events to).  If you change these, change ConnectivityService
    // too.
    private static final int MIN_NETWORK_STATE_TRACKER_EVENT = 1;
    private static final int MAX_NETWORK_STATE_TRACKER_EVENT = 100;

    public static final int EVENT_STATE_CHANGED = 1;
    public static final int EVENT_SCAN_RESULTS_AVAILABLE = 2;
    /**
     * arg1: 1 to show, 0 to hide
     * arg2: ID of the notification
     * obj: Notification (if showing)
     */
    public static final int EVENT_NOTIFICATION_CHANGED = 3;
    public static final int EVENT_CONFIGURATION_CHANGED = 4;
    public static final int EVENT_ROAMING_CHANGED = 5;
    public static final int EVENT_NETWORK_SUBTYPE_CHANGED = 6;

    public NetworkStateTracker(Context context,
            Handler target,
            int networkType,
            int subType,
            String typeName,
            String subtypeName) {
        super();
        mContext = context;
        mTarget = target;
        mTeardownRequested = false;

        this.mNetworkInfo = new NetworkInfo(networkType, subType, typeName, subtypeName);
    }

    public NetworkInfo getNetworkInfo() {
        return mNetworkInfo;
    }

    /**
     * Return the system properties name associated with the tcp buffer sizes
     * for this network.
     */
    public abstract String getTcpBufferSizesPropName();

    /**
     * Return the IP addresses of the DNS servers available for the mobile data
     * network interface.
     * @return a list of DNS addresses, with no holes.
     */
    abstract public String[] getNameServers();

    /*
     * Return the interface name that supports the specified IP Version.
     */
    abstract public String getInterfaceName(IPVersion ipv);

    /*
     * return the gateway associated with this interface.
     */
    abstract public InetAddress getGateway(IPVersion ipv);

    /*
     * TODO: This should come from native space, rather than relying on
     * telephony.
     */
    abstract public InetAddress getIpAdress(IPVersion ipv);

    /**
     * Return the IP addresses of the DNS servers available for this
     * network interface.
     * @param propertyNames the names of the system properties whose values
     * give the IP addresses. Properties with no values are skipped.
     * @return an array of {@code String}s containing the IP addresses
     * of the DNS servers, in dot-notation. This may have fewer
     * non-null entries than the list of names passed in, since
     * some of the passed-in names may have empty values.
     */
    static protected String[] getNameServerList(String[] propertyNames) {
        String[] dnsAddresses = new String[propertyNames.length];
        int i, j;

        for (i = 0, j = 0; i < propertyNames.length; i++) {
            String value = SystemProperties.get(propertyNames[i]);
            // The GSM layer sometimes sets a bogus DNS server address of
            // 0.0.0.0
            if (!TextUtils.isEmpty(value) && !TextUtils.equals(value, "0.0.0.0")) {
                dnsAddresses[j++] = value;
            }
        }
        return dnsAddresses;
    }


    boolean mPrivateDnsRouteSet[] = new boolean[] {false, false};

    public void addPrivateDnsRoutes() {
        addPrivateDnsRoutes(IPVersion.IPV4);
        addPrivateDnsRoutes(IPVersion.IPV6);
    }

    public void addPrivateDnsRoutes(IPVersion ipv) {
        String interfaceName = getInterfaceName(ipv);
        int index = ipv == IPVersion.IPV4 ? 0 : 1;

        if (interfaceName != null && mPrivateDnsRouteSet[index] == false) {
            for (String addrString : getNameServers()) {
                if (addrString != null) {
                    try {
                        InetAddress inetAddress = InetAddress.getByName(addrString);
                        if (ipv == IPVersion.IPV4 && inetAddress instanceof Inet4Address) {
                            Log.v(TAG, "adding ipv4 dns " + addrString + " through "
                                    + interfaceName);
                            NetworkUtils.addHostRoute(interfaceName, inetAddress);
                        } else if (ipv == IPVersion.IPV6 && inetAddress instanceof Inet6Address) {
                            Log.v(TAG, "adding ipv6 dns " + addrString + " through "
                                    + interfaceName);
                            NetworkUtils.addHostRoute(interfaceName, inetAddress);
                        }
                    } catch (UnknownHostException e) {
                        Log.w(TAG, " DNS address " + addrString + " : Exception " + e);
                    }
                }
            }
            mPrivateDnsRouteSet[index] = true;
        }
    }

    public void removePrivateDnsRoutes() {
        removePrivateDnsRoutes(IPVersion.IPV4);
        removePrivateDnsRoutes(IPVersion.IPV6);
    }

    public void removePrivateDnsRoutes(IPVersion ipv) {
        // TODO - we should do this explicitly but the NetUtils api doesnt
        // support this yet - must remove all. No worse than before

        String interfaceName = getInterfaceName(ipv);
        int index = ipv == IPVersion.IPV4 ? 0 : 1;

        if (interfaceName != null && mPrivateDnsRouteSet[index]) {
            Log.v(TAG, "remove " + ipv + " dns routes for " + mNetworkInfo.getTypeName() + " ("
                    + interfaceName + ")");
            NetworkUtils.removeHostRoutes(interfaceName);
        }
        mPrivateDnsRouteSet[index] = false;
    }

    boolean mDefaultRouteSet[] = new boolean[] {false, false};

    public void addDefaultRoute() {
        addDefaultRoute(IPVersion.IPV4);
        addDefaultRoute(IPVersion.IPV6);
    }

    public void addDefaultRoute(IPVersion ipv) {

        String interfaceName = getInterfaceName(ipv);
        InetAddress gateway = getGateway(ipv);
        int index = ipv == IPVersion.IPV4 ? 0 : 1;

        if ((interfaceName != null) && (gateway != null) && mDefaultRouteSet[index] == false) {
            Log.i(TAG, "addDefaultRoute (" + ipv + ") for " + mNetworkInfo.getTypeName() +
                    " ("+ interfaceName + "), GatewayAddr=" + gateway);
            if (NetworkUtils.addRoute(interfaceName, gateway, 0)) {
                mDefaultRouteSet[index] = true;
            } else {
                Log.e(TAG, "  Unable to add default route.");
            }
        }
    }

    public void removeDefaultRoute() {
        removeDefaultRoute(IPVersion.IPV4);
        removeDefaultRoute(IPVersion.IPV6);
    }

    public void removeDefaultRoute(IPVersion ipv) {

        String interfaceName = getInterfaceName(ipv);
        int index = ipv == IPVersion.IPV4 ? 0 : 1;

        if (interfaceName != null && mDefaultRouteSet[index] == true) {
            if (DBG) {
                Log.d(TAG, "removeDefaultRoute for " + mNetworkInfo.getTypeName() + " ("
                        + interfaceName + ")");
            }
            NetworkUtils.removeDefaultRoute(interfaceName);
        }
        mDefaultRouteSet[index] = false;
    }

    /**
     * Reads the network specific TCP buffer sizes from SystemProperties
     * net.tcp.buffersize.[default|wifi|umts|edge|gprs] and set them for system
     * wide use
     */
   public void updateNetworkSettings() {
        String key = getTcpBufferSizesPropName();
        String bufferSizes = SystemProperties.get(key);

        if (bufferSizes.length() == 0) {
            Log.w(TAG, key + " not found in system properties. Using defaults");

            // Setting to default values so we won't be stuck to previous values
            key = "net.tcp.buffersize.default";
            bufferSizes = SystemProperties.get(key);
        }

        // Set values in kernel
        if (bufferSizes.length() != 0) {
            if (DBG) {
                Log.v(TAG, "Setting TCP values: [" + bufferSizes
                        + "] which comes from [" + key + "]");
            }
            setBufferSize(bufferSizes);
        }
    }

    /**
     * Release the wakelock, if any, that may be held while handling a
     * disconnect operation.
     */
    public void releaseWakeLock() {
    }

    /**
     * Writes TCP buffer sizes to /sys/kernel/ipv4/tcp_[r/w]mem_[min/def/max]
     * which maps to /proc/sys/net/ipv4/tcp_rmem and tcpwmem
     * 
     * @param bufferSizes in the format of "readMin, readInitial, readMax,
     *        writeMin, writeInitial, writeMax"
     */
    private void setBufferSize(String bufferSizes) {
        try {
            String[] values = bufferSizes.split(",");

            if (values.length == 6) {
              final String prefix = "/sys/kernel/ipv4/tcp_";
                stringToFile(prefix + "rmem_min", values[0]);
                stringToFile(prefix + "rmem_def", values[1]);
                stringToFile(prefix + "rmem_max", values[2]);
                stringToFile(prefix + "wmem_min", values[3]);
                stringToFile(prefix + "wmem_def", values[4]);
                stringToFile(prefix + "wmem_max", values[5]);
            } else {
                Log.w(TAG, "Invalid buffersize string: " + bufferSizes);
            }
        } catch (IOException e) {
            Log.w(TAG, "Can't set tcp buffer sizes:" + e);
        }
    }

    /**
     * Writes string to file. Basically same as "echo -n $string > $filename"
     * 
     * @param filename
     * @param string
     * @throws IOException
     */
    private void stringToFile(String filename, String string) throws IOException {
        FileWriter out = new FileWriter(filename);
        try {
            out.write(string);
        } finally {
            out.close();
        }
    }

    /**
     * Record the detailed state of a network, and if it is a
     * change from the previous state, send a notification to
     * any listeners.
     * @param state the new @{code DetailedState}
     */
    public void setDetailedState(NetworkInfo.DetailedState state) {
        if (state == DetailedState.CONNECTED) {
            /*
             * TODO: this function is called by wifi. We assume that if wifi
             * says CONNECTED, both v4 and v6 is connected. This may not be true
             * always but no other way of knowing this now.
             */
            setDetailedState(state, true, true, null, null);
        } else {
            setDetailedState(state, false, false, null, null);
        }
    }

    /**
     * Record the detailed state of a network, and if it is a
     * change from the previous state, send a notification to
     * any listeners.
     * @param state the new @{code DetailedState}
     * @param reason a {@code String} indicating a reason for the state change,
     * if one was supplied. May be {@code null}.
     * @param extraInfo optional {@code String} providing extra information about the state change
     */
    public void setDetailedState(NetworkInfo.DetailedState state, boolean isIpv4Connected,
            boolean isIpv6Connected, String reason, String extraInfo) {
        if (DBG) Log.d(TAG, "setDetailed state, old ="+mNetworkInfo.getDetailedState()+" and new state="+state);

        boolean wasConnecting = (mNetworkInfo.getState() == NetworkInfo.State.CONNECTING);
        String lastReason = mNetworkInfo.getReason();
        /*
         * If a reason was supplied when the CONNECTING state was entered, and no
         * reason was supplied for entering the CONNECTED state, then retain the
         * reason that was supplied when going to CONNECTING.
         */
        if (wasConnecting && state == NetworkInfo.DetailedState.CONNECTED && reason == null
                && lastReason != null)
            reason = lastReason;
        mNetworkInfo.setDetailedState(state, isIpv4Connected, isIpv6Connected, reason, extraInfo);
        Message msg = mTarget.obtainMessage(EVENT_STATE_CHANGED, mNetworkInfo);
        msg.sendToTarget();
    }

    protected void setDetailedStateInternal(NetworkInfo.DetailedState state) {

        if (state == DetailedState.CONNECTED) {
            /*
             * TODO: this function is called by wifi. We assume that if wifi
             * says CONNECTED, both v4 and v6 is connected. This may not be true
             * always but no other way of knowing this now.
             */
            mNetworkInfo.setDetailedState(state, true, true, null, null);
        } else {
            mNetworkInfo.setDetailedState(state, false, false, null, null);
        }
    }

    public void setTeardownRequested(boolean isRequested) {
        mTeardownRequested = isRequested;
    }

    public boolean isTeardownRequested() {
        return mTeardownRequested;
    }

    /**
     * Send a  notification that the results of a scan for network access
     * points has completed, and results are available.
     */
    protected void sendScanResultsAvailable() {
        Message msg = mTarget.obtainMessage(EVENT_SCAN_RESULTS_AVAILABLE, mNetworkInfo);
        msg.sendToTarget();
    }

    /**
     * Record the roaming status of the device, and if it is a change from the previous
     * status, send a notification to any listeners.
     * @param isRoaming {@code true} if the device is now roaming, {@code false}
     * if it is no longer roaming.
     */
    protected void setRoamingStatus(boolean isRoaming) {
        if (isRoaming != mNetworkInfo.isRoaming()) {
            mNetworkInfo.setRoaming(isRoaming);
            Message msg = mTarget.obtainMessage(EVENT_ROAMING_CHANGED, mNetworkInfo);
            msg.sendToTarget();
        }
    }

    protected void setSubtype(int subtype, String subtypeName) {
        int oldSubtype = mNetworkInfo.getSubtype();
        if (subtype != oldSubtype) {
            mNetworkInfo.setSubtype(subtype, subtypeName);
            if (mNetworkInfo.isConnected()) {
                Message msg = mTarget.obtainMessage(
                        EVENT_NETWORK_SUBTYPE_CHANGED, oldSubtype, 0, mNetworkInfo);
                msg.sendToTarget();
            }
        }
    }

    public abstract void startMonitoring();

    /**
     * Disable connectivity to a network
     * @return {@code true} if a teardown occurred, {@code false} if the
     * teardown did not occur.
     */
    public abstract boolean teardown();

    /**
     * Reenable connectivity to a network after a {@link #teardown()}.
     */
    public abstract boolean reconnect();

    /**
     * Turn the wireless radio off for a network.
     * @param turnOn {@code true} to turn the radio on, {@code false}
     */
    public abstract boolean setRadio(boolean turnOn);

    /**
     * Returns an indication of whether this network is available for
     * connections. A value of {@code false} means that some quasi-permanent
     * condition prevents connectivity to this network.
     */
    public abstract boolean isAvailable();

    /**
     * Tells the underlying networking system that the caller wants to
     * begin using the named feature. The interpretation of {@code feature}
     * is completely up to each networking implementation.
     * @param feature the name of the feature to be used
     * @param callingPid the process ID of the process that is issuing this request
     * @param callingUid the user ID of the process that is issuing this request
     * @return an integer value representing the outcome of the request.
     * The interpretation of this value is specific to each networking
     * implementation+feature combination, except that the value {@code -1}
     * always indicates failure.
     */
    public abstract int startUsingNetworkFeature(String feature, int callingPid, int callingUid);

    /**
     * Tells the underlying networking system that the caller is finished
     * using the named feature. The interpretation of {@code feature}
     * is completely up to each networking implementation.
     * @param feature the name of the feature that is no longer needed.
     * @param callingPid the process ID of the process that is issuing this request
     * @param callingUid the user ID of the process that is issuing this request
     * @return an integer value representing the outcome of the request.
     * The interpretation of this value is specific to each networking
     * implementation+feature combination, except that the value {@code -1}
     * always indicates failure.
     */
    public abstract int stopUsingNetworkFeature(String feature, int callingPid, int callingUid);

    /**
     * Ensure that a network route exists to deliver traffic to the specified
     * host via this network interface.
     * @param hostAddress the IP address of the host to which the route is desired
     * @return {@code true} on success, {@code false} on failure
     */
    public boolean requestRouteToHost(InetAddress hostAddress) {
        return false;
    }

    /**
     * Interprets scan results. This will be called at a safe time for
     * processing, and from a safe thread.
     */
    public void interpretScanResultsAvailable() {
    }

    public String getInterfaceName() {
        return mInterfaceName;
    }
}
