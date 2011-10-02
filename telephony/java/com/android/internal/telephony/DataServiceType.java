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
import java.util.HashMap;


import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;


/*
 * more or less the same as APN type. TODO: fix this - As of now we keep the
 * id of the, type, as the same defined in Phone.
 */

enum DataServiceType {
    SERVICE_TYPE_DEFAULT(0),
    SERVICE_TYPE_MMS(1),
    SERVICE_TYPE_SUPL(2),
    SERVICE_TYPE_DUN(3),
    SERVICE_TYPE_HIPRI(4);

    int index;
    int priority;

    private static final String LOG_TAG = "DST";

    /*
     * Default service priorities - can be overridden by setting the
     * "persist.telephony.ds.priorities" property.
     */
    private static final String DEFAULT_SERVICE_TYPE_PRIORITIES
                                        = "0=10;1=20;2=30;3=50;4=400;";

    private DataServiceType(int index) {
        this.index = index;
        this.priority = getServicePriorityFromProperty();
    }

    public int getid() {
        return index;
    }

    /*
     * Check if THIS service has the same priority as the service specified
     * (ds)
     */
    public boolean isEqualPriority(DataServiceType ds) {
        return priority == ds.priority;
    }

    /*
     * Check if THIS service has the same Android Configured DEFAULT priority
     * as the service specified (ds)
     */
    public boolean isEqualDefaultPriority(DataServiceType ds) {
        return getServicePriorityFromProperty() == ds.getServicePriorityFromProperty();
    }

    /*
     * Check if THIS service has a higher priority than the service specified
     * (ds)
     */
    public boolean isHigherPriorityThan(DataServiceType ds) {
        if (isEqualPriority(ds))
            return getServicePriorityFromProperty() > ds.getServicePriorityFromProperty();
        else
            return priority > ds.priority;
    }

    /*
     * Check if THIS service has a higher Android Configured DEFAULT priority
     * than the service specified (ds)
     */
    public boolean isHigherDefaultPriorityThan(DataServiceType ds) {
        return getServicePriorityFromProperty() > ds.getServicePriorityFromProperty();
    }

    /* Check if THIS service has a lower priority than the service specified
     * (ds)
     */
    public boolean isLowerPriorityThan(DataServiceType ds) {
        if (isEqualPriority(ds))
            return getServicePriorityFromProperty() < ds.getServicePriorityFromProperty();
        else
            return priority < ds.priority;
    }

    /* Check if THIS service has a lower Andriod Configured DEFAULT priority
     * than the service specified (ds)
     */
    public boolean isLowerDefaultPriorityThan(DataServiceType ds) {
        return getServicePriorityFromProperty() < ds.getServicePriorityFromProperty();
    }

    public void setPriority(int priority) {
       this.priority = priority;
    }

    private static HashMap<Integer, Integer> parseServicePriorityString(String s) {

        if (s == null) {
            return null;
        }

        HashMap<Integer, Integer> h = new HashMap<Integer, Integer>();
        try {
            String[] t1 = s.split(";");
            for (String t2 : t1) {
                String value[] = t2.split("=");
                h.put(Integer.parseInt(value[0]), Integer.parseInt(value[1]));
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error parsing service priority string, ignoring " + s);
            return null;
        }
        /*
         * TODO : To check for completeness of the property
         */
        return h;
    }

    public int getServicePriorityFromProperty() {

        String priorities = SystemProperties.get(
                TelephonyProperties.PROPERTY_DATA_SERVICE_PRIORITIES);

        HashMap <Integer, Integer> h = parseServicePriorityString(priorities);
        if (h == null) {
            h = parseServicePriorityString(DEFAULT_SERVICE_TYPE_PRIORITIES);
            //must be non null;
        }

        return (Integer) h.get(this.index);
    }

    private static class ServicePriorityComparator implements Comparator<DataServiceType>
    {
        public int compare(DataServiceType ds1, DataServiceType ds2) {
            int secondPriority = ds2.priority;
            int firstPriority = ds1.priority;

            // if the priorities are equal, compare their default configured priorities
            if (secondPriority == firstPriority) {
                secondPriority = ds2.getServicePriorityFromProperty();
                firstPriority = ds1.getServicePriorityFromProperty();
            }
            return secondPriority - firstPriority; //descending
        }
    };

    private static final ServicePriorityComparator servComp = new ServicePriorityComparator();

    static public DataServiceType[] getPrioritySortedValues() {
        DataServiceType ret[] = DataServiceType.values().clone(); //TODO: clone??
        Arrays.sort(ret, servComp);
        return ret;
    }

    static public DataServiceType apnTypeStringToServiceType(String type) {
        if (TextUtils.equals(type, Phone.APN_TYPE_DEFAULT)) {
            return SERVICE_TYPE_DEFAULT;
        } else if (TextUtils.equals(type, Phone.APN_TYPE_MMS)) {
            return SERVICE_TYPE_MMS;
        } else if (TextUtils.equals(type, Phone.APN_TYPE_SUPL)) {
            return SERVICE_TYPE_SUPL;
        } else if (TextUtils.equals(type, Phone.APN_TYPE_DUN)) {
            return SERVICE_TYPE_DUN;
        } else if (TextUtils.equals(type, Phone.APN_TYPE_HIPRI)) {
            return SERVICE_TYPE_HIPRI;
        } else {
            return null;
        }
    }

    protected String toApnTypeString() {
        switch (this) {
            case SERVICE_TYPE_DEFAULT:
                return Phone.APN_TYPE_DEFAULT;
            case SERVICE_TYPE_MMS:
                return Phone.APN_TYPE_MMS;
            case SERVICE_TYPE_SUPL:
                return Phone.APN_TYPE_SUPL;
            case SERVICE_TYPE_DUN:
                return Phone.APN_TYPE_DUN;
            case SERVICE_TYPE_HIPRI:
                return Phone.APN_TYPE_HIPRI;
            default:
                return null;
        }
    }
}
