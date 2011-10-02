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

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Registrant;
import android.os.RegistrantList;

/** This class will know how to access records outside of
 * application DFs. (i.e. phonebook, etc.)
 */
public class UiccRecords extends Handler implements IccConstants{
    UiccCard mUiccCard; //parent
    IccFileHandler mIccFh;

    private boolean mDestroyed = false; //set to true once this object is commanded to be disposed of.
    private RegistrantList mUnavailableRegistrants = new RegistrantList();

    UiccRecords(UiccCard uc) {
        mUiccCard = uc;
    }

    synchronized void dispose() {
        mDestroyed = true;
        mUnavailableRegistrants.notifyRegistrants();
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

}
