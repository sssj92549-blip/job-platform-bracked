package cn.itcast.demo.jobplatform.mapper;

import cn.itcast.demo.jobplatform.entity.Resume;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * resume基础数据库操作。
 */
public interface ResumeMapper extends BaseMapper<Resume> {
    /**
     * 仅供经过投递归属校验的历史附件访问，包含逻辑删除行。
     */
    @org.apache.ibatis.annotations.Select("select * from resume where id=#{id}")
    Resume historical(Long id);
}
