package com.codeatlas.server.service.impl;

import com.codeatlas.common.dto.PageResult;
import com.codeatlas.server.entity.ConstitutionRuleEntity;
import com.codeatlas.server.mapper.ConstitutionRuleMapper;
import com.codeatlas.server.mapper.ViolationMapper;
import com.codeatlas.server.service.ConstitutionRuleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ConstitutionRuleServiceImpl implements ConstitutionRuleService {

    private static final Logger log = LoggerFactory.getLogger(ConstitutionRuleServiceImpl.class);

    private final ConstitutionRuleMapper mapper;
    private final ViolationMapper violationMapper;

    public ConstitutionRuleServiceImpl(ConstitutionRuleMapper mapper, ViolationMapper violationMapper) {
        this.mapper = mapper;
        this.violationMapper = violationMapper;
    }

    @Override
    @Cacheable(value = "rules", key = "#projectId")
    public List<ConstitutionRuleEntity> getRules(Long projectId) {
        return mapper.findByProjectId(projectId);
    }

    @Override
    public List<ConstitutionRuleEntity> getAllRules(Long projectId) {
        return mapper.findAllByProjectId(projectId);
    }

    @Override
    public PageResult<ConstitutionRuleEntity> getRulesPaged(Long projectId, int page, int size) {
        long total = mapper.countByProjectId(projectId);
        int offset = (page - 1) * size;
        List<ConstitutionRuleEntity> records = mapper.findByProjectIdPaged(projectId, offset, size);
        return new PageResult<>(records, total, page, size);
    }

    @Override
    public PageResult<ConstitutionRuleEntity> getAllRulesPaged(Long projectId, int page, int size) {
        long total = mapper.countAllByProjectId(projectId);
        int offset = (page - 1) * size;
        List<ConstitutionRuleEntity> records = mapper.findAllByProjectIdPaged(projectId, offset, size);
        return new PageResult<>(records, total, page, size);
    }

    @Override
    @Transactional
    @CacheEvict(value = "rules", allEntries = true)
    public ConstitutionRuleEntity toggleRule(Long ruleId, boolean enabled) {
        ConstitutionRuleEntity rule = mapper.findById(ruleId);
        if (rule == null) {
            log.warn("Rule not found: id={}", ruleId);
            return null;
        }
        rule.setIsEnabled(enabled);
        mapper.toggleEnabled(rule);

        // 禁用规则时，自动标记该规则的未解决违规为已解决
        if (!enabled) {
            int resolved = violationMapper.resolveAllByRuleId(ruleId);
            log.info("Rule {} disabled, auto-resolved {} violations", rule.getName(), resolved);
        }

        return mapper.findById(ruleId);
    }

    @Override
    @Transactional
    public void updateRuleDefinition(Long ruleId, String newDefinition) {
        ConstitutionRuleEntity rule = mapper.findById(ruleId);
        if (rule == null) {
            log.warn("Rule not found: id={}", ruleId);
            return;
        }
        int version = rule.getVersion() != null ? rule.getVersion() + 1 : 2;
        rule.setRuleDefinition(newDefinition);
        rule.setVersion(version);
        mapper.updateDefinition(rule);

        // 规则定义变更时，自动标记旧违规为已解决
        int resolved = violationMapper.resolveAllByRuleId(ruleId);
        log.info("Rule {} definition updated to v{}, auto-resolved {} violations",
                rule.getName(), version, resolved);
    }
}
