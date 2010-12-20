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


import com.android.internal.telephony.UiccConstants.CardState;
import com.android.internal.telephony.UiccConstants.PinState;
import com.android.internal.telephony.UiccConstants.AppType;
import com.android.internal.telephony.cat.CatService;
import android.content.Context;
import android.os.Handler;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.util.Log;

/** Every user of this class will be registered for Unavailable with every
 * object it gets reference to. It is the user's responsibility to unregister
 * and remove reference to object, once UNAVAILABLE callback is received.
 */
public class UiccCard extends Handler{
    private String mLogTag = "RIL_UiccCard";

    private UiccManager mUiccManager; //parent
    private UiccCardApplication[] mUiccApplications;
    private UiccRecords mUiccRecords;
    private int mSlotId;
    private CardState mCardState;
    private PinState mUniversalPinState;
    private int[] mSubscription3gppAppIndex;     /* value < RIL_CARD_MAX_APPS */
    private int[] mSubscription3gpp2AppIndex;    /* value < RIL_CARD_MAX_APPS */
    private RegistrantList mUnavailableRegistrants = new RegistrantList();
    private RegistrantList mAbsentRegistrants = new RegistrantList();
    private boolean mDestroyed = false; //set to true once this card is commanded to be disposed of.
    private Context mContext;
    private CommandsInterface mCi;
    private CatService mCatService;


    UiccCard(UiccManager uiccManager, int slotId, UiccCardStatusResponse.CardStatus ics, Context c, CommandsInterface ci) {
        mUiccManager = uiccManager;
        mSlotId = slotId;
        mCardState = ics.card_state;
        mUniversalPinState = ics.universal_pin_state;
        mSubscription3gppAppIndex = ics.subscription_3gpp_app_index;
        mSubscription3gpp2AppIndex = ics.subscription_3gpp2_app_index;
        mUiccRecords = new UiccRecords(this);
        mUiccApplications = new UiccCardApplication[UiccConstants.RIL_CARD_MAX_APPS];
        mContext = c;
        mCi = ci;

        Log.d(mLogTag, "Creating " + ics.applications.length + " applications");
        for (int i = 0; i < ics.applications.length; i++) {
            mUiccApplications[i] = new UiccCardApplication(this, ics.applications[i], mUiccRecords, mContext, mCi);
        }

        if (mUiccApplications.length > 0 && mUiccApplications[0] != null) {
            mCatService = CatService.getInstance(mCi, mUiccApplications[0].getApplicationRecords(), mContext,
                                                 mUiccApplications[0].getIccFileHandler(), null);
        }
    }

    public void update(UiccCardStatusResponse.CardStatus ics, Context c, CommandsInterface ci) {
        if (mDestroyed) {
            Log.e(mLogTag, "Updated after destroyed! Fix me!");
            return;
        }
        //TODO: Fusion - If state changed - notify registrants
        mCardState = ics.card_state;
        mUniversalPinState = ics.universal_pin_state;
        mSubscription3gppAppIndex = ics.subscription_3gpp_app_index;
        mSubscription3gpp2AppIndex = ics.subscription_3gpp2_app_index;
        mContext = c;
        mCi = ci;
        //update applications
        for ( int i = 0; i < mUiccApplications.length; i++) {
            if (mUiccApplications[i] == null) {
                //Create newly added Applications
                if (i < ics.applications.length) {
                    mUiccApplications[i] = new UiccCardApplication(this,
                            ics.applications[i], mUiccRecords, mContext, mCi);
                }
            } else if (i >= ics.applications.length) {
                //Delete removed applications
                mUiccApplications[i].dispose();
                mUiccApplications[i] = null;
            } else {
                //Update the rest
                mUiccApplications[i].update(ics.applications[i], mUiccRecords, mContext, mCi);
            }
        }
    }

    public synchronized void dispose() {
        mDestroyed = true;

        mUiccRecords.dispose();
        mUiccRecords = null;
        if (mCatService != null) {
            mCatService.dispose();
        }
        mCatService = null;
        for (UiccCardApplication app: mUiccApplications) {
            if (app != null) {
                app.dispose();
            }
        }
        mUiccApplications = null;
        mUnavailableRegistrants.notifyRegistrants();
    }

    public UiccManager getUiccManager() {
        return mUiccManager;
    }
    public int[] getSubscription3gppAppIndex() {
        return mSubscription3gppAppIndex;
    }

    public int[] getSubscription3gpp2AppIndex() {
        return mSubscription3gpp2AppIndex;
    }

    /* Returns number of applications on this card */
    public int getNumApplications() {
        int count = 0;
        for (UiccCardApplication a : mUiccApplications) {
            if (a != null) {
                count++;
            }
        }
        return count;
    }

    public int getSlotId() {
        return mSlotId;
    }

    public synchronized UiccCardApplication getUiccCardApplication(int appIndex) {
        if (mDestroyed) {
            return null;
        }

        if (appIndex < mUiccApplications.length) {
            return mUiccApplications[appIndex];
        } else {
            return null;
        }
    }

    public CardState getCardState() {
        return mCardState;
    }

    public synchronized UiccRecords getRecords() {
        return mUiccRecords;
    }

    public PinState getUniversalPinState() {
        return mUniversalPinState;
    }
    public synchronized void registerForUnavailable(Handler h, int what, Object obj) {
        if (mDestroyed) {
            return;
        }

        Registrant r = new Registrant (h, what, obj);
        mUnavailableRegistrants.add(r);
    }
    public synchronized void unregisterForUnavailable(Handler h) {
        mUnavailableRegistrants.remove(h);
    }

    protected void finalize() {
        Log.d(mLogTag, "UiccCard finalized");
    }

    /**
     * Notifies handler of any transition into State.ABSENT
     */
    public void registerForAbsent(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        mAbsentRegistrants.add(r);

        if (getCardState() == CardState.ABSENT) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForAbsent(Handler h) {
        mAbsentRegistrants.remove(h);
    }

    public boolean isApplicationOnIcc(AppType type) {
        for (UiccCardApplication a : mUiccApplications) {
            if (a != null && a.getType() == type) {
                return true;
            }
        }
        return false;
    }

    private void logd(String msg) {
        Log.d(mLogTag, msg);
    }

}
