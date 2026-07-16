package fan.summer.fengyu.plugin.offlinepython.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlatformMatcherTest {

    private WheelEntry wheel(String file) {
        return new WheelEntry("x", "1.0", file, null, 0, true);
    }

    @Test
    void purePythonAnyWheelAlwaysMatches() {
        PlatformMatcher.HostTags host = new PlatformMatcher.HostTags(
                "win", "x64", "3.12", List.of("win_amd64", "win32", "any"));
        var wheels = List.of(wheel("requests-2.31.0-py3-none-any.whl"));
        List<WheelEntry> matched = PlatformMatcher.match(host, wheels);
        assertEquals(1, matched.size());
    }

    @Test
    void exactPlatformMatch() {
        PlatformMatcher.HostTags host = new PlatformMatcher.HostTags(
                "win", "x64", "3.12", List.of("win_amd64", "win32", "any"));
        var wheels = List.of(wheel("numpy-1.26.4-cp312-cp312-win_amd64.whl"));
        assertEquals(1, PlatformMatcher.match(host, wheels).size());
    }

    @Test
    void wrongPlatformExcluded() {
        PlatformMatcher.HostTags linux = new PlatformMatcher.HostTags(
                "linux", "x64", "3.12", List.of("manylinux2014_x86_64", "linux_x86_64", "any"));
        var wheels = List.of(wheel("numpy-1.26.4-cp312-cp312-win_amd64.whl"));
        assertTrue(PlatformMatcher.match(linux, wheels).isEmpty());
        assertNotNull(PlatformMatcher.incompatReason(linux, wheels.get(0)));
    }

    @Test
    void cpTagMustMatchPythonVersion() {
        PlatformMatcher.HostTags host = new PlatformMatcher.HostTags(
                "win", "x64", "3.11", List.of("win_amd64", "win32", "any"));
        var wheels = List.of(wheel("numpy-1.26.4-cp312-cp312-win_amd64.whl"));
        assertTrue(PlatformMatcher.match(host, wheels).isEmpty());
    }

    @Test
    void macosVersionRangeMatchesNewer() {
        PlatformMatcher.HostTags mac = new PlatformMatcher.HostTags(
                "mac", "arm64", "3.12", List.of("macosx_11_0_arm64", "any"));
        // wheel 声明 11.0,本机 14.0 → 兼容
        var wheels = List.of(wheel("pillow-10.0.0-cp312-cp312-macosx_11_0_arm64.whl"));
        assertEquals(1, PlatformMatcher.match(mac, wheels).size());
    }

    @Test
    void macosVersionRangeRejectsOlder() {
        PlatformMatcher.HostTags mac = new PlatformMatcher.HostTags(
                "mac", "arm64", "3.12", List.of("macosx_10_9_arm64", "any"));
        // wheel 声明 11.0,本机兼容下限 10.9,但本机解析的标签里没有 macosx_11_0_arm64
        // 此测试验证:若 host 标签不含该具体 tag,则不匹配(除非 any)
        var wheels = List.of(wheel("pillow-10.0.0-cp312-cp312-macosx_12_0_arm64.whl"));
        assertTrue(PlatformMatcher.match(mac, wheels).isEmpty());
    }

    @Test
    void detectHostProducesTags() {
        PlatformMatcher.HostTags h = PlatformMatcher.detectHost("3.12");
        assertNotNull(h);
        assertFalse(h.compatibleTags().isEmpty());
        assertTrue(h.compatibleTags().contains("any"));
    }

    @Test
    void samePackageMultipleWheelsAllCompatibleKept() {
        PlatformMatcher.HostTags host = new PlatformMatcher.HostTags(
                "win", "x64", "3.12", List.of("win_amd64", "win32", "any"));
        var wheels = List.of(
                wheel("numpy-1.26.4-cp312-cp312-win_amd64.whl"),
                wheel("numpy-1.26.4-py3-none-any.whl"));
        // 两个都兼容 → 都保留(UI 去重展示,DeployService 都交给 pip)
        assertEquals(2, PlatformMatcher.match(host, wheels).size());
    }
}
