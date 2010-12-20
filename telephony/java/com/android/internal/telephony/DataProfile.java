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

import android.util.Log;

import com.android.internal.telephony.Phone.IPVersion;

public abstract class DataProfile {

    protected final static String LOG_TAG = "DataProfile";

    enum DataProfileType {
        PROFILE_TYPE_3GPP_PID(0),
        PROFILE_TYPE_3GPP_APN(1),
        PROFILE_TYPE_3GPP2_PID(2),
        PROFILE_TYPE_3GPP2_NAI(3);

        int id;

        private DataProfileType(int i) {
            this.id = i;
        }

        public int getid() {
            return id;
        }
    }

    /* - we assume that this profile will work by default.
     * - set it to false, only if network tells us that this profile cannot be used
     *   because of authentication issues.
     */
    private boolean worksWithIpv4  = true;
    private boolean worksWithIpv6  = true;

    /* set, if some data connection is established using this profile */
    private DataConnection ipv4Dc = null;
    private DataConnection ipv6Dc = null;

    /* package */boolean isWorking(IPVersion ipv) {
        return (ipv == IPVersion.IPV6) ? worksWithIpv6 : worksWithIpv4;
    }

    /* package */void setWorking(boolean working, IPVersion ipv) {
        if (ipv == IPVersion.IPV4)
            this.worksWithIpv4 = working;
        else if (ipv == IPVersion.IPV6)
            this.worksWithIpv6 = working;
    }

    /* package */ boolean isActive(IPVersion ipv) {
        if (ipv == IPVersion.IPV4) {
            return ipv4Dc != null;
        } else if (ipv == IPVersion.IPV6) {
            return ipv6Dc != null;
        }
        return false;
    }

    /* package */ boolean isActive() {
      return isActive(IPVersion.IPV4) || isActive(IPVersion.IPV6);
    }

    /* package */void setAsActive(IPVersion ipv, DataConnection dc) {
        if (ipv == IPVersion.IPV4) {
            // ASSERT: ipv4Dc == null
            if (ipv4Dc != null) {
                Log.e(LOG_TAG, "data profile already active on ipv4 : " + "[dp = "
                        + this.toString() + ", dc = " + dc.toString() + "]");
            }
            ipv4Dc = dc;
        } else if (ipv == IPVersion.IPV6) {
            // ASSERT: ipv6Dc == null
            if (ipv6Dc != null) {
                Log.e(LOG_TAG, "data profile already active on ipv6 : " + "[dp = "
                        + this.toString() + ", dc = " + dc.toString() + "]");
            }
            ipv6Dc = dc;
        }
    }

    /* package */void setAsInactive(IPVersion ipv) {
        if (ipv == IPVersion.IPV4)
            ipv4Dc = null;
        else if (ipv == IPVersion.IPV6)
            ipv6Dc = null;
    }

    public String toString() {
        return "[dpt=" + getDataProfileType() + ", active=" + isActive() + ", ";
    }

    /* some way to identify this data profile uniquely */
    abstract String toHash();

    public abstract String toShortString();

    /* package */abstract boolean canSupportIpVersion(IPVersion ipv);

    abstract boolean canHandleServiceType(DataServiceType type);

    abstract DataProfileType getDataProfileType();
}