# 更新日志

**v3.0.0-rc.1** — 2026-05-29

### ✨ 新功能

- **插件生命周期**: 在 `SwissKitJPlugin` 接口中添加了 `onActivate()` 和 `onDeactivate()` 默认方法，允许插件挂载到生命周期事件。

### ♻️ 变更

- **ToolCategory 枚举**: 将分类字符串转换为 `ToolCategory` 枚举（`DEV`、`TEXT`、`IMAGE`、`NET`、`OTHER`），实现类型安全的分类处理。

## 3.0.0-beta.2 (2026-05-25)

- feat(ui): 支持 *.ggufz 文件
