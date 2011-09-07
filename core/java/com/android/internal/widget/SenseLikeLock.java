/*
 *  Copyright 2011 John Weyrauch
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package com.android.internal.widget;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.RotateAnimation;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;
import android.widget.ImageView;

import com.android.internal.R;



/*
 * This class represents an unlock ring that can be moved 
 * around along with definable shortcuts that can also
 * be used to unlock the device. This is an open source clean room
 * reverse engineering of the HTC unlocker for sense 3.0 > 
 * 
 */
public class SenseLikeLock extends View{
	
	
	// ********************* Debug Variables
	
	
	private String TAG = "SenseLikeLock";
	private static final boolean DBG = true;
	private static final boolean IDBG = DBG;
	private static final boolean TDBG = false;
    private static final boolean VISUAL_DEBUG = false;
	
    private Animation mUnlockAnimation;
    
    // ***********Rotation constants and variables
    /**
     * Either {@link #HORIZONTAL} or {@link #VERTICAL}.
     */	
	 private int mOrientation;

	 public static final int HORIZONTAL = 0;
	 public static final int VERTICAL = 1;
    
	 
	 // ********************* UI Elements
	 
	   final Matrix mBgMatrix = new Matrix();
	   private Paint mPaint = new Paint();
	   
	   
	   // *** Backgrounds **
	   Bitmap mLowerBackground;
	   Bitmap mShortcutsBackground;
	   
	   // ** Unlocker icons **
	   Bitmap mLockIcon;
	   Bitmap mLockAppIcon;
	   
	   // ** Shortcut icons **
	   Bitmap mShortCutOne;
	   Bitmap mShortCutTwo;
	   Bitmap mShortCutThree;
	   Bitmap mShortCutFour;
	   
	   private float mShortCutHeight;
	   
	   private int mLockX, mLockY;
	   private boolean mIsTouchInCircle = false;
	   private boolean mUsingShortcuts = false;
	   private boolean mTriggering = false;
	   
	   
	   private Canvas mCanvas;
	   
	   private float mDensity;
	   
	   // ***************
	   private OnSenseLikeSelectorTriggerListener mSenseLikeTriggerListener;
	   private int  mGrabbedState = OnSenseLikeSelectorTriggerListener.ICON_GRABBED_STATE_NONE;
	 
	   Boolean mSlideisSensitive = false;
	private float mDensityScaleFactor = 1;
	private int mShortCutSelected;
	
	private enum mSelected {
		
		LOCK(1),
		SHORTCUT(2);
		
		private final double value;

		
		mSelected(int i){
			this.value = i;
			
		}
	}
	 
    //
    //********************** Constructors**********
	//
	public SenseLikeLock(Context context) {
		this(context,null);
		
		// TODO Auto-generated constructor stub
	}
	public SenseLikeLock(Context context, AttributeSet attrs) {
		super(context,attrs);
		
		   TypedArray a =
	            context.obtainStyledAttributes(attrs, R.styleable.SenseLikeSelector);
		   // TODO obtain proper orientaion
		   
	        mOrientation = a.getInt(R.styleable.SenseLikeSelector_orientation, VERTICAL);
	        
	        Resources r = getResources();
	        mDensity = r.getDisplayMetrics().density;
	        int densityDpi = r.getDisplayMetrics().densityDpi;

	        /*
	         * this hack assumes people change build.prop for increasing
	         * the virtual size of their screen by decreasing dpi in
	         * build.prop file. this is often done especially for hd
	         * phones. keep in mind changing build.prop and density
	         * isnt officially supported, but this should do for most cases
	         */
	        if(densityDpi <= 240 && densityDpi >= 180)
	            mDensityScaleFactor=(float)(240.0 / densityDpi);
	        if(densityDpi <= 160 && densityDpi >= 120)
	            mDensityScaleFactor=(float)(160.0 / densityDpi);

	        
	        
	        

	        a.recycle();
	        
	        initializeUI();
		// TODO Auto-generated constructor stub
	}
	
	//**************** Overridden super methods
	
	@Override 
	public boolean onTouchEvent(MotionEvent event){
		super.onTouchEvent(event);
		
		final int height = getHeight();
		final int width  = getWidth();
		
		final int action = event.getAction();
		
	
		final int eventX = (int) event.getX();
                
        final int eventY = (int) event.getY();

        if (DBG) log("x -" + eventX + " y -" + eventY);
    	                
		switch (action) {
        case MotionEvent.ACTION_DOWN:
            if (DBG) log("touch-down");
         

			// If the touch is on a shortcut, lock the ring
			// in a up front view. Cause the ring bg to display
			// the app type

			setLockXY(eventX, eventY);
			
			if(whichShortcutSelected()){
				
				setGrabbedState(OnSenseLikeSelectorTriggerListener.ICON_SHORTCUT_GRABBED_STATE_GRABBED);
				invalidate();
				mUsingShortcuts = true;
				
				
			}else {
				// shortcut was not grabbed
				
				mIsTouchInCircle = true;
				setGrabbedState(OnSenseLikeSelectorTriggerListener.ICON_GRABBED_STATE_GRABBED);
				invalidate();
			}
			
		   	
		
     
       
            break;

        case MotionEvent.ACTION_MOVE:
            if (DBG) log("touch-move");
            setLockXY(eventX, eventY);
            if(mUsingShortcuts){
            
            	int i = OnSenseLikeSelectorTriggerListener.LOCK_ICON_SHORTCUT_ONE_TRIGGERED;
            	int ar[] = {(width - mLockIcon.getWidth())/2, (height -(2*(mLockIcon.getHeight()/3))) };
            
            if(isShortTriggered( eventX, eventY)){
            	Log.d(TAG, "Shortcut Triggered");
            
            	switch(this.mShortCutSelected){
            		
            	case 1:
            		dispatchTriggerEvent(OnSenseLikeSelectorTriggerListener.LOCK_ICON_SHORTCUT_ONE_TRIGGERED);
            		reset();
            		break;
            	case 2:
            		dispatchTriggerEvent(OnSenseLikeSelectorTriggerListener.LOCK_ICON_SHORTCUT_TWO_TRIGGERED);
            		reset();
            		break;
            	case 3:
            		dispatchTriggerEvent(OnSenseLikeSelectorTriggerListener.LOCK_ICON_SHORTCUT_THREE_TRIGGERED);
            		reset();
            		break;
            	case 4:
            		dispatchTriggerEvent(OnSenseLikeSelectorTriggerListener.LOCK_ICON_SHORTCUT_FOUR_TRIGGERED);
            		reset();
            		break;
            	}
            }
            	// If the shorcut is pulled down
            	// dispatch the event
            }
            else{
            	
            	if(isLockIconTriggered()){
            		reset();
            		mTriggering = true;
            		
            	}
            
            }
            
            	
		    invalidate();
		    
            break;
        case MotionEvent.ACTION_UP:
            if (DBG) log("touch-up");
            reset();
            invalidate();
            break;
        case MotionEvent.ACTION_CANCEL:
            if (DBG) log("touch-cancel");
            reset();
            invalidate();
        
    }
		
		return true;
		
	}
	
	
	private boolean isLockIconTriggered() {
		
		if((mLockX > this.getWidth() || mLockX < 0) || (mLockX == 0 || mLockX == this.getWidth() )){
    		Log.d(TAG, "Dispatching horizontal lock trigger event");
    		dispatchTriggerEvent(OnSenseLikeSelectorTriggerListener.LOCK_ICON_TRIGGERED);
    		return true;
    		
    	}// dispatch the trigger event
    	if((mLockY < (this.getHeight()/4) || mLockY > this.getHeight()) ||
    			( mLockY == (this.getHeight()/2) || mLockY == this.getHeight()) ){

    		Log.d(TAG, "Dispatching vertical lock trigger event");
    		dispatchTriggerEvent(OnSenseLikeSelectorTriggerListener.LOCK_ICON_TRIGGERED);
    		return true;
    	}// dispatch the trigger event
    	return false;
	}
	private boolean isShortTriggered(int x, int y) {
		int width = this.getWidth()/2;
		int height = this.getHeight() - (this.mLockIcon.getHeight()/4);
		int radius = this.mShortcutsBackground.getWidth()/4;
		
		int CartesianShiftTouchX;
    	int CartesianShiftTouchY; 
    	
    	if(x < width)
    		CartesianShiftTouchX = width - x;
    	else
    		 CartesianShiftTouchX = x - width;
    	
    	if(y < height)
    		CartesianShiftTouchY = height - y;
    	else
    		 CartesianShiftTouchY = y - height;
    	
		
    	
    	int YTouchRadius = (int) Math.sqrt((CartesianShiftTouchX*CartesianShiftTouchX) + (CartesianShiftTouchY*CartesianShiftTouchY));
    	
    	if(YTouchRadius > radius)
    		return false;
    	else 
    		return true;
    	
	}
	private boolean whichShortcutSelected() {
		
		Log.d(TAG, "Figuring out which shortcut");

        if (DBG) log("x -" + mLockX + " y -" + mLockY);
		
		if((mLockY > this.mShortCutHeight) && (mLockY < (this.mShortCutHeight + this.mShortcutsBackground.getHeight())) ){
			// then we are in the shortcut box
			// next determine the shortcut
			int width = this.getWidth()/2; // start from the middle
			int padding = this.mShortcutsBackground.getWidth()/2;
			Log.d(TAG, "Touch within shortcut bar");
			
			if(mLockX >  width - (padding*6) && mLockX < width - (padding*4)){
				// this is the first lock
				mShortCutSelected = 1;
				Log.d(TAG, "Shortcut one");
				return true;
			}
			if(mLockX >  width - (padding*3) && mLockX < width - (padding)){
				// this is the first lock
				mShortCutSelected = 2;
				Log.d(TAG, "Shortcut two");
				
				return true;
			}
			if(mLockX <  width + (padding*3) && mLockX > width + (padding)){
				// this is the first lock
				mShortCutSelected = 3;
				Log.d(TAG, "Shortcut three");
				return true;
			}
			if(mLockX <  width + (padding*6) && mLockX > width + (padding*4)){
				// this is the first lock
				mShortCutSelected = 4;
				Log.d(TAG, "Shortcut four");
				return true;
			}
			
			Log.d(TAG, "No shortcut selected");
			return false;
		}

		Log.d(TAG, "No touch in shortcut bar");
	return false;
		
		
	}
	
	@Override 
	public void onDraw(Canvas canvas){
		super.onDraw(canvas);
		mCanvas = canvas;
		
		  if (IDBG) log("Redrawing the view");

          final int width = getWidth();
          final int height = getHeight();
          final int halfWidth = width/2;
          final int halfHeight = height/2;
          mShortCutHeight = height - (this.mLockIcon.getHeight()/2) - this.mLowerBackground.getHeight();
          int padding = this.mShortcutsBackground.getWidth()/2;
 	    
          if (DBG) log("The width of the view is " + width + " and the hieght of the veiw is " + height );

          if (VISUAL_DEBUG) {
              // draw bounding box around widget

			  if (IDBG) log("Debugging the widget visibly");
              mPaint.setColor(0xffff0000);
              mPaint.setStyle(Paint.Style.STROKE);
              canvas.drawRect(0, 0, width-1, height-1 , mPaint);
              
              float h = height - (this.mLockIcon.getHeight()/2) - this.mLowerBackground.getHeight()/2;              
              canvas.drawRect(0, mShortCutHeight , width, mShortCutHeight + this.mShortcutsBackground.getHeight() , mPaint);
              canvas.drawLine(halfWidth, height, halfWidth, 0, mPaint);
          
          }
          
       

         
          // always draw the lower background
          canvas.drawBitmap(this.mLowerBackground,  0, (height -(this.mLowerBackground.getHeight()) ), mPaint);
          
          // Draw the shortcuts 
         
          
          if(mIsTouchInCircle){	

          		mLockIcon = getBitmapFor(R.drawable.sense_ring_on_unlock);
        		canvas.drawBitmap(mLockIcon,  mLockX-(mLockIcon.getWidth()/2), mLockY - mLockIcon.getHeight()/2, mPaint);
        	
          }
          else if(mUsingShortcuts){
        	  // Cause the ring to move up 
        	  // disabled ring grabbing
        	  // enable shortcut sliding
        	  // set the grabb state to shortcut
        	  
        	  Log.d(TAG, "Shorcut bar drawing without moving ring");

              // Draw before the rest so that the other are placed on top
        	  // of the ring
        	  canvas.drawBitmap(mLockAppIcon,  (width - mLockIcon.getWidth())/2, (height -(2*(mLockIcon.getHeight()/3))), mPaint);
    		  
            
              drawShorts(canvas, halfWidth, padding);
              
             
          }
          else {
        	  
        	  Log.d(TAG, "Shorcut bar drawing with moving ring");
        	  drawShortOne(canvas, halfWidth - (padding*6), mShortCutHeight);
        	  drawShortTwo(canvas,  halfWidth - (padding*3), mShortCutHeight);
        	  drawShortThree(canvas, halfWidth + (padding), mShortCutHeight);
        	  drawShortFour(canvas, halfWidth + (padding*4), mShortCutHeight);
        	  
        	  
        	  if(isVertical()){
	        	  // Fallback case where the lock is always drawn in the center on the bottom of the view
	        	   canvas.drawBitmap(mLockIcon,  (width/2)-(mLockIcon.getWidth()/2), (height -(mLockIcon.getHeight()/3)), mPaint);
	     	  }else{
	    		// TODO  
	     		 canvas.drawBitmap(mLockIcon,  (width - mLockIcon.getWidth())/2, (height -(mLockIcon.getHeight()/2)), mPaint);
	    	  }
        	  
          }
          
          
		
		return;
	}
	private void doUnlockAnimation() {
		Log.d(TAG, "dounlockanimation");
		
		this.mUnlockAnimation = new ScaleAnimation(1,0,1,0);
		this.mUnlockAnimation.setDuration(1000L);
		this.mUnlockAnimation.setInterpolator(new AccelerateInterpolator());

		mUnlockAnimation.setRepeatCount(0);
		this.startAnimation(mUnlockAnimation);
		
	
		
		
		// TODO Auto-generated method stub
		
	}
	private void drawShorts(Canvas canvas, int halfWidth, int padding) {
	

        switch(mShortCutSelected){
	              case 1 : { 
	            	  Log.d(TAG, "Drawing shorcut new position");
	            	  	
	            	  drawMovableShort(canvas, 1, mShortcutsBackground.getWidth()/2 );
	            	  drawShortTwo(canvas,  halfWidth - (padding*3), mShortCutHeight);
	            	  drawShortThree(canvas, halfWidth + (padding), mShortCutHeight);
	            	  drawShortFour(canvas, halfWidth + (padding*4), mShortCutHeight);
	            	  	break;
	          	}
          	  
            case 2 : {
          	  drawMovableShort(canvas, 2, mShortcutsBackground.getWidth()/2);
          	  drawShortOne(canvas, halfWidth - (padding*6), mShortCutHeight);
          	  drawShortThree(canvas, halfWidth + (padding), mShortCutHeight);
          	  drawShortFour(canvas, halfWidth + (padding*4), mShortCutHeight);
          	  
            }
      	  break;
            case 3 : {
          	  drawMovableShort(canvas, 3, mShortcutsBackground.getWidth()/2);
          	  drawShortOne(canvas, halfWidth - (padding*6), mShortCutHeight);
          	  drawShortTwo(canvas,  halfWidth - (padding*3), mShortCutHeight);
          	  drawShortFour(canvas, halfWidth + (padding*4), mShortCutHeight);
            }
      	  break;
            case 4 : {
          	  drawMovableShort(canvas, 4, mShortcutsBackground.getWidth()/2);
          	  drawShortOne(canvas, halfWidth - (padding*6), mShortCutHeight);
          	  drawShortTwo(canvas,  halfWidth - (padding*3), mShortCutHeight);
          	  drawShortThree(canvas, halfWidth + (padding), mShortCutHeight);
            }
      	  break;
        
        }
		
	}
	public void drawMovableShort(Canvas canvas, int whichshort, int offset){
		
		switch(whichshort){
		case 1:
			 drawShortOne(canvas, mLockX - offset, mLockY- offset);
			 break;
		case 2:
			 drawShortTwo(canvas, mLockX- offset, mLockY- offset);
			break;
		case 3:
			 drawShortThree(canvas, mLockX- offset, mLockY- offset);
			break;
		case 4:
			 drawShortFour(canvas, mLockX - offset, mLockY- offset);
			break;
		
		}
		
		
	}
	private void drawShortOne(Canvas canvas, float postx, float posty){

        canvas.drawBitmap(this.mShortcutsBackground,  postx, posty, mPaint);
        canvas.drawBitmap(this.mShortCutOne, postx, posty, mPaint);
	
	}
	private void drawShortTwo(Canvas canvas, float postx, float posty){
		 canvas.drawBitmap(this.mShortcutsBackground,   postx, posty, mPaint);
         canvas.drawBitmap(this.mShortCutTwo,  postx, posty, mPaint);
		
	}
	private void drawShortThree(Canvas canvas, float postx, float posty){

        canvas.drawBitmap(this.mShortcutsBackground,   postx, posty, mPaint);
        canvas.drawBitmap(this.mShortCutThree,   postx, posty, mPaint);
		
	}
	private void drawShortFour(Canvas canvas, float postx, float posty){
		canvas.drawBitmap(this.mShortcutsBackground,   postx, posty, mPaint);
        canvas.drawBitmap(this.mShortCutFour,  postx, posty, mPaint);
		  
	}
	
    @Override 
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    	   
		  if (IDBG) log("Measuring the demensions of the view");
    	   
		  
		  final int length = isVertical() ?
                  MeasureSpec.getSize(widthMeasureSpec) :
                  MeasureSpec.getSize(heightMeasureSpec);
                  
      	final int height = (isVertical() ?
                      (MeasureSpec.getSize(heightMeasureSpec)) :
                      MeasureSpec.getSize(widthMeasureSpec)/2);
		  
		 
                


		  if (DBG) log("The demensions of the view is length:" + length + " and height: " + height );
           if (isVertical()) {
               setMeasuredDimension(length, height);
           } else {
               setMeasuredDimension(height, length);
           }
       }

    
  
    
    // ************* Initilization function
    
    private void initializeUI(){
    	
    	mLockIcon = getBitmapFor(R.drawable.sense_ring);
    	mLowerBackground = getBitmapFor(R.drawable.sense_panel);
    	mShortcutsBackground = getBitmapFor(R.drawable.app_bg);
    	mShortCutOne = getBitmapFor(R.drawable.app_camera);
    	mShortCutTwo = getBitmapFor(R.drawable.app_gmail);
    	mShortCutThree = getBitmapFor(R.drawable.app_msg);
    	mShortCutFour = getBitmapFor(R.drawable.app_phone);
    	mLockAppIcon = getBitmapFor(R.drawable.sense_ring_appready);
    	
    }
    
    
    
    
    
    // Lock position function
    
    private void setLockXY(int eventX, int eventY){
    	mLockX = eventX;
    	mLockY = eventY;
    	
    	
    }
    
    /**
     * This is the interface for creating the is-a
     * relationship with another class. In this context
     * it is used to allow the trigger for the widget
     * to be application dependent. The trigger can be 
     * in one of two state either
     *  {@link ICON_GRABBED_STATE_NONE} or {@link ICON_GRABBED_STATE_GRABBED}
     */
    
    public interface OnSenseLikeSelectorTriggerListener{
    	
    	// Grabbed state
    	static final int ICON_GRABBED_STATE_NONE = 0;
    	static final int ICON_GRABBED_STATE_GRABBED = 1;
    	static final int ICON_SHORTCUT_GRABBED_STATE_GRABBED = 2;
    	
    	// Trigger for the lock icon
    	static final int LOCK_ICON_TRIGGERED = 10;
    	
    	// Tigger const for the shortcut icons
    	static final int LOCK_ICON_SHORTCUT_ONE_TRIGGERED   = 11;
    	static final int LOCK_ICON_SHORTCUT_TWO_TRIGGERED   = 12;
    	static final int LOCK_ICON_SHORTCUT_THREE_TRIGGERED = 13;
    	static final int LOCK_ICON_SHORTCUT_FOUR_TRIGGERED  = 14;
    	
    	// Sets the grabbed state
    	public void OnSenseLikeSelectorGrabbedStateChanged(View v, int GrabState);
    	
    	// Trigger interface methods
    	public void onSenseLikeSelectorTrigger(View v, int Trigger);

    	
    }
    
    // *********************** Callbacks
    
    /**
     * Registers a callback to be invoked when the music controls
     * are "triggered" by sliding the view one way or the other
     * or pressing the music control buttons.
     *
     * @param l the OnMusicTriggerListener to attach to this view
     */
    public void setOnSenseLikeSelectorTriggerListener(OnSenseLikeSelectorTriggerListener l) {
    	 if (DBG) log("Setting the listners");
    	this.mSenseLikeTriggerListener = l;
    }
    
    /**
     * Sets the current grabbed state, and dispatches a grabbed state change
     * event to our listener.
     */
    private void setGrabbedState(int newState) {
        if (newState != mGrabbedState) {
            mGrabbedState = newState;
            if (mSenseLikeTriggerListener != null) {
                mSenseLikeTriggerListener.OnSenseLikeSelectorGrabbedStateChanged(this, mGrabbedState);
            }
        }
    }
    
    
    /**
     * Dispatches a trigger event to our listener.
     */
    private void dispatchTriggerEvent(int whichTrigger) {
    	
    	 if (IDBG) log("Dispatching a trigered event");
        //vibrate(VIBRATE_LONG);
        if (mSenseLikeTriggerListener != null) {
        	switch(whichTrigger){
        	case OnSenseLikeSelectorTriggerListener.LOCK_ICON_SHORTCUT_ONE_TRIGGERED:
        	case OnSenseLikeSelectorTriggerListener.LOCK_ICON_SHORTCUT_TWO_TRIGGERED:
        	case OnSenseLikeSelectorTriggerListener.LOCK_ICON_SHORTCUT_THREE_TRIGGERED:
        	case OnSenseLikeSelectorTriggerListener.LOCK_ICON_SHORTCUT_FOUR_TRIGGERED:{
        		// Create an animation that moves the circle to the middle
        		// and becomes bigger, with the app icon in the middle, then unlock
        		//mCanvas.
        		doUnlockAnimation();
        		break;
        		
        	}
        	case OnSenseLikeSelectorTriggerListener.LOCK_ICON_TRIGGERED:
        		// Create an animation that moves the circle to the middle
        		// and becomes bigger, then unlock
        		doUnlockAnimation();
        		break;
        	
        	}
            
        		mSenseLikeTriggerListener.onSenseLikeSelectorTrigger(this, whichTrigger);
            
        }
    }
    
    
    //************************** Misc Function***********************
    private boolean isVertical() {
        return (mOrientation == VERTICAL);
    }
    
    
    private void log(String msg) {
	    Log.d(TAG, msg);
	}
	    
    private Bitmap getBitmapFor(int resId) {
        return BitmapFactory.decodeResource(getContext().getResources(), resId);
    }
    private void reset(){
    	
    	setGrabbedState(OnSenseLikeSelectorTriggerListener.ICON_GRABBED_STATE_NONE);
    	mIsTouchInCircle = false;
    	mUsingShortcuts = false;
    	mTriggering = false;
    	this.mLockX = 0;
    	this.mLockY = 0;
    	
    	
    }
    
    /**
     * If the implementor wishes that the grab state be
     * changed from movement that come from the outside
     * of the lock ring. Note, this still requires that the user move
     * the lock icon out of the ring. The default behavior is to not 
     * be sensitive to these kind of input and disregard the input.
     * 
     * 
     * @param SlideAll Initiate lock movement if touch is received from outside the area and moved in.
     */
    public void setSlide(boolean SlideAll){
    	
    	mSlideisSensitive = SlideAll;
    	
    	
    }
    
    
    
}
