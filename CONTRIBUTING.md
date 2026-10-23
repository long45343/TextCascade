# Contributing to TextCascade (v1)

感谢你考虑为 TextCascade (v1) 贡献代码！

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

1. 从 `v1` 分支开 feature/bugfix 分支
2. 确保所有单元测试通过
3. PR 标题用英文，描述中文/英文皆可
4. CI 会自动运行构建和测试，绿了才能合并

## 代码风格

- 遵循 Kotlin 官方编码规范
- 命名：camelCase 变量/函数、PascalCase 类
- 注释推荐中文，doc 风格注释用于公开 API
- 谨慎引入新的运行时依赖——v1 专注于极简原生与零外部网络库依赖（由 JDK Socket / HttpURLConnection 承担），保持低于 10MB 内存基线

## 许可证

TextCascade 是 GPLv3 开源软件。提交代码即表示你同意在 GPLv3 下授权你的贡献。
