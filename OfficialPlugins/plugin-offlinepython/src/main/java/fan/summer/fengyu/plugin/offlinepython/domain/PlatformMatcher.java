package fan.summer.fengyu.plugin.offlinepython.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯逻辑:给定本机平台标签 + wheel 列表,返回适配本机的子集。
 * 不依赖 JavaFX,可单测。匹配规则是 PEP 425 的简化版。
 */
public final class PlatformMatcher {

    private PlatformMatcher() {}

    /** 本机环境信息 + 推导出的兼容平台标签集合。 */
    public record HostTags(String os, String arch, String pythonVersion, List<String> compatibleTags) {}

    /** 用 JVM 系统属性推导本机平台标签。pythonVersion 形如 "3.12"。 */
    public static HostTags detectHost(String pythonVersion) {
        String osName = System.getProperty("os.name", "").toLowerCase();
        String osArch = System.getProperty("os.arch", "");
        String os;
        String arch;
        if (osName.contains("win")) {
            os = "win";
            arch = osArch.contains("64") ? "x64" : "x86";
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            os = "mac";
            arch = osArch.contains("aarch64") || osArch.contains("arm") ? "arm64" : "x64";
        } else {
            os = "linux";
            arch = osArch.contains("aarch64") || osArch.contains("arm") ? "arm64" : "x64";
        }
        return new HostTags(os, arch, pythonVersion, tagsFor(os, arch));
    }

    private static List<String> tagsFor(String os, String arch) {
        List<String> tags = new ArrayList<>();
        tags.add("any");
        switch (os) {
            case "win" -> {
                if ("x64".equals(arch)) tags.add("win_amd64");
                tags.add("win32");
            }
            case "linux" -> {
                if ("x64".equals(arch)) {
                    tags.add("manylinux2014_x86_64");
                    tags.add("linux_x86_64");
                } else {
                    tags.add("manylinux2014_aarch64");
                }
            }
            case "mac" -> {
                // macOS 用区间匹配:这里放入一个代表当前最低版本(取运行机 macOS 版本)的 tag,
                // match() 内对 macosx_A_B_arch 做区间比较,不依赖此列表精确枚举。
                int[] v = macVersion();
                if ("arm64".equals(arch)) {
                    tags.add("macosx_" + v[0] + "_" + v[1] + "_arm64");
                } else {
                    tags.add("macosx_" + v[0] + "_" + v[1] + "_x86_64");
                }
            }
        }
        return tags;
    }

    private static int[] macVersion() {
        String v = System.getProperty("os.version", "11.0");
        String[] p = v.split("\\.");
        int major = p.length > 0 ? parseIntSafe(p[0], 11) : 11;
        int minor = p.length > 1 ? parseIntSafe(p[1], 0) : 0;
        return new int[]{major, minor};
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    /** 返回适配 host 的 wheel 子集(保留所有兼容 wheel,按输入顺序)。 */
    public static List<WheelEntry> match(HostTags host, List<WheelEntry> wheels) {
        List<WheelEntry> out = new ArrayList<>();
        for (WheelEntry w : wheels) {
            if (isCompatible(host, w)) out.add(w);
        }
        return out;
    }

    /** 不兼容原因(null = 兼容)。 */
    public static String incompatReason(HostTags host, WheelEntry wheel) {
        String[] tags = parseWheelTags(wheel.getFile());
        if (tags == null) return "无法解析 wheel 文件名";
        if (!pythonTagOk(tags[0], host.pythonVersion())) return "Python 标签 " + tags[0] + " 与本机 " + host.pythonVersion() + " 不兼容";
        if (!abiTagOk(tags[1], host.pythonVersion())) return "ABI 标签 " + tags[1] + " 不兼容";
        if (!platformTagOk(tags[2], host)) return "平台 " + tags[2] + " 不适配本机";
        return null;
    }

    private static boolean isCompatible(HostTags host, WheelEntry wheel) {
        return incompatReason(host, wheel) == null;
    }

    /** 解析 wheel 文件名 → [pythonTag, abiTag, platformTag]。 */
    static String[] parseWheelTags(String fileName) {
        if (fileName == null) return null;
        String name = fileName.endsWith(".whl") ? fileName.substring(0, fileName.length() - 4) : fileName;
        String[] parts = name.split("-");
        if (parts.length < 5) return null;
        // {name}-{version}-{pythonTag}-{abiTag}-{platformTag}
        return new String[]{parts[parts.length - 3], parts[parts.length - 2], parts[parts.length - 1]};
    }

    private static boolean pythonTagOk(String tag, String pythonVersion) {
        if (tag == null) return false;
        int[] pv = verParts(pythonVersion);
        if (tag.startsWith("py3") || tag.equals("py3")) return true;
        if (tag.startsWith("py2")) return false;
        if (tag.startsWith("cp") || tag.startsWith("pp")) {
            int[] tv = tagParts(tag);
            return tv != null && tv[0] == pv[0] && tv[1] == pv[1];
        }
        if (tag.startsWith("py")) {
            int[] tv = tagParts(tag);
            return tv != null && (tv[0] > pv[0] || (tv[0] == pv[0] && tv[1] >= pv[1]));
        }
        return false;
    }

    private static boolean abiTagOk(String abi, String pythonVersion) {
        if (abi == null || "none".equals(abi)) return true;
        if ("abi3".equals(abi)) return true;
        if (abi.startsWith("cp")) {
            int[] pv = verParts(pythonVersion);
            int[] tv = tagParts(abi);
            return tv != null && tv[0] == pv[0] && tv[1] == pv[1];
        }
        return false;
    }

    private static boolean platformTagOk(String platform, HostTags host) {
        if (platform == null || "any".equals(platform)) return true;
        if (host.compatibleTags().contains(platform)) return true;
        // macOS 区间匹配:macosx_<minMajor>_<minMinor>_<arch>
        if (platform.startsWith("macosx_") && "mac".equals(host.os())) {
            return macosRangeOk(platform, host);
        }
        return false;
    }

    private static boolean macosRangeOk(String platform, HostTags host) {
        String[] p = platform.split("_");
        if (p.length < 4) return false;
        int wheelMajor = parseIntSafe(p[1], -1);
        int wheelMinor = parseIntSafe(p[2], -1);
        String wheelArch = p[3];
        if (!wheelArch.equals(host.arch())) return false;
        // 本机 macOS 版本必须取自 host 自身声明的兼容标签(macosx_<major>_<minor>_<arch>),
        // 而非 JVM 的 os.version —— 否则单测结果依赖运行机器,且与 host 声明脱节。
        int[] hostMac = hostMacVersion(host);
        if (hostMac == null) return false;
        if (hostMac[0] > wheelMajor) return true;
        if (hostMac[0] == wheelMajor && hostMac[1] >= wheelMinor) return true;
        return false;
    }

    /** 从 host 的 compatibleTags 解析本机 macOS 版本(取 arch 匹配的最高 macosx_X_Y_arch)。 */
    private static int[] hostMacVersion(HostTags host) {
        int[] best = null;
        for (String tag : host.compatibleTags()) {
            if (!tag.startsWith("macosx_")) continue;
            String[] parts = tag.split("_");
            if (parts.length < 4 || !parts[3].equals(host.arch())) continue;
            int major = parseIntSafe(parts[1], -1);
            int minor = parseIntSafe(parts[2], -1);
            if (major < 0) continue;
            if (best == null || major > best[0] || (major == best[0] && minor > best[1])) {
                best = new int[]{major, minor};
            }
        }
        return best;
    }

    private static int[] verParts(String v) {
        if (v == null) return new int[]{0, 0};
        String[] p = v.split("\\.");
        return new int[]{parseIntSafe(p[0], 0), p.length > 1 ? parseIntSafe(p[1], 0) : 0};
    }

    /** "cp312" → [3,12];"py39" → [3,9]。 */
    private static int[] tagParts(String tag) {
        StringBuilder digits = new StringBuilder();
        for (char c : tag.toCharArray()) if (Character.isDigit(c)) digits.append(c);
        if (digits.length() < 2) return null;
        String d = digits.toString();
        return new int[]{Integer.parseInt(d.substring(0, 1)),
                         Integer.parseInt(d.substring(1))};
    }
}
