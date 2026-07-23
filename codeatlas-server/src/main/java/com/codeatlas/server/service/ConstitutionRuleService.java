package com.codeatlas.server.service;

import com.codeatlas.common.dto.PageResult;
import com.codeatlas.server.entity.ConstitutionRuleEntity;

import java.util.List;

public interface ConstitutionRuleService {

    List<ConstitutionRuleEntity> getRules(Long projectId);

    List<ConstitutionRuleEntity> getAllRules(Long projectId);

    PageResult<ConstitutionRuleEntity> getRulesPaged(Long projectId, int page, int size);

    PageResult<ConstitutionRuleEntity> getAllRulesPaged(Long projectId, int page, int size);

    ConstitutionRuleEntity toggleRule(Long ruleId, boolean enabled);

    /** 更新规则定义并自动清理旧违规 */
    void updateRuleDefinition(Long ruleId, String newDefinition);

    /** 删除规则（仅项目级规则可删除） */
    void deleteRule(Long ruleId);
}
