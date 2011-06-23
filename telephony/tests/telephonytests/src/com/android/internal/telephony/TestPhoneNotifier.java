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

import com.android.internal.telephony.DataPhone;
import com.android.internal.telephony.DataPhone.IPVersion;

/**
 * Stub class used for unit tests
 */

public class TestPhoneNotifier implements PhoneNotifier {
    public TestPhoneNotifier() {
    }

    public void notifyPhoneState(VoicePhone sender) {
    }

    public void notifyVoiceServiceState(VoicePhone sender) {
    }

    public void notifyCellLocation(VoicePhone sender) {
    }

    public void notifySignalStrength(VoicePhone sender) {
    }

    public void notifyMessageWaitingChanged(VoicePhone sender) {
    }

    public void notifyCallForwardingChanged(VoicePhone sender) {
    }

    public void notifyDataConnection(DataPhone sender, String type, IPVersion ipv, String reason) {
    }

    public void notifyDataConnectionFailed(DataPhone sender, String reason) {
    }

    public void notifyDataActivity(DataPhone sender) {
    }

    public void notifyDataServiceState(DataPhone sender) {
    }
}
