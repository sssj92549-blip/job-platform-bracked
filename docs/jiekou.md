# 招聘系统接口文档

版本：1.3（职位卡片、企业规模与筛选）
适用项目：Spring Boot 3.5.16 + Java 17 + MyBatis-Plus 3.5.17 + MySQL / Python 3.10 + FastAPI + PaddleOCR + 大模型 API + Chroma。  
说明：认证、多身份、个人资料、职位、简历、投递、AI异步任务、人才检索与用户/职位审核接口已实现。Java通过RestTemplate调用Python，Redis用于限流、短期防重及职位详情缓存；业务状态持久化在MySQL。本文同步至桌面jiekou.md和前端docs/backend-api.md。实现范围以本文接口清单为准。

## 1. 通用约定

### 1.1 地址、认证和权限

- Java 本地服务：`http://localhost:8080`，业务前缀 `/api`。
- Python 本地服务：`http://127.0.0.1:7999`，内部前缀 `/internal`。
- 表格中的路径均为完整路径（不包含主机名）。
- 默认请求、响应类型：`application/json; charset=UTF-8`；文件上传使用 `multipart/form-data`，下载返回二进制。
- 登录采用服务端 Session：成功后返回 `Set-Cookie: JSESSIONID=...; HttpOnly; SameSite=Lax`。浏览器后续携带 Cookie；生产 HTTPS 设置 Secure。登出、封禁使 Session 失效，默认空闲 30 分钟过期。
- 前端与 Java 优先同源部署。写接口校验 CSRF：登录前通过验证码接口取得绑定匿名 Session 的 `csrfToken`；POST、PUT、PATCH、DELETE 均带 `X-CSRF-Token` 请求头。登录成功轮换 token 并在响应返回。若跨域开发，只允许明确配置的前端源并开启 credentials，不允许通配符源。
- 角色：`JOB_SEEKER` 求职者、`COMPANY` 企业、`ADMIN` 管理员。管理员账号由初始化脚本创建，不能公开注册。
- 每个受保护接口都重新检查账号状态、角色与资源归属，不能只依赖前端隐藏按钮。企业访问业务接口还必须通过用户审核。
- Python 仅供 Java 调用，通过 `X-Internal-Token` 认证，部署时不对公网开放。
- `me` 表示当前 Session 用户，请求不得指定其他用户来代替当前身份。

### 1.2 类型和输入规则

- 所有 ID 为 JSON 字符串（例 `"1001"`），数据库可用 BIGINT，避免前端精度丢失。
- 时间：ISO 8601，携带时区，例如 `2026-09-09T14:30:00+08:00`。
- 分页：`page` 默认 1、最小 1；`size` 默认 10、范围 1～50。默认 `createdAt DESC, id DESC` 排序，禁止直接拼接客户端 SQL 排序字段。
- 表格输入中 `?` 表示可选；其他字段必填。`无` 表示不传请求体。路径参数均必填，GET 参数来自 query。
- PUT 为文档列出的可编辑字段整体替换；可选字段省略时清空。PATCH 仅更新实际传入的字段，`null` 只允许用于可空字段。
- 手机号：11 位中国大陆手机号；密码：8～32 位、同时包含字母和数字；所有响应不得包含密码或密码哈希。
- `education`：`HIGH_SCHOOL`、`JUNIOR_COLLEGE`、`BACHELOR`、`MASTER`、`DOCTOR`、`OTHER`。无法识别的学历为 `null`。
- 字符串默认去除首尾空白；姓名 1～50 字、公司名 2～100 字、职位名称 2～100 字、城市 1～50 字；简介最多 2000 字，职位描述与要求各最多 10000 字。数组最多 50 项，每项最多 100 字。

### 1.3 统一响应

Java 和 Python 的 JSON 接口均返回此信封；下文“输出”描述的是 `data`。成功 `code=0`，失败 `data=null`。

```json
{
  "code": 0,
  "message": "success",
  "data": {"id": "1001"},
  "requestId": "req-abc123"
}
```

分页数据 `Page<T>`：

```json
{
  "records": [],
  "total": 0,
  "page": 1,
  "size": 10
}
```

无业务数据的成功输出使用 `{}`。创建资源返回 HTTP 201；异步任务已接收返回 HTTP 202；普通成功返回 HTTP 200。二进制下载不使用 JSON 信封，下载失败仍返回 JSON 错误。

| HTTP | code | 含义 |
|---|---|---|
| 400 | 40001 | 参数校验失败、文件类型错误 |
| 400 | 40002 | 图形验证码错误或过期 |
| 401 | 40101 | 未登录或 Session 已失效 |
| 401 | 40102 | 手机号、用户名或密码错误（统一提示） |
| 403 | 40301 | 角色或业务权限不足、账号禁用 |
| 403 | 40302 | 用户尚未审核通过 |
| 403 | 40303 | CSRF 校验失败 |
| 404 | 40401 | 资源不存在或不属于当前用户 |
| 409 | 40901 | 手机号或用户名已注册 |
| 409 | 40902 | 状态不允许当前操作 |
| 409 | 40903 | 已投递该职位 |
| 409 | 40904 | 简历版本已变化，请重新加载 |
| 413 | 41301 | 上传文件过大 |
| 422 | 42201 | 简历文本无法识别或 AI 输出不符合结构 |
| 429 | 42901 | 登录已锁定（提示剩余秒数，不暴露账号是否存在） |
| 429 | 42902 | 请求过于频繁 |
| 502 | 50201 | AI 服务或模型调用失败 |
| 503 | 50301 | 向量索引暂不可用 |
| 504 | 50401 | AI 调用超时 |
| 500 | 50001 | 系统内部错误 |

错误 `message` 使用可读描述，不向前端返回堆栈、密钥或磁盘绝对路径。Python 参数校验错误也应转换为统一格式。

## 2. 业务状态与数据对象

### 2.1 状态及关键规则

| 对象 | 状态 / 字段 | 规则 |
|---|---|---|
| 用户 | `reviewStatus=PENDING/APPROVED/REJECTED`；`enabled` | 求职者注册默认 APPROVED，企业注册默认 PENDING；管理员可审核和禁用 |
| 职位 | `DRAFT/PENDING/APPROVED/REJECTED/CLOSED` | 草稿提交审核后 PENDING；审核通过才公开；关闭后不可投递 |
| 简历解析 | `parseStatus=PENDING/PROCESSING/SUCCESS/FAILED` | 上传后 Java 异步调用 Python，前端轮询；Python 单次同步返回 |
| 简历确认 | `confirmationStatus=UNCONFIRMED/CONFIRMED` | 解析成功且用户确认后才可投递、加入人才检索 |
| 信息冲突 | `conflictStatus=NONE/PENDING_VERIFY/RESOLVED` | 比较姓名、手机号、学历；用户确认后 RESOLVED，保留原始差异记录 |
| 向量索引 | `indexStatus=NOT_READY/PENDING/PROCESSING/READY/FAILED/DELETING/DELETED` | 解析成功即安排向量化；只有 READY 且已确认、允许被发现的数据可被推荐 |
| 投递 | `SUBMITTED/VIEWED/SHORTLISTED/REJECTED/WITHDRAWN` | 不包含面试邀约和面试流程 |
| AI 生成任务 | `PENDING/PROCESSING/SUCCESS/FAILED` | Java 保存任务，Python 内部推理接口无会话状态 |

设计决定：

1. AI 解析值写入 resume，不立即不可逆覆盖 profile。用户确认时更新 profile 的姓名、学历，符合“确认后才投递”的业务闭环。登录手机号只通过单独的账号机制修改，本期不提供；解析手机号仅作为简历联系方式。
2. 企业“一键采用 AI”只更改本企业投递记录的展示来源，不修改求职者全局资料。求职者原填信息及差异在投递快照中保留。
3. 匹配得分属于“简历版本＋职位版本”，存入匹配记录，不使用单一的 `resume.match_score` 作为权威分数。
4. 每个求职者仅有一份当前简历。新上传生成新 ID，旧简历退出人才检索；历史投递保留原版本快照和附件。删除当前简历为逻辑删除，不破坏历史投递。
5. 向量写入会改变 Chroma 数据，因此“无状态”特指 AI 推理没有多轮会话记忆，并不意味着系统没有数据库或索引状态。
6. 本期职位通过审核后不可原地修改；需要修改时关闭并新建职位，简化历史版本一致性。职位 `version` 在每次草稿编辑时递增。
7. 企业收到的历史投递保留投递时内容；岗位关闭后仍可处理已有投递。人才检索仅允许自己的 APPROVED 职位。

### 2.2 响应对象（字段构成接口契约）

除另行说明，以下对象列出的字段均返回；无值用 `null`，数组无值用 `[]`。对象内部字段类型不因不同接口变化。

**User**（本人、管理员可见）

```json
{
  "id": "1001", "accountId": "501", "username": "zhangsan", "phone": "13800138000", "role": "JOB_SEEKER",
  "name": "张三", "education": "BACHELOR", "avatarUrl": null,
  "city": "杭州", "introduction": null, "discoverable": false,
  "companyName": null, "industry": null, "companySize": null, "companyDescription": null,
  "reviewStatus": "APPROVED", "reviewReason": null, "enabled": true,
  "createdAt": "2026-09-09T14:30:00+08:00"
}
```

`discoverable` 默认 false，只有求职者可以设置；企业注册的 `companyName` 必填。

**Job**（公开及业务详情）

```json
{
  "id": "2001", "companyId": "1002", "companyName": "示例科技",
  "companyIndustry": "软件服务", "companySize": "100_499", "companyAvatarUrl": null,
  "title": "Java开发工程师", "city": "杭州",
  "salaryMin": 8000, "salaryMax": 15000,
  "educationRequirement": "BACHELOR", "experienceMinYears": 0,
  "description": "参与招聘平台开发", "requirements": "熟悉Spring Boot",
  "skills": ["Java", "MySQL"], "status": "APPROVED", "version": 1,
  "reviewReason": null, "createdAt": "2026-09-09T14:30:00+08:00",
  "publishedAt": "2026-09-09T15:00:00+08:00"
}
```

薪资单位：人民币元/月，整数，`0 <= salaryMin <= salaryMax <= 1000000`；经验年限 0～50。学历要求可为 null 表示不限。公开响应 `reviewReason` 固定 null。

**Resume**（仅本人详情；企业通过投递快照查看）

```json
{
  "id": "3001", "version": 1, "fileName": "张三简历.pdf", "fileSize": 123456,
  "downloadUrl": "/api/resumes/3001/file",
  "parseStatus": "SUCCESS", "confirmationStatus": "UNCONFIRMED",
  "conflictStatus": "PENDING_VERIFY", "indexStatus": "PENDING",
  "parsedName": "张三", "parsedPhone": "13900139000",
  "parsedEducation": "BACHELOR", "parsedSkills": ["Java", "MySQL"],
  "parsedSummary": "具有Java项目经验", "extractedText": "简历全文……",
  "conflicts": [
    {"field": "phone", "userValue": "13800138000", "aiValue": "13900139000"}
  ],
  "confirmedProfile": null, "parseError": null, "indexError": null,
  "createdAt": "2026-09-09T14:30:00+08:00", "confirmedAt": null
}
```

`confirmedProfile` 确认后为 `{name, contactPhone, education, skills}`，其中姓名、学历、联系方式必填，技能允许空数组。`version` 在重新解析和确认时递增；异步结果仅能写回其启动时的版本。原始文本最多 60000 字；更大文档解析失败并提示精简，不能无提示截断。

**Application**（投递详情）

```json
{
  "id": "4001", "jobId": "2001", "jobTitle": "Java开发工程师",
  "companyName": "示例科技", "candidateId": "1001",
  "resumeId": "3001", "resumeVersion": 2, "status": "SUBMITTED",
  "profileSource": "CONFIRMED", "matchScore": null,
  "resumeSnapshot": {
    "confirmedProfile": {"name": "张三", "contactPhone": "13900139000", "education": "BACHELOR", "skills": ["Java"]},
    "originalProfile": {"name": "张小三", "phone": "13800138000", "education": "BACHELOR"},
    "aiProfile": {"name": "张三", "phone": "13900139000", "education": "BACHELOR", "skills": ["Java"]},
    "conflictStatus": "RESOLVED", "conflicts": [{"field": "name", "userValue": "张小三", "aiValue": "张三"}],
    "extractedText": "简历全文……",
    "downloadUrl": "/api/applications/4001/resume-file"
  },
  "createdAt": "2026-09-09T15:10:00+08:00", "updatedAt": "2026-09-09T15:10:00+08:00"
}
```

列表使用 `ApplicationSummary`：Application 去掉 `resumeSnapshot`，增加 `candidateName`（按 profileSource 选择姓名，AI 为空则回退 confirmedProfile）。匹配分数来自该投递对应版本的成功匹配任务，没有时 null。

**AiTask<T>**

```json
{
  "id": "5001", "type": "MATCH", "status": "SUCCESS",
  "applicationId": "4001", "resumeId": "3001", "resumeVersion": 2,
  "jobId": "2001", "jobVersion": 1,
  "result": {"score": 86, "reasons": ["具备Java和MySQL经验"], "gaps": ["缺少生产部署经验"]},
  "errorCode": null, "errorMessage": null,
  "createdAt": "2026-09-09T15:10:00+08:00", "completedAt": "2026-09-09T15:10:10+08:00"
}
```

任务未成功时 result=null；失败时 errorCode、errorMessage 有值。`type` 为 MATCH、INTERVIEW 或 ASSISTANT。助手任务的 applicationId/jobId/jobVersion 为 null。任务仅创建者可查询。

结果类型：

- `MatchResult`：`{score: integer(0..100), reasons: string[], gaps: string[]}`。
- `InterviewResult`：`{questions: [{number: integer(1..10), question: string, direction: string, assessmentPoints: string[]}]}`，必须恰好 10 道，无标准答案要求。
- `AssistantResult`：`{answer: string}`，最多 8000 字，不保存多轮上下文；可保存单次任务结果用于轮询。
- `Candidate`：`{candidateId: string, resumeId: string, resumeVersion: integer, name: string, education: string|null, skills: string[], summary: string|null, similarity: number(0..1)}`。姓名为已确认姓名，不返回手机号、附件或全文。similarity 是向量相似度，不是大模型匹配分数。

## 3. 注册、登录与个人资料

| 方法 | 请求路径 | 权限 | 输入 | 输出 data |
|---|---|---|---|---|
| GET | `/api/auth/captcha` | 公开 | 无 | `{captchaId: string, imageBase64: string, expiresIn: 120, csrfToken: string}` |
| POST | `/api/auth/register` | 公开 | `{username, phone, password, role, companyName?, captchaId, captchaCode}` | HTTP 201，`{userId: string, reviewStatus: string}` |
| POST | `/api/auth/login` | 公开 | `{loginName, password, captchaId, captchaCode}` | `Session（见第 13 节）`，同时设置 Session Cookie |
| POST | `/api/auth/logout` | 已登录或待选择身份 | 无 | `null`，销毁 Session |
| GET | `/api/auth/session` | 公开 | 无 | Session；匿名也返回 200 |
| GET | `/api/auth/profiles` | 已验证账号 | 无 | Session，含全部身份选项 |
| POST | `/api/auth/select-profile` | 待选择身份 | `{profileId: string}` | Session，AUTHENTICATED |
| POST | `/api/auth/switch-profile` | 已登录 | `{profileId: string}` | Session，新身份及 CSRF |
| POST | `/api/auth/profiles` | 已登录 | `{role, companyName?}` | HTTP 201，ProfileOption，不自动切换 |
| GET | `/api/users/me` | 已登录 | 无 | `User` |
| PUT | `/api/users/me/profile` | 求职者 | `{name, education, city?, introduction?}` | `User` |
| PUT | `/api/company/profile` | 企业（待审也可） | `{companyName, industry?, companySize?, city?, companyDescription?}` | `User` |
| POST | `/api/company/profile/submit-review` | 企业 | 无 | `{reviewStatus: "PENDING"}` |
| PATCH | `/api/users/me/discoverability` | 求职者 | `{discoverable: boolean}` | `{discoverable: boolean, indexStatus: string}` |
| POST | `/api/users/me/avatar` | 已登录 | multipart：`file`（JPEG/PNG，≤2MB） | `{avatarUrl: string}` |
| GET | `/api/users/{userId}/avatar` | 公开 | path：userId | 图片二进制；未设置返回 404 |

补充规则：

- 验证码图片返回 `data:image/png;base64,...`，绑定 Session、120 秒有效、单次使用；注册同样使用图形验证码，不含短信验证。
- 登录按同一 account 归并手机号/用户名统计：连续 5 次凭证失败锁定 15 分钟，锁定信息存服务端；正确登录后清零，锁定期结束后重置。验证码失败不算密码失败，但受独立频率限制。未知登录名使用相同提示和限流策略。
- 待审或被拒企业允许登录、维护企业资料和重新提交审核，其他业务接口返回 40302；禁用用户不可登录。
- 企业修改资料后回到 PENDING；审核未通过期间，该企业职位不在公开列表出现且不可投递。重复提交 PENDING 返回 40902。
- 求职者修改基础资料不自动修改已确认简历或投递快照，需要更新简历时重新确认。人才检索展示简历确认资料。
- 关闭 discoverable 后 Java 查询层立即排除该人，并异步删除其向量；再次开启则异步重建索引。索引失败不影响隐私开关生效。
- 头像保存到 `${user.dir}/uploads/avatars/`，随机文件名，通过受控接口读取，不公开服务器目录。

## 4. 职位浏览与企业职位管理

`JobInput`：`{title, city, salaryMin: integer, salaryMax: integer, educationRequirement?: enum, experienceMinYears: integer, description, requirements, skills: string[]}`。

| 方法 | 请求路径 | 权限 | 输入 | 输出 data |
|---|---|---|---|---|
| GET | `/api/jobs` | 公开 | query：`page?, size?, keyword?, city?, education?, salaryMin?, salaryMax?, experience?, industry?, companySize?` | `Page<Job>`，仅启用且审核通过企业的 APPROVED 职位 |
| GET | `/api/jobs/{jobId}` | 公开 | path：jobId | `Job`，非公开职位返回 404 |
| GET | `/api/companies/{companyId}` | 公开 | path：companyId | `{id, companyName, industry, companySize, city, companyDescription, avatarUrl}`，仅启用且已审核企业；不返回联系方式 |
| POST | `/api/company/jobs` | 企业 | body：JobInput | HTTP 201，`Job`，状态 DRAFT |
| GET | `/api/company/jobs` | 企业 | query：`page?, size?, status?, keyword?` | `Page<Job>`，仅自己的职位 |
| GET | `/api/company/jobs/{jobId}` | 企业 | path：jobId | `Job`，仅自己的职位，可看非公开状态 |
| PUT | `/api/company/jobs/{jobId}` | 企业 | path：jobId；body：JobInput | `Job`，仅 DRAFT/REJECTED 可编辑，编辑后 DRAFT |
| POST | `/api/company/jobs/{jobId}/submit-review` | 企业 | path：jobId；无 body | `Job`，DRAFT/REJECTED → PENDING |
| POST | `/api/company/jobs/{jobId}/close` | 企业 | path：jobId；无 body | `Job`，APPROVED → CLOSED |
| DELETE | `/api/company/jobs/{jobId}` | 企业 | path：jobId | `{}`，仅 DRAFT/REJECTED 且无投递，逻辑删除 |

查询 keyword 匹配职位名称、公司名称；薪资筛选采用区间相交：岗位上限≥筛选下限且岗位下限≤筛选上限。只传一端则单边筛选。所有筛选同时生效。

## 5. 简历上传、解析与确认

| 方法 | 请求路径 | 权限 | 输入 | 输出 data |
|---|---|---|---|---|
| POST | `/api/resumes` | 求职者 | multipart：`file`（PDF，≤10MB，≤20页） | HTTP 202，`{resumeId: string, version: 1, parseStatus: "PENDING", confirmationStatus: "UNCONFIRMED"}` |
| GET | `/api/resumes/current` | 求职者 | 无 | `Resume`；尚未上传返回 null |
| GET | `/api/resumes/{resumeId}` | 求职者本人 | path：resumeId | `Resume`；轮询此接口查看解析、索引状态 |
| POST | `/api/resumes/{resumeId}/reparse` | 求职者本人 | path：resumeId；`{expectedVersion: integer}` | HTTP 202，`{resumeId: string, version: integer, parseStatus: "PENDING"}` |
| POST | `/api/resumes/{resumeId}/confirm` | 求职者本人 | path：resumeId；`{expectedVersion: integer, name, contactPhone, education, skills: string[]}` | `Resume`，CONFIRMED；事务内更新 profile 的姓名、学历 |
| POST | `/api/resumes/{resumeId}/retry-index` | 求职者本人 | path：resumeId；`{expectedVersion: integer}` | HTTP 202，`{resumeId: string, indexStatus: "PENDING"}` |
| GET | `/api/resumes/{resumeId}/file` | 求职者本人 | path：resumeId | `application/pdf` 二进制附件 |
| DELETE | `/api/resumes/{resumeId}` | 求职者本人 | path：resumeId | `{}`，逻辑删除并异步清理向量 |

流程：上传 → PENDING → PROCESSING → SUCCESS/FAILED → 用户检查 AI 结果及冲突 → confirm → 可投递。前端建议每 2 秒轮询，成功或失败后停止，不将轮询超时等同于后台失败。

- PDF 校验真实文件签名、页数及可读性；拒绝加密 PDF。接收文件后生成随机存储名，路径为 `${user.dir}/uploads/resumes/`，不接受前端提交任意磁盘路径。
- PyMuPDF 优先提取每页文本；无有效文本的页面单独降级 OCR，合并页序后交给大模型。扫描件与混合 PDF 均适用。
- 解析字段缺失用 null/[]，不得编造信息。缺少姓名、学历、联系方式时仍允许解析成功，用户在确认表单补齐。
- conflicts 比较上传时保存的 profile 快照和非空 AI 字段（规范化后比较）；AI 空字段不作为冲突，交由确认表单补齐。
- reparse 仅当前有效简历且 SUCCESS/FAILED 时允许；清除确认状态并递增版本，旧投递快照不变。确认时可人工修正 AI 字段；原解析字段保留，confirmedProfile 保存最终值。
- confirm 仅当前简历且解析 SUCCESS 时允许；同一 expectedVersion 重复提交返回 40904，前端重新读取即可。确认后更新索引元数据和版本，成功前不参与推荐。
- retry-index 仅 parseStatus=SUCCESS 且 indexStatus=FAILED 时允许。索引失败不阻止确认或投递。
- Java先在MySQL提交任务，后台每2秒领取已提交的PENDING工作；解析、生成、索引分别使用调度线程。超过10分钟的PROCESSING解析/生成任务标记FAILED；向量任务重新领取并幂等执行，普通调用失败最多尝试3次，重试间隔30/60秒。app.jobs.enabled=false可暂停轮询。
- 附件下载使用安全 Content-Disposition 文件名；历史附件仅通过授权的投递文件接口访问。

## 6. 投递与企业候选人处理

| 方法 | 请求路径 | 权限 | 输入 | 输出 data |
|---|---|---|---|---|
| POST | `/api/applications` | 求职者 | `{jobId: string, resumeId: string, resumeVersion: integer}` | HTTP 201，`Application` |
| GET | `/api/applications/me` | 求职者 | query：`page?, size?, status?` | `Page<ApplicationSummary>` |
| GET | `/api/applications/{applicationId}` | 投递本人或所属企业 | path：applicationId | `Application` |
| POST | `/api/applications/{applicationId}/withdraw` | 求职者本人 | path：applicationId；无 body | `Application`，SUBMITTED/VIEWED/SHORTLISTED → WITHDRAWN |
| GET | `/api/company/applications` | 企业 | query：`page?, size?, jobId?, status?` | `Page<ApplicationSummary>`，仅自己职位的投递 |
| PATCH | `/api/company/applications/{applicationId}/status` | 所属企业 | path：applicationId；`{status: "VIEWED"\|"SHORTLISTED"\|"REJECTED"}` | `Application` |
| PATCH | `/api/company/applications/{applicationId}/profile-source` | 所属企业 | path：applicationId；`{profileSource: "AI"\|"CONFIRMED"}` | `Application` |
| GET | `/api/applications/{applicationId}/resume-file` | 投递本人或所属企业 | path：applicationId | 投递时 PDF 二进制，`application/pdf` |

- 投递必须使用本人当前简历，解析 SUCCESS、确认 CONFIRMED、版本匹配；职位 APPROVED 且企业启用、审核通过。事务内保存简历与职位快照。
- 数据库唯一约束 `(candidate_id, job_id)`；即使撤回也不允许重复投递，重复请求返回 40903。
- 企业可做：SUBMITTED → VIEWED/SHORTLISTED/REJECTED；VIEWED → SHORTLISTED/REJECTED；SHORTLISTED → REJECTED。REJECTED/WITHDRAWN 终态，不可再改；重复设置同一状态返回当前对象。
- GET 详情不改变状态；标记已查看由 PATCH 显式触发。
- profileSource=AI 即“一键采用 AI 解析结果”，只影响本次投递展示；不会消除原始冲突证据。AI 某字段为空时回退确认字段，登录手机号不受影响。

## 7. AI 匹配、面试题和求职助手

外部 AI 功能统一异步：创建任务返回 202，前端查询任务接口取结果。Python 同步处理单次调用，不负责前端轮询。AI 生成接口只接受业务 ID，简历、职位文本由 Java 根据权限读取，防止客户端伪造他人内容。

| 方法 | 请求路径 | 权限 | 输入 | 输出 data |
|---|---|---|---|---|
| POST | `/api/ai/matches` | 求职者或企业 | 求职者：`{resumeId, resumeVersion, jobId}`；企业：`{applicationId}`，两种输入互斥 | HTTP 202，`AiTask<MatchResult>` |
| POST | `/api/ai/interview-questions` | 求职者或企业 | 求职者：`{resumeId, resumeVersion, jobId}`；企业：`{applicationId}`，两种输入互斥 | HTTP 202，`AiTask<InterviewResult>` |
| POST | `/api/ai/assistant` | 求职者 | `{resumeId, resumeVersion, question: string(1..2000), previousTaskId?: string}` | HTTP 202，`AiTask<AssistantResult>` |
| GET | `/api/ai/tasks/{taskId}` | 任务创建者 | path：taskId | 按 type 返回 `AiTask<MatchResult/InterviewResult/AssistantResult>` |

- 求职者仅使用本人已确认当前简历；匹配/面试题选择公开有效职位。企业仅使用自己收到的投递快照，不能凭任意 resumeId 调用。
- 企业不可对 WITHDRAWN 投递创建新 AI 任务。任务创建时固定简历和职位版本，后续资料变更不污染结果。
- 面试题生成只是内容生成，不创建面试安排。岗位推荐助手结合确认简历与真实职位进行多轮RAG；通过previousTaskId关联本人上一条成功任务，不接受客户端提供的history。
- 同创建者、类型、简历版本、职位版本、投递ID且正在执行的任务复用；助手另以 question 和 previousTaskId 的哈希区分。返回已有任务时 HTTP 200。失败任务可重新 POST 创建新任务，不无限自动重试。
- 当前RestTemplate统一连接超时3秒、读取超时180秒，通过app.ai.connect-timeout/read-timeout配置；Python模型调用自身默认90秒。超时或连接失败记录FAILED，错误为50401。
- Java 对模型结果进行结构校验：分数必须整数且 0～100，题目必须 10 道。简历文本作为数据处理，不能作为系统指令；模型不得获取文件访问、SQL 或业务写入权限。

匹配请求示例：

```http
POST /api/ai/matches
Content-Type: application/json
Cookie: JSESSIONID=...
X-CSRF-Token: ...

{"resumeId":"3001","resumeVersion":2,"jobId":"2001"}
```

任务轮询成功示例：

```json
{
  "code": 0, "message": "success", "requestId": "req-match-1",
  "data": {
    "id": "5001", "type": "MATCH", "status": "SUCCESS",
    "applicationId": null, "resumeId": "3001", "resumeVersion": 2,
    "jobId": "2001", "jobVersion": 1,
    "result": {"score": 86, "reasons": ["掌握岗位要求的Java技术"], "gaps": ["缺少部署经验"]},
    "errorCode": null, "errorMessage": null,
    "createdAt": "2026-09-09T15:10:00+08:00", "completedAt": "2026-09-09T15:10:10+08:00"
  }
}
```

## 8. 语义人才挖掘

| 方法 | 请求路径 | 权限 | 输入 | 输出 data |
|---|---|---|---|---|
| POST | `/api/company/jobs/{jobId}/talent-search` | 所属企业 | path：jobId；`{topK?: integer(1..50, 默认10), minSimilarity?: number(0..1, 默认0.60)}` | `{jobId: string, candidates: Candidate[], returnedCount: integer}` |

规则：职位须 APPROVED。Java 将职位描述、技能与要求送 Python 向量化，检索 Chroma。候选人必须启用、用户审核通过、简历当前有效且已确认、索引 READY、discoverable=true，并排除曾投递当前职位的人（含已撤回）。

本期按毕设数据规模，由 Java 查询符合条件的简历版本列表作为白名单传给 Python，Python 只检索白名单；返回后 Java 再校验一次状态、版本和投递情况，防止索引延迟泄露数据。最终结果可能少于 topK，允许空数组，不补造候选人。该接口同步执行，超时返回 50401。

采用余弦距离，`similarity = clamp(1 - cosineDistance, 0, 1)`，按 similarity 降序、resumeId 升序排序。分值不代表录用概率，也不与大模型 0～100 分混用。不生成自然语言推荐理由，不做完整 RAG。

输出示例：

```json
{
  "jobId": "2001",
  "candidates": [{
    "candidateId": "1003", "resumeId": "3003", "resumeVersion": 2,
    "name": "李四", "education": "BACHELOR", "skills": ["Java", "Spring Boot"],
    "summary": "有招聘平台开发经验", "similarity": 0.87
  }],
  "returnedCount": 1
}
```

## 9. 管理员接口

| 方法 | 请求路径 | 权限 | 输入 | 输出 data |
|---|---|---|---|---|
| GET | `/api/admin/users` | 管理员 | query：`page?, size?, role?, reviewStatus?, enabled?: boolean, keyword?` | `Page<User>`，keyword 匹配姓名/手机号/公司名 |
| GET | `/api/admin/users/{userId}` | 管理员 | path：userId | `User` |
| POST | `/api/admin/users/{userId}/review` | 管理员 | path：userId；`{decision: "APPROVED"\|"REJECTED", reason?: string(1..500)}` | `User` |
| PATCH | `/api/admin/users/{userId}/enabled` | 管理员 | path：userId；`{enabled: boolean, reason: string(1..500)}` | `User` |
| GET | `/api/admin/jobs` | 管理员 | query：`page?, size?, status?, companyId?, keyword?` | `Page<Job>` |
| GET | `/api/admin/jobs/{jobId}` | 管理员 | path：jobId | `Job` |
| POST | `/api/admin/jobs/{jobId}/review` | 管理员 | path：jobId；`{decision: "APPROVED"\|"REJECTED", reason?: string(1..500)}` | `Job` |
| POST | `/api/admin/jobs/{jobId}/close` | 管理员 | path：jobId；`{reason: string(1..500)}` | `Job` |

审核只允许 PENDING → APPROVED/REJECTED，拒绝必须提供 reason。用户审核用于待审企业，求职者默认通过，违规求职者通过禁用处理。管理员不能审核、禁用自己或其他管理员。职位审核通过前必须验证企业启用且已通过审核。

禁用企业后公开职位立即不可见且不可投递；禁用求职者后人才检索立即排除并安排索引删除。恢复启用仍需满足原有审核和职位状态，不自动将关闭职位重新发布。管理员关闭职位仅适用于 APPROVED。审核、禁用和关闭记录操作人、时间、原因以便追溯。

## 10. Java → Python 内部接口

所有接口要求 `X-Internal-Token`，JSON 请求同时携带 `X-Request-Id` 便于关联日志。以下无业务 Session；鉴权失败 HTTP 401。Python 不直接更新 MySQL，Java 负责权限、事务、状态和异步任务持久化。

### 10.1 简历解析

`POST /internal/resumes/parse`

输入：

```json
{"resumeId":"3001","resumeVersion":1,"filePath":"resumes/9f3c2a.pdf"}
```

`filePath` 为共享 uploads 根目录下的相对路径。Java、Python 各自配置同一个共享目录（同机可为同一磁盘路径）；Python 规范化路径后检查必须位于根目录内，并拒绝 `..`、绝对路径、符号链接逃逸。不同机器部署时必须先实现文件传输接口，不能沿用不可访问的 Java 本地路径。

输出 data：

```json
{
  "resumeId": "3001", "resumeVersion": 1,
  "extractedText": "简历全文……", "extractionMethod": "MIXED", "pageCount": 2,
  "parsedName": "张三", "parsedPhone": "13900139000",
  "parsedEducation": "BACHELOR", "parsedSkills": ["Java", "MySQL"],
  "parsedSummary": "具有Java项目经验"
}
```

`extractionMethod` 为 TEXT/OCR/MIXED。请求限制与外部上传一致，字段提取失败返回 42201。该接口不直接写 Chroma，Java 成功落库后再调用向量写入。

### 10.2 人岗匹配

`POST /internal/ai/match`

输入：

```json
{
  "resumeText": "已确认资料与简历全文……",
  "job": {"title":"Java开发工程师","description":"参与后端开发","requirements":"熟悉Spring Boot","skills":["Java"]}
}
```

输出 data：`MatchResult`，示例 `{"score":86,"reasons":["具备Java经验"],"gaps":["缺少部署经验"]}`。

### 10.3 面试题生成

`POST /internal/ai/interview-questions`

输入：与匹配接口相同，额外必填 `count: 10`（本期只允许 10）。

输出 data：`InterviewResult`，结构如下，其中 questions 必须实际包含 10 项：

```text
{questions: [{number: 1, question: "如何保证重复投递不会产生多条记录？", direction: "数据库与并发", assessmentPoints: ["唯一约束", "事务处理"]}, ...共10项]}
```

### 10.4 求职助手

`POST /internal/ai/assistant`

输入：

```json
{
  "question": "我适合什么岗位？",
  "profile": {"name":"张三","education":"BACHELOR","skills":["Java","MySQL"],"summary":"有Java项目经验"}
}
```

profile 中 summary 可为 null，其他字段必填。无需传手机号。输出 data：`{"answer":"可以优先考虑Java后端开发实习或初级岗位……"}`。

### 10.5 简历向量写入

`PUT /internal/vector/resumes/{resumeId}/versions/{resumeVersion}`

输入（所有字段必填）：

```json
{
  "candidateId": "1001", "text": "学历、技能、项目经历等检索文本……",
  "metadata": {"confirmed": true, "discoverable": true}
}
```

输出 data：`{resumeId: string, resumeVersion: integer, indexed: true, embeddingModel: string, dimension: integer}`。

按 `resumeId:resumeVersion` 作为 Chroma document ID 幂等 upsert，同版本重试不会重复插入。解析成功时可先写 confirmed=false；用户确认后写新版本并清理旧版本。检索文本由 Java 组装，尽量去除姓名、电话等非匹配字段；确认后以最终资料替换结构化部分。向量模型和维度由配置固定，更换模型需要重建集合，禁止混用。

### 10.6 简历向量删除

`DELETE /internal/vector/resumes/{resumeId}/versions/{resumeVersion}`

输入：路径 resumeId、resumeVersion；无 body。输出 data：`{resumeId: string, resumeVersion: integer, deleted: true}`；不存在也成功。Java 在旧版本、删除、关闭发现、封禁时安排清理对应版本；检索白名单确保迟到的向量写入不会重新暴露无效版本。

### 10.7 人才向量检索

`POST /internal/vector/talents/search`

输入：

```json
{
  "jobId": "2001", "jobVersion": 1,
  "queryText": "Java后端开发，熟悉Spring Boot与MySQL",
  "topK": 10, "minSimilarity": 0.6,
  "eligibleResumes": [{"resumeId":"3003","resumeVersion":2}]
}
```

全部字段必填；eligibleResumes 空数组立即返回空结果，不允许理解为不限制范围。Python 仅在指定版本且 confirmed/discoverable 均为 true 的文档内检索。queryText 最多 25000 字；索引 text、匹配 resumeText 最多 60000 字；向量模型长度限制由 Python 通过分块编码并聚合为单简历向量处理，不能静默丢弃文末内容。

输出 data：

```json
{
  "jobId": "2001", "jobVersion": 1,
  "matches": [{"resumeId":"3003","resumeVersion":2,"candidateId":"1003","similarity":0.87}]
}
```

Java 根据返回 ID 重新校验并读取确认资料，组装外部 Candidate。职位查询向量可按 jobId/jobVersion 缓存，不要求单独持久化职位向量集合。Chroma 或 embedding 服务不可用返回 50301，不能伪装成成功的空推荐。

### 10.8 健康检查

`GET /internal/health`

输入：无（仍需内部认证）。输出 data：`{status: "UP"|"DEGRADED", ocrReady: boolean, vectorReady: boolean, llmConfigured: boolean}`。不实际调用付费模型；llmConfigured 只说明配置存在。依赖不可用 HTTP 503，code=50301、data=null，message 描述不可用依赖。

## 11. 数据落库与实现建议

| 表 / 存储 | 主要内容 |
|---|---|
| `account` | 手机号唯一、用户名忽略大小写唯一、密码哈希、账号启用状态 |
| `profile` | account_id、角色、姓名、学历、企业资料、头像、审核、启用、人才发现开关；(account_id, role) 唯一 |
| `resume` | 用户归属、文件内部路径、当前标识、版本、解析/确认/索引状态、parsed_*、全文、确认资料、原填快照、差异和失败信息 |
| `job` | 企业归属、职位字段、状态、版本、审核原因和发布时间 |
| `application` | 用户＋职位唯一、简历版本、投递时简历/职位快照、状态、profile_source |
| `ai_task` | 创建者、任务类型、状态、输入版本快照、结果 JSON、失败信息、时间 |
| `resume_job_match` | 简历 ID/版本＋职位 ID/版本、0～100 分、原因、差距、计算时间；复用时须再次验证调用者权限 |
| `audit_log` | 管理员审核、禁用、关闭等操作；操作者、对象、原因、时间 |
| Chroma | 简历各版本向量、candidateId、确认及发现元数据 |
| 本地 uploads | PDF、头像；前端永远只拿受控下载地址 |

建议实现顺序：登录与角色 → 企业/职位审核 → PDF上传与异步解析 → 确认及投递 → 匹配/面试题/助手 → 向量索引与人才检索。

## 12. 联调验收清单

- 注册手机号重复、非法 ADMIN 注册、验证码过期和复用均失败；第 5 次密码失败锁定 15 分钟。
- 求职者不能发布职位，企业不能访问其他企业投递，待审企业不能开展业务，前端伪造角色无效。
- PDF 上传后先返回任务状态；扫描件可 OCR；解析失败可重试；未确认不能投递。
- 确认更新姓名/学历但不修改登录手机号；原填信息和冲突记录可追溯；过期版本确认返回 40904。
- 同一职位重复投递被数据库约束阻止；新上传简历不会修改历史投递与附件。
- 关闭或下架职位不可继续投递；企业一键采用 AI 不修改用户全局信息。
- AI 任务可轮询，失败有明确原因；匹配分数随职位区分；面试题恰好 10 道；助手没有跨请求对话记忆。
- 关闭人才发现、禁用、删除、换新简历时，即使 Chroma 清理延迟也不会推荐旧数据；已投递候选人被排除。
- Python 中断不会回滚已经成功的简历上传或投递，任务失败可以恢复；服务重启后不会永久停在 PROCESSING。

本文暂不包含：移动端、聊天/IM、面试邀约与流程管理、OSS/MinIO、LangGraph、完整 RAG，以及短信验证、找回密码、收藏、消息通知等未明确要求的扩展功能。


## 13. 账号与多身份契约（已对齐前端）

本节认证接口已在后端实现，前端已经接入。接口表中短路径统一添加 /api 前缀。User 是兼容现有页面的档案视图，业务 userId 均为 profile.id。

### 数据模型

| 表 | 字段及约束 |
|---|---|
| account | id、phone（唯一）、username（忽略大小写唯一）、password_hash、enabled、创建时间；保存登录凭证 |
| profile | id、account_id、role、name、education、头像、企业资料、review_status、enabled；唯一约束 `(account_id, role)` |

用户名采用 `[A-Za-z][A-Za-z0-9_]{3,31}`，避免与手机号混淆。同一手机号注册一次，登录后添加另一身份。公开添加仅允许 JOB_SEEKER / COMPANY，管理员由后台初始化。企业新档案为 PENDING。已有用户迁移时应保留旧 user.id 作为 profile.id，业务外键指向 profile；登录凭证移入 account，旧密码哈希保持原算法。

现有 User 输出继续供业务页面使用，增加 `accountId`、`username`；`id` 明确表示当前 profile.id，`phone` 来自 account。`companyId`、`candidateId` 和旧接口中的 `userId` 均表示档案 ID。简历解析不得改写 account.phone。管理员原 `/admin/users/{userId}/enabled` 按档案启停，User.enabled 表示账号与档案均有效；账号整体停用由后端单独管理并使其所有身份失效。

### 通用返回类型

所有输出包在 `{ "code": 0, "message": "成功", "data": ..., "requestId": "..." }` 中。

ProfileOption：

```json
{"id":"1","role":"JOB_SEEKER","name":"张三","companyName":null,"reviewStatus":"APPROVED","enabled":true}
```

Session 输出（以下展示待选择状态）：

```json
{
  "stage": "SELECT_PROFILE",
  "user": null,
  "profiles": [
    {"id":"1","role":"JOB_SEEKER","name":"张三","companyName":null,"reviewStatus":"APPROVED","enabled":true},
    {"id":"2","role":"COMPANY","name":null,"companyName":"示例科技","reviewStatus":"PENDING","enabled":true}
  ],
  "csrfToken": "server-generated-token"
}
```

- ANONYMOUS：user=null、profiles=[]；匿名会话恢复返回 HTTP 200。
- SELECT_PROFILE：凭证验证通过，user=null，profiles 为该账号的身份选项；此时尚无业务权限。
- AUTHENTICATED：user 为完整 User（含 accountId、username、当前档案 id 和 role），profiles 为本账号身份列表。
- 三种状态均返回当前有效 csrfToken。档案 ID 不能是数字类型。

### 请求路径、输入、输出

| 方法及路径（拼接上述前缀） | 输入 | 输出 / 行为 |
|---|---|---|
| GET `/auth/captcha` | 无 | 保持旧契约：captchaId、imageBase64、expiresIn、csrfToken；保留已登录或待选择会话 |
| POST `/auth/register` | `{username,phone,password,role,companyName?,captchaId,captchaCode}` | HTTP 201，`{userId,reviewStatus}`；创建 account 和首个 profile，userId 为档案 ID，不自动登录 |
| POST `/auth/login` | `{loginName,password,captchaId,captchaCode}` | Session；loginName 为手机号或用户名。单档案直接 AUTHENTICATED，多档案进入 SELECT_PROFILE |
| GET `/auth/session` | 无 | Session；页面刷新恢复，包括待选择状态 |
| GET `/auth/profiles` | 无 | Session；允许已验证账号在待选择或已登录状态调用，匿名返回 401 |
| POST `/auth/select-profile` | `{"profileId":"1"}` | Session，成功为 AUTHENTICATED；仅待选择会话使用 |
| POST `/auth/switch-profile` | `{"profileId":"2"}` | Session，成功为 AUTHENTICATED；仅完整登录后使用 |
| POST `/auth/profiles` | `{"role":"COMPANY","companyName":"示例科技"}` 或 `{"role":"JOB_SEEKER"}` | HTTP 201，ProfileOption；仅完整登录可添加，保持原当前身份。前端随后重新 GET 身份列表 |
| POST `/auth/logout` | 无 | `null`；清除完整或待选择会话 |
| GET `/users/me` | 无 | 当前身份的完整 User；待选择状态不能访问 |

登录示例输入：

```json
{"loginName":"zhangsan","password":"example123","captchaId":"captcha-id","captchaCode":"ABCD"}
```

将 loginName 改为手机号即可，前端不会传 phone 或 role 作为登录凭证字段。添加公司身份的 companyName 必填，2～100 字。已有同角色身份返回冲突，不重复创建。

### 会话和错误处理

- 延续 Session Cookie + X-CSRF-Token，所有写请求包含 CSRF 头。登录、选择、切换成功后轮换会话标识及 CSRF，输出新 token；前端接收成功结果后才改变当前角色。
- 待选择会话只保存已验证 accountId，建议 5 分钟有效；不能通过业务接口直接指定角色绕过选择。取消弹窗调用 logout。
- 完整身份下前端发送 `X-Profile-Id`，切换请求该头为旧身份、请求体为目标身份。后端必须从 Session 取得真实账号及当前档案，验证资源归属与权限；该头仅用于检测旧请求，不能用它授权。
- 业务请求和切换请求带来的旧身份不一致应在操作执行前拒绝。恢复 `/auth/session` 以 Cookie 为准，不能因旧头陷入无限刷新。后端应妥善处理同一会话并发写入和切换，避免将旧请求应用到新身份。
- 同浏览器标签页共享 Session；选择、切换、退出会广播无敏感信息的刷新通知。前端忽略旧身份尚未完成的业务响应，销毁旧页面；不同标签页不承诺独立身份。
- 验证码、5 次失败锁定 15 分钟在后端实现；手机号和用户名应归并到同一账号计数，切换身份不能解除锁定。每次业务访问检查账号、档案有效性、角色和企业审核状态。

| HTTP / code | 含义 |
|---|---|
| 401 / 40101 | 会话不存在或过期 |
| 403 / 40301 | 无权使用该档案、档案停用或操作不允许 |
| 403 / 40303 | CSRF 无效；前端不自动重放写请求 |
| 409 / 40901 | 手机号或用户名已注册 |
| 409 / 40905 | 身份已变化；前端清空当前状态并刷新恢复会话 |
| 409 / 40906 | 已存在该角色档案 |
| 409 / 40907 | 需要先完成身份选择 |

企业待审可以进入资料和账号身份页面，业务操作仍由后端拒绝。接口错误沿用原信封，data=null，不输出密码或其他账号的身份信息。

### 前端验收

登录输入手机号或用户名；多档案弹窗支持刷新恢复、停用身份禁选、失败重试与取消退出；个人中心进入 `/account/profiles` 添加缺少身份或切换，页头也可切换。切换成功进入目标角色工作台。浏览器测试使用隔离接口样例，真实联调须由后端实现上述接口后开展。


### 框架升级说明

后端使用 Spring Boot 3.5.16、Java 17、MyBatis-Plus 3.5.17 的 Boot 3 Starter；Servlet 和 Validation 使用 jakarta 包，Redis 配置为 spring.data.redis。升级不改变 Java/Python HTTP 通信和原业务路径。本文认证与招聘业务接口均已实现，真实MySQL与Redis集成测试已验证核心流程。

参考：[Spring Boot 3.5 要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[MyBatis-Plus 安装](https://baomidou.com/en/getting-started/install/)。


### 认证实现与公共实体说明

业务逻辑位于AuthService和AccountService；Controller只处理HTTP输入输出。Account/Profile继承BaseEntity，createdAt/updatedAt通过MyBatis-Plus的@TableField填充注解和EntityTimeHandler自动维护，对应created_at/updated_at。时间注解适用于传实体的MyBatis-Plus插入和更新。

完整建表脚本位于后端docs/init.sql。普通注册不创建管理员，且账号和初始档案在同一事务中落库。Redis不可用时认证失败并返回503，不降级为绕过锁定。验证码、注册和登录按实际连接IP限制每分钟120次；部署反向代理时需统一规划可信代理及限流策略。


## 15. Redis与实际运行规则

Redis连接沿用spring.data.redis，本地127.0.0.1:6379、1号库、无密码。业务数据仍在MySQL；Redis失效不等于任务或投递数据消失。

| Key前缀 | 用途 | TTL/规则 |
|---|---|---|
| `job-platform:auth:` | 登录失败计数、账号锁定、认证IP限流 | 保留认证模块规则 |
| `job-platform:business:rate:` | 按当前档案ID和操作限流 | Lua原子INCR+EXPIRE，首次请求开始固定窗口 |
| `job-platform:business:lock:` | 简历上传、投递、AI任务提交的短期防重 | SET NX，30秒；随机token，Lua比较后释放 |
| `job-platform:business:job:{id}:{version}` | 公开职位详情缓存 | 120秒；编辑、审核、关闭、删除清理；读取缓存前重新检查MySQL职位与企业状态 |

具体业务频率：上传简历10次/小时、重新解析10次/小时、索引重试10次/小时、头像20次/小时、投递30次/分钟、人才检索10次/分钟、AI生成6次/分钟且60次/小时。每个档案最多3个进行中的AI生成任务。限流返回42902，短期提交锁冲突返回40902；数据库仍以唯一键防止重复投递/进行中AI任务。

职位缓存不可用时回源MySQL；限流或提交锁所需Redis不可用时返回50301，不绕过控制。没有缓存手机号、简历全文、投递快照、会话或AI任务结果；Session仍由Servlet容器管理。

人才检索单次最多10000个合格简历版本，超出返回40902，不静默截断白名单。Java发送白名单并在Python返回后重新校验发现开关、启用状态、确认状态、当前版本和投递关系。关闭职位或禁用企业后，即使命中Redis也不能继续公开访问。

文件校验：PDF由PDFBox检查签名、可读性、加密状态和1–20页；JPEG/PNG限制2MB及1600万像素并重新编码。下载校验路径始终位于uploads内，历史投递附件在当前简历删除后仍可授权下载。

解析文本和确认资料组合后若超过60000字，返回可读错误，请精简简历，不静默截断。Java对AI评分、面试题数量和字段长度做第二次结构校验。

已删除框架示例`/api/system/ping`，依赖健康检查使用`/actuator/health`。当前实现没有引入JWT，继续采用Session、CSRF和AuthInterceptor；所有请求路径均不带版本段。


## 16. 职位发现与企业规模

求职者登录后默认进入 `/jobs`：顶部搜索、横向筛选、双列职位卡片；点击卡片进入 `/jobs/{id}` 展示完整详情。求职者端取消左侧导航，右上角“个人中心”提供简历、投递、AI工具、个人资料和账号身份入口。企业和管理员保留各自工作台。

企业档案仍存profile表，新增可空company_size字段，不另建重复的公司表。行业复用industry，公司标识图复用avatar_path。企业资料PUT接口允许companySize；不填或null清空，非枚举值返回40001。资料修改仍需重新审核。

规模取值：UNDER_20（20人以下）、20_99（20–99人）、100_499（100–499人）、500_999（500–999人）、1000_9999（1000–9999人）、10000_PLUS（10000人以上）。

GET /api/jobs新增筛选参数：
- industry：按企业行业包含匹配，最多100字。
- companySize：按上述规模枚举精确匹配。
- experience：按职位最低经验年数筛选，ENTRY=0，1_3=1至3，3_5=大于3且不超过5，5_PLUS=大于5。不传则不限。

所有筛选同时生效。职位响应新增companyIndustry、companySize、companyAvatarUrl；未填写返回null，前端展示“行业未填写”“规模未填写”，没有头像时用公司名称首字占位。缓存命中时也重新读取企业展示信息。

数据库升级SQL已写入docs/init.sql末尾，通过information_schema检查后仅添加缺失列；本机已执行升级，无需再次导入整份初始化脚本。


## 17. 简历动态可选字段

简历解析在基础姓名、联系方式、学历、技能和摘要之外，返回以下五个字段。数据库在 `resume` 表中使用可空 JSON 列，每个非空字段是字符串数组，每项对应一条经历或证书（最多20项，每项最多2000字）。没有对应内容则返回 `null`，模型不得编造经历或填充“暂无”等占位文字。

| 返回字段 | 数据库字段 | 内容 |
| --- | --- | --- |
| parsedWorkExperience | parsed_work_experience | 工作经历 |
| parsedInternshipExperience | parsed_internship_experience | 实习经历 |
| parsedProjectExperience | parsed_project_experience | 项目经历 |
| parsedCampusExperience | parsed_campus_experience | 校园经历 |
| parsedCertificates | parsed_certificates | 证书 |

新增返回片段示例（合并在原简历对象中）：

```json
{
  "parsedWorkExperience": null,
  "parsedInternshipExperience": null,
  "parsedProjectExperience": ["招聘平台：负责 Spring Boot 接口开发，实现简历上传与投递功能。"],
  "parsedCampusExperience": null,
  "parsedCertificates": ["大学英语六级"]
}
```

适用接口：Python `POST /internal/resumes/parse`，Java 获取当前简历与简历详情接口。Java 负责校验、入库和JSON序列化，Python不直接操作MySQL。兼容旧AI服务缺失字段或空数组，统一处理为null。求职者简历页按非空数组动态显示栏目，为null、缺失或空数组时隐藏。

新投递的五类信息保存在 `resumeSnapshot.optionalSections`，字段名称与上表相同，企业投递详情同样动态展示；历史投递不补写，避免改变投递时快照。重新解析清空旧字段并重新提取，旧简历需要重新解析才能得到这些内容。确认接口支持编辑五类可选信息，详见下方补充。

DDL及幂等增量升级语句统一存放在 `docs/init.sql`。


### 可选经历编辑与换行

`POST /api/resumes/{id}/confirm` 新增可选请求属性 `optionalSections`，对象内可使用上述五个 `parsed*` 字段，每个值为字符串数组或null。例如：

```json
{"expectedVersion":1,"name":"张三","contactPhone":"13800138000","education":"BACHELOR","skills":["Java"],"optionalSections":{"parsedProjectExperience":["招聘平台\n职责：开发接口\n成果：完成上线"],"parsedWorkExperience":null}}
```

每类最多20项，每项1至2000字。null或空数组清空该栏目，省略整个optionalSections或其中字段时保留已有确认值（首次确认沿用AI结果）。编辑结果及换行保存到 `resume.confirmed_profile` 的 `optionalSections`，不覆盖AI原始字段，无需新增数据库列。前端经历多行编辑框随底部确认按钮提交；企业按当前展示来源选择确认值或AI原文。历史投递仍使用当时快照。


## 企业职位发布流程调整

企业登录默认进入 `/company/jobs`，使用顶部导航和右上角企业中心。查看投递从具体职位进入 `/company/jobs/{jobId}/applications`，请求 `GET /api/company/applications?jobId={jobId}`，后端始终限制为本企业数据。

新增 `POST /api/company/jobs/{id}/publish`，无请求体，需要企业身份、CSRF及当前档案头。成功返回原职位对象，`status=APPROVED`（前端显示“已发布”），同时写入发布时间并清理缓存。不再等待管理员审核职位。旧 `/submit-review` 路径兼容调用同一直接发布逻辑。管理员仍可下架违规职位，历史待审核数据保留原管理接口。

发布前服务层校验企业名称（至少2字）、行业、合法公司规模、所在城市和公司简介均已填写；缺项返回409及待补充字段提示。企业账号必须启用且企业认证审核通过。企业认证审核与职位审核是独立规则，前者保留。保存草稿不要求这五项齐全。未通过认证的企业可读取自己的职位列表，不能发布。

无需新增表或字段，复用profile的公司信息及job的状态、发布时间。历史职位不自动批量上架。


## 求职者公司介绍页

前端 `/companies/{id}`：由职位列表公司名和职位详情公司名进入，展示公司名称、行业、人数规模、所在城市、公司简介及全部在招职位（每页10条）。复用 `GET /api/companies/{id}` 获取基本资料。`GET /api/jobs` 新增可选参数 `companyId`（正整数），与已发布状态、企业可用性共同过滤；其他企业、草稿和已关闭职位不返回。无在招职位显示空状态。公司页支持查看职位详情和确认简历后投递。


## 身份与角色资料拆表（2026-09-14）

数据库职责如下，本文早期章节中提及profile的角色专属字段以本节为准：

| 表 | 内容 |
| --- | --- |
| account | 用户名、手机号、密码哈希、账号启用状态 |
| profile | id、account_id、role、enabled、显示名称name、头像或Logo路径avatar_path、created_at、updated_at |
| candidate_profile | profile_id（主键兼外键）、education、city、introduction、discoverable |
| company_profile | profile_id（主键兼外键）、company_name、industry、company_size、city、company_description、review_status、review_reason |

两个角色资料表与公共身份一对一关联。身份显示名称（或企业联系人）及头像/Logo为共用字段，统一保留在profile；资料更新时间由修改时同步更新profile的updated_at记录。管理员不需要独立业务资料表，非企业角色无需认证审核，聚合接口中的reviewStatus固定返回APPROVED。

`profile_details` 是只读数据库视图，通过左连接合并上述三张表，用于兼容现有接口与筛选。它不存储重复数据。`ProfileRepository` 在事务中写入各自的真实表；账号注册同时建立身份和对应的资料行，任一步失败整体回滚。

原有profile.id全部保持不变，job.company_id、resume.candidate_id以及投递、AI任务、审计的身份外键继续引用profile.id。接口路径、JSON属性名称、前端表单均不变，企业发布所需资料校验继续生效。

`docs/init.sql` 已包含新表结构、数据复制、旧专属字段移除和视图创建的幂等迁移段。升级前须备份并停止旧版后端，执行迁移后启动新版本；不可让旧版本继续写原结构。已有公司资料补充脚本已改为更新company_profile。

本次未生成批量测试数据，也未修改现有账号密码。


## 企业投递筛选：学历、工作年限、年龄

企业端 `GET /api/company/applications` 支持以下可选参数，筛选与jobId、企业归属条件共同生效，先过滤再分页：

- `education`：按投递时确认的最高学历筛选，包含所选学历及以上（高中/中专、大专、本科、硕士、博士），不支持 `OTHER`；不传则不限学历。
- `experience`：`0`（不足1整年）、`1_3`（1至3年）、`3_5`（大于3年至5年）、`5_10`（大于5年至10年）、`10_PLUS`（大于10年）。
- `ageMin`、`ageMax`：0至120的整数，可只填一端，下限不得大于上限。

企业筛选界面仅显示以上三类条件，不提供关键词、状态、匹配度、投递时间筛选；状态仍在列表中展示，原有状态接口参数为兼容其他调用保留。求职者投递页的状态筛选保持不变。

简历确认接口新增可空 `birthDate`（YYYY-MM-DD，不得晚于今天）和 `workExperienceYears`（0至60整数，不含实习、按完整年计）字段，在简历确认页选填。它们存入resume.confirmed_profile JSON，提交后随application.resume_snapshot固定保存，无需增加数据库列。列表返回candidateEducation、candidateWorkExperienceYears、candidateAge，年龄按当前北京时间日期计算周岁。缺失字段返回null，前端显示未提供；启用相应范围后不匹配未知值。零年工作经验不等同于应届生。

历史投递不回填或猜测年龄与工作年限；重新确认只影响之后的投递。


### 简历自动提取与投递自动评分
- 简历解析结果新增 `parsedBirthDate`（YYYY-MM-DD）、`parsedAge`（0–120）、`parsedWorkExperienceYears`（0–60，整年不含实习）；缺少可靠依据返回 null，Java 持久化到 resume 对应字段。最高学历沿用 parsedEducation。
- 简历确认请求新增可选 `age`，并沿用 birthDate、workExperienceYears。前端从解析结果自动填写，用户确认后写入 confirmed_profile，投递时写入 resume_snapshot。出生日期优先计算年龄，仅提供年龄时作为确认时年龄展示。
- POST /api/applications 成功时自动入队一次 MATCH 任务，不等待模型。结果保存 resume_job_match，企业列表 matchScore 自动读取；同简历和职位版本已有评分则复用。失败保留 ai_task 错误，不伪造分数，不回滚成功投递。
- 仅上传/解析简历时没有目标职位，不进行无目标的人岗评分。求职者手动 AI 接口继续可用。
- 企业端不展示状态列及手动匹配/面试题工具、简历全文展开。求职者端投递状态和 AI 功能不变。
- 新增列的可重复增量迁移见 init.sql 末尾。旧投递快照不自动修改；重新解析并确认可补充后续投递资料。


### 职位向量生命周期与行业语义筛选
- GET /api/jobs 的 industry 参数在求职者公开列表中表示岗位语义方向：所选行业与职位名称、职责、要求及技能进行向量检索，不限制招聘公司的登记行业。无行业选项时不进行行业检索。
- keyword 继续混合检索。两者同时填写时取两组结果交集；城市、薪资、学历、经验、公司规模和 companyId 继续精确筛选。先过滤再召回、融合排序和分页；返回前复查职位公开状态与版本。
- 语义相似度最低0.55，相关性并非专业分类保证；向量服务失败时退回岗位文本关键词匹配。行业匹配不使用公司名称。
- 职位发布同事务创建 job_vector_task，后台异步 UPSERT；关闭、下架、删除创建 DELETE。每个职位任务串行处理，失败最多自动尝试3次，超时处理中任务可恢复。
- 每分钟补建已发布历史职位索引并补偿企业禁用/恢复。草稿不建向量；新版本发布后删除旧版本并写入新版本。SQL见 init.sql 末尾。
- 企业职位接口新增 indexStatus、indexError。POST /api/company/jobs/{id}/index-retry：无请求体，企业归属校验后重试失败索引，返回202和职位数据。
- Python新增 PUT/DELETE /internal/vector/jobs/{jobId}/versions/{jobVersion}，写入请求为 {"text":"职位名称、职责、要求及技能"}；POST /internal/vector/jobs/search 请求为 {"queryText":"互联网","eligibleJobs":[{"jobId":"1","jobVersion":1}],"minSimilarity":0.55}，响应data.matches含jobId、jobVersion、similarity。
- Chroma职位独立集合由 default.yml 的 embedding.job_collection 配置，复用本地Embedding模型，不调用DeepSeek生成评分。

行业选项筛选：公开列表 industry 使用职位内容向量召回（短行业词阈值0.45），不按公司登记行业限制；keyword 阈值保持0.55。二者并存时取交集，无新增前端开关。

职位筛选边界修正：experience=1_3 对应 [1,3)，3_5 对应 [3,5)，5_PLUS 对应 >=5。education 按 HIGH_SCHOOL<JUNIOR_COLLEGE<BACHELOR<MASTER<DOCTOR 包含所选等级及以上；OTHER仅匹配其他。选择等级时不包含学历不限的职位。

岗位推荐问答与推荐列表：GET /api/jobs?mode=recommended 读取本人当前已确认简历，通过职位向量检索，沿用筛选与分页。相似度至少0.65且与最高分差距不超过0.08；不固定推荐数量，不返回无关职位凑数。未确认简历返回409，推荐服务不可用返回503，不回退全部职位。
POST /api/ai/assistant 保持原请求体与异步轮询，先以问题和简历检索在招职位，再生成回答。结果为 answer 和 sources（jobId、jobVersion、title、companyName、city、salaryMin、salaryMax、reason、available）；引用必须来自检索结果，历史引用重新检查职位有效性。不写入人岗匹配分。
Python POST /internal/vector/jobs/search 新增可选 resumeText；POST /internal/ai/recommendation-answer 接受 question、resumeText、jobs，返回 answer、recommendations[{jobId,reason}]。


## 2026-09-15 多轮岗位推荐对话

- POST /api/ai/assistant 新增可选 previousTaskId（上一轮成功任务ID）；省略表示新对话，原单轮调用兼容。任务视图增加 question、previousTaskId。
- 会话链保存在 ai_task.input_snapshot.previousTaskId，不需要数据库迁移。问题、回答均从数据库读取；只允许引用当前求职身份本人的 ASSISTANT 成功任务，简历ID/版本必须一致。非法身份返回404，未完成/失败前序及简历版本变化返回409。MATCH/INTERVIEW不接受previousTaskId。
- GET /api/ai/tasks/{id}/conversation 返回 {records: AiTask[], nextTaskId: string|null}；records按时间正序，每页最多50轮。nextTaskId非空时，以其再次调用相同接口加载更早消息。每条历史岗位引用重新检查可用性。
- 模型上下文使用最近最多6轮完整问答、合计序列化长度不超过30000字符；这是模型上下文预算，不限制会话总轮数或数据库历史保存。更早记录可查看，但不保证模型记住窗口之外的内容。
- 每次追问先调用 Python POST /internal/ai/conversation-query：输入 {question, history:[{question,answer,sources}]}，输出 {query,referencedJobIds}。将追问改写成独立检索问题，最新明确修改的条件优先。Java/Python均拒绝不属于历史引用的ID。
- 泛推荐用改写后的query重新进行向量检索；明确指代历史岗位时读取对应职位的当前有效版本，已变更/下架的历史版本不能再次推荐。调用 /internal/ai/recommendation-answer 时传入 question、resumeText、jobs、history；历史只用于理解对话，当前jobs才是岗位事实依据。
- 前端改为聊天气泡、一次发送、Enter发送/Shift+Enter换行、失败重试、暂停后继续查询和新建对话。浏览器仅保存按身份及简历版本隔离的最新任务ID，不保存简历及回答正文；刷新时从授权接口恢复会话及未完成任务。
- 新建对话清除当前浏览器会话入口，不删除已保存任务；暂未提供独立历史会话列表。简历更新后使用新的会话入口，避免混用旧简历上下文。


## 2026-09-15 通用对话意图与检索策略更新

- 岗位推荐问答每轮均进行通用意图规划（包括首轮）。SEARCH重新检索、REFERENCES解释/比较已引用岗位、ADVICE提供一般职业建议；不使用职业名称或用户某一句话的硬编码分支。
- 计划字段：intent、query、resumeMode（MATCH/CONTEXT/IGNORE）、requirements、exclusions、referencedJobIds、excludeSeen。最新明确要求覆盖对应旧条件，未取消的其他条件继续保留；只把用户意图作为偏好来源，不把历史助手推断当成用户要求。
- MATCH在满足明确条件的基础上参考简历；CONTEXT跨方向探索不按旧专业筛选，但可解释可迁移能力；IGNORE完全不使用简历排序、筛选或评价，回答阶段也去掉旧助手回答中复述的简历信息。
- task.result.searchPlan保存本轮有效计划；下一轮的输入快照保存previousPlan，从而保留短历史窗口之前仍有效的偏好。新对话不继承计划；旧任务没有计划时仍可以从可用历史开始规划。
- 对话向量检索仅为候选排序：MATCH的简历权重为0.25，其他模式不传resumeText。候选不再被0.65简历匹配阈值提前淘汰。合法但未入索引的岗位仍作为后续候选审核。发现职位页面“推荐”模式维持原来的权重和相关性筛选规则，不受此次调整影响。
- 在选出回答用岗位之前，调用 Python POST /internal/ai/review-job-candidates，根据主要职责、真实要求、正向条件及排除条件逐一审核。请求 {plan,resumeText?,jobs}；返回 {decisions:[{jobId,eligible,reason}]}。必须恰好覆盖本批输入ID，Java/Python双重校验；审核错误时任务失败，不回填未审核结果。
- 每批最多12个岗位且约36000字符；批次是模型输入预算，不是全部检索上限。不合格岗位不占回答上下文名额，继续扫描后续候选。每轮回答最多使用6个已审核岗位；审核扫描约90秒预算，到期可以返回已有结果或明确尚有岗位未检查，不能声称全平台无结果。在招候选安全上限仍为10000。
- result.retrieval记录scope、candidateCount、reviewedCount、complete，便于诊断实际执行范围；回答用自然语言说明结果范围，不向用户暴露字段名或程序参数。
- Python通用提示词集中于 app/prompts.py（PLAN/REVIEW/ANSWER）；不再用包含“应届/远程”等关键词的Java分支替代通用条件理解。远程、经验、专业深度、地点、薪资、职业方向等均按各自真实含义审核，条件不互相替代。
- 无匹配结果时准确说明本轮未找到，不能附带推荐用户已排除的职业，也不能声称平台无法重新检索或要求用户提供平台岗位数据。
