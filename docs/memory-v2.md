# 单会话角色记忆第二版

本说明适用于单会话角色的融合记忆路径。普通聊天、其他 Agent 记忆和远端会话仍使用各自原有规则。实现采用 Kotlin、Room 和 Compose，界面资源覆盖简体中文、繁体中文、英语、日语和韩语。

## 发送前总结记忆

用户消息先落库，再用同一套 Token 估算检查全部未总结整轮；本轮消息计入近期额度，但永远不进入总结目标。默认近期额度为8000，未总结量大于额度时，先总结记忆再回复。没有回执的整轮必须保留在窗口里，有回执的近期原话才可按剩余空间补入或移出。

普通批次从最旧未总结整轮开始，目标约为批量设置（默认2000）与超额部分中较大者，每包不超过4000。遇到单个完整轮次超过4000时，先处理它前面的短聊天，再单独处理该长轮；短包完成后即使降回门槛内，也在同一次等待内完成已排定的长包，然后回复。没有前置短聊天时只有长包一次。前置短聊天本身超过4000时按普通包上限分包，仍在同次等待内顺序完成。

长轮绝不拆句或拆轮，允许目标超过4000，但完整模型请求仍受可用输入容量限制：优先减少参考原话、旧记忆，仍放不下则沿用容量错误提示。长包完成后恢复正常门槛判断，不无条件处理所有后续长轮。每包重新读取原话和回执，无进展的同一批不能无限重试。

整个调用只显示一次“正在总结记忆…”等待状态。停止会取消未完成总结及本轮回复；已完成包和已入库条目保留。第一包失败不继续第二包。失败包不写完成回执，保留未总结状态，并按既有失败提示、总上下文退让和下次发送重试规则处理。回复结束、手动编辑记忆后不额外触发后台总结。

## 编号、逐条写入与版本

- 每次调用由程序生成本批原话S编号、辅助原话R编号、旧记忆M编号；新增操作按顺序获得N编号。失败的新增操作也占其编号位置，不能把后续编号前移。
- 输出为JSONL，只支持`add`、`update`、`cancel`、`complete`、`retract`，最后必须输出`{"done":true}`。每条通过校验后立即事务入库；坏行不回滚已成功条目。
- 证据仅接受本批S编号，辅助R编号不能作为证据。未知目标不能降级为新增；用户画像必须有用户原话证据。回复中的工具使用同一套写入器，证据限本轮用户S1。
- 只有输出完整结束且原话指纹仍一致才写完成回执。未完成输出可重试，已存操作按幂等标识跳过；连续缺少done按原策略拆多轮批次，单个完整轮次不拆。
- 更新或撤回将旧版标为作废，保留历史，查询给模型只提供当前有效版本。恢复会建立新的当前版本，历史仍可追溯。

## 拿不准与锁定保护

锁定或人工修改的记忆不能被模型直接覆盖；需要变更时另存一条待确认记忆。待确认内容独立注入上下文，由角色自行决定是否自然询问，不在回复末尾强行追加问题。按用户回合控制休眠与唤醒，重复生成同一用户消息不重复计数。

待确认内容首次被修改确认时，未锁定的原记忆可按规则作废；锁定的原记忆进入待处理队列。聊天在前台、总结和生成结束、回复已落库之后才逐条展示处理弹窗；支持当场编辑、保持原记忆或删除。编辑后继续保持锁定。

## 日期与来源

时间按证据消息说出当天解释，而非总结当天。离线五语词库能解析时以程序日期为准；范围、模糊词、多个日期或不支持的表达返回Unresolved，再采用模型日期或留空。习惯性表达不强行落到某一天。

记忆页显示完整原消息，原消息删除后显示保留快照和已删除提示。日期按界面语言展示今天、昨天、同年月日或跨年年月日。删除、编辑及编辑后重新生成会一次汇总受影响记忆，默认展示前5条并支持展开；取消不改消息或记忆。提交事务内再次检查确认范围，避免确认后新增关联被静默删改。

## 数据库与便携数据

数据库版本保持64。63→64保留旧数据并增加作废版本、待确认、幂等操作、来源快照和锁定处理队列相关字段与表。新实体通过统一DAO访问。消息变化触发器对融合记忆路径采用新的明确选择语义，其他路径继续保留原保护。

旧库直接升级到记忆第二版时，Room在到达60后选择新增的60→64路径，跳过历史60→61中的物理清理，但保留中间全部结构变更。61、62、63起点仍走各自后续迁移；新版字段建立后，对明确被替代或撤回的未锁定、非冲突记录统一标记历史，保留正文、人工标记、原话出处、向量及关联记录，不把来源失效标记改回有效。锁定与冲突记录继续保留原有保护。

此修复只保护尚未经过旧清理的数据。已经处于64的数据库不会重跑迁移；过去被旧版本物理删除的记忆不能凭新字段自动恢复，需要删除前的备份。迁移回归使用虚构数据和Room实际路径，覆盖53—63起点及人工、自动、锁定、冲突、撤回和来源失效情况；不能代替真实设备升级验收。

便携导入导出包含新字段及相关记忆数据，兼容旧数据缺省值，保持归属隔离和恢复事务；回执、来源有效性及状态不能与正文脱节。设备上的升级仍应备份并独立验证，JVM迁移测试不代表设备验收。

## 代码与回归入口

| 范围 | 主要入口 | 回归 |
|---|---|---|
| 窗口、连续总结 | `FusedCompanionPolicy`、`FusedCompanionSummary`、`CompanionMemoryStore` | `CompanionConsolidationCallCountTest`、`FusedCompanionSummaryTest`、`FusedCompanionConsolidationFitTest` |
| 流式写入、编号、日期 | `FusedMemoryStream`、`FusedMemoryWriter`、`FusedMemoryOperation`、`TimeWordEngine` | `FusedMemoryStreamTest`、`FusedMemoryWriterTest`、`FusedReplyMemoryMappingTest`、`TimeWordEngineTest`、`TimeWordOriginal61Test` |
| 迁移与触发器 | `CompanionMemoryV2Schema`、`NekobotDatabase` | `CompanionMemoryV2MigrationTest`、`CompanionMemoryV2TriggerTest`、`UpstreamMigrationTest`、`NekobotDatabaseMigrationChainTest` |
| 编辑删除及锁定确认 | `CompanionMemoryInteractions`、`CompanionMessageDecision`、`CompanionReviewReply`、`ChatViewModel` | `CompanionMemoryInteractionsTest`、`CompanionMessageDecisionTest`、`CompanionReviewReplyTest` |
| 页面与五语 | `FusedCompanionMemoryScreen`、记忆弹窗组件、五组`memory_v2.xml` | `MemoryV2ResourcesTest`、`CompanionMemoryDateTest`、`ChatSessionRuntimeTest` |
| 便携数据 | `PortableImportPlan`、`PortableMemorySourceIntegrity`及传输组件 | `PortableImportSchemaCoverageTest`、`PortableMemorySourceIntegrityTest`、相关导入回归 |

单元回归入口：`./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`。发布前还应执行`lintDebug`和`assembleDebug`，并独立验证真机、真实模型网络及升级流程。离线回归只使用合成数据；时间词夹具的范围、上游来源与许可见`app/src/test/resources/timewords/README.md`。
