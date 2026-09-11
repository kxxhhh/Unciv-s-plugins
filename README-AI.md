# Unciv AI Edition

基于 [Unciv](https://github.com/yairm210/Unciv) 的 LLM AI 玩家版 —— 让大语言模型（LLM）作为文明领袖进行游戏。

## 核心改动

在官方 Unciv 源码中注入了一个完整的 AI 决策引擎 (`core/src/com/unciv/ai/`)，允许游戏中的 AI 文明由 OpenAI 兼容的 LLM（如 GPT、Claude、本地模型等）来控制，替代内置的规则 AI。

## 构建

### 桌面版（已验证）

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :desktop:dist
# 产出: desktop/build/libs/Unciv.jar (~54MB)
```

运行：`java -jar desktop/build/libs/Unciv.jar`

### Android APK

**需要 x86-64 环境构建**（AAPT2 仅为 x86-64 提供，aarch64 / ARM 设备无法直接构建）。

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :android:assembleDebug
# 产出: android/build/outputs/apk/debug/Unciv-debug.apk
```

推荐在 GitHub Actions 或其他 x86-64 CI 上构建。

## 使用说明

### 1. 配置 API

启动游戏 → 主菜单 → **Options** → **AI** 选项卡：

| 设置项 | 说明 | 示例 |
|--------|------|------|
| 启用 AI 文明 | 开关 | ✅ |
| API Base URL | OpenAI 兼容端点 | `https://api.openai.com/v1` |
| API Key | 你的 API Key（明文存储于 GameSettings.json） | `sk-...` |
| Model | 模型名 | `gpt-4o` |
| 温度 | 0-2 | 0.7 |
| 最大 Tokens | 回复上限 | 2048 |
| 上下文级别 | Minimal / Balanced / Detailed | Balanced |
| 人格 | 8 种预设 | Balanced / Aggressive / ... |
| 控制模式 | Manual / Semi / Fully | SemiAutonomous |

### 2. 标记 AI 文明

目前在 `GameSettings.json` 中手动添加文明名到 `ai.llmControlledCivs` 列表：

```json
{
  "ai": {
    "enabled": true,
    "llmControlledCivs": ["罗马", "希腊"]
  }
}
```

（UI 选择器将在后续版本加入）

### 3. 开始游戏

创建新游戏，选择你标记的文明为 AI 玩家。当轮到 LLM 文明时，游戏会自动：
1. 读取游戏状态
2. 发送给 LLM
3. LLM 通过工具调用决定操作
4. 执行操作
5. 结束回合

## 支持的 LLM 操作

| 工具 | 说明 |
|------|------|
| `move_unit` | 移动单位到目标格 |
| `attack` | 攻击附近敌人 |
| `change_city_production` | 改变城市生产 |
| `research_technology` | 选择研究科技 |
| `found_city` | 开拓者建城 |
| `get_detail` | 查询详细信息 |
| `declare_war` | 宣战 |
| `make_peace` | 求和 |
| `end_turn` | 结束回合 |

## AI 架构

```
LLMTurnAutomation（回合编排）
├── GameStateSerializer → 游戏状态压缩
├── AiPersonality → 系统提示词
├── LLMProvider → HTTP 请求（Ktor Client）
├── Tools → 工具声明（JSON Schema）
├── ActionExecutor → 工具执行（调用游戏 API）
└── AiMemory → 跨回合记忆
```

## 安全说明

- **API Key 明文存储**：保存在 `GameSettings.json` 中（位于 Unciv 数据目录）。请不要在公开场合分享此文件。
- 建议使用有额度限制的 API Key，或通过本地中转代理使用。
- Debug 模式下日志会打印请求/响应内容（不含 API Key）。

## 文件结构

```
core/src/com/unciv/ai/
├── AiSettings.kt          # AI 配置数据类
├── OpenAiModels.kt        # OpenAI 协议 DTO
├── LLMProvider.kt         # LLM 接口 + OpenAI 实现
├── GameStateSerializer.kt # 游戏状态压缩
├── Tools.kt               # 工具 JSON Schema
├── ActionExecutor.kt      # 工具执行器
├── AiMemory.kt            # 记忆系统
├── AiPersonality.kt       # 人格系统
└── LLMTurnAutomation.kt   # 回合编排主循环
```

## 许可证

基于 Unciv（MPL 2.0）。AI 模块新增代码同样以 MPL 2.0 发布。