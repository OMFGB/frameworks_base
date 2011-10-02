/* Copyright (c) 2009, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of Code Aurora nor
 *       the names of its contributors may be used to endorse or promote
 *       products derived from this software without specific prior written
 *       permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NON-INFRINGEMENT ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

package android.net;

import android.os.Parcelable;
import android.os.Parcel;

import java.net.InetAddress;

/** {@hide}
 * This class provides information about a link. User can query this object
 * to get supported information about the link.
 */
public class LinkInfo implements Parcelable {

    /** IP address of the network link. Currently android supports only v4
     */
    private InetAddress ipAddr;

    /** Estimated forward-link bandwidth in kbps.
     */
    private int availFwLinkBw;

    /** Estimated reverse-link bandwidth in kbps.
     */
    private int availRevLinkBw;

    /** The link identifier..
     */
    private int nwId;

    public static final int INF_UNSPECIFIED = -1;
    public static final int STATUS_FAILURE = 0;
    public static final int STATUS_SUCCESS = 1;

    /** Default constructor
     */
    public LinkInfo() {
        ipAddr = null;
        availFwLinkBw = INF_UNSPECIFIED;
        availRevLinkBw = INF_UNSPECIFIED;
        nwId = INF_UNSPECIFIED;
    }

    /** parametric constructor
     */
    public LinkInfo(String ip,
                    int fwLinkBw,
                    int revLinkBw,
                    int netId) {
        try {
            ipAddr = InetAddress.getByName(ip);
        } catch (java.net.UnknownHostException e) {
        }
        availFwLinkBw = fwLinkBw;
        availRevLinkBw = revLinkBw;
        nwId = netId;
    }

    /* needed for Creator */
    private LinkInfo(InetAddress ip,
                    int fwLinkBw,
                    int revLinkBw,
                    int netId) {
        ipAddr = ip;
        availFwLinkBw = fwLinkBw;
        availRevLinkBw = revLinkBw;
        nwId = netId;
    }


    /** @hide
     * provides the IP address of the interface that this LinkInfo object
     * corresponds to.
     * @return IP address of the interface
     */
    public InetAddress getIpAddr() {
        return ipAddr;
    }

    /** @hide
     * provides the estimated forward-link bandwidth in kbps available on
     * the interface that this LinkInfo object corresponds to.
     * @return estimated forward-link bandwidth in kbps of the interface.
     */
    public int getAvailFwLinkBw() {
        return availFwLinkBw;
    }

    /** @hide
     * provides the estimated reverse-link bandwidth in kbps available on
     * the interface that this LinkInfo object corresponds to.
     * @return estimated reverse-link bandwidth in kbps of the interface.
     */
    public int getAvailRevLinkBw() {
        return availRevLinkBw;
    }

    /** @hide
     * provides the network id of the interface that this LinkInfo object
     * corresponds to.
     * @return network id of the interface.
     */
    public int getNwId() {
        return nwId;
    }


    /**
     * Implement the Parcelable interface
     * @hide
     */
    public int describeContents() {
        return 0;
    }

    /**
     * Implement the Parcelable interface.
     * @hide
     */
    public void writeToParcel(Parcel dest, int flags) {
        byte ip[] = ipAddr.getAddress();
        dest.writeInt(ip.length);
        dest.writeByteArray(ip);
        dest.writeInt(availFwLinkBw);
        dest.writeInt(availRevLinkBw);
        dest.writeInt(nwId);
    }

    /**
     * Implement the Parcelable interface.
     * @hide
     */
    public void readFromParcel(Parcel in) {
        byte ip[] = new byte[in.readInt()];
        in.readByteArray(ip);
        try {
            ipAddr = InetAddress.getByAddress(ip);
        } catch (java.net.UnknownHostException e) {
            ipAddr = null;
        }
        availFwLinkBw = in.readInt();
        availRevLinkBw = in.readInt();
        nwId = in.readInt();
    }

    /**
     * Implement the Parcelable interface.
     * @hide
     */
    public static final Creator<LinkInfo> CREATOR =
        new Creator<LinkInfo>() {
            public LinkInfo createFromParcel(Parcel in) {
                byte ip[] = new byte[in.readInt()];
                in.readByteArray(ip);
                InetAddress ipAddr;
                try {
                    ipAddr = InetAddress.getByAddress(ip);
                } catch (java.net.UnknownHostException e) {
                    return null;
                }
                int availFwLinkBw = in.readInt();
                int availRevLinkBw = in.readInt();
                int nwId = in.readInt();
                LinkInfo info = new LinkInfo(ipAddr,
                                             availFwLinkBw,
                                             availRevLinkBw,
                                             nwId);
                return info;
            }

            public LinkInfo[] newArray(int size) {
                return new LinkInfo[size];
            }
        };
}
