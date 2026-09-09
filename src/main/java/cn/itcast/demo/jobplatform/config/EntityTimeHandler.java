package cn.itcast.demo.jobplatform.config;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 为标注@TableField(fill=...)的实体填充北京时间。 */
@Component
public class EntityTimeHandler implements MetaObjectHandler {
    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
    }
    @Override
    public void updateFill(MetaObject metaObject) {
        // 更新时覆盖旧值；仅用Wrapper而不传实体不会触发实体自动填充。
        setFieldValByName("updatedAt", LocalDateTime.now(ZoneId.of("Asia/Shanghai")), metaObject);
    }
}

