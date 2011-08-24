
package com.android.systemui.statusbar;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Color;
import android.os.Handler;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.CharacterStyle;
import android.text.style.RelativeSizeSpan;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

public class SignalText extends TextView {

    int dBm = 0;

    int ASU = 0;

    private boolean mAttached;

    private static final int STYLE_SHOW = 1;

    private static final int STYLE_DISABLE = 0;

    private static final int STYLE_SMALL_DBM = 2;

    private int style;

    private SignalStrength signal;

    private Handler mHandler;

    public SignalText(Context context) {
        super(context);

        mHandler = new Handler();
        SettingsObserver settingsObserver = new SettingsObserver(mHandler);
        settingsObserver.observe();
        updateSettings();

    }

    public SignalText(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SignalText(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        ((TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE)).listen(
                mPhoneStateListener, PhoneStateListener.LISTEN_SERVICE_STATE
                        | PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);

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
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
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
                    Settings.System.getUriFor(Settings.System.STATUS_BAR_SIGNAL_TEXT_STYLE), false,
                    this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.STATUS_BAR_SIGNAL_TEXT_0_BARS),
                    false, this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.STATUS_BAR_SIGNAL_TEXT_1_BARS),
                    false, this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.STATUS_BAR_SIGNAL_TEXT_2_BARS),
                    false, this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.STATUS_BAR_SIGNAL_TEXT_3_BARS),
                    false, this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.STATUS_BAR_SIGNAL_TEXT_4_BARS),
                    false, this);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.STATUS_BAR_SIGNAL_TEXT_ENABLE_AUTOCOLOR), false,
                    this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.STATUS_BAR_SIGNAL_TEXT_STATIC),
                    false, this);
        }

        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    final void updateSignalColor() {

        boolean autoColor = Settings.System.getInt(getContext().getContentResolver(),
                Settings.System.STATUS_BAR_SIGNAL_TEXT_ENABLE_AUTOCOLOR, 1) == 1 ? true : false;

        int color_regular = Settings.System.getInt(getContext().getContentResolver(),
                Settings.System.STATUS_BAR_SIGNAL_TEXT_STATIC, Color.WHITE);

        int color_0 = Settings.System.getInt(getContext().getContentResolver(),
                Settings.System.STATUS_BAR_SIGNAL_TEXT_0_BARS, Color.WHITE);

        int color_1 = Settings.System.getInt(getContext().getContentResolver(),
                Settings.System.STATUS_BAR_SIGNAL_TEXT_1_BARS, Color.WHITE);

        int color_2 = Settings.System.getInt(getContext().getContentResolver(),
                Settings.System.STATUS_BAR_SIGNAL_TEXT_2_BARS, Color.WHITE);

        int color_3 = Settings.System.getInt(getContext().getContentResolver(),
                Settings.System.STATUS_BAR_SIGNAL_TEXT_3_BARS, Color.WHITE);

        int color_4 = Settings.System.getInt(getContext().getContentResolver(),
                Settings.System.STATUS_BAR_SIGNAL_TEXT_4_BARS, Color.WHITE);

        if (autoColor) {
            if (ASU <= 2 || ASU == 99)
                setTextColor(color_0);
            else if (ASU >= 12)
                setTextColor(color_4);
            else if (ASU >= 8)
                setTextColor(color_3);
            else if (ASU >= 5)
                setTextColor(color_2);
            else
                setTextColor(color_1);
        } else {
            setTextColor(color_regular);
        }
    }

    public void updateSettings() {
        updateSignalColor();
        updateSignalText();

    }

    final void updateSignalText() {
        style = Settings.System.getInt(getContext().getContentResolver(),
                Settings.System.STATUS_BAR_SIGNAL_TEXT_STYLE, STYLE_DISABLE);

        if (style == STYLE_SHOW) {
            this.setVisibility(View.VISIBLE);

            String result = Integer.toString(dBm);

            setText(result + " ");
        } else if (style == STYLE_SMALL_DBM) {
            this.setVisibility(View.VISIBLE);

            String result = Integer.toString(dBm) + " dBm ";

            SpannableStringBuilder formatted = new SpannableStringBuilder(result);
            int start = result.indexOf("d");

            CharacterStyle style = new RelativeSizeSpan(0.7f);
            formatted.setSpan(style, start, start + 3, Spannable.SPAN_EXCLUSIVE_INCLUSIVE);

            setText(formatted);
        } else {
            this.setVisibility(View.GONE);
        }
    }

    /* Start the PhoneState listener */
    private PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            signal = signalStrength;

            if (signal != null) {
                ASU = signal.getGsmSignalStrength();
            }
            dBm = -113 + (2 * ASU);

            // update text every time the signal changes
            updateSettings();

        }

    };

}
