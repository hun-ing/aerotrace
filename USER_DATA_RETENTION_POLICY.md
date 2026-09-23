# AeroTrace User Data Retention Policy

> 마지막 업데이트: 2026-09-21
> 상태: Phase C session-only Frontend까지 구현, Production 수집 비활성. 자동 domain-data purge와 사용자 self-service export/delete는 미구현
> 범위: 사람의 identity, tenant membership, onboarding invite, security audit와 server session metadata

## 1. 목적과 다른 정책과의 경계

이 문서는 AeroTrace에 로그인하는 사람과 관련된 metadata의 최소 수집, 보존, 삭제와 복원 후 처리를 정한다. Trace payload, Project API Key와 Cloudflare notification data의 보존은 각각 기존 database/notification 정책과 runbook을 따른다.

Phase B Backend에는 OAuth login과 JDBC session 데이터 흐름이 구현됐지만 기본·Production auth profile은 비활성이고 실제 GitHub OAuth App/secret도 배치하지 않았다. 따라서 현재 Production은 이 흐름으로 사용자 data를 수집하지 않는다. 아래 보존 목표 중 자동 purge가 없는 값은 이미 보장되는 retention으로 표현하지 않는다.

Phase C Frontend는 현재 사용자·membership·project metadata와 권한 있는 Trace를 표시한다. 별도 identity DB, localStorage/sessionStorage의 auth token, OAuth token 보관은 추가하지 않는다. Invite는 POST 처리에만 사용하고 application log·URL·browser storage에 남기지 않는다. Session/조회 응답은 `no-store`이며 raw Cookie와 callback query를 edge access log에서도 제외하는 것은 Production 활성화 전 검증 항목이다. 새 Frontend 배포만으로 보존·삭제 정책이 구현됐다고 보지 않는다.

## 2. 수집하는 데이터

| 범주 | 저장 항목 | 목적 | 저장하지 않는 항목 |
|---|---|---|---|
| Local user | UUID, display name, avatar URL, status, 생성·수정·마지막 login 시각 | UI 표시, account 상태 | Password, email, 전화번호 |
| GitHub identity | provider, numeric subject, 현재 login, 생성·최근 확인 시각 | 동일 사용자 식별과 진단 | OAuth access/refresh token, repository/org/email scope data |
| Membership | tenant/user UUID, OWNER/ADMIN/VIEWER, active/revoked 상태와 시각 | Tenant authorization | Project별 ACL, credential |
| Invite | tenant, role, 32-byte token hash, creator/consumer UUID, 만료·소비·폐기 시각 | Invite-only onboarding과 재사용 방지 | Invite 원문, URL, provider token |
| Security audit | actor/tenant/project UUID, 제한된 action/result, 시각, correlation UUID | 인증·권한 incident 조사 | 자유 형식 payload, cookie, session ID, token, API Key, trace payload |
| JDBC session | 불투명 session 식별자, expiry/index metadata, 최소 authentication attribute | Server-side login session | OAuth token, role/membership snapshot, trace payload |
| Anonymous auth attempt | session 안의 10분 window 시작 시각·시도 횟수와 OAuth 시작 marker | onboarding/login abuse 완화와 callback audit 구분 | IP profile, provider token, invite 원문 |

GitHub login과 display name은 변경 가능한 표시 metadata다. Authorization identity key는 numeric provider subject이며 email/login을 권한 key로 사용하지 않는다.

## 3. 최소화와 접근 원칙

- GitHub repository, organization과 email scope를 요청하지 않는다.
- OAuth token은 identity 확인 직후 폐기하고 DB, session, log와 audit에 저장하지 않는다.
- Session에는 local `user_id`와 최소 authentication marker만 두고 role/membership은 요청마다 DB에서 다시 확인한다.
- OAuth authorization request의 state/PKCE와 onboarding intent의 invite row UUID는 anonymous server session에만 두며 invite 원문은 저장하지 않는다.
- Audit table에는 JSON/text metadata payload column을 두지 않아 임의 비밀정보 저장을 구조적으로 막는다.
- Invite code는 URL query/path, session attribute와 audit에 넣지 않는다.
- 운영 조회는 aggregate와 필요한 metadata만 출력하고 token hash, session attribute bytes와 provider response 원문을 조회하지 않는다.

## 4. 초기 보존 목표

| 데이터 | 활성 상태 | 종료 후 목표 | 현재 자동화 |
|---|---|---|---|
| Active user와 identity | Account 사용 기간 | 확인된 account 삭제 시 제거 | 미구현 |
| Active membership | Membership 사용 기간 | Revoke metadata 180일 후 제거 대상 | 미구현 |
| 미사용 invite | 만료 또는 폐기까지, 최대 7일 | 만료·폐기 후 30일 | 미구현 |
| 소비된 invite | 소비 증거 30일 | 이후 제거 대상, audit는 별도 유지 | 미구현 |
| Security audit | Incident 조사와 권한 변경 추적 | 180일 | 미구현 |
| JDBC session과 anonymous auth state | Idle 8시간, authenticated session absolute 7일 이내; auth attempt window 10분 | 매분 expiry cleanup 또는 즉시 revoke | Repository 구현, Production 비활성; user/global revoke operator entry point 미구현 |

기본 invite 유효기간은 24시간이고 operator가 설정할 수 있는 상한은 7일이다. 위 표의 invite 30일·audit 180일은 purge job과 acceptance가 있어야 실제 보장이 된다. Spring Session expiry cleanup은 auth profile이 실행 중일 때만 작동하며 off-host backup 안의 copy를 삭제하지 않는다.

Audit 180일은 초기 MVP 기준이다. 법적·계약상 요구, incident 조사 기간과 저장량을 30일 운영 review에서 재검토하되 필요 이상 연장하지 않는다.

## 5. Account disable, membership revoke와 삭제

`DISABLED` user와 `REVOKED` membership은 보존 중이어도 다음 authorization request부터 접근할 수 없다. 보존과 접근 허용은 별개다.

Account 삭제 전 순서:

```text
본인 확인과 최근 인증
-> 다른 active OWNER 존재 또는 tenant 삭제 승인 확인
-> 해당 user의 모든 session revoke
-> 미사용 invite revoke
-> creator/consumer로 연결된 terminal invite를 정책에 따라 삭제
-> user identity와 local user 삭제
-> membership cascade 삭제
-> security audit actor_user_id는 NULL로 비식별화
-> 결과와 correlation ID만 별도 audit
```

현재 `onboarding_invites.created_by_user_id`와 `consumed_by_user_id`는 증거 일관성을 위해 user 삭제를 `RESTRICT`한다. 따라서 terminal invite retention 처리 없이 user row를 강제로 삭제하거나 FK를 비활성화하지 않는다.

사용자 삭제는 tenant의 trace, project와 Project API Key를 자동 삭제하지 않는다. Tenant data ownership 이전 또는 tenant 전체 삭제는 별도 승인과 telemetry retention 정책을 따른다.

## 6. Export와 사용자 요청

현재 사용자 self-service export/delete endpoint가 없다. 실제 사용자 data를 받는 공개 MVP 전 다음을 구현한다.

- 본인 identity와 membership metadata export
- 자신이 actor인 audit event의 안전한 요약 범위 결정
- OWNER 이전을 포함한 account deletion workflow
- 요청 완료·거부·보류 사유와 correlation ID audit
- 다른 tenant member의 개인정보와 credential을 export에서 제외하는 test

원문 invite, token hash, OAuth token, session ID/attribute bytes와 Project API Key hash는 export 대상이 아니다.

## 7. Backup과 삭제의 한계

Full logical database backup에는 user, identity, membership, invite, audit와 session table이 포함된다. Live DB에서 삭제해도 기존 backup archive 안에는 삭제 전 data가 남을 수 있다.

Backblaze B2 US West private bucket, provider-side encryption과 Object Lock은 준비했지만 bucket은 비어 있다. B2 application key, client-side `age` key, production dump upload/download/restore, backup retention 기간과 자동 만료는 아직 확정·가동하지 않았다. 따라서 실제 사용자 data 수집 전에 다음을 완료해야 한다.

- 암호화된 off-host backup 보존 기간과 삭제 schedule 확정
- Account deletion 뒤 backup 잔존 기간을 사용자 문서에 공개
- Backup 만료 전 접근 통제와 restore 목적 제한
- Restore 후 session 삭제와 미사용 invite 일괄 revoke
- 삭제된 account가 restore로 재활성화되지 않는 acceptance

이 checkpoint 전에는 account 삭제가 모든 backup copy에서 즉시 지워진다고 약속하지 않는다.

## 8. DR restore와 재활성화 방지

복원 검증은 auth/session row가 archive에서 정확히 복원되는지 확인한다. 실제 traffic 재개는 별도 문제다.

Traffic 전 필수 순서:

```text
모든 restored session 삭제
-> 모든 restored 미사용 invite revoke
-> disabled user와 revoked membership 상태 확인
-> 삭제 요청 이후 시점 backup인지 확인
-> 필요한 owner/bootstrap invite만 새로 발급
-> aggregate 0과 audit 확인
-> traffic 승인
```

구체 SQL과 안전 확인은 [Authentication Operations Runbook](AUTHENTICATION_OPERATIONS_RUNBOOK.md)을 따른다.

## 9. Logging과 incident

다음 값이 application, CI, issue, screenshot 또는 support message에 나타나면 credential/privacy incident 후보로 취급한다.

- `ati_` invite 원문
- OAuth access/refresh token
- Session cookie/ID와 serialized session attribute
- Project API Key 원문 또는 hash
- Provider response 원문과 불필요한 profile metadata

즉시 log 보존 범위와 접근자를 제한하고 노출 invite/session/credential을 폐기한다. 공개 issue에는 값을 붙이지 않고 [Security Policy](SECURITY.md)의 private report 경로를 사용한다.

## 10. 구현 checkpoint

현재 구현 상태와 Production activation 전 남은 항목:

- Expired JDBC session cleanup: repository 구현, auth profile 실행 중 매분
- User/global session revoke application service: 구현; operator CLI/API 또는 승인된 SQL rehearsal은 미완료
- Expired/revoked/consumed invite purge
- Audit 180일 purge와 aggregate evidence
- Account export/delete 및 last-owner 보호
- Backup 잔존 경계와 restored account 비활성화 test
- Application/CI artifact의 OAuth/session/invite credential scan

## 11. 관련 문서

- [User Authentication and Onboarding Design](USER_AUTH_ONBOARDING_DESIGN.md)
- [Authentication Operations Runbook](AUTHENTICATION_OPERATIONS_RUNBOOK.md)
- [Database Backup/Restore Runbook](DATABASE_BACKUP_RESTORE_RUNBOOK.md)
- [Data Retention Policy](DATA_RETENTION_POLICY.md)
- [Security Policy](SECURITY.md)
