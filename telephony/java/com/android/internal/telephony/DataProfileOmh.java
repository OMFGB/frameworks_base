/*
 * Copyright (c) 2010, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of Code Aurora Forum, Inc. nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.internal.telephony;

import com.android.internal.telephony.Phone.IPVersion;
import com.android.internal.telephony.DataServiceType;

class DataProfileOmh extends DataProfile {

    /**
     *  OMH spec 3GPP2 C.S0023-D defines the application types in terms of a
     *  32-bit mask where each bit represents one application
     *
     *  Application bit and the correspondign app type is listed below:
     *  1 Unspecified (all applications use the same profile)
     *  2 MMS
     *  3 Browser
     *  4 BREW
     *  5 Java
     *  6 LBS
     *  7 Terminal (tethered mode for terminal access)
     *  8-32 Reserved for future use
     *
     *  From this list all the implemented app types are listed in the enum
     */
    enum DataProfileTypeModem {
        /* Static mapping of OMH profiles to Android Service Types */
        PROFILE_TYPE_UNSPECIFIED(0x00000001, DataServiceType.SERVICE_TYPE_DEFAULT),
        PROFILE_TYPE_MMS(0x00000002, DataServiceType.SERVICE_TYPE_MMS),
        PROFILE_TYPE_LBS(0x00000020, DataServiceType.SERVICE_TYPE_SUPL),
        PROFILE_TYPE_TETHERED(0x00000040, DataServiceType.SERVICE_TYPE_DUN);

        int id;
        DataServiceType serviceType;

        private DataProfileTypeModem(int i, DataServiceType dst) {
            this.id = i;
            this.serviceType = dst;
        }

        public int getid() {
            return id;
        }

        public DataServiceType getDataServiceType() {
            return serviceType;
        }

        public static DataProfileTypeModem getDataProfileTypeModem(DataServiceType dst) {
            DataProfileTypeModem  dptModem = PROFILE_TYPE_UNSPECIFIED;
            switch (dst) {
                case SERVICE_TYPE_DEFAULT:
                    dptModem = PROFILE_TYPE_UNSPECIFIED;
                    break;
                case SERVICE_TYPE_MMS:
                    dptModem = PROFILE_TYPE_MMS;
                    break;
                case SERVICE_TYPE_SUPL:
                    dptModem = PROFILE_TYPE_LBS;
                    break;
                case SERVICE_TYPE_DUN:
                    dptModem = PROFILE_TYPE_TETHERED;
                    break;
                 default:
                     /*
                      * TODO: What do we do for spl. service types such as HIPRI and VERIZON?
                      */
                     dptModem = PROFILE_TYPE_UNSPECIFIED;
                     break;
            }
            return dptModem;
        }
    }

    private int DATA_PROFILE_OMH_PRIORITY_LOWEST = 255;

    private int DATA_PROFILE_OMH_PRIORITY_HIGHEST = 0;

    private DataProfileTypeModem mDataProfileModem;

    private int serviceTypeMasks = 0;

    /* ID of the profile in the modem */
    private int mProfileId = 0;

    /* Priority of this profile in the modem */
    private int mPriority = 0;

    public DataProfileOmh() {
        this.mProfileId = 0;
        this.mPriority = 0;
    }

    public DataProfileOmh(int profileId, int priority) {
        this.mProfileId = profileId;
        this.mPriority = priority;
    }

    @Override
    boolean canHandleServiceType(DataServiceType type) {
        return ( 0 != (serviceTypeMasks & DataProfileTypeModem.
                getDataProfileTypeModem(type).getid()));
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
        return DataProfileType.PROFILE_TYPE_3GPP2_OMH;
    }

    @Override
    public String toShortString() {
        return "DataProfile OMH";
    }

    @Override
    String toHash() {
        return this.toString() + mProfileId + mPriority;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append(super.toString())
        .append(mProfileId)
        .append(", ").append(mPriority);
        sb.append("]");
        return sb.toString();
    }

    public void setDataProfileTypeModem(DataProfileTypeModem modemProfile) {
        mDataProfileModem = modemProfile;
    }

    public DataProfileTypeModem getDataProfileTypeModem() {
        return mDataProfileModem;
    }

    public void setProfileId(int profileId) {
        mProfileId = profileId;
    }

    public void setPriority(int priority) {
        mPriority = priority;
    }

    /* priority defined from 0..255; 0 is highest */
    public boolean isPriorityHigher(int priority) {
        return isValidPriority(priority) && (mPriority < priority);
    }

    /* priority defined from 0..255; 0 is highest */
    public boolean isPriorityLower(int priority) {
        return isValidPriority(priority) && mPriority > priority;
    }

    public boolean isValidPriority() {
        return isValidPriority(mPriority);
    }

    /* NOTE: priority values are reverse, lower number = higher priority */
    private boolean isValidPriority(int priority) {
        return priority >= DATA_PROFILE_OMH_PRIORITY_HIGHEST && priority <= DATA_PROFILE_OMH_PRIORITY_LOWEST;
    }

    public int getProfileId() {
        return mProfileId;
    }

    public int getPriority() {
        return mPriority;
    }

    public void addServiceType(DataProfileTypeModem modemProfile) {
        serviceTypeMasks |= modemProfile.getid();
    }
}
