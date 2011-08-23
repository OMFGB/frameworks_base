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
import android.content.ContentUris;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
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

    private Context mContext;
    private LayoutInflater mInflater;
    private AudioManager mAudioManager;

    private AudioManager am = (AudioManager)getContext().getSystemService(Context.AUDIO_SERVICE);
    private boolean mWasMusicActive = false;
    private boolean mIsMusicActive = am.isMusicActive();

    private ImageButton mPlayIcon;
    private ImageButton mPauseIcon;
    private ImageButton mRewindIcon;
    private ImageButton mForwardIcon;
    private ImageButton mAlbumArt;

    private TextView mNowPlayingArtist;
    private TextView mNowPlayingAlbum;

    private static String mArtist = null;
    private static String mTrack = null;
    private static Boolean mPlaying = null;
    private static long mSongId = 0;
    private static long mAlbumId = 0;

    public MusicControls(Context context, AttributeSet attrs) {
        super(context, attrs);

        mContext = context;
        mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    public void setupControls() {
        Slog.d(TAG, "Setting Up Music Controls");
        LinearLayout ll = new LinearLayout(mContext);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(Gravity.CENTER_HORIZONTAL);

	View controlsView = mInflater.inflate(R.layout.exp_music_controls, null, false);
	ll.addView(controlsView, BUTTON_LAYOUT_PARAMS);
	addView(ll, WIDGET_LAYOUT_PARAMS);
    }

    public void updateControls() {
	Slog.d(TAG, "Updating Music Controls Visibility");

        mPlayIcon = (ImageButton) findViewById(R.id.musicControlPlay);
        mPauseIcon = (ImageButton) findViewById(R.id.musicControlPause);
        mRewindIcon = (ImageButton) findViewById(R.id.musicControlPrevious);
        mForwardIcon = (ImageButton) findViewById(R.id.musicControlNext);
        mAlbumArt = (ImageButton) findViewById(R.id.albumArt);
   	  Uri uri = getArtworkUri(getContext(), SongId(),
	  AlbumId());
	  if (uri != null) {
	      mAlbumArt.setImageURI(uri);
	  }

        mNowPlayingArtist = (TextView) findViewById(R.id.musicNowPlayingArtist);
        mNowPlayingArtist.setSelected(true); // set focus to TextView to allow scrolling
        mNowPlayingArtist.setTextColor(0xffffffff);
            String nowPlayingArtist = NowPlayingArtist();
            mNowPlayingArtist.setText(nowPlayingArtist);

        mNowPlayingAlbum = (TextView) findViewById(R.id.musicNowPlayingAlbum);
        mNowPlayingAlbum.setSelected(true); // set focus to TextView to allow scrolling
        mNowPlayingAlbum.setTextColor(0xffffffff);
            String nowPlayingAlbum = NowPlayingAlbum();
            mNowPlayingAlbum.setText(nowPlayingAlbum);

//        setVisibility(View.VISIBLE);

	if (mIsMusicActive || mWasMusicActive) {
	     Slog.d(TAG, "Music is active");
	     setVisibility(View.VISIBLE);
	} else {
             Slog.d(TAG, "Music is not active");
	     setVisibility(View.GONE);
	}
    }

    public static Uri getArtworkUri(Context context, long song_id, long album_id) {

        if (album_id < 0) {
            // This is something that is not in the database, so get the album art directly
            // from the file.
            if (song_id >= 0) {
                return getArtworkUriFromFile(context, song_id, -1);
            }
            return null;
        }

       ContentResolver res = context.getContentResolver();
        Uri uri = ContentUris.withAppendedId(sArtworkUri, album_id);
        if (uri != null) {
            InputStream in = null;
            try {
                in = res.openInputStream(uri);
                return uri;
            } catch (FileNotFoundException ex) {
                // The album art thumbnail does not actually exist. Maybe the user deleted it, or
                // maybe it never existed to begin with.
                return getArtworkUriFromFile(context, song_id, album_id);
            } finally {
                try {
                    if (in != null) {
                        in.close();
                    }
                } catch (IOException ex) {
                }
            }
        }
        return null;
    }

   private static Uri getArtworkUriFromFile(Context context, long songid, long albumid) {
        if (albumid < 0 && songid < 0) {
            return null;
        }

        try {
            if (albumid < 0) {
                Uri uri = Uri.parse("content://media/external/audio/media/" + songid + "/albumart");
                ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r");
                if (pfd != null) {
                    return uri;
               }
            } else {
                Uri uri = ContentUris.withAppendedId(sArtworkUri, albumid);
                ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r");
                if (pfd != null) {
                    return uri;
                }
            }
        } catch (FileNotFoundException ex) {
        }
        return null;
    }

    private BroadcastReceiver mMusicReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            mArtist = intent.getStringExtra("artist");
            mTrack = intent.getStringExtra("track");
            mPlaying = intent.getBooleanExtra("playing", false);
            mSongId = intent.getLongExtra("songid", 0);
            mAlbumId = intent.getLongExtra("albumid", 0);
	    intent = new Intent("internal.policy.impl.updateSongStatus");
            context.sendBroadcast(intent);
        }
    };

    public static String NowPlayingArtist() {
        if (mArtist != null && mPlaying) {
            return (mArtist);
        } else {
            return "unknown";
        }
    }

    public static String NowPlayingAlbum() {
        if (mArtist != null && mPlaying) {
            return (mTrack);
        } else {
            return "unknown";
        }
    }

    public static long SongId() {
        return mSongId;
    }

    public static long AlbumId() {
        return mAlbumId;
    }

}
