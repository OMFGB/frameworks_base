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

import android.util.Log;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.IccConstants;
import com.android.internal.telephony.IccFileHandler;
import com.android.internal.telephony.UiccCardApplication;

/**
 * {@hide}
 * This class should be used to access files in CSIM ADF
 */
public final class CsimFileHandler extends IccFileHandler implements IccConstants {
    static final String LOG_TAG = "RIL_CsimFH";

    public CsimFileHandler(UiccCardApplication app, int slotId, String aid, CommandsInterface ci) {
        super(app, slotId, aid, ci);
    }

    protected void finalize() {
        Log.d(LOG_TAG, "CsimFileHandler finalized");
    }

    protected String getEFPath(int efid) {
        switch(efid) {
        case EF_SMS:
        case EF_CST:
        case EF_FDN:
        case EF_MSISDN:
        case EF_RUIM_SPN:
            return MF_SIM + ADF;
        }
        return getCommonIccEFPath(efid);
    }

    protected void logd(String msg) {
        Log.d(LOG_TAG, msg);
    }

    protected void loge(String msg) {
        Log.e(LOG_TAG, msg);
    }
}
