package fan.summer.fengyu.plugin.offlinepython.domain;

import lombok.Data;

public @Data class BuildConfig {

    private Python python = new Python();
    private Repository repository = new Repository();
    private Download download = new Download();
    private Pkg pkg = new Pkg();
    private Bundle bundle = new Bundle();   // ← 新增

    public static BuildConfig defaults() {
        BuildConfig c = new BuildConfig();
        c.python.version = "3.12.10";
        c.python.platforms = new java.util.ArrayList<>(java.util.List.of("win_amd64"));
        c.python.implementation = "cp";
        c.python.installer = true;
        c.repository.output = "output";
        c.repository.wheelDir = "wheelhouse";
        c.repository.cache = true;
        c.download.mirror = "official";
        c.download.upgradePip = true;
        c.download.recursive = true;
        c.download.onlyBinary = true;
        c.pkg.zip = true;
        c.pkg.sha256 = true;
        c.pkg.readme = true;
        c.bundle.sha256 = true;
        return c;
    }

    @Data public static class Python {
        private String version;
        /** 新增依赖的默认目标平台（不再是构建驱动；构建驱动改为 per-dep 的 depPlatforms）。 */
        private java.util.List<String> platforms = new java.util.ArrayList<>(java.util.List.of("win_amd64"));
        /** per-dependency 目标平台：normalizeName → 平台集合。key 用 DependencySpec.normalizeName。 */
        private java.util.Map<String, java.util.List<String>> depPlatforms = new java.util.LinkedHashMap<>();
        private String implementation;
        private boolean installer;
        private String executable; // null = auto-detect

        /** First selected platform (primary for estimates / single-platform display); defaults to win_amd64. */
        public String getPrimaryPlatform() {
            return platforms == null || platforms.isEmpty() ? "win_amd64" : platforms.get(0);
        }
    }

    @Data public static class Repository {
        private String output;
        private String wheelDir;
        private boolean cache;
    }

    @Data public static class Download {
        private String mirror;
        private boolean upgradePip;
        private boolean recursive;
        private boolean onlyBinary;
    }

    @Data public static class Pkg {
        private boolean zip;
        private boolean sha256;
        private boolean readme;
    }

    /** 部署 bundle 打包配置(打包 output/ → bundle.zip)。 */
    @Data public static class Bundle {
        /** 构建后是否自动打包(默认关)。手动打包按钮恒可用。 */
        private boolean autoPackage;
        /** bundle 名,空 = 用项目目录名。 */
        private String name = "";
        /** 是否在 ZIP 内包含 SHA256SUMS。 */
        private boolean sha256 = true;
    }
}
