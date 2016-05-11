package com.larry.practice.nfcwithbledemo;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * Created by larry on 4/13/16.
 */
public class DataParserThread extends Thread {

    private final static String TAG = "DataParserThread";

    /* Data types */
    public static final int EXTRA_HEADER_DATA                   = 0;
    public static final int EXTRA_IMAGE_DATA                    = 1;

    /* Action types */
    public static final int ACTION_IMAGE_HEADER_CHECKED         = 0;
    public static final int ACTION_ACK_LOST_PACKETS             = 1;
    public static final int ACTION_IMAGE_RECEIVED_SUCCESS       = 2;
    public static final int ACTION_IMAGE_RECEIVED_FAILURE       = 3;

    public final static String EXTRA_DATA_KEY = "PARSER";
    public final static String NOTIF_KEY = "NOTI";

    /* Variables for timer. */
    private static final int TIMEOUT_PERIOD = 300;
    private static final int MAX_TIMEOUT_TIME = 3;
    private static final int MAX_RETRANSMIT_TIME = 5;
    private int timerCounter = 0;
    private int retransmitTime = 0;

    /* Constants for data transmission. */
    private final static int PACKET_SIZE = 111;
    private final static int MAXIMUM_PACKET_NUM = 500;
    private final static int BLE_PACKET_SIZE = 20;

    /* Variables for handling transfer protocol. */
    private int recvNum = 0;
    private int lastRecvNum = recvNum;
    private int packetNum = 0;
    private int lastPacketSize = 0;
    private byte [][] dataBuf = null;
    private byte [] tempBuf = null;
    private int currentPacketId = 0;
    private boolean isLastPackets = false;
    private int targetPacketSize = 0;
    private int bufOffset = 0;
    private boolean isVerifySeqNum = false;
    private Set<Integer> recvPacketIdTable;

    public DataParserHandler mDataParserHandler;
    private Context mContext;
    private Handler mMainHandler;

    /* Performance evaluation */
    private int totalRetransPkts = 0;

    public DataParserThread(Context context, String mName, Handler handler) {
        super(mName);
        mContext = context;
        mMainHandler = handler;
    }

    @Override
    public void run(){
        Looper.prepare();
        mDataParserHandler = new DataParserHandler(Looper.myLooper());
        Looper.loop();
    }

    public Handler getDataParserHandler(){
        return mDataParserHandler;
    }

    private Runnable checkTimeoutRunnable = new Runnable() {
        public void run() {
            timerCounter++;
            if(lastRecvNum != recvNum) {
                timerCounter = 0;
                retransmitTime = 0;
            }

            // Current time log
//            Log.d(TAG, "Time: " + new Date().toString() + ", Counter: " + String.valueOf(timerCounter));
            if(timerCounter >= MAX_TIMEOUT_TIME){
                retransmitTime++;
                if(retransmitTime >= MAX_RETRANSMIT_TIME){

                    final Intent intent = new Intent(BluetoothLE.DATA_TRANSFER_FAILURE);
                    intent.putExtra(BluetoothLeService.EXTRA_DATA, (float)recvNum/packetNum);
                    mContext.sendBroadcast(intent);


                    Looper mLooper = Looper.myLooper();
                    if(mLooper != null) {
                        Log.i(TAG, "WorkingThread terminated.");
                        mLooper.quit();
                    }
                    return;
                }
                else{
                    if(recvNum == 0){
                        Message rtnMsg = mMainHandler.obtainMessage();
                        rtnMsg.what = ACTION_IMAGE_HEADER_CHECKED;
                        mMainHandler.sendMessage(rtnMsg);
                    }
                    else if(recvNum < packetNum){
                        byte [] retransmitData = getRetransmitIndices();

                        Message msg = mMainHandler.obtainMessage();
                        Bundle bundle = new Bundle();
                        bundle.putByteArray(NOTIF_KEY, retransmitData);
                        msg.setData(bundle);
                        msg.what = ACTION_ACK_LOST_PACKETS;
                        mMainHandler.sendMessage(msg);
                    }
                }
                timerCounter = 0;
            }
            else{
                // Passing constructed jpeg files back to BluetoothLE
                final Intent intent = new Intent(BluetoothLE.UPDATE_TRANSFER_INFORMATION);
                intent.putExtra(BluetoothLeService.EXTRA_DATA, (float)recvNum/packetNum);
                mContext.sendBroadcast(intent);
            }

            mDataParserHandler.postDelayed(this, TIMEOUT_PERIOD);
            lastRecvNum = recvNum;
        }
    };

    private void verifyPacketChecksum(int check){
        int checksum = 0;

        for(int i = 0; i < bufOffset; i++){
            checksum += (tempBuf[i] & 0xFF);
            checksum = checksum & 0xFF;
        }

        if (checksum == check){
            if(recvPacketIdTable.contains(currentPacketId)){
//                Log.d(TAG, "Already received packet index: " + String.valueOf(currentPacketId) + "/ " + String.valueOf(packetNum - 1));
            }
            else{
                dataBuf[currentPacketId] = new byte[bufOffset];
                System.arraycopy(tempBuf, 0, dataBuf[currentPacketId], 0, bufOffset);
                recvPacketIdTable.add(currentPacketId);
//                Log.d(TAG, "Receive packet index: " + String.valueOf(currentPacketId) + "/ " + String.valueOf(packetNum - 1));
                recvNum++;
            }
        }
        else{
            Log.i(TAG, "Checksum : " + String.format("%2s", Integer.toHexString(checksum).replace(' ', '0')) +
                    ", Check :" +String.format("%2s", Integer.toHexString(check).replace(' ', '0')));
        }
    }

    private void checkDataBuf() {
        Log.d(TAG, "Dropout rate: " + (float)(packetNum-recvNum)*100/packetNum + "%");
        if(recvNum >= packetNum){

            int currentIdx = 0;
            byte [] pictureBytes;

            if(lastPacketSize > 0)
                pictureBytes = new byte [(packetNum-1)*PACKET_SIZE+lastPacketSize];
            else
                pictureBytes = new byte [packetNum*PACKET_SIZE];

            int i = 0;
            try{
                for(i = 0; i < packetNum; i++) {
                    System.arraycopy(dataBuf[i], 0, pictureBytes, currentIdx, dataBuf[i].length);
                    currentIdx += dataBuf[i].length;
                }
            }
            catch (Exception e){
                Log.d(TAG, String.valueOf(i) + ", " + String.valueOf(dataBuf[i].length) + ", " + String.valueOf(pictureBytes.length));
                return;
            }

            // Passing constructed jpeg files back to BluetoothLE
            Message msg = mMainHandler.obtainMessage();
            Bundle bundle = new Bundle();
            bundle.putByteArray(NOTIF_KEY, pictureBytes);
            msg.setData(bundle);
            msg.what = ACTION_IMAGE_RECEIVED_SUCCESS;
            mMainHandler.sendMessage(msg);

            mMainHandler.removeCallbacks(checkTimeoutRunnable);
            Looper mLooper = Looper.myLooper();
            if(mLooper != null) {
                Log.i(TAG, "WorkingThread terminated.");
                mLooper.quit();
            }
        }
        else{
            byte [] retransmitData = getRetransmitIndices();

            Message msg = mMainHandler.obtainMessage();
            Bundle bundle = new Bundle();
            bundle.putByteArray(NOTIF_KEY, retransmitData);
            msg.setData(bundle);
            msg.what = ACTION_ACK_LOST_PACKETS;
            mMainHandler.sendMessage(msg);

            bufOffset = 0;
            timerCounter = 0;
        }
    }

    public byte[] getRetransmitIndices(){
        int remainPacketNum = packetNum - recvNum;
        if(remainPacketNum > 18)
            remainPacketNum = 18;

        totalRetransPkts += remainPacketNum;
        Log.d(TAG, "Request " + remainPacketNum + " packets.");

        byte [] bytes = new byte [remainPacketNum+2];
        bytes[0] = BluetoothLE.BLE_REQUEST_IMAGE_BY_INDEX;
        bytes[1] = (byte)(remainPacketNum & 0xFF);

        int i = 0;
        for(int j = 0; j < packetNum; j++){
            if(!recvPacketIdTable.contains(j)){
                if(i >= remainPacketNum)
                    break;
                bytes[i+2] = (byte)(j & 0xFF);
                i++;
            }
        }


        StringBuffer stringBuffer = new StringBuffer("");
        for(int ii = 2; ii < bytes.length; ii++){
            String s1 = String.format("%s", Integer.toString(bytes[ii] & 0xFF));
            if( ii != bytes.length-1)
                stringBuffer.append(s1 + ", ");
            else
                stringBuffer.append(s1);
        }
        Log.d(TAG, "Indices: " + stringBuffer.toString());
        return bytes;
    }

    public class DataParserHandler extends Handler {

        public DataParserHandler(Looper myLooper) {
            super(myLooper);
        }

        @Override
        public void handleMessage(Message msg) {

            // Act on the message
            byte [] data = msg.getData().getByteArray(EXTRA_DATA_KEY);
            if(data == null)
                return;

            switch (msg.what)
            {
                case EXTRA_HEADER_DATA:
                {
                    if(data.length >= 3 && data[0] == BluetoothLE.BLE_REPLY_IMAGE_INFO){
                        int picTotalLen = ((data[1] & 0xFF) << 8) + (data[2] & 0xFF);
                        packetNum = picTotalLen / PACKET_SIZE;
                        if (picTotalLen % PACKET_SIZE != 0) {
                            packetNum++;
                            lastPacketSize = picTotalLen % PACKET_SIZE;
                        }

                        Log.d(TAG, "Total picture length:".concat(String.valueOf(picTotalLen)));
                        Log.d(TAG, "Total packets:".concat(String.valueOf(packetNum)));
                        Log.d(TAG, "Last packet size:".concat(String.valueOf(lastPacketSize)));

                        dataBuf = new byte [MAXIMUM_PACKET_NUM][];
                        tempBuf = new byte [PACKET_SIZE];
                        recvPacketIdTable = new HashSet();

                        Message rtnMsg = mMainHandler.obtainMessage();
                        rtnMsg.what = ACTION_IMAGE_HEADER_CHECKED;
                        mMainHandler.sendMessage(rtnMsg);
                        mDataParserHandler.postDelayed(checkTimeoutRunnable, TIMEOUT_PERIOD);
                    }
                    break;
                }
                case EXTRA_IMAGE_DATA:
                {
                    if (bufOffset == 0){
                        int seqNum = ((data[1] & 0xFF) << 8) + (data[2] & 0xFF);
                        if(seqNum >= packetNum) {
                            isVerifySeqNum = false;
                            Log.d(TAG, "Drop this ble packet.");
                            return;
                        }
                        else{
                            isVerifySeqNum = true;
                        }

                        currentPacketId = seqNum;
                        if(currentPacketId == packetNum-1) {
                            isLastPackets = true;
                        }
                        else {
                            isLastPackets = false;
                        }

                        if( !isLastPackets ){
                            targetPacketSize = PACKET_SIZE;
                        }
                        else{
                            targetPacketSize = lastPacketSize;
                        }

                        if(targetPacketSize > BLE_PACKET_SIZE-4){
                            System.arraycopy(data, 3, tempBuf, 0, data.length-3);
                            bufOffset += (data.length-3);
                        }
                        else{
                            Log.d(TAG, "Debug:" + String.valueOf(targetPacketSize) + " " + String.valueOf(isLastPackets));
                            System.arraycopy(data, 3, tempBuf, 0, data.length - 4);
                            bufOffset += (data.length-4);
                            int check = data[data.length-1] & 0xFF;
                            verifyPacketChecksum(check);

                            if (recvNum == packetNum)
                                checkDataBuf();

                            bufOffset = 0;
                        }
                    }
                    else if(isVerifySeqNum){
                        if (targetPacketSize - bufOffset <= BLE_PACKET_SIZE - 2){               // Modified by Larry on 2/15
                            System.arraycopy(data, 1, tempBuf, bufOffset, data.length-2);
                            bufOffset += (data.length-2);
                            int check = data[data.length-1] & 0xFF;
                            verifyPacketChecksum(check);

                            if (recvNum == packetNum || isLastPackets)
                                checkDataBuf();

                            bufOffset = 0;
                        }
                        else {
                            System.arraycopy(data, 1, tempBuf, bufOffset, data.length-1);
                            bufOffset += (data.length-1);
                        }
                    }
                    else{
                        // Bypass those corrupted data
                        bufOffset = 0;
                    }
                    break;
                }
            }
            super.handleMessage(msg);
        }
    }
}
