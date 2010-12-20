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

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.util.Log;

import com.android.internal.telephony.UiccConstants.CardState;
import com.android.internal.telephony.cat.CatService;

/* This class will be responsible for keeping all knowledge about
 * ICCs in the system. It will also be used as API to get appropriate
 * applications to pass them to phone and service trackers.
 */
public class UiccManager extends Handler{
    public enum AppFamily {
        APP_FAM_3GPP,
        APP_FAM_3GPP2;
    }

    private static UiccManager mInstance;

    private static final int EVENT_RADIO_ON = 1;
    private static final int EVENT_ICC_STATUS_CHANGED = 2;
    private static final int EVENT_GET_ICC_STATUS_DONE = 3;
    private static final int EVENT_RADIO_OFF_OR_UNAVAILABLE = 4;

    private String mLogTag = "RIL_UiccManager";
    CommandsInterface mCi;
    Context mContext;
    UiccCard[] mUiccCards;

    private RegistrantList mIccChangedRegistrants = new RegistrantList();
    private CatService mCatService;

    public static UiccManager getInstance(Context c, CommandsInterface ci) {
        if (mInstance == null) {
            mInstance = new UiccManager(c, ci);
        } else {
            mInstance.mCi = ci;
            mInstance.mContext = c;
        }
        return mInstance;
    }

    public static UiccManager getInstance() {
        if (mInstance == null) {
            return null;
        } else {
            return mInstance;
        }
    }

    private UiccManager(Context c, CommandsInterface ci) {
        Log.e(mLogTag, "Creating");

        mUiccCards = new UiccCard[UiccConstants.RIL_MAX_CARDS];

        mContext = c;
        mCi = ci;
        mCi.registerForOn(this,EVENT_RADIO_ON, null);
        mCi.registerForIccStatusChanged(this, EVENT_ICC_STATUS_CHANGED, null);
        mCi.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_UNAVAILABLE, null);

        mCatService = CatService.getInstance(mCi, null, mContext, null, null);

    }

    @Override
    public void handleMessage (Message msg) {
        AsyncResult ar;

        switch (msg.what) {
            case EVENT_RADIO_ON:
            case EVENT_ICC_STATUS_CHANGED:
                Log.d(mLogTag, "Received EVENT_ICC_STATUS_CHANGED, calling getIccCardStatus");
                mCi.getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE, msg.obj));
                break;
            case EVENT_GET_ICC_STATUS_DONE:
                ar = (AsyncResult)msg.obj;

                onGetIccCardStatusDone(ar);

                //If UiccManager was provided with a callback when icc status update
                //was triggered - now is the time to call it.
                if (ar.userObj != null && ar.userObj instanceof AsyncResult) {
                    AsyncResult internalAr = (AsyncResult)ar.userObj;
                    if (internalAr.userObj != null &&
                            internalAr.userObj instanceof Message) {
                        Message onComplete = (Message)internalAr.userObj;
                        if (onComplete != null) {
                            onComplete.sendToTarget();
                        }
                    }
                } else if (ar.userObj != null && ar.userObj instanceof Message) {
                    Message onComplete = (Message)ar.userObj;
                    onComplete.sendToTarget();
                }
                break;
            case EVENT_RADIO_OFF_OR_UNAVAILABLE:
                disposeCards();
                break;
            default:
                Log.e(mLogTag, " Unknown Event " + msg.what);
        }

    }

    private synchronized void onGetIccCardStatusDone(AsyncResult ar) {
        if (ar.exception != null) {
            Log.e(mLogTag,"Error getting ICC status. "
                    + "RIL_REQUEST_GET_ICC_STATUS should "
                    + "never return an error", ar.exception);
            return;
        }

        UiccCardStatusResponse status = (UiccCardStatusResponse)ar.result;

        for (int i = 0; i < UiccConstants.RIL_MAX_CARDS; i++) {
            //Update already existing cards
            if (mUiccCards[i] != null && i < status.cards.length) {
                mUiccCards[i].update(status.cards[i], mContext, mCi);
            }

            //Dispose of removed cards
            if (mUiccCards[i] != null && i >= status.cards.length) {
                mUiccCards[i].dispose();
                mUiccCards[i] = null;
            }

            //Create added cards
            if (mUiccCards[i] == null && i < status.cards.length) {
                mUiccCards[i] = new UiccCard(this, i, status.cards[i], mContext, mCi);
            }
        }

        Log.d(mLogTag, "Notifying IccChangedRegistrants");
        mIccChangedRegistrants.notifyRegistrants();
    }

    private synchronized void disposeCards() {
        for (int i = mUiccCards.length - 1; i >= 0; i--) {
            if (mUiccCards[i] != null) {
                mUiccCards[i].dispose();
                mUiccCards[i] = null;
            }
        }
    }

    public void triggerIccStatusUpdate(Object onComplete) {
        sendMessage(obtainMessage(EVENT_ICC_STATUS_CHANGED, onComplete));
    }

    public synchronized UiccCard[] getIccCards() {
        ArrayList<UiccCard> cards = new ArrayList<UiccCard>();
        for (UiccCard c: mUiccCards) {
            if (c != null && c.getCardState() == CardState.PRESENT) {
                cards.add(c);
            }
        }
        return (UiccCard[])cards.toArray();
    }

    /* Return First subscription of selected family */
    public synchronized UiccCardApplication getCurrentApplication(AppFamily family) {
        for (UiccCard c: mUiccCards) {
            if (c == null || c.getCardState() != CardState.PRESENT) continue;
            int[] subscriptions;
            if (family == AppFamily.APP_FAM_3GPP) {
                subscriptions = c.getSubscription3gppAppIndex();
            } else {
                subscriptions = c.getSubscription3gpp2AppIndex();
            }
            if (subscriptions != null && subscriptions.length > 0) {
                //return First current subscription
                UiccCardApplication app = c.getUiccCardApplication(subscriptions[0]);
                return app;
            } else {
                //No subscriptions found
                return null;
            }
        }
        //No Cards found
        return null;
    }

    //Notifies when any of the cards' STATE changes (or card gets added or removed)
    public void registerForIccChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        synchronized (mIccChangedRegistrants) {
            mIccChangedRegistrants.add(r);
        }
        //Notify registrants soon after registering, so that it will get the latest ICC status,
        //otherwise which may not happen until there is an actual change in ICC status.
        r.notifyRegistrant();
    }
    public void unregisterForIccChanged(Handler h) {
        synchronized (mIccChangedRegistrants) {
            mIccChangedRegistrants.remove(h);
        }
    }
}