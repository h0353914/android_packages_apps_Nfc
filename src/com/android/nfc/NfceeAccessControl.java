/*
 * Ported from Sony's Oreo-era com.android.nfc (NfcNci.apk, SO-01K 47.2.B.5.38 factory firmware)
 * as part of poplardcm's FeliCa/osaifu-keitai support. Signature/package XML access-control
 * checker, used by FelicaService to gate access via /etc/felica_access.xml (mirrors the stock
 * /etc/nfcee_access.xml mechanism this class was originally written for).
 *
 * checkPackageNfceeAccess() below fixes a genuine bug visible in the decompiled source: the
 * original had an empty catch around getPackageInfo() that left `info` used uninitialized on
 * the NameNotFoundException path. Rewritten to let the checked exception propagate, matching
 * this method's declared `throws PackageManager.NameNotFoundException`.
 */
package com.android.nfc;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Environment;
import android.util.Log;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

public class NfceeAccessControl {
    static final boolean DBG = false;
    public static final String NFCEE_ACCESS_PATH = "/etc/nfcee_access.xml";
    static final String TAG = "NfceeAccess";
    final Context mContext;
    final boolean mDebugPrintSignature;
    final HashMap<Signature, String[]> mNfceeAccess;
    private final String mPath;
    final HashMap<Integer, Boolean> mUidCache;

    NfceeAccessControl(Context context) {
        this(context, NFCEE_ACCESS_PATH);
    }

    NfceeAccessControl(Context context, String path) {
        this.mContext = context;
        this.mPath = path;
        this.mNfceeAccess = new HashMap<>();
        this.mUidCache = new HashMap<>();
        boolean debug = false;
        try {
            debug = parseNfceeAccess();
        } catch (IOException | XmlPullParserException e) {
            Log.e(TAG, "Failed to load NFCEE access list", e);
        }
        this.mDebugPrintSignature = debug;
    }

    public boolean check(int uid, String pkg) {
        synchronized (this) {
            Boolean cached = this.mUidCache.get(Integer.valueOf(uid));
            if (cached != null) {
                return cached.booleanValue();
            }
            boolean access = false;
            PackageManager pm = this.mContext.getPackageManager();
            String[] pkgs = pm.getPackagesForUid(uid);
            if (pkgs != null) {
                for (String uidPkg : pkgs) {
                    if (uidPkg.equals(pkg)) {
                        try {
                            if (checkPackageNfceeAccess(pkg)) {
                                access = true;
                            }
                        } catch (PackageManager.NameNotFoundException e) {
                            // treat as no access
                        }
                    }
                }
            }
            this.mUidCache.put(Integer.valueOf(uid), Boolean.valueOf(access));
            return access;
        }
    }

    public boolean check(ApplicationInfo info) {
        synchronized (this) {
            Boolean access = this.mUidCache.get(Integer.valueOf(info.uid));
            if (access == null) {
                boolean granted;
                try {
                    granted = checkPackageNfceeAccess(((PackageItemInfo) info).packageName);
                } catch (PackageManager.NameNotFoundException e) {
                    granted = false;
                }
                access = Boolean.valueOf(granted);
                this.mUidCache.put(Integer.valueOf(info.uid), access);
            }
            return access.booleanValue();
        }
    }

    public void invalidateCache() {
        synchronized (this) {
            this.mUidCache.clear();
        }
    }

    boolean checkPackageNfceeAccess(String pkg) throws PackageManager.NameNotFoundException {
        PackageManager pm = this.mContext.getPackageManager();
        PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
        if (info.signatures == null) {
            return false;
        }
        for (Signature s : info.signatures) {
            if (s != null) {
                String[] packages = this.mNfceeAccess.get(s);
                if (packages != null) {
                    if (packages.length == 0) {
                        return true;
                    }
                    for (String p : packages) {
                        if (pkg.equals(p)) {
                            return true;
                        }
                    }
                }
            }
        }
        if (this.mDebugPrintSignature) {
            Log.w(TAG, "denied NFCEE access for " + pkg + " with signature:");
            for (Signature s2 : info.signatures) {
                if (s2 != null) {
                    Log.w(TAG, s2.toCharsString());
                }
            }
        }
        return false;
    }

    boolean parseNfceeAccess() throws IOException, XmlPullParserException {
        File file = new File(Environment.getRootDirectory(), this.mPath);
        FileReader reader = null;
        boolean debug = false;
        try {
            try {
                reader = new FileReader(file);
            } catch (FileNotFoundException e) {
                Log.w(TAG, "could not find " + this.mPath + ", no FeliCa/NFCEE access allowed");
                return false;
            }
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(reader);
            parser.setFeature("http://xmlpull.org/v1/doc/features.html#process-namespaces", false);
            ArrayList<String> packages = new ArrayList<>();
            Signature signature = null;
            while (true) {
                int event;
                try {
                    event = parser.next();
                } catch (XmlPullParserException e) {
                    Log.w(TAG, "failed to load NFCEE access list", e);
                    this.mNfceeAccess.clear();
                    break;
                }
                if (event == XmlPullParser.END_DOCUMENT) {
                    break;
                }
                String tag = parser.getName();
                if (event == XmlPullParser.START_TAG && "signer".equals(tag)) {
                    signature = null;
                    packages.clear();
                    for (int i = 0; i < parser.getAttributeCount(); i++) {
                        if ("android:signature".equals(parser.getAttributeName(i))) {
                            signature = new Signature(parser.getAttributeValue(i));
                            break;
                        }
                    }
                    if (signature == null) {
                        Log.w(TAG, "signer tag is missing android:signature attribute, ignoring");
                    } else if (this.mNfceeAccess.containsKey(signature)) {
                        Log.w(TAG, "duplicate signature, ignoring");
                        signature = null;
                    }
                } else if (event == XmlPullParser.END_TAG && "signer".equals(tag)) {
                    if (signature == null) {
                        Log.w(TAG, "mis-matched signer tag");
                    } else {
                        this.mNfceeAccess.put(signature, packages.toArray(new String[0]));
                        packages.clear();
                    }
                } else if (event == XmlPullParser.START_TAG && "package".equals(tag)) {
                    if (signature == null) {
                        Log.w(TAG, "ignoring unnested package tag");
                    } else {
                        String name = null;
                        for (int i = 0; i < parser.getAttributeCount(); i++) {
                            if ("android:name".equals(parser.getAttributeName(i))) {
                                name = parser.getAttributeValue(i);
                                break;
                            }
                        }
                        if (name == null) {
                            Log.w(TAG, "package missing android:name, ignoring signer group");
                            signature = null;
                        } else if (packages.contains(name)) {
                            Log.w(TAG, "duplicate package name in signer group, ignoring");
                        } else {
                            packages.add(name);
                        }
                    }
                } else if (event == XmlPullParser.START_TAG && "debug".equals(tag)) {
                    debug = true;
                }
            }
            Log.i(TAG, "read " + this.mNfceeAccess.size() + " signature(s) for NFCEE access from " + this.mPath);
            return debug;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("mNfceeAccess=");
        for (Signature s : this.mNfceeAccess.keySet()) {
            pw.printf("\t%s [", s.toCharsString());
            String[] ps = this.mNfceeAccess.get(s);
            for (String p : ps) {
                pw.printf("%s, ", p);
            }
            pw.println("]");
        }
        synchronized (this) {
            pw.println("mNfceeUidCache=");
            for (Integer uid : this.mUidCache.keySet()) {
                Boolean b = this.mUidCache.get(uid);
                pw.printf("\t%d %s\n", uid, b);
            }
        }
    }
}
