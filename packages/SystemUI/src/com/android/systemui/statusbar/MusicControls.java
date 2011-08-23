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
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.*;
import android.view.ViewGroup;
import android.media.AudioManager;

import com.android.systemui.R;

import java.util.ArrayList;
import java.util.HashMap;

import android.util.Slog;

public class MusicControls extends FrameLayout {
    private static final String TAG = "MusicControls";

    private static final FrameLayout.LayoutParams WIDGET_LAYOUT_PARAMS = new FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT, // width = match_parent
                                        ViewGroup.LayoutParams.WRAP_CONTENT  // height = wrap_content
                                        );

    private static final LinearLayout.LayoutParams BUTTON_LAYOUT_PARAMS = new LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT, // width = wrap_content
                                        ViewGroup.LayoutParams.MATCH_PARENT, // height = match_parent
                                        1.0f                                    // weight = 1
                                        );

    private static final Uri sArtworkUri = Uri.parse("content://media/external/audio/albumart");

    private AudioManager am = (AudioManager)getContext().getSystemService(Context.AUDIO_SERVICE);
    private boolean mWasMusicActive = false;
    private boolean mIsMusicActive = am.isMusicActive();

    private Context mContext;
    private LayoutInflater mInflater;
    private AudioManager mAudioManager;

    private ImageButton mPlayIcon;
    private ImageButton mPauseIcon;
    private ImageButton mRewindIcon;
    private ImageButton mForwardIcon;
    private ImageButton mAlbumArt;

    private TextView mNowPlayingArtist;
    private TextView mNowPlayingAlbum;

    public MusicControls(Context context, AttributeSet attrs) {
        super(context, attrs);

        mContext = context;
        mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
	setupControls();
    }

    public void setupControls() {
        LinearLayout ll = new LinearLayout(mContext);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(Gravity.CENTER_HORIZONTAL);

	View controlsView = mInflater.inflate(R.layout.exp_music_controls, null, false);
	addView(ll);
    }

    public void updateVisibility() {
	Slog.d(TAG, "Updating Music Controls Visibility");

        mPlayIcon = (ImageButton) findViewById(R.id.musicControlPlay);
        mPauseIcon = (ImageButton) findViewById(R.id.musicControlPause);
        mRewindIcon = (ImageButton) findViewById(R.id.musicControlPrevious);
        mForwardIcon = (ImageButton) findViewById(R.id.musicControlNext);
        mAlbumArt = (ImageButton) findViewById(R.id.albumArt);
        
        mNowPlayingArtist = (TextView) findViewById(R.id.musicNowPlayingArtist);
        mNowPlayingArtist.setSelected(true); // set focus to TextView to allow scrolling
        mNowPlayingArtist.setTextColor(0xffffffff);

        mNowPlayingAlbum = (TextView) findViewById(R.id.musicNowPlayingAlbum);
        mNowPlayingAlbum.setSelected(true); // set focus to TextView to allow scrolling
        mNowPlayingAlbum.setTextColor(0xffffffff);

	if (mIsMusicActive) {
	     setVisibility(VISIBLE);
	} else {
	     setVisibility(GONE);
	}
    }
}
