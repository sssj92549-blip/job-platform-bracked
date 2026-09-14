-- 招聘平台完整初始化脚本（MySQL 8.0.16+，建议 MySQL 8.4 LTS）
-- 执行：mysql -u root -p --default-character-set=utf8mb4 < docs/init.sql
-- 仅创建不存在的库/表，不删除数据；已有同名表不会自动升级，结构变更应另写迁移。
-- BIGINT ID 由 Java/MyBatis-Plus ASSIGN_ID 生成，API 输出为字符串。
-- 时间统一按北京时间写入 DATETIME(3)。JSON 数组/对象由服务层校验结构。
-- 外键禁止级联删除，历史投递、附件和审计必须保留；角色及资源归属仍需服务层检查。
SET NAMES utf8mb4;
CREATE DATABASE IF NOT EXISTS job_platform CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE job_platform;

CREATE TABLE IF NOT EXISTS account (
  id BIGINT NOT NULL COMMENT '账号ID，雪花算法生成',
  phone VARCHAR(11) NOT NULL COMMENT '登录手机号，唯一',
  username VARCHAR(32) NOT NULL COMMENT '用户名，忽略大小写唯一；Java限制ASCII字母开头',
  password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt哈希，禁止存明文',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '账号整体启用状态',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_account_phone (phone),
  UNIQUE KEY uk_account_username (username),
  CONSTRAINT ck_account_enabled CHECK (enabled IN (0,1))
) ENGINE=InnoDB COMMENT='登录凭证；验证码、锁定计数及Session由服务端管理';

CREATE TABLE IF NOT EXISTS profile (
  id BIGINT NOT NULL COMMENT '身份ID，业务中的userId/candidateId/companyId均指此ID',
  account_id BIGINT NOT NULL COMMENT '所属账号',
  role ENUM('JOB_SEEKER','COMPANY','ADMIN') NOT NULL COMMENT '角色',
  name VARCHAR(50) NULL COMMENT '姓名',
  education ENUM('HIGH_SCHOOL','JUNIOR_COLLEGE','BACHELOR','MASTER','DOCTOR','OTHER') NULL COMMENT '学历',
  avatar_path VARCHAR(255) NULL COMMENT 'uploads下相对路径，不是公开URL',
  city VARCHAR(50) NULL COMMENT '城市',
  introduction VARCHAR(2000) NULL COMMENT '个人简介',
  discoverable TINYINT NOT NULL DEFAULT 0 COMMENT '求职者是否允许人才发现',
  company_name VARCHAR(100) NULL COMMENT '公司名，企业必填',
  industry VARCHAR(100) NULL COMMENT '行业',
  company_size VARCHAR(32) NULL COMMENT '人数规模：UNDER_20/20_99/100_499/500_999/1000_9999/10000_PLUS',
  company_description VARCHAR(2000) NULL COMMENT '企业简介',
  review_status ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING' COMMENT '审核状态；求职者由服务层设为APPROVED',
  review_reason VARCHAR(500) NULL COMMENT '审核原因',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '当前档案是否启用',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_profile_account_role (account_id,role),
  KEY idx_profile_review (role,review_status,enabled,id),
  KEY idx_profile_discovery (role,discoverable,enabled,review_status,id),
  CONSTRAINT fk_profile_account FOREIGN KEY (account_id) REFERENCES account(id),
  CONSTRAINT ck_profile_flags CHECK (enabled IN (0,1) AND discoverable IN (0,1)),
  CONSTRAINT ck_profile_discovery CHECK (role='JOB_SEEKER' OR discoverable=0),
  CONSTRAINT ck_profile_company CHECK (role<>'COMPANY' OR (company_name IS NOT NULL AND CHAR_LENGTH(TRIM(company_name)) BETWEEN 2 AND 100))
) ENGINE=InnoDB COMMENT='角色档案；同一账号可以拥有求职者和企业身份';

CREATE TABLE IF NOT EXISTS job (
  id BIGINT NOT NULL COMMENT '职位ID',
  company_id BIGINT NOT NULL COMMENT '所属企业profile.id',
  title VARCHAR(100) NOT NULL COMMENT '职位名称',
  city VARCHAR(50) NOT NULL COMMENT '工作城市',
  salary_min INT NOT NULL COMMENT '最低月薪，元',
  salary_max INT NOT NULL COMMENT '最高月薪，元',
  education_requirement ENUM('HIGH_SCHOOL','JUNIOR_COLLEGE','BACHELOR','MASTER','DOCTOR','OTHER') NULL COMMENT '学历要求，NULL不限',
  experience_min_years INT NOT NULL DEFAULT 0 COMMENT '最低经验年限',
  description TEXT NOT NULL COMMENT '职位描述，服务层限制10000字',
  requirements TEXT NOT NULL COMMENT '职位要求，服务层限制10000字',
  skills JSON NOT NULL COMMENT '技能字符串数组，允许[]',
  status ENUM('DRAFT','PENDING','APPROVED','REJECTED','CLOSED') NOT NULL DEFAULT 'DRAFT' COMMENT '职位状态',
  version INT NOT NULL DEFAULT 1 COMMENT '草稿修改乐观锁版本',
  review_reason VARCHAR(500) NULL COMMENT '审核或关闭原因',
  published_at DATETIME(3) NULL COMMENT '审核通过发布时间',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；仅允许删除草稿等文档指定状态',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_job_public (status,deleted,city,created_at,id),
  KEY idx_job_company (company_id,deleted,status,created_at,id),
  CONSTRAINT fk_job_company FOREIGN KEY (company_id) REFERENCES profile(id),
  CONSTRAINT ck_job_salary CHECK (salary_min>=0 AND salary_max>=salary_min AND salary_max<=1000000),
  CONSTRAINT ck_job_experience CHECK (experience_min_years BETWEEN 0 AND 50),
  CONSTRAINT ck_job_flags CHECK (version>=1 AND deleted IN (0,1))
) ENGINE=InnoDB COMMENT='职位；发布后不可原地编辑';

CREATE TABLE IF NOT EXISTS resume (
  id BIGINT NOT NULL COMMENT '简历ID，每次新上传生成新ID',
  candidate_id BIGINT NOT NULL COMMENT '所属求职者profile.id',
  version INT NOT NULL DEFAULT 1 COMMENT '重新解析/确认递增；异步写回必须匹配版本',
  is_current TINYINT NOT NULL DEFAULT 1 COMMENT '是否当前简历',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除，不删除历史投递附件',
  current_candidate_id BIGINT GENERATED ALWAYS AS (CASE WHEN is_current=1 AND deleted=0 THEN candidate_id ELSE NULL END) STORED COMMENT '唯一当前简历约束，历史记录为NULL',
  file_name VARCHAR(255) NOT NULL COMMENT '原始展示文件名',
  file_path VARCHAR(255) NOT NULL COMMENT 'uploads下随机PDF相对路径',
  file_size BIGINT NOT NULL COMMENT '附件字节数',
  parse_status ENUM('PENDING','PROCESSING','SUCCESS','FAILED') NOT NULL DEFAULT 'PENDING' COMMENT '解析状态',
  confirmation_status ENUM('UNCONFIRMED','CONFIRMED') NOT NULL DEFAULT 'UNCONFIRMED' COMMENT '确认状态',
  conflict_status ENUM('NONE','PENDING_VERIFY','RESOLVED') NOT NULL DEFAULT 'NONE' COMMENT '冲突状态',
  index_status ENUM('NOT_READY','PENDING','PROCESSING','READY','FAILED','DELETING','DELETED') NOT NULL DEFAULT 'NOT_READY' COMMENT '当前版本向量状态',
  parsed_name VARCHAR(50) NULL COMMENT 'AI姓名',
  parsed_phone VARCHAR(32) NULL COMMENT 'AI联系方式，不修改登录手机号',
  parsed_education ENUM('HIGH_SCHOOL','JUNIOR_COLLEGE','BACHELOR','MASTER','DOCTOR','OTHER') NULL COMMENT 'AI学历',
  parsed_skills JSON NULL COMMENT 'AI技能数组',
  parsed_work_experience JSON NULL COMMENT 'AI工作经历数组，无内容为NULL',
  parsed_internship_experience JSON NULL COMMENT 'AI实习经历数组，无内容为NULL',
  parsed_project_experience JSON NULL COMMENT 'AI项目经历数组，无内容为NULL',
  parsed_campus_experience JSON NULL COMMENT 'AI校园经历数组，无内容为NULL',
  parsed_certificates JSON NULL COMMENT 'AI证书数组，无内容为NULL',
  parsed_summary TEXT NULL COMMENT 'AI摘要',
  extracted_text MEDIUMTEXT NULL COMMENT '提取全文，服务层限制60000字',
  extraction_method ENUM('TEXT','OCR','MIXED') NULL COMMENT '提取方式',
  page_count INT NULL COMMENT 'PDF页数',
  original_profile JSON NOT NULL COMMENT '上传时用户自填资料快照',
  conflicts JSON NULL COMMENT '差异数组，保留原始证据',
  confirmed_profile JSON NULL COMMENT '确认后的姓名、联系方式、学历和技能',
  parse_error VARCHAR(1000) NULL COMMENT '解析错误摘要',
  index_error VARCHAR(1000) NULL COMMENT '向量索引错误摘要',
  parse_started_at DATETIME(3) NULL COMMENT '处理起点，重启恢复使用',
  confirmed_at DATETIME(3) NULL COMMENT '用户确认时间',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_resume_current (current_candidate_id),
  KEY idx_resume_candidate (candidate_id,deleted,created_at,id),
  KEY idx_resume_parse (parse_status,parse_started_at),
  CONSTRAINT fk_resume_candidate FOREIGN KEY (candidate_id) REFERENCES profile(id),
  CONSTRAINT ck_resume_flags CHECK (version>=1 AND is_current IN (0,1) AND deleted IN (0,1)),
  CONSTRAINT ck_resume_size CHECK (file_size>0 AND file_size<=10485760),
  CONSTRAINT ck_resume_confirm CHECK (confirmation_status<>'CONFIRMED' OR (parse_status='SUCCESS' AND confirmed_profile IS NOT NULL AND confirmed_at IS NOT NULL))
) ENGINE=InnoDB COMMENT='简历及当前解析版本；匹配分数放resume_job_match';

CREATE TABLE IF NOT EXISTS application (
  id BIGINT NOT NULL COMMENT '投递ID',
  candidate_id BIGINT NOT NULL COMMENT '求职者profile.id',
  job_id BIGINT NOT NULL COMMENT '职位ID',
  resume_id BIGINT NOT NULL COMMENT '投递简历ID，文件保留',
  resume_version INT NOT NULL COMMENT '投递时简历版本',
  job_version INT NOT NULL COMMENT '投递时职位版本',
  status ENUM('SUBMITTED','VIEWED','SHORTLISTED','REJECTED','WITHDRAWN') NOT NULL DEFAULT 'SUBMITTED' COMMENT '投递状态',
  profile_source ENUM('CONFIRMED','AI') NOT NULL DEFAULT 'CONFIRMED' COMMENT '本投递展示资料来源',
  resume_snapshot JSON NOT NULL COMMENT '不可变简历快照，含原填、AI、确认、冲突、全文',
  job_snapshot JSON NOT NULL COMMENT '不可变职位及公司展示快照',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '投递时间',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_application_candidate_job (candidate_id,job_id),
  KEY idx_application_company_list (job_id,status,created_at,id),
  KEY idx_application_candidate_list (candidate_id,status,created_at,id),
  CONSTRAINT fk_application_candidate FOREIGN KEY (candidate_id) REFERENCES profile(id),
  CONSTRAINT fk_application_job FOREIGN KEY (job_id) REFERENCES job(id),
  CONSTRAINT fk_application_resume FOREIGN KEY (resume_id) REFERENCES resume(id),
  CONSTRAINT ck_application_versions CHECK (resume_version>=1 AND job_version>=1)
) ENGINE=InnoDB COMMENT='投递；撤回后仍禁止重复投递，禁止物理删除';

CREATE TABLE IF NOT EXISTS ai_task (
  id BIGINT NOT NULL COMMENT 'AI任务ID',
  creator_id BIGINT NOT NULL COMMENT '创建者profile.id，结果访问权限依据',
  type ENUM('MATCH','INTERVIEW','ASSISTANT') NOT NULL COMMENT '生成任务类型，与API一致',
  status ENUM('PENDING','PROCESSING','SUCCESS','FAILED') NOT NULL DEFAULT 'PENDING' COMMENT '任务状态',
  application_id BIGINT NULL COMMENT '企业分析的投递ID',
  resume_id BIGINT NOT NULL COMMENT '简历ID',
  resume_version INT NOT NULL COMMENT '固定简历版本',
  job_id BIGINT NULL COMMENT '助手无需职位',
  job_version INT NULL COMMENT '固定职位版本',
  question VARCHAR(2000) NULL COMMENT '助手单次问题，不保存会话历史',
  request_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化输入SHA256，含创建者/类型/版本/投递/问题',
  active_request_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING','PROCESSING') THEN request_key ELSE NULL END) STORED COMMENT '进行中任务幂等键',
  input_snapshot JSON NOT NULL COMMENT '调用时固定输入，不在执行时读取可变简历',
  result JSON NULL COMMENT '结构校验后的结果',
  error_code INT NULL COMMENT '业务错误码',
  error_message VARCHAR(1000) NULL COMMENT '脱敏错误摘要',
  started_at DATETIME(3) NULL COMMENT '开始处理时间，用于超时恢复',
  completed_at DATETIME(3) NULL COMMENT '结束时间',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_active_request (active_request_key),
  KEY idx_ai_creator (creator_id,created_at,id),
  KEY idx_ai_recovery (status,started_at,created_at),
  CONSTRAINT fk_ai_creator FOREIGN KEY (creator_id) REFERENCES profile(id),
  CONSTRAINT fk_ai_application FOREIGN KEY (application_id) REFERENCES application(id),
  CONSTRAINT fk_ai_resume FOREIGN KEY (resume_id) REFERENCES resume(id),
  CONSTRAINT fk_ai_job FOREIGN KEY (job_id) REFERENCES job(id),
  CONSTRAINT ck_ai_inputs CHECK (resume_version>=1 AND ((type='ASSISTANT' AND question IS NOT NULL AND job_id IS NULL AND job_version IS NULL AND application_id IS NULL) OR (type IN ('MATCH','INTERVIEW') AND job_id IS NOT NULL AND job_version>=1))),
  CONSTRAINT ck_ai_result CHECK ((status='SUCCESS' AND result IS NOT NULL AND completed_at IS NOT NULL) OR (status<>'SUCCESS' AND result IS NULL))
) ENGINE=InnoDB COMMENT='持久化AI生成任务；服务层提交事务后调度';

CREATE TABLE IF NOT EXISTS resume_job_match (
  id BIGINT NOT NULL COMMENT '匹配结果ID',
  resume_id BIGINT NOT NULL COMMENT '简历ID',
  resume_version INT NOT NULL COMMENT '简历版本',
  job_id BIGINT NOT NULL COMMENT '职位ID',
  job_version INT NOT NULL COMMENT '职位版本',
  score INT NOT NULL COMMENT '大模型评分0到100，不是向量相似度',
  reasons JSON NOT NULL COMMENT '匹配原因数组',
  gaps JSON NOT NULL COMMENT '差距数组',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '计算时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_match_versions (resume_id,resume_version,job_id,job_version),
  CONSTRAINT fk_match_resume FOREIGN KEY (resume_id) REFERENCES resume(id),
  CONSTRAINT fk_match_job FOREIGN KEY (job_id) REFERENCES job(id),
  CONSTRAINT ck_match_score CHECK (score BETWEEN 0 AND 100 AND resume_version>=1 AND job_version>=1)
) ENGINE=InnoDB COMMENT='按人岗版本缓存评分；使用前必须重新校验权限';

CREATE TABLE IF NOT EXISTS audit_log (
  id BIGINT NOT NULL COMMENT '日志ID',
  operator_id BIGINT NOT NULL COMMENT '操作者profile.id',
  target_type ENUM('ACCOUNT','PROFILE','JOB','APPLICATION','RESUME') NOT NULL COMMENT '操作对象类型',
  target_id BIGINT NOT NULL COMMENT '多态对象ID，由服务层验证',
  action VARCHAR(50) NOT NULL COMMENT '如APPROVE/REJECT/DISABLE/ENABLE/CLOSE/ADOPT_AI',
  reason VARCHAR(500) NULL COMMENT '操作原因',
  before_value JSON NULL COMMENT '变更前必要字段，禁止密码等敏感信息',
  after_value JSON NULL COMMENT '变更后必要字段',
  request_id VARCHAR(64) NULL COMMENT '请求追踪ID',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '操作时间',
  PRIMARY KEY (id),
  KEY idx_audit_target (target_type,target_id,created_at,id),
  KEY idx_audit_operator (operator_id,created_at,id),
  CONSTRAINT fk_audit_operator FOREIGN KEY (operator_id) REFERENCES profile(id)
) ENGINE=InnoDB COMMENT='追加式业务审计日志';

CREATE TABLE IF NOT EXISTS vector_sync_task (
  id BIGINT NOT NULL COMMENT '内部向量同步任务ID',
  resume_id BIGINT NOT NULL COMMENT '简历ID',
  resume_version INT NOT NULL COMMENT '目标版本，旧版本删除不依赖当前行版本',
  operation ENUM('UPSERT','DELETE') NOT NULL COMMENT '向量写入或删除',
  status ENUM('PENDING','PROCESSING','SUCCESS','FAILED') NOT NULL DEFAULT 'PENDING' COMMENT '同步状态',
  payload JSON NULL COMMENT '固定写入文本及元数据，DELETE可空',
  attempts INT NOT NULL DEFAULT 0 COMMENT '已尝试次数，服务层限制重试上限',
  next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下次执行时间',
  started_at DATETIME(3) NULL COMMENT '执行开始时间，用于恢复超时任务',
  completed_at DATETIME(3) NULL COMMENT '完成时间',
  error_message VARCHAR(1000) NULL COMMENT '脱敏错误摘要',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_vector_schedule (status,next_attempt_at,id),
  KEY idx_vector_resume (resume_id,resume_version,operation),
  CONSTRAINT fk_vector_resume FOREIGN KEY (resume_id) REFERENCES resume(id),
  CONSTRAINT ck_vector_task CHECK (resume_version>=1 AND attempts>=0 AND (operation='DELETE' OR payload IS NOT NULL))
) ENGINE=InnoDB COMMENT='事务内记录索引工作；后台调度调用Chroma并恢复失败任务';

-- 管理员初始化（可选，全部注释，避免默认弱口令和重复覆盖现有账号）：
-- 先通过 PasswordService.encode 生成自己的BCrypt哈希，再替换下面各参数。
-- ID需确认未占用；初始化失败应ROLLBACK，不能只创建account不创建profile。
-- START TRANSACTION;
-- INSERT INTO account(id,phone,username,password_hash) VALUES (1,'你的11位手机号','platform_admin','你生成的BCrypt哈希');
-- INSERT INTO profile(id,account_id,role,name,review_status) VALUES (1,1,'ADMIN','平台管理员','APPROVED');
-- COMMIT;

-- 业务实现注意：
-- 1. 上传替换当前简历：同一事务锁定求职者profile行，旧行is_current=0，再插入新行。
-- 2. 简历确认/异步回写：UPDATE ... WHERE id=? AND version=? AND deleted=0，检查影响行数。
-- 3. 投递：事务内校验当前简历、职位、企业状态，固定快照；唯一键处理并发重复投递。
-- 4. AI复用：规范化request_key后INSERT，唯一键冲突时读取已有进行中任务。
-- 5. 向量同步任务与简历状态同事务写入；定时领取、有限重试、重启恢复由服务层实现。
-- 6. JSON、枚举、外键不能替代角色鉴权；禁止把前端X-Profile-Id直接当作当前身份。

-- 指定企业的开发演示职位：手机号13576200952；仅此账号存在时插入，重复执行不重复添加。
SET NAMES utf8mb4;
USE job_platform;
START TRANSACTION;
SET @seed_company_id = NULL;
SELECT p.id INTO @seed_company_id FROM profile p JOIN account a ON a.id=p.account_id WHERE a.phone='13576200952' AND p.id=2097628693383979010 AND p.role='COMPANY' AND p.enabled=1 AND a.enabled=1 FOR UPDATE;
INSERT INTO job (id,company_id,title,city,salary_min,salary_max,education_requirement,experience_min_years,description,requirements,skills,status,version,deleted) SELECT 2099029336629446945,@seed_company_id,'Java后端开发工程师','杭州',10000,18000,'BACHELOR',1,'负责招聘平台业务接口开发，参与用户、职位、简历和投递模块设计；优化数据库查询并编写接口文档。','熟悉Java、Spring Boot和MyBatis-Plus，掌握MySQL事务、索引及Redis缓存，有REST接口开发经验。','["Java", "Spring Boot", "MyBatis-Plus", "MySQL", "Redis"]','DRAFT',1,0 WHERE @seed_company_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM job existing WHERE existing.company_id=@seed_company_id AND existing.title='Java后端开发工程师' AND existing.deleted=0);
INSERT INTO job (id,company_id,title,city,salary_min,salary_max,education_requirement,experience_min_years,description,requirements,skills,status,version,deleted) SELECT 2099029336627829013,@seed_company_id,'Vue前端开发工程师','杭州',9000,16000,'BACHELOR',1,'负责PC端招聘平台页面开发，完成职位搜索、企业工作台和管理后台交互；与后端协作完成接口联调。','熟悉Vue 3、JavaScript、HTML和CSS，掌握组件化开发、路由和状态管理，能够处理登录状态与接口异常。','["Vue 3", "JavaScript", "HTML", "CSS", "Pinia"]','DRAFT',1,0 WHERE @seed_company_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM job existing WHERE existing.company_id=@seed_company_id AND existing.title='Vue前端开发工程师' AND existing.deleted=0);
INSERT INTO job (id,company_id,title,city,salary_min,salary_max,education_requirement,experience_min_years,description,requirements,skills,status,version,deleted) SELECT 2099029336629924047,@seed_company_id,'Python AI应用开发工程师','上海',12000,22000,'BACHELOR',1,'开发FastAPI智能服务，实现PDF简历解析、大模型调用和向量检索；优化提示词、响应校验及异常处理。','熟悉Python和FastAPI，了解大模型API、Prompt工程及Chroma；有OCR或文档解析项目经验者优先。','["Python", "FastAPI", "大模型API", "PaddleOCR", "Chroma"]','DRAFT',1,0 WHERE @seed_company_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM job existing WHERE existing.company_id=@seed_company_id AND existing.title='Python AI应用开发工程师' AND existing.deleted=0);
INSERT INTO job (id,company_id,title,city,salary_min,salary_max,education_requirement,experience_min_years,description,requirements,skills,status,version,deleted) SELECT 2099029336630403334,@seed_company_id,'软件测试工程师','南京',7000,12000,'JUNIOR_COLLEGE',0,'编写招聘系统测试用例，开展功能测试、接口测试和回归测试；复现缺陷并跟踪修复，关注权限和异常流程。','掌握软件测试方法，能够使用Postman和SQL验证接口结果，了解Python自动化测试和缺陷管理流程。','["接口测试", "Postman", "SQL", "Python", "测试用例"]','DRAFT',1,0 WHERE @seed_company_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM job existing WHERE existing.company_id=@seed_company_id AND existing.title='软件测试工程师' AND existing.deleted=0);
INSERT INTO job (id,company_id,title,city,salary_min,salary_max,education_requirement,experience_min_years,description,requirements,skills,status,version,deleted) SELECT 2099029336629769500,@seed_company_id,'Java开发实习生','杭州',3000,5000,'BACHELOR',0,'协助开发基础业务接口和管理页面，参与数据库设计、单元测试与代码评审，在指导下完成小型功能模块。','计算机相关专业在校生，掌握Java基础、集合和MySQL，了解Spring Boot，能够阅读技术文档并使用Git协作。','["Java", "MySQL", "Spring Boot", "Git"]','DRAFT',1,0 WHERE @seed_company_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM job existing WHERE existing.company_id=@seed_company_id AND existing.title='Java开发实习生' AND existing.deleted=0);
INSERT INTO job (id,company_id,title,city,salary_min,salary_max,education_requirement,experience_min_years,description,requirements,skills,status,version,deleted) SELECT 2099029336630315219,@seed_company_id,'数据分析师','深圳',9000,15000,'BACHELOR',1,'分析职位发布、简历投递及用户活跃数据，设计业务指标并制作统计报表，为招聘运营提供数据支持。','熟悉SQL和Excel，能够使用Python进行数据清洗，理解常见统计指标，具备清晰的业务沟通能力。','["SQL", "Python", "Pandas", "Excel", "数据分析"]','DRAFT',1,0 WHERE @seed_company_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM job existing WHERE existing.company_id=@seed_company_id AND existing.title='数据分析师' AND existing.deleted=0);
INSERT INTO job (id,company_id,title,city,salary_min,salary_max,education_requirement,experience_min_years,description,requirements,skills,status,version,deleted) SELECT 2099029336630043605,@seed_company_id,'运维开发工程师','成都',10000,17000,'BACHELOR',1,'负责Java与Python服务部署、运行监控、日志排查和发布流程维护；配置Nginx并优化服务稳定性。','熟悉Linux、Shell、Docker和Nginx，了解MySQL及Redis运维，能够定位常见端口、网络和资源问题。','["Linux", "Docker", "Nginx", "Shell", "Redis"]','DRAFT',1,0 WHERE @seed_company_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM job existing WHERE existing.company_id=@seed_company_id AND existing.title='运维开发工程师' AND existing.deleted=0);
INSERT INTO job (id,company_id,title,city,salary_min,salary_max,education_requirement,experience_min_years,description,requirements,skills,status,version,deleted) SELECT 2099029336628677234,@seed_company_id,'全栈开发工程师','武汉',12000,20000,'BACHELOR',2,'承担招聘系统前后端功能开发，设计接口和数据结构，完成权限控制、文件上传及第三方AI服务集成。','熟悉Spring Boot和Vue 3，掌握MySQL、Redis及HTTP通信，有完整项目交付经验，注重可维护性和测试。','["Java", "Vue 3", "Spring Boot", "MySQL", "Redis"]','DRAFT',1,0 WHERE @seed_company_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM job existing WHERE existing.company_id=@seed_company_id AND existing.title='全栈开发工程师' AND existing.deleted=0);
COMMIT;
SELECT title,city,salary_min,salary_max,status FROM job WHERE company_id=@seed_company_id AND deleted=0 ORDER BY id;


-- 已有数据库升级：仅新增缺失的企业规模字段，不修改现有企业资料。
SET @company_size_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='profile' AND column_name='company_size')=0, 'ALTER TABLE profile ADD COLUMN company_size VARCHAR(32) NULL COMMENT ''Company employee size'' AFTER industry', 'SELECT 1');
PREPARE company_size_migration FROM @company_size_ddl;
EXECUTE company_size_migration;
DEALLOCATE PREPARE company_size_migration;

-- 简历动态可选字段增量升级；兼容已有数据库，保留历史数据。
SET @resume_optional_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resume' AND column_name='parsed_work_experience')=0, 'ALTER TABLE resume ADD COLUMN parsed_work_experience JSON NULL COMMENT ''AI工作经历数组，无内容为NULL''', 'SELECT 1');
PREPARE resume_optional_migration FROM @resume_optional_ddl;
EXECUTE resume_optional_migration;
DEALLOCATE PREPARE resume_optional_migration;
SET @resume_optional_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resume' AND column_name='parsed_internship_experience')=0, 'ALTER TABLE resume ADD COLUMN parsed_internship_experience JSON NULL COMMENT ''AI实习经历数组，无内容为NULL''', 'SELECT 1');
PREPARE resume_optional_migration FROM @resume_optional_ddl;
EXECUTE resume_optional_migration;
DEALLOCATE PREPARE resume_optional_migration;
SET @resume_optional_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resume' AND column_name='parsed_project_experience')=0, 'ALTER TABLE resume ADD COLUMN parsed_project_experience JSON NULL COMMENT ''AI项目经历数组，无内容为NULL''', 'SELECT 1');
PREPARE resume_optional_migration FROM @resume_optional_ddl;
EXECUTE resume_optional_migration;
DEALLOCATE PREPARE resume_optional_migration;
SET @resume_optional_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resume' AND column_name='parsed_campus_experience')=0, 'ALTER TABLE resume ADD COLUMN parsed_campus_experience JSON NULL COMMENT ''AI校园经历数组，无内容为NULL''', 'SELECT 1');
PREPARE resume_optional_migration FROM @resume_optional_ddl;
EXECUTE resume_optional_migration;
DEALLOCATE PREPARE resume_optional_migration;
SET @resume_optional_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resume' AND column_name='parsed_certificates')=0, 'ALTER TABLE resume ADD COLUMN parsed_certificates JSON NULL COMMENT ''AI证书数组，无内容为NULL''', 'SELECT 1');
PREPARE resume_optional_migration FROM @resume_optional_ddl;
EXECUTE resume_optional_migration;
DEALLOCATE PREPARE resume_optional_migration;
