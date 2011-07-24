/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.AttributeSet;
import android.util.Slog;
import android.widget.TextView;
import android.view.MotionEvent;
import android.text.format.*;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;

import java.text.DateFormat;
import java.util.Date;

public final class DateView extends TextView {
    private static final String TAG = "DateView";

    private boolean mUpdating = false;
    private int mStatusbarClock;
    private String mDateClock;

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_TIME_TICK)
                    || action.equals(Intent.ACTION_TIMEZONE_CHANGED)) {
                updateClock();
            }
        }
    };

    public DateView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        setUpdates(false);
    }

    @Override
    protected int getSuggestedMinimumWidth() {
        // makes the large background bitmap not force us to full width
        return 0;
    }

    private final void updateClock() {
	ContentResolver resolver = mContext.getContentResolver();

        Date now = new Date();
	DateFormat dateFormat = android.text.format.DateFormat.getDateFormat(getContext());
	DateFormat timeFormat = android.text.format.DateFormat.getTimeFormat(getContext());
	  
       mStatusbarClock = Settings.System.getInt(resolver, Settings.System.STATUSBAR_DATECLOCK, 1);

      if(mStatusbarClock == 0){
        setText("");
      } else if (mStatusbarClock == 2) {
	setText(timeFormat.format(now));
      } else if (mStatusbarClock == 3){
	setText(dateFormat.format(now).concat(" ").concat(timeFormat.format(now)));
      } else {
	setText(dateFormat.format(now));
      }
    }

    void setUpdates(boolean update) {
        if (update != mUpdating) {
            mUpdating = update;
            if (update) {
                // Register for Intent broadcasts for the clock and battery
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_TIME_TICK);
                filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
                mContext.registerReceiver(mIntentReceiver, filter, null, null);
                updateClock();
            } else {
                mContext.unregisterReceiver(mIntentReceiver);
            }
        }
    }
}

