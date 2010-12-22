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

package android.telephony;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

/**
 * Contains phone signal strength related information.
 */
public class SignalStrength implements Parcelable {

    static final String LOG_TAG = "PHONE";

    private int mGsmSignalStrength; // Valid values are (0-31, 99) as defined in TS 27.007 8.5
    private int mGsmBitErrorRate;   // bit error rate (0-7, 99) as defined in TS 27.007 8.5
    private int mCdmaDbm;   // This value is the RSSI value
    private int mCdmaEcio;  // This value is the Ec/Io
    private int mEvdoDbm;   // This value is the EVDO RSSI value
    private int mEvdoEcio;  // This value is the EVDO Ec/Io
    private int mEvdoSnr;   // Valid values are 0-8.  8 is the highest signal to noise ratio
    private int mLteRssi;   // LTE RSSI - Rxed Signal Strength Indicator value (0-31, 99)
    private int mLteRsrp;   // LTE RSRP - Reference signal received power in dBm - 0-97 TS 36.331
    private int mLteRsrq;   // LTE RSRQ in dB, 0-34  TS 36.331

    private boolean mIsGsm; // This value is set by the ServiceStateTracker onSignalStrengthResult

    /**
     * Create a new SignalStrength from a intent notifier Bundle
     *
     * This method is used by PhoneStateIntentReceiver and maybe by
     * external applications.
     *
     * @param m Bundle from intent notifier
     * @return newly created SignalStrength
     *
     * @hide
     */
    public static SignalStrength newFromBundle(Bundle m) {
        SignalStrength ret;
        ret = new SignalStrength();
        ret.setFromNotifierBundle(m);
        return ret;
    }

    /**
     * Empty constructor
     *
     * @hide
     */
    public SignalStrength() {
        this(99, -1, -1, -1, -1, -1, -1, true);
    }

    /**
     * Constructor
     *
     * @hide
     */
    public SignalStrength(int gsmSignalStrength, int gsmBitErrorRate, int cdmaDbm, int cdmaEcio,
            int evdoDbm, int evdoEcio, int evdoSnr, boolean isGsm) {
        this(gsmSignalStrength, gsmBitErrorRate, cdmaDbm, cdmaEcio, evdoDbm, evdoEcio, evdoSnr, 99,
                -1, -1, isGsm);
    }

    /**
     * Constructor
     *
     * @hide
     */
    public SignalStrength(int gsmSignalStrength, int gsmBitErrorRate, int cdmaDbm, int cdmaEcio,
            int evdoDbm, int evdoEcio, int evdoSnr, int lteRssi, int lteRsrp, int lteRsrq,
            boolean isGsm) {
        mGsmSignalStrength = gsmSignalStrength;
        mGsmBitErrorRate = gsmBitErrorRate;
        mCdmaDbm = cdmaDbm;
        mCdmaEcio = cdmaEcio;
        mEvdoDbm = evdoDbm;
        mEvdoEcio = evdoEcio;
        mEvdoSnr = evdoSnr;
        mLteRssi = lteRssi;
        mLteRsrp = lteRsrp;
        mLteRsrq = lteRsrq;
        mIsGsm = isGsm;
    }

    /**
     * Copy constructors
     *
     * @param s Source SignalStrength
     *
     * @hide
     */
    public SignalStrength(SignalStrength s) {
        copyFrom(s);
    }

    /**
     * @hide
     */
    protected void copyFrom(SignalStrength s) {
        mGsmSignalStrength = s.mGsmSignalStrength;
        mGsmBitErrorRate = s.mGsmBitErrorRate;
        mCdmaDbm = s.mCdmaDbm;
        mCdmaEcio = s.mCdmaEcio;
        mEvdoDbm = s.mEvdoDbm;
        mEvdoEcio = s.mEvdoEcio;
        mEvdoSnr = s.mEvdoSnr;
        mLteRssi = s.mLteRssi;
        mLteRsrp = s.mLteRsrp;
        mLteRsrq = s.mLteRsrq;
        mIsGsm = s.mIsGsm;
    }

    /**
     * Construct a SignalStrength object from the given parcel.
     *
     * @hide
     */
    public SignalStrength(Parcel in) {
        mGsmSignalStrength = in.readInt();
        mGsmBitErrorRate = in.readInt();
        mCdmaDbm = in.readInt();
        mCdmaEcio = in.readInt();
        mEvdoDbm = in.readInt();
        mEvdoEcio = in.readInt();
        mEvdoSnr = in.readInt();
        mLteRssi = in.readInt();
        mLteRsrp = in.readInt();
        mLteRsrq = in.readInt();
        mIsGsm = (in.readInt() != 0);
    }

    /**
     * {@link Parcelable#writeToParcel}
     */
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mGsmSignalStrength);
        out.writeInt(mGsmBitErrorRate);
        out.writeInt(mCdmaDbm);
        out.writeInt(mCdmaEcio);
        out.writeInt(mEvdoDbm);
        out.writeInt(mEvdoEcio);
        out.writeInt(mEvdoSnr);
        out.writeInt(mLteRssi);
        out.writeInt(mLteRsrp);
        out.writeInt(mLteRsrq);
        out.writeInt(mIsGsm ? 1 : 0);
    }

    /**
     * {@link Parcelable#describeContents}
     */
    public int describeContents() {
        return 0;
    }

    /**
     * {@link Parcelable.Creator}
     *
     * @hide
     */
    public static final Parcelable.Creator<SignalStrength> CREATOR = new Parcelable.Creator() {
        public SignalStrength createFromParcel(Parcel in) {
            return new SignalStrength(in);
        }

        public SignalStrength[] newArray(int size) {
            return new SignalStrength[size];
        }
    };

    /**
     * Get the GSM Signal Strength, valid values are (0-31, 99) as defined in TS 27.007 8.5
     */
    public int getGsmSignalStrength() {
        return this.mGsmSignalStrength;
    }

    /**
     * Get the GSM bit error rate (0-7, 99) as defined in TS 27.007 8.5
     */
    public int getGsmBitErrorRate() {
        return this.mGsmBitErrorRate;
    }

    /**
     * Get the CDMA RSSI value in dBm
     */
    public int getCdmaDbm() {
        return this.mCdmaDbm;
    }

    /**
     * Get the CDMA Ec/Io value in dB*10
     */
    public int getCdmaEcio() {
        return this.mCdmaEcio;
    }

    /**
     * Get the EVDO RSSI value in dBm
     */
    public int getEvdoDbm() {
        return this.mEvdoDbm;
    }

    /**
     * Get the EVDO Ec/Io value in dB*10
     */
    public int getEvdoEcio() {
        return this.mEvdoEcio;
    }

    /**
     * Get the signal to noise ratio. Valid values are 0-8. 8 is the highest.
     */
    public int getEvdoSnr() {
        return this.mEvdoSnr;
    }

    /**
     * Get the LTE RSSI value in dBm
     * @hide
     */
    public int getLteRssi() {
        return this.mLteRssi;
    }

    /**
     * Get the LTE RSRP
     * @hide
     */
    public int getLteRsrp() {
        return this.mLteRsrp;
    }

    /**
     * Get the LTE RSRQ
     * @hide
     */
    public int getLteRsrq() {
        return this.mLteRsrq;
    }

    /**
     * @return true if this is for GSM
     */
    public boolean isGsm() {
        return this.mIsGsm;
    }

    /**
     * @return hash code
     */
    @Override
    public int hashCode() {
        return ((mGsmSignalStrength * 0x1234)
                + mGsmBitErrorRate
                + mCdmaDbm + mCdmaEcio
                + mEvdoDbm + mEvdoEcio + mEvdoSnr
                + mLteRssi + mLteRsrp + mLteRsrq
                + (mIsGsm ? 1 : 0));
    }

    /**
     * @return true if the signal strengths are the same
     */
    @Override
    public boolean equals (Object o) {
        SignalStrength s;

        try {
            s = (SignalStrength) o;
        } catch (ClassCastException ex) {
            return false;
        }

        if (o == null) {
            return false;
        }

        return (mGsmSignalStrength == s.mGsmSignalStrength
                && mGsmBitErrorRate == s.mGsmBitErrorRate
                && mCdmaDbm == s.mCdmaDbm
                && mCdmaEcio == s.mCdmaEcio
                && mEvdoDbm == s.mEvdoDbm
                && mEvdoEcio == s.mEvdoEcio
                && mEvdoSnr == s.mEvdoSnr
                && mLteRssi == s.mLteRssi
                && mLteRsrp == s.mLteRsrp
                && mLteRsrq == s.mLteRsrq
                && mIsGsm == s.mIsGsm);
    }

    /**
     * @return string representation.
     */
    @Override
    public String toString() {
        return ("SignalStrength:"
                + " " + mGsmSignalStrength
                + " " + mGsmBitErrorRate
                + " " + mCdmaDbm
                + " " + mCdmaEcio
                + " " + mEvdoDbm
                + " " + mEvdoEcio
                + " " + mEvdoSnr
                + " " + mLteRssi
                + " " + mLteRsrp
                + " " + mLteRsrq
                + " " + (mIsGsm ? "gsm" : "cdma"));
    }

    /**
     * Test whether two objects hold the same data values or both are null
     *
     * @param a first obj
     * @param b second obj
     * @return true if two objects equal or both are null
     * @hide
     */
    private static boolean equalsHandlesNulls (Object a, Object b) {
        return (a == null) ? (b == null) : a.equals (b);
    }

    /**
     * Set SignalStrength based on intent notifier map
     *
     * @param m intent notifier map
     * @hide
     */
    private void setFromNotifierBundle(Bundle m) {
        mGsmSignalStrength = m.getInt("GsmSignalStrength");
        mGsmBitErrorRate = m.getInt("GsmBitErrorRate");
        mCdmaDbm = m.getInt("CdmaDbm");
        mCdmaEcio = m.getInt("CdmaEcio");
        mEvdoDbm = m.getInt("EvdoDbm");
        mEvdoEcio = m.getInt("EvdoEcio");
        mEvdoSnr = m.getInt("EvdoSnr");
        mLteRssi = m.getInt("LteRssi");
        mLteRsrp = m.getInt("LteRsrp");
        mLteRsrq = m.getInt("LteRsrq");
        mIsGsm = m.getBoolean("isGsm");
    }

    /**
     * Set intent notifier Bundle based on SignalStrength
     *
     * @param m intent notifier Bundle
     * @hide
     */
    public void fillInNotifierBundle(Bundle m) {
        m.putInt("GsmSignalStrength", mGsmSignalStrength);
        m.putInt("GsmBitErrorRate", mGsmBitErrorRate);
        m.putInt("CdmaDbm", mCdmaDbm);
        m.putInt("CdmaEcio", mCdmaEcio);
        m.putInt("EvdoDbm", mEvdoDbm);
        m.putInt("EvdoEcio", mEvdoEcio);
        m.putInt("EvdoSnr", mEvdoSnr);
        m.putInt("LteRssi", mLteRssi);
        m.putInt("LteRsrp", mLteRsrp);
        m.putInt("LteRsrq", mLteRsrq);
        m.putBoolean("isGsm", Boolean.valueOf(mIsGsm));
    }
}
