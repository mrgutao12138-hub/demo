# demo

Docker 上一键部署的 S3 兼容对象存储（桶服务），基于 MinIO。

```bash
cp .env.example .env
docker compose up -d
```

- S3 API：http://localhost:9000
- 控制台：http://localhost:9001
- 默认账号 / 密码：`minioadmin` / `minioadmin`（在 `.env` 里改）
- 默认桶名：`files`

上传、下载走 S3 API，把 SDK 的 endpoint 指到 `http://localhost:9000`，并使用 path-style。
