package fan.summer.fengyu.security;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The strict macOS Seatbelt (SBPL) profile for AGENT-exec'd commands — the port of
 * codex's {@code sandboxing/src/seatbelt.rs} model (base policy + read/write roots +
 * network deny), NOT the deny-sensitive worker profile in {@link ProcessSandbox}: a
 * user shell command is not a JVM and survives {@code (deny default)}.
 *
 * <p>Structure mirrors {@code create_seatbelt_command_args_with_profile}: the
 * Chrome-derived base policy (closed by default, process/sysctl/tty allows), then
 * {@code (allow file-read*)} for the full-disk-read tier, per-write-root
 * {@code file-write*} blocks whose protected metadata subpaths are excluded via
 * {@code require-not}, the root-anchor {@code deny file-write-unlink} (renaming a
 * writable root must not relocate its carveouts), the network policy only when
 * network is open (the base's deny-default already denies it otherwise), and the
 * universal hardening tail ({@code deny mach-lookup} on all XPC prefixes and the
 * {@code F_MAKECOMPRESSED}/{@code F_TRANSFEREXTENTS} fcntl leak). Paths are passed as
 * {@code -D} params, never inlined; the executor is the hardcoded
 * {@code /usr/bin/sandbox-exec} (PATH-injection-proof, codex {@code seatbelt.rs:62}).</p>
 */
public final class StrictSeatbeltProfile {

    private StrictSeatbeltProfile() {
    }

    /** The executor — hardcoded so a PATH-shimmed sandbox-exec can never be picked up. */
    public static final String SANDBOX_EXEC = "/usr/bin/sandbox-exec";

    /**
     * Chrome-derived base policy (verbatim semantics of codex
     * {@code seatbelt_base_policy.sbpl}): closed by default; child processes, same-sandbox
     * signaling/process-info, /dev/null writes, the sysctl-read allowlist toolchains
     * probe, the kern.grade_cputype sysctl-write misclassification, IOKit RootDomain,
     * opendirectoryd libinfo lookup, posix semaphores (Python multiprocessing), the
     * OpenMP shared-memory registration range, and PTY support (openpty + ptmx + ttys).
     */
    static final String BASE_POLICY = """
            (version 1)
            (deny default)
            (allow process-exec)
            (allow process-fork)
            (allow signal (target same-sandbox))
            (allow process-info* (target same-sandbox))
            (allow file-write-data
              (require-all
                (path "/dev/null")
                (vnode-type CHARACTER-DEVICE)))
            (allow sysctl-read
              (sysctl-name "hw.activecpu")
              (sysctl-name "hw.byteorder")
              (sysctl-name "hw.cacheconfig")
              (sysctl-name "hw.cachelinesize_compat")
              (sysctl-name "hw.cpufamily")
              (sysctl-name "hw.cputype")
              (sysctl-name "hw.logicalcpu")
              (sysctl-name "hw.logicalcpu_max")
              (sysctl-name "hw.machine")
              (sysctl-name "hw.model")
              (sysctl-name "hw.memsize")
              (sysctl-name "hw.ncpu")
              (sysctl-name "hw.nperflevels")
              (sysctl-name "hw.packages")
              (sysctl-name "hw.pagesize")
              (sysctl-name "hw.pagesize_compat")
              (sysctl-name "hw.physicalcpu")
              (sysctl-name "hw.physicalcpu_max")
              (sysctl-name "hw.vectorunit")
              (sysctl-name "machdep.cpu.brand_string")
              (sysctl-name "kern.argmax")
              (sysctl-name "kern.hostname")
              (sysctl-name "kern.maxfilesperproc")
              (sysctl-name "kern.maxproc")
              (sysctl-name "kern.osproductversion")
              (sysctl-name "kern.osrelease")
              (sysctl-name "kern.ostype")
              (sysctl-name "kern.osvariant_status")
              (sysctl-name "kern.osversion")
              (sysctl-name "kern.secure_kernel")
              (sysctl-name "kern.sysv.semmns")
              (sysctl-name "kern.usrstack64")
              (sysctl-name "kern.version")
              (sysctl-name "sysctl.proc_cputype")
              (sysctl-name "vm.loadavg")
              (sysctl-name-prefix "hw.optional.arm.")
              (sysctl-name-prefix "hw.optional.armv8_")
              (sysctl-name-prefix "hw.perflevel")
              (sysctl-name-prefix "kern.proc.pgrp.")
              (sysctl-name-prefix "kern.proc.pid.")
              (sysctl-name-prefix "net.routetable."))
            (allow sysctl-write (sysctl-name "kern.grade_cputype"))
            (allow iokit-open
              (iokit-registry-entry-class "RootDomainUserClient"))
            (allow mach-lookup
              (global-name "com.apple.system.opendirectoryd.libinfo"))
            (allow ipc-posix-sem)
            (allow ipc-posix-shm-read-data
              ipc-posix-shm-write-create
              ipc-posix-shm-write-unlink
              (ipc-posix-name-regex "^/__KMP_REGISTERED_LIB_[0-9]+$"))
            (allow mach-lookup
              (global-name "com.apple.PowerManagement.control"))
            (allow pseudo-tty)
            (allow file-read* file-write* file-ioctl (literal "/dev/ptmx"))
            (allow file-read* file-write*
              (require-all
                (regex "^/dev/ttys[0-9]+")
                (extension "com.apple.sandbox.pty")))
            (allow file-ioctl (regex "^/dev/ttys[0-9]+"))
            """;

    /** The open-network section (codex {@code seatbelt_network_policy.sbpl}); omitted = denied. */
    static final String NETWORK_POLICY = """
            (allow system-socket
              (require-all
                (socket-domain AF_SYSTEM)
                (socket-protocol 2)))
            (allow mach-lookup
              (global-name "com.apple.bsd.dirhelper")
              (global-name "com.apple.system.opendirectoryd.membership")
              (global-name "com.apple.SecurityServer")
              (global-name "com.apple.networkd")
              (global-name "com.apple.ocspd")
              (global-name "com.apple.trustd.agent")
              (global-name "com.apple.SystemConfiguration.DNSConfiguration")
              (global-name "com.apple.SystemConfiguration.configd"))
            (allow sysctl-read
              (sysctl-name-regex "^net.routetable"))
            """;

    /** Builds the full SBPL profile text for the agent tiers. */
    // (path normalization shares ProcessSandbox's mount-consistent form: same package)
    static String profile(List<Path> writableRoots, List<Path> protectedReadOnlySubpaths,
            boolean allowNetwork) {
        StringBuilder policy = new StringBuilder(BASE_POLICY);
        // Both agent tiers are full-disk READ (the workspace_exec model: broad
        // inspection face); the fence is on writes and network.
        policy.append("; allow read-only file operations\n(allow file-read*)\n");

        List<Path> writable = ProcessSandbox.normalizedExisting(writableRoots);
        List<Path> protectedSubs = ProcessSandbox.normalizedExisting(protectedReadOnlySubpaths);
        if (!writable.isEmpty()) {
            policy.append("; allow write file operations\n");
            for (int i = 0; i < writable.size(); i++) {
                Path root = writable.get(i);
                StringBuilder require = new StringBuilder("(subpath (param \"WRITABLE_ROOT_")
                        .append(i).append("\"))");
                int excluded = 0;
                for (Path sub : protectedSubs) {
                    if (!sub.startsWith(root)) continue;
                    require.append(" (require-not (subpath (param \"WRITABLE_ROOT_")
                            .append(i).append("_EXCLUDED_").append(excluded).append("\")))");
                    excluded++;
                }
                policy.append("(allow file-write* (require-all ").append(require).append("))\n");
                // Root anchor: renaming the writable root must not relocate its carveouts.
                policy.append("(deny file-write-unlink (require-all (literal (param \"WRITABLE_ROOT_")
                        .append(i).append("\")) (vnode-type DIRECTORY)))\n");
            }
        }

        if (allowNetwork) {
            policy.append(NETWORK_POLICY);
        }

        policy.append("(deny mach-lookup (xpc-service-name-prefix \"\"))\n");
        if (!writable.isEmpty()) {
            // F_MAKECOMPRESSED=80 / F_TRANSFEREXTENTS=110 mutate through read-only
            // descriptors, bypassing file-write* and file-ioctl; deny-default still
            // needs this explicit deny (codex seatbelt.rs tail).
            policy.append("(deny system-fcntl (fcntl-command 80 110))\n");
        }
        return policy.toString();
    }

    /**
     * The {@code sandbox-exec} argv: {@code -p <profile>} + {@code -D} params + the
     * command after {@code --}. Paths ride params, never the policy text.
     */
    public static List<String> sandboxExecArgv(List<String> command, List<Path> writableRoots,
            List<Path> protectedReadOnlySubpaths, boolean allowNetwork) {
        List<String> argv = new ArrayList<>();
        argv.add(SANDBOX_EXEC);
        argv.add("-p");
        argv.add(profile(writableRoots, protectedReadOnlySubpaths, allowNetwork));

        List<Path> writable = ProcessSandbox.normalizedExisting(writableRoots);
        List<Path> protectedSubs = ProcessSandbox.normalizedExisting(protectedReadOnlySubpaths);
        for (int i = 0; i < writable.size(); i++) {
            argv.add("-DWRITABLE_ROOT_" + i + "=" + writable.get(i));
            int excluded = 0;
            for (Path sub : protectedSubs) {
                if (!sub.startsWith(writable.get(i))) continue;
                argv.add("-DWRITABLE_ROOT_" + i + "_EXCLUDED_" + excluded + "=" + sub);
                excluded++;
            }
        }
        argv.add("--");
        argv.addAll(command);
        return argv;
    }
}
