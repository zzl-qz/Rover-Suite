# 发布前检查清单

正式发布前，建议在干净工作区、目标 JDK 和目标 Docker 环境中完成以下检查：

```bash
# 升级前备份（指向你的 config 目录）
./deploy/scripts/backup-overlays.sh
# 或：./deploy/scripts/backup-overlays.sh deploy/docker/config

mvn -DskipTests package
mvn dependency:tree -DoutputFile=target/dependency-tree.txt

# 一键冒烟（build → up → health/overview/业务路径 → down）
./deploy/scripts/smoke-compose.sh
```

也可以逐步手跑：

```bash
docker compose -f deploy/docker/docker-compose.yml build
docker compose -f deploy/docker/docker-compose.yml up -d
curl -fsS -H "X-Rover-Admin-Token: rover-compose-gateway-admin-token" \
  http://127.0.0.1:8889/_manage/health
curl -fsS -H "X-Rover-Admin-Token: rover-compose-gateway-admin-token" \
  http://127.0.0.1:8080/_manage/health
curl http://127.0.0.1:9090/api/overview
curl http://127.0.0.1:8080/api/hello
docker compose -f deploy/docker/docker-compose.yml down
```

## 构件和许可证

- 为源码创建与版本号一致的 Git tag。
- 发布 JAR、SHA256 校验文件、依赖树、变更记录、`LICENSE` 和 `NOTICE`。
- 用 CycloneDX 生成对应 Tag 的 SBOM，例如：

  ```bash
  mvn org.cyclonedx:cyclonedx-maven-plugin:2.8.1:makeAggregateBom
  ```

- 检查 SBOM 中的许可证和已知漏洞，并确认前端 ECharts、Vue、Logo、图片和字体的来源说明完整
  （Admin 脚本在 `rover-admin/.../static/vendor/`，不再依赖公网 CDN）。
- 不要提前提交不对应正式 Tag 的静态 SBOM；SBOM 必须反映该版本实际依赖图。

## 文档和演示

- 中英文 README、Admin 使用手册、API、配置、部署、故障排查、插件和兼容性说明已同步。
- Admin 截图中的地址、服务名、时间、实例和请求数据已确认是演示数据或完成脱敏。
- Docker Compose 示例中的 token、端口映射、镜像 Tag 和 ACL 已替换为发布环境值；
  生产请改用 [`deploy/production/`](../deploy/production/)。
- 性能边界文档已补充目标机器、JDK、CPU、内存、压测工具和真实 P50/P95/P99 数据
  （见 [`troubleshooting-performance.zh-CN.md`](./troubleshooting-performance.zh-CN.md) 参照表）。

CI 和单元测试不是本项目的发布门槛；本项目采用真实构建、真实容器启动和接口冒烟检查作为最低验收。若后续引入 CI，
应将其作为额外质量门槛，而不是替代容器验收。
