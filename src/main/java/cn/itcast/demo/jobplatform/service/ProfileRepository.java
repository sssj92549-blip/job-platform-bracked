package cn.itcast.demo.jobplatform.service;

import cn.itcast.demo.jobplatform.entity.*;
import cn.itcast.demo.jobplatform.mapper.*;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.*;

/**
 * 角色资料聚合访问：视图负责读取，独立实体负责写入，同一事务保持身份和资料一致。
 */
@Repository
public class ProfileRepository {
    private final ProfileMapper reads;
    private final IdentityProfileMapper identities;
    private final CandidateProfileMapper candidates;
    private final CompanyProfileMapper companies;

    public ProfileRepository(ProfileMapper reads, IdentityProfileMapper identities, CandidateProfileMapper candidates, CompanyProfileMapper companies) {
        this.reads = reads;
        this.identities = identities;
        this.candidates = candidates;
        this.companies = companies;
    }

    public Profile selectById(Serializable id) {
        return reads.selectById(id);
    }

    public List<Profile> selectList(Wrapper<Profile> query) {
        return reads.selectList(query);
    }

    public Profile selectOne(Wrapper<Profile> query) {
        return reads.selectOne(query);
    }

    public Page<Profile> selectPage(Page<Profile> page, Wrapper<Profile> query) {
        return reads.selectPage(page, query);
    }

    /**
     * 注册新身份与相应资料；管理员无需专属资料表。
     */
    @Transactional
    public int insert(Profile profile) {
        IdentityProfile identity = new IdentityProfile();
        BeanUtils.copyProperties(profile, identity);
        if (identity.getEnabled() == null) identity.setEnabled(true);
        int count = identities.insert(identity);
        BeanUtils.copyProperties(identity, profile);
        if ("JOB_SEEKER".equals(profile.getRole())) {
            CandidateProfile candidate = new CandidateProfile();
            BeanUtils.copyProperties(profile, candidate);
            candidate.setProfileId(identity.getId());
            if (candidate.getDiscoverable() == null) candidate.setDiscoverable(false);
            candidates.insert(candidate);
        } else if ("COMPANY".equals(profile.getRole())) {
            CompanyProfile company = new CompanyProfile();
            BeanUtils.copyProperties(profile, company);
            company.setProfileId(identity.getId());
            if (company.getReviewStatus() == null) company.setReviewStatus("PENDING");
            companies.insert(company);
        }
        return count;
    }

    /**
     * 与MyBatis-Plus默认策略一致，仅更新非null属性。
     */
    @Transactional
    public int updateById(Profile patch) {
        return updateFields(patch.getId(), patch, Collections.emptyMap());
    }

    /**
     * 显式清空使用属性名到值的映射，避免MyBatis-Plus忽略null。
     */
    @Transactional
    public int updateFields(Long id, Profile patch, Map<String, Object> explicit) {
        IdentityProfile identity = identities.selectOne(new QueryWrapper<IdentityProfile>().eq("id", id).last("FOR UPDATE"));
        if (identity == null) return 0;
        Profile current = reads.selectById(id);
        BeanWrapperImpl target = new BeanWrapperImpl(current), source = new BeanWrapperImpl(patch);
        for (var property : source.getPropertyDescriptors()) {
            String name = property.getName();
            if (Set.of("class", "id", "accountId", "role", "createdAt", "updatedAt").contains(name)) continue;
            Object value = source.getPropertyValue(name);
            if (value != null) target.setPropertyValue(name, value);
        }
        explicit.forEach(target::setPropertyValue);
        identity.setName(current.getName());
        identity.setAvatarPath(current.getAvatarPath());
        identity.setEnabled(current.getEnabled());
        UpdateWrapper<IdentityProfile> update = new UpdateWrapper<IdentityProfile>().eq("id", id);
        if (current.getName() == null) update.set("name", null);
        if (current.getAvatarPath() == null) update.set("avatar_path", null);
        identities.update(identity, update);
        if ("JOB_SEEKER".equals(current.getRole())) {
            candidates.update(null, new UpdateWrapper<CandidateProfile>().eq("profile_id", id).set("education", current.getEducation()).set("city", current.getCity()).set("introduction", current.getIntroduction()).set("discoverable", Boolean.TRUE.equals(current.getDiscoverable())));
        } else if ("COMPANY".equals(current.getRole())) {
            companies.update(null, new UpdateWrapper<CompanyProfile>().eq("profile_id", id).set("company_name", current.getCompanyName()).set("industry", current.getIndustry()).set("company_size", current.getCompanySize()).set("city", current.getCity()).set("company_description", current.getCompanyDescription()).set("review_status", current.getReviewStatus()).set("review_reason", current.getReviewReason()));
        }
        return 1;
    }

    /**
     * 构造允许包含null的显式字段更新。
     */
    public static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return values;
    }
}
