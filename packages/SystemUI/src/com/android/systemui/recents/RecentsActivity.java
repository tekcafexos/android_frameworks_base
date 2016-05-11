/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.SearchManager;
import android.app.WallpaperManager;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewStub;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.recents.misc.Console;
import com.android.systemui.recents.misc.DebugTrigger;
import com.android.systemui.recents.misc.ReferenceCountedTrigger;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.RecentsTaskLoadPlan;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.views.DebugOverlayView;
import com.android.systemui.recents.views.RecentsView;
import com.android.systemui.recents.views.SystemBarScrimViews;
import com.android.systemui.recents.views.ViewAnimation;
import com.android.systemui.recents.views.TaskStackView;
import com.android.systemui.statusbar.BlurUtils;
import com.android.systemui.statusbar.DisplayUtils;
import com.android.systemui.statusbar.phone.NotificationPanelView;
import cyanogenmod.providers.CMSettings;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * The main Recents activity that is started from AlternateRecentsComponent.
 */
public class RecentsActivity extends Activity implements RecentsView.RecentsViewCallbacks,
        RecentsAppWidgetHost.RecentsAppWidgetHostCallbacks,
        DebugOverlayView.DebugOverlayViewCallbacks {

    private static final HashMap<String, Field> fieldCache = new HashMap<String, Field>();
    RecentsConfiguration mConfig;
    long mLastTabKeyEventTime;

    // Top level views
    RecentsView mRecentsView;
    SystemBarScrimViews mScrimViews;
    ViewStub mEmptyViewStub;
    ViewStub mDebugOverlayStub;
    View mEmptyView;
    DebugOverlayView mDebugOverlay;

    //ME
    public static boolean mBlurredRecentAppsEnabled;

    private static int mBlurScale;
    private static int mBlurRadius;
    private static Context mContext;
    private static BlurUtils mBlurUtils;
    private static ColorFilter mColorFilter;
    private static int mBlurDarkColorFilter;
    private static int mBlurMixedColorFilter;
    private static int mBlurLightColorFilter;
    private static RecentsActivity mRecentsActivity;
    private static FrameLayout mRecentsActivityRootView;
    //ME

    // Resize task debug
    RecentsResizeTaskDialog mResizeTaskDebugDialog;

    // Search AppWidget
    AppWidgetProviderInfo mSearchWidgetInfo;
    RecentsAppWidgetHost mAppWidgetHost;
    RecentsAppWidgetHostView mSearchWidgetHostView;

    // Runnables to finish the Recents activity
    FinishRecentsRunnable mFinishLaunchHomeRunnable;

    // Runnable to be executed after we paused ourselves
    Runnable mAfterPauseRunnable;
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


    public static void startBlurTask() {

        // remove o background !
        if (mRecentsActivityRootView != null)
            mRecentsActivityRootView.setBackground(null);

        // habilitado ?
        if (!mBlurredRecentAppsEnabled)
            return;

        // callback
        BlurTask.setBlurTaskCallback(new BlurUtils.BlurTaskCallback() {

            @Override
            public void blurTaskDone(final Bitmap blurredBitmap) {

                if (blurredBitmap != null) {

                    // -------------------------
                    // bitmap criado com sucesso
                    // -------------------------

                    if (mRecentsActivityRootView != null) {

                        mRecentsActivityRootView.post(new Runnable() {

                            @Override
                            public void run() {

                                // cria o drawable com o filtro de cor
                                BitmapDrawable blurredDrawable = new BitmapDrawable(blurredBitmap);
                                blurredDrawable.setColorFilter(mColorFilter);

                                // seta
                                mRecentsActivityRootView.setBackground(blurredDrawable);

                            }
                        });
                    }
                }
            }

            @Override
            public void dominantColor(int color) {

                // obtém a luminosidade da cor dominante
                double lightness = DisplayUtils.getColorLightness(color);

                if (lightness >= 0.0 && color <= 1.0) {

                    // --------------------------------------------------
                    // seta o filtro de cor de acordo com a cor dominante
                    // --------------------------------------------------

                    if (lightness <= 0.33) {

                        // imagem clara (mais perto do branco)
                        mColorFilter = new PorterDuffColorFilter(mBlurLightColorFilter, PorterDuff.Mode.MULTIPLY);

                    } else if (lightness >= 0.34 && lightness <= 0.66) {

                        // imagem mista
                        mColorFilter = new PorterDuffColorFilter(mBlurMixedColorFilter, PorterDuff.Mode.MULTIPLY);

                    } else if (lightness >= 0.67 && lightness <= 1.0) {

                        // imagem clara (mais perto do preto)
                        mColorFilter = new PorterDuffColorFilter(mBlurDarkColorFilter, PorterDuff.Mode.MULTIPLY);

                    }

                } else {

                    // -------
                    // erro !!
                    // -------

                    // seta a cor mista
                    mColorFilter = new PorterDuffColorFilter(mBlurMixedColorFilter, PorterDuff.Mode.MULTIPLY);

                }
            }
        });

        // engine
        BlurTask.setBlurEngine(BlurUtils.BlurEngine.RenderScriptBlur);

        // blur
        new BlurTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

    }

    public static void onConfigurationChanged() {

        // -----------------
        // alterou a rotação
        // -----------------

        // recicla
      //  recycle();
        RecentsActivity.startBlurTask();
    }



        public static class BlurTask extends AsyncTask<Void, Void, Bitmap> {

            private static int[] mScreenDimens;
            private static Bitmap mScreenBitmap;
            private static BlurUtils.BlurEngine mBlurEngine;
            private static BlurUtils.BlurTaskCallback mCallback;

            public static void setBlurEngine(BlurUtils.BlurEngine blurEngine) {

                mBlurEngine = blurEngine;

            }

            private Bitmap drawableToBitmap(Drawable drawable) {
                Bitmap bitmap = null;
                if (drawable instanceof BitmapDrawable) {
                    BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
                    if(bitmapDrawable.getBitmap() != null) {
                        return bitmapDrawable.getBitmap();
                    }
                }
                if(drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
                    bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); // Single color bitmap will be created of 1x1 pixel
                } else {
                    bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
                }
                Canvas canvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                drawable.draw(canvas);
                return bitmap;
            }

            public static void setBlurTaskCallback(BlurUtils.BlurTaskCallback callBack) {

                mCallback = callBack;

            }

            public static int[] getRealScreenDimensions() {

                return mScreenDimens;

            }

            public static Bitmap getLastBlurredBitmap() {

                return mScreenBitmap;

            }

            @Override
            protected void onPreExecute() {

                // obtém o tamamho real da tela
                mScreenDimens = DisplayUtils.getRealScreenDimensions(mContext);

                // obtém a screenshot da tela com escala reduzida
                //mScreenBitmap = DisplayUtils.takeSurfaceScreenshot(mContext, mBlurScale);
                WallpaperManager wallpaperManager = WallpaperManager.getInstance(mContext);
                DisplayMetrics dm = mContext.getResources().getDisplayMetrics();
                float screenh = dm.heightPixels;
                float screenw = dm.widthPixels;
                float bmheight = screenh;
                float bmwidth = screenw;
                float bmratio = 1;
                Bitmap bback = drawableToBitmap(wallpaperManager.getDrawable());
                float bbh = bback.getHeight();
                float bbw = bback.getWidth();
                if (bmheight > bbh) {
                    bmratio = bbh/bmheight;
                    bmheight = (bmratio*screenh);
                }
                bmwidth = (bmwidth*bmratio);
                if (bmwidth > bbw) {
                    bmratio = bbw/screenw;
                    bmwidth = (bmratio*screenw);
                    bmheight = (bmratio*screenh);
                }
//                Bitmap mScreenBitmap2 = Bitmap.createBitmap(bback, (int)((screenw-(bbw-bmwidth))/2), (int)((screenh-(bbh-bmheight))/2), (int)bmwidth, (int)bmheight)
                Bitmap mScreenBitmap2 = Bitmap.createBitmap(bback, 0, 0, (int)bmwidth, (int)bmheight);
                mScreenBitmap = Bitmap.createScaledBitmap(
                        mScreenBitmap2, (int)(bmwidth / 20), (int)(bmheight / 20), false);
            }

            @Override
            protected Bitmap doInBackground(Void... arg0) {

                try {

                    // continua ?
                    if (mScreenBitmap == null)
                        return null;

                    // calback
                    mCallback.dominantColor(DisplayUtils.getDominantColorByPixelsSampling(mScreenBitmap, 10, 10));

                    // blur engine
                    if (mBlurEngine == BlurUtils.BlurEngine.RenderScriptBlur) {

                        mScreenBitmap = mBlurUtils.renderScriptBlur(mScreenBitmap, mBlurRadius);

                    } else if (mBlurEngine == BlurUtils.BlurEngine.StackBlur) {

                        mScreenBitmap = mBlurUtils.stackBlur(mScreenBitmap, mBlurRadius);

                    } else if (mBlurEngine == BlurUtils.BlurEngine.FastBlur) {

                        mBlurUtils.fastBlur(mScreenBitmap, mBlurRadius);

                    }

                    return mScreenBitmap;

                } catch (OutOfMemoryError e) {

                    // erro
                    return null;

                }
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {

                if (bitmap != null) {

                    // -----------------------------
                    // bitmap criado com sucesso !!!
                    // -----------------------------

                    // callback
                    mCallback.blurTaskDone(bitmap);

                } else {

                    // --------------------------
                    // erro ao criar o bitmap !!!
                    // --------------------------

                    // callback
                    mCallback.blurTaskDone(null);

                }
            }
        }

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private ReferenceCountedTrigger mExitTrigger;

    /**
     * A common Runnable to finish Recents either by calling finish() (with a custom animation) or
     * launching Home with some ActivityOptions.  Generally we always launch home when we exit
     * Recents rather than just finishing the activity since we don't know what is behind Recents in
     * the task stack.  The only case where we finish() directly is when we are cancelling the full
     * screen transition from the app.
     */
    class FinishRecentsRunnable implements Runnable {
        Intent mLaunchIntent;
        ActivityOptions mLaunchOpts;
        boolean mAbort = false;

        /**
         * Creates a finish runnable that starts the specified intent, using the given
         * ActivityOptions.
         */
        public FinishRecentsRunnable(Intent launchIntent, ActivityOptions opts) {
            mLaunchIntent = launchIntent;
            mLaunchOpts = opts;
        }

        public void setAbort(boolean run) {
            this.mAbort = run;
        }

        @Override
        public void run() {
            if (mAbort) {
                return;
            }
            // Finish Recents
            if (mLaunchIntent != null) {
                try {
                    if (mLaunchOpts != null) {
                        startActivityAsUser(mLaunchIntent, mLaunchOpts.toBundle(), UserHandle.CURRENT);
                    } else {
                        startActivityAsUser(mLaunchIntent, UserHandle.CURRENT);
                    }
                } catch (Exception e) {
                    Console.logError(RecentsActivity.this,
                            getString(R.string.recents_launch_error_message, "Home"));
                }
            } else {
                finish();
                overridePendingTransition(R.anim.recents_to_launcher_enter,
                        R.anim.recents_to_launcher_exit);
            }
        }
    }

    /**
     * Broadcast receiver to handle messages from AlternateRecentsComponent.
     */
    final BroadcastReceiver mServiceBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Recents.ACTION_HIDE_RECENTS_ACTIVITY)) {
                if (intent.getBooleanExtra(Recents.EXTRA_TRIGGERED_FROM_ALT_TAB, false)) {
                    // If we are hiding from releasing Alt-Tab, dismiss Recents to the focused app
                    dismissRecentsToFocusedTaskOrHome(false);
                } else if (intent.getBooleanExtra(Recents.EXTRA_TRIGGERED_FROM_HOME_KEY, false)) {
                    // Otherwise, dismiss Recents to Home
                    dismissRecentsToHomeRaw(true);
                } else {
                    // Do nothing
                }
            } else if (action.equals(Recents.ACTION_TOGGLE_RECENTS_ACTIVITY)) {
                // If we are toggling Recents, then first unfilter any filtered stacks first
                dismissRecentsToFocusedTaskOrHome(true);
            } else if (action.equals(Recents.ACTION_START_ENTER_ANIMATION)) {
                // Trigger the enter animation
                onEnterAnimationTriggered();
                // Notify the fallback receiver that we have successfully got the broadcast
                // See AlternateRecentsComponent.onAnimationStarted()
                setResultCode(Activity.RESULT_OK);
            }
        }
    };

    /**
     * Broadcast receiver to handle messages from the system
     */
    final BroadcastReceiver mSystemBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                // When the screen turns off, dismiss Recents to Home
                dismissRecentsToHome(false);
            } else if (action.equals(SearchManager.INTENT_GLOBAL_SEARCH_ACTIVITY_CHANGED)) {
                // When the search activity changes, update the search widget view
                SystemServicesProxy ssp = RecentsTaskLoader.getInstance().getSystemServicesProxy();
                mSearchWidgetInfo = ssp.getOrBindSearchAppWidget(context, mAppWidgetHost);
                refreshSearchWidgetView();
            }
        }
    };

    /**
     * A custom debug trigger to listen for a debug key chord.
     */
    final DebugTrigger mDebugTrigger = new DebugTrigger(new Runnable() {
        @Override
        public void run() {
            onDebugModeTriggered();
        }
    });

    /** Updates the set of recent tasks */

    private static void recycle() {

        if (mRecentsActivityRootView == null)
            return;

        // limpa e recicla
        if (mRecentsActivityRootView.getBackground() != null) {

            // recicla
            Bitmap bitmap = ((BitmapDrawable) mRecentsActivityRootView.getBackground()).getBitmap();
            if (bitmap != null) {

                bitmap.recycle();
                bitmap = null;

            }

            // limpa
            mRecentsActivityRootView.setBackground(null);

        }
    }

    void updateRecentsTasks() {
        // If AlternateRecentsComponent has preloaded a load plan, then use that to prevent
        // reconstructing the task stack
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        RecentsTaskLoadPlan plan = Recents.consumeInstanceLoadPlan();
        if (plan == null) {
            plan = loader.createLoadPlan(this);
        }

        // Start loading tasks according to the load plan
        if (!plan.hasTasks()) {
            loader.preloadTasks(plan, mConfig.launchedFromHome);
        }
        RecentsTaskLoadPlan.Options loadOpts = new RecentsTaskLoadPlan.Options();
        loadOpts.runningTaskId = mConfig.launchedToTaskId;
        loadOpts.numVisibleTasks = mConfig.launchedNumVisibleTasks;
        loadOpts.numVisibleTaskThumbnails = mConfig.launchedNumVisibleThumbnails;
        loader.loadTasks(this, plan, loadOpts);

        ArrayList<TaskStack> stacks = plan.getAllTaskStacks();
        mConfig.launchedWithNoRecentTasks = !plan.hasTasks();
        mRecentsView.setTaskStacks(stacks);

        // Create the home intent runnable
        Intent homeIntent = new Intent(Intent.ACTION_MAIN, null);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        mFinishLaunchHomeRunnable = new FinishRecentsRunnable(homeIntent,
            ActivityOptions.makeCustomAnimation(this,
                 R.anim.recents_to_search_launcher_enter,
                    R.anim.recents_to_search_launcher_exit));

        // Mark the task that is the launch target
        int taskStackCount = stacks.size();
        int launchTaskIndexInStack = 0;
        if (mConfig.launchedToTaskId != -1) {
            for (int i = 0; i < taskStackCount; i++) {
                TaskStack stack = stacks.get(i);
                ArrayList<Task> tasks = stack.getTasks();
                int taskCount = tasks.size();
                for (int j = 0; j < taskCount; j++) {
                    Task t = tasks.get(j);
                    if (t.key.id == mConfig.launchedToTaskId) {
                        t.isLaunchTarget = true;
                        launchTaskIndexInStack = tasks.size() - j - 1;
                        break;
                    }
                }
            }
        }

        boolean enableShakeCleanByUser = Settings.System.getInt(getContentResolver(),
            Settings.System.SHAKE_TO_CLEAN_RECENT, 0) == 1;

        // Update the top level view's visibilities
        if (mConfig.launchedWithNoRecentTasks) {
            if (mEmptyView == null) {
                mEmptyView = mEmptyViewStub.inflate();
            }
            TaskStackView.enableShake(false);
            mEmptyView.setVisibility(View.VISIBLE);
            mEmptyView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    dismissRecentsToHome(true);
                }
            });
            mRecentsView.setSearchBarVisibility(View.GONE);
        } else {
            if (mEmptyView != null) {
                mEmptyView.setVisibility(View.GONE);
                mEmptyView.setOnClickListener(null);
            }
            TaskStackView.enableShake(true && enableShakeCleanByUser);
            if (!mConfig.searchBarEnabled) {
                mRecentsView.setSearchBarVisibility(View.GONE);
            } else {
                if (mRecentsView.hasValidSearchBar()) {
                    mRecentsView.setSearchBarVisibility(View.VISIBLE);
                } else {
                    refreshSearchWidgetView();
                }
            }
        }

        // Animate the SystemUI scrims into view
        mScrimViews.prepareEnterRecentsAnimation();

        // Keep track of whether we launched from the nav bar button or via alt-tab
        if (mConfig.launchedWithAltTab) {
            MetricsLogger.count(this, "overview_trigger_alttab", 1);
        } else {
            MetricsLogger.count(this, "overview_trigger_nav_btn", 1);
        }
        // Keep track of whether we launched from an app or from home
        if (mConfig.launchedFromAppWithThumbnail) {
            MetricsLogger.count(this, "overview_source_app", 1);
            // If from an app, track the stack index of the app in the stack (for affiliated tasks)
            MetricsLogger.histogram(this, "overview_source_app_index", launchTaskIndexInStack);
        } else {
            MetricsLogger.count(this, "overview_source_home", 1);
        }
        // Keep track of the total stack task count
        int taskCount = 0;
        for (int i = 0; i < stacks.size(); i++) {
            TaskStack stack = stacks.get(i);
            taskCount += stack.getTaskCount();
        }
        MetricsLogger.histogram(this, "overview_task_count", taskCount);
    }

    /** Dismisses recents if we are already visible and the intent is to toggle the recents view */
    boolean dismissRecentsToFocusedTaskOrHome(boolean checkFilteredStackState) {
        SystemServicesProxy ssp = RecentsTaskLoader.getInstance().getSystemServicesProxy();
        if (ssp.isRecentsTopMost(ssp.getTopMostTask(), null)) {
            // If we currently have filtered stacks, then unfilter those first
            if (checkFilteredStackState &&
                mRecentsView.unfilterFilteredStacks()) return true;
            // If we have a focused Task, launch that Task now
            if (mRecentsView.launchFocusedTask()) return true;
            // If we launched from Home, then return to Home
            if (mConfig.launchedFromHome) {
                dismissRecentsToHomeRaw(true);
                return true;
            }
            // Otherwise, try and return to the Task that Recents was launched from
            if (mRecentsView.launchPreviousTask()) return true;
            // If none of the other cases apply, then just go Home
            dismissRecentsToHomeRaw(true);
            return true;
        }
        return false;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && mExitTrigger != null && mExitTrigger.getCount() > 0) {
            // we are animating recents out and the window has lost focus during the
            // animation. we need to stop everything we're doing now and get out
            // without any animations (since we were already animating)
            mFinishLaunchHomeRunnable.setAbort(true);
            finish();
            overridePendingTransition(0, 0);
        }
    }

    /** Dismisses Recents directly to Home. */
    void dismissRecentsToHomeRaw(boolean animated) {
        if (animated) {
            mExitTrigger = new ReferenceCountedTrigger(this,
                    null, mFinishLaunchHomeRunnable, null);
            mRecentsView.startExitToHomeAnimation(
                    new ViewAnimation.TaskViewExitContext(mExitTrigger));
        } else {
            mFinishLaunchHomeRunnable.run();
        }
    }

    /** Dismisses Recents directly to Home without transition animation. */
    void dismissRecentsToHomeWithoutTransitionAnimation() {
        finish();
        overridePendingTransition(0, 0);
    }

    /** Dismisses Recents directly to Home if we currently aren't transitioning. */
    boolean dismissRecentsToHome(boolean animated) {
        SystemServicesProxy ssp = RecentsTaskLoader.getInstance().getSystemServicesProxy();
        if (ssp.isRecentsTopMost(ssp.getTopMostTask(), null)) {
            // Return to Home
            dismissRecentsToHomeRaw(animated);
            return true;
        }
        return false;
    }

    /** Called with the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // For the non-primary user, ensure that the SystemServicesProxy and configuration is
        // initialized
        RecentsTaskLoader.initialize(this);
        SystemServicesProxy ssp = RecentsTaskLoader.getInstance().getSystemServicesProxy();
        mConfig = RecentsConfiguration.reinitialize(this, ssp);

        // Initialize the widget host (the host id is static and does not change)
        mAppWidgetHost = new RecentsAppWidgetHost(this, Constants.Values.App.AppWidgetHostId);

        // Set the Recents layout
        setContentView(R.layout.recents);
        mRecentsView = (RecentsView) findViewById(R.id.recents_view);
        mRecentsView.setCallbacks(this);
        mRecentsView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        mEmptyViewStub = (ViewStub) findViewById(R.id.empty_view_stub);
        mDebugOverlayStub = (ViewStub) findViewById(R.id.debug_overlay_stub);
        mScrimViews = new SystemBarScrimViews(this, mConfig);
        inflateDebugOverlay();

        // Bind the search app widget when we first start up
        mSearchWidgetInfo = ssp.getOrBindSearchAppWidget(this, mAppWidgetHost);

        // Register the broadcast receiver to handle messages when the screen is turned off
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(SearchManager.INTENT_GLOBAL_SEARCH_ACTIVITY_CHANGED);
        registerReceiver(mSystemBroadcastReceiver, filter);
//TODO: fuuuu
        try {
            // obtém os campos
            RecentsView mRecentsView = (RecentsView) getObjectField(this, "mRecentsView");
            
            // guarda o layout parente do mRecentsView (root)
            mRecentsActivityRootView = (FrameLayout) mRecentsView.getParent();

            // obtém o último blurred bitmap
            Bitmap lastBlurredBitmap = BlurTask.getLastBlurredBitmap();

            // seta o background ?
            if ((mBlurredRecentAppsEnabled) && (lastBlurredBitmap != null)) {

                // cria o drawable com o filtro de cor
                BitmapDrawable blurredDrawable = new BitmapDrawable(lastBlurredBitmap);
                blurredDrawable.setColorFilter(mColorFilter);

                // seta
                mRecentsActivityRootView.setBackground(blurredDrawable);

            }
        } catch (Exception e){
            Log.d("MANGO3", String.valueOf(e));
        }
    }

    //#################################################################################################
    public static Object getObjectField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).get(obj);
        } catch (IllegalAccessException e) {
            // should not happen
            Log.d("MANGJAMJQ", String.valueOf(e));
            throw new IllegalAccessError(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw e;
        }
    }


    /**
     * Look up a field in a class and set it to accessible. The result is cached.
     * If the field was not found, a {@link NoSuchFieldError} will be thrown.
     */
    public static Field findField(Class<?> clazz, String fieldName) {
        StringBuilder sb = new StringBuilder(clazz.getName());
        sb.append('#');
        sb.append(fieldName);
        String fullFieldName = sb.toString();

        if (fieldCache.containsKey(fullFieldName)) {
            Field field = fieldCache.get(fullFieldName);
            if (field == null)
                throw new NoSuchFieldError(fullFieldName);
            return field;
        }

        try {
            Field field = findFieldRecursiveImpl(clazz, fieldName);
            field.setAccessible(true);
            fieldCache.put(fullFieldName, field);
            return field;
        } catch (NoSuchFieldException e) {
            fieldCache.put(fullFieldName, null);
            throw new NoSuchFieldError(fullFieldName);
        }
    }

    private static Field findFieldRecursiveImpl(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            while (true) {
                clazz = clazz.getSuperclass();
                if (clazz == null || clazz.equals(Object.class))
                    break;

                try {
                    return clazz.getDeclaredField(fieldName);
                } catch (NoSuchFieldException ignored) {}
            }
            throw e;
        }
    }

    /** Inflates the debug overlay if debug mode is enabled. */
    void inflateDebugOverlay() {
        if (!Constants.DebugFlags.App.EnableDebugMode) return;

        if (mConfig.debugModeEnabled && mDebugOverlay == null) {
            // Inflate the overlay and seek bars
            mDebugOverlay = (DebugOverlayView) mDebugOverlayStub.inflate();
            mDebugOverlay.setCallbacks(this);
            mRecentsView.setDebugOverlay(mDebugOverlay);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        // Clear any debug rects
        if (mDebugOverlay != null) {
            mDebugOverlay.clear();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        MetricsLogger.visible(this, MetricsLogger.OVERVIEW_ACTIVITY);
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        SystemServicesProxy ssp = loader.getSystemServicesProxy();
        Recents.notifyVisibilityChanged(this, ssp, true);

        // Register the broadcast receiver to handle messages from our service
        IntentFilter filter = new IntentFilter();
        filter.addAction(Recents.ACTION_HIDE_RECENTS_ACTIVITY);
        filter.addAction(Recents.ACTION_TOGGLE_RECENTS_ACTIVITY);
        filter.addAction(Recents.ACTION_START_ENTER_ANIMATION);
        registerReceiver(mServiceBroadcastReceiver, filter);

        // Register any broadcast receivers for the task loader
        loader.registerReceivers(this, mRecentsView);

        // Update the recent tasks
        updateRecentsTasks();

        // If this is a new instance from a configuration change, then we have to manually trigger
        // the enter animation state, or if recents was relaunched by AM, without going through
        // the normal mechanisms
        boolean wasLaunchedByAm = !mConfig.launchedFromHome && !mConfig.launchedFromAppWithThumbnail;
        if (mConfig.launchedHasConfigurationChanged || wasLaunchedByAm) {
            onEnterAnimationTriggered();
        }

        if (!mConfig.launchedHasConfigurationChanged) {
            mRecentsView.disableLayersForOneFrame();
        }
    }

    @Override
    protected void onResume() {
        if (mConfig.searchBarEnabled && mConfig.launchedFromHome) {
            overridePendingTransition(0, 0);
        }
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mAfterPauseRunnable != null) {
            mRecentsView.post(mAfterPauseRunnable);
            mAfterPauseRunnable = null;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        mExitTrigger = null;

        MetricsLogger.hidden(this, MetricsLogger.OVERVIEW_ACTIVITY);
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        SystemServicesProxy ssp = loader.getSystemServicesProxy();
        Recents.notifyVisibilityChanged(this, ssp, false);

        // Notify the views that we are no longer visible
        mRecentsView.onRecentsHidden();

        // Unregister the RecentsService receiver
        unregisterReceiver(mServiceBroadcastReceiver);

        // Unregister any broadcast receivers for the task loader
        loader.unregisterReceivers();

        // Workaround for b/22542869, if the RecentsActivity is started again, but without going
        // through SystemUI, we need to reset the config launch flags to ensure that we do not
        // wait on the system to send a signal that was never queued.
        mConfig.launchedFromHome = false;
        mConfig.launchedFromSearchHome = false;
        mConfig.launchedFromAppWithThumbnail = false;
        mConfig.launchedToTaskId = -1;
        mConfig.launchedWithAltTab = false;
        mConfig.launchedHasConfigurationChanged = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Unregister the system broadcast receivers
        unregisterReceiver(mSystemBroadcastReceiver);

        // Stop listening for widget package changes if there was one bound
        mAppWidgetHost.stopListening();
    }

    public void onEnterAnimationTriggered() {
        // Try and start the enter animation (or restart it on configuration changed)
        ReferenceCountedTrigger t = new ReferenceCountedTrigger(this, null, null, null);
        ViewAnimation.TaskViewEnterContext ctx = new ViewAnimation.TaskViewEnterContext(t);
        mRecentsView.startEnterRecentsAnimation(ctx);

        if (mSearchWidgetInfo != null) {
            final WeakReference<RecentsAppWidgetHost.RecentsAppWidgetHostCallbacks> cbRef =
                    new WeakReference<RecentsAppWidgetHost.RecentsAppWidgetHostCallbacks>(
                            RecentsActivity.this);
            ctx.postAnimationTrigger.addLastDecrementRunnable(new Runnable() {
                @Override
                public void run() {
                    // Start listening for widget package changes if there is one bound
                    RecentsAppWidgetHost.RecentsAppWidgetHostCallbacks cb = cbRef.get();
                    if (cb != null) {
                        mAppWidgetHost.startListening(cb);
                    }
                }
            });
        }

        // Animate the SystemUI scrim views
        mScrimViews.startEnterRecentsAnimation();
    }

    @Override
    public void onTrimMemory(int level) {
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        if (loader != null) {
            loader.onTrimMemory(level);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_TAB: {
                boolean hasRepKeyTimeElapsed = (SystemClock.elapsedRealtime() -
                        mLastTabKeyEventTime) > mConfig.altTabKeyDelay;
                if (event.getRepeatCount() <= 0 || hasRepKeyTimeElapsed) {
                    // Focus the next task in the stack
                    final boolean backward = event.isShiftPressed();
                    mRecentsView.focusNextTask(!backward);
                    mLastTabKeyEventTime = SystemClock.elapsedRealtime();
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_UP: {
                mRecentsView.focusNextTask(true);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                mRecentsView.focusNextTask(false);
                return true;
            }
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_FORWARD_DEL: {
                mRecentsView.dismissFocusedTask();
                // Keep track of deletions by keyboard
                MetricsLogger.histogram(this, "overview_task_dismissed_source",
                        Constants.Metrics.DismissSourceKeyboard);
                return true;
            }
            default:
                break;
        }
        // Pass through the debug trigger
        mDebugTrigger.onKeyEvent(keyCode);
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onUserInteraction() {
        mRecentsView.onUserInteraction();
    }

    @Override
    public void onBackPressed() {
        // Test mode where back does not do anything
        if (mConfig.debugModeEnabled) return;

        // Dismiss Recents to the focused Task or Home
        dismissRecentsToFocusedTaskOrHome(true);
    }

    /** Called when debug mode is triggered */
    public void onDebugModeTriggered() {
        if (mConfig.developerOptionsEnabled) {
            if (Prefs.getBoolean(this, Prefs.Key.DEBUG_MODE_ENABLED, false /* boolean */)) {
                // Disable the debug mode
                Prefs.remove(this, Prefs.Key.DEBUG_MODE_ENABLED);
                mConfig.debugModeEnabled = false;
                inflateDebugOverlay();
                if (mDebugOverlay != null) {
                    mDebugOverlay.disable();
                }
            } else {
                // Enable the debug mode
                Prefs.putBoolean(this, Prefs.Key.DEBUG_MODE_ENABLED, true);
                mConfig.debugModeEnabled = true;
                inflateDebugOverlay();
                if (mDebugOverlay != null) {
                    mDebugOverlay.enable();
                }
            }
            Toast.makeText(this, "Debug mode (" + Constants.Values.App.DebugModeVersion + ") " +
                (mConfig.debugModeEnabled ? "Enabled" : "Disabled") + ", please restart Recents now",
                Toast.LENGTH_SHORT).show();
        }
    }


    /**** RecentsResizeTaskDialog ****/

    private RecentsResizeTaskDialog getResizeTaskDebugDialog() {
        if (mResizeTaskDebugDialog == null) {
            mResizeTaskDebugDialog = new RecentsResizeTaskDialog(getFragmentManager(), this);
        }
        return mResizeTaskDebugDialog;
    }

    @Override
    public void onTaskResize(Task t) {
        getResizeTaskDebugDialog().showResizeTaskDialog(t, mRecentsView);
    }

    /**** RecentsView.RecentsViewCallbacks Implementation ****/

    @Override
    public void onExitToHomeAnimationTriggered() {
        // Animate the SystemUI scrim views out
        mScrimViews.startExitRecentsAnimation();
    }

    @Override
    public void onTaskViewClicked() {
    }

    @Override
    public void onTaskLaunchFailed() {
        // Return to Home
        dismissRecentsToHomeRaw(true);
    }

    @Override
    public void onAllTaskViewsDismissed() {
        mFinishLaunchHomeRunnable.run();
    }

    @Override
    public void onScreenPinningRequest() {
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        SystemServicesProxy ssp = loader.getSystemServicesProxy();
        Recents.startScreenPinning(this, ssp);

        MetricsLogger.count(this, "overview_screen_pinned", 1);
    }

    @Override
    public void runAfterPause(Runnable r) {
        mAfterPauseRunnable = r;
    }

    /**** RecentsAppWidgetHost.RecentsAppWidgetHostCallbacks Implementation ****/

    @Override
    public void refreshSearchWidgetView() {
        if (mSearchWidgetInfo != null) {
            SystemServicesProxy ssp = RecentsTaskLoader.getInstance().getSystemServicesProxy();
            int searchWidgetId = ssp.getSearchAppWidgetId(this);
            mSearchWidgetHostView = (RecentsAppWidgetHostView) mAppWidgetHost.createView(
                    this, searchWidgetId, mSearchWidgetInfo);
            Bundle opts = new Bundle();
            opts.putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                    AppWidgetProviderInfo.WIDGET_CATEGORY_SEARCHBOX);
            mSearchWidgetHostView.updateAppWidgetOptions(opts);
            // Set the padding to 0 for this search widget
            mSearchWidgetHostView.setPadding(0, 0, 0, 0);
            mRecentsView.setSearchBar(mSearchWidgetHostView);
        } else {
            mRecentsView.setSearchBar(null);
        }
    }

    /**** DebugOverlayView.DebugOverlayViewCallbacks ****/

    @Override
    public void onPrimarySeekBarChanged(float progress) {
        // Do nothing
    }

    @Override
    public void onSecondarySeekBarChanged(float progress) {
        // Do nothing
    }

    public static void init(Context context) {

        // guarda
        mContext = context;

        // inicia o BlurUtils
        mBlurUtils = new BlurUtils(mContext);

    }

    public static void updatePreferences(Context mContext) {

        // atualiza
        mBlurScale = 20;
        mBlurRadius = 3;
        mBlurDarkColorFilter = Color.LTGRAY;
        mBlurMixedColorFilter = Color.GRAY;
        mBlurLightColorFilter = Color.DKGRAY;
        mBlurredRecentAppsEnabled = (Settings.System.getInt(mContext.getContentResolver(), Settings.System.RECENT_APPS_ENABLED_PREFERENCE_KEY, 1) == 1);
    }
}
