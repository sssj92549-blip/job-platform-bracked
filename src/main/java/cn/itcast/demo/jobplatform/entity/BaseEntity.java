package cn.itcast.demo.jobplatform.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

/** 实体公共字段：ID由MyBatis-Plus生成，时间由EntityTimeHandler自动填充。 */
public abstract class BaseEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 创建时间：插入实体时自动填充，对应created_at。 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    /** 更新时间：插入、更新实体时自动刷新，对应updated_at。 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { this.createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { this.updatedAt = value; }
}

