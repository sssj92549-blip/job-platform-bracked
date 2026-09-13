package cn.itcast.demo.jobplatform.service;

import cn.itcast.demo.jobplatform.entity.AuditLog;
import cn.itcast.demo.jobplatform.mapper.AuditLogMapper;
import org.springframework.stereotype.Service;
import org.slf4j.MDC;

/** 与业务变更处于同一事务的审计记录，不保存密码、密钥及简历全文。 */
@Service
public class AuditService {
    private final AuditLogMapper logs;
    private final BusinessSupport b;
    public AuditService(AuditLogMapper logs,BusinessSupport b) { this.logs=logs; this.b=b; }
    public void record(Long actor,String type,Long id,String action,String reason,Object before,Object after) {
        AuditLog log=new AuditLog(); log.setOperatorId(actor); log.setTargetType(type); log.setTargetId(id);
        log.setAction(action); log.setReason(reason); log.setBeforeValue(b.write(before)); log.setAfterValue(b.write(after));
        log.setRequestId(MDC.get("requestId")); logs.insert(log);
    }
}
