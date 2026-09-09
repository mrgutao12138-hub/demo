# 扩展 02 · 本地大模型 Ollama

```
┌────────────────────────────────────────────────────────────────────┐
│  不要在学主线时插入本章。                                            │
│  尤其不要在学习第 3 周（part-02 Hello World）就上本地模型。            │
│  本地环境会引入 GPU/内存/模型下载等新变量，掩盖 Spring AI 本身的学习曲线。│
└────────────────────────────────────────────────────────────────────┘
```

> 同学们，本地大模型很酷，同事演示时也特别有冲击力。  
> 但作为老师，我必须先把「酷」和「该用」分开。  
> 这一篇讲 Ollama 是什么、Spring AI 怎么接、和 DeepSeek 云怎么混用——以及为什么它排在 extensions 而不是第三周。

---

## 1. 何时需要本地大模型：四个真实动机

企业里提出「能不能本地化」，通常不是技术同学一时兴起，而是下面四类约束之一（或叠加）：

### 1.1 数据不出域

- 用户输入含客户名、合同条款、未公开财务数字
- 制度要求 **文本不能离开公司机房**，哪怕加密传到公有云也不行
- 此时云端 API（含 DeepSeek、GPT、国产云）都要过合规评审；**本地推理**成为选项

### 1.2 断网机房、边缘节点

- 工厂车间、船舶、军工隔离网：没有稳定公网
- 需要 **离线可运行** 的问答或轻量 Agent（通常工具仍要连内网库，只是模型推理在本地）

### 1.3 打样与成本沙盘

- 想在大规模采购前，用开源权重 **试模型脾气**（工具调用准不准、中文如何）
- 开发环境不想每次 commit 都烧云端 token

### 1.4 混合架构的「敏感通道」

- 非敏感总结走云；含 PII 的工单摘要走本地（扩展 03 会讲路由）

**不成立的动机（请警惕）：**

- 「本地免费所以学本地」——电费、显卡、人力排障也是钱
- 「本地一定更安全」——模型进程若被注入恶意提示，照样能误导用户；安全是系统问题
- 「不用学云端了」——Spring AI、Advisor、工具循环、RAG 照样要学，只是 HTTP 终点从公网变 `localhost`

---

## 2. Ollama 是什么：本机模型进程的直觉解释

把 Ollama 想成 **跑在你电脑或服务器上的一个「模型守护进程」**，类似：

- 你熟悉的数据库：应用连 `localhost:5432`，不关心 Postgres 内部怎么存页
- Ollama：应用连 `localhost:11434`，发「聊天补全」请求，不关心权重文件怎么加载到 GPU

### 2.1 它做了什么

1. **管理模型文件**（pull、list、rm）——像 Docker 镜像，但对象是 GGUF 等权重
2. **加载到内存/显存**并提供推理
3. **暴露 HTTP API**，且默认带 **OpenAI 兼容** 的聊天端点（版本演进中细节可能微调，以你安装的 Ollama 版本为准）

### 2.2 和「直接跑 Python 脚本」的区别

- Ollama 帮你处理了模型格式、量化、部分硬件适配
- Spring AI 不需要在 JVM 里嵌 Python；**仍用 HTTP + ChatClient**，与云端心智一致
- 运维可以单独升级 Ollama，而不必重打 Java 包

### 2.3 一张简图

```
┌──────────────┐     HTTP (OpenAI 兼容)      ┌─────────────────┐
│ Spring Boot  │ ──────────────────────────► │ Ollama 进程      │
│ ChatClient   │   localhost:11434             │  llama3 / qwen…  │
└──────────────┘                               └────────┬────────┘
                                                        │
                                                        ▼
                                               CPU / GPU / 内存
```

---

## 3. Spring AI 2.0：spring-ai-starter-model-ollama

### 3.1 依赖

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-ollama</artifactId>
</dependency>
```

学习实验时 **不要** 与 `spring-ai-starter-model-deepseek` 同工程乱引；单独 `sandbox-ollama` 更清晰。

### 3.2 最小配置

```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: qwen2.5:7b
          temperature: 0.3
```

`model` 必须是 `ollama list` 里已有的名字，常见写法带 tag，如 `llama3.2:3b`。

### 3.3 代码形态（与主线相同）

```java
@RestController
public class LocalChatController {

    private final ChatClient chatClient;

    public LocalChatController(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @GetMapping("/local")
    public String ask(String q) {
        return chatClient.prompt().user(q).call().content();
    }
}
```

**老师强调：** 第三周 Hello World 请用 DeepSeek 云。你要验证的是「Spring AI 管线通了」，不是「显卡驱动装了没」。

### 3.4 Ollama 侧准备（命令级）

```bash
# 安装 Ollama 后
ollama pull qwen2.5:7b
ollama list
ollama serve   # 多数安装方式已自带后台服务
```

模型第一次 pull 会下载数 GB，**不要在公司培训教室开局就 pull 70B**。

---

## 4. 模型体积、内存、CPU/GPU、速度：参差很大

本地模型不是「一个小 jar」。选模型是在 **能力、速度、资源** 三角里做取舍。

### 4.1 体积与量化

| 常见规模 | 磁盘约略 | 适合场景 |
|---|---|---|
| 3B～8B | 2～6 GB | 笔记本试玩、简单摘要 |
| 14B～32B | 8～20 GB | 工作站、小型服务器 |
| 70B+ | 40 GB+ | 多卡或高端单卡，仍可能慢 |

后缀如 `Q4_K_M` 表示量化，体积更小、略损精度——**工具调用和 JSON 输出往往先受损**。

### 4.2 内存与显存

- **纯 CPU**：能跑，但 token/s 可能让人崩溃；多轮 Agent 更明显
- **GPU**：显存要装下模型 + KV cache；爆显存会退到 CPU 或 OOM
- 服务器规划：给 Ollama **独占** 机器往往比和 Java 堆抢内存省心

### 4.3 速度对 Agent 的影响

工具循环 = 多轮模型调用。云端 DeepSeek 一轮 1～3 秒可能可接受；本地 7B CPU 每轮 10～30 秒，**用户体验断崖**。

工程结论：

- 本地更适合 **单轮或两轮** 的敏感摘要
- 复杂多工具决策链，仍建议 **云端推理 + 本地只做 embedding**（part-05 的 Transformers 路线）

### 4.4 工具调用能力参差

不是每个 Ollama 模型都靠谱地支持 function calling。选型时要：

1. 查模型卡片是否强调 tool use
2. 用 part-04 同款 `@Tool` 跑 10 条用例
3. 对比云端 DeepSeek 的 pass rate

**不要用 3B 模型否定「Agent 不行」——可能是模型档位的锅。**

---

## 5. 与 DeepSeek 云的混合架构

成熟企业很少「全本地」或「全云」二选一，而是 **按数据分级路由**（扩展 03 细讲，这里先建立直觉）。

### 5.1 典型分工

```
用户请求
    │
    ▼
┌───────────────┐
│ 分级 / 路由    │  Java 层：看数据标签、用户角色、问题类型
└───────┬───────┘
        │
   敏感 ├────────────────► Ollama ChatClient（本地）
        │
   一般 ├────────────────► DeepSeek ChatClient（云）
        │
   复杂推理（无敏感）────► DeepSeek reasoner / 高阶云模型
```

### 5.2 工具与数据库仍在一处

无论模型在哪，**查库存的 `@Tool` 仍在你的 Spring 服务里**。本地模型只决定「怎么说、选哪个工具」；不能把 ERP 塞进 Ollama 进程。

### 5.3 RAG 的本地组合

常见做法：

- **Embedding**：`spring-ai-starter-model-transformers` 本地向量（与 part-05 一致）
- **生成**：敏感问答走 Ollama；一般制度解读走云

向量库（pgvector 等）仍在你的基础设施上，与模型进程解耦。

### 5.4 失败与降级

- Ollama 进程挂了：路由层应能 **failover 到云**（若合规允许）或返回明确错误
- 云不可用：本地只能答 **检索+模板化** 的窄能力，不要假装全功能

---

## 6. 配置对照：Ollama vs DeepSeek 云

### 6.1 DeepSeek（主线）

```yaml
spring:
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      chat:
        model: deepseek-v4-flash
        temperature: 0.3
```

### 6.2 Ollama（本地实验）

```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: qwen2.5:7b
          temperature: 0.3
```

注意：Ollama **通常不需要 api-key**（除非你自己在前面加了反向代理鉴权）。别把「没 key」当成「没安全」——`localhost` 绑定与防火墙仍要管。

### 6.3 同一应用两个 ChatClient（预告扩展 03）

```java
@Configuration
public class MultiModelConfig {

    @Bean
    ChatClient cloudChatClient(ChatClient.Builder builder) {
        return builder.build(); // 默认绑定 spring.ai.model.chat 指定的云模型
    }

    @Bean
    ChatClient localChatClient(
            OllamaChatModel ollamaChatModel,
            ChatClientBuilderConfigurer configurer) {
        ChatClient.Builder builder = ChatClient.builder(ollamaChatModel);
        configurer.configure(builder);
        return builder.build();
    }
}
```

多 Bean 的正确姿势在扩展 03 展开；**主线阶段一个 ChatClient 就够。**

---

## 7. 运维与排障清单

### 7.1 常见问题

| 症状 | 排查 |
|---|---|
| Connection refused | Ollama 没启动；`base-url` 错；Docker 网络隔离 |
| model not found | `ollama pull`；yml 里名字与 `ollama list` 不一致 |
| 极慢 | 模型太大、无 GPU、并发过高 |
| 工具从不调用 | 换模型；检查 `@Tool` 描述；降 temperature |
| 中文胡言 | 换中文优化权重（Qwen、DeepSeek 蒸馏版等） |

### 7.2 与 Java 进程部署

- 开发机：Ollama 本机 + Spring Boot IDEA 跑，最简单
- 生产：Ollama 常单独容器/虚拟机；Java 通过内网 DNS 访问，不要硬编码 `localhost`（除非同 pod 副作用容器）

### 7.3 观测

本地也要打日志：路由选了哪条模型、每轮耗时、是否 fallback。管理层不会因为你「本地」就原谅三十秒无响应。

---

## 8. 安全：本地不是免检区

- Ollama 默认监听要限制在 **内网接口**，不要裸露到公网
- 若暴露兼容 API，应加 **API Key 或 mTLS**，与云端同样纪律
- 本地模型照样 **幻觉**；工具权限仍由 Java 控制
- 模型文件来源要可信，防止供应链投毒

---

## 9. 学习路径上的纪律：为什么不是第 3 周

part-02 的验收标准是：

- JDK、Maven、Spring Boot 4、Spring AI 2.0 自动配置理解
- `ChatClient` 同步/流式/结构化跑通
- **第一笔云端 API 费用**让你建立 token 成本意识

若第 3 周就上 Ollama，你会并行踩：

- 显卡驱动、CUDA、磁盘空间
- 模型下载失败、量化选型
- 「慢」究竟是网络还是算力

最后 **说不清 ChatClient 和 ChatModel 的关系**，工具循环更没学到。

**老师定的顺序：** 云先通 → Agent 循环 → RAG → 综合项目 → 再本地 / 多模型。

---

## 10. 动手实验（主线合格后）

1. 安装 Ollama，pull `qwen2.5:7b` 或讲义推荐的小模型
2. 新建 `sandbox-ollama`，只引 `spring-ai-starter-model-ollama`
3. 复制 part-02 三个接口，对比延迟与输出质量
4. 接一个只读 `@Tool`，记录工具调用成功率 vs DeepSeek
5. 写一条「含假造客户名」的请求，理解为何合规会要求本地通道

---

## 11. 硬件选型参考：不是买最贵显卡就能上线

老师把常见部署场景写成表，便于你和运维同事对话。**数字随市场变化，只看量级。**

| 场景 | 建议模型规模 | 内存/显存粗估 | 预期体验 |
|---|---|---|---|
| 个人笔记本试玩 | 3B～7B Q4 | 16 GB 统一内存或 8 GB 显存 | 单轮聊天可接受 |
| 部门内网 PoC | 7B～14B | 32 GB RAM 或 12～16 GB VRAM | 简单摘要、非多工具 |
| 机房 CPU -only | 7B 量化 | 32～64 GB RAM | 慢，适合夜间批处理 |
| 生产敏感通道 | 14B～32B + GPU | 按模型卡片 | 要与云端评测对比 |

**误区纠正：** 「70B 一定比 7B 适合 Agent」—— 70B 在弱硬件上反而因降速导致 **多轮工具超时**，综合体验更差。

---

## 12. Docker 与 systemd：Ollama 怎么养在生产

### 12.1 Docker 示意（单机）

```bash
docker run -d \
  --name ollama \
  --gpus all \
  -p 11434:11434 \
  -v ollama-data:/root/.ollama \
  ollama/ollama
```

Spring Boot 在同一宿主机时 `base-url: http://127.0.0.1:11434`；在 Kubernetes 时改为 **Service DNS**，并对 11434 做 **NetworkPolicy** 限制只有 Agent 命名空间可访问。

### 12.2 与 Java 进程的资源隔离

不要把 Ollama 和 Java 堆 **抢同一台 32G 机器的全部内存**。经验法则：

- Ollama 预留够模型 + KV cache
- JVM `-Xmx` 保守（如 4G），Agent 线程池限流

否则 GC 停顿与推理争抢会导致 **双端超时**。

### 12.3 模型预热

冷启动第一次请求会加载权重，可能数十秒。生产在发布脚本里：

```bash
curl http://ollama:11434/api/generate -d '{"model":"qwen2.5:7b","prompt":"ping"}'
```

再摘负载均衡流量。

---

## 13. 本地模型清单：讲义实验推荐（2026 初）

以下名字供实验选用，**以 `ollama library` 当日可用为准**：

| 模型 tag（示例） | 特点 | 工具调用 | 老师建议 |
|---|---|---|---|
| `qwen2.5:7b` | 中文较好 | 中等 | 首选实验 |
| `llama3.2:3b` | 极小 | 偏弱 | 只测通路 |
| `deepseek-r1` 蒸馏版（若有） | 推理向 | 视版本 | 对比云端 reasoner |
| `mistral:7b` | 英文强 | 中等 | 跨境子公司场景试 |

每换一个 tag，**重跑 part-04 的十条工具用例**，不要只聊「你好」。

---

## 14. 混合云落地故事（虚构但典型）

某制造企业三条线：

1. **工单摘要（含工人姓名）** → 路由到 Ollama `qwen2.5:7b`，不出厂区网闸。  
2. **库存与交付异常分析（宽表已脱敏）** → DeepSeek v4-pro。  
3. **英文供应商邮件润色** → 集团采购的 OpenAI 兼容网关。

Java 里只有一个 `DecisionFacade`，内部 `ModelRouter` 选 `ChatClient`。审计表字段：`route=local|deepseek|vendor`，方便合规抽查。

**衔接 part-06：** 宽表仍在 Postgres，工具不分模型；换的是 **谁生成自然语言**。

---

## 15. 流式、并发与 Ollama 限制

Ollama 默认 **并发请求能力有限**。Agent 高峰时：

- 对本地路由加 **队列**（`Semaphore` 或消息队列异步摘要）
- 不要把「管理层百人同时刷新」直接打到单实例 Ollama
- 流式 `stream()` 在长答案时占住模型更久，接口层要设 **总超时**

Spring AI 侧代码与 DeepSeek 云相同，**瓶颈在 Ollama 进程**，Profiling 别看错层。

---

## 16. 课堂答疑

**问：第三周我偷偷装 Ollama 只玩一下行吗？**  
答：玩可以，但 **Hello World 作业仍必须交 DeepSeek 云端截图**，否则 part-02 验收无效。

**问：本地模型要不要 API Key？**  
答：Ollama 默认无 key；生产必须在前面加 **反向代理鉴权**，否则内网任何人可耗你 GPU。

**问：和 spring-ai-starter-model-transformers 有何区别？**  
答：Transformers starter 常在 **JVM 进程内**跑 embedding 小模型；Ollama 是 **独立进程**跑聊天大模型。part-05 embedding 用 Transformers，扩展 02 聊天用 Ollama，可以并存。

**问：工具循环本地太慢怎么办？**  
答：缩短循环（合并工具）、换小模型、或 **敏感步骤本地、分析步骤云**——回到混合架构。

---

## 17. Ollama 与 Spring AI 请求生命周期（逐步跟）

同学学 part-04 时跟过云端工具循环；本地只是把 **HTTP 终点** 从 `api.deepseek.com` 换成 `localhost:11434`。老师带你看一遍时间线：

1. 用户 POST `/api/chat`，Controller 调 `chatClient.prompt().user(...).tools(...).call()`。  
2. `ToolCallingAdvisor` 组装 Prompt，含工具 schema。  
3. Spring AI 的 `OllamaChatModel` 向 `http://localhost:11434/api/chat`（或兼容路径）发请求。  
4. Ollama 加载权重（若未加载则极慢），在 CPU/GPU 上推理。  
5. 模型返回纯文本或带 tool call 的结构；Advisor 解析。  
6. 若有 tool call，Java 执行 `@Tool`，结果写回消息列表，**回到步骤 3**——每一轮都是一次本地推理。  
7. 无 tool call 后返回最终字符串。

你会直观看到：**Agent 轮数 × 本地推理延迟 = 用户等待时间**。云端 2 秒一轮、本地 15 秒一轮，三轮就差 45 秒。这不是 Spring 慢，是算力账单。

---

## 18. 量化（Quantization）白话

权重文件可以是 FP16、INT8、Q4 等。数字越小，文件越小、内存越省，但 **表示精度损失**。

对决策 Agent 的影响顺序通常是：

1. **工具参数 JSON** 先坏（缺字段、类型错）  
2. **数字引用** 次之（把 1200 写成 120）  
3. **文笔流畅度** 最后才明显

所以本地 PoC 若用过度量化的小模型，得出「Agent 不可用」的结论，可能是 **量化过头**，不是 Agent 范式不行。

---

## 19. 与 part-05 Transformers embedding 并存示例

同一 `application.yaml` 里概念上可同时存在：

```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: qwen2.5:7b
    model:
      transformers:
        embedding:
          model: sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2
```

- **入库与检索**：Transformers embedding + pgvector（主线 part-05）  
- **敏感问答生成**：Ollama chat  
- **非敏感分析**：仍可走 DeepSeek 云（扩展 03 路由）

三套配置、三个职责，**不要在第三周就把它们全打开**。

---

## 20. 机房断网演练清单

适合 part-09 之后做红蓝演练：

1. 拔掉应用服务器外网网关（或防火墙 deny 443）  
2. 确认 Ollama 仍监听内网  
3. 确认 Agent 对 `SECRET` 级请求仍可用  
4. 确认对 `GENERAL` 级请求返回 **明确降级文案**，而不是 hang 住  
5. 恢复外网，确认云路由自动恢复  

演练报告归档到运维 Wiki，比 PPT 更能说服审计。

---

## 21. 为什么不建议学习期用 GPU 云主机自学 Ollama

有同学想「租一台 GPU 云主机装 Ollama 算本地」。问题不在钱，在于 **你又多了一套 SSH、安全组、磁盘、驱动运维**，与 Spring AI 学习无关。学习期云端 DeepSeek 已经够用；真要做本地，等公司提供 **标准化 Ollama 镜像** 再碰。

---

## 22. 本地模型选型决策树（文字版）

```
开始
  │
  ├─ 合规是否禁止上云？ ─否→ 主线 DeepSeek，本地仅作实验
  │
  └─是
      │
      ├─ 是否有 GPU？ ─否→ 仅 7B 以下 + 接受慢 + 限制单轮工具
      │
      └─是
          │
          ├─ 是否需要多工具 Agent？ ─是→ 必须跑工具评测，不达标则「敏感单轮本地 + 分析走内网专线云」
          │
          └─否→ Ollama 摘要/分类可上线
```

把决策树贴进架构评审 PPT，避免领导以为「买显卡 = 全能 AI」。

---

## 23. spring-ai-starter-model-ollama 自动配置心智图

```
OllamaApi
    └── OllamaChatModel
ChatClient.Builder (prototype)
ToolCallingAdvisor（与云端相同机制）
```

与 DeepSeek starter 的 **对称性** 是学习重点：换的是 `OllamaChatModel` 实现，Advisor 哲学不变。  
part-03 作业若要求画 starter 心智图，Ollama 版可作为 extra credit，**不作为第三周必交**。

---

## 24. 性能基准记录表示例

| 模型 | 硬件 | 单轮首 token | 工具循环 3 轮总耗时 | 工具 pass rate |
|---|---|---|---|---|
| deepseek-v4-flash 云 | — | 0.8s | 4.2s | 97% |
| qwen2.5:7b Ollama | RTX 4090 | 0.3s | 6.1s | 72% |
| qwen2.5:7b Ollama | CPU only | 2.5s | 38s | 65% |

用你自己的机器填表。**没有数字不要选型。**

---

## 25. 与 DevOps 协作：Ollama 版本锁定

`ollama/ollama:latest` 不要直接上生产。  
镜像 tag 锁定小版本；模型 manifest 锁定 digest。  
升级 Ollama 或模型权重前，在 staging **重跑评测集**——本地模型「静默变聪明或变笨」时有发生。

---

## 26. 长案例：车间边缘节点的 Ollama 部署叙事

某企业在注塑车间放一台工控机，16G 内存、无独显，运行 Ollama `qwen2.5:3b` 做 **设备告警日志的中文摘要**。车间网络与办公网隔离，办公网的 Spring Boot Agent 通过 **内网单向网闸** 调车间 MCP（扩展 05）或 HTTP 代理，不在车间跑完整 Agent。

为什么不在车间跑 Spring Boot？因为 JVM + Ollama + 监控叠加后内存紧张；车间只需要 **摘要**，不需要多工具 ERP 查询。重分析仍发生在办公网 DeepSeek。

这个案例说明：

1. Ollama 常常是 **能力降维** 后的本地组件，不是数据中心里唯一大脑。  
2. 「本地」可以发生在 **边缘**，不一定是你开发笔记本。  
3. 与主线 part-06「宽表在中心库」不矛盾：边缘只碰当日日志，不碰全量 ERP。

你们第三期项目不必复制此架构，但答辩时若领导问「本地怎么用」，可用此叙事展示 **边界感**。

---

## 27. CPU 推理优化清单（运维向）

- 关闭不必要的桌面环境，Linux server 最小安装。  
- `OLLAMA_NUM_PARALLEL` 等环境变量按官方文档限制并发（名称以版本为准）。  
- 避免与重型 ETL 同机抢磁盘 IO（模型加载读盘）。  
- 监控 `ollama ps` 常驻模型数，防止多人 pull 不同模型导致频繁换入换出。  
- 对 Agent 路由加 **队列**，削峰填谷。

---

## 28. 与 spring-ai-starter-model-transformers 的课堂对比

| 维度 | Transformers（JVM 内 embedding） | Ollama（独立进程 chat） |
|---|---|---|
| 进程 | 与 Spring 同 JVM | 独立 |
| 典型用途 | 向量、小模型 | 对话生成 |
| 内存 | 吃 JVM 堆外/堆 | 吃系统 RAM/VRAM |
| part-05 | 主线 RAG 入库 | 不用于主线默认 |
| 扩展 02 | 可并存 | 本章主角 |

记住：**part-05 不等于扩展 02**。前者是向量；后者是本地聊天。混谈会导致 pom 引依赖时一头雾水。

---

## 29. Ollama 模型卡片怎么读（老师带读字段）

打开 Ollama 模型页或 `ollama show <model>` 时，重点看：

- **Parameters（参数量）**：越大通常越强，也越吃内存。  
- **Quantization（量化级别）**：Q4、Q8 等，影响体积与精度。  
- **Context length（上下文长度）**：Agent 多轮 + RAG 片段会吃上下文；太小会截断工具结果。  
- **License**：商用是否允许，法务要归档。  
- **Tool / Function calling 支持**：卡片或社区评测是否提及；没有提及则默认「要实测」。

不要只看「中文榜排名第一」就 pull；**你的评测集才是榜**。

---

## 30. 开发机与生产机 Ollama 策略差异

| 环境 | 策略 |
|---|---|
| 开发机 | 可随时 pull 新模型，重启无所谓 |
| 测试机 | 锁定 tag，与生产一致 |
| 生产机 | 禁止 `pull latest`；变更走工单 + 评测 |

开发机上跑得飞，生产 CPU 机房跑得慢，是 **常态**。性能测试必须在 **目标硬件** 上做。

---

## 31. 混合架构下的提示词注意

本地小模型上下文窄，system prompt 要 **更短更硬**：

```text
规则：
1. 必须先调用工具再答数字。
2. 不知道就说不知道。
3. 输出结构：结论/依据/风险。
```

云端 DeepSeek 可适度加长口径说明；本地模型塞一万字制度全文会 **挤掉工具结果**。  
RAG 召回 topK 对本地路由应 **更小**，例如 3 而非 8。

---

## 32. part-02 作业与 Ollama 的边界（再次强调）

part-02 验收要提交：

- DeepSeek 云端调用成功截图或日志（脱敏）  
- `DEEPSEEK_API_KEY` 环境变量说明  
- 同步、流式、结构化三个接口

**不接受** 用 Ollama 截图替代上述验收。  
Ollama 实验记入个人笔记即可，不计入主线学分。  
老师重复第三遍，是因为每年都有同学「本地跑通了以为 week3 过了」。

---

## 33. 笔记本安装 Ollama 后的第一课（命令清单）

```bash
# 1. 确认服务
curl http://localhost:11434/api/tags

# 2. 拉模型（示例）
ollama pull qwen2.5:7b

# 3. 命令行试一句
ollama run qwen2.5:7b "用一句话解释什么是库存周转"

# 4. Spring Boot 启动后 curl 自己的接口
curl -X POST http://localhost:8080/api/chat -d 'MAT-10023库存如何？'
```

四步都通，才算「Ollama 与 Spring 握上手」。任一步失败，先别写路由，先修环境。

---

## 34. 何时从 Ollama 退回「专线云」而非公有云

国资子公司有时 **禁止公有云** 但允许 **集团私有云上的 DeepSeek 专机或国产大模型专机**。  
此时「本地 Ollama」不是唯一选项；若专机 pass rate 远高于本地 7B，应优先专机，Ollama 只兜底断网。

**技术选型是约束求最优，不是本地原教旨主义。**

---

## 35. 与 part-07 安全章的提前衔接

part-07 会讲审计、限流、密钥。本地 Ollama 额外要记：

- 进程监听地址绑定  
- 模型文件目录权限  
- 谁可以 `ollama pull` 新模型（供应链）  

这些写进 **安全基线检查表**，与 `DEEPSEEK_API_KEY` 同级对待。云端 key 泄露会上新闻；本地 Ollama 裸露在公网同样会上新闻。

---

## 36. 收束：Ollama 在讲义宇宙中的坐标

用一句话把扩展 02 钉在墙上：

> **Ollama 是合规与离线场景下的推理插座；Spring AI 是插座上面的电器标准；主线 DeepSeek 是你学会装电器时用的示范电源。**

装电器（Advisor、Tool、RAG）学会之前，不要忙着买插座（显卡、Ollama、模型盘）。  
电器学会之后，插座即插即用——`spring-ai-starter-model-ollama` + `base-url` 改 localhost，代码层几乎不动。

最后提醒：若领导问「我们能不能全面本地化」，你的标准回答是 **「可以用评测集证明哪些场景本地化，其余仍建议云或专机」**——这句话本身，就是 part-09 工程化思维。

扩展 02 读完，请回到 `01-总学习路线图.md` 最下方「extensions 换模型、本地、MCP 深水」那一行——你会明白为什么它排在 part-09 之后，而不是第三周。

Ollama 章节没有要求你成为 GPU 专家；它要求你 **在正确的阶段** 知道本地推理的代价与收益，并能用 Spring AI 同一套 `ChatClient` 接上去。做到这一点，扩展篇 02 的任务就完成了。

---

## 37. 什么时候才应该用 · 和主线如何衔接

### 什么时候才应该用

- 合规或网络约束 **明确禁止** 业务文本上云
- 已有 **GPU/内存资源** 和运维能力，不是学生笔记本硬扛 70B
- 云端 Agent 已稳定，需要 **敏感子集降级到本地**
- 需要 **离线演示**（客户机房无公网）

### 不要在什么时候用

- part-02～part-04 学习期，用来逃避申请 API Key
- 没有评测集，指望本地模型「凑合」上线多工具 Agent
- 团队无人懂 Ollama 升级与模型漏洞，「装一次不管了」

### 和主线如何衔接

| 主线章节 | 衔接 |
|---|---|
| part-02 | 同一 `ChatClient` API；第三周仍用 DeepSeek |
| part-03 | `ChatClientBuilderConfigurer` 为多 ChatClient 埋伏笔 |
| part-04 | 工具循环不变；本地需单独测 tool calling |
| part-05 | 本地 embedding + 本地/云生成分离组合 |
| part-07 | 路由、审计、降级策略 |
| part-09 | 部署拓扑：Ollama 侧车 vs 独立节点 |
| 扩展 03 | 多模型路由的完整工程实现 |

本地模型是 **合规与成本杠杆**，不是 Spring AI 的入门教具。先把云上的 Agent 跑顺，你才有资格谈「哪些请求值得留在本机」。

---

*扩展篇 02 · 完。下一篇：多模型路由与国产云。*
