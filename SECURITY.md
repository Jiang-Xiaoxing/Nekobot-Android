# 安全策略

如果你发现 NekoBot Android 中存在安全漏洞，请按照本文件的流程私下报告，**不要**通过公开 Issue、Discussion 或评论区披露细节。

## 支持的版本

本项目迭代较快，安全修复只针对最新 Release 发布。

| 版本 | 支持状态 |
| --- | --- |
| 最新 Release（见 [Releases](https://github.com/asukaneko/Nekobot-Android/releases/latest)） | ✅ 接收安全修复 |
| 更早版本 | ❌ 请升级到最新版本 |

## 如何报告漏洞

请使用 GitHub 的私密漏洞报告功能：

👉 [私下报告漏洞](https://github.com/asukaneko/Nekobot-Android/security/advisories/new)

报告时请尽量包含：

- 受影响的版本号（可在「更多 → 关于」中查看）
- 设备型号与 Android 版本
- 漏洞描述与影响评估（如数据泄露、凭据暴露、任意代码执行等）
- 复现步骤，如有 PoC 或截图请一并提供（注意脱敏，不要附带含个人对话数据或 API Key 的备份文件 / 数据库 / 日志）
- 你认为可行的修复建议（可选）

## 响应承诺

- **确认**：收到报告后 72 小时内确认
- **评估**：7 天内给出严重程度评估与处理计划
- **修复**：根据严重程度尽快发布修复，随下一个版本发布
- **披露**：修复发布后再公开漏洞细节；在修复发布前，请对漏洞信息保密

## 适用范围

**属于本项目范围：**

- 本仓库内的 Android 客户端源码
- 官方 GitHub Release 发布的 APK（`asukaneko/Nekobot-Android`）

**不属于本项目范围：**

- 配套后端服务器（独立项目，请向其维护者报告）
- 第三方 AI 服务商的接口与其返回内容
- 用户自行搭建 / 修改的服务器端环境
- 已 root 或解锁 Bootloader 设备上的风险
- 需要物理接触已解锁设备才能实施的攻击
- 暴力破解、社工、垃圾报告等无实际意义的行为

## 安全设计说明

供安全研究人员参考的若干事实：

- API Key、OAuth 凭据等敏感数据通过 Android Keystore 加密后存储在本机
- 应用网络安全配置禁止明文 HTTP，服务器连接必须使用 HTTPS
- 本地模式下所有数据（会话、角色、知识库等）仅保存在设备本地，不会上传
- 应用内更新仅从官方 GitHub Releases 检查和下载
- 请始终从 [官方 Releases 页面](https://github.com/asukaneko/Nekobot-Android/releases) 下载 APK，谨防第三方渠道的篡改版本

## 安全许诺（Safe Harbor）

对于善意遵守本策略进行的安全研究，我们承诺不会对其提起法律诉讼或提出投诉。
