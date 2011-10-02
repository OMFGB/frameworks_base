/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.os.INetStatService;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;

import com.android.internal.telephony.DataConnectionTracker.Activity;

class DataNetStatistics implements Runnable {

    // 1 sec. default polling interval when screen is on.
    protected static final int POLL_NETSTAT_MILLIS = 1000;

    // 10 min. default polling interval when screen is off.
    protected static final int POLL_NETSTAT_SCREEN_OFF_MILLIS = 1000 * 60 * 10;

    DataConnectionTracker dc;

    private boolean enablePoll = false;
    public Activity mActivity = Activity.NONE;
    private boolean mIsScreenOn = true;

    private long txPkts, rxPkts, sentSinceLastRecv;
    private long netStatPollPeriod;

    INetStatService netstat;

    public DataNetStatistics(DataConnectionTracker dataConnectionTracker) {
        this.dc = dataConnectionTracker;
        this.netstat = INetStatService.Stub.asInterface(ServiceManager.getService("netstat"));
    }

    public boolean isEnablePoll() {
        return enablePoll;
    }

    public void setEnablePoll(boolean enablePoll) {
        this.enablePoll = enablePoll;
    }

    public void notifyScreenState(boolean b) {
        mIsScreenOn = b;
    }

    synchronized public Activity getActivity() {
        return mActivity;
    }

    synchronized public void setActivity(Activity mActivity) {
        this.mActivity = mActivity;
    }

    public void resetPollStats() {
        txPkts = -1;
        rxPkts = -1;
        sentSinceLastRecv = 0;
        netStatPollPeriod = POLL_NETSTAT_MILLIS;
    }

    public void run() {
        long sent, received;
        long preTxPkts = -1, preRxPkts = -1;

        Activity newActivity;

        preTxPkts = txPkts;
        preRxPkts = rxPkts;

        try {
            txPkts = netstat.getMobileTxPackets();
            rxPkts = netstat.getMobileRxPackets();
        } catch (RemoteException e) {
            txPkts = 0;
            rxPkts = 0;
        }

        // Log.d(LOG_TAG, "rx " + String.valueOf(rxPkts) + " tx " +
        // String.valueOf(txPkts));

        if (enablePoll && (preTxPkts > 0 || preRxPkts > 0)) {
            sent = txPkts - preTxPkts;
            received = rxPkts - preRxPkts;

            if (sent > 0 && received > 0) {
                sentSinceLastRecv = 0;
                newActivity = Activity.DATAINANDOUT;
            } else if (sent > 0 && received == 0) {
                if (true /* TODO: fusion - p.getState() == Phone.State.IDLE */) {
                    sentSinceLastRecv += sent;
                } else {
                    sentSinceLastRecv = 0;
                }
                newActivity = Activity.DATAOUT;
            } else if (sent == 0 && received > 0) {
                sentSinceLastRecv = 0;
                newActivity = Activity.DATAIN;
            } else if (sent == 0 && received == 0) {
                newActivity = (getActivity() == Activity.DORMANT) ? getActivity() : Activity.NONE;
            } else {
                sentSinceLastRecv = 0;
                newActivity = (getActivity() == Activity.DORMANT) ? getActivity() : Activity.NONE;
            }

            if (getActivity() != newActivity && mIsScreenOn) {
                setActivity(newActivity);
                dc.notifyDataActivity();
            }
        }

        if (mIsScreenOn) {
            netStatPollPeriod = Settings.Secure.getInt(dc.mContext.getContentResolver(),
                    Settings.Secure.PDP_WATCHDOG_POLL_INTERVAL_MS, POLL_NETSTAT_MILLIS);
        } else {
            netStatPollPeriod = Settings.Secure.getInt(dc.mContext.getContentResolver(),
                    Settings.Secure.PDP_WATCHDOG_LONG_POLL_INTERVAL_MS,
                    POLL_NETSTAT_SCREEN_OFF_MILLIS);
        }

        if (enablePoll) {
            dc.postDelayed(this, netStatPollPeriod);
        }
    }
};
