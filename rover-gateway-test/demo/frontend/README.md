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
默认网关地址：`http://localhost:9999`

可在页面顶部修改，或编辑 `src/utils/request.js` 文件。

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

1. 启动 Nameserver（端口 8888）
2. 启动 Gateway（端口 9999）
3. 启动多个后端实例：
   ```bash
   java -jar rover-demo.jar --server.port=8081
   java -jar rover-demo.jar --server.port=8082
   java -jar rover-demo.jar --server.port=8083
   ```
4. 启动前端：`npm run dev`
5. 点击测试按钮，观察端口分布变化

## 构建

```bash
npm run build
```

生成的静态文件在 `dist/` 目录，可部署到任何静态服务器。