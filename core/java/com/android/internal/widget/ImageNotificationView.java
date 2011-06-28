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
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.View.MeasureSpec;
import android.widget.TextView;

import com.android.internal.R;

public class ImageNotificationView extends View {
	
	
	
// ********************* Debug Variables
	
	
	private final String TAG = "ImageNotificationView";
	private static final boolean DBG = false;
	private static final boolean IDBG = true;
	private static final boolean TDBG = false;
    private static final boolean VISUAL_DEBUG = true;
	
	 int mOrientation;

	private final int HORIZONTAL = 1;
	private final int VERTICAL = 1;

	 // ********************* UI Elements
	 
	private final Matrix mBgMatrix = new Matrix();
	private Paint mPaint = new Paint();
	private Drawable mImage;
	private String  mNotification;
	private int mGrabbedState;
	
	private int textSize;
	
	
	public ImageNotificationView(Context context) {
		this(context,null);
		// TODO Auto-generated constructor stub
	}
	public ImageNotificationView(Context context, AttributeSet attrs) {
		super(context,attrs);
		
		   TypedArray a =
	            context.obtainStyledAttributes(attrs, R.styleable.ImageNotification);
		   // TODO obtain proper orientaion
		   
		    mOrientation = a.getInt(R.styleable.ImageNotification_orientation, VERTICAL);
	        mImage = a.getDrawable(R.styleable.ImageNotification_notificationimage);
	        textSize = 25;//a.getInt(R.styleable.ImageNotification_textSize, 25);
	        mNotification = "1";
		 
	        a.recycle();
	        
	}
	
	@Override 
	public void onDraw(Canvas canvas){
		super.onDraw(canvas);
		

        final int width = getWidth();
        final int height = getHeight();

		if (DBG) {
			log("Debugging the widget visibly");
			mPaint.setColor(0xffff0000);
        	mPaint.setStyle(Paint.Style.STROKE);
            canvas.drawRect(0, 0, width-1, height-1 , mPaint);
        }
        
        int imageWidth  = mImage.getIntrinsicWidth();
        int imageHeight = mImage.getIntrinsicHeight();
        int notificationlength = mNotification.length();
        int Viewwidth =  imageWidth + notificationlength + textSize;
        int Viewheigth = imageHeight ;
        
        
        
        mImage.setBounds(5, 10, mImage.getIntrinsicWidth() + 10, Viewheigth + 10 );
        mImage.draw(canvas);

		 
		 

		mPaint.setColor(Color.WHITE);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setTextSize(textSize); 
        canvas.drawText(mNotification, (Viewwidth - (textSize/3)), (Viewheigth+(textSize/2)), mPaint); 
        
        
        
        
		
	}
	
	@Override 
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    	   
		  if (IDBG) log("Measuring the demensions of the view");
    	   
	
		 
		  final int length = (mImage.getIntrinsicWidth() + textSize);
		  final int height = mImage.getIntrinsicHeight();
                


	  if (DBG) log("The demensions of the view is length:" + length + " and height: " + height );
	  
           if (isVertical()) {
               setMeasuredDimension(length + textSize , height + textSize);
           } 
           else{
               setMeasuredDimension(height + textSize, length + textSize );
           }
           
       }
	
	
	
	
	 /**
		 *  Sets the notification text. Invalidates the view after every call
		 * 
		 * 
		 * @param s
		 */
		 public void setNotificationCallbacktext(String s){
			 
			 mNotification  = s;
			 invalidate();
			 
		 }
	
		 public void setTextSize(int size){
				
				textSize = size;
			}
	
		 private boolean isVertical() {
		        return (mOrientation == VERTICAL);
		    }
	
	 private void log(String msg) {
		    Log.d(TAG, msg);
		}
		    
	    private Bitmap getBitmapFor(int resId) {
	        return BitmapFactory.decodeResource(getContext().getResources(), resId);
	    }
	

}
