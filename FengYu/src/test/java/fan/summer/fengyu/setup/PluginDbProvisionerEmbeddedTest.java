package fan.summer.fengyu.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * SQLite（嵌入式 no-RBAC）宿主下的授权语义：provision 是 no-op 成功（不落 store、不跑
 * DDL、可重复），status/retry 恒报 {@link PluginDbProvisioner#STATUS_EMBEDDED}——授权界面
 * 不应再收到"does not support RBAC provisioning"的 500。worker 的真实连接信息由
 * {@code PluginRuntimeEnvironmentService} 的 embedded 分支注入（插件数据目录下的独立 DB
 * 文件），与本 store 无关。
 */
class PluginDbProvisionerEmbeddedTest {

    @TempDir
    Path config;

    private PluginDbProvisioner sqliteProvisioner() {
        DataSourceConfigService dataSources = new DataSourceConfigService(config.toString()) {
            @Override public DataSourceConfig load() {
                return new DataSourceConfig(DbType.SQLITE,
                    "jdbc:sqlite:/data/fengyu.db", "org.sqlite.JDBC",
                    "org.hibernate.dialect.SQLiteDialect", "", "", "/data/fengyu.db", "", "");
            }
        };
        return new PluginDbProvisioner(dataSources, new PluginDbProvisioningStore(config));
    }

    @Test
    void provisionIsNoOpSuccessWithoutStoreRecord() {
        PluginDbProvisioner provisioner = sqliteProvisioner();
        PluginDbProvisioner.ProvisionedCredentials creds =
            provisioner.provision("com.fengyu.priv.fyreport");
        assertEquals(DbType.SQLITE, creds.type());
        // 占位凭据：嵌入式没有服务器级账号，真实 env 由 runtime 的 embedded 分支提供。
        assertEquals("", creds.url());
        assertEquals("", creds.username());
        assertEquals("", creds.password());
        // 不落 store：嵌入式授权没有可恢复的 DDL 状态。
        assertNull(new PluginDbProvisioningStore(config).get("com.fengyu.priv.fyreport"));
    }

    @Test
    void provisionIsRepeatable() {
        PluginDbProvisioner provisioner = sqliteProvisioner();
        provisioner.provision("com.fengyu.priv.fyreport");
        provisioner.provision("com.fengyu.priv.fyreport");   // 幂等：第二次同样 no-op 成功
        assertEquals(PluginDbProvisioner.STATUS_EMBEDDED, provisioner.status("com.fengyu.priv.fyreport"));
    }

    @Test
    void statusAndRetryAlwaysReportEmbedded() {
        PluginDbProvisioner provisioner = sqliteProvisioner();
        assertEquals(PluginDbProvisioner.STATUS_EMBEDDED, provisioner.status("com.fengyu.priv.fyreport"));
        assertEquals(PluginDbProvisioner.STATUS_EMBEDDED,
            provisioner.retryIncompleteOperation("com.fengyu.priv.fyreport"));
        // 未声明 database 权限的插件同样落在 embedded 语义下（授权界面对两者都放行）。
        assertEquals(PluginDbProvisioner.STATUS_EMBEDDED, provisioner.status("fan.summer.email"));
    }
}
