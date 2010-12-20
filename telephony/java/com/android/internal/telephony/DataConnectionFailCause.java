/*
 * Copyright (C) 2006 The Android Open Source Project
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

public enum DataConnectionFailCause {
    NONE,

    // following correspond to codes defined in TS 24.0083 section 6.1.3.1.3
    OPERATOR_BARRED,
    INSUFFICIENT_RESOURCES,
    MISSING_UNKOWN_APN,
    UNKNOWN_PDP_ADDRESS,
    USER_AUTHENTICATION,
    ACTIVATION_REJECT_GGSN,
    ACTIVATION_REJECT_UNSPECIFIED,
    SERVICE_OPTION_NOT_SUPPORTED,
    SERVICE_OPTION_NOT_SUBSCRIBED,
    SERVICE_OPTION_OUT_OF_ORDER,
    NSAPI_IN_USE,
    PROTOCOL_ERRORS,
    UNKNOWN,

    // following are custom codes that not all modems might be able to report
    REGISTRATION_FAIL,
    GPRS_REGISTRATION_FAIL,
    RADIO_NOT_AVAILABLE,
    RADIO_ERROR_RETRY,
    PREF_RADIO_TECHNOLOGY_CHANGED,
    TETHERED_MODE_CALL_ON,
    IP_VERSION_NOT_SUPPORTED,
    PDP_NOT_AVAILABLE;

    /*
     * indicates that setup failure is caused by some sort of data profile
     * issues
     */
    public boolean isDataProfileFailure() {
        return (this == MISSING_UNKOWN_APN) || (this == USER_AUTHENTICATION);
    }

   /* indicates that setup failure is caused by lack of network resources,
    * network supports no mpdp, or limited pdp etc. it could also be because
    * of tethered mode call being active.
    */
   public boolean isPdpAvailabilityFailure() {
       return (this == PDP_NOT_AVAILABLE);
   }

    public boolean isPermanentFail() {
        return (this == OPERATOR_BARRED) || (this == MISSING_UNKOWN_APN) ||
               (this == UNKNOWN_PDP_ADDRESS) || (this == USER_AUTHENTICATION) ||
               (this == ACTIVATION_REJECT_GGSN) || (this == ACTIVATION_REJECT_UNSPECIFIED) ||
               (this == SERVICE_OPTION_NOT_SUPPORTED) ||
               (this == SERVICE_OPTION_NOT_SUBSCRIBED) || (this == NSAPI_IN_USE) ||
               (this == PROTOCOL_ERRORS) || (this == RADIO_NOT_AVAILABLE);
    }

    public boolean isEventLoggable() {
        return (this == OPERATOR_BARRED) || (this == INSUFFICIENT_RESOURCES) ||
                (this == UNKNOWN_PDP_ADDRESS) || (this == USER_AUTHENTICATION) ||
                (this == ACTIVATION_REJECT_GGSN) || (this == ACTIVATION_REJECT_UNSPECIFIED) ||
                (this == SERVICE_OPTION_NOT_SUBSCRIBED) ||
                (this == SERVICE_OPTION_NOT_SUPPORTED) ||
                (this == SERVICE_OPTION_OUT_OF_ORDER) || (this == NSAPI_IN_USE) ||
                (this == PROTOCOL_ERRORS);
    }

    public boolean canRetryAfterDcDisconnect() {
        if (isPermanentFail())
            return false;
        switch (this) {
            case TETHERED_MODE_CALL_ON:
                return false;
            default:
                return true;
        }
    }

    public static DataConnectionFailCause getDataCallSetupFailCause(int rilCause) {
        DataConnectionFailCause cause;

        switch (rilCause) {
            case PDP_FAIL_OPERATOR_BARRED:
                cause = DataConnectionFailCause.OPERATOR_BARRED;
                break;
            case PDP_FAIL_INSUFFICIENT_RESOURCES:
                cause = DataConnectionFailCause.INSUFFICIENT_RESOURCES;
                break;
            case PDP_FAIL_MISSING_UNKOWN_APN:
                cause = DataConnectionFailCause.MISSING_UNKOWN_APN;
                break;
            case PDP_FAIL_UNKNOWN_PDP_ADDRESS_TYPE:
                cause = DataConnectionFailCause.UNKNOWN_PDP_ADDRESS;
                break;
            case PDP_FAIL_USER_AUTHENTICATION:
                cause = DataConnectionFailCause.USER_AUTHENTICATION;
                break;
            case PDP_FAIL_ACTIVATION_REJECT_GGSN:
                cause = DataConnectionFailCause.ACTIVATION_REJECT_GGSN;
                break;
            case PDP_FAIL_ACTIVATION_REJECT_UNSPECIFIED:
                cause = DataConnectionFailCause.ACTIVATION_REJECT_UNSPECIFIED;
                break;
            case PDP_FAIL_SERVICE_OPTION_OUT_OF_ORDER:
                cause = DataConnectionFailCause.SERVICE_OPTION_OUT_OF_ORDER;
                break;
            case PDP_FAIL_SERVICE_OPTION_NOT_SUPPORTED:
                cause = DataConnectionFailCause.SERVICE_OPTION_NOT_SUPPORTED;
                break;
            case PDP_FAIL_SERVICE_OPTION_NOT_SUBSCRIBED:
                cause = DataConnectionFailCause.SERVICE_OPTION_NOT_SUBSCRIBED;
                break;
            case PDP_FAIL_NSAPI_IN_USE:
                cause = DataConnectionFailCause.NSAPI_IN_USE;
                break;
            case PDP_FAIL_PROTOCOL_ERRORS:
                cause = DataConnectionFailCause.PROTOCOL_ERRORS;
                break;
            case PDP_FAIL_ERROR_UNSPECIFIED:
                cause = DataConnectionFailCause.UNKNOWN;
                break;
            case PDP_FAIL_REGISTRATION_FAIL:
                cause = DataConnectionFailCause.REGISTRATION_FAIL;
                break;
            case PDP_FAIL_GPRS_REGISTRATION_FAIL:
                cause = DataConnectionFailCause.GPRS_REGISTRATION_FAIL;
                break;
            case PDP_FAIL_ONLY_IPV4_ALLOWED:
            case PDP_FAIL_ONLY_IPV6_ALLOWED:
                cause = DataConnectionFailCause.IP_VERSION_NOT_SUPPORTED;
                break;
            //TODO: fusion - add radio tech changed, tethered mode on etc.
            default:
                cause = DataConnectionFailCause.UNKNOWN;
        }
        return cause;
    }

    public static DataConnectionFailCause getDataConnectionDisconnectCause(int rilCause) {
        return getDataCallSetupFailCause(rilCause);
    }

    private static final int PDP_FAIL_OPERATOR_BARRED = 0x08;
    private static final int PDP_FAIL_INSUFFICIENT_RESOURCES = 0x1A;
    private static final int PDP_FAIL_MISSING_UNKOWN_APN = 0x1B;
    private static final int PDP_FAIL_UNKNOWN_PDP_ADDRESS_TYPE = 0x1C;
    private static final int PDP_FAIL_USER_AUTHENTICATION = 0x1D;
    private static final int PDP_FAIL_ACTIVATION_REJECT_GGSN = 0x1E;
    private static final int PDP_FAIL_ACTIVATION_REJECT_UNSPECIFIED = 0x1F;
    private static final int PDP_FAIL_SERVICE_OPTION_NOT_SUPPORTED = 0x20;
    private static final int PDP_FAIL_SERVICE_OPTION_NOT_SUBSCRIBED = 0x21;
    private static final int PDP_FAIL_SERVICE_OPTION_OUT_OF_ORDER = 0x22;
    private static final int PDP_FAIL_NSAPI_IN_USE = 0x23;
    private static final int PDP_FAIL_NETWORK_FAILURE = 0x26;
    private static final int PDP_FAIL_ONLY_IPV4_ALLOWED = 0x32;
    private static final int PDP_FAIL_ONLY_IPV6_ALLOWED = 0x33;
    private static final int PDP_FAIL_ONLY_SINGLE_BEARER_ALLOWED = 0x34;
    private static final int PDP_FAIL_PROTOCOL_ERRORS   = 0x6F;
    private static final int PDP_FAIL_ERROR_UNSPECIFIED = 0xffff;

    private static final int PDP_FAIL_REGISTRATION_FAIL = -1;
    private static final int PDP_FAIL_GPRS_REGISTRATION_FAIL = -2;
}
