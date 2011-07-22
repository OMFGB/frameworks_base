/*
 * Created by Sven Dawitz; Copyright (C) 2011 CyanogenMod Project
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
 *
 * Credit: birgertime for auto color idea
 *        -sbrissen
 */

package com.android.systemui.statusbar;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;
import android.graphics.*;

/**
 * This widget displays the percentage of the battery as a number
 */
public class BatteryText extends TextView {
    private boolean mAttached;

    // weather to show this battery widget or not
    private boolean mBatteryText;
    private boolean mBatteryAutoColor;
    private int mBatteryTextColor;

    Handler mHandler;

    // tracks changes to settings, so status bar is auto updated the moment the
    // setting is toggled
    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.STATUSBAR_BATTERY_PERCENT), false, this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.BATTERY_TEXT_COLOR), false, this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.BATTERY_COLOR_AUTO_CHARGING), false, this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.BATTERY_COLOR_AUTO_REGULAR), false, this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.BATTERY_COLOR_AUTO_MEDIUM), false, this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.BATTERY_COLOR_AUTO_LOW), false, this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.BATTERY_COLOR), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    public BatteryText(Context context) {
        this(context, null);
    }

    public BatteryText(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryText(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mHandler = new Handler();
        SettingsObserver settingsObserver = new SettingsObserver(mHandler);
        settingsObserver.observe();

        updateSettings();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!mAttached) {
            mAttached = true;
            IntentFilter filter = new IntentFilter();

            filter.addAction(Intent.ACTION_BATTERY_CHANGED);

            getContext().registerReceiver(mIntentReceiver, filter, null, getHandler());
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            getContext().unregisterReceiver(mIntentReceiver);
            mAttached = false;
        }
    }

    /**
     * Handles changes ins battery level and charger connection
     */
    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
		updateBatteryTextColor(intent);
                updateBatteryText(intent);
            }
        }
    };

    /**
     * Sets the output text. Kind of onDraw of canvas based classes
     *
     * @param intent
     */
    final void updateBatteryText(Intent intent) {
        int level = intent.getIntExtra("level", 0);
	String level2 = Integer.toString(level);
	String BattText = level2.concat("%");
        setText(BattText);
    }

    final void updateBatteryTextColor(Intent intent) {
	ContentResolver resolver = mContext.getContentResolver();

	int level = intent.getIntExtra("level", 0);
	boolean plugged = intent.getIntExtra("plugged", 0) != 0;

	if (!mBatteryAutoColor){
	  mBatteryTextColor = Settings.System.getInt(resolver, Settings.System.BATTERY_COLOR, -1);
	}else if (plugged){
	  mBatteryTextColor = Settings.System.getInt(resolver, Settings.System.BATTERY_COLOR_AUTO_CHARGING, -1);
	}else if (level >= 60){
	  mBatteryTextColor = Settings.System.getInt(resolver, Settings.System.BATTERY_COLOR_AUTO_REGULAR, -1);
	}else if (level <= 15){
	  mBatteryTextColor = Settings.System.getInt(resolver, Settings.System.BATTERY_COLOR_AUTO_LOW, -1);
	}else {
	  mBatteryTextColor = Settings.System.getInt(resolver, Settings.System.BATTERY_COLOR_AUTO_MEDIUM, -1);
	}
	
	setTextColor(mBatteryTextColor);  
    }
      

    /**
     * Invoked by SettingsObserver, this method keeps track of just changed
     * settings. Also does the initial call from constructor
     */
    private void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();

        mBatteryAutoColor = (Settings.System
                .getInt(resolver, Settings.System.BATTERY_TEXT_COLOR, 0) == 1);

        mBatteryText = (Settings.System
                .getInt(resolver, Settings.System.STATUSBAR_BATTERY_PERCENT, 0) == 1);

        if (mBatteryText)
            setVisibility(View.VISIBLE);
        else
            setVisibility(View.GONE);
    }
}
