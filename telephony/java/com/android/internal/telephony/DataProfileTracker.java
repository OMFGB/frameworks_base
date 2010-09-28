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
import android.os.SystemProperties;
import android.provider.Telephony;
import android.util.Log;

import com.android.internal.telephony.DataConnectionTracker.State;
import com.android.internal.telephony.Phone.IPVersion;
import com.android.internal.telephony.DataProfile;
import com.android.internal.telephony.DataProfile.DataProfileType;
import com.android.internal.telephony.DataProfileOmh.DataProfileTypeModem;
import com.android.internal.telephony.DataProfileOmh;

/*
 * This class keeps track of the following :
 * requested/active service types. For each service type,
 * - the list of data profiles (ex: APN) that can handle this service type
 * - data connection that handles this data profile (if active)
 */

public class DataProfileTracker extends Handler {

    private static final String LOG_TAG = "DATA";

    private Context mContext;

    private CommandsInterface mCm;

    private DataProfileDbObserver mDpObserver;

    /*
     * for each service type (apn type), we have an instance of
     * DataServiceTypeInfo, that stores all metadata related to that service
     * type.
     */

    HashMap<DataServiceType, DataServiceInfo> dsMap;

    HashMap<DataServiceType, Integer> mOmhServicePriorityMap;

    /* MCC/MNC of the current active operator */
    private String mOperatorNumeric;
    private RegistrantList mDataDataProfileDbChangedRegistrants = new RegistrantList();
    private ArrayList<DataProfile> mAllDataProfilesList = new ArrayList<DataProfile>();

    /* NOTE: Assumption is that the modem profiles will not change without
     * requiring a reboot. Modifications over the air is not supported.
     * Modified or new RUIM cards inserted will require a reboot
     */
    // Enumerated list of DataProfile from the modem.
    private ArrayList<DataProfile> mOmhDataProfilesList = new ArrayList<DataProfile>();

    // Temp. DataProfile list from the modem.
    private ArrayList<DataProfile> mTempOmhDataProfilesList = new ArrayList<DataProfile>();

    /*
     * Context for read profiles for OMH.
     */
    private int mOmhReadProfileContext = 0;

    /*
     * Count to track if all read profiles for OMH are completed or not.
     */
    private int mOmhReadProfileCount = 0;

    private boolean mOmhEnabled = SystemProperties.getBoolean(
                            TelephonyProperties.PROPERTY_OMH_ENABLED, false);

    private static final int OMH_MAX_PRIORITY = 255;

    private static final int EVENT_DATA_PROFILE_DB_CHANGED = 1;
    private static final int EVENT_READ_MODEM_PROFILES = 2;
    private static final int EVENT_GET_DATA_CALL_PROFILE_DONE = 3;

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

    DataProfileTracker(Context context, CommandsInterface ci) {

        mContext = context;

        mCm = ci;

        /*
         * initialize data service type specific meta data
         */
        dsMap = new HashMap<DataServiceType, DataServiceInfo>();
        for (DataServiceType t : DataServiceType.values()) {
            dsMap.put(t, new DataServiceInfo(mContext, t));
        }

        if (mOmhEnabled) {
            /*
             * initialize data service type with arbitrated priorities mapping data
             */
            mOmhServicePriorityMap = new HashMap<DataServiceType, Integer>();
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

    public int mapOmhPriorityToAndroidPriority(DataServiceType t, boolean isOmhProfileProvisioned) {
        /*
         * Per spec, for the OMH profiles, the value 'OMH_MAX_PRIORITY : 255' attributes
         * to least priority. Reverse the value to be in consistent with android service type
         * priorities (Greater the value, higher its priority) if service type is provisioned.
         * If the service type is not provisioned, then set its priority
         * to a least value (say zero).
        */
        int mappedPriority = 0;
        if(isOmhProfileProvisioned) {
            mappedPriority = (OMH_MAX_PRIORITY - mOmhServicePriorityMap.get(t));
        }

        return mappedPriority;
    }

    /*
     * Retrieves the highest priority for all APP types except SUPL. Note that
     * for SUPL, retrieve the least priority among its profiles.
     */
    public int omhListGetArbitratedPriority(ArrayList<DataProfile> dataProfileListModem,
            DataServiceType ds) {
        DataProfile profile = null;

        for (DataProfile dp : dataProfileListModem) {
            if (!((DataProfileOmh) dp).isValidPriority()) {
                logw("[OMH] Invalid priority... skipping");
                continue;
            }

            if (profile == null) {
                profile = dp; // first hit
            } else {
                if (ds == DataServiceType.SERVICE_TYPE_SUPL) {
                    // Choose the profile with lower priority
                    profile = ((DataProfileOmh) dp).isPriorityLower(((DataProfileOmh) profile)
                            .getPriority()) ? dp : profile;
                } else {
                    // Choose the profile with higher priority
                    profile = ((DataProfileOmh) dp).isPriorityHigher(((DataProfileOmh) profile)
                            .getPriority()) ? dp : profile;
                }
            }
        }
        return ((DataProfileOmh) profile).getPriority();
    }

    public void handleMessage(Message msg) {
        AsyncResult ar;

        switch (msg.what) {
            case EVENT_DATA_PROFILE_DB_CHANGED:
                reloadAllDataProfiles(Phone.REASON_APN_CHANGED);
                break;
            case EVENT_READ_MODEM_PROFILES:
                onReadDataprofilesFromModem();
                break;
            case EVENT_GET_DATA_CALL_PROFILE_DONE:
                onGetDataCallProfileDone((AsyncResult) msg.obj, (int)msg.arg1 );
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

        if (mOmhEnabled) {

            /* handle omh */

            if ((mOmhDataProfilesList != null) && (mOmhDataProfilesList.size() > 0)) {

                updateDataServiceTypePrioritiesForOmh();

                for (DataProfile dp :mOmhDataProfilesList) {
                    logd("DataProfile from Modem " + dp);

                    // Add to the master list of profiles
                    allDataProfiles.add(dp);
                }
            }
        }

        /*
         * For supporting CDMA, for now, we just create a Data profile of TYPE NAI that
         * supports all service types and add it to all DataProfiles.
         * TODO: this information should be read from apns-conf.xml / carriers db.
         */
        CdmaNAI cdmaNaiProfile = new CdmaNAI();
        allDataProfiles.add(cdmaNaiProfile);

        /*
         * clear the data profile list associated with each service type and
         * re-populate them.
         */
        for (DataServiceType t : DataServiceType.values()) {
            dsMap.get(t).clearDataProfiles();
        }

        for (DataProfile dp : allDataProfiles) {
            logv("new dp found : "+dp.toString());
            for (DataServiceType t : DataServiceType.values()) {
                if (dp.canHandleServiceType(t)) {
                    dsMap.get(t).addDataProfile(dp);
                }
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
                .get(DataServiceType.SERVICE_TYPE_DEFAULT).getDataProfiles());
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

    private void updateDataServiceTypePrioritiesForOmh() {
        /*
         * Iterate through data service type list, to check if card had been
         * provisioned for the ServiceType in question
         */

        for (DataServiceType t : DataServiceType.values()) {
            int p = mapOmhPriorityToAndroidPriority(t, mOmhServicePriorityMap.containsKey(t));
            logv("[OMH] Setting service priority: " + t + "= " + p);
            t.setPriority(p);
        }
    }

    /*
     * Trigger modem read for data profiles
     */
    public void readDataprofilesFromModem() {
        sendMessage(obtainMessage(EVENT_READ_MODEM_PROFILES));
        return;
    }

    /*
     * Reads all the data profiles from the modem
     */
    private void onReadDataprofilesFromModem() {

       if (mOmhEnabled) {
            mOmhReadProfileContext++;

            mOmhReadProfileCount = 0; // Reset the count and list(s)
            /* Clear out the modem profiles lists (main and temp) which were read/saved */
            mOmhDataProfilesList.clear();
            mTempOmhDataProfilesList.clear();
            mOmhServicePriorityMap.clear();

            // For all the service types known in modem, read the data profies
            for (DataProfileTypeModem p : DataProfileTypeModem.values()) {
                logd("Reading profiles for:" + p.getid());
                mOmhReadProfileCount++;
                mCm.getDataCallProfile(p.getid(), obtainMessage(EVENT_GET_DATA_CALL_PROFILE_DONE, //what
                                                                mOmhReadProfileContext, //arg1
                                                                0 , //arg2  -- ignore
                                                                p));//userObj
            }
        }

        return;
    }

    /*
     * Process the response for the RIL request GET_DATA_CALL_PROFILE.
     * Save the profile details received.
     */
    private void onGetDataCallProfileDone(AsyncResult ar, int context) {

        if (ar.exception != null) {
            loge("Exception in onOmhProfileDone:" + ar.exception);
            return;
        }

       if (context != mOmhReadProfileContext) {
            //we have other onReadOmhDataprofiles() on the way.
            return;
        }

        // DataProfile list from the modem for a given SERVICE_TYPE. These may
        // be from RUIM in case of OMH
        ArrayList<DataProfile> dataProfileListModem = new ArrayList<DataProfile>();
        dataProfileListModem = (ArrayList<DataProfile>)ar.result;

        DataProfileTypeModem modemProfile = (DataProfileTypeModem)ar.userObj;

        mOmhReadProfileCount--;

        if (dataProfileListModem != null && dataProfileListModem.size() > 0) {
            DataServiceType dst;

            /* For the modem service type, get the android DataServiceType */
            dst = modemProfile.getDataServiceType();

            logd("[OMH] # profiles returned from modem:" + dataProfileListModem.size()
                    + " for " + dst);

            mOmhServicePriorityMap.put(dst,
                    omhListGetArbitratedPriority(dataProfileListModem, dst));

            for (DataProfile dp : dataProfileListModem) {

                logd("[OMH] omh data profile from modem " + dp);

                /* Store the modem profile type in the data profile */
                ((DataProfileOmh)dp).setDataProfileTypeModem(modemProfile);

                /* Look through mTempOmhDataProfilesList for existing profile id's
                 * before adding it. This implies that the (similar) profile with same
                 * priority already exists.
                 */
                DataProfileOmh omhDuplicatedp = getDuplicateProfile(dp);
                if(null == omhDuplicatedp) {
                    mTempOmhDataProfilesList.add(dp);
                    ((DataProfileOmh)dp).addServiceType(DataProfileTypeModem.
                            getDataProfileTypeModem(dst));
                } else {
                    /*  To share the already established data connection
                     * (say between SUPL and DUN) in cases such as below:
                     *  Ex:- SUPL+DUN [profile id 201, priority 1]
                     *  'dp' instance is found at this point. Add the non-provisioned
                     *   service type to this 'dp' instance
                     */
                    logd("[OMH] Duplicate Profile " + omhDuplicatedp);
                    ((DataProfileOmh)omhDuplicatedp).addServiceType(DataProfileTypeModem.
                            getDataProfileTypeModem(dst));
                }
            }
        }

        //(Re)Load APN List
        if(mOmhReadProfileCount == 0) {
            logd("[OMH] Modem omh profile read complete.");
            addServiceTypeToUnSpecified();
            mOmhDataProfilesList = mTempOmhDataProfilesList;
            this.sendMessage(obtainMessage(EVENT_DATA_PROFILE_DB_CHANGED));
        }

        return;
    }


    /*
     * returns the object 'OMH dataProfile' if a match with the same profile id exists
     * in the enumerated list of OMH profile list
     */
    private DataProfileOmh getDuplicateProfile(DataProfile dp) {
        for (DataProfile dataProfile : mTempOmhDataProfilesList) {
            if (((DataProfileOmh)dp).getProfileId() ==
                ((DataProfileOmh)dataProfile).getProfileId()){
                return (DataProfileOmh)dataProfile;
            }
        }
        return null;
    }

    /* For all the OMH service types not present in mOmhServicePriorityMap,
     *  i;e; in other words, 'this' service type is not provisioned in the
     *  card..... For such OMH service types, add to the UNSPECIFIED/DEFAULT data profile
     *  from mTempOmhDataProfilesList
     */
    private void addServiceTypeToUnSpecified() {
        for (DataServiceType t : DataServiceType.values()) {
            if(!mOmhServicePriorityMap.containsKey(t)) {
                // DataServiceType :t is not provisioned in the card
                for (DataProfile dp : mTempOmhDataProfilesList) {
                    // Iterate through the tempOmhDataList to get till UNSPECIFIED dp and add.
                    if (((DataProfileOmh)dp).getDataProfileTypeModem() ==
                        DataProfileTypeModem.PROFILE_TYPE_UNSPECIFIED) {
                        ((DataProfileOmh)dp).addServiceType(DataProfileTypeModem.
                                getDataProfileTypeModem(t));
                        logd("[OMH] Service Type added to UNSPECIFIED is : " +
                                DataProfileTypeModem.getDataProfileTypeModem(t));
                        break;
                    }
                }
            }
        }
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
            result[0] = Phone.APN_TYPE_ALL;
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
        getRetryManager(ds, ipv).resetRetryCount();

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

    RetryManager getRetryManager(DataServiceType ds, IPVersion ipv) {
        return dsMap.get(ds).getRetryManager(ipv);
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

    boolean isOmhEnabled() {
        return mOmhEnabled;
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
