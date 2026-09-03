# Contributing to TextCascade

感谢你考虑为 TextCascade 贡献代码！

## 构建

```bash
export ANDROID_HOME=/path/to/android/sdk
./gradlew assembleDebug
```

## 运行测试

```bash
./gradlew testDebugUnitTest --stacktrace
```

## 提 Pull Request

1. 从 `v2` 分支开 feature/bugfix 分支
2. 确保所有测试通过
3. PR 标题用英文，描述中文/英文皆可
4. CI 会自动运行构建和测试，绿了才能合并

## 代码风格

- 遵循 Kotlin 官方编码规范
- 命名：camelCase 变量/函数、PascalCase 类
- 注释推荐中文，doc 风格注释用于公开 API
- 谨慎引入新的运行时依赖——现状仅 OkHttp 一项（spec 决策引入，承担 WebSocket/TLS）；新增依赖须先在 spec 中论证并经确认

## 许可证

TextCascade 是 GPLv3 开源软件。提交代码即表示你同意在 GPLv3 下授权你的贡献。
