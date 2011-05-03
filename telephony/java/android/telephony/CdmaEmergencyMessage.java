/*
 * Copyright (c) 2011, Code Aurora Forum. All rights reserved.
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

package android.telephony;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Describes an Cdma Emergency message.
 *
 * {@hide}
 */
public class CdmaEmergencyMessage implements EmergencyMessage{

    private String mBody = "CdmaEmergencyMessage Uninitialized";
    int mServiceCategory;

    private CdmaEmergencyMessage() {

    }

    public String getMessageBody() {
        return mBody;
    }

    public static CdmaEmergencyMessage createFromSmsMessage(SmsMessage src) {
        CdmaEmergencyMessage message = new CdmaEmergencyMessage();
        //TODO initialize more fields (serial number, update number, message id, etc)
        message.mBody = src.getMessageBody();
        return message;
    }

    private CdmaEmergencyMessage(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public String toString() {
        return ("CdmaEmergencyMessage: " + mBody);
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mBody);
    }

    private void readFromParcel(Parcel in) {
        mBody = in.readString();
    }

    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<CdmaEmergencyMessage>
            CREATOR = new Parcelable.Creator<CdmaEmergencyMessage>() {
        public CdmaEmergencyMessage createFromParcel(Parcel in) {
            return new CdmaEmergencyMessage(in);
        }

        public CdmaEmergencyMessage[] newArray(int size) {
            return new CdmaEmergencyMessage[size];
        }
    };

}