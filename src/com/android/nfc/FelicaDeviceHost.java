/*
 * Ported from Sony's Oreo-era com.android.nfc (NfcNci.apk, SO-01K 47.2.B.5.38 factory firmware)
 * as part of poplardcm's FeliCa/osaifu-keitai support. Pure interface contracts implemented by
 * the com.android.nfc.dhimpl.NativeFelica{Rf,Se,RfTarget} native bridges (nci/src) and consumed
 * by FelicaService.
 */
package com.android.nfc;

public interface FelicaDeviceHost {

    interface FelicaRfEndpoint {
        void cancel(int handle);

        int connect(int timeout);

        int disconnect(int handle);

        byte[] transceive(int handle, byte[] command, int timeout, int[] err);
    }

    interface FelicaRfTargetEndpoint {
        void sendResponse(byte[] response);
    }

    interface FelicaSeEndpoint {
        void cancel(int handle);

        int close(int handle);

        int open();

        byte[] transceive(int handle, byte[] command, int timeout, int[] err);
    }
}
