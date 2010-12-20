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

import java.util.Arrays;
import java.util.Comparator;

import android.text.TextUtils;

/*
 * more or less the same as APN type. TODO: fix this - As of now we keep the
 * id of the, type, as the same defined in dataPhone.
 */
/*
 * priority - higher number means higher priority, TODO: this should be read
 * from some property or other OEM configurable file!
 */

// TODO : is index necessary? may be useful to map with some property later
enum DataServiceType {
    SERVICE_TYPE_DEFAULT(0, 10),
    SERVICE_TYPE_MMS(1, 20),
    SERVICE_TYPE_SUPL(2, 30),
    SERVICE_TYPE_DUN(3, 50),
    SERVICE_TYPE_HIPRI(4, 400),
    SERVICE_TYPE_VERIZON(5,100);

    int index;
    int priority;

    private DataServiceType(int index, int priority) {
        this.index = index;
        this.priority = priority;
    }

    public int getid() {
        return index;
    }

    /*
     * Check if THIS service has a higher priority than the service specified
     * (ds)
     */
    public boolean isHigherPriorityThan(DataServiceType ds) {
        return priority > ds.priority;
    }

    /* Check if THIS service has a lower priority than the service specified
     * (ds)
     */
    public boolean isLowerPriorityThan(DataServiceType ds) {
        return priority < ds.priority;
    }

    private static class ServicePriorityComparator implements Comparator<DataServiceType>
    {
        public int compare(DataServiceType ds1, DataServiceType ds2) {
            return ds2.priority - ds1.priority; //descending
        }
    };

    private static final ServicePriorityComparator servComp = new ServicePriorityComparator();

    static public DataServiceType[] getPrioritySortedValues() {
        DataServiceType ret[] = DataServiceType.values().clone(); //TODO: clone??
        Arrays.sort(ret, servComp);
        return ret;
    }

    static public DataServiceType apnTypeStringToServiceType(String type) {
        if (TextUtils.equals(type, DataPhone.APN_TYPE_DEFAULT)) {
            return SERVICE_TYPE_DEFAULT;
        } else if (TextUtils.equals(type, DataPhone.APN_TYPE_MMS)) {
            return SERVICE_TYPE_MMS;
        } else if (TextUtils.equals(type, DataPhone.APN_TYPE_SUPL)) {
            return SERVICE_TYPE_SUPL;
        } else if (TextUtils.equals(type, DataPhone.APN_TYPE_DUN)) {
            return SERVICE_TYPE_DUN;
        } else if (TextUtils.equals(type, DataPhone.APN_TYPE_HIPRI)) {
            return SERVICE_TYPE_HIPRI;
        } else if (TextUtils.equals(type, DataPhone.APN_TYPE_VERIZON)) {
            return SERVICE_TYPE_VERIZON;
        } else {
            return null;
        }
    }

    protected String toApnTypeString() {
        switch (this) {
            case SERVICE_TYPE_DEFAULT:
                return DataPhone.APN_TYPE_DEFAULT;
            case SERVICE_TYPE_MMS:
                return DataPhone.APN_TYPE_MMS;
            case SERVICE_TYPE_SUPL:
                return DataPhone.APN_TYPE_SUPL;
            case SERVICE_TYPE_DUN:
                return DataPhone.APN_TYPE_DUN;
            case SERVICE_TYPE_HIPRI:
                return DataPhone.APN_TYPE_HIPRI;
            case SERVICE_TYPE_VERIZON:
                return DataPhone.APN_TYPE_VERIZON;
            default:
                return null;
        }
    }
}
