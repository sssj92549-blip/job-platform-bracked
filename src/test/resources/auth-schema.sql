CREATE TABLE IF NOT EXISTS account (
  id BIGINT NOT NULL,
  phone VARCHAR(11) NOT NULL,
  username VARCHAR(32) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uk_account_phone UNIQUE (phone),
  CONSTRAINT uk_account_username UNIQUE (username),
  CONSTRAINT ck_account_enabled CHECK (enabled IN (0,1))
);

CREATE TABLE IF NOT EXISTS profile (
  id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  role ENUM('JOB_SEEKER','COMPANY','ADMIN') NOT NULL,
  name VARCHAR(50) NULL,
  avatar_path VARCHAR(255) NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uk_profile_account_role UNIQUE (account_id,role),
  CONSTRAINT fk_profile_account FOREIGN KEY (account_id) REFERENCES account(id),
  CONSTRAINT ck_profile_flags CHECK (enabled IN (0,1))
);
CREATE TABLE IF NOT EXISTS candidate_profile (
  profile_id BIGINT NOT NULL,
  education ENUM('HIGH_SCHOOL','JUNIOR_COLLEGE','BACHELOR','MASTER','DOCTOR','OTHER') NULL,
  city VARCHAR(50) NULL,
  introduction VARCHAR(2000) NULL,
  discoverable TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (profile_id),
  CONSTRAINT fk_candidate_profile_identity FOREIGN KEY (profile_id) REFERENCES profile(id),
  CONSTRAINT ck_candidate_discoverable CHECK (discoverable IN (0,1))
);
CREATE TABLE IF NOT EXISTS company_profile (
  profile_id BIGINT NOT NULL,
  company_name VARCHAR(100) NOT NULL,
  industry VARCHAR(100) NULL,
  company_size VARCHAR(32) NULL,
  city VARCHAR(50) NULL,
  company_description VARCHAR(2000) NULL,
  review_status ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING',
  review_reason VARCHAR(500) NULL,
  PRIMARY KEY (profile_id),
  CONSTRAINT fk_company_profile_identity FOREIGN KEY (profile_id) REFERENCES profile(id),
  CONSTRAINT ck_company_name CHECK (CHAR_LENGTH(TRIM(company_name)) BETWEEN 2 AND 100)
);
CREATE OR REPLACE VIEW profile_details AS
SELECT p.id,p.account_id,p.role,p.name,p.avatar_path,p.enabled,p.created_at,p.updated_at,
       c.education,CASE WHEN p.role='COMPANY' THEN e.city ELSE c.city END AS city,
       c.introduction,COALESCE(c.discoverable,0) AS discoverable,
       e.company_name,e.industry,e.company_size,e.company_description,
       CASE WHEN p.role='COMPANY' THEN e.review_status ELSE 'APPROVED' END AS review_status,
       e.review_reason
FROM profile p LEFT JOIN candidate_profile c ON c.profile_id=p.id AND p.role='JOB_SEEKER'
LEFT JOIN company_profile e ON e.profile_id=p.id AND p.role='COMPANY';