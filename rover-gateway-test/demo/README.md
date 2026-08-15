# Gateway 测试套件

> ⚠️ **重要说明**：这是**测试项目**，用于验证 Rover Gateway 和 Nameserver 功能，**不是生产级模块**。

## 📦 项目结构

```
test/demo/
├── backend/          # 后端测试服务（Spring Boot）
│   ├── src/
│   │   └── main/java/com/rover/demo/controller/HelloController.java
│   └── pom.xml
├── frontend/         # 前端测试面板（Vue 3）
│   ├── src/
│   │   ├── App.vue
│   │   └── utils/request.js
│   ├── package.json
│   └── vite.config.js
└── README.md         # 本文件
```

## 🎯 用途

- 测试网关代理转发
- 测试负载均衡（多实例）
- 测试请求头传递
- 测试超时处理
- 测试错误场景

## 🚀 快速开始

### 1. 启动 Nameserver
```bash
cd ../../rover-nameserver-bootstrap
mvn spring-boot:run
```

### 2. 启动 Gateway
```bash
cd ../../rover-gateway-bootstrap
mvn spring-boot:run
```

### 3. 启动后端（多实例测试负载均衡）
```bash
cd backend
mvn clean package

# 启动 3 个实例
java -jar target/rover-demo-1.0.0-SNAPSHOT.jar --server.port=8081
java -jar target/rover-demo-1.0.0-SNAPSHOT.jar --server.port=8082
java -jar target/rover-demo-1.0.0-SNAPSHOT.jar --server.port=8083
```

### 4. 启动前端
```bash
cd frontend
npm install
npm run dev
```

访问 http://localhost:3000 开始测试。

## 🧪 测试场景

前端提供 6 大测试场景：

1. **基础接口** - GET /api/hello, /api/echo, /api/health, /api/info
2. **请求头测试** - 自定义 Authorization、X-Request-ID、X-Custom-Header
3. **POST 数据** - 简单 JSON、大数据量（100 元素）
4. **超时延迟** - 可配置延迟时间、超时测试（1s 超时）
5. **并发测试** - 可配置并发数（1-50），观察端口分布
6. **错误场景** - 404、模拟错误

## 📊 功能特性

✅ 实时统计（总请求数、成功率、平均延迟）
✅ 端口分布可视化（负载均衡验证）
✅ 历史记录查看（最近 30 条）
✅ 可配置网关地址
✅ 可配置请求头、延迟时间、并发数

## 📝 注意事项

- 仅用于功能测试，不是生产项目
- 前端请求到网关地址（默认 http://localhost:9999）
- 后端已集成 rover-nameserver-starter，自动注册到 Nameserver