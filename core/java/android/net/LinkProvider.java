/* Copyright (c) 2009,2010 Code Aurora Forum. All rights reserved.
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

import android.net.IConnectivityManager;
import android.os.Binder;
import android.os.RemoteException;
import android.net.LinkInfo;
import android.os.ServiceManager;
import android.os.IBinder;
import android.util.Log;
import android.os.Handler;
import android.os.Message;
import android.os.Looper;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Map;

/** {@hide}
 * This class provides a means for applications to specify their requirements
 * and request for a link. Apps can also report their satisfaction with the
 * assigned link and switch links when a new link is available.
 */
public class LinkProvider
{
    static final String LOG_TAG = "LinkProvider";
    static final boolean DBG = false;

    /** {@hide}
     * Default Role Id, applies to any packet data traffic pattern that
     * doesn't have another role defined explicitly.
     */
    public static final int ROLE_DEFAULT =  0;

    /* role that the app wants to register.
     */
    private int mRole;

    /* link requirements of the app for the registered role.
     */
    private Map<LinkRequirement, String> mLinkReqs = null;

    /* Apps process id
     */
    private int mPid;

    /* LinkNotifier object to provide notification to the app.
     */
    private LinkNotifier mLinkNotifier;

    /* handle to connectivity service obj
     */
    private IConnectivityManager mService;

    private Handler mHandler;
    private Looper mLooper;
    private static final int ON_LINK_AVAIL        =  1;
    private static final int ON_BETTER_LINK_AVAIL =  2;
    private static final int ON_LINK_LOST         =  3;
    private static final int ON_GET_LINK_FAILURE  =  4;

    private Lock mLock;
    private Condition mHandlerAvail;

    public enum LinkRequirement {
        FW_LINK_BW,
        REV_LINK_BW,
    }

    /** {@hide}
     * This constructor can be used by apps to specify a role and optional
     * requirements and a link notifier object to receive notifications.
     * @param role Role that the app wants to register
     * @param reqs Requirements of the app for that role
     * @param notifier LinkNotifier object to provide notification to the app
     */
    public LinkProvider(int role, Map<LinkRequirement, String> reqs, LinkNotifier notifier)
      throws InterruptedException {
        mRole = role;
        mLinkReqs = reqs;
        mLinkNotifier = notifier;
        /* get handle to connectivity service */
        IBinder b = ServiceManager.getService("connectivity");
        mService = IConnectivityManager.Stub.asInterface(b);
        /* check for mservice to be null and throw a exception */
        if(mService == null){
            throw new IllegalStateException(
                "mService can not be null");
        }
        mLock = new ReentrantLock();
        mHandlerAvail  = mLock.newCondition();
    }


    /** {@hide}
     * This function will be used by apps to request a system after they have
     * specified their role and/or requirements.
     * @return {@code true}if the request has been accepted.
     * {@code false} otherwise.  A return value of true does NOT mean that a
     * link is available for the app to use. That will delivered via the
     * LinkNotifier.
     */
    public boolean getLink(){
        try {
            if(mHandler == null){
                try{
                    init();
                }catch(InterruptedException ex){
                    if (DBG) Log.d(LOG_TAG,"Interrupted exception!");
                    return false;
                }
            }else{
                if (DBG) Log.d(LOG_TAG,"getLink called before release is called!!");
                return false;
            }
            ConSvcEventListener listener = (ConSvcEventListener)
              IConSvcEventListener.Stub.asInterface( new ConSvcEventListener());
            mPid = listener.getCallingPid();
            if (DBG) Log.d(LOG_TAG,"GetLink called with role="+mRole+"pid="+mPid);
            return mService.getLink(mRole,mLinkReqs,mPid,listener);

        } catch ( RemoteException e ) {
            if (DBG) Log.d(LOG_TAG,"ConSvc throwed remoteExcept'n on startConn call");
            return false;
        }
    }

    private void init() throws InterruptedException{
        (new NotificationsThread()).start();
        /* block until mHandler gets created. */
        try{
            mLock.lock();
            if (mHandler == null) {
                mHandlerAvail.await();
            }
        } finally {
            mLock.unlock();
        }
    }

    private void deInit(){
        mLooper.quit();
        mHandler= null;
    }

    /** {@hide}
     * This function will be used by apps to report to CnE whether they are
     * satisfied or dissatisfied with the link that was assigned to them.
     * @param info {@code LinkInfo} about the Link assigned to the app. The
     * app needs to pass back the same LinkInfo object it received via the
     * {@code LinkNotifier}
     * @param isSatisfied whether the app is satisfied with the link or not
     * @param isNotifyBetterLink whether the app wants to be notified when
     * another link is available which CnE believes is "better" for the app
     * @return {@code true} if the request has been accepted by Android
     * framework, {@code false} otherwise.
     */
    public boolean reportLinkSatisfaction
    (
      LinkInfo info,
      boolean isSatisfied,
      boolean isNotifyBetterLink
    ){
        try {
            return mService.reportLinkSatisfaction(mRole,
                                                   mPid,
                                                   info,
                                                   isSatisfied,
                                                   isNotifyBetterLink);

        } catch ( RemoteException e ) {
            if (DBG) Log.d(LOG_TAG,"ConSvc throwed remoteExcept'n on reportConnSatis call");
            return false;
        }
    }

    /** {@hide}
     * When a "better link available" notification is delivered to the app,
     * the app has a choice on whether to switch to the new link or continue
     * with the old one. The app needs to call this API if it wants to switch
     * to the new link.
     * @param info {@code LinkInfo} about the new link provided to the app
     * @param isNotifyBetterLink Whether the app wants to be notified if a
     * "better" network for its role is available
     * @return {@code true}if the request has been accepted by Android
     * framework, {@code false} otherwise.
     */
    public boolean
    switchLink(LinkInfo info, boolean isNotifyBetterLink){
        try {
            return mService.switchLink(mRole,
                                       mPid,
                                       info,
                                       isNotifyBetterLink);

        } catch ( RemoteException e ) {
            if (DBG) Log.d(LOG_TAG,"ConSvc throwed remoteExcept'n on reportConnSatis call");
            return false;
        }
    }

    /** {@hide}
     * When a "better link available" notification is delivered to the app,
     * the app has a choice on whether to switch to the new link or continue
     * with the old one. The app needs to call this API if it wants to stay
     * with the old link.
     * @param info {@code LinkInfo} about the new link provided to the app
     * @param isnotifyBetterLink whether the app wants to be notified if a
     * "better" network for its role is available
     * @return {@code true} if the request has been accepted by Android
     * framework, {@code false} otherwise.
     */
    public boolean
    rejectSwitch(LinkInfo info, boolean isNotifyBetterLink){
        try {
            return mService.rejectSwitch(mRole,
                                         mPid,
                                         info,
                                         isNotifyBetterLink);

        } catch ( RemoteException e ) {
            if (DBG) Log.d(LOG_TAG,"ConSvc throwed remoteExcept'n on reportConnSatis call");
            return false;
        }
    }



    /** {@hide}
     * This function will be used by apps to release the network assigned to
     * them for a given role.
     * @return {@code true} if the request has been accepted by Android
     * framework, {@code false} otherwise.
     */
    public boolean releaseLink(){
        try {
            boolean retVal = mService.releaseLink(mRole,mPid);
            deInit();
            return retVal;
        } catch ( RemoteException e ) {
            if (DBG) Log.d(LOG_TAG,"ConSvc throwed remoteExcept'n on releaseLink call");
            return false;
        }
    }





    /** {@hide} */
    /* This class has the remoted function call backs that get called
     * when the ConSvc has to notify things to the app
     */
    private class ConSvcEventListener extends IConSvcEventListener.Stub {

        public  void onLinkAvail(LinkInfo info) {
            if (DBG) Log.v(LOG_TAG,"Sending OnLinkAvail with nwId="+info.getNwId()+
                  "to App");
            Message msg;
            msg = mHandler.obtainMessage(ON_LINK_AVAIL,
                                         info);
            msg.setTarget(mHandler);
            msg.sendToTarget();
            return;
        }

        public  void onBetterLinkAvail(LinkInfo info) {
            if (DBG) Log.v(LOG_TAG,"Sending onBetterLinkAvail with nwId="+info.getNwId()+
                  "to App");
            Message msg;
            msg = mHandler.obtainMessage(ON_BETTER_LINK_AVAIL,
                                         info);
            msg.setTarget(mHandler);
            msg.sendToTarget();
            return;
        }

        public  void onLinkLost(LinkInfo info) {
            if (DBG) Log.v(LOG_TAG,"Sending onLinkLost with nwId="+info.getNwId()+
                  "to App");
            Message msg;
            msg = mHandler.obtainMessage(ON_LINK_LOST,
                                         info);
            msg.setTarget(mHandler);
            msg.sendToTarget();
            return;
        }

        public  void onGetLinkFailure(int reason) {
            if (DBG) Log.v(LOG_TAG,"Sending onGetLinkFailure with reason="+reason+
                  "to App");
            Message msg;
            msg = mHandler.obtainMessage(ON_GET_LINK_FAILURE,
                                         reason);
            msg.setTarget(mHandler);
            msg.sendToTarget();
            return;
        }
    };

    private class NotificationsThread extends Thread {

        public void run() {
          Looper.prepare();
          mLooper = Looper.myLooper();
          mHandler = new Handler(){
            @Override
            public void handleMessage(Message msg) {
              if (DBG) Log.v(LOG_TAG,"handle Message called for msg = " + msg.what);
              switch (msg.what) {
                  case ON_LINK_AVAIL:{
                      LinkInfo info = (LinkInfo)msg.obj;
                      if(mLinkNotifier != null){
                          mLinkNotifier.onLinkAvail(info);
                      }
                      break;
                  }
                  case ON_BETTER_LINK_AVAIL:{
                      LinkInfo info = (LinkInfo)msg.obj;
                      if(mLinkNotifier != null){
                          mLinkNotifier.onBetterLinkAvail(info);
                      }
                      break;
                  }
                  case ON_LINK_LOST:{
                      LinkInfo info = (LinkInfo)msg.obj;
                      if(mLinkNotifier != null){
                          mLinkNotifier.onLinkLost(info);
                      }
                      break;
                  }
                  case ON_GET_LINK_FAILURE:{
                      int reason = (int)msg.arg1;
                      if(mLinkNotifier != null){
                          mLinkNotifier.onGetLinkFailure(reason);
                      }
                      break;
                  }
                  default:
                      if (DBG) Log.d(LOG_TAG,"Unhandled Message msg = " + msg.what);
              }
           }
         };
         mLock.lock();
         mHandlerAvail.signal();
         mLock.unlock();

         Looper.loop();
        }
    };
}
