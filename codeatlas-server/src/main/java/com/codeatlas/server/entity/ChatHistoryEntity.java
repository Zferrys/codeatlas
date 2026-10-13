package com.codeatlas.server.entity;

import java.time.LocalDateTime;

public class ChatHistoryEntity {

    private Long id;
    private Long projectId;
    private Long userId;
    private String scope;
    private String contextFqn;
    private String question;
    private String answer;
    private String verification;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public String getContextFqn() { return contextFqn; }
    public void setContextFqn(String contextFqn) { this.contextFqn = contextFqn; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public String getVerification() { return verification; }
    public void setVerification(String verification) { this.verification = verification; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
