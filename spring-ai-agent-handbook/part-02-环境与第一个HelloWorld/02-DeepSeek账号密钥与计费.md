# 02 · DeepSeek 账号、密钥与计费

> 调模型不是「免费魔法」，是**按量付费的云服务**。  
> 本章带你走完账号、实名、建 Key、充值、看用量，并建立**密钥安全意识**。企业里 Key 泄露一次，账单和合规都能把你送走。

---

## 1. 为什么要单独讲这一章

很多同学急着写代码，跳过账号环节，结果：

- 没有 Key，`application.yml` 里占位符解析失败，项目起不来；
- Key 写进 Git，同事 fork 一下，你的配额被刷光；
- 不知道 flash 和 pro 的价差，学习期不小心一直开 pro，月底傻眼。

**先把「钥匙」和「电费」搞清楚，再敲 Hello World。**

---

## 2. 注册与登录

### 2.1 【必做】注册步骤

1. 浏览器打开 **DeepSeek 开放平台**（搜索「DeepSeek 开放平台」进入官网，选择「开放平台 / API」入口，不要和纯聊天产品混淆）。
2. 使用手机号或邮箱注册账号，按页面提示完成验证。
3. 登录后进入 **DeepSeek 开放平台控制台**（下文简称「控制台」）。

### 2.2 【必做】实名认证

国内云 API 通常要求实名后才能正式调用或提高额度：

1. 在控制台找到「实名认证」或「账户认证」。
2. 按提示提交个人或企业信息（学习期个人认证即可）。
3. 等待审核通过（通常几分钟到几个工作日）。

未实名时，可能无法创建 Key 或额度极低。卡在这一步不要硬写代码，先完成认证。

---

## 3. 创建 API Key

### 3.1 【必做】在控制台创建 Key

1. 进入控制台的 **API Keys**（或「密钥管理」）页面。
2. 点击「创建 API Key」。
3. 给 Key 起个能认出来的名字，例如：`spring-ai-handbook-local-张三`。
4. 创建成功后，页面会**只显示一次**完整 Key（通常以 `sk-` 开头）。
5. **立刻复制**到密码管理器或本地安全笔记；关闭页面后往往无法再查看完整密钥，只能作废重建。

### 3.2 【必做】写入本机环境变量

不要把 Key 贴进微信、钉钉、邮件、GitHub Issue。

按 `01-环境准备.md` 已学的命令设置：

**macOS / Linux：**

```bash
export DEEPSEEK_API_KEY="sk-你刚复制的密钥"
```

持久化写入 `~/.zshrc` 或 `~/.bashrc`（见上一章）。

**Windows PowerShell：**

```powershell
[System.Environment]::SetEnvironmentVariable("DEEPSEEK_API_KEY", "sk-你刚复制的密钥", "User")
```

验证：

```bash
# macOS / Linux
echo $DEEPSEEK_API_KEY

# Windows
echo $env:DEEPSEEK_API_KEY
```

### 3.3 与 Spring 配置的对应关系

项目里只写占位符，**不写真实 Key**：

```yaml
spring:
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
```

【易混】不要写成 `spring.ai.openai.api-key`。那是 OpenAI 兼容路径或 1.x 老帖子的写法，用官方 DeepSeek starter 时前缀必须是 **`spring.ai.deepseek`**。

---

## 4. 充值与计费意识

### 4.1 充值

1. 在控制台打开「余额 / 充值」。
2. 学习期建议先**小额充值**（具体门槛以控制台为准），够你跑几周实验即可。
3. 绑定支付方式按页面指引操作。

### 4.2 模型与价格（概念层）

计费一般按 **输入 token + 输出 token** 计。不同模型单价不同：

| 模型（讲义主线） | 典型用途 | 学习期建议 |
|---|---|---|
| `deepseek-v4-flash` | 快、便宜、日常对话与试错 | **Hello World 与练习默认用它** |
| `deepseek-v4-pro` | 更强推理，单价更高 | 决策类 Agent 后期再用 |
| `deepseek-chat` / `deepseek-reasoner` 等 | 别名或特定能力型号 | 以控制台文档为准，勿凭记忆硬写 |

**本章冻结：** `application.yml` 里写 `deepseek-v4-flash`。别一上来就 pro，除非你在做对比实验且接受账单。

### 4.3 【建议】用量与账单

1. 控制台定期查看 **用量统计 / 账单明细**。
2. 给自己设一个心理上限：例如学习期每天不超过 N 元，超额就停手查日志是不是死循环调模型。
3. 流式接口、结构化重试（`validateSchema`）都会多次调用模型，token 会比你想的多。

---

## 5. 限额、限流与失败心态

### 5.1 常见限制

- **QPS / 并发**：短时间大量请求可能 429。
- **日配额**：新账号或低等级实名可能有上限。
- **余额不足**：返回付费相关错误。

### 5.2 工程上怎么处理（本章先建立意识）

- 不要在 `for` 循环里无脑调 `chatClient`（后面会做限流与缓存）。
- 接口要设超时；用户端要有「服务繁忙」提示。
- Key 泄露后：**立即在控制台作废 Key**，新建一把，并检查 Git 历史有没有误提交。

---

## 6. 安全红线（【红线】必读）

1. **不要把 API Key 发到群聊、截图、录屏、博客、Git 仓库。**
2. **不要把 Key 写进 `application.yml` / `application-prod.yml` 再 commit。**
3. 若团队共用 Key，应走**密钥管理系统**或 CI 密文变量，不是 Excel。
4. 离职、换机、录完教程视频后，考虑**轮换 Key**。
5. 若 Key 曾误提交 Git：作废 Key → 清理历史（或咨询安全同事）→ 新建 Key。

讲义仓库里的 `.gitignore` 会忽略本地覆盖配置；**纪律在你手上**。

---

## 7. base-url 说明

Spring AI DeepSeek starter 默认 `base-url` 已是：

```
https://api.deepseek.com
```

一般**不需要**在 `application.yml` 里再写一遍。只有公司走代理网关、或控制台明确要求换 endpoint 时，才加：

```yaml
spring:
  ai:
    deepseek:
      base-url: https://api.deepseek.com
```

写错地址会导致连接失败或 404，排查时先确认你没乱改这一项。

---

## 8. 【必做】本章验收

1. 已在 DeepSeek 开放平台完成注册与实名（或确认当前账号状态允许创建 Key）。
2. 已在控制台创建至少一个 API Key，且**只保存在本机环境变量**。
3. 终端执行 `echo $DEEPSEEK_API_KEY`（Windows 用 `$env:DEEPSEEK_API_KEY`）能看到 `sk-` 开头的值。
4. 能口头回答：学习期默认模型叫什么？（`deepseek-v4-flash`）
5. 能口头回答：Spring 里 Key 的配置项全名是什么？（`spring.ai.deepseek.api-key`）

---

## 9. 下一章

账号和 Key 就绪后，打开 `03-从零创建SpringBoot4项目.md`，开始建工程骨架。
