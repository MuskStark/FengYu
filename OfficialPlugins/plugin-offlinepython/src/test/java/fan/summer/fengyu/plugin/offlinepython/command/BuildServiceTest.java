package fan.summer.fengyu.plugin.offlinepython.command;

import org.junit.jupiter.api.Test;
import fan.summer.fengyu.plugin.offlinepython.domain.DependencySpec;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BuildServiceTest {

    @Test
    void groupsDepsByPlatformSet() {
        List<DependencySpec> deps = List.of(
                new DependencySpec("numpy", "==1.26.4", null),
                new DependencySpec("requests", "==2.31.0", null),
                new DependencySpec("flask", "==3.0.0", null));
        Map<String, List<String>> depPlatforms = new java.util.LinkedHashMap<>();
        depPlatforms.put("numpy", List.of("win_amd64"));
        depPlatforms.put("requests", List.of("manylinux2014_x86_64"));
        depPlatforms.put("flask", List.of("win_amd64")); // 与 numpy 同集合 → 同组

        List<BuildService.DepGroup> groups =
                BuildService.groupByPlatform(deps, depPlatforms, List.of("win_amd64"));
        assertEquals(2, groups.size());
        BuildService.DepGroup winGroup = groups.stream()
                .filter(g -> g.platforms.equals(List.of("win_amd64"))).findFirst().orElseThrow();
        assertTrue(winGroup.specs.contains("numpy==1.26.4"));
        assertTrue(winGroup.specs.contains("flask==3.0.0"));
        assertEquals(2, winGroup.specs.size());
    }

    @Test
    void fallsBackToDefaultPlatformWhenDepMissing() {
        List<DependencySpec> deps = List.of(new DependencySpec("numpy", "==1.26.4", null));
        List<BuildService.DepGroup> groups =
                BuildService.groupByPlatform(deps, Map.of(), List.of("manylinux2014_x86_64"));
        assertEquals(1, groups.size());
        assertEquals(List.of("manylinux2014_x86_64"), groups.get(0).platforms);
    }

    @Test
    void normalizesDepNameForKeyLookup() {
        // requirements 里写 "Pillow"，depPlatforms key 是 normalize 后的 "pillow"
        List<DependencySpec> deps = List.of(new DependencySpec("Pillow", "==10.0.0", null));
        Map<String, List<String>> depPlatforms = new java.util.LinkedHashMap<>();
        depPlatforms.put("pillow", List.of("win_amd64"));
        List<BuildService.DepGroup> groups =
                BuildService.groupByPlatform(deps, depPlatforms, List.of("any"));
        assertEquals(List.of("win_amd64"), groups.get(0).platforms);
    }

    @Test
    void unionPlatformsDedupesPreservingOrder() {
        List<BuildService.DepGroup> groups = List.of(
                new BuildService.DepGroup(List.of("win_amd64")),
                new BuildService.DepGroup(List.of("manylinux2014_x86_64", "win_amd64")));
        assertEquals(List.of("win_amd64", "manylinux2014_x86_64"),
                BuildService.unionPlatforms(groups));
    }
}
