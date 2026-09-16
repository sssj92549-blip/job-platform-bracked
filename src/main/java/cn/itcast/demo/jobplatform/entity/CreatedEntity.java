package cn.itcast.demo.jobplatform.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

/**
 * 追加式记录的公共字段，不存在更新时间列。
 */
public abstract class CreatedEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /**
     * 创建时间，通过填充注解由EntityTimeHandler写入。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long value) {
        id = value;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime value) {
        createdAt = value;
    }
}
