--liquibase formatted sql

--changeset vttg:001-create-system-submissions
CREATE TABLE system_submissions
(
    id                     UUID          NOT NULL PRIMARY KEY,
    author_id              UUID          NOT NULL,
    author_name            VARCHAR(100),
    repository_url         VARCHAR(2048) NOT NULL,
    manifest_url           VARCHAR(2048) NOT NULL,
    -- Описание из заявки; без него в каталог идёт description из манифеста.
    description            VARCHAR(1000),

    -- Снимок system.json на момент подачи или последнего обновления.
    system_id              VARCHAR(64)   NOT NULL,
    system_name            VARCHAR(150)  NOT NULL,
    system_version         VARCHAR(50)   NOT NULL,
    system_author          VARCHAR(150),
    system_description     VARCHAR(2000),
    compatibility_minimum  VARCHAR(50),
    compatibility_verified VARCHAR(50),
    compatibility_maximum  VARCHAR(50),
    download_url           VARCHAR(2048) NOT NULL,
    manifest_json          TEXT          NOT NULL,
    manifest_synced_at     TIMESTAMPTZ   NOT NULL,

    status                 VARCHAR(20)   NOT NULL,
    moderator_id           UUID,
    moderation_comment     VARCHAR(2000),
    reviewed_at            TIMESTAMPTZ,
    -- Первое одобрение: с него ссылки заявки зафиксированы.
    approved_at            TIMESTAMPTZ,

    created_at             TIMESTAMPTZ   NOT NULL,
    updated_at             TIMESTAMPTZ   NOT NULL,
    version                BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT ck_system_submissions_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'SUPERSEDED')),
    CONSTRAINT ck_system_submissions_rejection_comment
        CHECK (status <> 'REJECTED' OR moderation_comment IS NOT NULL),
    CONSTRAINT ck_system_submissions_system_id
        CHECK (system_id ~ '^[a-z0-9][a-z0-9_-]*$'),
    CONSTRAINT ck_system_submissions_description
        CHECK (description IS NOT NULL OR system_description IS NOT NULL)
);

-- У id системы — не больше одной одобренной заявки и одной на рассмотрении
-- (заявка-замена автора одобренной системы). Отклонённые, отозванные и
-- заменённые id не держат.
CREATE UNIQUE INDEX ux_system_submissions_approved_system_id
    ON system_submissions (system_id)
    WHERE status = 'APPROVED';

CREATE UNIQUE INDEX ux_system_submissions_pending_system_id
    ON system_submissions (system_id)
    WHERE status = 'PENDING';

CREATE INDEX ix_system_submissions_author ON system_submissions (author_id, created_at DESC);
CREATE INDEX ix_system_submissions_status ON system_submissions (status, created_at);
