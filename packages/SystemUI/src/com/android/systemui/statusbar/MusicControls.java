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

import com.android.systemui.statusbar.StatusBarService;

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
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.KeyEvent;
import android.widget.*;
import android.view.ViewGroup;
import android.media.AudioManager;

import com.android.systemui.R;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;

import com.google.android.collect.Lists;

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
                                        2.0f                                    // weight = 1
                                        );

    private static final Uri sArtworkUri = Uri.parse("content://media/external/audio/albumart");

    private ArrayList<InfoCallback> mInfoCallbacks = Lists.newArrayList();

    private Context mContext;
    private LayoutInflater mInflater;
    private AudioManager mAudioManager;

    private StatusBarService mSBService;
    private AudioManager am = (AudioManager)getContext().getSystemService(Context.AUDIO_SERVICE);
    private boolean mIsMusicActive = am.isMusicActive();
    private boolean paused = false;

    private ImageButton mPlayPauseIcon;
    private ImageButton mRewindIcon;
    private ImageButton mForwardIcon;
    private ImageButton mAlbumArt;

    private TextView mNowPlayingInfo;

    private static String mArtist = null;
    private static String mTrack = null;
    private static Boolean mPlaying = null;
    private static long mSongId = 0;
    private static long mAlbumId = 0;

    public MusicControls(Context context, AttributeSet attrs) {
        super(context, attrs);

        mContext = context;
        mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        IntentFilter iF = new IntentFilter();
        iF.addAction("com.android.music.playstatechanged");
        iF.addAction("com.android.music.metachanged");
        mContext.registerReceiver(mMusicReceiver, iF);
    }

    public void setupControls() {
        Slog.d(TAG, "Setting Up Music Controls");
        LinearLayout ll = new LinearLayout(mContext);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(Gravity.CENTER_HORIZONTAL);

	View controlsView = mInflater.inflate(R.layout.exp_music_controls, null, false);
	ll.addView(controlsView, BUTTON_LAYOUT_PARAMS);
	addView(ll, WIDGET_LAYOUT_PARAMS);

	updateControls();
    }

    public void updateControls() {
        Slog.d(TAG, "Updating Music Controls Visibility");
        mIsMusicActive = am.isMusicActive();

        mPlayPauseIcon = (ImageButton) findViewById(R.id.musicControlPlayPause);
        mPlayPauseIcon.setImageResource(R.drawable.stat_media_pause);
        mRewindIcon = (ImageButton) findViewById(R.id.musicControlPrevious);
        mForwardIcon = (ImageButton) findViewById(R.id.musicControlNext);
        mNowPlayingInfo = (TextView) findViewById(R.id.musicNowPlayingInfo);
        mNowPlayingInfo.setSelected(true); // set focus to TextView to allow scrolling
        mNowPlayingInfo.setTextColor(0xffffffff);
        mAlbumArt = (ImageButton) findViewById(R.id.albumArt);

        if (mIsMusicActive) {
             Slog.d(TAG, "Music is active");
	     updateInfo();
	     mSBService.mMusicToggleButton.setVisibility(View.VISIBLE);
             setVisibility(View.VISIBLE);
        } else {
             Slog.d(TAG, "Music is not active");
             mSBService.mMusicToggleButton.setVisibility(View.GONE);
             setVisibility(View.GONE);
        }
   }

    public void visibilityToggled() {
	if (this.getVisibility() == View.VISIBLE) {
	    setVisibility(View.GONE);
	} else {
            setVisibility(View.VISIBLE);
	}
    }

    public void updateInfo() {
	Slog.d(TAG, "Updating Music Controls Info");

        // Set album art
        Uri uri = getArtworkUri(getContext(), SongId(), AlbumId());
        if (uri != null) {
           mAlbumArt.setImageURI(uri);
        } else {
           mAlbumArt.setImageResource(R.drawable.default_artwork);
        }

	String nowPlayingArtist = NowPlayingArtist();
	String nowPlayingAlbum = NowPlayingAlbum();
        if (nowPlayingArtist.equals("PAUSED")) {
	   mNowPlayingInfo.setText("PAUSED");
	   mPlayPauseIcon.setImageResource(R.drawable.stat_media_play);
	} else {
  	   mNowPlayingInfo.setText(nowPlayingArtist + " -- " + nowPlayingAlbum);
           mPlayPauseIcon.setImageResource(R.drawable.stat_media_pause);
	}

        mPlayPauseIcon.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
               sendMediaButtonEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            }
        });

        mRewindIcon.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                sendMediaButtonEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            }
        });

        mForwardIcon.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                sendMediaButtonEvent(KeyEvent.KEYCODE_MEDIA_NEXT);
            }
        });
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
	    Slog.d(TAG, "RECEIVED!");
            String action = intent.getAction();
            mArtist = intent.getStringExtra("artist");
            mTrack = intent.getStringExtra("track");
            mPlaying = intent.getBooleanExtra("playing", false);
            mSongId = intent.getLongExtra("songid", 0);
            mAlbumId = intent.getLongExtra("albumid", 0);
	    handleSongUpdate();
            updateInfo();
        }
    };

    public static String NowPlayingArtist() {
        if (mArtist != null && mPlaying) {
            return (mArtist);
        } else if (mArtist != null && !mPlaying) {
	    return "PAUSED";
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

    private void handleSongUpdate() {
           for (int i = 0; i< mInfoCallbacks.size(); i++) {
               mInfoCallbacks.get(i).onMusicChanged();
           }
    }

    interface InfoCallback {
	void onMusicChanged();
    }

    /** {@inheritDoc} */
    public void onMusicChanged() {
        updateInfo();
    }

    private void sendMediaButtonEvent(int code) {
        long eventtime = SystemClock.uptimeMillis();

        Intent downIntent = new Intent(Intent.ACTION_MEDIA_BUTTON, null);
        KeyEvent downEvent = new KeyEvent(eventtime, eventtime, KeyEvent.ACTION_DOWN, code, 0);
        downIntent.putExtra(Intent.EXTRA_KEY_EVENT, downEvent);
        getContext().sendOrderedBroadcast(downIntent, null);

        Intent upIntent = new Intent(Intent.ACTION_MEDIA_BUTTON, null);
        KeyEvent upEvent = new KeyEvent(eventtime, eventtime, KeyEvent.ACTION_UP, code, 0);
        upIntent.putExtra(Intent.EXTRA_KEY_EVENT, upEvent);
        getContext().sendOrderedBroadcast(upIntent, null);
    }
}
