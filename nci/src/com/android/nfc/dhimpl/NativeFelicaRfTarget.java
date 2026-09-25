/*
 * Ported from Sony's Oreo-era com.android.nfc (NfcNci.apk, SO-01K 47.2.B.5.38 factory firmware)
 * as part of poplardcm's FeliCa/osaifu-keitai support. See NativeFelicaRf.java for the native-
 * bridge caveat that applies equally here (no libnfc_nci_jni.so backing yet).
 */
package com.android.nfc.dhimpl;

import com.android.nfc.FelicaDeviceHost;

public class NativeFelicaRfTarget implements FelicaDeviceHost.FelicaRfTargetEndpoint {
    private native void doSendResponse(byte[] response);

    @Override
    public void sendResponse(byte[] response) {
        doSendResponse(response);
    }
}
