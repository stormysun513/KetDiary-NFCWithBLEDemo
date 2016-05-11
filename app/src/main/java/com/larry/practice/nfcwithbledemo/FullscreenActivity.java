package com.larry.practice.nfcwithbledemo;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.os.Parcelable;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */
public class FullscreenActivity extends AppCompatActivity implements BluetoothListener {

    private static final String TAG = "BluetoothLE";

    /**
     * Some older devices needs a small delay between UI widget updates
     * and a change of the status and navigation bar.
     */
    private static final int UI_ANIMATION_DELAY = 100;

    private BluetoothLE bluetoothLE = null;

    private View mBaseLayoutView;
    private View mContentView;
    private View mControlsView;
    private TextView mMainContentTextView;
    private TextView mInfoContentTextView;
    private NfcAdapter mNfcAdapter;
    private boolean mVisible;
    private boolean mTransferCompleted;

    private final Handler mHideHandler = new Handler();
    private final Runnable mHidePart2Runnable = new Runnable() {
        @SuppressLint("InlinedApi")
        @Override
        public void run() {
            // Delayed removal of status and navigation bar

            // Note that some of these constants are new as of API 16 (Jelly Bean)
            // and API 19 (KitKat). It is safe to use them, as they are inlined
            // at compile-time and do nothing on earlier devices.
            mBaseLayoutView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    };
    private final Runnable mShowPart2Runnable = new Runnable() {
        @Override
        public void run() {
            mControlsView.setVisibility(View.VISIBLE);
        }
    };
    private final Runnable mHideRunnable = new Runnable() {
        @Override
        public void run() {
            hide();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }

        setContentView(R.layout.activity_fullscreen);

        mVisible = true;
        mBaseLayoutView = findViewById(R.id.fullscreen_base_layout);
        mContentView = findViewById(R.id.fullscreen_content);
        mControlsView = findViewById(R.id.fullscreen_content_controls);
        mMainContentTextView = (TextView)findViewById(R.id.main_content);
        mInfoContentTextView = (TextView)findViewById(R.id.info_content);

        mNfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (mNfcAdapter == null){
            Log.d(TAG, "This phone is not NFC enabled.");
        }

        // Set up the user interaction to manually show or hide the system UI.
        mContentView.setOnLongClickListener(new View.OnLongClickListener(){

            @Override
            public boolean onLongClick(View view) {
                toggle();
                return true;
            }
        });

//        mContentView.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View view) {
//                toggle();
//            }
//        });

        Log.d(TAG, "OnCreated.");
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        // Trigger the initial hide() shortly after the activity has been
        // created, to briefly hint to the user that UI controls
        // are available.
        delayedHide(100);
    }

    @Override
    protected void onResume() {
        super.onResume();

//        Log.i(TAG, "OnResume: " + getIntent().getAction());
        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(getIntent().getAction())) {
            processIntent(getIntent());
        }

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnectFromBLEDevice();
    }

    @Override
    public void onNewIntent(Intent intent) {

//        Log.i(TAG, "onNewIntent: " + NfcAdapter.ACTION_NDEF_DISCOVERED);
        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction())) {
            processIntent(intent);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(bluetoothLE != null)
            bluetoothLE.onBLEActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void bleNotSupported() {
        Log.i(TAG, "[BLE Callback]: BluetoothLE not supported.");
        bluetoothLE = null;
    }

    @Override
    public void bleConnectionTimeout() {
        Log.i(TAG, "[BLE Callback]: Connection timeout.");
        mInfoContentTextView.setText(R.string.timeout);
        bluetoothLE = null;
    }

    @Override
    public void bleConnected() {
        Log.i(TAG, "[BLE Callback]: Successfully connected.");
        mInfoContentTextView.setText(R.string.connected);
        mTransferCompleted = false;
        mBaseLayoutView.setBackgroundColor(Color.parseColor("#aa0055"));
    }

    @Override
    public void bleDisconnected() {
        Log.i(TAG, "[BLE Callback]: Disconnected.");
        if(!mTransferCompleted)
            mInfoContentTextView.setText(R.string.disconnected);
    }

    @Override
    public void bleWriteCharacteristicSuccess() {
        Log.i(TAG, "[BLE Callback]: Write successfully.");
    }

    @Override
    public void bleWriteCharacteristicFail() {
        Log.i(TAG, "[BLE Callback]: Failed to write.");
    }

    @Override
    public void bleGetDataSuccess() {
        Log.i(TAG, "Received data successfully.");
        mInfoContentTextView.setText(R.string.completed);
        mTransferCompleted = true;
        mBaseLayoutView.setBackgroundColor(Color.parseColor("#0099cc"));
//        disconnectFromBLEDevice();
    }

    @Override
    public void bleGetDataFailure(float dropout) {
        String buffer = "Dropout: " + String.format("%.1f", 100* dropout) + "%";
        Log.i(TAG, buffer);
        mInfoContentTextView.setText(buffer);
    }

    @Override
    public void bleUpdateTransferInfo(float progress) {
        String buffer = "Progress: " + String.format("%.1f", 100* progress) + "%";
        mInfoContentTextView.setText(buffer);
    }

    @Override
    public void bleSaveDataCompleted() {
        Log.i(TAG, "File saved.");
        if(mTransferCompleted)
            disconnectFromBLEDevice();
        else
            bluetoothLE.bleGetFlashData();

    }

    private void toggle() {
        if (mVisible) {
            hide();
            disconnectFromBLEDevice();
            mBaseLayoutView.setBackgroundColor(Color.parseColor("#aa0055"));
        } else {
            show();
//            if(bluetoothLE != null)
//                bluetoothLE.bleGetFlashData();
        }
    }

    private void hide() {
        // Hide UI first
        mControlsView.setVisibility(View.GONE);
        mVisible = false;

        // Schedule a runnable to remove the status and navigation bar after a delay
        mHideHandler.removeCallbacks(mShowPart2Runnable);
        mHideHandler.postDelayed(mHidePart2Runnable, UI_ANIMATION_DELAY);
    }

    @SuppressLint("InlinedApi")
    private void show() {
        // Show the system bar
        mBaseLayoutView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        mVisible = true;

        // Schedule a runnable to display UI elements after a delay
        mHideHandler.removeCallbacks(mHidePart2Runnable);
        mHideHandler.postDelayed(mShowPart2Runnable, UI_ANIMATION_DELAY);
    }

    /**
     * Schedules a call to hide() in [delay] milliseconds, canceling any
     * previously scheduled calls.
     */
    private void delayedHide(int delayMillis) {
        mHideHandler.removeCallbacks(mHideRunnable);
        mHideHandler.postDelayed(mHideRunnable, delayMillis);
    }

    /**
     * Parses the NDEF Message from the intent and prints to the TextView
     */
    void processIntent(Intent intent) {

        Parcelable[] rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
        NdefMessage[] msgs;

        // Get messages sent by tags
        if (rawMsgs != null) {
            msgs = new NdefMessage[rawMsgs.length];
            for (int i = 0; i < rawMsgs.length; i++) {
                msgs[i] = (NdefMessage) rawMsgs[i];
            }
            if(msgs.length != 0) {
                mMainContentTextView.setText(new String(msgs[0].getRecords()[0].getPayload()));
            }
            connectToBLEDevice("DUMMY_NAME");
        }
    }

    public void connectToBLEDevice(String deviceName){
        if(bluetoothLE == null){
            bluetoothLE = new BluetoothLE(FullscreenActivity.this, deviceName);
        }
        bluetoothLE.bleConnect();
    }

    public void disconnectFromBLEDevice(){
        if(bluetoothLE != null)
            bluetoothLE.bleSelfDisconnection();
    }
}
