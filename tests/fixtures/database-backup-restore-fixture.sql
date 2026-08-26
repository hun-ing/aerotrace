INSERT INTO public.tenants (
    id,
    name,
    slug,
    created_at,
    updated_at
)
VALUES (
    '11111111-1111-4111-8111-111111111111',
    'Backup Restore Acceptance',
    'backup-restore-acceptance',
    CURRENT_TIMESTAMP - INTERVAL '2 hours',
    CURRENT_TIMESTAMP - INTERVAL '2 hours'
);

INSERT INTO public.projects (
    id,
    tenant_id,
    name,
    slug,
    created_at,
    updated_at
)
VALUES (
    '22222222-2222-4222-8222-222222222222',
    '11111111-1111-4111-8111-111111111111',
    'Backup Restore Acceptance',
    'backup-restore-acceptance',
    CURRENT_TIMESTAMP - INTERVAL '2 hours',
    CURRENT_TIMESTAMP - INTERVAL '2 hours'
);

INSERT INTO public.project_api_keys (
    id,
    tenant_id,
    project_id,
    name,
    key_id,
    secret_hash,
    created_at,
    expires_at,
    revoked_at
)
VALUES (
    '33333333-3333-4333-8333-333333333333',
    '11111111-1111-4111-8111-111111111111',
    '22222222-2222-4222-8222-222222222222',
    'backup-restore-acceptance',
    'backuprestore001',
    decode(repeat('ab', 32), 'hex'),
    CURRENT_TIMESTAMP - INTERVAL '2 hours',
    CURRENT_TIMESTAMP + INTERVAL '1 day',
    NULL
);

INSERT INTO public.app_users (
    id,
    display_name,
    avatar_url,
    status,
    created_at,
    updated_at,
    last_login_at
)
VALUES (
    '44444444-4444-4444-8444-444444444444',
    'Backup Restore User',
    NULL,
    'ACTIVE',
    CURRENT_TIMESTAMP - INTERVAL '2 hours',
    CURRENT_TIMESTAMP - INTERVAL '1 hour',
    CURRENT_TIMESTAMP - INTERVAL '1 hour'
);

INSERT INTO public.user_identities (
    id,
    user_id,
    provider,
    provider_subject,
    provider_login,
    created_at,
    last_seen_at
)
VALUES (
    '55555555-5555-4555-8555-555555555555',
    '44444444-4444-4444-8444-444444444444',
    'GITHUB',
    '1234567890',
    'backup-restore-user',
    CURRENT_TIMESTAMP - INTERVAL '2 hours',
    CURRENT_TIMESTAMP - INTERVAL '1 hour'
);

INSERT INTO public.tenant_memberships (
    tenant_id,
    user_id,
    role,
    status,
    created_at,
    updated_at,
    revoked_at
)
VALUES (
    '11111111-1111-4111-8111-111111111111',
    '44444444-4444-4444-8444-444444444444',
    'OWNER',
    'ACTIVE',
    CURRENT_TIMESTAMP - INTERVAL '2 hours',
    CURRENT_TIMESTAMP - INTERVAL '1 hour',
    NULL
);

INSERT INTO public.onboarding_invites (
    id,
    tenant_id,
    role,
    token_hash,
    created_by_user_id,
    created_at,
    expires_at,
    consumed_at,
    consumed_by_user_id,
    revoked_at
)
VALUES (
    '66666666-6666-4666-8666-666666666666',
    '11111111-1111-4111-8111-111111111111',
    'OWNER',
    decode(repeat('cd', 32), 'hex'),
    NULL,
    CURRENT_TIMESTAMP - INTERVAL '2 hours',
    CURRENT_TIMESTAMP + INTERVAL '22 hours',
    CURRENT_TIMESTAMP - INTERVAL '1 hour',
    '44444444-4444-4444-8444-444444444444',
    NULL
);

INSERT INTO public.security_audit_events (
    id,
    actor_user_id,
    tenant_id,
    project_id,
    action,
    result,
    occurred_at,
    correlation_id
)
VALUES (
    '77777777-7777-4777-8777-777777777777',
    '44444444-4444-4444-8444-444444444444',
    '11111111-1111-4111-8111-111111111111',
    '22222222-2222-4222-8222-222222222222',
    'BACKUP_RESTORE_FIXTURE',
    'SUCCESS',
    CURRENT_TIMESTAMP - INTERVAL '1 hour',
    '88888888-8888-4888-8888-888888888888'
);

INSERT INTO public.aerotrace_session (
    primary_id,
    session_id,
    creation_time,
    last_access_time,
    max_inactive_interval,
    expiry_time,
    principal_name
)
VALUES (
    '99999999-9999-4999-8999-999999999999',
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    1787700000000,
    1787700000000,
    28800,
    1787728800000,
    '44444444-4444-4444-8444-444444444444'
);

INSERT INTO public.aerotrace_session_attributes (
    session_primary_id,
    attribute_name,
    attribute_bytes
)
VALUES (
    '99999999-9999-4999-8999-999999999999',
    'backup_restore_fixture',
    decode('aced0005', 'hex')
);

INSERT INTO public.spans (
    tenant_id,
    project_id,
    trace_id,
    span_id,
    parent_span_id,
    trace_state,
    flags,
    service_name,
    scope_name,
    scope_version,
    name,
    span_kind,
    status_code,
    status_message,
    start_time,
    end_time,
    duration_nano,
    resource_attributes,
    span_attributes,
    events,
    links,
    ingested_at,
    dropped_attributes_count,
    dropped_events_count,
    dropped_links_count
)
VALUES
    (
        '11111111-1111-4111-8111-111111111111',
        '22222222-2222-4222-8222-222222222222',
        '11111111111111111111111111111111',
        '1111111111111111',
        NULL,
        '',
        1,
        'checkout-api',
        'backup-test',
        '1.0.0',
        'POST /checkout',
        2,
        0,
        '',
        CURRENT_TIMESTAMP - INTERVAL '1 hour',
        CURRENT_TIMESTAMP - INTERVAL '1 hour' + INTERVAL '100 milliseconds',
        100000000,
        '{"service.version":"1.0.0"}',
        '{"http.request.method":"POST"}',
        '[]',
        '[]',
        CURRENT_TIMESTAMP - INTERVAL '59 minutes',
        0,
        0,
        0
    ),
    (
        '11111111-1111-4111-8111-111111111111',
        '22222222-2222-4222-8222-222222222222',
        '11111111111111111111111111111111',
        '2222222222222222',
        '1111111111111111',
        '',
        1,
        'payment-api',
        'backup-test',
        '1.0.0',
        'charge',
        3,
        2,
        'fixture error',
        CURRENT_TIMESTAMP - INTERVAL '59 minutes 59 seconds',
        CURRENT_TIMESTAMP - INTERVAL '59 minutes 58.95 seconds',
        50000000,
        '{"service.version":"1.0.0"}',
        '{"payment.provider":"fixture"}',
        '[{"name":"retry","attributes":{}}]',
        '[]',
        CURRENT_TIMESTAMP - INTERVAL '59 minutes',
        1,
        0,
        0
    ),
    (
        '11111111-1111-4111-8111-111111111111',
        '22222222-2222-4222-8222-222222222222',
        '22222222222222222222222222222222',
        '3333333333333333',
        NULL,
        '',
        0,
        'worker',
        'backup-test',
        '1.0.0',
        'scheduled-task',
        1,
        1,
        '',
        CURRENT_TIMESTAMP - INTERVAL '30 minutes',
        CURRENT_TIMESTAMP - INTERVAL '29 minutes 59.8 seconds',
        200000000,
        '{"service.version":"1.0.0"}',
        '{"job.name":"fixture"}',
        '[]',
        '[{"trace_id":"11111111111111111111111111111111"}]',
        CURRENT_TIMESTAMP - INTERVAL '29 minutes',
        0,
        0,
        1
    );
