/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (c) 2009, Code Aurora Forum. All rights reserved.
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

public class UiccConstants {
    public enum AppState{
        APPSTATE_UNKNOWN,
        APPSTATE_DETECTED,
        APPSTATE_PIN,
        APPSTATE_PUK,
        APPSTATE_SUBSCRIPTION_PERSO,
        APPSTATE_READY,
        APPSTATE_ILLEGAL;
    }

    public enum AppType{
        APPTYPE_UNKNOWN,
        APPTYPE_SIM,
        APPTYPE_USIM,
        APPTYPE_RUIM,
        APPTYPE_CSIM
    }


    public enum CardState {
        ABSENT,
        PRESENT,
        ERROR;
    }

    public enum PersoSubState{
        PERSOSUBSTATE_UNKNOWN,
        PERSOSUBSTATE_IN_PROGRESS,
        PERSOSUBSTATE_READY,
        PERSOSUBSTATE_SIM_NETWORK,
        PERSOSUBSTATE_SIM_NETWORK_SUBSET,
        PERSOSUBSTATE_SIM_CORPORATE,
        PERSOSUBSTATE_SIM_SERVICE_PROVIDER,
        PERSOSUBSTATE_SIM_SIM,
        PERSOSUBSTATE_SIM_NETWORK_PUK,
        PERSOSUBSTATE_SIM_NETWORK_SUBSET_PUK,
        PERSOSUBSTATE_SIM_CORPORATE_PUK,
        PERSOSUBSTATE_SIM_SERVICE_PROVIDER_PUK,
        PERSOSUBSTATE_SIM_SIM_PUK,
        PERSOSUBSTATE_RUIM_NETWORK1,
        PERSOSUBSTATE_RUIM_NETWORK2,
        PERSOSUBSTATE_RUIM_HRPD,
        PERSOSUBSTATE_RUIM_CORPORATE,
        PERSOSUBSTATE_RUIM_SERVICE_PROVIDER,
        PERSOSUBSTATE_RUIM_RUIM,
        PERSOSUBSTATE_RUIM_NETWORK1_PUK,
        PERSOSUBSTATE_RUIM_NETWORK2_PUK,
        PERSOSUBSTATE_RUIM_HRPD_PUK,
        PERSOSUBSTATE_RUIM_CORPORATE_PUK,
        PERSOSUBSTATE_RUIM_SERVICE_PROVIDER_PUK,
        PERSOSUBSTATE_RUIM_RUIM_PUK;
    }

    public enum PinState {
        PINSTATE_UNKNOWN,
        PINSTATE_ENABLED_NOT_VERIFIED,
        PINSTATE_ENABLED_VERIFIED,
        PINSTATE_DISABLED,
        PINSTATE_ENABLED_BLOCKED,
        PINSTATE_ENABLED_PERM_BLOCKED
    }

    static final int RIL_MAX_CARDS = 2;
    static final int RIL_CARD_MAX_APPS = 8;

}