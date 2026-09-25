/******************************************************************************
 *
 *  Native JNI backing for com.android.nfc.dhimpl.NativeFelicaSe, part of
 *  poplardcm's ported FeliCa/osaifu-keitai support (see
 *  packages/apps/Nfc/src/com/android/nfc/FelicaService.java for the Java
 *  side).
 *
 *  Sony's original libnfc_nci_jni.so implementation of these methods is
 *  closed-source; this implementation is written directly against
 *  libnfc-nci's own NFA_Ee* API (nfa_ee_api.h), which provides the
 *  "open a conn-oriented connection to an NFCEE, exchange raw APDUs, close
 *  it" primitives this needs:
 *    NFA_EeConnect(handle, NFC_NFCEE_INTERFACE_T3T, cback) -> NFA_EE_CONNECT_EVT
 *    NFA_EeSendData(handle, len, data)                     -> NFA_EE_DATA_EVT
 *    NFA_EeDisconnect(handle)                              -> NFA_EE_DISCONNECT_EVT
 *  Verified working end-to-end on real hardware (Google Wallet Suica-add
 *  flow: connect, several transceive round trips with real APDU responses,
 *  disconnect).
 *
 ******************************************************************************/

#include <android-base/logging.h>
#include <android-base/stringprintf.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <string.h>

#include <vector>

#include "JavaClassConstants.h"
#include "NfcJniUtil.h"
#include "SyncEvent.h"
#include "nfa_ee_api.h"

using android::base::StringPrintf;

namespace android {

// Self-contained class-name constant (unlike NativeNfcTag/NativeNfcManager's,
// this one doesn't need to live in JavaClassConstants.h/NativeNfcManager.cpp
// since nothing else in this codebase references it).
static const char* kNativeFelicaSeClassName =
    "com/android/nfc/dhimpl/NativeFelicaSe";

// Mirrors the TYPE_NFC_* constants in FelicaService.java (no shared header
// exists between the Java and native sides for these; kept in sync by hand).
static const jint TYPE_NFC_NONE_ERROR = 0;
static const jint TYPE_NFC_GENERIC_ERROR = -99;
static const jint TYPE_NFC_TIMEOUT = -13;
static const jint TYPE_NFC_CANCELED = -17;
static const jint TYPE_NFC_ESE_UNAVAILABLE = -15;

static const uint32_t FELICA_SE_CONNECT_TIMEOUT_MS = 3000;
static const uint32_t FELICA_SE_DISCONNECT_TIMEOUT_MS = 3000;

static SyncEvent sFelicaSeConnectEvent;
static SyncEvent sFelicaSeDisconnectEvent;
static SyncEvent sFelicaSeDataEvent;
static tNFA_STATUS sFelicaSeConnectStatus = NFA_STATUS_FAILED;
static std::vector<uint8_t> sFelicaSeRxBuffer;
static volatile bool sFelicaSeCancelled = false;

/*******************************************************************************
**
** Function:        findFelicaSeHandle
**
** Description:     Locate the active off-host NFCEE that is the FeliCa
**                  embedded SE. By the time an app opens a FeliCa SE
**                  session, NFC is already enabled and the device's NFC
**                  HAL has already activated this EE (the stack itself
**                  never activates a T3T NFCEE), so this only needs to
**                  find it.
**
**                  This board enumerates more than one active off-host EE
**                  (0x401 is the UICC slot; connecting to it is rejected by
**                  the NFCC firmware since nothing is behind it), so filter
**                  on lf_protocol (Listen F protocol support), which is
**                  specific to the FeliCa eSE.
**
** Returns:         The EE's handle, or NFA_HANDLE_INVALID if none found.
**
*******************************************************************************/
static tNFA_HANDLE findFelicaSeHandle() {
  tNFA_EE_INFO eeInfo[NFA_EE_MAX_EE_SUPPORTED];
  uint8_t actualNumEe = NFA_EE_MAX_EE_SUPPORTED;
  memset(&eeInfo, 0, sizeof(eeInfo));

  tNFA_STATUS stat = NFA_EeGetInfo(&actualNumEe, eeInfo);
  if (stat != NFA_STATUS_OK) {
    LOG(ERROR) << StringPrintf("%s: NFA_EeGetInfo failed; error=0x%X",
                               __func__, stat);
    return NFA_HANDLE_INVALID;
  }
  for (uint8_t i = 0; i < actualNumEe; i++) {
    LOG(DEBUG) << StringPrintf(
        "%s: EE[%u] handle=0x%04x status=%u num_interface=%u "
        "interface[0]=0x%X lf_protocol=0x%X",
        __func__, i, eeInfo[i].ee_handle, eeInfo[i].ee_status,
        eeInfo[i].num_interface,
        eeInfo[i].num_interface > 0 ? eeInfo[i].ee_interface[0] : 0xFF,
        eeInfo[i].lf_protocol);
    if (eeInfo[i].num_interface != 0 &&
        eeInfo[i].ee_status == NFA_EE_STATUS_ACTIVE &&
        eeInfo[i].lf_protocol != 0) {
      return eeInfo[i].ee_handle;
    }
  }
  LOG(ERROR) << StringPrintf(
      "%s: no active off-host EE with Listen-F support found (is NFC "
      "enabled?)",
      __func__);
  return NFA_HANDLE_INVALID;
}

/*******************************************************************************
**
** Function:        felicaSeEeCallback
**
** Description:     Callback registered directly with NFA_EeConnect(); NFA
**                  routes CONNECT/DATA/DISCONNECT events for this specific
**                  conn-oriented session back here (distinct from
**                  RoutingManager's own NFA_EeRegister() callback, which
**                  handles EE-management events like MODE_SET/DISCOVER).
**
**                  NFA_EE_DISCONNECT_EVT carries no real status field
**                  (nfa_ee_api_disconnect() only ever populates
**                  evt_data.handle) -- receiving the event at all means the
**                  disconnect completed, so it just signals its SyncEvent.
**
*******************************************************************************/
static void felicaSeEeCallback(tNFA_EE_EVT event, tNFA_EE_CBACK_DATA* data) {
  switch (event) {
    case NFA_EE_CONNECT_EVT: {
      SyncEventGuard g(sFelicaSeConnectEvent);
      sFelicaSeConnectStatus = data ? data->connect.status : NFA_STATUS_FAILED;
      LOG(DEBUG) << StringPrintf("%s: NFA_EE_CONNECT_EVT status=0x%X",
                                 __func__, sFelicaSeConnectStatus);
      sFelicaSeConnectEvent.notifyOne();
      break;
    }
    case NFA_EE_DISCONNECT_EVT: {
      SyncEventGuard g(sFelicaSeDisconnectEvent);
      LOG(DEBUG) << StringPrintf("%s: NFA_EE_DISCONNECT_EVT", __func__);
      sFelicaSeDisconnectEvent.notifyOne();
      break;
    }
    case NFA_EE_DATA_EVT: {
      SyncEventGuard g(sFelicaSeDataEvent);
      if (data && data->data.p_buf && data->data.len > 0) {
        sFelicaSeRxBuffer.assign(data->data.p_buf,
                                 data->data.p_buf + data->data.len);
      } else {
        sFelicaSeRxBuffer.clear();
      }
      LOG(DEBUG) << StringPrintf("%s: NFA_EE_DATA_EVT len=%zu", __func__,
                                 sFelicaSeRxBuffer.size());
      sFelicaSeDataEvent.notifyOne();
      break;
    }
    default:
      break;
  }
}

/*******************************************************************************
**
** Function:        nativeFelicaSe_doOpen
**
** Description:     Open a conn-oriented connection to the FeliCa eSE, using
**                  the T3T (Type 3 Tag, FeliCa's native protocol) NFCEE
**                  interface. Java: NativeFelicaSe.doOpen() -> int.
**
** Returns:         The EE handle (>= 0) to use as the "device handle" for
**                  subsequent close/transceive/cancel calls, or a negative
**                  TYPE_NFC_* error code.
**
*******************************************************************************/
static jint nativeFelicaSe_doOpen(JNIEnv*, jobject) {
  LOG(DEBUG) << StringPrintf("%s: enter", __func__);

  tNFA_HANDLE handle = findFelicaSeHandle();
  if (handle == NFA_HANDLE_INVALID) return TYPE_NFC_ESE_UNAVAILABLE;

  sFelicaSeCancelled = false;
  tNFA_STATUS stat;
  {
    SyncEventGuard g(sFelicaSeConnectEvent);
    sFelicaSeConnectStatus = NFA_STATUS_FAILED;
    stat = NFA_EeConnect(handle, NFC_NFCEE_INTERFACE_T3T, felicaSeEeCallback);
    if (stat != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: NFA_EeConnect failed; error=0x%X",
                                 __func__, stat);
      return TYPE_NFC_GENERIC_ERROR;
    }
    if (!sFelicaSeConnectEvent.wait(FELICA_SE_CONNECT_TIMEOUT_MS)) {
      LOG(ERROR) << StringPrintf("%s: timed out waiting for CONNECT_EVT",
                                 __func__);
      return TYPE_NFC_TIMEOUT;
    }
  }
  if (sFelicaSeConnectStatus != NFA_STATUS_OK) {
    LOG(ERROR) << StringPrintf("%s: connect failed; status=0x%X", __func__,
                               sFelicaSeConnectStatus);
    return TYPE_NFC_GENERIC_ERROR;
  }
  LOG(DEBUG) << StringPrintf("%s: connected, handle=0x%04x", __func__, handle);
  return (jint)handle;
}

/*******************************************************************************
**
** Function:        nativeFelicaSe_doClose
**
** Description:     Close the connection opened by doOpen().
**                  Java: NativeFelicaSe.doClose(int handle) -> int.
**
** Returns:         0 on success, a negative TYPE_NFC_* error code otherwise.
**
*******************************************************************************/
static jint nativeFelicaSe_doClose(JNIEnv*, jobject, jint handle) {
  LOG(DEBUG) << StringPrintf("%s: enter; handle=0x%04x", __func__, handle);

  tNFA_STATUS stat;
  {
    SyncEventGuard g(sFelicaSeDisconnectEvent);
    stat = NFA_EeDisconnect((tNFA_HANDLE)handle);
    if (stat != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: NFA_EeDisconnect failed; error=0x%X",
                                 __func__, stat);
      return TYPE_NFC_GENERIC_ERROR;
    }
    if (!sFelicaSeDisconnectEvent.wait(FELICA_SE_DISCONNECT_TIMEOUT_MS)) {
      LOG(ERROR) << StringPrintf("%s: timed out waiting for DISCONNECT_EVT",
                                 __func__);
      return TYPE_NFC_TIMEOUT;
    }
  }
  return TYPE_NFC_NONE_ERROR;
}

/*******************************************************************************
**
** Function:        nativeFelicaSe_doTransceive
**
** Description:     Send an APDU to the eSE and wait for its response.
**                  Java: NativeFelicaSe.doTransceive(int handle,
**                  byte[] command, int timeout, int[] err) -> byte[].
**
** Returns:         Response bytes, or NULL on error (err[0] holds the
**                  TYPE_NFC_* reason).
**
*******************************************************************************/
static jbyteArray nativeFelicaSe_doTransceive(JNIEnv* e, jobject,
                                              jint handle, jbyteArray command,
                                              jint timeout, jintArray err) {
  LOG(DEBUG) << StringPrintf("%s: enter; handle=0x%04x, timeout=%d", __func__,
                             handle, timeout);

  ScopedIntArrayRW errArr(e, err);
  auto setErr = [&](jint code) {
    if (errArr.get() != nullptr && errArr.size() > 0) errArr[0] = code;
  };

  ScopedByteArrayRO bytes(e, command);
  if (bytes.size() == 0) {
    setErr(TYPE_NFC_GENERIC_ERROR);
    return nullptr;
  }
  uint8_t* buf = const_cast<uint8_t*>(
      reinterpret_cast<const uint8_t*>(&bytes[0]));

  tNFA_STATUS stat;
  {
    SyncEventGuard g(sFelicaSeDataEvent);
    sFelicaSeRxBuffer.clear();
    sFelicaSeCancelled = false;
    stat = NFA_EeSendData((tNFA_HANDLE)handle, (uint16_t)bytes.size(), buf);
    if (stat != NFA_STATUS_OK) {
      LOG(ERROR) << StringPrintf("%s: NFA_EeSendData failed; error=0x%X",
                                 __func__, stat);
      setErr(TYPE_NFC_GENERIC_ERROR);
      return nullptr;
    }
    uint32_t waitMs = timeout > 0 ? (uint32_t)timeout : FELICA_SE_CONNECT_TIMEOUT_MS;
    if (!sFelicaSeDataEvent.wait(waitMs)) {
      LOG(ERROR) << StringPrintf("%s: timed out waiting for DATA_EVT",
                                 __func__);
      setErr(TYPE_NFC_TIMEOUT);
      return nullptr;
    }
  }
  if (sFelicaSeCancelled) {
    setErr(TYPE_NFC_CANCELED);
    return nullptr;
  }

  jbyteArray result = e->NewByteArray(sFelicaSeRxBuffer.size());
  if (result != nullptr && sFelicaSeRxBuffer.size() > 0) {
    e->SetByteArrayRegion(result, 0, sFelicaSeRxBuffer.size(),
                          (const jbyte*)sFelicaSeRxBuffer.data());
  }
  setErr(TYPE_NFC_NONE_ERROR);
  return result;
}

/*******************************************************************************
**
** Function:        nativeFelicaSe_doCancel
**
** Description:     Abort a transceive that is currently blocked waiting for
**                  a response, so it returns promptly instead of waiting out
**                  its full timeout. Java: NativeFelicaSe.doCancel(int) -> void.
**
*******************************************************************************/
static void nativeFelicaSe_doCancel(JNIEnv*, jobject, jint /*handle*/) {
  LOG(DEBUG) << StringPrintf("%s: enter", __func__);
  SyncEventGuard g(sFelicaSeDataEvent);
  sFelicaSeCancelled = true;
  sFelicaSeDataEvent.notifyOne();
}

/*****************************************************************************
 **
 ** Description:     JNI functions
 **
 *****************************************************************************/
static JNINativeMethod gMethods[] = {
    {"doOpen", "()I", (void*)nativeFelicaSe_doOpen},
    {"doClose", "(I)I", (void*)nativeFelicaSe_doClose},
    {"doTransceive", "(I[BI[I)[B", (void*)nativeFelicaSe_doTransceive},
    {"doCancel", "(I)V", (void*)nativeFelicaSe_doCancel},
};

/*******************************************************************************
 **
 ** Function:        register_com_android_nfc_dhimpl_NativeFelicaSe
 **
 ** Description:     Register JNI functions with Java Virtual Machine.
 **                  e: Environment of JVM.
 **
 ** Returns:         Status of registration.
 **
 *******************************************************************************/
int register_com_android_nfc_dhimpl_NativeFelicaSe(JNIEnv* e) {
  return jniRegisterNativeMethods(e, kNativeFelicaSeClassName, gMethods,
                                  NELEM(gMethods));
}

}  // namespace android
