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

import com.android.internal.telephony.DataPhone;
import com.android.internal.telephony.DataPhone.IPVersion;

/**
 * This class represents a apn setting for create PDP link
 */
public class ApnSetting extends DataProfile {

    String carrier;
    String apn;
    String proxy;
    String port;
    String mmsc;
    String mmsProxy;
    String mmsPort;
    String user;
    String password;
    int authType;
    @Deprecated String[] types;
    DataServiceType serviceTypes[];
    int id;
    String numeric;
    boolean supportsIPv4 = false;
    boolean supportsIPv6 = false;


    ApnSetting(int id, String numeric, String carrier, String apn, String proxy, String port,
            String mmsc, String mmsProxy, String mmsPort,
            String user, String password, int authType, String[] types, String ipVersion) {
        super();
        this.id = id;
        this.numeric = numeric;
        this.carrier = carrier;
        this.apn = apn;
        this.proxy = proxy;
        this.port = port;
        this.mmsc = mmsc;
        this.mmsProxy = mmsProxy;
        this.mmsPort = mmsPort;
        this.user = user;
        this.password = password;
        this.authType = authType;
        this.types = types;

        if (ipVersion == null) {
            this.supportsIPv4 = true;
        } else {
            String verList[] = ipVersion.split(",");
            for (String version : verList) {
                version = version.trim();
                if (version.equals("6")) {
                    this.supportsIPv6 = true;
                }
                if (version.equals("4")) {
                    this.supportsIPv4 = true;
                }
            }
        }
    }

    /*
     * simple way to compare apns - toString() + username + password
     */
    String toHash() {
        return this.toString() + this.user + this.password;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append(super.toString())
        .append(carrier)
        .append(", ").append(id)
        .append(", ").append(numeric)
        .append(", ").append(apn)
        .append(", ").append(proxy)
        .append(", ").append(mmsc)
        .append(", ").append(mmsProxy)
        .append(", ").append(mmsPort)
        .append(", ").append(port)
        .append(", ").append(authType)
        .append(", ").append(supportsIPv4)
        .append(", ").append(supportsIPv6)
        .append(", [");
        for (String t : types) {
            sb.append(", ").append(t);
        }
        sb.append("]");
        sb.append("]");
        return sb.toString();
    }

    public String toShortString() {
        StringBuilder sb = new StringBuilder();
        sb.append(super.toString())
          .append(numeric)
          .append(", ").append(apn)
          .append("]");
        return sb.toString();
    }

    @Deprecated
    boolean canHandleType(String type) {
        for (String t : types) {
            // DEFAULT handles all, and HIPRI is handled by DEFAULT
            if (t.equals(type) || t.equals(DataPhone.APN_TYPE_ALL) ||
                    (t.equals(DataPhone.APN_TYPE_DEFAULT) &&
                    type.equals(DataPhone.APN_TYPE_HIPRI))) {
                return true;
            }
        }
        return false;
    }

    boolean canHandleServiceType(DataServiceType type) {
        for (DataServiceType t : serviceTypes) {
            if (t == type
                    || (t == DataServiceType.SERVICE_TYPE_DEFAULT && type == DataServiceType.SERVICE_TYPE_HIPRI))
                return true;
        }
        return false;
    }

    DataProfileType getDataProfileType() {
        return DataProfileType.PROFILE_TYPE_3GPP_APN;
    }

    @Override
    boolean canSupportIpVersion(IPVersion ipv) {
        if (ipv == IPVersion.IPV6) {
            return supportsIPv6;
        } else if (ipv == IPVersion.IPV4) {
            return supportsIPv4;
        }
        return false;
    }
}
