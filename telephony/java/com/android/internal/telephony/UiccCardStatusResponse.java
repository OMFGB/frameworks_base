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

package com.android.internal.telephony;

import com.android.internal.telephony.UiccConstants.AppType;
import com.android.internal.telephony.UiccConstants.AppState;
import com.android.internal.telephony.UiccConstants.CardState;
import com.android.internal.telephony.UiccConstants.PersoSubState;
import com.android.internal.telephony.UiccConstants.PinState;

/* An object of this class will be passed as the response to the RIL_REQUEST_GET_SIM_STATUS
 */
public class UiccCardStatusResponse {

    class CardStatus {
        class AppStatus {
            public AppType        app_type;
            public AppState       app_state;
            // applicable only if app_state == RIL_APPSTATE_SUBSCRIPTION_PERSO
            public PersoSubState  perso_substate;
            // null terminated string, e.g., from 0xA0, 0x00 -> 0x41, 0x30, 0x30, 0x30 */
            public String         aid;
            // null terminated string
            public String         app_label;
            // applicable to USIM and CSIM
            public int            pin1_replaced;
            public PinState       pin1;
            public PinState       pin2;
            public String toString() {
                return "AppStatus: " + app_type + " " + app_state + " " + perso_substate +
                       " aid:" + aid + " app_label:" + app_label + " pin1_replaced: " +
                       pin1_replaced + " pin1:" + pin1 + " pin2:" + pin2;
            }
        }
        CardState     card_state;
        PinState      universal_pin_state;
        int[]         subscription_3gpp_app_index;     /* value < RIL_CARD_MAX_APPS */
        int[]         subscription_3gpp2_app_index;    /* value < RIL_CARD_MAX_APPS */
        AppStatus[]   applications;
    }

    CardStatus[] cards;

    UiccCardStatusResponse() {

    }
}
