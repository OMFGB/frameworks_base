/*
 *  Copyright 2011 John Weyrauch
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.aparache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package com.android.internal.view;


import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

/**
 * Basic View used for the base class layout of a lockscreen
 * unlocker. This is a single View unlocker. If mutliple
 * are need use {@link #LockScreenViewGroup}. This view
 * includes a listener to set and the grabbed state varaible.
 * 
 *
 */
public class LockScreenView extends View {


    /**
    * Either {@link #HORIZONTAL} or {@link #VERTICAL}.
    */	
	protected int mOrientation;

	public static final int HORIZONTAL = 0;
	public static final int VERTICAL = 1;
	 
	protected onLockViewSelectorListener mOnLockViewSelectorListener;
	protected int  mGrabbedState = onLockViewSelectorListener.LOCK_ICON_GRABBED_STATE_NONE;

	public LockScreenView(Context context) {
		super(context);
	}

	public LockScreenView(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public LockScreenView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}


	public interface onLockViewSelectorListener{

		static final int LOCK_ICON_GRABBED_STATE_NONE = 0;
		
		/**
		 * Primary Unlock handle
		 */
    	static final int LOCK_ICON_ONE = 1;
    	/**
    	 * 
    	 * Secondary unlock handle
    	 * Commonly used for setting the ringer mode
    	 * 
    	 */
    	static final int LOCK_ICON_TWO = 2;
    	
    	static final int LOCK_ICON_EXTRA_ONE = 3;
    	static final int LOCK_ICON_EXTRA_TWO = 4;
    	static final int LOCK_ICON_EXTRA_THREE = 5;
    	static final int LOCK_ICON_EXTRA_FOUR = 6;
    	
    	
    	static final int LOCK_ICON_OTHER = 20;
    	
    	/**
         * Called when the user moves a handle beyond the threshold.
         *
         * @param v The view that was triggered.
         * @param whichHandle  Which "dial handle" the user grabbed,
         *        either {@link #LEFT_HANDLE}, {@link #RIGHT_HANDLE}.
         */
        void onTrigger(View v, int Trigger);

        /**
         * Called when the "grabbed state" changes (i.e. when the user either grabs or releases
         * one of the handles.)
         *
         * @param v the view that was triggered
         * @param grabbedState the new state: {@link #NO_HANDLE}, {@link #LEFT_HANDLE},
         * or {@link #RIGHT_HANDLE}.
         */
        void onGrabbedStateChanged(View v, int GrabState);


	}

	protected onLockViewSelectorListener getOnLockViewSelectorListener(){
		return mOnLockViewSelectorListener;
	}
	
	/**
	 * Set the listener for the trigger and grabbed
	 * state changes
	 * 
	 * @param l The listener to set
	 * @return True if the listener was set
	 */
	public boolean setOnLockViewSelectorListener(onLockViewSelectorListener l){
		if(l != null){
			mOnLockViewSelectorListener = l;
			return true;
			}
		
		return false;
		
	}
	
	/**
     * Sets the current grabbed state, and dispatches a grabbed state change
     * event to our listener.
     */
	protected void setGrabbedState(int newState) {
		 if (newState != mGrabbedState) {
	            mGrabbedState = newState;
	            if (mOnLockViewSelectorListener != null) {
	            	mOnLockViewSelectorListener.onGrabbedStateChanged(this, mGrabbedState);
	            }
	        }
		
		
	}

    /**
     * Dispatches a trigger event to our listener.
     */
    protected void dispatchTriggerEvent(View v, int whichTrigger) {
        if (mOnLockViewSelectorListener != null) {
        	mOnLockViewSelectorListener.onTrigger(v, whichTrigger);
            
        }
    }
    
    protected boolean isVertical() {
        return (mOrientation == VERTICAL);
    }
    

}
