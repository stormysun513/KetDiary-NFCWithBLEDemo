package com.larry.practice.nfcwithbledemo;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

@SuppressLint("NewApi")
public class BluetoothLE {
	private static final String TAG = "BluetoothLE";

    private static final UUID SERVICE3_UUID = UUID.fromString("713D0000-503E-4C75-BA94-3148F18D941E");
    public static final UUID SERVICE3_WRITE_CHAR_UUID = UUID.fromString("713D0003-503E-4C75-BA94-3148F18D941E");
    public static final UUID SERVICE3_NOTIFICATION_CHAR_UUID = UUID.fromString("713D0002-503E-4C75-BA94-3148F18D941E");

    public final static String UPDATE_TRANSFER_INFORMATION =
            "com.larry.practice.nfcwithbledemo.UPDATE_TRANSFER_INFORMATION";
    public final static String DATA_TRANSFER_FAILURE =
            "com.larry.practice.nfcwithbledemo.DATA_TRANSFER_FAILURE";
    public final static String DATA_TRANSFER_SUCCESS =
            "com.larry.practice.nfcwithbledemo.DATA_TRANSFER_SUCCESS";
    public final static String DATA_SAVED =
            "com.larry.practice.nfcwithbledemo.DATA_SAVED";

    public final static byte BLE_REQUEST_IMAGE_INFO = (byte)0x03;
    public final static byte BLE_REQUEST_IMAGE_BY_INDEX = (byte)0x04;
    public final static byte BLE_END_IMAGE_TRANSFER = (byte)0x05;
    public final static byte BLE_SHUTDOWN = (byte)0x07;
    public final static byte BLE_DEVICE_INFO = (byte)0x0A;

    public final static byte BLE_REPLY_IMAGE_INFO = (byte)0x00;
    public final static byte BLE_REPLY_IMAGE_PACKET = (byte)0x03;
    public final static byte BLE_REPLY_DEVICE_INFO = (byte)0x04;

	// Intent request codes
    private static final int REQUEST_ENABLE_BT = 2;
	
	private Activity activity = null;
    private String mId = null;
    private String mDeviceAddress = null;

	private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeService mBluetoothLeService = null;
    private boolean mConnected = false;
    private boolean mBLEScanning = false;
    private boolean mManualDisconnect = false;
    private boolean mReconnection = false;

    private BluetoothGattCharacteristic mNotifyCharacteristic;
    private BluetoothGattCharacteristic mWriteCharacteristic;

    private Handler mHandler;
    private Runnable mScanDeviceRunnable;
    private Runnable mWaitScanRunnable;
    private SortedMap sortedMap;

    // Stops scanning after 15 seconds.
    private static final int SCAN_PERIOD = 10000;
    private static final int WAIT_PERIOD = 1000;
    private static final int COMMAND_DALAY_PERIOD = 500;

    public BluetoothLE(Activity activity, String deviceName) {

        Log.i(TAG, "Initialize BLE Service.");

        mHandler = new Handler();
        this.activity = activity;
        this.mId = deviceName;

        // Use this check to determine whether BLE is supported on the device.  Then you can
        // selectively disable BLE-related features.
        if (!activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            ((BluetoothListener) activity).bleNotSupported();
        }

        // Initializes a Bluetooth adapter.  For API level 18 and above, get a reference to
        // BluetoothAdapter through BluetoothManager.
        final BluetoothManager bluetoothManager =
                (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();

        // Checks if Bluetooth is supported on the device.
        if (mBluetoothAdapter == null)
            ((BluetoothListener) activity).bleNotSupported();
    }
     
	// Code to manage Service lifecycle.
	private final ServiceConnection mBluetoothLeServiceConnection = new ServiceConnection() {
        
	    @Override
	    public void onServiceConnected(ComponentName componentName, IBinder service) {
	        mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();

	        if (!mBluetoothLeService.initialize()) {
	            Log.e(TAG, "Unable to initialize Bluetooth Service.");
//	            activity.finish();
	        }
            else{
	            // Automatically connects to the device upon successful start-up initialization.
                mBluetoothLeService.setId(mId);
	            mBluetoothLeService.connect(mDeviceAddress);
            }
	    }
	
	    @Override
	    public void onServiceDisconnected(ComponentName componentName) {
            unbindBLEService();
            mBluetoothLeService = null;
	    }
	};

    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mConnected = true;
                mReconnection = false;
                mManualDisconnect = false;

                //Terminate the BLE connection timeout (10sec)
                mHandler.removeCallbacks(mScanDeviceRunnable);
                mHandler.removeCallbacks(mWaitScanRunnable);
                mBLEScanning = false;
                ((BluetoothListener) activity).bleConnected();

            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                mConnected = false;
                unbindBLEService();
                if(mManualDisconnect){

                    // Disconnection callback
                    ((BluetoothListener) activity).bleDisconnected();
                }
                else{
                    Log.d(TAG, "Reconnection started.");
                    mReconnection = true;
                    bleConnect();
                }

            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {

                // Show all the supported services and characteristics on the user interface.
            	List<BluetoothGattService> gattServices = mBluetoothLeService.getSupportedGattServices();
                int serviceSize = gattServices.size();

                if(serviceSize >= 3){
            	    mNotifyCharacteristic = gattServices.get(2).getCharacteristic(SERVICE3_NOTIFICATION_CHAR_UUID);
            	    mBluetoothLeService.setCharacteristicNotification(mNotifyCharacteristic, true);

                    /** Enable GATT notification **/
                    // 0x2902 org.bluetooth.descriptor.gatt.client_characteristic_configuration.xml
                    UUID uuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
                    BluetoothGattDescriptor descriptor = mNotifyCharacteristic.getDescriptor(uuid);
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    mBluetoothLeService.writeDescriptor(descriptor);
                    /** IMPORTANT added by Larry **/
                
                    mWriteCharacteristic = gattServices.get(2).getCharacteristic(SERVICE3_WRITE_CHAR_UUID);
                    Log.d(TAG, "BLE ACTION_GATT_SERVICES_DISCOVERED");

                    mHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            bleGetFlashData();
                        }
                    }, COMMAND_DALAY_PERIOD);
                }

            } else if (BluetoothLeService.ACTION_DATA_WRITE_SUCCESS.equals(action)) {
                ((BluetoothListener) activity).bleWriteCharacteristicSuccess();

            } else if (BluetoothLeService.ACTION_DATA_WRITE_FAIL.equals(action)) {
                ((BluetoothListener) activity).bleWriteCharacteristicFail();

            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
            	byte[] data = intent.getByteArrayExtra(BluetoothLeService.EXTRA_DATA);
                // DEBUG section
//            	StringBuffer stringBuffer = new StringBuffer("");
//            	for(int ii=0;ii<data.length;ii++){
//                    String s1 = String.format("%2s", Integer.toHexString(data[ii] & 0xFF)).replace(' ', '0');
//                    if( ii != data.length-1)
//                        stringBuffer.append(s1 + ":");
//                    else
//                        stringBuffer.append(s1);
//            	}
//            	Log.i(TAG, stringBuffer.toString());
                // End of DEBUG section

            } else if(UPDATE_TRANSFER_INFORMATION.equals(action)){
                float progress = intent.getFloatExtra(BluetoothLeService.EXTRA_DATA, -1);
                ((BluetoothListener) activity).bleUpdateTransferInfo(progress);
            } else if(DATA_TRANSFER_FAILURE.equals(action)){
                float dropout = intent.getFloatExtra(BluetoothLeService.EXTRA_DATA, -1);
                ((BluetoothListener) activity).bleGetDataFailure(dropout);
            } else if(DATA_TRANSFER_SUCCESS.equals(action)){
                ((BluetoothListener) activity).bleGetDataSuccess();
            } else if(DATA_SAVED.equals(action)){
                ((BluetoothListener) activity).bleSaveDataCompleted();
            } else{
                Log.d(TAG, "----BLE Can't handle data----");
            }

        }
    };

    private void unbindBLEService() {
        activity.unbindService(mBluetoothLeServiceConnection);
        activity.unregisterReceiver(mGattUpdateReceiver);
    }

	public void bleConnect() {
		
		// Ensures Bluetooth is enabled on the device.  If Bluetooth is not currently enabled,
        // fire an intent to display a dialog asking the user to grant permission to enable it.
        if (!mBluetoothAdapter.isEnabled()) {
            if (!mBluetoothAdapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                activity.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            }
        }
        else {
            if(mBLEScanning){
                Log.d(TAG, "Stop previous handler");
                mHandler.removeCallbacks(mScanDeviceRunnable);
                mHandler.removeCallbacks(mWaitScanRunnable);
                mBluetoothAdapter.stopLeScan(mLeScanCallback);
                mBLEScanning = false;
            }

            if(!mConnected)
                bleScan();
        }
	}

    private void bleWriteCharacteristic1(byte [] data) {

        if((mBluetoothLeService != null) && (mWriteCharacteristic != null)) {
            mWriteCharacteristic.setValue(data);
            mBluetoothLeService.writeCharacteristic(mWriteCharacteristic);
        }
    }

    public void bleGetFlashData(){
        byte [] command = new byte[] {BluetoothLE.BLE_REQUEST_IMAGE_INFO};
        bleWriteCharacteristic1(command);
    }


    public void bleSelfDisconnection(){

        // Set manual disconnect flag
        mManualDisconnect = true;

        if(mBLEScanning) {
            //Terminate the BLE connection timeout (10sec)
            mHandler.removeCallbacks(mScanDeviceRunnable);
            mHandler.removeCallbacks(mWaitScanRunnable);
            mBluetoothAdapter.stopLeScan(mLeScanCallback);
            mBLEScanning = false;
        }

        if(mConnected) {
            mBluetoothLeService.disconnect();
        }
    }

    /* Timeout implementation */
    private void bleScan() {
        mHandler.postDelayed(mWaitScanRunnable = new Runnable() {

            @Override
            public void run() {
                connectToClosestDevice();
                if(mBLEScanning){
                    mHandler.removeCallbacks(mWaitScanRunnable);
                    mHandler.postDelayed(mWaitScanRunnable, WAIT_PERIOD);
                }

            }
        }, WAIT_PERIOD);

        mHandler.postDelayed(mScanDeviceRunnable = new Runnable() {

            @Override
            public void run() {
                mBLEScanning = false;
                mBluetoothAdapter.stopLeScan(mLeScanCallback);
                if(!mReconnection){
                    ((BluetoothListener) activity).bleConnectionTimeout();
                }
                else{
                    mReconnection = false;
                    ((BluetoothListener) activity).bleDisconnected();
                    Log.d(TAG, "Still cannot reconnect.");
                }
            }
        }, SCAN_PERIOD);

        Log.d(TAG, "BLE Scanning.");
        if(sortedMap == null)
            sortedMap = new TreeMap();
        sortedMap.clear();

        mBLEScanning = true;
        mBluetoothAdapter.startLeScan(mLeScanCallback);
    }

    private void connectToClosestDevice(){

        // Check the closest device nearby
        Log.i(TAG, "Unsorted map......");
        Set<Map.Entry<Integer, BluetoothDevice>> set = sortedMap.entrySet();

        for(Iterator<Map.Entry<Integer, BluetoothDevice>> iter = set.iterator(); iter.hasNext();){
            Map.Entry<Integer, BluetoothDevice> entry = iter.next();
            Integer rssi = entry.getKey();
            BluetoothDevice device = entry.getValue();
            Log.i(TAG, "# " + rssi);

            if(!iter.hasNext()){
                mDeviceAddress = device.getAddress();

                Intent gattServiceIntent = new Intent(activity, BluetoothLeService.class);
                activity.bindService(gattServiceIntent, mBluetoothLeServiceConnection, Context.BIND_AUTO_CREATE);
                activity.registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
                mBluetoothAdapter.stopLeScan(mLeScanCallback);
            }
        }
    }
	
	private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        /* BluetoothLeService */
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_WRITE_SUCCESS);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_WRITE_FAIL);

        /* Self-defined action */
        intentFilter.addAction(UPDATE_TRANSFER_INFORMATION);
        intentFilter.addAction(DATA_TRANSFER_FAILURE);
        intentFilter.addAction(DATA_TRANSFER_SUCCESS);
        intentFilter.addAction(DATA_SAVED);

        return intentFilter;
    }

	public void onBLEActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
            case REQUEST_ENABLE_BT:{
        	    // When the request to enable Bluetooth returns
                if (resultCode == Activity.RESULT_OK) {
                    // Bluetooth is now enabled, enable BLE scan
                    bleScan();
                } else{
                    // User did not enable Bluetooth or an error occurred
                    Toast.makeText(activity, "Bluetooth did not enable!", Toast.LENGTH_SHORT).show();
//                activity.finish();
                }
        	    break;
            }
		}
	}

    @SuppressWarnings("unchecked")
    // Device scan callback.
    private BluetoothAdapter.LeScanCallback mLeScanCallback =
            new BluetoothAdapter.LeScanCallback() {

                @Override
                public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
                    // Do nothing if target device is scanned
                    if(mConnected)
                        return;

                    String scannedName = device.getName();
                    BLEAdvertisedData badData = new BLEUtil().parseAdvertisedData(scanRecord);
                    List<UUID> uuids = badData.getUuids();

                    if(device.getName() == null){
                        scannedName = badData.getName();
                    }
                    Log.d(TAG, "Device = " + scannedName + ", Address = " + device.getAddress() +
                            ", RSSI = " + rssi);

                    // Check whether provides SERVICE
                    for(UUID uuid : uuids) {
                        if(SERVICE3_UUID.equals(uuid))
                            sortedMap.put(rssi, device);
                    }
                }
            };

    private final class BLEUtil {

        public BLEAdvertisedData parseAdvertisedData(byte[] advertisedData) {
            List<UUID> Uuids = new ArrayList<UUID>();
            String name = null;
            if( advertisedData == null ){
                return new BLEAdvertisedData(Uuids, name);
            }

            ByteBuffer buffer = ByteBuffer.wrap(advertisedData).order(ByteOrder.LITTLE_ENDIAN);
            while (buffer.remaining() > 2) {
                byte length = buffer.get();
                if (length == 0)
                    break;

                byte type = buffer.get();
                switch (type) {
                    case 0x02: // Partial list of 16-bit UUIDs
                    case 0x03: // Complete list of 16-bit UUIDs
                        while (length >= 2) {
                            Uuids.add(UUID.fromString(String.format(
                                    "%08x-0000-1000-8000-00805f9b34fb", buffer.getShort())));
                            length -= 2;
                        }
                        break;
                    case 0x06: // Partial list of 128-bit UUIDs
                    case 0x07: // Complete list of 128-bit UUIDs
                        while (length >= 16) {
                            long lsb = buffer.getLong();
                            long msb = buffer.getLong();
                            Uuids.add(new UUID(msb, lsb));
                            length -= 16;
                        }
                        break;
                    case 0x09:
                        byte[] nameBytes = new byte[length-1];
                        buffer.get(nameBytes);
                        try {
                            name = new String(nameBytes, "utf-8");
                        } catch (UnsupportedEncodingException e) {
                            e.printStackTrace();
                        }
                        break;
                    default:
                        buffer.position(buffer.position() + length - 1);
                        break;
                }
            }
            return new BLEAdvertisedData(Uuids, name);
        }
    }

    private class BLEAdvertisedData {
        private List<UUID> mUuids;
        private String mName;

        public BLEAdvertisedData(List<UUID> uuids, String name){
            mUuids = uuids;
            mName = name;
        }
        public List<UUID> getUuids(){return mUuids;}
        public String getName(){
            return mName;
        }
    }
}

