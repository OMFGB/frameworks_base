
package com.android.systemui.statusbar;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.BatteryManager;
import android.os.Handler;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.CharacterStyle;
import android.text.style.RelativeSizeSpan;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

public class BatteryText extends TextView {
    private int batteryLevel;

    private int batteryStatus;

    private boolean mAttached;

    private Context mContext;

    private String appendText = "% ";

    private static final int STYLE_SHOW = 1;

    private static final int STYLE_DISABLE = 2;

    private static final int STYLE_SMALL_PERCENT = 3;
    
    private static final int STYLE_NO_PERCENT = 4;

    private int style;

    private Handler mHandler;

    public BatteryText(Context context) {
        super(context);
        mContext = context;

    }

    public BatteryText(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
        mContext = context;
    }

    public BatteryText(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mHandler = new Handler();
        mContext = context;
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

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();

            resolver.registerContentObserver(
                    Settings.System
                            .getUriFor(Settings.System.BATTERY_COLOR_ENABLE_AUTOCOLOR),
                    false, this);
            resolver.registerContentObserver(
                    Settings.System
                            .getUriFor(Settings.System.BATTERY_COLOR_AUTO_CHARGING),
                    false, this);
            resolver.registerContentObserver(
                    Settings.System
                            .getUriFor(Settings.System.BATTERY_COLOR_AUTO_LOW),
                    false, this);
            resolver.registerContentObserver(
                    Settings.System
                            .getUriFor(Settings.System.BATTERY_COLOR_AUTO_MEDIUM),
                    false, this);
            resolver.registerContentObserver(
                    Settings.System
                            .getUriFor(Settings.System.BATTERY_COLOR_AUTO_REGULAR),
                    false, this);
            resolver.registerContentObserver(
                    Settings.System
                            .getUriFor(Settings.System.BATTERY_COLOR_STATIC),
                    false, this);
            resolver.registerContentObserver(
                    Settings.System
                            .getUriFor(Settings.System.STATUSBAR_BATTERY_TEXT_STYLE),
                    false, this);

        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
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

                style = Settings.System.getInt(getContext().getContentResolver(),
                        Settings.System.STATUS_BAR_CM_BATTERY, STYLE_DISABLE);

                batteryStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                        BatteryManager.BATTERY_STATUS_UNKNOWN);

                batteryLevel = intent.getIntExtra("level", 50);

                updateSettings();
                
            }
        }
    };

    final void updateBatteryColor() {

        boolean autoColorBatteryText = Settings.System.getInt(getContext().getContentResolver(),
                Settings.System.BATTERY_COLOR_ENABLE_AUTOCOLOR, 1) == 1 ? true : false;

        int color_auto_charging = Settings.System.getInt(getContext().getContentResolver(),
                Settings.System.BATTERY_COLOR_AUTO_CHARGING, 0xFF93D500);

        int color_auto_regular = Settings.System.getInt(getContext().getContentResolver(),
                Settings.System.BATTERY_COLOR_AUTO_REGULAR, 0xFFFFFFFF);

        int color_auto_medium = Settings.System.getInt(getContext().getContentResolver(),
                Settings.System.BATTERY_COLOR_AUTO_MEDIUM, 0xFFD5A300);

        int color_auto_low = Settings.System.getInt(getContext().getContentResolver(),
                Settings.System.BATTERY_COLOR_AUTO_LOW, 0xFFD54B00);

        int color_regular = Settings.System.getInt(getContext().getContentResolver(),
                Settings.System.BATTERY_COLOR_AUTO_REGULAR, 0xFFFFFFFF);

        if (autoColorBatteryText) {
            if (batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING
                    || batteryStatus == BatteryManager.BATTERY_STATUS_FULL) {
                setTextColor(color_auto_charging);

            } else {
                if (batteryLevel < 15) {
                    setTextColor(color_auto_low);
                } else if (batteryLevel < 40) {
                    setTextColor(color_auto_medium);
                } else {
                    setTextColor(color_auto_regular);
                }

            }
        } else {
            setTextColor(color_regular);
        }
    }

    private void updateSettings() {
        style = Settings.System.getInt(getContext().getContentResolver(),
                Settings.System.STATUSBAR_BATTERY_TEXT_STYLE,
                STYLE_DISABLE);

        updateBatteryColor();
        updateBatteryText();

    }

    final void updateBatteryText() {
        style = Settings.System.getInt(getContext().getContentResolver(),
                Settings.System.STATUSBAR_BATTERY_TEXT_STYLE,
                STYLE_DISABLE);

        if (style == STYLE_SHOW) {
            this.setVisibility(View.VISIBLE);

            String result = Integer.toString(batteryLevel) + appendText;

            setText(result);
        } else if (style == STYLE_SMALL_PERCENT) {
            this.setVisibility(View.VISIBLE);

            String result = Integer.toString(batteryLevel) + "% ";

            SpannableStringBuilder formatted = new SpannableStringBuilder(result);
            int start = result.indexOf("%");

            CharacterStyle style = new RelativeSizeSpan(0.7f);
            formatted.setSpan(style, start, start + 1, Spannable.SPAN_EXCLUSIVE_INCLUSIVE);

            setText(formatted);
        } else if (style == STYLE_NO_PERCENT) {
            this.setVisibility(View.VISIBLE);

            String result = Integer.toString(batteryLevel) + " ";

            setText(result);
        } else {
            this.setVisibility(View.GONE);
        }
    }

}