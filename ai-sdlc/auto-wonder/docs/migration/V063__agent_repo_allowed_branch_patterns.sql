ALTER TABLE agent_repo_perm
    ADD COLUMN allowed_branch_patterns TEXT NULL COMMENT 'JSON branch allowlist; NULL means unrestricted' AFTER perm_level;
