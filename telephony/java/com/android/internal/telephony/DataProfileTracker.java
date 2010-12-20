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

import java.util.ArrayList;
import java.util.HashMap;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.provider.Telephony;
import android.util.Log;

import com.android.internal.telephony.DataConnectionTracker.State;
import com.android.internal.telephony.DataPhone.IPVersion;
import com.android.internal.telephony.DataProfile.DataProfileType;

/*
 * This class keeps track of the following :
 * requested/active service types. For each service type,
 * - the list of data profiles (ex: APN) that can handle this service type
 * - data connection that handles this data profile (if active)
 */

public class DataProfileTracker extends Handler {

    private static final String LOG_TAG = "DATA";

    private Context mContext;

    private DataProfileDbObserver mDpObserver;

    /*
     * for each service type (apn type), we have an instance of
     * DataServiceTypeInfo, that stores all metadata related to that service
     * type.
     */
    HashMap<DataServiceType, DataServiceInfo> dsMap;

    /* MCC/MNC of the current active operator */
    private String mOperatorNumeric;
    private RegistrantList mDataDataProfileDbChangedRegistrants = new RegistrantList();
    private ArrayList<DataProfile> mAllDataProfilesList = new ArrayList<DataProfile>();

    private static final int EVENT_DATA_PROFILE_DB_CHANGED = 1;

    /*
     * Observer for keeping track of changes to the APN database.
     */
    private class DataProfileDbObserver extends ContentObserver {
        public DataProfileDbObserver(Handler h) {
            super(h);
        }

        @Override
        public void onChange(boolean selfChange) {
            sendMessage(obtainMessage(EVENT_DATA_PROFILE_DB_CHANGED));
        }
    }

    DataProfileTracker(Context context) {

        mContext = context;

        /*
         * initialize data service type specific meta data
         */
        dsMap = new HashMap<DataServiceType, DataServiceInfo>();
        for (DataServiceType t : DataServiceType.values()) {
            dsMap.put(t, new DataServiceInfo(mContext, t));
        }

        /*
         * register database observer
         */
        mDpObserver = new DataProfileDbObserver(this);
        mContext.getContentResolver().registerContentObserver(Telephony.Carriers.CONTENT_URI, true,
                mDpObserver);

        //Load APN List
        this.sendMessage(obtainMessage(EVENT_DATA_PROFILE_DB_CHANGED));
    }

    public void dispose() {
        // TODO Auto-generated method stub
    }

    public void handleMessage(Message msg) {

        switch (msg.what) {
            case EVENT_DATA_PROFILE_DB_CHANGED:
                reloadAllDataProfiles(Phone.REASON_APN_CHANGED);
                break;

            default:
                logw("unhandled msg.what="+msg.what);
        }
    }

    /*
     * data profile database has changed, - reload everything - inform DCT about
     * this.
     */
    private synchronized boolean reloadAllDataProfiles(String reason) {

        logv("Reloading profile db for operator = [" + mOperatorNumeric + "]. reason=" + reason);

        ArrayList<DataProfile> allDataProfiles = new ArrayList<DataProfile>();

        if (mOperatorNumeric != null) {
            String selection = "numeric = '" + mOperatorNumeric + "'";

            /* fetch all data profiles from the database that matches the
             * specified operator numeric */

            Cursor cursor = mContext.getContentResolver().query(Telephony.Carriers.CONTENT_URI,
                    null, selection, null, null);

            if (cursor != null) {
                if (cursor.getCount() > 0) {
                    allDataProfiles = createDataProfileList(cursor);
                }
                cursor.close();
            }
        }

        /*
         * For supporting CDMA, for now, we just create a Data profile of TYPE NAI that supports
         * all service types and add it to all DataProfiles.
         * TODO: this information should be read from apns-conf.xml / carriers db.
         */
        CdmaNAI cdmaNaiProfile = new CdmaNAI();
        allDataProfiles.add(cdmaNaiProfile);

        /*
         * clear the data profile list associated with each service type and
         * re-populate them.
         */
        for (DataServiceType t : DataServiceType.values()) {
            dsMap.get(t).mDataProfileList.clear();
        }

        for (DataProfile dp : allDataProfiles) {
            logv("new dp found : "+dp.toString());
            for (DataServiceType t : DataServiceType.values()) {
                if (dp.canHandleServiceType(t))
                    dsMap.get(t).mDataProfileList.add(dp);
            }
        }

        /*
         * compare old list of data profiles with new list and check if anything
         * has really changed, added or deleted. Ideally we wouldn't need this
         * but sometimes content observer reports that db has changed even if
         * nothing has actually changed.
         */
        String oldHash = "";
        for (DataProfile dp : mAllDataProfilesList) {
            oldHash = oldHash + ":" + dp.toHash();
        }
        String newHash = "";
        for (DataProfile dp : allDataProfiles) {
            newHash = newHash + ":" + dp.toHash();
        }

        mAllDataProfilesList = allDataProfiles;

        boolean hasProfileDbChanged = true;
        if (oldHash.equals(newHash)) {
            hasProfileDbChanged = false;
        }

        ApnSetting newPreferredApn = getPreferredDefaultApnFromDb(dsMap
                .get(DataServiceType.SERVICE_TYPE_DEFAULT).mDataProfileList);
        if (isApnDifferent(newPreferredApn, mPreferredDefaultApn)) {
            logv("preferred apn has changed");
            hasProfileDbChanged = true;
            mPreferredDefaultApn = newPreferredApn;
        }

        logv("hasProfileDbChanged = " + hasProfileDbChanged);

        if (hasProfileDbChanged) {
            mDataDataProfileDbChangedRegistrants.notifyRegistrants(new AsyncResult(null, reason, null));
        }

        return hasProfileDbChanged;
    }

    /*
     * returns true if at least one profile of the specified data profile type is configured
     * for the current operator.
     */
    public boolean isAnyDataProfileAvailable(DataProfileType dpt) {
        boolean ret = false;
        for (DataProfile dp : mAllDataProfilesList) {
            if (dp.getDataProfileType() == dpt) {
                ret = true;
                break;
            }
        }
        return ret;
    }

    boolean isDataProfilesLoadedForOperator(String numeric) {
        return (numeric == null && mOperatorNumeric == null)
                || (numeric != null && numeric.equals(mOperatorNumeric));
    }

    public void updateOperatorNumeric(String newOperatorNumeric, String reason) {

        if (isDataProfilesLoadedForOperator(newOperatorNumeric))
            return;

        logv("Operator numeric changed : [" + mOperatorNumeric + "]  >>  [" + newOperatorNumeric
                + "]. Reloading profile db. reason = " + reason);

        mOperatorNumeric = newOperatorNumeric;

        reloadAllDataProfiles(reason);
    }

    public void resetAllProfilesAsWorking() {
        if (mAllDataProfilesList != null) {
            for (DataProfile dp : mAllDataProfilesList) {
              dp.setWorking(true, IPVersion.IPV4);
              dp.setWorking(true, IPVersion.IPV6);
            }
        }
    }

    void resetAllServiceStates() {
        for (DataServiceType ds : DataServiceType.values()) {
            dsMap.get(ds).resetServiceConnectionState();
        }
    }

    void resetServiceState(DataServiceType ds) {
        dsMap.get(ds).resetServiceConnectionState();
    }

    /*
     * @param types comma delimited list of APN types
     * @return array of APN types
     */
    @Deprecated
    private String[] parseServiceTypeString(String types) {
        String[] result;
        // If unset, set to DEFAULT.
        if (types == null || types.equals("")) {
            result = new String[1];
            result[0] = DataPhone.APN_TYPE_ALL;
        } else {
            result = types.split(",");
        }
        return result;
    }

    private DataServiceType[] parseServiceTypes(String types) {
        ArrayList<DataServiceType> result = new ArrayList<DataServiceType>();
        if (types == null || types.equals("")) {
            return DataServiceType.values(); /* supports all */
        } else {
            String tempString[] = types.split(",");
            for (String ts : tempString) {
                if (DataServiceType.apnTypeStringToServiceType(ts) != null) {
                    result.add(DataServiceType.apnTypeStringToServiceType(ts));
                }
            }
        }
        DataServiceType typeArray[] = new DataServiceType[result.size()];
        typeArray= (DataServiceType[]) result.toArray(typeArray);

        return typeArray;
    }

    private ArrayList<DataProfile> createDataProfileList(Cursor cursor) {
        ArrayList<DataProfile> result = new ArrayList<DataProfile>();
        if (cursor.moveToFirst()) {
            do {
                String[] types = parseServiceTypeString(cursor.getString(cursor
                        .getColumnIndexOrThrow(Telephony.Carriers.TYPE)));
                /* its all apn now */
                ApnSetting apn = new ApnSetting(
                        cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers._ID)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.NUMERIC)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.NAME)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.APN)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PROXY)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PORT)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSC)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSPROXY)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSPORT)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.USER)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PASSWORD)),
                        cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.AUTH_TYPE)),
                        types,
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.IPVERSION)));
                apn.serviceTypes = parseServiceTypes(cursor.getString(cursor
                        .getColumnIndexOrThrow(Telephony.Carriers.TYPE)));
                result.add(apn);
            } while (cursor.moveToNext());
        }
        return result;
    }

    void setServiceTypeEnabled(DataServiceType ds, boolean enable) {
        dsMap.get(ds).setServiceTypeEnabled(enable);
    }

    boolean isServiceTypeEnabled(DataServiceType ds) {
        return dsMap.get(ds).isDataServiceTypeEnabled();
    }

    void setServiceTypeAsActive(DataServiceType ds, DataConnection dc, IPVersion ipv) {
        dsMap.get(ds).setDataServiceTypeAsActive(dc, ipv);

        /* preferred APN handling */
        if (ds == DataServiceType.SERVICE_TYPE_DEFAULT
                && ipv == IPVersion.IPV4
                && dc.getDataProfile().getDataProfileType() == DataProfileType.PROFILE_TYPE_3GPP_APN) {
            ApnSetting apnUsed = (ApnSetting) dc.getDataProfile();
            if (isApnDifferent(mPreferredDefaultApn, apnUsed) == true) {
                mPreferredDefaultApn = apnUsed;
                setPreferredDefaultApnToDb(apnUsed);
            }
        }
    }

    void setServiceTypeAsInactive(DataServiceType ds, IPVersion ipv) {
        dsMap.get(ds).setDataServiceTypeAsInactive(ipv);
    }

    boolean isServiceTypeActive(DataServiceType ds, IPVersion ipv) {
        return dsMap.get(ds).isServiceTypeActive(ipv);
    }

    boolean isServiceTypeActive(DataServiceType ds) {
        return dsMap.get(ds).isServiceTypeActive();
    }

    DataConnection getActiveDataConnection(DataServiceType ds, IPVersion ipv) {
        return dsMap.get(ds).getActiveDataConnection(ipv);
    }

    DataProfile getNextWorkingDataProfile(DataServiceType ds, DataProfileType dpt, IPVersion ipv) {
        /*
         * For default service type on IPV4, return preferred APN if it is known
         * to work.
         */
        if (dpt == DataProfileType.PROFILE_TYPE_3GPP_APN
                && ds == DataServiceType.SERVICE_TYPE_DEFAULT
                && ipv == IPVersion.IPV4) {
            if (mPreferredDefaultApn != null
                    && mPreferredDefaultApn.isWorking(IPVersion.IPV4)
                    && mPreferredDefaultApn.canSupportIpVersion(IPVersion.IPV4)) {
                return mPreferredDefaultApn;
            }
        }

        return dsMap.get(ds).getNextWorkingDataProfile(dpt, ipv);
    }

    void setState(State state, DataServiceType ds, IPVersion ipv) {
        dsMap.get(ds).setState(state, ipv);
    }

    State getState(DataServiceType ds, IPVersion ipv) {
        return dsMap.get(ds).getState(ipv);
    }

    RetryManager getRetryManager(DataServiceType ds) {
        return dsMap.get(ds).getRetryManager();
    }

    void registerForDataProfileDbChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mDataDataProfileDbChangedRegistrants.add(r);
    }

    void unregisterForDataProfileDbChanged(Handler h) {
        mDataDataProfileDbChangedRegistrants.remove(h);
    }

    /*
     * The following is relevant only for the default profile Type + IPV4.
     */

    static final Uri PREFER_DEFAULT_APN_URI = Uri.parse("content://telephony/carriers/preferapn");
    static final String APN_ID = "apn_id";

    ApnSetting mPreferredDefaultApn = null;
    boolean mCanSetDefaultPreferredApn = false;

    /*
     * return a preferred APN for default internet connection if any.
     * This function has side effects! (TODO: fix this).
     * mCanSetDefaultPreferredApn is set to true if such a table entry exists.
     * setPreferredDefaultApn() is set to null if preferred apn id is invalid
     */
    private ApnSetting getPreferredDefaultApnFromDb(ArrayList<DataProfile> defaultDataProfileList) {

        if (defaultDataProfileList.isEmpty()) {
            return null;
        }

        Cursor cursor = mContext.getContentResolver().query(PREFER_DEFAULT_APN_URI, new String[] {
                "_id", "name", "apn"
        }, null, null, Telephony.Carriers.DEFAULT_SORT_ORDER);

        if (cursor != null) {
            mCanSetDefaultPreferredApn = true;
        } else {
            mCanSetDefaultPreferredApn = false;
        }

        if (mCanSetDefaultPreferredApn && cursor.getCount() > 0) {
            cursor.moveToFirst();
            int pos = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers._ID));
            for (DataProfile p : defaultDataProfileList) {
                if (p.getDataProfileType() == DataProfileType.PROFILE_TYPE_3GPP_APN) {
                    ApnSetting apn = (ApnSetting) p;
                    if (apn.id == pos
                            && apn.canHandleServiceType(DataServiceType.SERVICE_TYPE_DEFAULT)) {
                        cursor.close();
                        return apn;
                    }
                }
            }
        }

        if (cursor != null) {
            cursor.close();
        }

        return null;
    }

    private void setPreferredDefaultApnToDb(ApnSetting apn) {

        if (!mCanSetDefaultPreferredApn)
            return;

        ContentResolver resolver = mContext.getContentResolver();
        resolver.delete(PREFER_DEFAULT_APN_URI, null, null);

        if (apn != null) {
            ContentValues values = new ContentValues();
            values.put(APN_ID, apn.id);
            resolver.insert(PREFER_DEFAULT_APN_URI, values);
        }
    }

    /*
     * used to check if preferred apn has changed.
     */
    private boolean isApnDifferent(ApnSetting oldApn, ApnSetting newApn) {
        boolean different = true;
        if ((newApn != null) && (oldApn != null)) {
            if (oldApn.toHash().equals(newApn.toHash())) {
                different = false;
            }
        }
        return different;
    }

    private void logv(String msg) {
        Log.v(LOG_TAG, "[DPT] " + msg);
    }

    private void logd(String msg) {
        Log.d(LOG_TAG, "[DPT] " + msg);
    }

    private void logw(String msg) {
        Log.w(LOG_TAG, "[DPT] " + msg);
    }

    private void loge(String msg) {
        Log.e(LOG_TAG, "[DPT] " + msg);
    }
}
