package fan.summer.fengyu.plugin.offlinepython.domain;

import java.nio.file.Path;

/**
 * 安装目标:全局现有 Python,或新建虚拟环境。
 */
public sealed interface DeployTarget {

    /** 装到检测到的全局 Python 的 site-packages。 */
    record Global(Path pythonExe) implements DeployTarget {}

    /** 新建虚拟环境(python -m venv venvPath)后装进去。 */
    record Venv(Path pythonExe, Path venvPath) implements DeployTarget {}
}
