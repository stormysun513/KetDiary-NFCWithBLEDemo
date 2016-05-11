package com.larry.practice.nfcwithbledemo;

import android.content.Context;
import android.content.Intent;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Created by larry on 4/14/16.
 */
public class WriteFileThread extends Thread{

    private final static String resultDirectoryName = "PatientTracking";

    private byte[] mData;
    private File mTargetFile;
    private Context mContext;

    public WriteFileThread(Context context, String id, byte[] data){

        mData = data;
        mContext = context;
        File mainDirectoryPath;

        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED))
            mainDirectoryPath = new File(Environment.getExternalStorageDirectory(), resultDirectoryName);
        else
            mainDirectoryPath = new File(context.getApplicationContext().getFilesDir(), resultDirectoryName);

        if (!mainDirectoryPath.exists() && !mainDirectoryPath.mkdirs()){
            return;
        }

        File subDirectoryPath = new File(mainDirectoryPath, id);
        if (!subDirectoryPath.exists())
            subDirectoryPath.mkdirs();

        Long timestamp = System.currentTimeMillis()/1000;
        mTargetFile = new File(subDirectoryPath, timestamp.toString());
    }

    @Override
    public void run(){

        try {
            // 打开文件获取输出流，文件不存在则自动创建
            FileOutputStream fos = new FileOutputStream(mTargetFile);
            fos.write(mData);
            fos.close();

            final Intent intent = new Intent(BluetoothLE.DATA_SAVED);
            mContext.sendBroadcast(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
