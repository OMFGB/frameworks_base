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

import com.android.internal.telephony.Phone.IPVersion;

class CdmaNAI extends DataProfile {

    /* TODO: This class is a TODO! */

    /* ID of the profile in the modem */
    private int mProfileId = 0;

    @Override
    boolean canHandleServiceType(DataServiceType type) {
        return true;
    }

    @Override
    boolean canSupportIpVersion(IPVersion ipv) {
        if (ipv == IPVersion.IPV4 || ipv == IPVersion.IPV6)
            return true;
        else
            return false;
    }

    @Override
    DataProfileType getDataProfileType() {
        return DataProfileType.PROFILE_TYPE_3GPP2_NAI;
    }

    public int getProfileId() {
        return mProfileId;
    }

    @Override
    public String toShortString() {
        return "CDMA NAI";
    }

    @Override
    String toHash() {
        return this.toString();
    }

}
