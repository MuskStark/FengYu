**机械规则(validate.sh 判定)**:
```
M1 SPI 文件存在:src/main/resources/META-INF/services/fan.summer.zhiflow.api.ZhiFlowPlugin
M2 SPI 内容 = 入口类 FQN,且该 .java 存在
M3 ZhiFlow-Api 依赖 scope 为 provided
M4 无 .glass- CSS 引用(源码+资源)
M5 无 setPrefWidth(Double.MAX_VALUE)
M6 无 maxWidthProperty().bind(widthProperty() 循环绑定
M7 插件 getId() 为 reverse-domain(至少两段,点分)
M8 pom 配了 ServicesResourceTransformer
M9 i18n/messages.properties 存在
M10 createView 或 init 中注册了 i18n bundle(registerPluginBundle 或 host.i18n().registerBundle)
M11 DevLauncher 零 javafx 引用(import 或 FQN),所有 JavaFX 均放在独立的 DevApp 类中(pitfall #4)
M12 pom 中 zhiflow.api.version 属性存在(值由使用者维护)
```
**语义规则(reviewer agent 判定)**:
```
S1 AiTool 返回 JSON 符合 {success, summary, ...}(error 用 {success:false,error})
S2 后台任务经 host.tasks() 提交,而非裸 new Thread
S3 自建 Alert/Stage 调用 Themes.applyTo(scene) 上主题
S4 H2 路径基于 user.dir 且用正斜杠
S5 createView() 由宿主调用一次并缓存返回的 Node,插件无需自行缓存;违规指的是生命周期中(如 onActivate/onForeground)重复做本应一次性的构建/副作用,而非"没有缓存字段"本身
S6 使用 -sk-* / .sk-* token,不硬编码颜色规避主题
```
