# 发布与签名

正式分发的 APK 必须用**固定的 release keystore**签名；否则已安装的用户无法覆盖升级
（Android 会因签名不同拒绝安装）。

## 1. 生成密钥（维护者机器上，只做一次）

```bash
bash tools/gen-release-keystore.sh
```

产物（`.secrets/` 已被 `.gitignore` 忽略）：

| 文件 | 用途 |
|---|---|
| `.secrets/release.jks` | 签名密钥 |
| `.secrets/keystore.properties` | 构建读取的签名参数（随机口令） |

> **立刻备份整个 `.secrets/` 目录**（离线保存）。密钥丢失后，只能换新签名重新分发，
> 老用户需要卸载再装。

## 2. 本地构建签名包

```bash
./gradlew :app:assembleRelease
# 校验签名
"$ANDROID_HOME/build-tools/<版本>/apksigner" verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

没有签名材料时，release 构建会**直接失败**并提示先生成密钥——不会静默产出未签名包。

## 3. CI 发布（tag 触发）

在 GitHub 仓库的 **Settings → Secrets and variables → Actions** 添加：

| Secret | 值 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 .secrets/release.jks` 的输出 |
| `ANDROID_KEYSTORE_PASSWORD` | `keystore.properties` 里的 `storePassword` |
| `ANDROID_KEY_ALIAS` | `androidcontrol` |
| `ANDROID_KEY_PASSWORD` | `keystore.properties` 里的 `keyPassword` |

设置示例（不会把密钥打印到终端）：

```bash
base64 -w0 .secrets/release.jks | gh secret set ANDROID_KEYSTORE_BASE64
grep '^storePassword=' .secrets/keystore.properties | cut -d= -f2 | gh secret set ANDROID_KEYSTORE_PASSWORD
grep '^keyPassword='  .secrets/keystore.properties | cut -d= -f2 | gh secret set ANDROID_KEY_PASSWORD
echo -n androidcontrol | gh secret set ANDROID_KEY_ALIAS
```

## 4. 发一个版本

1. 更新 `app/build.gradle.kts` 的 `versionCode` / `versionName`；
2. 在 `CHANGELOG.md` 写下本版本的变化；
3. 打 tag 并推送：

```bash
git tag v0.3.0
git push origin v0.3.0
```

`release.yml` 会构建、校验签名，并把 APK 挂到 GitHub Release 上。

## 5. 安全边界

- `.secrets/` 永不提交；CI 里只在构建时解码到临时目录，构建结束后 runner 即销毁。
- 怀疑泄露时：生成新密钥（`--force`）、作废旧版本签名、通知用户卸载重装；
  同时轮换配对码（长辈端「高级信息 → 重新生成配对码」）。
