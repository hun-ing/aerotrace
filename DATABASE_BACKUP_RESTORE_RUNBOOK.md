# AeroTrace Database Backup and Restore Runbook

> 마지막 업데이트: 2026-08-25
> 상태: Ephemeral full-database backup/restore acceptance 완료, production-sized/off-host rehearsal 전
> 범위: Self-hosted TimescaleDB `2.28.3-pg15`의 AeroTrace application database

## 1. 목적

Docker Named Volume은 container 재생성에는 견디지만 host disk 손상, volume 삭제, 잘못된 SQL·migration, 서버 분실과 새 장비 이전을 해결하지 않는다. 이 runbook은 AeroTrace database의 논리 백업을 만들고 별도의 빈 TimescaleDB에 실제 복원할 수 있는지 검증하는 절차를 고정한다.

백업 파일이 존재한다는 사실만으로 성공으로 판단하지 않는다. 다음 조건을 모두 확인해야 usable backup이다.

```text
PostgreSQL custom archive 생성
-> SHA-256 일치
-> archive TOC parse 성공
-> exact PostgreSQL major와 TimescaleDB version target 준비
-> 빈 database에 restore 성공
-> hypertable, columnstore, retention/columnstore policy 확인
-> tenant/project/API Key metadata/span count와 data fingerprint 일치
```

## 2. 보장 범위와 제외 범위

포함:

- `tenants`, `projects`, `project_api_keys`, `spans`
- TimescaleDB hypertable와 internal catalog metadata
- Columnstore 설정과 scheduled columnstore/retention policy
- Flyway schema history가 source DB에 있으면 해당 table과 row
- PostgreSQL database 안의 schema, constraints, indexes와 data

제외:

- PostgreSQL cluster role과 tablespace 같은 global object
- `.env`, `otel-collector.env`, `frontend.env`의 원문 secret
- Project API Key 원문
- Collector persistent queue의 아직 DB에 저장되지 않은 telemetry
- Docker image, Compose file, Worker D1과 Slack notification data
- Point-in-time recovery용 WAL archive

DB에는 Project API Key hash만 있으므로 DB를 복원해도 분실한 원문 Key를 복구할 수 없다. Collector와 Frontend secret file이 함께 유실되면 별도 Key 폐기·재발급 절차가 필요하다.

## 3. Tracked 도구

```text
scripts/database/backup-timescaledb.sh
scripts/database/restore-timescaledb.sh
scripts/database/verify-timescaledb-restore.sh
scripts/database/test-backup-restore.sh
```

안전 기본값:

- Password와 connection URL을 command argument로 받지 않는다.
- Container 내부의 `POSTGRES_USER`와 `POSTGRES_DB`를 값 출력 없이 사용한다.
- Backup directory는 symlink를 거부하고 group/other permission이 없어야 한다.
- Archive, metadata와 checksum은 mode `0600`으로 생성한다.
- Restore는 존재하지 않는 lowercase target database만 허용한다.
- Archive set은 absolute path의 regular file과 group/other permission 없음만 허용한다.
- 실제 `aerotrace-timescaledb` container는 restore target으로 거부한다.
- `postgres`, `template0`, `template1`과 기존 database는 거부한다.
- PostgreSQL major와 TimescaleDB extension version이 정확히 일치해야 한다.
- TimescaleDB catalog 안전을 위해 parallel `pg_restore -j`를 사용하지 않는다.
- `--clean`, `DROP DATABASE`와 기존 DB overwrite 기능을 제공하지 않는다.

## 4. Backup artifact set

성공한 한 번의 backup은 다음 세 파일을 만든다.

```text
aerotrace-db-<UTC timestamp>.dump
aerotrace-db-<UTC timestamp>.metadata
aerotrace-db-<UTC timestamp>.sha256
```

`.dump`는 compressed PostgreSQL custom-format archive다. `.metadata`는 source PostgreSQL/TimescaleDB version, archive size와 hash를 기록하며 credential이나 row payload를 포함하지 않는다. `.sha256`은 archive 전송·보관 중 bit corruption을 확인한다.

세 파일이 모두 존재해야 complete backup set이다. Archive에는 trace payload와 credential hash가 포함될 수 있으므로 세 파일 전체를 민감 운영 자료로 취급한다.

## 5. Production backup 생성 — operator action

다음 명령은 DB를 읽고 host에 민감한 backup 파일을 생성한다. DB row나 schema는 변경하지 않지만 저장 위치와 접근 권한을 운영자가 먼저 승인해야 한다. Codex는 production backup 위치를 임의로 정하거나 자동 실행하지 않는다.

현재 host에서 사용할 수 있는 local staging 예시:

```bash
install -d -m 0700 /home/huning/aerotrace-backups

cd /home/huning/aerotrace
bash scripts/database/backup-timescaledb.sh \
  --container aerotrace-timescaledb \
  --output-dir /home/huning/aerotrace-backups
```

성공 출력:

```text
backup_result=CREATED
archive_path=...
metadata_path=...
checksum_path=...
archive_size_bytes=...
postgres_major=15
timescaledb_version=2.28.3
```

`pg_dump`는 실행 중인 DB의 consistent snapshot을 만들 수 있지만 I/O와 CPU 부하는 발생한다. Production-sized 최초 실행은 저부하 시간에 수행하고 시작·종료 시각, archive size와 service health를 기록한다.

## 6. 생성 직후 검사

Checksum file은 archive basename을 사용하므로 backup directory에서 확인한다.

```bash
cd /home/huning/aerotrace-backups
sha256sum --check aerotrace-db-<UTC timestamp>.sha256
```

Archive TOC는 data를 복원하지 않고 parse할 수 있다.

```bash
docker exec -i aerotrace-timescaledb \
  pg_restore --list \
  < /home/huning/aerotrace-backups/aerotrace-db-<UTC timestamp>.dump \
  >/dev/null
```

두 명령이 성공해도 restore proof는 아니다. 별도 target 복원을 주기적으로 수행해야 한다.

## 7. 격리 acceptance

Repository acceptance는 production container, network와 volume을 사용하지 않는다. 고유 이름, `--network none`, tmpfs와 전용 label을 가진 source/target TimescaleDB container만 만들고 종료 시 제거한다.

```bash
cd /home/huning/aerotrace
bash scripts/database/test-backup-restore.sh
```

성공 기준:

```text
backup_restore_acceptance=PASS
source_target_summary_match=yes
existing_target_overwrite_refused=yes
production_target_container_refused=yes
invalid_archive_refused_before_target_creation=yes
version_mismatch_refused_before_target_creation=yes
metadata_credential_scan=PASS
```

Acceptance는 현재 migration 전체와 synthetic fixture를 적용하고 다음을 비교한다.

- PostgreSQL과 TimescaleDB version
- 필수 application table 4개
- `public.spans` hypertable
- Columnstore enabled
- Scheduled columnstore/retention policy 2개
- Tenant/project/API Key/span count
- Row 원문을 출력하지 않는 one-way application data fingerprint

## 8. 빈 target 복원

Restore script는 운영자가 준비한 **별도 TimescaleDB container** 안에 아직 존재하지 않는 database만 생성한다.

```bash
cd /home/huning/aerotrace
bash scripts/database/restore-timescaledb.sh \
  --container <isolated-target-container> \
  --target-database aerotrace_restored \
  --archive /secure/path/aerotrace-db-<UTC timestamp>.dump \
  --metadata /secure/path/aerotrace-db-<UTC timestamp>.metadata \
  --checksum /secure/path/aerotrace-db-<UTC timestamp>.sha256 \
  --confirm-create-empty-target
```

내부 순서:

```text
checksum과 archive TOC 확인
-> target DB가 없는지 확인
-> PostgreSQL major/TimescaleDB version exact match
-> template0 기반 빈 DB 생성
-> CREATE EXTENSION timescaledb
-> timescaledb_pre_restore()
-> single-process pg_restore --exit-on-error
-> timescaledb_post_restore()
-> ANALYZE
```

복원 후 검증:

```bash
bash scripts/database/verify-timescaledb-restore.sh \
  --container <isolated-target-container> \
  --database aerotrace_restored
```

Restore 도중 실패하면 partial target DB를 자동 삭제하지 않는다. 오류 evidence를 보존하고 `timescaledb_post_restore()` best-effort 결과와 target 상태를 조사한 뒤, 정확한 disposable target임을 운영자가 확인한 경우에만 별도로 제거한다.

## 9. 실제 장애 복구 흐름

```text
1. Ingest와 application write 중지
2. 장애 DB와 volume을 삭제하지 않고 evidence 보존
3. 가장 최근 complete backup set과 checksum 선택
4. Source와 같은 PostgreSQL major/TimescaleDB version의 새 instance 준비
5. 기존 DB를 덮어쓰지 않고 새 빈 DB에 복원
6. verify script와 Backend read-only query로 검증
7. Collector queue와 마지막 backup 시각을 이용해 예상 data gap 산정
8. Secret file/API Key 원문 가용성 확인
9. 승인 후 Backend connection을 restored DB로 전환
10. Health, ingest, trace query와 notification을 확인
11. Incident timeline, 실제 RPO와 RTO 기록
```

손상된 기존 DB 위에 `--clean` restore를 실행하지 않는다. 새 target에서 검증을 마친 뒤 connection을 전환해야 rollback과 forensic evidence가 남는다.

## 10. Off-host와 encryption

`/home/huning/aerotrace-backups`처럼 같은 서버 디스크에 둔 파일은 local restore staging일 뿐 host loss를 대비한 backup이 아니다. Usable disaster-recovery backup이 되려면 다음 조건을 추가로 만족해야 한다.

- 다른 physical device 또는 독립 remote account에 복사
- 전송 중 TLS 또는 동등한 보호
- 저장 시 encryption
- 최소 권한 account와 MFA
- Backup 삭제 권한을 production runtime과 분리
- 정기 checksum 확인과 restore rehearsal
- Trace retention·incident hold에 맞는 backup 만료

Off-host provider와 암호화 key custody는 아직 선택하지 않았다. 무료 여부만으로 결정하지 않고 deletion isolation, restore 속도, egress와 key 분실 위험을 함께 검토한다.

## 11. RPO와 RTO 상태

현재 committed RPO/RTO는 없다. Ephemeral fixture acceptance는 절차 정확성을 검증하지만 production data volume의 dump/restore 시간이나 off-host 전송 시간을 측정하지 않는다.

초기 목표를 채택하려면 다음 evidence가 필요하다.

```text
production-sized archive 생성 시간과 size
isolated target restore 시간
post-restore verification 시간
off-host upload/download 시간
backup 이후 Collector queue와 DB data gap
운영자 승인·connection cutover 시간
```

이 측정 후 backup 주기에서 RPO를, 새 instance 준비부터 검증 완료까지의 rehearsal에서 RTO를 정한다.

## 12. 알려진 한계와 재검토 조건

- 전체 DB logical dump는 현재 소규모 database에 적합하다.
- 100 GB 접근, restore window 초과 또는 point-in-time 요구가 생기면 physical base backup와 WAL archiving을 검토한다.
- PostgreSQL role/global privilege가 복잡해지면 별도 `pg_dumpall --globals-only` 정책이 필요하다.
- Cross-major PostgreSQL 또는 TimescaleDB upgrade는 이 runbook의 same-version restore와 분리한다.
- Collector queue는 DB backup 대상이 아니므로 pending telemetry를 별도로 다룬다.
- 실제 사용자 data를 받기 전에 production-sized restore와 off-host copy를 완료한다.

## 13. 공식 근거

- [TimescaleDB logical backup with pg_dump and pg_restore](https://docs.timescale.com/self-hosted/latest/backup-and-restore/logical-backup/)
- [TimescaleDB restore administration functions](https://docs.timescale.com/api/latest/administration/)
- [PostgreSQL 15 pg_dump](https://www.postgresql.org/docs/15/app-pgdump.html)
- [PostgreSQL pg_restore](https://www.postgresql.org/docs/current/app-pgrestore.html)
