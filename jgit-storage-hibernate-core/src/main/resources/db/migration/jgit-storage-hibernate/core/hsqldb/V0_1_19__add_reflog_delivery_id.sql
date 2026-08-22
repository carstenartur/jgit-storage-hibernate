ALTER TABLE git_reflog ADD COLUMN delivery_id VARCHAR(128);

CREATE INDEX idx_reflog_repo_delivery
    ON git_reflog (repository_name, delivery_id);
