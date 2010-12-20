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

import android.os.Handler;
import android.os.Message;
import android.telephony.ServiceState;
import com.android.internal.telephony.DataConnection;
import java.util.List;

/**
 * Internal interface used to control the phone; SDK developers cannot
 * obtain this interface.
 *
 * {@hide}
 *
 */
public interface DataPhone {

    /**
     * The state of a data connection.
     * <ul>
     * <li>CONNECTED = IP traffic should be available</li>
     * <li>CONNECTING = Currently setting up data connection</li>
     * <li>DISCONNECTED = IP not available</li>
     * <li>SUSPENDED = connection is created but IP traffic is
     *                 temperately not available. i.e. voice call is in place
     *                 in 2G network</li>
     * </ul>
     */
    enum DataState {
        CONNECTED, CONNECTING, DISCONNECTED, SUSPENDED;
    };

    public enum DataActivityState {
        /**
         * The state of a data activity.
         * <ul>
         * <li>NONE = No traffic</li>
         * <li>DATAIN = Receiving IP ppp traffic</li>
         * <li>DATAOUT = Sending IP ppp traffic</li>
         * <li>DATAINANDOUT = Both receiving and sending IP ppp traffic</li>
         * <li>DORMANT = The data connection is still active,
                                     but physical link is down</li>
         * </ul>
         */
        NONE, DATAIN, DATAOUT, DATAINANDOUT, DORMANT;
    };

    public enum IPVersion {
        IPV4, IPV6
    };

    // Key used to read/write "disable data connection on boot" pref (used for testing)
    public static final String DATA_DISABLED_ON_BOOT_KEY = "disabled_on_boot_key";

    static final String FAILURE_REASON_KEY = "reason";
    static final String STATE_CHANGE_REASON_KEY = "reason";
    static final String DATA_APN_TYPES_KEY = "apnType";
    static final String DATA_APN_KEY = "apn";

    static final String DATA_IFACE_NAME_KEY = "iface"; //ipv4 interface
    static final String DATA_GATEWAY_KEY = "gateway";
    static final String DATA_IFACE_IPV6_NAME_KEY = "ifaceIpv6";
    static final String NETWORK_UNAVAILABLE_KEY = "networkUnvailable";

    /**
     * APN types for data connections.  These are usage categories for an APN
     * entry.  One APN entry may support multiple APN types, eg, a single APN
     * may service regular internet traffic ("default") as well as MMS-specific
     * connections.<br/>
     * APN_TYPE_ALL is a special type to indicate that this APN entry can
     * service all data connections.
     */
    static final String APN_TYPE_ALL = "*";
    /** APN type for default data traffic */
    static final String APN_TYPE_DEFAULT = "default";
    /** APN type for MMS traffic */
    static final String APN_TYPE_MMS = "mms";
    /** APN type for SUPL assisted GPS */
    static final String APN_TYPE_SUPL = "supl";
    /** APN type for DUN traffic */
    static final String APN_TYPE_DUN = "dun";
    /** APN type for HiPri traffic */
    static final String APN_TYPE_HIPRI = "hipri";
    /** APN type for Verizon applications */
    static final String APN_TYPE_VERIZON = "verizon";


    // "Features" accessible through the connectivity manager
    static final String FEATURE_ENABLE_MMS = "enableMMS";
    static final String FEATURE_ENABLE_SUPL = "enableSUPL";
    static final String FEATURE_ENABLE_DUN = "enableDUN";
    static final String FEATURE_ENABLE_HIPRI = "enableHIPRI";
    static final String FEATURE_ENABLE_VERIZON = "enableVerizon";

    /**
     * Return codes for <code>enableApnType()</code>
     */
    static final int APN_ALREADY_ACTIVE     = 0;
    static final int APN_REQUEST_STARTED    = 1;
    static final int APN_TYPE_NOT_AVAILABLE = 2;
    static final int APN_REQUEST_FAILED     = 3;

    /**
     * Optional reasons for disconnect and connect
     */
    static final String REASON_ROAMING_ON = "roamingOn";
    static final String REASON_ROAMING_OFF = "roamingOff";
    static final String REASON_DATA_DISABLED = "dataDisabled";
    static final String REASON_DATA_ENABLED = "dataEnabled";
    static final String REASON_GPRS_ATTACHED = "gprsAttached";
    static final String REASON_GPRS_DETACHED = "gprsDetached";
    static final String REASON_CDMA_DATA_ATTACHED = "cdmaDataAttached";
    static final String REASON_CDMA_DATA_DETACHED = "cdmaDataDetached";
    static final String REASON_APN_CHANGED = "apnChanged";
    static final String REASON_APN_SWITCHED = "apnSwitched";
    static final String REASON_APN_FAILED = "apnFailed";
    static final String REASON_RESTORE_DEFAULT_APN = "restoreDefaultApn";
    static final String REASON_RADIO_TURNED_OFF = "radioTurnedOff";
    static final String REASON_PDP_RESET = "pdpReset";
    static final String REASON_VOICE_CALL_ENDED = "2GVoiceCallEnded";
    static final String REASON_VOICE_CALL_STARTED = "2GVoiceCallStarted";
    static final String REASON_PS_RESTRICT_ENABLED = "psRestrictEnabled";
    static final String REASON_PS_RESTRICT_DISABLED = "psRestrictDisabled";
    static final String REASON_SIM_LOADED = "simLoaded";
    static final String REASON_RADIO_TECHNOLOGY_CHANGED = "radioTechnologyChanged";

    /**
     * Get the current DataState. No change notification exists at this
     * interface -- use
     * {@link com.android.telephony.PhoneStateListener PhoneStateListener}
     * instead.
     */
    @Deprecated
    DataState getDataConnectionState();

    /**
     * Get the current DataState. No change notification exists at this
     * interface -- use
     * {@link com.android.telephony.PhoneStateListener PhoneStateListener}
     * instead.
     */
    DataState getDataConnectionState(String type, IPVersion ipv);

    /**
     * Get the current DataActivityState. No change notification exists at this
     * interface -- use
     * {@link TelephonyManager} instead.
     */
    DataActivityState getDataActivityState();

    /**
     * Get the current data network ServiceState. Use
     * <code>registerForDataServiceStateChanged</code> to be informed of
     * updates.
     */
    ServiceState getDataServiceState();

    /**
     * Disables the DNS check (i.e., allows "0.0.0.0").
     * Useful for lab testing environment.
     * @param b true disables the check, false enables.
     */
    void disableDnsCheck(boolean b);

    /**
     * Returns true if the DNS check is currently disabled.
     */
    boolean isDnsCheckDisabled();

    /**
     * Returns an array of string identifiers for the APN types serviced by the
     * currently active or last connected APN.
     *  @return The string array.
     */
    String[] getActiveApnTypes();

    /**
     * Returns a string identifier for currently active or last connected APN.
     *  @return The string name.
     */
    @Deprecated
    String getActiveApn();

    /**
     * Returns a string identifier for currently active APN on the specified apn
     * type and ip version if any.
     *
     * @return The string name.
     */

    String getActiveApn(String type, IPVersion ipv);

    /**
     * Get the current active Data Call list, substitutes getPdpContextList
     *
     * @param response <strong>On success</strong>, "response" bytes is
     * made available as:
     * (String[])(((AsyncResult)response.obj).result).
     * <strong>On failure</strong>,
     * (((AsyncResult)response.obj).result) == null and
     * (((AsyncResult)response.obj).exception) being an instance of
     * com.android.internal.telephony.gsm.CommandException
     */
    void getDataCallList(Message response);

    /**
     * Get current multiple data connection status
     *
     * @return list of data connections
     */
    List<DataConnection> getCurrentDataConnectionList();

    /**
     * @return true if enable data connection on roaming
     */
    boolean getDataRoamingEnabled();

    /**
     * @param enable set true if enable data connection on roaming
     */
    void setDataRoamingEnabled(boolean enable);

    /**
     * Allow mobile data connections.
     * @return {@code true} if the operation started successfully
     * <br/>{@code false} if it
     * failed immediately.<br/>
     * Even in the {@code true} case, it may still fail later
     * during setup, in which case an asynchronous indication will
     * be supplied.
     */
    boolean enableDataConnectivity();

    /**
     * Disallow mobile data connections, and terminate any that
     * are in progress.
     * @return {@code true} if the operation started successfully
     * <br/>{@code false} if it
     * failed immediately.<br/>
     * Even in the {@code true} case, it may still fail later
     * during setup, in which case an asynchronous indication will
     * be supplied.
     */
    boolean disableDataConnectivity();

    /**
     * Report the current state of data connectivity (enabled or disabled)
     * @return {@code false} if data connectivity has been explicitly disabled,
     * {@code true} otherwise.
     */
    boolean isDataConnectivityEnabled();

    /**
     * Enables the specified APN type. Only works for "special" APN types,
     * i.e., not the default APN.
     * @param type The desired APN type. Cannot be {@link #APN_TYPE_DEFAULT}.
     * @return <code>APN_ALREADY_ACTIVE</code> if the current APN
     * services the requested type.<br/>
     * <code>APN_TYPE_NOT_AVAILABLE</code> if the carrier does not
     * support the requested APN.<br/>
     * <code>APN_REQUEST_STARTED</code> if the request has been initiated.<br/>
     * <code>APN_REQUEST_FAILED</code> if the request was invalid.<br/>
     * A <code>ACTION_ANY_DATA_CONNECTION_STATE_CHANGED</code> broadcast will
     * indicate connection state progress.
     */
    int enableApnType(String type);

    /**
     * Disables the specified APN type, and switches back to the default APN,
     * if necessary. Switching to the default APN will not happen if default
     * data traffic has been explicitly disabled via a call to {@link #disableDataConnectivity}.
     * <p/>Only works for "special" APN types,
     * i.e., not the default APN.
     * @param type The desired APN type. Cannot be {@link #APN_TYPE_DEFAULT}.
     * @return <code>APN_ALREADY_ACTIVE</code> if the default APN
     * is already active.<br/>
     * <code>APN_REQUEST_STARTED</code> if the request to switch to the default
     * APN has been initiated.<br/>
     * <code>APN_REQUEST_FAILED</code> if the request was invalid.<br/>
     * A <code>ACTION_ANY_DATA_CONNECTION_STATE_CHANGED</code> broadcast will
     * indicate connection state progress.
     */
    int disableApnType(String type);

    /**
     * Report on whether data connectivity is allowed.
     */
    boolean isDataConnectivityPossible();

    /**
     * Returns the name of the network interface used by the specified APN type.
     */
    @Deprecated
    String getInterfaceName(String apnType);

    /**
     * Returns the name of the network interface used by the specified APN type.
     */
    String getInterfaceName(String apnType, IPVersion ipv);

    /**
     * Returns the IP address of the network interface used by the specified
     * APN type.
     */
    @Deprecated
    String getIpAddress(String apnType);

    /**
     * Returns the IP address of the network interface used by the specified
     * APN type on the specified IPVersion.
     */
    String getIpAddress(String apnType, IPVersion ipv);

    /**
     * Returns the gateway for the network interface used by the specified APN
     * type.
     */
    @Deprecated
    String getGateway(String apnType);

    /**
     * Returns the gateway for the network interface used by the specified APN
     * type on the specified IPVersion
     */
    String getGateway(String apnType, IPVersion ipv);

    /**
     * Returns the DNS servers for the network interface used by the specified
     * APN type.
     */
    @Deprecated
    public String[] getDnsServers(String apnType);

    /**
     * Returns the DNS servers for the network interface used by the specified
     * APN type on specified ip version
     */
    public String[] getDnsServers(String apnType, IPVersion ipv);

    @Deprecated
    public void notifyDataActivity();

    /**
     * Register for data service state changed.
     * Message.obj will contain an AsyncResult.
     * AsyncResult.result will be a ServiceState instance
     */
    void registerForDataServiceStateChanged(Handler h, int what, Object obj);

    /**
     * Unregisters for voice service state change notification.
     * Extraneous calls are tolerated silently
     */
    void unregisterForDataServiceStateChanged(Handler h);
}
