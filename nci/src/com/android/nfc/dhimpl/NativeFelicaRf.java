/*
 * Ported from Sony's Oreo-era com.android.nfc (NfcNci.apk, SO-01K 47.2.B.5.38 factory firmware)
 * as part of poplardcm's FeliCa/osaifu-keitai support.
 *
 * The native methods below have NO backing implementation yet: libnfc_nci_jni.so (built from
 * nci/jni/ in this tree) does not export Java_com_android_nfc_dhimpl_NativeFelicaRf_*. Calling
 * connect()/transceive()/etc. will throw UnsatisfiedLinkError until that native bridge is
 * ported from Sony's own (undecompiled) libnfc_nci_jni.so. The class still compiles and can be
 * constructed fine; only actually invoking a native method fails. No separate
 * System.loadLibrary() call is needed here -- NativeNfcManager already loads "nfc_nci_jni" for
 * the whole process before FelicaService (and this class) is ever touched.
 */
package com.android.nfc.dhimpl;

import android.util.Log;
import com.android.nfc.FelicaDeviceHost;

public class NativeFelicaRf implements FelicaDeviceHost.FelicaRfEndpoint {
    private static final String TAG = "NativeFelicaRf";

    private native void doCancel(int handle);

    private native int doConnect(int timeout);

    private native int doDisconnect(int handle);

    private native byte[] doTransceive(int handle, byte[] command, int timeout, int[] err);

    @Override
    public synchronized byte[] transceive(int handle, byte[] command, int timeout, int[] err) {
        Log.d(TAG, "execute doTransceive");
        return doTransceive(handle, command, timeout, err);
    }

    @Override
    public void cancel(int handle) {
        doCancel(handle);
    }

    @Override
    public synchronized int connect(int timeout) {
        return doConnect(timeout);
    }

    @Override
    public synchronized int disconnect(int handle) {
        return doDisconnect(handle);
    }
}
