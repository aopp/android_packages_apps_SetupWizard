/*
 * Copyright (C) 2013 The CyanogenMod Project
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

package com.cyanogenmod.setupwizard.setup;

import com.cyanogenmod.setupwizard.ui.SetupWizardActivity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.FragmentManager;

import android.support.v4.content.WakefulBroadcastReceiver;
//import android.content.BroadcastReceiver;
import android.content.Context;


import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;

import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;

import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.cyanogenmod.setupwizard.R;

import com.cyanogenmod.setupwizard.cmstats.SetupStats;
import com.cyanogenmod.setupwizard.ui.SetupPageFragment;


import cyanogenmod.hardware.CMHardwareManager;
import cyanogenmod.providers.CMSettings;

public class PwnixSettingsPage extends SetupPage {

    public static final String TAG = "PwnixSetupPage";

    public static final String KEY_ENABLE_NAV_KEYS = "enable_nav_keys";


    public PwnixSettingsPage(Context context, SetupDataCallbacks callbacks) {
        super(context, callbacks);
    }

    @Override
    public Fragment getFragment(FragmentManager fragmentManager, int action) {
        Fragment fragment = fragmentManager.findFragmentByTag(getKey());
        if (fragment == null) {
            Bundle args = new Bundle();
            args.putString(Page.KEY_PAGE_ARGUMENT, getKey());
            args.putInt(Page.KEY_PAGE_ACTION, action);
            fragment = new PwnixSetupFragment();
            fragment.setArguments(args);
        }
        return fragment;
    }


    @Override
    public String getKey() {
        return TAG;
    }

    @Override
    public int getTitleResId() {
        return R.string.pwnix_environment_setup;
    }

    private static void writeDisableNavkeysOption(Context context, boolean enabled) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final int defaultBrightness = context.getResources().getInteger(
                com.android.internal.R.integer.config_buttonBrightnessSettingDefault);

        Settings.Secure.putInt(context.getContentResolver(),
                Settings.Secure.DEV_FORCE_SHOW_NAVBAR, enabled ? 1 : 0);
        final CMHardwareManager hardware = CMHardwareManager.getInstance(context);
        hardware.set(CMHardwareManager.FEATURE_KEY_DISABLE, enabled);

        /* Save/restore button timeouts to disable them in softkey mode */
        SharedPreferences.Editor editor = prefs.edit();

        if (enabled) {
            int currentBrightness = CMSettings.Secure.getInt(context.getContentResolver(),
                    CMSettings.Secure.BUTTON_BRIGHTNESS, defaultBrightness);
            if (!prefs.contains("pre_navbar_button_backlight")) {
                editor.putInt("pre_navbar_button_backlight", currentBrightness);
            }
            CMSettings.Secure.putInt(context.getContentResolver(),
                    CMSettings.Secure.BUTTON_BRIGHTNESS, 0);
        } else {
            int oldBright = prefs.getInt("pre_navbar_button_backlight", -1);
            if (oldBright != -1) {
                CMSettings.Secure.putInt(context.getContentResolver(),
                        CMSettings.Secure.BUTTON_BRIGHTNESS, oldBright);
                editor.remove("pre_navbar_button_backlight");
            }
        }
        editor.commit();
    }

    @Override
    public void onFinishSetup() {
        getCallbacks().addFinishRunnable(new Runnable() {
            @Override
            public void run() {
                if (getData().containsKey(KEY_ENABLE_NAV_KEYS)) {
                    SetupStats.addEvent(SetupStats.Categories.SETTING_CHANGED,
                            SetupStats.Action.ENABLE_NAV_KEYS,
                            SetupStats.Label.CHECKED,
                            String.valueOf(getData().getBoolean(KEY_ENABLE_NAV_KEYS)));
                    writeDisableNavkeysOption(mContext, getData().getBoolean(KEY_ENABLE_NAV_KEYS));
                }
            }
        });
        /** CM saving their prefs **/
    }

    public static class PwnixSetupFragment extends SetupPageFragment {

        private static TextView step1Label, step2Label, step3Label, step4Label, step5Label, step6Label, fragmentBlurb; // Step Labels

        private static TextView launchButton; // "Button" to start process - textview with an onclick defined

        private static TextView overallProgressLabel;

        private static ImageView step1Imageview, step2Imageview, step3Imageview, step4Imageview, step5Imageview, step6Imageview, doneImageview; // Checkmarks for complete installation steps

        private static RelativeLayout row1, row2, row3, row4, row5, row6;

        private static ScrollView stepContainer; // Used to try and focus user on currently in progress step row

        private static boolean animationRan = false; // Used for start install transition

        private static ProgressBar step1PGB, step2PGB, step3PGB, step4PGB, step5PGB, step6PGB, overallProgressbar; // Indeterminate progress bars for each step in installation process

        private SetupWizardActivity activity;

        /**
         * @see java.lang.Enum
         * PwnixInstallState
         * Defines the states/steps of the Pwnix Environment Setup
         */
        public enum PwnixInstallState { CONNECTION_ERROR, POWER_ERROR, NOTSTARTED, DOWNLOADING, DOWNLOADED, VERIFICATION_ERROR, VERIFYING, VERIFIED, INSTALLING, INSTALLED, REGISTERING, REGISTERED, PREPARING, PREPARED, SETTINGUP, SETUP }

        /**
         * fragmentState - holds application state - used to maintain UI across orientation change, etc
         */
        public static PwnixInstallState fragmentState = PwnixInstallState.NOTSTARTED;

        private static MyReceiver broadcastReceiver;

        private static ImageView startIcon;

        private static TextView dlProgress;

        private static int dlP =0;

        private static boolean receiverRegistered = false;

        private AlertDialog errorDialog;

        private boolean dismissed = false;

        @Override
        protected void initializePage() {
            Log.d("init page", this.toString());

             activity = (SetupWizardActivity) getActivity();


            //Need to check shared prefs to see if provisioned.

            //if provisioned set state to SETUP otherwise ignore because this may be called mid install and fragmentState should be accurate

            gatherUIElements();
            handleUIState();
            loadReceiver();
        }

        public void loadReceiver() {
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.intent.action.MAIN");

            if (broadcastReceiver==null) { // may break this
                broadcastReceiver = new MyReceiver();
                broadcastReceiver.setCallback(this);
                Log.d("broadcastR null", "creating");
                receiverRegistered=false; //it was null -- it aint registered
            }

            if (!receiverRegistered) {
                getActivity().registerReceiver(broadcastReceiver, filter);
                receiverRegistered=true;
            }
        }

        public void unloadReceiver() {
            if (receiverRegistered) {
                getActivity().unregisterReceiver(broadcastReceiver);
                receiverRegistered=false;
            }
        }

        @Override
        public void onResume() {
            super.onResume();

            loadReceiver();
            //check shared prefs the same as init
            handleUIState(); //handle user putting screen to sleep mid install and waking up
        }

        @Override
        public void onPause() {

            if (errorDialog != null) {
                errorDialog.dismiss();
            }
            super.onPause();
        }

        @Override
        public void onDestroyView() {
            Log.d("ondestroyView","called");
            step1Label = null;
            step2Label = null;
            step3Label = null;
            step4Label = null;
            step5Label = null;
            step6Label =null;
            fragmentBlurb = null; // Step Labels
            launchButton = null;
            overallProgressLabel = null;
            step1Imageview = null;
            step2Imageview = null;
            step3Imageview = null;
            step4Imageview = null;
            step5Imageview = null;
            step6Imageview = null;
            doneImageview = null;
            row1 = null;
            row2 = null;
            row3 = null;
            row4 = null;
            row5 = null;
            row6 = null;
            stepContainer = null;
            step1PGB = null;
            step2PGB = null;
            step3PGB = null;
            step4PGB = null;
            step5PGB = null;
            step6PGB = null;
            overallProgressbar = null;
            //activity =null;
            startIcon = null;
            dlProgress = null;
            broadcastReceiver = null;
            super.onDestroyView();
            // dialog=null;

        }

        @Override
        public void onDestroy() {
            Log.d("ondestroy","called");

            unloadReceiver();
            step1Label = null;
            step2Label = null;
            step3Label = null;
            step4Label = null;
            step5Label = null;
            step6Label =null;
            fragmentBlurb = null; // Step Labels
            launchButton = null;
            overallProgressLabel = null;
            step1Imageview = null;
            step2Imageview = null;
            step3Imageview = null;
            step4Imageview = null;
            step5Imageview = null;
            step6Imageview = null;
            doneImageview = null;
            row1 = null;
            row2 = null;
            row3 = null;
            row4 = null;
            row5 =null;
            row6 = null;
            stepContainer = null;
            step1PGB = null;
            step2PGB = null;
            step3PGB = null;
            step4PGB = null;
            step5PGB = null;
            step6PGB = null;
            overallProgressbar = null;
            //activity =null;
            startIcon = null;
            dlProgress = null;
            broadcastReceiver = null;
            //dialog=null;
            super.onDestroy();
        }

        /**
         private void showWikiDialog(Context context){
         //not allowed in a privilaged application context (i.e. SetupWizard lol)
         //Instead may offer checkbox to open browser to wiki while they install google services

         AlertDialog.Builder wikiAlert = new AlertDialog.Builder(context);
         wikiAlert.setTitle("AOPP Wiki");


         WebView wv = new WebView(context);
         wv.getSettings().setJavaScriptEnabled(true);
         wv.loadUrl("https://wiki.pwnieexpress.com/index.php/Getting_Started");
         wv.setWebViewClient(new WebViewClient(){
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
        view.loadUrl(url);
        return true;
        }
        });

         wikiAlert.setView(wv);
         wikiAlert.setNegativeButton("Close", new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int id) {
        dialog.dismiss();
        }
        });
         wikiAlert.show();
         }

         **/

        private void handleUiNotStarted() {
            step1Label.setPadding(15,0,0,0);
            step2Label.setPadding(15,0,0,0);
            step3Label.setPadding(15,0,0,0);
            step4Label.setPadding(15,0,0,0);
            step5Label.setPadding(15,0,0,0);
            step6Label.setPadding(15,0,0,0);

            step1Label.setText(R.string.step1future);
            step2Label.setText(R.string.step2future);
            step3Label.setText(R.string.step3future);
            step4Label.setText(R.string.step4future);
            step5Label.setText(R.string.step5future);
            step6Label.setText(R.string.step6future);

            step1Label.setTextColor(Color.parseColor("#bebebe"));
            step2Label.setTextColor(Color.parseColor("#bebebe"));
            step3Label.setTextColor(Color.parseColor("#bebebe"));
            step4Label.setTextColor(Color.parseColor("#bebebe"));
            step5Label.setTextColor(Color.parseColor("#bebebe"));
            step6Label.setTextColor(Color.parseColor("#bebebe"));

            dlProgress.setVisibility(View.GONE);
            step1PGB.setVisibility(View.GONE);
            step2PGB.setVisibility(View.GONE);
            step3PGB.setVisibility(View.GONE);
            step4PGB.setVisibility(View.GONE);
            step5PGB.setVisibility(View.GONE);
            step6PGB.setVisibility(View.GONE);

            step1Imageview.setVisibility(View.GONE);
            step2Imageview.setVisibility(View.GONE);
            step3Imageview.setVisibility(View.GONE);
            step4Imageview.setVisibility(View.GONE);
            step5Imageview.setVisibility(View.GONE);
            step6Imageview.setVisibility(View.GONE);

            fragmentBlurb.setVisibility(View.VISIBLE);

            overallProgressbar.setVisibility(View.GONE);
            overallProgressLabel.setVisibility(View.GONE);


            /** CHANGES MADE BELOW AROUND START BUTTON. FIX **/
            launchButton.setVisibility(View.VISIBLE);
            startIcon.setVisibility(View.VISIBLE);
            doneImageview.setVisibility(View.GONE);

            startIcon.setImageDrawable(getResources().getDrawable(R.drawable.ic_system_update_black_24dp));
            startIcon.setClickable(true);
            startIcon.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    //Testing
                    //fragmentState=PwnixInstallState.DOWNLOADING;
                    //handleUIState();

                    getActivity().sendBroadcast(new Intent().setAction("com.pwnieexpress.android.pxinstaller.action.START_PROVISION"));
                    activity.enableButtonBar(false);


                    startIcon.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            //stop mashing the button dear lord
                        }
                    });
                    startIcon.setClickable(false); //#overkill
                }//end on click
            });

            launchButton.setClickable(true);
            launchButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    //Testing
                    // fragmentState=PwnixInstallState.DOWNLOADING;
                    // handleUIState();

                    getActivity().sendBroadcast(new Intent().setAction("com.pwnieexpress.android.pxinstaller.action.START_PROVISION"));
                    activity.enableButtonBar(false);


                    launchButton.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            //stop mashing the button dear lord
                        }
                    });
                    launchButton.setClickable(false); //#overkill
                }//end on click
            });
        }

        private static void setOverallProgressbar(int p) {
            if (!(fragmentState==PwnixInstallState.DOWNLOADING) || ((fragmentState==PwnixInstallState.DOWNLOADING) && animationRan)) { // normal if its not the downloading fragment (starting) OR it is but the animation has already run
                // Hide Get Started Button since we are showing progress
                launchButton.setVisibility(View.GONE);
                startIcon.setVisibility(View.GONE);
                launchButton.setClickable(false);

                // Show progress bar and update
                overallProgressLabel.setVisibility(View.VISIBLE);
                overallProgressbar.setVisibility(View.VISIBLE);
                overallProgressbar.setProgress(p);

                overallProgressbar.invalidate();
            } else { // animated for first run
                launchButton.setVisibility(View.GONE);
                launchButton.setClickable(false);
                startIcon.setVisibility(View.GONE);

                overallProgressbar.setVisibility(View.VISIBLE);
                overallProgressLabel.setVisibility(View.VISIBLE);
                overallProgressbar.setProgress(p);

                Animation fadeIn = new AlphaAnimation(0.0f, 1.0f);
                fadeIn.setDuration(1000);
                overallProgressbar.setAnimation(fadeIn);
                overallProgressLabel.setAnimation(fadeIn);
                animationRan=true;
            }
        }

        private void startStep(int step) {
            switch(step) {
                case 1:
                    //fade out blurb (if this is the first time this has been called)
                    if (!animationRan) {
                        Animation fadeOut = new AlphaAnimation(1.0f, 0.0f);
                        fadeOut.setDuration(500);
                        fragmentBlurb.setAnimation(fadeOut);

                        fadeOut.setAnimationListener(new Animation.AnimationListener() {
                            @Override
                            public void onAnimationStart(Animation animation) {

                            }

                            @Override
                            public void onAnimationEnd(Animation animation) {
                                fragmentBlurb.setVisibility(View.GONE);
                                setOverallProgressbar(5);
                            }

                            @Override
                            public void onAnimationRepeat(Animation animation) {

                            }
                        });
                    } else { // if animation ran already it still needs to be gone -- orientation change
                        fragmentBlurb.setVisibility(View.GONE);
                        setOverallProgressbar(5); //show progress
                    }

                    // Update text and color
                    step1Label.setText(R.string.step1present);
                    step1Label.setPadding(0,0,0,0);
                    step1Label.setTextColor(Color.BLACK);
                    step1PGB.setVisibility(View.VISIBLE);
                    step1Label.invalidate();
                    step1PGB.invalidate();

                    break;
                case 2:
                    // Update padding (indent)
                    step2Label.setPadding(0,0,0,0);
                    // Update text and color
                    step2Label.setText(R.string.step2present);
                    step2Label.setTextColor(Color.BLACK);
                    step2PGB.setVisibility(View.VISIBLE);
                    step2Label.invalidate();
                    step2PGB.invalidate();

                    updatePreviousSteps(2);

                    break;
                case 3:
                    // Update padding (indent)
                    step3Label.setPadding(0,0,0,0);
                    // Update text and color
                    step3Label.setText(R.string.step3present);
                    step3Label.setTextColor(Color.BLACK);
                    step3PGB.setVisibility(View.VISIBLE);
                    step3Label.invalidate();
                    step3PGB.invalidate();
                    updatePreviousSteps(3);

                    break;
                case 4:
                    // Update padding (indent)
                    step4Label.setPadding(0,0,0,0);
                    // Update text and color
                    step4Label.setText(R.string.step4present);
                    step4Label.setTextColor(Color.BLACK);
                    step4PGB.setVisibility(View.VISIBLE);
                    step4Label.invalidate();
                    step4PGB.invalidate();
                    updatePreviousSteps(4);

                    break;
                case 5:
                    // Update padding (indent)
                    step5Label.setPadding(0,0,0,0);
                    // Update text and color
                    step5Label.setText(R.string.step5present);
                    step5Label.setTextColor(Color.BLACK);
                    step5PGB.setVisibility(View.VISIBLE);
                    step5Label.invalidate();
                    step5PGB.invalidate();
                    updatePreviousSteps(5);

                    break;
                case 6:
                    // Update padding (indent)
                    step6Label.setPadding(0,0,0,0);
                    // Update text and color
                    step6Label.setText(R.string.step6present);
                    step6Label.setTextColor(Color.BLACK);
                    step6PGB.setVisibility(View.VISIBLE);
                    step6Label.invalidate();
                    step6PGB.invalidate();
                    updatePreviousSteps(6);

                    break;
            }
        }

        // method is simply a location to handle 1 step done but 2 not started but takes care of setting the step states properly
        private void finishStep(int step){
            switch(step){
                case 1:
                    updatePreviousSteps(2);
                    break;
                case 2:
                    updatePreviousSteps(3);
                    break;
                case 3:
                    updatePreviousSteps(4);
                    break;
                case 4:
                    updatePreviousSteps(5);
                    break;
                case 5:
                    updatePreviousSteps(6);
                    break;
                case 6:
                    updatePreviousSteps(7);
                    break;
            }
        }

        /**
         *updatePreviousSteps- sets UI for already completed steps
         * @param currentStep - int - starting step - method will update all "rows"/steps below this value to the past tense / completed
         */
        private void updatePreviousSteps(int currentStep){
            switch(currentStep){
                case  7:
                    // Update all ( <6 )
                    fragmentBlurb.setVisibility(View.GONE);
                    step6Label.setPadding(15,0,0,0);
                    step6Label.setTextColor(Color.parseColor("#bebebe"));
                    step6Label.setText(R.string.step6past);
                    step6PGB.setVisibility(View.GONE);
                    step6Imageview.setVisibility(View.VISIBLE);
                    step6Imageview.setImageDrawable(getResources().getDrawable(R.drawable.ic_check_black_24dp));
                    step6Label.invalidate();
                    step6PGB.invalidate();
                    step6Imageview.invalidate();
                case 6:
                    // Update <5
                    fragmentBlurb.setVisibility(View.GONE);
                    step5Label.setPadding(15,0,0,0);
                    step5Label.setTextColor(Color.parseColor("#bebebe"));
                    step5Label.setText(R.string.step5past);
                    step5PGB.setVisibility(View.GONE);
                    step5Imageview.setVisibility(View.VISIBLE);
                    step5Imageview.setImageDrawable(getResources().getDrawable(R.drawable.ic_check_black_24dp));
                    step5Label.invalidate();
                    step5PGB.invalidate();
                    step5Imageview.invalidate();
                case 5:
                    // Update <4
                    fragmentBlurb.setVisibility(View.GONE);
                    step4Label.setPadding(15,0,0,0);
                    step4Label.setTextColor(Color.parseColor("#bebebe"));
                    step4Label.setText(R.string.step4past);
                    step4PGB.setVisibility(View.GONE);
                    step4Imageview.setVisibility(View.VISIBLE);
                    step4Imageview.setImageDrawable(getResources().getDrawable(R.drawable.ic_check_black_24dp));
                    step4Label.invalidate();
                    step4PGB.invalidate();
                    step4Imageview.invalidate();
                case 4:
                    // Update <3
                    fragmentBlurb.setVisibility(View.GONE);
                    step3Label.setPadding(15,0,0,0);
                    step3Label.setTextColor(Color.parseColor("#bebebe"));
                    step3Label.setText(R.string.step3past);
                    step3PGB.setVisibility(View.GONE);
                    step3Imageview.setVisibility(View.VISIBLE);
                    step3Imageview.setImageDrawable(getResources().getDrawable(R.drawable.ic_check_black_24dp));
                    step3Label.invalidate();
                    step3PGB.invalidate();
                    step3Imageview.invalidate();
                case 3:
                    // Update <2
                    fragmentBlurb.setVisibility(View.GONE);
                    step2Label.setPadding(15,0,0,0);
                    step2Label.setTextColor(Color.parseColor("#bebebe"));
                    step2Label.setText(R.string.step2past);
                    step2PGB.setVisibility(View.GONE);
                    step2Imageview.setVisibility(View.VISIBLE);
                    step2Imageview.setImageDrawable(getResources().getDrawable(R.drawable.ic_check_black_24dp));
                    step2Label.invalidate();
                    step2PGB.invalidate();
                    step2Imageview.invalidate();
                case 2:
                    // Update <1
                    // Update padding (indent)
                    fragmentBlurb.setVisibility(View.GONE);
                    step1Label.setPadding(15,0,0,0);
                    // Update row text and color
                    step1Label.setTextColor(Color.parseColor("#bebebe"));
                    step1Label.setText(R.string.step1past);

                    dlProgress.setVisibility(View.GONE);

                    // Replace spinner with checkmark
                    step1PGB.setVisibility(View.GONE);
                    step1Imageview.setVisibility(View.VISIBLE);
                    step1Imageview.setImageDrawable(getResources().getDrawable(R.drawable.ic_check_black_24dp));
                    step1Label.invalidate();
                    step1PGB.invalidate();
                    step1Imageview.invalidate();


                    break;
            }
        }

        private static void showDLProgress(){
            if(fragmentState==PwnixInstallState.DOWNLOADING|| fragmentState==PwnixInstallState.VERIFICATION_ERROR) { // assure we are in a state that actually shows progress.
                dlProgress.setVisibility(View.VISIBLE);
                if (dlP < 10) {
                    dlProgress.setText(" " + dlP + "%"); //lol I hate everything - center 0-9%
                } else {
                    dlProgress.setText(dlP + "%");
                }
                dlProgress.invalidate();
            }
        }

        public static void updateDLProgress(int a){
            dlP = a;
            showDLProgress();
        }

        public static String getCurrentSsid(Context context) {
            String ssid = null;
            ConnectivityManager connManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo networkInfo = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            if (networkInfo.isConnected()) {
                final WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                final WifiInfo connectionInfo = wifiManager.getConnectionInfo();
                if (connectionInfo != null && !TextUtils.isEmpty(connectionInfo.getSSID())) {
                    ssid = connectionInfo.getSSID();
                }
            }
            return ssid;
        }

        public void showAlert(PwnixInstallState error){
            Log.d("ShowAlert","Called");
            switch(error){
                case CONNECTION_ERROR:
                    if(errorDialog == null || !errorDialog.isShowing()) {
                        Log.d("CREATE CONNERR","+++++++++");
                        String ssid= PwnixSetupFragment.getCurrentSsid(getThis().getActivity());
                        if(ssid == null){
                            //special?
                        }
                        //Create Dialog
                        errorDialog = new AlertDialog.Builder(getActivity()).setTitle("Network Error").setMessage("We are not able to communicate with the server to download the Pwnix environment bundle.\n\nPlease ensure the network "+ssid+" has internet connectivity. Otherwise go back now and connect to a different network").setPositiveButton("OK",
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog,
                                                        int which) {
                                        getThis().setState(PwnixSetupFragment.PwnixInstallState.NOTSTARTED);

                                     //You shall not pass
                                     ((SetupWizardActivity)getActivity()).enableButtonBar(true);
                                     //cant go forward only backwards if you havent started
                                     ((SetupWizardActivity)getActivity()).enableNextButton(false);

                                        getThis().updateUI();
                                    }
                                }).setCancelable(false).create();
                        //Dialogs break immersive view - ty for the hack stackoverflow https://stackoverflow.com/questions/22794049/how-to-maintain-the-immersive-mode-in-dialogs
                        errorDialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);

                        //Show the dialog!
                        errorDialog.show();

                        //Set the dialog to immersive
                        errorDialog.getWindow().getDecorView().setSystemUiVisibility(
                                (getActivity()).getWindow().getDecorView().getSystemUiVisibility());

                        //Clear the not focusable flag from the window
                        errorDialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);

                    }
                    break;
                case POWER_ERROR:
                    Log.d("CREATE POWERERR","+++++++++");
                    if(errorDialog == null || !errorDialog.isShowing()) {
                        errorDialog = new AlertDialog.Builder(getActivity()).setTitle("Low Battery").setMessage("This is a complicated process. Please connect the device to a reliable source of power before proceeding. ").setPositiveButton("OK",
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog,
                                                        int which) {
                                        getThis().setState(PwnixSetupFragment.PwnixInstallState.NOTSTARTED);
                                        getThis().updateUI();

                                     //You shall not pass -- override handleUIState - called after
                                     ((SetupWizardActivity)getActivity()).enableButtonBar(false);


                                    }
                                }).setCancelable(false).create();
                        //Dialogs break immersive view - ty for the hack stackoverflow https://stackoverflow.com/questions/22794049/how-to-maintain-the-immersive-mode-in-dialogs
                        errorDialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);

                        //Show the dialog!
                        errorDialog.show();

                        //Set the dialog to immersive
                        errorDialog.getWindow().getDecorView().setSystemUiVisibility(
                                (getActivity()).getWindow().getDecorView().getSystemUiVisibility());

                        //Clear the not focusable flag from the window
                        errorDialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);

                    }
                    break;
                case VERIFICATION_ERROR:

                    if(!dismissed) {//because this is a state we will stay in we need this flag to not spam them
                        if (errorDialog == null || !errorDialog.isShowing()) {
                            Log.d("CREATE CONNERR", "+++++++++");

                            errorDialog = new AlertDialog.Builder(getActivity()).setTitle("Bundle Verification Failed").setMessage("The downloaded bundle did not pass verification. Restarting the download...").setPositiveButton("OK",
                                    new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface dialog,
                                                            int which) {
                                            // getThis().setState(PwnixSetupFragment.PwnixInstallState.NOTSTARTED);
                                            dismissed = true;

                                     //You shall not pass
                                     ((SetupWizardActivity)getActivity()).enableButtonBar(false);


                                            getThis().updateUI();//unneeded
                                        }
                                    }).setCancelable(false).create();
                            //Dialogs break immersive view - ty for the hack stackoverflow https://stackoverflow.com/questions/22794049/how-to-maintain-the-immersive-mode-in-dialogs
                            errorDialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);

                            //Show the dialog!
                            errorDialog.show();

                            //Set the dialog to immersive
                            errorDialog.getWindow().getDecorView().setSystemUiVisibility(
                                    (getActivity()).getWindow().getDecorView().getSystemUiVisibility());

                            //Clear the not focusable flag from the window
                            errorDialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);

                        }
                    }
                    break;
            }

        }

        //ew
        private PwnixSetupFragment getThis(){
            return this;
        }

        /**
         * handleUIState - handles all the steps in the install / calls methods for UI to react to PwnixInstallStates
         */
        private void handleUIState() {
            Log.d("STATE CHANGE:" + fragmentState.toString(), " HandleUIState:"+this.toString());

             //Default to lock enable when not started or done.
             ((SetupWizardActivity)getActivity()).enableButtonBar(false);


            switch(fragmentState) {
                case CONNECTION_ERROR:
                    handleUiNotStarted();
                    animationRan=false;
                    showAlert(PwnixInstallState.CONNECTION_ERROR);
                    break;
                case POWER_ERROR:
                    handleUiNotStarted();
                    animationRan=false;
                    showAlert(PwnixInstallState.POWER_ERROR);
                    break;
                //Edge Case - Error - bundle checksum not valid
                case VERIFICATION_ERROR:
                    updatePreviousSteps(2);
                    startStep(1);
                    step2Label.setTextColor(Color.RED);
                    step2Label.setText(R.string.step2error);
                    step2Imageview.setVisibility(View.VISIBLE);
                    step2Imageview.setImageDrawable(getResources().getDrawable(R.drawable.ic_check_black_24dp));
                    step2Label.setPadding(15,0,0,0);
                    step2PGB.setVisibility(View.GONE);
                    step2Imageview.setVisibility(View.GONE);
                    showDLProgress();

                    showAlert(PwnixInstallState.VERIFICATION_ERROR);


                    break;
                case NOTSTARTED: // step 0 - starting/default state
                    handleUiNotStarted();


                     //You shall not pass
                     ((SetupWizardActivity)getActivity()).enableButtonBar(true);
                     //cant go forward only backwards if you havent started
                     ((SetupWizardActivity)getActivity()).enableNextButton(false);

                    break;
                case DOWNLOADING: // start step 1
                    startStep(1);
                    showDLProgress();

                    break;
                case DOWNLOADED: // finish step 1
                    setOverallProgressbar(38);
                    finishStep(1);
                    fragmentState = PwnixInstallState.VERIFYING;

                case VERIFYING: // start step 2
                    dismissed=false;
                    //auto dismiss
                    if(errorDialog != null &&
                            errorDialog.isShowing()) {
                        errorDialog.dismiss();
                    }

                    setOverallProgressbar(40);
                    startStep(2);
                    break;
                case VERIFIED: // finish step 2
                    setOverallProgressbar(45);
                    finishStep(2);
                    fragmentState = PwnixInstallState.INSTALLING;

                case INSTALLING: // start step 3
                    setOverallProgressbar(50);
                    startStep(3);
                    break;
                case INSTALLED: // finish step 3
                    setOverallProgressbar(80);
                    finishStep(3);
                    fragmentState = PwnixInstallState.PREPARING;

                case PREPARING: // start step 5
                    setOverallProgressbar(85);
                    startStep(4);
                    break;
                case PREPARED: // finish step 5
                    setOverallProgressbar(90);
                    finishStep(4);
                    fragmentState = PwnixInstallState.REGISTERING;

                case REGISTERING: // start step 4
                    setOverallProgressbar(95);
                    startStep(5);
                    break;
                case REGISTERED: // finish step 4
                    setOverallProgressbar(98);
                    finishStep(5);
                    fragmentState = PwnixInstallState.SETTINGUP;

                case SETTINGUP: // start step 6
                    setOverallProgressbar(99);
                    startStep(6);
                    break;
                case SETUP: // finish step 6
                    setOverallProgressbar(100);
                    finishStep(6);

                    //step6Label.setTextColor(Color.GREEN); // wow that is bright nvm
                    step6Label.setText(R.string.step6past);
                    step6Label.invalidate();

                    doneImageview.setVisibility(View.VISIBLE);
                    doneImageview.setImageDrawable(getResources().getDrawable(R.drawable.ic_check_black_24dp));

                    overallProgressbar.setVisibility(View.GONE);
                    launchButton.setVisibility(View.VISIBLE);
                    launchButton.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            //your click is top priority I promise.
                        }
                    });
                    launchButton.setText(R.string.complete);
                    launchButton.setPadding(0,100,0,100);

                    overallProgressLabel.setVisibility(View.GONE);

                    //enjoy your freedom earthling
                    ((SetupWizardActivity)getActivity()).enableButtonBar(true);

                    //write to shared prefs because process is done they could leave and come back for a brief amount of time.
                    break;
            }
        }

        private void gatherUIElements() {

            dlProgress = (TextView) mRootView.findViewById(R.id.dlProgress);
            startIcon = (ImageView) mRootView.findViewById(R.id.beginIcon);
            fragmentBlurb = (TextView) mRootView.findViewById(R.id.fragmentBlurb);
            overallProgressbar = (ProgressBar) mRootView.findViewById(R.id.overallProgress);
            launchButton = (TextView) mRootView.findViewById(R.id.wikibutton);

            stepContainer = (ScrollView) mRootView.findViewById(R.id.scrollView);

            //row1 = (RelativeLayout) mRootView.findViewById(R.id.DownloadRow);
            step1Label = (TextView) mRootView.findViewById(R.id.step1);
            step1PGB = (ProgressBar) mRootView.findViewById(R.id.downloadProgress);
            step1Imageview = (ImageView) mRootView.findViewById(R.id.step1CheckMark);

            //row2 = (RelativeLayout) mRootView.findViewById(R.id.VerifyRow);
            step2Label = (TextView) mRootView.findViewById(R.id.step2);
            step2PGB = (ProgressBar) mRootView.findViewById(R.id.verifyProgress);
            step2Imageview = (ImageView) mRootView.findViewById(R.id.step2CheckMark);

            //row3 = (RelativeLayout) mRootView.findViewById(R.id.InstallRow);
            step3Label = (TextView) mRootView.findViewById(R.id.step3);
            step3PGB = (ProgressBar) mRootView.findViewById(R.id.installProgress);
            step3Imageview = (ImageView) mRootView.findViewById(R.id.step3CheckMark);

            //row4 = (RelativeLayout) mRootView.findViewById(R.id.RegisterRow);
            step4Label = (TextView) mRootView.findViewById(R.id.step4);
            step4PGB = (ProgressBar) mRootView.findViewById(R.id.registerProgress);
            step4Imageview = (ImageView) mRootView.findViewById(R.id.step4CheckMark);

            //row5 = (RelativeLayout) mRootView.findViewById(R.id.EnableRow);
            step5Label = (TextView) mRootView.findViewById(R.id.step5);
            step5PGB = (ProgressBar) mRootView.findViewById(R.id.enableAppsProgress);
            step5Imageview = (ImageView) mRootView.findViewById(R.id.step5CheckMark);

            //row6 = (RelativeLayout) mRootView.findViewById(R.id.HomescreenRow);
            step6Label = (TextView) mRootView.findViewById(R.id.step6);
            step6PGB = (ProgressBar) mRootView.findViewById(R.id.homescreenProgress);
            step6Imageview = (ImageView) mRootView.findViewById(R.id.step6CheckMark);
            doneImageview = (ImageView) mRootView.findViewById(R.id.doneImage);

            overallProgressLabel = (TextView) mRootView.findViewById(R.id.overallProgressLabel);

        }

        @Override
        protected int getLayoutResource() {
            return R.layout.setup_pwnix_services;
        }

        public static void setState(PwnixInstallState state){
            fragmentState = state;
        }

        public void updateUI(){
            if(!this.isDetached()) { // stop trying to do things when the fragment isnt attached
                handleUIState();
            }
        }

    }


    public static class MyReceiver extends WakefulBroadcastReceiver {

        private PwnixSetupFragment fragment;

        public void setCallback(PwnixSetupFragment f){
            fragment=f;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (fragment.isAdded()) { // make sure the fragment is attached before doing anything
                Bundle extras = intent.getExtras();
                String words = extras.getString("stage");
                if (words != null) {
                    Toast.makeText(context, "Received: " + words, Toast.LENGTH_SHORT).show();

                }

                int progress = extras.getInt("progress");

                // update progress
                if (progress < 100 && progress >= 0) {
                    fragment.updateDLProgress(progress);
                }

                try {
                    if (words != null) {
                        PwnixSetupFragment.PwnixInstallState replyState = PwnixSetupFragment.PwnixInstallState.valueOf(words.toUpperCase());

                        if(fragment.fragmentState == PwnixSetupFragment.PwnixInstallState.VERIFICATION_ERROR && replyState== PwnixSetupFragment.PwnixInstallState.DOWNLOADING){
                            return; // ignore
                        }
                        fragment.setState(replyState);
                        fragment.updateUI();
                    }
                } catch (java.lang.IllegalArgumentException e) {
                    //the intent extra contains a string that is not a PwnixInstallState
                }
            }
        }
    }

}
