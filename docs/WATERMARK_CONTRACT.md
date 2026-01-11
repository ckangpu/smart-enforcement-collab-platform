# 水印契约（Watermark Contract）

本契约用于“文件预览水印”的长期稳定性：代码必须完全配置驱动，后续若要调整水印样式，只允许修改 `application.yml`，不允许改代码。

## 1. 变量与渲染规则（严格）

### 1.1 允许变量（只允许以下 4 个）

- `{username}`：当前查看者用户名
- `{phone_last4}`：当前查看者手机号后四位（不足四位则尽量取末尾；为空则为空串）
- `{timestamp}`：水印生成时间
- `{tag}`：固定标签（由系统根据 variant 决定，禁止从请求参数/用户输入传入）

禁止引入任何其他变量（例如 `{ip}`、`{userId}` 等）。

### 1.2 时间格式（固定）

- `timestamp` 固定格式：`yyyy-MM-dd HH:mm:ss`
- 时区必须固定为：`Asia/Shanghai`

说明：即便服务器系统时区不同，也必须以该固定时区输出。

### 1.3 tag 固定映射（禁止外部输入）

- external：`EXTERNAL`
- client：`CLIENT`
- internal：`INTERNAL`

## 2. 模板（默认值）

### 2.1 external（必须一字不差）

```
"{tag} | {username} | {phone_last4} | {timestamp}"
```

且 `{tag}` 必须固定渲染为 `EXTERNAL`。

### 2.2 client（建议默认）

```
"CLIENT | {username} | {timestamp}"
```

### 2.3 internal（建议默认）

```
"INTERNAL | {username} | {timestamp}"
```

## 3. 默认参数（external 必须写死为契约默认，配置可覆盖）

external 默认参数（契约默认值）：

- angle：`-35`
- opacity：`0.18`
- fontSize：`22`
- densityMultiplier：`1.0`

## 4. 安全与输出要求（强制）

- external 输出必须为 image-based PDF（不可复制文本层）；`PDFTextStripper` 提取文本必须为空
- 输出 PDF 必须清理：metadata、embedded files、AF（Associated Files）

