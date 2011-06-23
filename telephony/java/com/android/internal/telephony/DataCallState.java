/*
 * Copyright (C) 2009 Qualcomm Innovation Center, Inc.  All Rights Reserved.
 * Copyright (C) 2009 The Android Open Source Project
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

import com.android.internal.telephony.CommandsInterface.RadioTechnology;


public class DataCallState {
    public int cid;
    public int active;
    public String type;
    public String apn;
    public String address;
    RadioTechnology mRadioTech;
    public int inactiveReason;

    @Override
    public String toString() {
        return "DataCallState: {" + " cid: " + cid + ", active: " + active + ", type: " + type
                + ", apn: " + apn + ", address: " + address + " }";
    }
}
