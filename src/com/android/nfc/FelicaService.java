/*
 * Ported from Sony's Oreo-era com.android.nfc (NfcNci.apk, SO-01K 47.2.B.5.38 factory firmware)
 * as part of poplardcm's FeliCa/osaifu-keitai support. See project memory
 * (project_poplar_kddi_log1cs_port) for the full extraction/porting methodology.
 *
 * Deliberate differences from Sony's original, all because our AOSP-15 NfcService has a
 * completely different discovery/routing architecture than Sony's Oreo-era one:
 *  - Sony's DiscoveryManager (a whole priority-stacked poll/listen/SE-mask engine, replaced
 *    entirely in modern AOSP by NfcDiscoveryParameters + computeDiscoveryParameters()) is not
 *    ported. Its pushDiscovery(TypeFOnly)/popDiscovery() calls are replaced by NfcService's
 *    existing pausePolling()/resumePolling() (see setPollingPaused()). Off-host Type-F routing to
 *    the FeliCa eSE (NFCEE 0x402) is handled by the stock native RoutingManager once the
 *    device's NFC HAL has activated that NFCEE, so it needs no equivalent of Sony's
 *    per-session addTechRouting() calls at all.
 *  - applyRoutingWithFelicaResult()'s failure/rollback path is dropped: our
 *    DeviceHost.enableDiscovery() is void, there is no synchronous failure signal to roll back
 *    on in this architecture.
 *  - DeviceHost.rectifyRfStateIfNeeded() has no native port yet -- kept as a no-op landmark.
 *  - DeviceHost.isActivated() has no native port -- approximated with isTagPresent().
 *  - com.sonymobile.nfc.idd.NfcIddEvent telemetry (FelicaSeServiceWrapper/FelicaRfServiceWrapper
 *    and the FelicaApiIddEvent interface) is dropped entirely: it's Sony's own usage-analytics
 *    upload pipeline, unrelated to whether FeliCa itself works. FelicaAdapterService now returns
 *    the plain FelicaSeService/FelicaRfService Stub instances directly.
 *  - NativeFelicaRf/NativeFelicaSe/NativeFelicaRfTarget (com.android.nfc.dhimpl, nci/src) exist
 *    as thin native-method wrappers but have no libnfc_nci_jni.so backing yet -- calling
 *    connect()/open()/transceive()/etc. throws UnsatisfiedLinkError until that's ported.
 */
package com.android.nfc;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import com.android.nfc.dhimpl.NativeFelicaRf;
import com.android.nfc.dhimpl.NativeFelicaRfTarget;
import com.android.nfc.dhimpl.NativeFelicaSe;
import com.felicanetworks.felica.IFelicaAdapter;
import com.felicanetworks.felica.IFelicaRf;
import com.felicanetworks.felica.IFelicaSe;
import java.util.Arrays;

public final class FelicaService implements ServiceExtension {
    private static final String ADHOC_CLASS_NAME = "com.felicanetworks.mfc.AdhocReceiver";
    private static final String ADHOC_COMPONENT_NAME = "com.felicanetworks.mfc";
    private static final String ADHOC_DATA_KEY_NAME = "com.felicanetworks.mfc.adhoc_data";
    private static final byte CHANGE_ACTIVE_INTERFACE_COMMAND_CODE = -92;
    private static final byte CHANGE_ACTIVE_INTERFACE_RESPONSE_CODE = -91;
    private static final boolean DBG = NfcService.DBG;
    private static final String ERROR_CODE_KEY = "e";
    private static final String FELICA_ACCESS_PATH = "/etc/felica_access.xml";
    private static final int FELICA_STATE_OFF = 0;
    private static final int FELICA_STATE_RF = 4;
    private static final int FELICA_STATE_RF_POLL = 8;
    private static final int FELICA_STATE_SE = 1;
    private static final int FELICA_STATE_SE_CONNECT = 2;
    private static final int INVALID_HANDLE = -1;
    private static final int LOCK_EXECUTE_TIMEOUT_MS = 10000;
    private static final int MSG_PUSH_RESPONSE = 0;
    private static final String OUT_DATA_KEY = "out";
    private static final byte PUSH_COMMAND_CODE = -80;
    private static final int PUSH_LENGTH_MAX = 224;
    private static final byte PUSH_RESPONSE_CODE = -79;
    private static final String TAG = "FelicaService";
    private static final int TYPE_NFC_CONFLICT = -12;
    private static final int TYPE_NFC_FELICA_RW_STOP = -20;
    private static final int TYPE_NFC_GENERIC_ERROR = -99;
    private static final int TYPE_NFC_INVALID_ACCESS = -19;
    private static final int TYPE_NFC_INVALID_PARAM = -10;
    private static final int TYPE_NFC_INVALID_STATUS = -11;
    private static final int TYPE_NFC_LOCKED = -18;

    private Context mContext;
    private DeviceHost mDeviceHost;
    private NfceeAccessControl mFelicaAccessControl;
    private FelicaRfService mFelicaRfService;
    private FelicaDeviceHost.FelicaRfTargetEndpoint mFelicaRfTargetEndpoint;
    private FelicaSeService mFelicaSeService;
    private PowerManager.WakeLock mFelicaServiceWakeLock;
    private int mFelicaState;
    private OpenFelicaRf mOpenFelicaRf;
    private OpenFelicaSe mOpenFelicaSe;
    private PowerManager mPowerManager;
    private UserHandle mRfUserHandle;
    private UserHandle mSeUserHandle;
    private final ConditionVariable mNfcLockExecutingCond = new ConditionVariable(true);
    private volatile boolean mIsNfcLocked = false;
    private final FelicaServiceHandler mHandler = new FelicaServiceHandler();
    private final NfcService mNfcService = NfcService.getInstance();
    private final FelicaAdapterService mFelicaAdapter = new FelicaAdapterService();

    private void enforceFelicaAdminPerm(String pkg) {
        if (pkg == null) {
            throw new SecurityException("caller must pass a package name");
        }
        NfcPermissions.enforceUserPermissions(this.mContext);
        if (!this.mFelicaAccessControl.check(Binder.getCallingUid(), pkg)) {
            throw new SecurityException("/etc/felica_access.xml denies FeliCa access to " + pkg);
        }
    }

    // Sony's DeviceHost.rectifyRfStateIfNeeded() has no native port yet; deliberate no-op.
    private void rectifyRfStateIfNeeded() {
    }

    // Approximates Sony's DeviceHost.isActivated() (no native port) with NfcService's own
    // isTagPresent() (the NfcOemExtension entry point). May not catch every "RF active" case
    // (e.g. an HCE peer connection with no classic tag object), but covers the ordinary
    // tag-connected conflict this check exists to guard against.
    private boolean isRfActivated() {
        long token = Binder.clearCallingIdentity();
        try {
            return this.mNfcService.mNfcAdapter.isTagPresent();
        } catch (RemoteException e) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // Stands in for Sony's DiscoveryManager pushDiscovery(TypeFOnly)/popDiscovery() with
    // NfcService's own pausePolling()/resumePolling() (the NfcOemExtension entry points). Like
    // Sony's original, this stops RF discovery as a whole while a FeliCa session is open; the
    // pause is capped at getMaxPausePollingTimeoutMs() (overlay max_pause_polling_time_out_ms),
    // after which NfcService resumes polling on its own.
    private void setPollingPaused(boolean paused) {
        NfcService.NfcAdapterService adapter = this.mNfcService.mNfcAdapter;
        long token = Binder.clearCallingIdentity();
        try {
            if (paused) {
                adapter.pausePolling(adapter.getMaxPausePollingTimeoutMs());
            } else {
                adapter.resumePolling();
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private boolean isAirplaneModeOn() {
        return Settings.Global.getInt(
                this.mContext.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
    }

    @Override
    public void onRequested(byte[] data) {
        sendMessage(MSG_PUSH_RESPONSE, data);
    }

    @Override
    public void onNfcLockStateChanged(boolean isLocked, boolean isLockExecuting) {
        synchronized (this) {
            if (!isLockExecuting && this.mIsNfcLocked != isLocked) {
                if (DBG) Log.w(TAG, "NFC lock executing state is illegal");
                return;
            }
            this.mIsNfcLocked = isLocked;
            if (DBG) Log.d(TAG, "NFC lock state is set to : " + this.mIsNfcLocked);
            if (DBG) Log.d(TAG, "NFC lock executing state is set to : " + isLockExecuting);
            if (isLockExecuting) {
                this.mNfcLockExecutingCond.close();
            } else {
                this.mNfcLockExecutingCond.open();
            }
            if (this.mIsNfcLocked && isLockExecuting) {
                new ResetTask().execute();
            }
        }
    }

    public FelicaService(Context context, DeviceHost dh) {
        this.mContext = context;
        this.mDeviceHost = dh;
        Log.i(TAG, "Starting FeliCa service");
        this.mFelicaSeService = new FelicaSeService();
        this.mFelicaRfService = new FelicaRfService();
        this.mFelicaRfTargetEndpoint = new NativeFelicaRfTarget();
        this.mFelicaAccessControl = new NfceeAccessControl(this.mContext, FELICA_ACCESS_PATH);
        this.mPowerManager = this.mContext.getSystemService(PowerManager.class);
        this.mFelicaServiceWakeLock =
                this.mPowerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "FelicaService:mFelicaServiceWakeLock");
    }

    @Override
    public void onReceiveShutdown() {
        if (isNfcLocked()) {
            return;
        }
        this.mNfcService.maybeDisconnectTarget();
        if ((this.mFelicaState & FELICA_STATE_SE_CONNECT) != 0) {
            this.mOpenFelicaSe.mFelicaSeEndpoint.cancel(this.mOpenFelicaSe.mDeviceHandle);
            _felicaSeDisconnect(this.mOpenFelicaSe.mApplicationHandle);
        }
        if ((this.mFelicaState & FELICA_STATE_RF_POLL) == 0) {
            return;
        }
        this.mOpenFelicaRf.mFelicaRfEndpoint.cancel(this.mOpenFelicaRf.mDeviceHandle);
        _felicaRfDisconnect(this.mOpenFelicaRf.mApplicationHandle);
    }

    @Override
    public void onReceivePackageRemoved() {
        this.mFelicaAccessControl.invalidateCache();
    }

    @Override
    public IBinder getExtensionAdapter(String name) {
        if (IFelicaAdapter.class.getName().equals(name)) {
            return this.mFelicaAdapter;
        }
        return null;
    }

    final class FelicaAdapterService extends IFelicaAdapter.Stub {
        public IFelicaSe getFelicaSeInterface(String pkg) throws RemoteException {
            try {
                enforceFelicaAdminPerm(pkg);
                return mFelicaSeService;
            } catch (SecurityException e) {
                Log.e(TAG, String.valueOf(e.getMessage()));
                return null;
            }
        }

        public IFelicaRf getFelicaRfInterface(String pkg) throws RemoteException {
            try {
                enforceFelicaAdminPerm(pkg);
                return mFelicaRfService;
            } catch (SecurityException e) {
                Log.e(TAG, String.valueOf(e.getMessage()));
                return null;
            }
        }
    }

    final class FelicaSeService extends IFelicaSe.Stub {
        private final FelicaDeviceHost.FelicaSeEndpoint mFelicaSeEndpoint = new NativeFelicaSe();

        public Bundle open(String pkg, IBinder b) throws RemoteException {
            enforceFelicaAdminPerm(pkg);
            if (!mNfcLockExecutingCond.block(LOCK_EXECUTE_TIMEOUT_MS)) {
                Log.e(TAG, "nfc lock executing timed out!");
            }
            Bundle bundle = new Bundle();
            synchronized (mNfcService) {
                if (b == null) {
                    bundle.putInt(OUT_DATA_KEY, -1);
                    bundle.putInt(ERROR_CODE_KEY, TYPE_NFC_INVALID_PARAM);
                    return bundle;
                }
                if (isNfcLocked()) {
                    bundle.putInt(OUT_DATA_KEY, -1);
                    bundle.putInt(ERROR_CODE_KEY, TYPE_NFC_LOCKED);
                    return bundle;
                }
                if ((mFelicaState & FELICA_STATE_SE_CONNECT) != 0) {
                    bundle.putInt(OUT_DATA_KEY, -1);
                    bundle.putInt(ERROR_CODE_KEY, TYPE_NFC_INVALID_STATUS);
                    return bundle;
                }
                UserHandle callingUser = Binder.getCallingUserHandle();
                if (mSeUserHandle != null && !mSeUserHandle.equals(callingUser)) {
                    bundle.putInt(OUT_DATA_KEY, -1);
                    bundle.putInt(ERROR_CODE_KEY, TYPE_NFC_INVALID_STATUS);
                    return bundle;
                }
                if (mOpenFelicaSe != null && (mFelicaState & FELICA_STATE_SE) != 0) {
                    if (mOpenFelicaSe.mBinder != b) {
                        bundle.putInt(OUT_DATA_KEY, -1);
                        bundle.putInt(ERROR_CODE_KEY, TYPE_NFC_INVALID_STATUS);
                        return bundle;
                    }
                    bundle.putInt(OUT_DATA_KEY, mOpenFelicaSe.mApplicationHandle);
                    bundle.putInt(ERROR_CODE_KEY, 0);
                    return bundle;
                }
                rectifyRfStateIfNeeded();
                if (isRfActivated()) {
                    bundle.putInt(OUT_DATA_KEY, -1);
                    bundle.putInt(ERROR_CODE_KEY, TYPE_NFC_CONFLICT);
                    Log.e(TAG, "RF is activated!");
                    return bundle;
                }
                mFelicaState |= FELICA_STATE_SE;
                if ((mFelicaState & FELICA_STATE_RF) == 0) {
                    mFelicaServiceWakeLock.acquire();
                    setPollingPaused(true);
                    mFelicaServiceWakeLock.release();
                }
                int retHandle = (int) ((Math.random() * 2.147483647E9d) + 1.0d);
                mOpenFelicaSe = new OpenFelicaSe(mFelicaSeEndpoint, retHandle, b);
                try {
                    b.linkToDeath(mOpenFelicaSe, 0);
                    mSeUserHandle = callingUser;
                    bundle.putInt(OUT_DATA_KEY, retHandle);
                    bundle.putInt(ERROR_CODE_KEY, 0);
                    return bundle;
                } catch (RemoteException e) {
                    mOpenFelicaSe.binderDied();
                    bundle.putInt(OUT_DATA_KEY, -1);
                    bundle.putInt(ERROR_CODE_KEY, TYPE_NFC_GENERIC_ERROR);
                    return bundle;
                }
            }
        }

        public int close(String pkg, int handle, IBinder b) throws RemoteException {
            enforceFelicaAdminPerm(pkg);
            return _felicaSeClose(handle, b);
        }

        public Bundle transceive(String pkg, int handle, byte[] command, int timeout)
                throws RemoteException {
            enforceFelicaAdminPerm(pkg);
            Bundle bundle = new Bundle();
            synchronized (mNfcService) {
                if (command == null || command.length == 0 || timeout < 0 || handle <= 0) {
                    bundle.putByteArray(OUT_DATA_KEY, null);
                    bundle.putInt(ERROR_CODE_KEY, TYPE_NFC_INVALID_PARAM);
                    return bundle;
                }
                if (mOpenFelicaSe != null && handle != mOpenFelicaSe.mApplicationHandle) {
                    bundle.putByteArray(OUT_DATA_KEY, null);
                    bundle.putInt(ERROR_CODE_KEY, TYPE_NFC_INVALID_ACCESS);
                    return bundle;
                }
                if (isNfcLocked()) {
                    bundle.putByteArray(OUT_DATA_KEY, null);
                    bundle.putInt(ERROR_CODE_KEY, TYPE_NFC_LOCKED);
                    return bundle;
                }
                if ((FELICA_STATE_SE & mFelicaState) == 0
                        || mOpenFelicaSe == null
                        || (mFelicaState & FELICA_STATE_SE_CONNECT) == 0) {
                    bundle.putByteArray(OUT_DATA_KEY, null);
                    bundle.putInt(ERROR_CODE_KEY, TYPE_NFC_INVALID_STATUS);
                    return bundle;
                }
                mFelicaServiceWakeLock.acquire();
                try {
                    int[] errCode = {TYPE_NFC_GENERIC_ERROR};
                    byte[] ret =
                            mFelicaSeEndpoint.transceive(
                                    mOpenFelicaSe.mDeviceHandle, command, timeout, errCode);
                    bundle.putByteArray(OUT_DATA_KEY, ret);
                    bundle.putInt(ERROR_CODE_KEY, errCode[0]);
                    return bundle;
                } finally {
                    mFelicaServiceWakeLock.release();
                }
            }
        }

        public int cancel(String pkg, int handle) throws RemoteException {
            enforceFelicaAdminPerm(pkg);
            if (handle <= 0) {
                return TYPE_NFC_INVALID_PARAM;
            }
            if (mOpenFelicaSe != null && handle != mOpenFelicaSe.mApplicationHandle) {
                return TYPE_NFC_INVALID_ACCESS;
            }
            if (isNfcLockedThreadUnsafe()) {
                return TYPE_NFC_LOCKED;
            }
            if ((mFelicaState & FELICA_STATE_SE) == 0
                    || mOpenFelicaSe == null
                    || (mFelicaState & FELICA_STATE_SE_CONNECT) == 0) {
                return TYPE_NFC_INVALID_STATUS;
            }
            mFelicaSeEndpoint.cancel(mOpenFelicaSe.mDeviceHandle);
            return 0;
        }

        public int connect(String pkg, int handle) throws RemoteException {
            enforceFelicaAdminPerm(pkg);
            synchronized (mNfcService) {
                if (handle <= 0) {
                    return TYPE_NFC_INVALID_PARAM;
                }
                if (mOpenFelicaSe != null && handle != mOpenFelicaSe.mApplicationHandle) {
                    return TYPE_NFC_INVALID_ACCESS;
                }
                if (isNfcLocked()) {
                    return TYPE_NFC_LOCKED;
                }
                if ((mFelicaState & FELICA_STATE_SE) == 0 || mOpenFelicaSe == null) {
                    return TYPE_NFC_INVALID_STATUS;
                }
                if ((mFelicaState & FELICA_STATE_RF_POLL) != 0) {
                    return TYPE_NFC_INVALID_STATUS;
                }
                if ((mFelicaState & FELICA_STATE_SE_CONNECT) != 0) {
                    return 0;
                }
                mFelicaState |= FELICA_STATE_SE_CONNECT;
                mFelicaServiceWakeLock.acquire();
                try {
                    int deviceHandle = mFelicaSeEndpoint.open();
                    if (deviceHandle >= 0) {
                        mOpenFelicaSe.setDeviceHandle(deviceHandle);
                        return 0;
                    }
                    mFelicaState &= ~FELICA_STATE_SE_CONNECT;
                    return deviceHandle;
                } finally {
                    mFelicaServiceWakeLock.release();
                }
            }
        }

        public int disconnect(String pkg, int handle) throws RemoteException {
            enforceFelicaAdminPerm(pkg);
            return _felicaSeDisconnect(handle);
        }
    }

    int _felicaSeDisconnect(int handle) {
        return _felicaSeDisconnect(handle, false);
    }

    int _felicaSeDisconnect(int handle, boolean force) {
        synchronized (this.mNfcService) {
            if (handle <= 0) {
                return TYPE_NFC_INVALID_PARAM;
            }
            if (this.mOpenFelicaSe != null && handle != this.mOpenFelicaSe.mApplicationHandle) {
                return TYPE_NFC_INVALID_ACCESS;
            }
            if (isNfcLocked() && !force) {
                return TYPE_NFC_LOCKED;
            }
            if ((this.mFelicaState & FELICA_STATE_SE) == 0 || this.mOpenFelicaSe == null) {
                return TYPE_NFC_INVALID_STATUS;
            }
            if ((this.mFelicaState & FELICA_STATE_SE_CONNECT) == 0) {
                return 0;
            }
            this.mFelicaState &= ~FELICA_STATE_SE_CONNECT;
            this.mFelicaServiceWakeLock.acquire();
            try {
                int ret = this.mOpenFelicaSe.mFelicaSeEndpoint.close(this.mOpenFelicaSe.mDeviceHandle);
                if (ret == 0) {
                    this.mOpenFelicaSe.setDeviceHandle(-1);
                }
                return ret;
            } finally {
                this.mFelicaServiceWakeLock.release();
            }
        }
    }

    int _felicaSeClose(int handle, IBinder binder) {
        return _felicaSeClose(handle, binder, false);
    }

    int _felicaSeClose(int handle, IBinder binder, boolean force) {
        int ret = 0;
        synchronized (this.mNfcService) {
            if (handle <= 0 || binder == null) {
                return TYPE_NFC_INVALID_PARAM;
            }
            if (this.mOpenFelicaSe != null
                    && (handle != this.mOpenFelicaSe.mApplicationHandle
                            || binder != this.mOpenFelicaSe.mBinder)) {
                return TYPE_NFC_INVALID_ACCESS;
            }
            if (isNfcLocked() && !force) {
                return TYPE_NFC_LOCKED;
            }
            if ((this.mFelicaState & FELICA_STATE_SE) == 0 || this.mOpenFelicaSe == null) {
                return 0;
            }
            if ((this.mFelicaState & FELICA_STATE_SE_CONNECT) != 0) {
                return TYPE_NFC_INVALID_STATUS;
            }
            binder.unlinkToDeath(this.mOpenFelicaSe, 0);
            this.mOpenFelicaSe = null;
            this.mFelicaState &= ~FELICA_STATE_SE;
            this.mSeUserHandle = null;
            if (this.mFelicaState == FELICA_STATE_OFF) {
                this.mFelicaServiceWakeLock.acquire();
                setPollingPaused(false);
                this.mFelicaServiceWakeLock.release();
            }
            return ret;
        }
    }

    private class OpenFelicaSe implements IBinder.DeathRecipient {
        private int mApplicationHandle;
        private IBinder mBinder;
        private int mDeviceHandle = -1;
        private final FelicaDeviceHost.FelicaSeEndpoint mFelicaSeEndpoint;

        OpenFelicaSe(FelicaDeviceHost.FelicaSeEndpoint felicaSe, int handle, IBinder binder) {
            this.mFelicaSeEndpoint = felicaSe;
            this.mApplicationHandle = handle;
            this.mBinder = binder;
        }

        void setDeviceHandle(int deviceHandle) {
            this.mDeviceHandle = deviceHandle;
        }

        @Override
        public void binderDied() {
            synchronized (FelicaService.this.mNfcService) {
                FelicaService.this._felicaSeDisconnect(this.mApplicationHandle);
                FelicaService.this._felicaSeClose(this.mApplicationHandle, this.mBinder);
            }
        }

        @Override
        public String toString() {
            return Integer.toHexString(hashCode())
                    + "[mApplicationHandle="
                    + this.mApplicationHandle
                    + " mDeviceHandle="
                    + this.mDeviceHandle
                    + "]";
        }
    }

    final class FelicaRfService extends IFelicaRf.Stub {
        private final FelicaDeviceHost.FelicaRfEndpoint mFelicaRfEndpoint = new NativeFelicaRf();

        public Bundle open(String pkg, IBinder b) throws RemoteException {
            enforceFelicaAdminPerm(pkg);
            if (!mNfcLockExecutingCond.block(LOCK_EXECUTE_TIMEOUT_MS)) {
                Log.e(TAG, "nfc lock executing timed out!");
            }
            Bundle bundle = new Bundle();
            synchronized (mNfcService) {
                if (b == null) {
                    bundle.putInt(OUT_DATA_KEY, -1);
                    bundle.putInt(ERROR_CODE_KEY, TYPE_NFC_INVALID_PARAM);
                    return bundle;
                }
                if (isNfcLocked()) {
                    bundle.putInt(OUT_DATA_KEY, -1);
                    bundle.putInt(ERROR_CODE_KEY, TYPE_NFC_LOCKED);
                    return bundle;
                }
                if ((mFelicaState & FELICA_STATE_RF_POLL) != 0) {
                    bundle.putInt(OUT_DATA_KEY, -1);
                    bundle.putInt(ERROR_CODE_KEY, TYPE_NFC_INVALID_STATUS);
                    return bundle;
                }
                UserHandle callingUser = Binder.getCallingUserHandle();
                if (mRfUserHandle != null && !mRfUserHandle.equals(callingUser)) {
                    bundle.putInt(OUT_DATA_KEY, -1);
                    bundle.putInt(ERROR_CODE_KEY, TYPE_NFC_INVALID_STATUS);
                    return bundle;
                }
                if (mOpenFelicaRf != null && (mFelicaState & FELICA_STATE_RF) != 0) {
                    if (mOpenFelicaRf.mBinder != b) {
                        bundle.putInt(OUT_DATA_KEY, -1);
                        bundle.putInt(ERROR_CODE_KEY, TYPE_NFC_INVALID_STATUS);
                        return bundle;
                    }
                    bundle.putInt(OUT_DATA_KEY, mOpenFelicaRf.mApplicationHandle);
                    bundle.putInt(ERROR_CODE_KEY, 0);
                    return bundle;
                }
                rectifyRfStateIfNeeded();
                if (isRfActivated()) {
                    bundle.putInt(OUT_DATA_KEY, -1);
                    bundle.putInt(ERROR_CODE_KEY, TYPE_NFC_CONFLICT);
                    Log.e(TAG, "RF is activated!");
                    return bundle;
                }
                mFelicaState |= FELICA_STATE_RF;
                if ((mFelicaState & FELICA_STATE_SE) == 0) {
                    mFelicaServiceWakeLock.acquire();
                    setPollingPaused(true);
                    mFelicaServiceWakeLock.release();
                }
                int retHandle = (int) ((Math.random() * 2.147483647E9d) + 1.0d);
                mOpenFelicaRf = new OpenFelicaRf(mFelicaRfEndpoint, retHandle, b);
                try {
                    b.linkToDeath(mOpenFelicaRf, 0);
                    mRfUserHandle = callingUser;
                    bundle.putInt(OUT_DATA_KEY, retHandle);
                    bundle.putInt(ERROR_CODE_KEY, 0);
                    return bundle;
                } catch (RemoteException e) {
                    mOpenFelicaRf.binderDied();
                    bundle.putInt(OUT_DATA_KEY, -1);
                    bundle.putInt(ERROR_CODE_KEY, TYPE_NFC_GENERIC_ERROR);
                    return bundle;
                }
            }
        }

        public int close(String pkg, int handle, IBinder b) throws RemoteException {
            enforceFelicaAdminPerm(pkg);
            return _felicaRfClose(handle, b);
        }

        public Bundle transceive(String pkg, int handle, byte[] command, int timeout)
                throws RemoteException {
            enforceFelicaAdminPerm(pkg);
            Bundle bundle = new Bundle();
            if (command == null || command.length == 0 || timeout < 0 || handle <= 0) {
                bundle.putByteArray(OUT_DATA_KEY, null);
                bundle.putInt(ERROR_CODE_KEY, TYPE_NFC_INVALID_PARAM);
                return bundle;
            }
            if (mOpenFelicaRf != null && handle != mOpenFelicaRf.mApplicationHandle) {
                bundle.putByteArray(OUT_DATA_KEY, null);
                bundle.putInt(ERROR_CODE_KEY, TYPE_NFC_INVALID_ACCESS);
                return bundle;
            }
            if (isNfcLocked()) {
                bundle.putByteArray(OUT_DATA_KEY, null);
                bundle.putInt(ERROR_CODE_KEY, TYPE_NFC_LOCKED);
                return bundle;
            }
            if (isAirplaneModeOn()) {
                bundle.putByteArray(OUT_DATA_KEY, null);
                bundle.putInt(ERROR_CODE_KEY, TYPE_NFC_FELICA_RW_STOP);
                return bundle;
            }
            if ((mFelicaState & FELICA_STATE_RF) == 0
                    || mOpenFelicaRf == null
                    || (mFelicaState & FELICA_STATE_RF_POLL) == 0) {
                bundle.putByteArray(OUT_DATA_KEY, null);
                bundle.putInt(ERROR_CODE_KEY, TYPE_NFC_INVALID_STATUS);
                return bundle;
            }
            mFelicaServiceWakeLock.acquire();
            try {
                int[] errCode = {TYPE_NFC_GENERIC_ERROR};
                byte[] ret =
                        mFelicaRfEndpoint.transceive(
                                mOpenFelicaRf.mDeviceHandle, command, timeout, errCode);
                bundle.putByteArray(OUT_DATA_KEY, ret);
                bundle.putInt(ERROR_CODE_KEY, errCode[0]);
                return bundle;
            } finally {
                mFelicaServiceWakeLock.release();
            }
        }

        public int cancel(String pkg, int handle) throws RemoteException {
            enforceFelicaAdminPerm(pkg);
            if (handle <= 0) {
                return TYPE_NFC_INVALID_PARAM;
            }
            if (mOpenFelicaRf != null && handle != mOpenFelicaRf.mApplicationHandle) {
                return TYPE_NFC_INVALID_ACCESS;
            }
            if (isNfcLockedThreadUnsafe()) {
                return TYPE_NFC_LOCKED;
            }
            if ((mFelicaState & FELICA_STATE_RF) == 0 || mOpenFelicaRf == null) {
                return TYPE_NFC_INVALID_STATUS;
            }
            mFelicaRfEndpoint.cancel(mOpenFelicaRf.mDeviceHandle);
            return 0;
        }

        public int connect(String pkg, int handle, int timeout) throws RemoteException {
            enforceFelicaAdminPerm(pkg);
            synchronized (mNfcService) {
                if (timeout < 0 || handle <= 0) {
                    return TYPE_NFC_INVALID_PARAM;
                }
                if (mOpenFelicaRf != null && handle != mOpenFelicaRf.mApplicationHandle) {
                    return TYPE_NFC_INVALID_ACCESS;
                }
                if (isNfcLocked()) {
                    return TYPE_NFC_LOCKED;
                }
                if (isAirplaneModeOn()) {
                    return TYPE_NFC_FELICA_RW_STOP;
                }
                if ((mFelicaState & FELICA_STATE_RF) == 0 || mOpenFelicaRf == null) {
                    return TYPE_NFC_INVALID_STATUS;
                }
                if ((mFelicaState & FELICA_STATE_SE_CONNECT) != 0) {
                    return TYPE_NFC_INVALID_STATUS;
                }
                if ((mFelicaState & FELICA_STATE_RF_POLL) != 0) {
                    return 0;
                }
                mFelicaState |= FELICA_STATE_RF_POLL;
                mFelicaServiceWakeLock.acquire();
                try {
                    int deviceHandle = mFelicaRfEndpoint.connect(timeout);
                    if (deviceHandle > 0) {
                        mOpenFelicaRf.setDeviceHandle(deviceHandle);
                        return 0;
                    }
                    mFelicaState &= ~FELICA_STATE_RF_POLL;
                    return deviceHandle;
                } finally {
                    mFelicaServiceWakeLock.release();
                }
            }
        }

        public int disconnect(String pkg, int handle) throws RemoteException {
            enforceFelicaAdminPerm(pkg);
            return _felicaRfDisconnect(handle);
        }
    }

    int _felicaRfDisconnect(int handle) {
        return _felicaRfDisconnect(handle, false);
    }

    int _felicaRfDisconnect(int handle, boolean force) {
        synchronized (this.mNfcService) {
            if (handle <= 0) {
                return TYPE_NFC_INVALID_PARAM;
            }
            if (this.mOpenFelicaRf != null && handle != this.mOpenFelicaRf.mApplicationHandle) {
                return TYPE_NFC_INVALID_ACCESS;
            }
            if (isNfcLocked() && !force) {
                return TYPE_NFC_LOCKED;
            }
            if ((this.mFelicaState & FELICA_STATE_RF) == 0 || this.mOpenFelicaRf == null) {
                return TYPE_NFC_INVALID_STATUS;
            }
            if ((this.mFelicaState & FELICA_STATE_RF_POLL) == 0) {
                return 0;
            }
            this.mFelicaState &= ~FELICA_STATE_RF_POLL;
            this.mFelicaServiceWakeLock.acquire();
            try {
                int ret = this.mOpenFelicaRf.mFelicaRfEndpoint.disconnect(this.mOpenFelicaRf.mDeviceHandle);
                if (ret == 0) {
                    this.mOpenFelicaRf.setDeviceHandle(-1);
                }
                return ret;
            } finally {
                this.mFelicaServiceWakeLock.release();
            }
        }
    }

    int _felicaRfClose(int handle, IBinder binder) {
        return _felicaRfClose(handle, binder, false);
    }

    int _felicaRfClose(int handle, IBinder binder, boolean force) {
        int ret = 0;
        synchronized (this.mNfcService) {
            if (handle <= 0 || binder == null) {
                return TYPE_NFC_INVALID_PARAM;
            }
            if (this.mOpenFelicaRf != null
                    && (handle != this.mOpenFelicaRf.mApplicationHandle
                            || binder != this.mOpenFelicaRf.mBinder)) {
                return TYPE_NFC_INVALID_ACCESS;
            }
            if (isNfcLocked() && !force) {
                return TYPE_NFC_LOCKED;
            }
            if ((this.mFelicaState & FELICA_STATE_RF) == 0 || this.mOpenFelicaRf == null) {
                return 0;
            }
            if ((this.mFelicaState & FELICA_STATE_RF_POLL) != 0) {
                return TYPE_NFC_INVALID_STATUS;
            }
            binder.unlinkToDeath(this.mOpenFelicaRf, 0);
            this.mOpenFelicaRf = null;
            this.mFelicaState &= ~FELICA_STATE_RF;
            this.mRfUserHandle = null;
            if (this.mFelicaState == FELICA_STATE_OFF) {
                this.mFelicaServiceWakeLock.acquire();
                setPollingPaused(false);
                this.mFelicaServiceWakeLock.release();
            }
            return ret;
        }
    }

    private class OpenFelicaRf implements IBinder.DeathRecipient {
        private int mApplicationHandle;
        private IBinder mBinder;
        private int mDeviceHandle = -1;
        private final FelicaDeviceHost.FelicaRfEndpoint mFelicaRfEndpoint;

        OpenFelicaRf(FelicaDeviceHost.FelicaRfEndpoint felicaRf, int applicationHandle, IBinder binder) {
            this.mFelicaRfEndpoint = felicaRf;
            this.mApplicationHandle = applicationHandle;
            this.mBinder = binder;
        }

        void setDeviceHandle(int deviceHandle) {
            this.mDeviceHandle = deviceHandle;
        }

        @Override
        public void binderDied() {
            synchronized (FelicaService.this.mNfcService) {
                FelicaService.this._felicaRfDisconnect(this.mApplicationHandle);
                FelicaService.this._felicaRfClose(this.mApplicationHandle, this.mBinder);
            }
        }

        @Override
        public String toString() {
            return Integer.toHexString(hashCode())
                    + "[mApplicationHandle="
                    + this.mApplicationHandle
                    + " mDeviceHandle="
                    + this.mDeviceHandle
                    + "]";
        }
    }

    void sendMessage(int what, Object obj) {
        Message msg = this.mHandler.obtainMessage();
        msg.what = what;
        msg.obj = obj;
        this.mHandler.sendMessage(msg);
    }

    final class FelicaServiceHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what != MSG_PUSH_RESPONSE) {
                Log.e(TAG, "Unknown message received");
                return;
            }
            if (DBG) Log.d(TAG, "Push response");
            synchronized (mNfcService) {
                byte[] res = null;
                byte[] sendData = null;
                byte[] data = (byte[]) msg.obj;
                if (isNfcLocked()) {
                    data = null;
                }
                if (data != null && data.length > 1) {
                    int maxSize = mDeviceHost.getMaxTransceiveLength(FELICA_STATE_RF) + 1;
                    // (data[0] & 0xFF): treat the signed length byte as unsigned, matching Sony's
                    // original use of SnepMessage.RESPONSE_REJECT (-1) purely as a 0xFF bitmask.
                    if ((data[0] & 0xFF) != data.length || data.length > maxSize) {
                        Log.e(TAG, "invalid data length");
                    } else {
                        byte b = data[1];
                        if (b == CHANGE_ACTIVE_INTERFACE_COMMAND_CODE && data.length == 11) {
                            res = Arrays.copyOf(data, 11);
                            res[0] = 11;
                            res[1] = CHANGE_ACTIVE_INTERFACE_RESPONSE_CODE;
                            res[10] = 0;
                        } else if (b == PUSH_COMMAND_CODE && data.length >= 11) {
                            int dataSize = data[10] & 0xFF;
                            if (dataSize == data.length - 11 && dataSize <= PUSH_LENGTH_MAX) {
                                res = Arrays.copyOf(data, 11);
                                res[0] = 11;
                                res[1] = PUSH_RESPONSE_CODE;
                                sendData = Arrays.copyOfRange(data, 11, data.length);
                            }
                        }
                    }
                }
                mFelicaRfTargetEndpoint.sendResponse(res);
                if (sendData != null) {
                    ComponentName targetName =
                            new ComponentName(ADHOC_COMPONENT_NAME, ADHOC_CLASS_NAME);
                    Intent adhocIntent = new Intent();
                    adhocIntent.setComponent(targetName);
                    adhocIntent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
                    adhocIntent.putExtra(ADHOC_DATA_KEY_NAME, sendData);
                    mContext.sendBroadcastAsUser(adhocIntent, UserHandle.CURRENT);
                }
            }
        }
    }

    private boolean isNfcLocked() {
        synchronized (this) {
            return this.mIsNfcLocked;
        }
    }

    private boolean isNfcLockedThreadUnsafe() {
        return this.mIsNfcLocked;
    }

    class ResetTask extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... params) {
            resetInternal();
            return null;
        }

        void resetInternal() {
            if (DBG) Log.d(TAG, "reset the mobile felica adaptor");
            synchronized (FelicaService.this.mNfcService) {
                if (FelicaService.this.mOpenFelicaSe != null) {
                    FelicaService.this._felicaSeDisconnect(
                            FelicaService.this.mOpenFelicaSe.mApplicationHandle, true);
                    FelicaService.this._felicaSeClose(
                            FelicaService.this.mOpenFelicaSe.mApplicationHandle,
                            FelicaService.this.mOpenFelicaSe.mBinder,
                            true);
                }
                if (FelicaService.this.mOpenFelicaRf != null) {
                    FelicaService.this._felicaRfDisconnect(
                            FelicaService.this.mOpenFelicaRf.mApplicationHandle, true);
                    FelicaService.this._felicaRfClose(
                            FelicaService.this.mOpenFelicaRf.mApplicationHandle,
                            FelicaService.this.mOpenFelicaRf.mBinder,
                            true);
                }
            }
        }
    }
}
