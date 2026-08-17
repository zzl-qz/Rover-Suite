# Rover Demo UI

前端测试项目，用于测试 Rover Gateway 的代理转发和负载均衡功能。

## 快速开始

### 安装依赖
```bash
npm install
```

### 启动开发服务器
```bash
npm run dev
```

访问 http://localhost:3000

## 配置说明

### 网关地址
默认网关地址：`http://localhost:8080`

可在页面顶部修改；完整组件启动方式见上级 [Gateway 测试套件](../README.md)。

### 测试接口
- `/api/hello` - 基本连通性测试
- `/api/echo?msg=xxx` - 参数传递测试
- `/api/data` - POST 请求测试
- `/api/health` - 健康检查
- `/api/info` - 服务信息

## 功能特性

✅ 实时显示请求响应
✅ 统计端口分布（验证负载均衡）
✅ 历史记录查看
✅ 可配置网关地址

## 测试步骤

基础组件和后端命令均从仓库根目录执行。

1. 启动 Nameserver（端口 8888）
2. 启动 Gateway（本地配置端口 8080）
3. 启动多个后端实例：
   ```bash
   java -jar rover-gateway-test/demo/backend/target/rover-demo-1.0.0-SNAPSHOT.jar --server.port=8081
   java -jar rover-gateway-test/demo/backend/target/rover-demo-1.0.0-SNAPSHOT.jar --server.port=8082
   java -jar rover-gateway-test/demo/backend/target/rover-demo-1.0.0-SNAPSHOT.jar --server.port=8083
   ```
4. 启动前端：`npm run dev`
5. 点击测试按钮，观察端口分布变化

## 构建

```bash
npm run build
```

生成的静态文件在 `dist/` 目录，可部署到任何静态服务器。
