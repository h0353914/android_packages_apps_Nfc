/*
 * Ported from Sony's Oreo-era com.android.nfc (NfcNci.apk, SO-01K 47.2.B.5.38 factory firmware)
 * as part of poplardcm's FeliCa/osaifu-keitai support. See NativeFelicaRf.java for the native-
 * bridge caveat that applies equally here (no libnfc_nci_jni.so backing yet).
 */
package com.android.nfc.dhimpl;

import com.android.nfc.FelicaDeviceHost;

public class NativeFelicaSe implements FelicaDeviceHost.FelicaSeEndpoint {
    private native void doCancel(int handle);

    private native int doClose(int handle);

    private native int doOpen();

    private native byte[] doTransceive(int handle, byte[] command, int timeout, int[] err);

    @Override
    public int open() {
        return doOpen();
    }

    @Override
    public int close(int handle) {
        return doClose(handle);
    }

    @Override
    public byte[] transceive(int handle, byte[] command, int timeout, int[] err) {
        return doTransceive(handle, command, timeout, err);
    }

    @Override
    public void cancel(int handle) {
        doCancel(handle);
    }
}
