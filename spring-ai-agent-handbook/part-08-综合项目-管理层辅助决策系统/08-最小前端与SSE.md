# 08 · 最小前端与 SSE

> 前端可以丑。不能没有。领导看的是浏览器，不是 IDEA 控制台。

---

## 1. 目标

单页：

- 输入框、发送、新话题
- 消息区渲染 Markdown 五段（可用极简正则或直接 `innerHTML` 转义后替换换行）
- 流式：能看就看；不行就等完整 JSON
- 展示 sources 卡片
- 固定页脚免责声明

管理员另开 `/audit.html` 拉审计列表。更丑也行。

---

## 2. 后端流式（先给完整再给流）

毕业周若 SSE 与工具循环组合让你卡超过半天：**先做非流式 JSON**。演示不依赖打字机效果。

非流式：

```java
@PostMapping("/api/chat")
public DecisionReply chat(@RequestAttribute UserScope scope,
                          @RequestHeader("X-Conversation-Id") String cid,
                          @RequestBody ChatReq req) {
    return orchestrator.ask(scope, cid, req.message());
}
```

SSE 示例（内容流）：

```java
@GetMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> stream(@RequestAttribute UserScope scope,
                                            @RequestHeader("X-Conversation-Id") String cid,
                                            @RequestParam String message) {
    return proClient.prompt()
            .user(message)
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid))
            .toolContext(Map.of("userScope", scope))
            .stream()
            .content()
            .map(chunk -> ServerSentEvent.builder(chunk).build());
}
```

注意：流式时审计较难在结束前拿 usage。可 `doOnComplete` 另记一笔，或毕业演示用非流式。

---

## 3. 完整单页 HTML（放到 `src/main/resources/static/index.html`）

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8"/>
  <title>Decision Copilot</title>
  <style>
    body { font-family: sans-serif; max-width: 880px; margin: 24px auto; }
    #log { border: 1px solid #ccc; min-height: 320px; padding: 12px; white-space: pre-wrap; }
    .src { font-size: 12px; background: #f6f6f6; padding: 8px; margin-top: 8px; }
    footer { color: #666; font-size: 12px; margin-top: 16px; }
    textarea { width: 100%; height: 72px; }
  </style>
</head>
<body>
  <h1>Decision Copilot</h1>
  <p>用户 <input id="user" value="east.manager"/>
     会话 <input id="cid" size="36"/>
     <button onclick="newCid()">新话题</button></p>
  <div id="log"></div>
  <textarea id="q" placeholder="例如：WH-A 的 SKU-1001 可用库存多少？"></textarea>
  <p><button onclick="send()">发送</button></p>
  <footer>数字来自业务系统查询，AI 只负责归纳。重大决策请以源系统为准。本系统不能修改库存或下单。</footer>
<script>
function newCid() {
  document.getElementById('cid').value = crypto.randomUUID();
}
newCid();
async function send() {
  const user = document.getElementById('user').value;
  const cid = document.getElementById('cid').value;
  const message = document.getElementById('q').value;
  const log = document.getElementById('log');
  log.textContent += "\n\n你：" + message + "\n";
  const res = await fetch('/api/chat', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': user,
      'X-Conversation-Id': cid
    },
    body: JSON.stringify({ message })
  });
  const data = await res.json();
  log.textContent += "副驾：\n" + (data.rawMarkdown || JSON.stringify(data, null, 2));
  if (data.sources) {
    log.textContent += "\n来源：" + JSON.stringify(data.sources);
  }
}
</script>
</body>
</html>
```

上面脚本里的换行转义，若你直接保存为 HTML，把 `\\n` 写成 JS 的 `\n`。

---

## 4. 静态资源与 CORS

同一 Boot 进程提供页面，不必 CORS。若前后端分离，允许的 Header 要包含 `X-User-Id`、`X-Conversation-Id`。

---

## 5. 【必做】

1. 浏览器打开页面，完成评测集第 1、7、12、5、20 问。
2. 新话题后指代消失。
3. 手机或窄窗口也能提交（不要求精美）。

没有浏览器工具时，用 curl 把同样五问跑通，并保存响应文本，答辩时打开文本+审计。
