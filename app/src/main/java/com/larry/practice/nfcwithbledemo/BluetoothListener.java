package com.larry.practice.nfcwithbledemo;

public interface BluetoothListener {

    /* BLE is not supported in this device */
    void bleNotSupported();

    /* BLE connection establishment timeout (10sec) */
    void bleConnectionTimeout();

    /* BLE connection established successfully */
    void bleConnected();

    /* BLE disconnected */
    void bleDisconnected();

    /* BLE write state success */
    void bleWriteCharacteristicSuccess();

    /* BLE write state fail */
    void bleWriteCharacteristicFail();

    void bleGetDataSuccess();

    void bleGetDataFailure(float dropout);

    void bleUpdateTransferInfo(float progress);

    void bleSaveDataCompleted();
}
