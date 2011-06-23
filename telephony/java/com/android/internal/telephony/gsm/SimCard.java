/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.gsm;

import android.util.Log;

import com.android.internal.telephony.IccCard;

/**
 * {@hide}
 */
public final class SimCard extends IccCard {

    SimCard(GSMPhone phone) {
        super(phone, "GSM", true);

        mPhone.mCM.registerForOn(mHandler, EVENT_RADIO_ON, null);
        mPhone.mCM.registerForOffOrNotAvailable(mHandler, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        mPhone.mCM.registerForIccStatusChanged(mHandler, EVENT_ICC_STATUS_CHANGED, null);
        updateStateProperty();
    }

    @Override
    public void dispose() {
        //Unregister for all events
        mPhone.mCM.unregisterForOffOrNotAvailable(mHandler);
        mPhone.mCM.unregisterForOn(mHandler);
        mPhone.mCM.unregisterForIccStatusChanged(mHandler);
    }

    @Override
    public String getServiceProviderName () {
        return ((GSMPhone)mPhone).mSIMRecords.getServiceProviderName();
    }

}
