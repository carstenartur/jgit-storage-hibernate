ALTER TABLE git_reflog ADD delivery_id NVARCHAR(128) NULL;

CREATE INDEX idx_reflog_repo_delivery
    ON git_reflog (repository_name, delivery_id)
    WHERE delivery_id IS NOT NULL;
