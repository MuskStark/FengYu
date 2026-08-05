package fan.summer.fengyu.security;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinNT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Windows-only JNA binding over the Win32 Job Object API, used by {@link ProcessSandbox}'s
 * {@code WINDOWS_JOB} backend to guarantee reliable process-tree termination.
 *
 * <p>A Job Object created with {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE} ensures that when the
 * host JVM dies (closing the job handle), the Windows kernel kills every process in the job —
 * including grandchildren (e.g. a plugin worker's Node driver / Chromium). This replaces the
 * unreliable {@code ProcessHandle.descendants()} enumeration on Windows.
 *
 * <p><b>Not a filesystem/network sandbox.</b> Job Objects confine the process tree's lifecycle,
 * not its filesystem or network access. OS-level file/network isolation on Windows would require
 * AppContainer + ACL work (out of scope; see the design spec's known-gap note).
 *
 * <p>This class is only loaded on Windows (guarded by {@code ProcessSandbox.detect()} →
 * {@code WINDOWS_JOB}, which is only selected on Windows, and by {@link #isAvailable()}). Loading
 * it on a non-Windows host would fail to link the native stubs, so never reference it unconditionally.
 */
final class WindowsJobSandbox {

    private static final Logger log = LoggerFactory.getLogger(WindowsJobSandbox.class);

    // --- Win32 constants (winnt.h / winbase.h) ---
    /** job object limit flag: kill the whole job when the last handle is closed. */
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000;
    /** JobObjectExtendedLimitInformation information class for SetInformationJobObject. */
    private static final int JobObjectExtendedLimitInformation = 9;
    /** OpenProcess access right needed to assign a process to a job. */
    private static final int PROCESS_SET_QUOTA = 0x0100;
    /** OpenProcess access right needed to terminate a process. */
    private static final int PROCESS_TERMINATE = 0x0001;

    /**
     * Minimal kernel32 mapping for Job Object lifecycle. Loaded lazily (never at class-init on a
     * non-Windows host); see {@link #loadKernel32()}.
     */
    @SuppressWarnings("unused")
    interface Kernel32Library extends Library {
        WinNT.HANDLE CreateJobObjectW(Pointer lpJobAttributes, String lpName);

        boolean SetInformationJobObject(WinNT.HANDLE hJob, int infoClass,
                                        Pointer lpJobObjectInfo, int cbJobObjectInfoLength);

        boolean AssignProcessToJobObject(WinNT.HANDLE job, WinNT.HANDLE process);

        boolean TerminateJobObject(WinNT.HANDLE job, int exitCode);

        boolean CloseHandle(WinNT.HANDLE hObject);

        WinNT.HANDLE OpenProcess(int access, boolean inherit, int pid);
    }

    /**
     * The kernel32 binding, or {@code null} when JNA could not load it (non-Windows hosts, or a
     * broken JNA install). Null-safe by design so that class initialization never throws and
     * {@link #isAvailable()} can be queried safely on any platform.
     */
    private static final Kernel32Library LIB = loadKernel32();

    private WindowsJobSandbox() {
    }

    /**
     * True iff JNA + the Win32 stubs loaded successfully on this host (Windows only). Safe to call
     * on any platform — never throws, never triggers additional native loading.
     */
    static boolean isAvailable() {
        return LIB != null;
    }

    private static Kernel32Library loadKernel32() {
        // Short-circuit on non-Windows BEFORE Native.load so we never touch the native stub.
        if (!Platform.isWindows()) {
            return null;
        }
        try {
            return Native.load("kernel32", Kernel32Library.class);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError | RuntimeException e) {
            log.warn("JNA kernel32 unavailable; Windows Job isolation disabled: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Create a Job Object configured to kill its entire process tree when the handle closes.
     *
     * @return the job handle (as a long); never 0
     * @throws IllegalStateException if the Job could not be created or configured
     */
    static long createAndConfigureJob() {
        if (LIB == null) {
            throw new IllegalStateException("JNA kernel32 not available");
        }
        WinNT.HANDLE job = LIB.CreateJobObjectW(null, null);
        if (job == null) {
            throw new IllegalStateException("CreateJobObjectW returned null");
        }
        if (!configureKillOnClose(job)) {
            LIB.CloseHandle(job);
            throw new IllegalStateException("SetInformationJobObject failed");
        }
        return handleToLong(job);
    }

    private static boolean configureKillOnClose(WinNT.HANDLE job) {
        // Build JOBOBJECT_EXTENDED_LIMIT_INFORMATION with LimitFlags = KILL_ON_JOB_CLOSE.
        ExtendedLimit info = new ExtendedLimit();
        info.basicLimitInformation.limitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        info.write();  // flush Java fields into the native struct memory
        return LIB.SetInformationJobObject(job, JobObjectExtendedLimitInformation,
                info.getPointer(), info.size());
    }

    /**
     * Assign a running process to the job. Throws on failure (explicit-failure policy).
     *
     * @param jobHandle the value returned by {@link #createAndConfigureJob()}
     * @param process   a live child process; only its pid is read
     */
    static void assign(long jobHandle, Process process) {
        if (LIB == null) {
            throw new IllegalStateException("JNA kernel32 not available");
        }
        WinNT.HANDLE job = longToHandle(jobHandle);
        long pid = process.pid();
        WinNT.HANDLE ph = LIB.OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE, false, (int) pid);
        if (ph == null) {
            throw new IllegalStateException("OpenProcess failed for pid " + pid);
        }
        try {
            if (!LIB.AssignProcessToJobObject(job, ph)) {
                throw new IllegalStateException("AssignProcessToJobObject failed for pid " + pid);
            }
        } finally {
            LIB.CloseHandle(ph);
        }
    }

    /**
     * Terminate every process in the job (normal close path). No-op if the binding is unavailable
     * or the handle is 0.
     */
    static void terminate(long jobHandle) {
        if (LIB == null || jobHandle == 0L) {
            return;
        }
        LIB.TerminateJobObject(longToHandle(jobHandle), 1);
    }

    /**
     * Close the job handle. On the last close with KILL_ON_JOB_CLOSE, the kernel kills the tree.
     * No-op if the binding is unavailable or the handle is 0.
     */
    static void closeHandle(long jobHandle) {
        if (LIB == null || jobHandle == 0L) {
            return;
        }
        LIB.CloseHandle(longToHandle(jobHandle));
    }

    private static long handleToLong(WinNT.HANDLE handle) {
        return Pointer.nativeValue(handle.getPointer());
    }

    private static WinNT.HANDLE longToHandle(long value) {
        // Reconstruct a HANDLE from a raw pointer value.
        return new WinNT.HANDLE(new Pointer(value));
    }

    /**
     * JOBOBJECT_EXTENDED_LIMIT_INFORMATION (winnt.h) — full Win64 ABI binding.
     *
     * <p>Layout on Win64 (verified against the Microsoft docs, sizes confirmed via JNA
     * {@code Structure.size()} on a 64-bit JVM):
     * <pre>
     *   JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation; // 64 bytes
     *   IO_COUNTERS                       IoInfo;                // 48 bytes
     *   SIZE_T                            ProcessMemoryLimit;    //  8 bytes
     *   SIZE_T                            JobMemoryLimit;        //  8 bytes
     *   SIZE_T                            PeakProcessMemoryUsed; //  8 bytes
     *   SIZE_T                            PeakJobMemoryUsed;     //  8 bytes
     * </pre>
     * Total: 144 bytes on Win64. The {@link BasicLimit} inner struct encodes the BASIC layout
     * (LARGE_INTEGER/LARGE_INTEGER/DWORD + 4-byte pad / SIZE_T / SIZE_T / DWORD + 4-byte pad /
     * ULONG_PTR / DWORD / DWORD → 64 bytes); JNA inserts the alignment padding automatically
     * from the declared field types.
     */
    @Structure.FieldOrder({"basicLimitInformation", "ioInfo",
            "processMemoryLimit", "jobMemoryLimit",
            "peakProcessMemoryUsed", "peakJobMemoryUsed"})
    public static class ExtendedLimit extends Structure {
        public BasicLimit basicLimitInformation;
        public IoCounters ioInfo;
        public long processMemoryLimit;      // SIZE_T → long on Win64
        public long jobMemoryLimit;          // SIZE_T → long on Win64
        public long peakProcessMemoryUsed;   // SIZE_T → long on Win64
        public long peakJobMemoryUsed;       // SIZE_T → long on Win64

        public ExtendedLimit() {
        }

        @Override
        protected List<String> getFieldOrder() {
            // JNA 5.x honors @FieldOrder, but keep this as a belt-and-braces fallback for older
            // runtimes and to make the intended order explicit at the use site.
            return List.of("basicLimitInformation", "ioInfo",
                    "processMemoryLimit", "jobMemoryLimit",
                    "peakProcessMemoryUsed", "peakJobMemoryUsed");
        }
    }

    /**
     * JOBOBJECT_BASIC_LIMIT_INFORMATION (winnt.h) — Win64 ABI binding.
     *
     * <pre>
     *   LARGE_INTEGER PerProcessUserTimeLimit; // 8
     *   LARGE_INTEGER PerJobUserTimeLimit;     // 8
     *   DWORD         LimitFlags;              // 4
     *   // 4 bytes alignment padding (next field is SIZE_T, 8-byte aligned)
     *   SIZE_T        MinimumWorkingSetSize;   // 8
     *   SIZE_T        MaximumWorkingSetSize;   // 8
     *   DWORD         ActiveProcessLimit;      // 4
     *   // 4 bytes alignment padding (next field is ULONG_PTR, 8-byte aligned)
     *   ULONG_PTR     Affinity;                // 8
     *   DWORD         PriorityClass;           // 4
     *   DWORD         SchedulingClass;         // 4
     * </pre>
     * Total: 64 bytes on Win64. JNA derives the alignment padding from the field types.
     */
    @Structure.FieldOrder({"perProcessUserTimeLimit", "perJobUserTimeLimit", "limitFlags",
            "minimumWorkingSetSize", "maximumWorkingSetSize", "activeProcessLimit",
            "affinity", "priorityClass", "schedulingClass"})
    public static class BasicLimit extends Structure {
        public long perProcessUserTimeLimit; // LARGE_INTEGER → long
        public long perJobUserTimeLimit;     // LARGE_INTEGER → long
        public int limitFlags;               // DWORD → int
        public long minimumWorkingSetSize;   // SIZE_T → long on Win64
        public long maximumWorkingSetSize;   // SIZE_T → long on Win64
        public int activeProcessLimit;       // DWORD → int
        public long affinity;                // ULONG_PTR → long on Win64
        public int priorityClass;            // DWORD → int
        public int schedulingClass;          // DWORD → int

        public BasicLimit() {
        }
    }

    /** IO_COUNTERS (winnt.h) — six ULONGLONG counters, 48 bytes. */
    @Structure.FieldOrder({"readOperationCount", "writeOperationCount", "otherOperationCount",
            "readTransferCount", "writeTransferCount", "otherTransferCount"})
    public static class IoCounters extends Structure {
        public long readOperationCount;
        public long writeOperationCount;
        public long otherOperationCount;
        public long readTransferCount;
        public long writeTransferCount;
        public long otherTransferCount;

        public IoCounters() {
        }
    }
}
