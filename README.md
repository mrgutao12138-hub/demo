# 本机对象存储（MinIO）

在自己电脑上用 Docker 跑一套 S3 兼容的桶服务。文件写在本机 `./data` 目录，不经过云端。

完整步骤见 [操作手册.md](./操作手册.md)。

```bash
cp .env.example .env
docker compose up -d
```

- 控制台：http://127.0.0.1:9001
- S3 API：http://127.0.0.1:9000
- 默认账号 / 密码：`minioadmin` / `minioadmin`
- 默认桶：`files`
- 本地数据：`./data`
