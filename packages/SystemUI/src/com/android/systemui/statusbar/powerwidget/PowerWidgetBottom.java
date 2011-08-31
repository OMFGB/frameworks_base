
package com.android.systemui.statusbar.powerwidget;

import com.android.systemui.statusbar.StatusBarService;

import android.content.Context;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;

public class PowerWidgetBottom extends PowerWidget {

    private Context mContext;

    public PowerWidgetBottom(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;

    }

    @Override
    public void updateVisibility() {
        // now check if we need to display the widget still
        boolean displayPowerWidget = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.EXPANDED_VIEW_WIDGET, 1) == 2;
        if (!displayPowerWidget) {
            this.setVisibility(View.GONE);
            StatusBarService.mTogglesNotVisibleButton.setVisibility(View.VISIBLE);
            StatusBarService.mTogglesVisibleButton.setVisibility(View.GONE);
        } else {

            this.setVisibility(View.VISIBLE);
            StatusBarService.mTogglesNotVisibleButton.setVisibility(View.GONE);
            StatusBarService.mTogglesVisibleButton.setVisibility(View.VISIBLE);
        }
    }

}
