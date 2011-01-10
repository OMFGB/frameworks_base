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

/** {@hide}
 * This is an interface that the applications need to implement. An app
 * This interface will be used by CnE to notify apps of various events
 * related to connectivity.
 */
public interface LinkNotifier {

    public static int FAILURE_GENERAL = -1;
    public static int FAILURE_NO_LINKS = -2;

    /** {@hide}
     * This function notifies the availability of the Link for use. It can
     * get called due to one of the following reasons:
     * 1. getLink was called and connection was successful
     * 2. reportLinkStatisfaction was called with dissatisfaction
     * 3. if a previous connection is lost, after the onConnectionLost call
     * 4. switchLink called
     */
    public void onLinkAvail(LinkInfo info);

    /** {@hide}
     * This function will be called if the getLink call by the app has
     * failed to provide any link
     */
    public void onGetLinkFailure(int reason);

    /** {@hide}
     * This function notifies the availability of a better link. It can get
     * called due to one of the following reasons:
     * 1. reportLinkStatisfaction was called with isNotifyBetterCon true
     * 2. rejectSwitch was called with isNotifyBetterCon true
     * 3. switchLink was called with isNotifyBetterCon true
     */
    public void onBetterLinkAvail(LinkInfo info);

    /** {@hide}
     * This function notifies the connection loss. It can get called due to
     * one of the following reasons:
     * 1. previously established connection was lost
     * expect an onLinkAvail after this for any new connections
     * that can be used.
     */
    public void onLinkLost(LinkInfo info);

}
