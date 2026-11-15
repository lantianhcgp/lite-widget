# 任务看板视觉升级 Implementation Plan

> For Hermes: 依照 subagent-driven-development 分工与真实 RED/GREEN，审查后统一提交。本简报已获用户裁定，不重复询问设计选项。

Goal: 状态一眼可见优先，交付 0.3.10/code28。
Architecture: 维持 Canvas、显式 parcel.N.*、visibleIf 静态切换；模板生成器唯一真源；KanbanData 扩展可选时长与展示字段；聚合器只由 keeper start/last_seen 计算时长。
Tech Stack: Kotlin/Android Canvas、Python标准库、原有Robolectric/JUnit；不新增运行依赖、权限、定时链。

## 设计/边界（用户简报）
- 蓝/青运行中、琥珀受阻、灰等待、红事故文字胶囊；4px左轨与渐变面板。
- 已知进度实值，未知静态分段条+进行中，不用时长猜百分比。旧 percent=— 可保留兼容，新卡片不绑定它。
- 头部标题+状态计数+updated_at短格式MM-DD HH:MM。
- 任务名、状态、线路、备注分别分层；时长形如1h23m，缺失空。
- 4x4/2x4=8卡，4x2=4卡，2x2=2紧凑单状态卡；无4x1。
- elapsed可选非负整数分钟，上限2147483647；旧五字段任务仍有效。
- 续 feat/kanban-20261115；固定点68d3aa9；不动master/experiment，不安装手机。

## 顺序与测试缝
1. 基线：读模板/Renderer布局/契约/脚本/测试/签名CI，运行旧Python/JVM，记录输出。
2. KanbanData公共validate/fields：先新增elapsed可选/83min/缺失/非法与statusLabel/计数/progressLabel/unknown/displayNote测试→真实RED→最少实现→GREEN。旧percent测试不删不改弱。
3. 4x4 generate()公共接口：新增每卡中文状态chip/4px左轨/独立note/常在已知或未知条/头行计数时间断言→RED→生成4x4→GREEN；使用原Renderer MCP隔离预览（明确fixture、不替换真实看板），看图及逐卡放大。
4. 其他3尺寸：同一card构件适配；所有尺寸槽数/可见谓词/禁止4x1/窄屏几何断言→RED→GREEN。MIUI测试尺寸138x168/344x160/138x385/285x385/285x168 dp。
5. 聚合器子任务独立：build_tasks与CLI公共边界先RED，keeper start/last_seen有效时间计算；重复输入幂等，wrapper最早start/最晚last_seen，inactive冻结值，缺失不编造。原39项保留。最终两份脚本逐字节同步并验证。
6. 发布门：versionName=0.3.10/code28；precheck/JVM/Python/generator --check/独立SPEC与QUALITY审查。上传精确分支；CI原AndroidCanvas、MCP、像素门全部绿，旧证据不覆盖。
7. 取包：exact SHA/run/attempt/artifact；验包名版本、ZIP CRC、签名与钉死keystore/旧包一致、包内模板/schema；SHA256；报告与APK在Documents，证据在GitHub分支qa/kanban-v2。装机归发起方，未安装不声称桌面已验。

## 90分钟盒
开始18:56:56 CST。优先4x4真实图，随后其他尺寸与一包CI；失败记录原样保留，不以计划/编译绿替代验收。
