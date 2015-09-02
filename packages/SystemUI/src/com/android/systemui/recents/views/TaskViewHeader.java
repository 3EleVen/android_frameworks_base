/*
 * Copyright (C) 2014 The Android Open Source Project
 * Copyright (C) 2015 The MoKee OpenSource Project
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

package com.android.systemui.recents.views;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.recents.AlternateRecentsComponent;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.Task;
import com.android.systemui.utils.LockAppUtils;

/* The task bar view */
public class TaskViewHeader extends FrameLayout {

    RecentsConfiguration mConfig;

    // Header views
    ImageView mDismissButton;
    ImageView mLockAppButton;
    ImageView mApplicationIcon;
    TextView mActivityDescription;

    Context ct;
    LockAppUtils lockAppUtils;
    Task task;

    // Header drawables
    boolean mCurrentPrimaryColorIsDark;
    int mCurrentPrimaryColor;
    int mBackgroundColor;
    Drawable mLightDismissDrawable;
    Drawable mDarkDismissDrawable;
    RippleDrawable mBackground;
    GradientDrawable mBackgroundColorDrawable;
    AnimatorSet mFocusAnimator;
    String mDismissContentDescription;

    // Static highlight that we draw at the top of each view
    static Paint sHighlightPaint;

    // Header dim, which is only used when task view hardware layers are not used
    Paint mDimLayerPaint = new Paint();
    PorterDuffColorFilter mDimColorFilter = new PorterDuffColorFilter(0, PorterDuff.Mode.SRC_ATOP);

    public TaskViewHeader(Context context) {
        this(context, null);
    }

    public TaskViewHeader(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TaskViewHeader(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public TaskViewHeader(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mConfig = RecentsConfiguration.getInstance();
        setWillNotDraw(false);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRect(0, 0, getMeasuredWidth(), getMeasuredHeight());
            }
        });

        this.ct = context;
        lockAppUtils = new LockAppUtils(context);
        // Load the dismiss resources
        Resources res = context.getResources();
        mLightDismissDrawable = res.getDrawable(R.drawable.recents_dismiss_light);
        mDarkDismissDrawable = res.getDrawable(R.drawable.recents_dismiss_dark);
        mDismissContentDescription =
                res.getString(R.string.accessibility_recents_item_will_be_dismissed);

        // Configure the highlight paint
        if (sHighlightPaint == null) {
            sHighlightPaint = new Paint();
            sHighlightPaint.setStyle(Paint.Style.STROKE);
            sHighlightPaint.setStrokeWidth(mConfig.taskViewHighlightPx);
            sHighlightPaint.setColor(mConfig.taskBarViewHighlightColor);
            sHighlightPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
            sHighlightPaint.setAntiAlias(true);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // We ignore taps on the task bar except on the filter and dismiss buttons
        if (!Constants.DebugFlags.App.EnableTaskBarTouchEvents) return true;

        return super.onTouchEvent(event);
    }

    @Override
    protected void onFinishInflate() {
        // Initialize the icon and description views
        mApplicationIcon = (ImageView) findViewById(R.id.application_icon);
        mActivityDescription = (TextView) findViewById(R.id.activity_description);
        mDismissButton = (ImageView) findViewById(R.id.dismiss_task);
        mLockAppButton = (ImageView) findViewById(R.id.set_lock_app);

        // Hide the backgrounds if they are ripple drawables
        if (!Constants.DebugFlags.App.EnableTaskFiltering) {
            if (mApplicationIcon.getBackground() instanceof RippleDrawable) {
                mApplicationIcon.setBackground(null);
            }
        }

        mBackgroundColorDrawable = (GradientDrawable) getContext().getDrawable(R.drawable
                .recents_task_view_header_bg_color);
        // Copy the ripple drawable since we are going to be manipulating it
        mBackground = (RippleDrawable)
                getContext().getDrawable(R.drawable.recents_task_view_header_bg);
        mBackground = (RippleDrawable) mBackground.mutate().getConstantState().newDrawable();
        mBackground.setColor(ColorStateList.valueOf(0));
        mBackground.setDrawableByLayerId(mBackground.getId(0), mBackgroundColorDrawable);
        setBackground(mBackground);
    }

    private void refreshBackground(boolean is_color_light, boolean iswhite) {
        mLockAppButton.setImageDrawable(ct.getDrawable((iswhite ? (is_color_light ? R.drawable.ic_lock_light : R.drawable.ic_lock_dark) 
            : (is_color_light ? R.drawable.ic_lock_open_light : R.drawable.ic_lock_open_dark))));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Draw the highlight at the top edge (but put the bottom edge just out of view)
        float offset = (float) Math.ceil(mConfig.taskViewHighlightPx / 2f);
        float radius = mConfig.taskViewRoundedCornerRadiusPx;
        int count = canvas.save(Canvas.CLIP_SAVE_FLAG);
        canvas.clipRect(0, 0, getMeasuredWidth(), getMeasuredHeight());
        canvas.drawRoundRect(-offset, 0f, (float) getMeasuredWidth() + offset,
                getMeasuredHeight() + radius, radius, radius, sHighlightPaint);
        canvas.restoreToCount(count);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /**
     * Sets the dim alpha, only used when we are not using hardware layers.
     * (see RecentsConfiguration.useHardwareLayers)
     */
    void setDimAlpha(int alpha) {
        mDimColorFilter.setColor(Color.argb(alpha, 0, 0, 0));
        mDimLayerPaint.setColorFilter(mDimColorFilter);
        setLayerType(LAYER_TYPE_HARDWARE, mDimLayerPaint);
    }

    /** Returns the secondary color for a primary color. */
    int getSecondaryColor(int primaryColor, boolean useLightOverlayColor) {
        int overlayColor = useLightOverlayColor ? Color.WHITE : Color.BLACK;
        return Utilities.getColorWithOverlay(primaryColor, overlayColor, 0.8f);
    }

    /** Binds the bar view to the task */
    public void rebindToTask(final Task t) {
        // If an activity icon is defined, then we use that as the primary icon to show in the bar,
        // otherwise, we fall back to the application icon

        lockAppUtils.refreshLockAppMap();
        t.isLockedApp = lockAppUtils.isLockedApp(t.pkgName) ;
        if (t.activityIcon != null) {
            mApplicationIcon.setImageDrawable(t.activityIcon);
        } else if (t.applicationIcon != null) {
            mApplicationIcon.setImageDrawable(t.applicationIcon);
        }
        mApplicationIcon.setContentDescription(t.activityLabel);
        if (!mActivityDescription.getText().toString().equals(t.activityLabel)) {
            mActivityDescription.setText(t.activityLabel);
        }
        task = t;
        refreshBackground(t.useLightOnPrimaryColor,t.isLockedApp);
        mLockAppButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                lockAppUtils.refreshLockAppMap();
                if (t.isLockedApp) {
                    lockAppUtils.removeApp(t.pkgName);
                    mDismissButton.setVisibility(View.VISIBLE);
                } else {
                    lockAppUtils.addApp(t.pkgName);
                    mDismissButton.setVisibility(View.GONE);
                }
                t.isLockedApp = !t.isLockedApp;
                refreshBackground(t.useLightOnPrimaryColor,t.isLockedApp);
                Intent intent = AlternateRecentsComponent.createLocalBroadcastIntent(mContext, AlternateRecentsComponent.ACTION_FLOATING_BUTTON_REFRESH);
                mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
            }
        });
        // Try and apply the system ui tint
        int existingBgColor = (getBackground() instanceof ColorDrawable) ?
                ((ColorDrawable) getBackground()).getColor() : 0;
        if (existingBgColor != t.colorPrimary) {
            mBackgroundColorDrawable.setColor(t.colorPrimary);
            mBackgroundColor = t.colorPrimary;
        }
        mCurrentPrimaryColor = t.colorPrimary;
        mCurrentPrimaryColorIsDark = t.useLightOnPrimaryColor;
        mActivityDescription.setTextColor(t.useLightOnPrimaryColor ?
                mConfig.taskBarViewLightTextColor : mConfig.taskBarViewDarkTextColor);
        mDismissButton.setImageDrawable(t.useLightOnPrimaryColor ?
                mLightDismissDrawable : mDarkDismissDrawable);
        mDismissButton.setContentDescription(String.format(mDismissContentDescription,
                t.activityLabel));
        if (t.isLockedApp) {
            mDismissButton.setVisibility(View.GONE);
        }
    }

    /** Unbinds the bar view from the task */
    void unbindFromTask() {
        mApplicationIcon.setImageDrawable(null);
    }

    /** Animates this task bar dismiss button when launching a task. */
    void startLaunchTaskDismissAnimation() {
        if (mDismissButton.getVisibility() == View.VISIBLE) {
            mDismissButton.animate().cancel();
            mDismissButton.animate()
                    .alpha(0f)
                    .setStartDelay(0)
                    .setInterpolator(mConfig.fastOutSlowInInterpolator)
                    .setDuration(mConfig.taskViewExitToAppDuration)
                    .withLayer()
                    .start();
        }

        if (mLockAppButton.getVisibility() == View.VISIBLE) {
            mLockAppButton.animate().cancel();
            mLockAppButton.animate()
                    .alpha(0f)
                    .setStartDelay(0)
                    .setInterpolator(mConfig.fastOutSlowInInterpolator)
                    .setDuration(mConfig.taskViewExitToAppDuration)
                    .withLayer()
                    .start();
        }
    }

    /** Animates this task bar if the user does not interact with the stack after a certain time. */
    void startNoUserInteractionAnimation() {
        if (mDismissButton.getVisibility() != View.VISIBLE && !task.isLockedApp) {
            mDismissButton.setVisibility(View.VISIBLE);
            mDismissButton.setAlpha(0f);
            mDismissButton.animate()
                    .alpha(1f)
                    .setStartDelay(0)
                    .setInterpolator(mConfig.fastOutLinearInInterpolator)
                    .setDuration(mConfig.taskViewEnterFromAppDuration)
                    .withLayer()
                    .start();
        }

        if (mLockAppButton.getVisibility() != View.VISIBLE) {
            mLockAppButton.setVisibility(View.VISIBLE);
            mLockAppButton.setAlpha(0f);
            mLockAppButton.animate()
                    .alpha(1f)
                    .setStartDelay(0)
                    .setInterpolator(mConfig.fastOutLinearInInterpolator)
                    .setDuration(mConfig.taskViewEnterFromAppDuration)
                    .withLayer()
                    .start();
        }
    }

    /** Mark this task view that the user does has not interacted with the stack after a certain time. */
    void setNoUserInteractionState() {
        if (mDismissButton.getVisibility() != View.VISIBLE && !task.isLockedApp) {
            mDismissButton.animate().cancel();
            mDismissButton.setVisibility(View.VISIBLE);
            mDismissButton.setAlpha(1f);
        }
        if (mLockAppButton.getVisibility() != View.VISIBLE) {
            mLockAppButton.animate().cancel();
            mLockAppButton.setVisibility(View.VISIBLE);
            mLockAppButton.setAlpha(1f);
        }
    }

    /** Resets the state tracking that the user has not interacted with the stack after a certain time. */
    void resetNoUserInteractionState() {
        if (!task.isLockedApp) {
            mDismissButton.setVisibility(View.INVISIBLE);
        }
        mLockAppButton.setVisibility(View.INVISIBLE);
    }

    @Override
    protected int[] onCreateDrawableState(int extraSpace) {

        // Don't forward our state to the drawable - we do it manually in onTaskViewFocusChanged.
        // This is to prevent layer trashing when the view is pressed.
        return new int[] {};
    }

    /** Notifies the associated TaskView has been focused. */
    void onTaskViewFocusChanged(boolean focused, boolean animateFocusedState) {
        // If we are not animating the visible state, just return
        if (!animateFocusedState) return;

        boolean isRunning = false;
        if (mFocusAnimator != null) {
            isRunning = mFocusAnimator.isRunning();
            Utilities.cancelAnimationWithoutCallbacks(mFocusAnimator);
        }

        if (focused) {
            int secondaryColor = getSecondaryColor(mCurrentPrimaryColor, mCurrentPrimaryColorIsDark);
            int[][] states = new int[][] {
                    new int[] { android.R.attr.state_enabled },
                    new int[] { android.R.attr.state_pressed }
            };
            int[] newStates = new int[]{
                    android.R.attr.state_enabled,
                    android.R.attr.state_pressed
            };
            int[] colors = new int[] {
                    secondaryColor,
                    secondaryColor
            };
            mBackground.setColor(new ColorStateList(states, colors));
            mBackground.setState(newStates);
            // Pulse the background color
            int currentColor = mBackgroundColor;
            int lightPrimaryColor = getSecondaryColor(mCurrentPrimaryColor, mCurrentPrimaryColorIsDark);
            ValueAnimator backgroundColor = ValueAnimator.ofObject(new ArgbEvaluator(),
                    currentColor, lightPrimaryColor);
            backgroundColor.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    mBackground.setState(new int[]{});
                }
            });
            backgroundColor.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    int color = (int) animation.getAnimatedValue();
                    mBackgroundColorDrawable.setColor(color);
                    mBackgroundColor = color;
                }
            });
            backgroundColor.setRepeatCount(ValueAnimator.INFINITE);
            backgroundColor.setRepeatMode(ValueAnimator.REVERSE);
            // Pulse the translation
            ObjectAnimator translation = ObjectAnimator.ofFloat(this, "translationZ", 15f);
            translation.setRepeatCount(ValueAnimator.INFINITE);
            translation.setRepeatMode(ValueAnimator.REVERSE);

            mFocusAnimator = new AnimatorSet();
            mFocusAnimator.playTogether(backgroundColor, translation);
            mFocusAnimator.setStartDelay(750);
            mFocusAnimator.setDuration(750);
            mFocusAnimator.start();
        } else {
            if (isRunning) {
                // Restore the background color
                int currentColor = mBackgroundColor;
                ValueAnimator backgroundColor = ValueAnimator.ofObject(new ArgbEvaluator(),
                        currentColor, mCurrentPrimaryColor);
                backgroundColor.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        int color = (int) animation.getAnimatedValue();
                        mBackgroundColorDrawable.setColor(color);
                        mBackgroundColor = color;
                    }
                });
                // Restore the translation
                ObjectAnimator translation = ObjectAnimator.ofFloat(this, "translationZ", 0f);

                mFocusAnimator = new AnimatorSet();
                mFocusAnimator.playTogether(backgroundColor, translation);
                mFocusAnimator.setDuration(150);
                mFocusAnimator.start();
            } else {
                mBackground.setState(new int[] {});
                setTranslationZ(0f);
            }
        }
    }
}
