/*
 * Ported from Sony's Oreo-era com.android.nfc (NfcNci.apk, SO-01K 47.2.B.5.38 factory firmware)
 * as part of poplardcm's FeliCa/osaifu-keitai support. See FelicaService.java for the concrete
 * implementation and NfcService.setupServiceExtension()/getExtensionAdapter() for wiring.
 */
package com.android.nfc;

import android.os.IBinder;

/** Vendor extension hook, reached from {@code INfcAdapter.getExtensionAdapter()}. */
public interface ServiceExtension {
    IBinder getExtensionAdapter(String name);

    void onNfcLockStateChanged(boolean isLocked, boolean isLockExecuting);

    void onReceivePackageRemoved();

    void onReceiveShutdown();

    void onRequested(byte[] data);
}
