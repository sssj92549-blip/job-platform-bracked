package cn.itcast.demo.jobplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import cn.itcast.demo.jobplatform.entity.Profile;

/**
 * profile_details只读聚合视图；禁止调用写方法，写入使用ProfileRepository。
 */
public interface ProfileMapper extends BaseMapper<Profile> {
}

