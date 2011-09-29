package com.android.internal.policy.impl;

import android.content.ContentResolver;
import android.content.Intent;
import android.media.AudioManager;
import android.provider.Settings;
import android.provider.Settings.System;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;


import com.android.internal.R;
import com.android.internal.widget.CircularSelector;
import com.android.internal.widget.RotarySelector;
import com.android.internal.widget.SenseLikeLock;
import com.android.internal.widget.SlidingTab;
import com.android.internal.widget.UnlockRing;
import com.android.internal.widget.CircularSelector.OnCircularSelectorTriggerListener;
import com.android.internal.widget.RotarySelector.OnDialTriggerListener;
import com.android.internal.widget.SenseLikeLock.OnSenseLikeSelectorTriggerListener;
import com.android.internal.widget.SlidingTab.OnTriggerListener;
import com.android.internal.widget.UnlockRing.OnHoneyTriggerListener;

/**
 * Class abstractly represent a Lockscreen
 * It manages the lockscreen inflation 
 * and implements the lockscreen types
 * and their associated triggers.
 * 
 * Eventually this class should call out
 * to other packages for the view
 * that the lockscreen should inflate
 * 
 * 
 */
public class LockScreenManager implements OnTriggerListener, OnDialTriggerListener, 
OnCircularSelectorTriggerListener, OnHoneyTriggerListener, OnSenseLikeSelectorTriggerListener {


	private String TAG = this.getClass().getSimpleName();
	private ContentResolver mResolver;
	private int mType;
	private ViewGroup mLockscreen;
	private View mUnlocker;
	private LockscreenManagerCallback mLockscreenManagerCallback;
    private boolean mSilentMode;
	private AudioManager mAudioManager;
	
	 private String[] mCustomQuandrants = new String[4];

	
	/**
	 * Retrieves the lockscreen type in integer format
	 * 
	 * 
	 * @param v The VieGroup to attached the inflation to
	 * @param resolve See {@link retreiveActiveLockscreen }
	 * @return The integer format of the lockscreen
	 */
	LockScreenManager(ContentResolver resolve, ViewGroup layout){
		mLockscreen = layout;
		mResolver = resolve;
		mType = queryActiveLockscreen();
		
		
		 mCustomQuandrants[0] = Settings.System.getString(mResolver, Settings.System.LOCKSCREEN_CUSTOM_APP_HONEY_1 ) ;

		 mCustomQuandrants[1]  = (Settings.System.getString(mResolver,
	            Settings.System.LOCKSCREEN_CUSTOM_APP_HONEY_2));

		 mCustomQuandrants[2] = (Settings.System.getString(mResolver,
	            Settings.System.LOCKSCREEN_CUSTOM_APP_HONEY_3));

		 mCustomQuandrants[3] = (Settings.System.getString(mResolver,
	            Settings.System.LOCKSCREEN_CUSTOM_APP_HONEY_4));
		
		
	}
	
	/**
	 * Set the callback interface from which to respond the 
	 * the lockscreen with
	 * 
	 * @param l The interfcae to set
	 * @return True if succeful
	 */
	public boolean setCallbackInterface(LockscreenManagerCallback l){
		
		if(l != null){
			mLockscreenManagerCallback = l;
			return true;
		}
		
		return false;
	}
	
	/**
	 * Querys the active lockscreen
	 * 
	 * @return the lockscreen type in integer format
	 */
	private  int queryActiveLockscreen(){
		
		int i = Settings.System.getInt(mResolver, Settings.System.LOCKSCREEN_TYPE, Settings.System.USE_TAB_LOCKSCREEN);
		
		switch(i)
		{
		case Settings.System.USE_HONEYCOMB_LOCKSCREEN:
			return Settings.System.USE_HONEYCOMB_LOCKSCREEN;
		
		case Settings.System.USE_ROTARY_LOCKSCREEN:
			return Settings.System.USE_ROTARY_LOCKSCREEN;

		case Settings.System.USE_SENSELIKE_LOCKSCREEN:
			return Settings.System.USE_SENSELIKE_LOCKSCREEN;

		case Settings.System.USE_TAB_LOCKSCREEN:
			return Settings.System.USE_TAB_LOCKSCREEN;
			
		case Settings.System.USE_HCC_LOCKSCREEN:
			return Settings.System.USE_HCC_LOCKSCREEN;


		}


		// Always return the system default lockcsreen
		return Settings.System.USE_TAB_LOCKSCREEN;
	}
	
	/**
	 * Handles resolution of the lockscreen type and inflates the
	 * currently selected Lockscreen view
	 * 
	 * @param inflater The inflater to inflate the vier from
	 * @param inPortrait
	 */
	public void inflateLockscreen(LayoutInflater inflater, boolean inPortrait){
		
		Log.d("LockscreenManager", "the lockscren variable is:" +( mLockscreen != null ? "is initilized" : "is not initilized" ) );

		Log.d("LockscreenManager", "the lockscren inflater is:" +( inflater != null ? "is initilized" : "is not initilized" ) );
		if(inPortrait){
			
			switch(mType){
			
			
			case Settings.System.USE_HONEYCOMB_LOCKSCREEN:
	            inflater.inflate(R.layout.keyguard_screen_honey_unlock, mLockscreen, true);
				break;
			case Settings.System.USE_ROTARY_LOCKSCREEN:
				inflater.inflate(R.layout.keyguard_screen_rotary_unlock, mLockscreen, true);
				break;
			case Settings.System.USE_SENSELIKE_LOCKSCREEN:
				inflater.inflate(R.layout.keyguard_screen_senselike_unlock, mLockscreen, true);
				break;
			case Settings.System.USE_TAB_LOCKSCREEN:
				inflater.inflate(R.layout.keyguard_screen_tab_unlock, mLockscreen, true);
				break;
			case Settings.System.USE_HCC_LOCKSCREEN:
				inflater.inflate(R.layout.keyguard_screen_circular_unlock, mLockscreen, true);
				break;
	        
			
			}
			
			
		}
		else{
			
		switch(mType){
				
				
				case Settings.System.USE_HONEYCOMB_LOCKSCREEN:
		            inflater.inflate(R.layout.keyguard_screen_honey_unlock_land, mLockscreen, true);
					break;
				case Settings.System.USE_ROTARY_LOCKSCREEN:
					inflater.inflate(R.layout.keyguard_screen_rotary_unlock_land, mLockscreen, true);
					break;
				case Settings.System.USE_SENSELIKE_LOCKSCREEN:
					inflater.inflate(R.layout.keyguard_screen_senselike_unlock_land, mLockscreen, true);
					break;
				case Settings.System.USE_TAB_LOCKSCREEN:
					inflater.inflate(R.layout.keyguard_screen_tab_unlock_land, mLockscreen, true);
					break;
				case Settings.System.USE_HCC_LOCKSCREEN:
					inflater.inflate(R.layout.keyguard_screen_circular_unlock_land, mLockscreen, true);
					break;
		        
				
				}
			
		}
	}
	
	/**
	 * You must call {@link inflateLockscreen} and {@link aetupActiveLocksreen}
	 * before using this function.
	 * 
	 * @return The active initialized lockscreen that is selected
	 */
	public View retreiveActiveLockscreen(){
		

		return mUnlocker;
	}
	
	/**
	 * Sets up the active lockscreen according to what the intial
	 * creation specifications are. Each type of view extension is found
	 * and setup and then casted back to a view that the lockscreen
	 * can manage.
	 * 
	 */
	public void setupActiveLockscreen(){
		
		switch(mType){
		
			case Settings.System.USE_HONEYCOMB_LOCKSCREEN:{
		           UnlockRing unlockring = (UnlockRing) mLockscreen.findViewById(R.id.unlock_ring);
		           unlockring.setOnHoneyTriggerListener(this);
		           mUnlocker = (View) unlockring;
				break;
			}case Settings.System.USE_ROTARY_LOCKSCREEN:{
			     RotarySelector rotaryselector = (RotarySelector) mLockscreen.findViewById(R.id.rotary_selector);
			     rotaryselector.setOnDialTriggerListener(this);
		         rotaryselector.setLeftHandleResource(R.drawable.ic_jog_dial_unlock);
		         mUnlocker = (View) rotaryselector;
				break;
			} case Settings.System.USE_SENSELIKE_LOCKSCREEN:{
		          SenseLikeLock senseringselector = (SenseLikeLock) mLockscreen.findViewById(R.id.sense_selector);
		          senseringselector.setOnSenseLikeSelectorTriggerListener(this);
		          mUnlocker = (View) senseringselector;
				break;
			}case Settings.System.USE_TAB_LOCKSCREEN:{
				SlidingTab tmp = (SlidingTab) mLockscreen.findViewById(R.id.tab_selector);
				  tmp.setHoldAfterTrigger(true, false);
		           tmp.setLeftHintText(R.string.lockscreen_unlock_label);
		           tmp.setOnTriggerListener(this);
		           tmp.setLeftTabResources(
		                R.drawable.ic_jog_dial_unlock,
		                R.drawable.jog_tab_target_green,
		                R.drawable.jog_tab_bar_left_unlock,
		                R.drawable.jog_tab_left_unlock);
				mUnlocker = (View) tmp;
				break;
			}case Settings.System.USE_HCC_LOCKSCREEN:{
					  CircularSelector circularselector = (CircularSelector) mLockscreen.findViewById(R.id.circular_selector);
			          circularselector.setOnCircularSelectorTriggerListener(this);
			      	  mUnlocker = (View) circularselector;
				break;
			}
		
		}
	}

	

    /** {@inheritDoc} */
    @Override
    public void onTrigger(View v, int whichHandle) {

 	   v.getClass().getSimpleName();
        if (whichHandle == SlidingTab.OnTriggerListener.LEFT_HANDLE) {
        	mLockscreenManagerCallback.goToUnlockScreenFromManager();
        } else if (whichHandle == SlidingTab.OnTriggerListener.RIGHT_HANDLE) {
            // toggle silent mode
            mSilentMode = !mSilentMode;
            if (mSilentMode) {
                final boolean vibe = (Settings.System.getInt(
                    mResolver,
                    Settings.System.VIBRATE_IN_SILENT, 1) == 1);

                mAudioManager.setRingerMode(vibe
                    ? AudioManager.RINGER_MODE_VIBRATE
                    : AudioManager.RINGER_MODE_SILENT);
            } else {
                mAudioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
            }

            updateRightTabResources();

            String message = mSilentMode ?
            		mLockscreenManagerCallback.getStringWithContext(R.string.global_action_silent_mode_on_status) :
            			mLockscreenManagerCallback.getStringWithContext(R.string.global_action_silent_mode_off_status);

            final int toastIcon = mSilentMode
                ? R.drawable.ic_lock_ringer_off
                : R.drawable.ic_lock_ringer_on;

            final int toastColor = mSilentMode
                ? mLockscreenManagerCallback.getColorResource(R.color.keyguard_text_color_soundoff)
                :mLockscreenManagerCallback.getColorResource(R.color.keyguard_text_color_soundon);
                
                mLockscreenManagerCallback.toastMessageFromManager(message, toastColor, toastIcon);

        	mLockscreenManagerCallback.pokeWakeLockFromManager();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onGrabbedStateChange(View v, int grabbedState) {
    	SlidingTab selector = (SlidingTab) mUnlocker;
    	
 	   v.getClass().getSimpleName();
        if (grabbedState == SlidingTab.OnTriggerListener.RIGHT_HANDLE) {
            mSilentMode = isSilentMode();
            selector.setRightHintText(mSilentMode ? R.string.lockscreen_sound_on_label
                    : R.string.lockscreen_sound_off_label);
        }
        // Don't poke the wake lock when returning to a state where the handle is
        // not grabbed since that can happen when the system (instead of the user)
        // cancels the grab.
        if (grabbedState != SlidingTab.OnTriggerListener.NO_HANDLE) {
        	mLockscreenManagerCallback.pokeWakeLockFromManager();
        }
    }
    @Override
    public void onHoneyGrabbedStateChange(View v, int grabbedState) {

 	   v.getClass().getSimpleName();
        if (grabbedState != UnlockRing.OnHoneyTriggerListener.NO_HANDLE) {
            
        	mLockscreenManagerCallback.pokeWakeLockFromManager();
        }
    }

    @Override
    public void OnSenseLikeSelectorGrabbedStateChanged(View v, int GrabState) {

 	   v.getClass().getSimpleName();
 	   mLockscreenManagerCallback.pokeWakeLockFromManager();

    }
    @Override
    public void onSenseLikeSelectorTrigger(View v, int Trigger) {
 	   v.getClass().getSimpleName();
 	  mLockscreenManagerCallback.goToUnlockScreenFromManager();
 
    }
    
    public boolean setAudioManager(AudioManager am){
    	
    	if(am != null){
    		mAudioManager = am;
    		return true;
    	}
    	
    	return false;
    	
    }
    
    
    private boolean isSilentMode() {
        return mAudioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL;
    }
    
    public interface LockscreenManagerCallback{
    	
    	
    	void pokeWakeLockFromManager();
    	void pokeWakeLockFromManager(int i);
    	void goToUnlockScreenFromManager();
    	void toastMessageFromManager(final String text, final int color, final int iconResourceId);
    	void startActivityFromManager(Intent i);
    	String getStringWithContext(int rid);
    	int getColorResource(int rid);
    	
    }

    public void destroyManager(){
    	
    	mResolver = null;
    	mCustomQuandrants = null;
    	mLockscreenManagerCallback = null;
    	mUnlocker = null;
    	
    }
    public void OnCircularSelectorGrabbedStateChanged(View v, int GrabState) {

  	   v.getClass().getSimpleName();
  	   mLockscreenManagerCallback.pokeWakeLockFromManager();

     }
     
     public void onHoneyTrigger(View v, int trigger) {

    	UnlockRing selector = (UnlockRing) mUnlocker;
  	   v.getClass().getSimpleName();
         final String TOGGLE_SILENT = "silent_mode";
         
         if (trigger == UnlockRing.OnHoneyTriggerListener.UNLOCK_HANDLE) {
             mLockscreenManagerCallback.goToUnlockScreenFromManager();

         } else if (mCustomQuandrants[0] != null
                 && trigger == UnlockRing.OnHoneyTriggerListener.QUADRANT_1) {
             if (mCustomQuandrants[0].equals(TOGGLE_SILENT)) {
                 toggleSilentMode();
                 mLockscreenManagerCallback.pokeWakeLockFromManager();
                 selector.reset(false);
             } else {
                 try {
                     Intent i = Intent.parseUri(mCustomQuandrants[0], 0);
                     i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                             | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                     mLockscreenManagerCallback.startActivityFromManager(i);
                     mLockscreenManagerCallback.goToUnlockScreenFromManager();
                 } catch (Exception e) {
                	 selector.reset(false);
                 }
             }
         } else if (mCustomQuandrants[1] != null
                 && trigger == UnlockRing.OnHoneyTriggerListener.QUADRANT_2) {
             if (mCustomQuandrants[1].equals(TOGGLE_SILENT)) {
                 toggleSilentMode();
                 selector.reset(false);
                 mLockscreenManagerCallback.pokeWakeLockFromManager();
             } else {
                 try {
                     Intent i = Intent.parseUri(mCustomQuandrants[1], 0);
                     i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                             | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                     mLockscreenManagerCallback.startActivityFromManager(i);
                     mLockscreenManagerCallback.goToUnlockScreenFromManager();
                 } catch (Exception e) {
                	 selector.reset(false);
                 }
             }
         } else if (mCustomQuandrants[2] != null
                 && trigger == UnlockRing.OnHoneyTriggerListener.QUADRANT_3) {
             if (mCustomQuandrants[2].equals(TOGGLE_SILENT)) {
                 toggleSilentMode();
                 selector.reset(false);
                 mLockscreenManagerCallback.pokeWakeLockFromManager();
             } else {
                 try {
                     Intent i = Intent.parseUri(mCustomQuandrants[2], 0);
                     i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                             | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                     mLockscreenManagerCallback.startActivityFromManager(i);
                     mLockscreenManagerCallback.goToUnlockScreenFromManager();
                 } catch (Exception e) {
                     selector.reset(false);
                 }
             }
         } else if (mCustomQuandrants[3] != null
                 && trigger == UnlockRing.OnHoneyTriggerListener.QUADRANT_4) {
             if (mCustomQuandrants[3].equals(TOGGLE_SILENT)) {
                 toggleSilentMode();
                 selector.reset(false);
                 mLockscreenManagerCallback.pokeWakeLockFromManager();
             } else {
                 try {
                     Intent i = Intent.parseUri(mCustomQuandrants[3], 0);
                     i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                             | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                     mLockscreenManagerCallback.startActivityFromManager(i);
                     mLockscreenManagerCallback.goToUnlockScreenFromManager();
                 } catch (Exception e) {
                     selector.reset(false);
                 }
             }
         }
         mUnlocker = (View) selector;
     }

     public void onCircularSelectorTrigger(View v, int Trigger) {
        mLockscreenManagerCallback.goToUnlockScreenFromManager();
         //

     }
     
     /** {@inheritDoc} */
     public void onDialTrigger(View v, int whichHandle) {

  	   v.getClass().getSimpleName();
         boolean mUnlockTrigger=false;
         boolean mCustomAppTrigger=false;

         if(whichHandle == RotarySelector.OnDialTriggerListener.LEFT_HANDLE){
             mUnlockTrigger=true;
         }

         if (mUnlockTrigger) {
             this.mLockscreenManagerCallback.goToUnlockScreenFromManager();
         } 
         
         if (whichHandle == RotarySelector.OnDialTriggerListener.RIGHT_HANDLE) {
        	 // toggle silent mode
        	 toggleSilentMode();
        	 updateRightTabResources();

        	 String message = mSilentMode ?  mLockscreenManagerCallback.getStringWithContext(R.string.global_action_silent_mode_on_status) :
        		 mLockscreenManagerCallback.getStringWithContext(R.string.global_action_silent_mode_off_status);

        	 final int toastIcon = mSilentMode ? R.drawable.ic_lock_ringer_off
        			 : R.drawable.ic_lock_ringer_on;


        	 final int toastColor = mSilentMode ? mLockscreenManagerCallback.getColorResource(
        			 R.color.keyguard_text_color_soundoff) : mLockscreenManagerCallback.getColorResource(
        					 R.color.keyguard_text_color_soundon);


        			 mLockscreenManagerCallback.toastMessageFromManager( message, toastColor, toastIcon);
        			 mLockscreenManagerCallback.pokeWakeLockFromManager();
         }
     }

     public void updateRightTabResources() {
         boolean vibe = mSilentMode
             && (mAudioManager.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE);

         switch(mType){
	         case Settings.System.USE_ROTARY_LOCKSCREEN:{
	        	 RotarySelector selector = (RotarySelector) mUnlocker;
	        	           selector
		            .setRightHandleResource(mSilentMode ? (vibe ? R.drawable.ic_jog_dial_vibrate_on
		                    : R.drawable.ic_jog_dial_sound_off) : R.drawable.ic_jog_dial_sound_on);
	        	  mUnlocker = (View) selector;
	         }
	         case Settings.System.USE_TAB_LOCKSCREEN:{
	        	 SlidingTab selector = (SlidingTab) mUnlocker;
	             selector.setRightTabResources(
		                    mSilentMode ? ( vibe ? R.drawable.ic_jog_dial_vibrate_on
		                                         : R.drawable.ic_jog_dial_sound_off )
		                                : R.drawable.ic_jog_dial_sound_on,
		                    mSilentMode ? R.drawable.jog_tab_target_yellow
		                                : R.drawable.jog_tab_target_gray,
		                    mSilentMode ? R.drawable.jog_tab_bar_right_sound_on
		                                : R.drawable.jog_tab_bar_right_sound_off,
		                    mSilentMode ? R.drawable.jog_tab_right_sound_on
		                                : R.drawable.jog_tab_right_sound_off);
	             mUnlocker = (View) selector;
	         }
         }
     }
     
     private void toggleSilentMode() {
         // tri state silent<->vibrate<->ring if silent mode is enabled, otherwise toggle silent mode
         final boolean mVolumeControlSilent = Settings.System.getInt(mResolver,
             Settings.System.VOLUME_CONTROL_SILENT, 0) != 0;
         mSilentMode = mVolumeControlSilent
             ? ((mAudioManager.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE) || !mSilentMode)
             : !mSilentMode;
         if (mSilentMode) {
             final boolean vibe = mVolumeControlSilent
             ? (mAudioManager.getRingerMode() != AudioManager.RINGER_MODE_VIBRATE)
             : (Settings.System.getInt(
                 mResolver,
                 Settings.System.VIBRATE_IN_SILENT, 1) == 1);

             mAudioManager.setRingerMode(vibe
                 ? AudioManager.RINGER_MODE_VIBRATE
                 : AudioManager.RINGER_MODE_SILENT);
         } else {
             mAudioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
         }
 }
     
     public void setVisibility(int vis){
    	 
    	 mUnlocker.setVisibility(vis);
    	 
     }
}
