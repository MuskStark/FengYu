# FengYu Plugin CLI

```bash
fengyu plugin create my-plugin --id com.example.my-plugin
fengyu plugin dev my-plugin --port 4173
fengyu plugin validate my-plugin
fengyu plugin build my-plugin
fengyu plugin install my-plugin/dist-package/com.example.my-plugin-1.0.0.fyp --host http://127.0.0.1:24056
```

The dev command serves a sandboxed host simulator with hot reload and an RPC inspector. Build uses
a cross-platform ZIP writer and runs the same manifest checks as `validate` before producing `.fyp`.
