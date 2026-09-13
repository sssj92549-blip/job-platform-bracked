# jobPlatform 招聘平台后端

已升级至 Spring Boot 3 系列；基于 Java 17、Spring Boot 3.5.16、MyBatis-Plus 3.5.17、MySQL 和 Redis。已实现登录注册和多身份认证，招聘业务接口按 [接口文档](docs/jiekou.md) 继续开发。

## 本地启动

1. 使用 JDK 17（IDEA 的 Project SDK、Maven Runner 和运行配置统一设置为 17）。
2. 启动本机 MySQL（3306）和 Redis（6379）。本地使用 MySQL `job_platform`、Redis 1 号库，无 Redis 密码。
3. 当前机器已配置被 Git 忽略的 `src/main/resources/application-local.yml`。其他机器使用环境变量 MYSQL_PASSWORD 提供密码，并先执行 docs/init.sql 建库。
4. 执行 `./mvnw spring-boot:run`；Windows 执行 `.\mvnw.cmd spring-boot:run`，或直接运行 `JobPlatformApplication`。

本地配置默认启用，允许 MySQL 驱动在首次连接时创建 `job_platform`（账号需有建库权限），不会清空已有数据。不使用自动建库时可手动执行 `docs/init.sql`。完整的9张业务表定义在 docs/init.sql，手动执行；应用启动不自动建表或覆盖数据。管理员初始化是脚本中的注释模板，不提供默认账号。

本机 PowerShell 启动示例：

```powershell
$env:JAVA_HOME = 'C:\Users\S\.jdks\ms-17.0.16'
.\mvnw.cmd spring-boot:run
```

## 框架验证接口

| 接口 | 用途 |
|---|---|
| `GET http://localhost:8080/api/system/ping` | 验证 HTTP 服务和统一响应；不代表数据库已连接 |
| `GET http://localhost:8080/actuator/health` | 检查 MySQL、Redis 等依赖，健康返回 200/UP，失败返回 503/DOWN |

Actuator 使用原生健康响应，不套业务信封，且不对外显示连接详情。Python 服务尚未实现，不计入健康检查。

## 目录与基础能力

```text
src/main/java/cn/itcast/demo/jobplatform/
  common/       统一响应、分页结果、业务异常、全局异常处理
  config/       MyBatis-Plus 分页、AI HTTP 客户端、异步线程池、密码编码器
  controller/   HTTP 接口
  service/      业务与事务
  mapper/       MyBatis-Plus Mapper
  entity/       数据库实体
  dto/          请求对象与参数校验
  vo/           返回对象
  interceptor/  认证拦截、请求追踪过滤器
src/main/resources/mapper/    Mapper XML
docs/init.sql                 非破坏性建库脚本
docs/jiekou.md                业务接口契约
```

- Redis 已由 Spring Boot 自动配置，注入 `StringRedisTemplate` 使用，业务 key 统一以 `job-platform:` 开头。不执行 FLUSHDB，不依赖 Redis 作为业务数据库。
- Mapper 继承 `BaseMapper<T>`，分页使用 MyBatis-Plus `Page<T>`，单页最多 50 条。账号与档案通过 BaseMapper 完成持久化，不手写简单CRUD SQL。
- 数据库 ID 默认 ASSIGN_ID；业务响应对象将 ID 转为字符串。实体含 `deleted` 时启用逻辑删除约定，建表需定义默认值 0。
- `PasswordEncoder` 使用 BCrypt；认证采用服务端 Session + MVC 拦截器，密码哈希不会返回前端。
- `RestTemplate` Bean 名为 `aiRestTemplate`，默认访问 `http://127.0.0.1:7999`，连接超时 3 秒、读取超时 180 秒；配置 `AI_INTERNAL_TOKEN` 后自动附加内部认证头。已有请求上下文时转发 `X-Request-Id`。
- 使用 `@Async("aiExecutor")` 提交 AI 工作，有界队列 50、线程 2～4。队列满会拒绝任务，后续业务需处理拒绝并记录失败；本阶段线程池不提供持久化、重启恢复或事务提交后调度。
- 文件目录通过 `app.storage.root` 配置，默认 `${user.dir}/uploads`。后续上传服务负责校验文件、创建子目录和授权下载；没有将目录公开映射为静态资源。
- Session 30 分钟空闲过期，Cookie HttpOnly/SameSite=Lax。登录、验证码、锁定、角色校验与 CSRF 已实现；文件接口和招聘业务 CRUD 尚未实现。

## 测试

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
```

自动化测试显式使用 `test` profile 和内存 H2，不连接本地 MySQL、不要求本地 Redis，也不修改本地业务数据。真实依赖连通性通过 local profile 的 `/actuator/health` 另行验证。

## 部署配置

设置 `SPRING_PROFILES_ACTIVE=prod` 并通过环境变量提供配置：

| 变量 | 默认值 / 用途 |
|---|---|
| `SERVER_PORT` | 8080 |
| `MYSQL_HOST` / `MYSQL_PORT` | 127.0.0.1 / 3306 |
| `MYSQL_DATABASE` / `MYSQL_USERNAME` | job_platform / root |
| `MYSQL_PASSWORD` | 无，部署时填写 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_DATABASE` | 127.0.0.1 / 6379 / 1 |
| `REDIS_PASSWORD` | 无密码 |
| `UPLOAD_ROOT` | 工作目录下 uploads |
| `AI_BASE_URL` | http://127.0.0.1:7999 |
| `AI_INTERNAL_TOKEN` | Java/Python 共用的内部调用凭据 |

部署前由脚本建库。打包前移除构建环境中的 `application-local.yml`，避免本机凭据进入制品；生产 HTTPS 设置 `SERVER_SERVLET_SESSION_COOKIE_SECURE=true`。

依赖选型参考：[MyBatis-Plus 官方安装说明](https://baomidou.com/en/getting-started/install/)，本项目使用 Spring Boot 3 对应的 `mybatis-plus-spring-boot3-starter`。

## 认证分层与自动填充

- AuthController / CurrentUserController：仅接收请求、校验DTO、调用Service及返回响应。
- AuthService：验证码校验、凭证验证、会话恢复、身份选择/切换和退出编排。
- AccountService：注册事务、账号与档案查询、添加角色、组装无密码的User视图。
- AccountMapper / ProfileMapper：继承MyBatis-Plus BaseMapper，基础CRUD无需XML。
- BaseEntity：id使用ASSIGN_ID；createdAt标注 `@TableField(fill = FieldFill.INSERT)`，updatedAt标注 `@TableField(fill = FieldFill.INSERT_UPDATE)`。
- EntityTimeHandler：实现MetaObjectHandler，插入时填充两个时间，updateById(entity)时刷新updatedAt，统一北京时间。使用只有Wrapper、没有实体的更新不会触发填充，需传实体或显式设置时间。
- LoginGuard：Redis原子计数，账号的手机号和用户名共用锁定记录；5次连续失败锁定15分钟，正确登录清零。未知账号计数15分钟过期。实际连接IP每分钟最多120次验证码/登录/注册请求。
- CaptchaService：120秒、单次使用、绑定Session；只返回PNG图片，不向客户端返回答案。刷新验证码不会注销已登录会话。

已实现：GET /api/auth/captcha、POST /register、POST /login、GET /session、GET /profiles、POST /profiles、POST /select-profile、POST /switch-profile、POST /logout（后八项同auth前缀），以及 GET /api/users/me。

普通测试使用H2和隔离的Redis替身；运行 `./mvnw -Dauth.redis.tests=true test` 可额外验证本机Redis1号库，只操作随机测试键。生产代码无演示账号或验证码后门。系统ping仍被前端首页调用，属于实际健康探测功能。

拦截器独立放在 `interceptor/AuthInterceptor.java`；`config/AuthWebConfig.java` 仅注册 `/api/**` 的拦截范围。
