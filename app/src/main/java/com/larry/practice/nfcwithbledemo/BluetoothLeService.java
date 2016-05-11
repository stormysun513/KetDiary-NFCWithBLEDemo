/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.larry.practice.nfcwithbledemo;

import android.annotation.SuppressLint;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;

import java.util.List;
import java.util.UUID;

/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */

@SuppressLint("NewApi")
public class BluetoothLeService extends Service {
    private final static String TAG = "BluetoothLeService";

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    private BluetoothGatt mBluetoothGatt;
    private BluetoothGattCharacteristic mWriteCharacteristic;
    private int mConnectionState = STATE_DISCONNECTED;
    private DataParserThread mDataParserThread;
    private WriteFileThread mWriteFileThread;
    private String mId;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    public final static String ACTION_GATT_CONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED =
            "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED";
    public final static String ACTION_DATA_AVAILABLE =
            "com.example.bluetooth.le.ACTION_DATA_AVAILABLE";
    public final static String ACTION_DATA_WRITE_SUCCESS =
            "com.example.bluetooth.le.ACTION_DATA_WRITE_SUCCESS";
    public final static String ACTION_DATA_WRITE_FAIL =
            "com.example.bluetooth.le.ACTION_DATA_WRITE_FAIL";
    public final static String EXTRA_DATA =
            "com.example.bluetooth.le.EXTRA_DATA";

    private Handler mHandler;

    {
        mHandler = new Handler() {

            @Override
            public void handleMessage(Message msg) {

                switch (msg.what){
                    case DataParserThread.ACTION_IMAGE_HEADER_CHECKED:
                    {
                        byte[] command = new byte[]{BluetoothLE.BLE_REQUEST_IMAGE_BY_INDEX, (byte) 0x00};
                        if(mWriteCharacteristic != null){
                            mWriteCharacteristic.setValue(command);
                            writeCharacteristic(mWriteCharacteristic);
                            Log.d(TAG, "Received data header information.");
                        }
                        break;
                    }
                    case DataParserThread.ACTION_ACK_LOST_PACKETS:
                    {
                        byte[] command = msg.getData().getByteArray(DataParserThread.NOTIF_KEY);
                        if(mWriteCharacteristic != null){
                            mWriteCharacteristic.setValue(command);
                            writeCharacteristic(mWriteCharacteristic);
                        }
                        break;
                    }
                    case DataParserThread.ACTION_IMAGE_RECEIVED_SUCCESS:
                    {
                        byte[] data = msg.getData().getByteArray(DataParserThread.NOTIF_KEY);
                        final Intent intent = new Intent(BluetoothLE.DATA_TRANSFER_SUCCESS);
                        sendBroadcast(intent);

                        mWriteFileThread = new WriteFileThread(BluetoothLeService.this, mId, data);
                        mWriteFileThread.start();
                        break;
                    }
                    case DataParserThread.ACTION_IMAGE_RECEIVED_FAILURE:
                    {
                        float dropout = msg.getData().getFloat(DataParserThread.NOTIF_KEY);
                        final Intent intent = new Intent(BluetoothLE.DATA_TRANSFER_FAILURE);
                        intent.putExtra(EXTRA_DATA, dropout);
                        sendBroadcast(intent);

                        String buffer = "Dropout: " + String.format("%.1f", 100*dropout) + "%";
                        mWriteFileThread = new WriteFileThread(BluetoothLeService.this, mId, buffer.getBytes());
                        mWriteFileThread.start();
                        break;
                    }
                }
            }
        };
    }

    // Implements callback methods for GATT events that the app cares about.  For example,
    // connection change and services discovered.
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

            String intentAction;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mConnectionState = STATE_CONNECTED;
                Log.d(TAG, "Connected to GATT server.");

                // Notify connected event
                intentAction = ACTION_GATT_CONNECTED;
                broadcastUpdate(intentAction);

                // Attempts to discover services after successful connection.
                Log.d(TAG, "Attempting to start service discovery:" +
                        mBluetoothGatt.discoverServices());

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mConnectionState = STATE_DISCONNECTED;
                Log.d(TAG, "Disconnected from GATT server.");

                // Notify disconnected event
                intentAction = ACTION_GATT_DISCONNECTED;
                broadcastUpdate(intentAction);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                List<BluetoothGattService> gattServices = getSupportedGattServices();
                if(gattServices.size() >= 3)
                    mWriteCharacteristic =
                            gattServices.get(2)
                            .getCharacteristic(BluetoothLE.SERVICE3_WRITE_CHAR_UUID);

                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
            } else {
                Log.d(TAG, "onServicesDiscovered received: " + status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
        	
            broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
        }

        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_DATA_WRITE_SUCCESS, characteristic);
            }
            else {
                broadcastUpdate(ACTION_DATA_WRITE_FAIL, characteristic);
            }
        }
    };

    private void broadcastUpdate(final String action) {
    	
        final Intent intent = new Intent(action);
        sendBroadcast(intent);
    }

    private void broadcastUpdate(final String action,
                                 final BluetoothGattCharacteristic characteristic) {

        UUID uuid = characteristic.getUuid();
        final byte[] data = characteristic.getValue();

        if(uuid.compareTo(BluetoothLE.SERVICE3_NOTIFICATION_CHAR_UUID) == 0){

            if(data != null && data.length > 0){

                switch (data[0]){
                    case BluetoothLE.BLE_REPLY_IMAGE_INFO:
                    {
                        Handler handler = mDataParserThread.getDataParserHandler();
                        if(handler != null){
                            Message msg = handler.obtainMessage();
                            Bundle bundle = new Bundle();
                            bundle.putByteArray(DataParserThread.EXTRA_DATA_KEY, data);
                            msg.setData(bundle);
                            msg.what = DataParserThread.EXTRA_HEADER_DATA;
                            handler.sendMessage(msg);
                        }
                        else{
                            Log.d(TAG, "Error happened when getting the handler from parser.");
                        }
                        break;
                    }
                    case BluetoothLE.BLE_REPLY_IMAGE_PACKET:
                    {
                        if(mDataParserThread != null){
                            Handler handler = mDataParserThread.getDataParserHandler();
                            Message msg = handler.obtainMessage();
                            Bundle bundle = new Bundle();
                            bundle.putByteArray(DataParserThread.EXTRA_DATA_KEY, data);
                            msg.setData(bundle);
                            msg.what = DataParserThread.EXTRA_IMAGE_DATA;
                            handler.sendMessage(msg);
                        }
                        break;
                    }
                    default:
                    {
                        Log.i(TAG, "Other headers.");
                        final Intent intent = new Intent(action);

                        // By default,  data are written in HEX format.
                        intent.putExtra(EXTRA_DATA, data);
                        sendBroadcast(intent);
                        break;
                    }
                }
            }
        }
        else if(uuid.compareTo(BluetoothLE.SERVICE3_WRITE_CHAR_UUID) == 0){
            if(data != null && data.length > 0){

                switch (data[0]){
                    case BluetoothLE.BLE_REQUEST_IMAGE_INFO:
                    {
                        mDataParserThread = new DataParserThread(BluetoothLeService.this, "WorkingThread", mHandler);
                        mDataParserThread.start();
                        break;
                    }
                    default:
                        break;
                }
            }
        }
    }

    private final IBinder mBinder = new LocalBinder();

    public class LocalBinder extends Binder {
        BluetoothLeService getService() {
            return BluetoothLeService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // After using a given device, you should make sure that BluetoothGatt.close() is called
        // such that resources are cleaned up properly.  In this particular example, close() is
        // invoked when the UI is disconnected from the Service.
        close();
        return super.onUnbind(intent);
    }

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    public boolean initialize() {
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }

        return true;
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     *
     * @return Return true if the connection is initiated successfully. The connection result
     *         is reported asynchronously through the
     *         {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     *         callback.
     */
    public boolean connect(final String address) {
        if (mBluetoothAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        // Previously connected device.  Try to reconnect.
        if (mBluetoothDeviceAddress != null && address.equals(mBluetoothDeviceAddress)
                && mBluetoothGatt != null) {
            Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
            if (mBluetoothGatt.connect()) {
                mConnectionState = STATE_CONNECTING;
                return true;
            } else {
                return false;
            }
        }

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.");
            return false;
        }

        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
        Log.d(TAG, "Trying to create a new connection.");
        mBluetoothDeviceAddress = address;
        mConnectionState = STATE_CONNECTING;
        return true;
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnect() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.disconnect();
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public void close() {
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    /**
     * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
     * asynchronously through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
     * callback.
     *
     * @param characteristic The characteristic to read from.
     */
    public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(characteristic);
    }

    public void writeCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.writeCharacteristic(characteristic);
    }

    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param characteristic Characteristic to act on.
     * @param enabled If true, enable notification.  False otherwise.
     */
    public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic,
                                              boolean enabled) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.setCharacteristicNotification(characteristic, enabled);
    }

    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after {@code BluetoothGatt#discoverServices()} completes successfully.
     *
     * @return A {@code List} of supported services.
     */
    public List<BluetoothGattService> getSupportedGattServices() {
        if (mBluetoothGatt == null) return null;

        return mBluetoothGatt.getServices();
    }

    public boolean writeDescriptor (BluetoothGattDescriptor descriptor){
        if (mBluetoothGatt == null)
            return false;
        return mBluetoothGatt.writeDescriptor(descriptor);
    }

    public void setId(String id){
        mId = id;
    }
}
