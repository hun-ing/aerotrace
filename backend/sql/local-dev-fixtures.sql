-- 반복 테스트 전에 현재 테스트 Span만 정리한다.
DELETE FROM spans
WHERE tenant_id = '11111111-1111-1111-1111-111111111111'
  AND project_id = '22222222-2222-2222-2222-222222222222'
  AND service_name IN (
                       'aerotrace-manual-test',
                       'attribute-test-service'
    );


-- 로컬 테스트 전용 Tenant
INSERT INTO tenants (
    id,
    name,
    slug
)
VALUES (
           '11111111-1111-1111-1111-111111111111',
           'AeroTrace 로컬 개발',
           'aerotrace-local-development'
       )
ON CONFLICT DO NOTHING;


-- 로컬 테스트 전용 Project
INSERT INTO projects (
    id,
    tenant_id,
    name,
    slug
)
VALUES (
           '22222222-2222-2222-2222-222222222222',
           '11111111-1111-1111-1111-111111111111',
           'AeroTrace 백엔드 로컬 테스트',
           'aerotrace-backend-local-test'
       )
ON CONFLICT DO NOTHING;


SELECT
    t.id AS tenant_id,
    t.name AS tenant_name,
    p.id AS project_id,
    p.name AS project_name
FROM tenants t
         JOIN projects p
              ON p.tenant_id = t.id
WHERE t.id = '11111111-1111-1111-1111-111111111111'
  AND p.id = '22222222-2222-2222-2222-222222222222';