package io.haifa.agent.auth.localmodel;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase.FILETIME;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Native JNA-backed implementation of Windows Credential Manager. */
public final class NativeWindowsCredentialManagerClient implements WindowsCredentialManagerClient {
    public static final NativeWindowsCredentialManagerClient INSTANCE = new NativeWindowsCredentialManagerClient();

    public static final int CRED_TYPE_GENERIC = 1;
    public static final int CRED_PERSIST_LOCAL_MACHINE = 2;

    private final Advapi32 advapi32;

    public interface Advapi32 extends StdCallLibrary {
        Advapi32 INSTANCE = Native.load("advapi32", Advapi32.class, W32APIOptions.UNICODE_OPTIONS);

        boolean CredReadW(String targetName, int type, int flags, PointerByReference pCredential);

        boolean CredWriteW(CREDENTIAL credential, int flags);

        boolean CredDeleteW(String targetName, int type, int flags);

        boolean CredEnumerateW(String filter, int flags, IntByReference count, PointerByReference pCredentials);

        void CredFree(Pointer buffer);
    }

    @Structure.FieldOrder({
        "Flags",
        "Type",
        "TargetName",
        "Comment",
        "LastWritten",
        "CredentialBlobSize",
        "CredentialBlob",
        "Persist",
        "AttributeCount",
        "Attributes",
        "TargetAlias",
        "UserName"
    })
    public static class CREDENTIAL extends Structure {
        public int Flags;
        public int Type;
        public WString TargetName;
        public WString Comment;
        public FILETIME LastWritten;
        public int CredentialBlobSize;
        public Pointer CredentialBlob;
        public int Persist;
        public int AttributeCount;
        public Pointer Attributes;
        public WString TargetAlias;
        public WString UserName;

        public CREDENTIAL() {
            super();
        }

        public CREDENTIAL(Pointer pointer) {
            super(pointer);
        }
    }

    NativeWindowsCredentialManagerClient() {
        this(Advapi32.INSTANCE);
    }

    NativeWindowsCredentialManagerClient(Advapi32 advapi32) {
        this.advapi32 = Objects.requireNonNull(advapi32, "advapi32 must not be null");
    }

    @Override
    public Optional<String> read(String targetName) {
        Objects.requireNonNull(targetName, "targetName must not be null");
        PointerByReference pCred = new PointerByReference();
        boolean success = advapi32.CredReadW(targetName, CRED_TYPE_GENERIC, 0, pCred);
        if (!success) {
            int error = Kernel32.INSTANCE.GetLastError();
            if (error == WinError.ERROR_NOT_FOUND) {
                return Optional.empty();
            }
            throw new IllegalStateException("Windows CredRead failed with error " + error);
        }
        try {
            CREDENTIAL cred = new CREDENTIAL(pCred.getValue());
            cred.read();
            if (cred.CredentialBlob == null || cred.CredentialBlobSize <= 0) {
                return Optional.of("");
            }
            byte[] bytes = cred.CredentialBlob.getByteArray(0, cred.CredentialBlobSize);
            return Optional.of(new String(bytes, StandardCharsets.UTF_8));
        } finally {
            advapi32.CredFree(pCred.getValue());
        }
    }

    @Override
    public void write(String targetName, String secret) {
        Objects.requireNonNull(targetName, "targetName must not be null");
        Objects.requireNonNull(secret, "secret must not be null");
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        CREDENTIAL cred = new CREDENTIAL();
        cred.Type = CRED_TYPE_GENERIC;
        cred.TargetName = new WString(targetName);
        cred.Persist = CRED_PERSIST_LOCAL_MACHINE;
        if (bytes.length > 0) {
            Memory memory = new Memory(bytes.length);
            memory.write(0, bytes, 0, bytes.length);
            cred.CredentialBlob = memory;
            cred.CredentialBlobSize = bytes.length;
        } else {
            cred.CredentialBlob = Pointer.NULL;
            cred.CredentialBlobSize = 0;
        }
        boolean success = advapi32.CredWriteW(cred, 0);
        if (!success) {
            int error = Kernel32.INSTANCE.GetLastError();
            throw new IllegalStateException("Windows CredWrite failed with error " + error);
        }
    }

    @Override
    public boolean delete(String targetName) {
        Objects.requireNonNull(targetName, "targetName must not be null");
        boolean success = advapi32.CredDeleteW(targetName, CRED_TYPE_GENERIC, 0);
        if (!success) {
            int error = Kernel32.INSTANCE.GetLastError();
            if (error == WinError.ERROR_NOT_FOUND) {
                return false;
            }
            throw new IllegalStateException("Windows CredDelete failed with error " + error);
        }
        return true;
    }

    @Override
    public List<String> listTargets(String prefixFilter) {
        Objects.requireNonNull(prefixFilter, "prefixFilter must not be null");
        String filter = prefixFilter.endsWith("*") ? prefixFilter : prefixFilter + "*";
        IntByReference count = new IntByReference();
        PointerByReference pCredentials = new PointerByReference();
        boolean success = advapi32.CredEnumerateW(filter, 0, count, pCredentials);
        if (!success) {
            int error = Kernel32.INSTANCE.GetLastError();
            if (error == WinError.ERROR_NOT_FOUND) {
                return List.of();
            }
            throw new IllegalStateException("Windows CredEnumerate failed with error " + error);
        }
        try {
            int n = count.getValue();
            if (n <= 0 || pCredentials.getValue() == Pointer.NULL) {
                return List.of();
            }
            Pointer[] pointers = pCredentials.getValue().getPointerArray(0, n);
            List<String> targets = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                if (pointers[i] != null && pointers[i] != Pointer.NULL) {
                    CREDENTIAL cred = new CREDENTIAL(pointers[i]);
                    cred.read();
                    if (cred.TargetName != null) {
                        targets.add(cred.TargetName.toString());
                    }
                }
            }
            return List.copyOf(targets);
        } finally {
            advapi32.CredFree(pCredentials.getValue());
        }
    }
}
