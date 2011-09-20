package com.android.internal.policy.impl;

import com.android.internal.policy.impl.LockScreen;

import com.android.internal.R;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.SystemClock;
import android.os.Vibrator;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.TranslateAnimation;
import android.view.animation.Animation.AnimationListener;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MusicControlsPanel extends LinearLayout {

	// =========================================
	// Private members
	// =========================================
        private final Context mContext;

	private static final String TAG = "Music Controls Panel";
	private Boolean isOpen;
	private Boolean animationRunning;
	private FrameLayout contentPlaceHolder;
	private ImageButton toggleButton;
	private int animationDuration;

	private LockScreen mLockscreen;
        private AudioManager am = (AudioManager)getContext().getSystemService(Context.AUDIO_SERVICE);
        private boolean mIsMusicActive = am.isMusicActive();

        private AudioManager mAudioManager;

	// =========================================
	// Constructors
	// =========================================

	public MusicControlsPanel(Context context, Boolean isOpen) {
		super(context);
	        mContext = context;
		this.isOpen = isOpen;
		Init(null);
	}

	public MusicControlsPanel(Context context, AttributeSet attrs) {
		super(context, attrs);
	        mContext = context;
		// to prevent from crashing the designer
		try {
			Init(attrs);
		} catch (Exception ex) {
		//Catch stub
		}
	}
	// =========================================
	// Initialization
	// =========================================

	private void Init(AttributeSet attrs) {
		setDefaultValues(attrs);

		createHandleToggleButton();

		// create the handle container
		FrameLayout handleContainer = new FrameLayout(getContext());
		if (mIsMusicActive) {
		    handleContainer.addView(toggleButton);
		}

		// create and populate the panel's container, and inflate it
		contentPlaceHolder = new FrameLayout(getContext());
		LayoutInflater li = (LayoutInflater) getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		    li.inflate(R.layout.keyguard_screen_music_controls, contentPlaceHolder, true);
			handleContainer.setLayoutParams(new LayoutParams(
					android.view.ViewGroup.LayoutParams.FILL_PARENT,
					android.view.ViewGroup.LayoutParams.FILL_PARENT, 1));
			contentPlaceHolder.setLayoutParams(new LayoutParams(
					android.view.ViewGroup.LayoutParams.FILL_PARENT,
					android.view.ViewGroup.LayoutParams.FILL_PARENT, 1));
			this.addView(contentPlaceHolder);
			this.addView(handleContainer);

		if (!isOpen) {
			contentPlaceHolder.setVisibility(GONE);
		}
	}

	private void setDefaultValues(AttributeSet attrs) {
		// set default values
		isOpen = false;
		animationRunning = false;
		animationDuration = 500;
                setOrientation(LinearLayout.VERTICAL);
                setGravity(Gravity.TOP);

	}

	private void createHandleToggleButton() {
		toggleButton = new ImageButton(getContext());
		toggleButton.setPadding(0, 0, 0, 0);
		toggleButton.setLayoutParams(new FrameLayout.LayoutParams(
				android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
				android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
				Gravity.CENTER));
		toggleButton.setBackgroundColor(Color.TRANSPARENT);
		toggleButton.setImageResource(R.drawable.bottom_dock_handle);
		toggleButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
		                Intent intent = new Intent("android.intent.action.POKE_WAKE_LOCK");
				toggle();
                                mContext.sendBroadcast(intent);
			}
		});
	}

	// =========================================
	// Public methods
	// =========================================

	public int getAnimationDuration() {
		return animationDuration;
	}

	public void setAnimationDuration(int milliseconds) {
		animationDuration = milliseconds;
	}

	public Boolean getIsRunning() {
		return animationRunning;
	}

	public void open() {
		if (!animationRunning) {
			Log.d(TAG, "Opening...");
			//Animation animation = createShowAnimation();
			//this.setAnimation(animation);
			//animation.start();
                        contentPlaceHolder.setVisibility(View.VISIBLE);
			isOpen = true;
                        Log.d(TAG, "Opened");
		}
	}

	public void close() {
		if (!animationRunning) {
			Log.d(TAG, "Closing...");
			//Animation animation = createHideAnimation();
			//this.setAnimation(animation);
			//animation.start();
                        contentPlaceHolder.setVisibility(View.GONE);
			isOpen = false;
			Log.d(TAG, "Closed");
		}
	}

	public void toggle() {
                Vibrator vibe = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
                long[] pattern = {
                                0, 30
                };
                vibe.vibrate(pattern, -1);
		if (isOpen) {
			close();
		} else {
			open();
		}
	}
	// =========================================
	// Private methods
	// =========================================

	private Animation createHideAnimation() {
		Animation animation = null;
			animation = new TranslateAnimation(0, 0, 0, -contentPlaceHolder
					.getHeight());
		animation.setDuration(animationDuration);
		animation.setInterpolator(new AccelerateInterpolator());
		animation.setAnimationListener(new AnimationListener() {
			@Override
			public void onAnimationStart(Animation animation) {
				animationRunning = true;
			}

			@Override
			public void onAnimationRepeat(Animation animation) {
			}

			@Override
			public void onAnimationEnd(Animation animation) {
				contentPlaceHolder.setVisibility(View.GONE);
				animationRunning = false;
			}
		});
		return animation;
	}

	private Animation createShowAnimation() {
		Animation animation = null;
			animation = new TranslateAnimation(0, 0, -contentPlaceHolder
					.getHeight(), 0);
		animation.setDuration(animationDuration);
		animation.setInterpolator(new DecelerateInterpolator());
		animation.setAnimationListener(new AnimationListener() {
			@Override
			public void onAnimationStart(Animation animation) {
				animationRunning = true;
				contentPlaceHolder.setVisibility(View.VISIBLE);
			}

			@Override
			public void onAnimationRepeat(Animation animation) {
			}

			@Override
			public void onAnimationEnd(Animation animation) {
				animationRunning = false;
			}
		});
		return animation;
	}
}
