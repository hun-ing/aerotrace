# AeroTrace Local Runtime Runbook

## 1. 실행 모드

AeroTrace는 개발 모드와 Docker 통합 모드를 지원한다.

### 개발 모드

TimescaleDB와 OpenTelemetry Collector를 Docker로 실행한다.

```powershell
docker compose `
  -f .\docker-compose.yaml `
  up -d
```

애플리케이션은 호스트에서 실행한다.

```text
Backend  → IntelliJ 또는 Gradle
Frontend → npm run dev
```

개발 모드에서 Collector는 다음 주소로 Backend에 연결한다.

```text
http://host.docker.internal:8080
```

### Docker 통합 모드

TimescaleDB, Backend, OpenTelemetry Collector, Frontend를 모두 Docker로 실행한다.

```powershell
.\scripts\runtime\aerotrace.ps1 Up
```

Docker Network 내부 연결 구조:

```text
Browser
→ Frontend :3000
→ Backend :8080
→ TimescaleDB :5432

OTLP Client
→ Collector :4317/4318
→ Backend :8080
→ TimescaleDB :5432
```

Container 간에는 Docker Compose 서비스 이름을 사용한다.

```text
frontend → backend:8080
collector → backend:8080
backend → timescaledb:5432
```

## 2. 필수 로컬 환경 파일

통합 실행 전에 다음 파일이 필요하다.

```text
.env
otel-collector.env
frontend.env
```

이 파일들은 Secret을 포함하므로 Git에 커밋하지 않는다.

저장소에는 실제 값이 없는 예시 파일만 저장한다.

```text
.env.example
otel-collector.env.example
frontend.env.example
```

## 3. 통합 실행 명령

### Compose 설정 검사

```powershell
.\scripts\runtime\aerotrace.ps1 Config
```

### 전체 서비스 실행

```powershell
.\scripts\runtime\aerotrace.ps1 Up
```

### 상태 확인

```powershell
.\scripts\runtime\aerotrace.ps1 Status
```

### 전체 로그 확인

```powershell
.\scripts\runtime\aerotrace.ps1 Logs
```

로그 출력을 종료할 때는 `Ctrl+C`를 사용한다. 로그 보기만 종료되며 Container는 계속 실행된다.

### 전체 서비스 재시작

```powershell
.\scripts\runtime\aerotrace.ps1 Restart
```

### Container와 Network 제거

```powershell
.\scripts\runtime\aerotrace.ps1 Down
```

`Down`은 Container와 Docker Network를 제거하지만 Named Volume은 유지한다.

## 4. 서비스 주소

```text
Dashboard         http://localhost:3000
Backend Health    http://localhost:8080/actuator/health
OTLP/gRPC         localhost:4317
OTLP/HTTP         http://localhost:4318
Collector Metrics http://localhost:8888/metrics
PostgreSQL        localhost:5432
```

## 5. 정상 상태

```text
aerotrace-timescaledb        healthy
aerotrace-backend            healthy
aerotrace-frontend           healthy
aerotrace-otel-collector     running
aerotrace-otel-storage-init  exited (0)
```

`otel-storage-init`은 Collector Persistent Queue 디렉터리의 권한을 설정하고 종료되는 일회성 서비스다. 따라서 `Exited (0)`이 정상이다.

## 6. 데이터 보존 원칙

TimescaleDB 데이터와 Collector Persistent Queue는 Docker Named Volume에 저장된다.

Container와 Network를 제거해도 Named Volume을 삭제하지 않으면 데이터는 유지된다.

다음 명령은 사용하지 않는다.

```powershell
docker compose down -v
```

`-v` 옵션은 TimescaleDB 데이터와 Collector Persistent Queue Volume을 삭제할 수 있다.

## 7. Container 재생성 전후 데이터 보존 확인

TimescaleDB Container에서 DB 사용자와 DB 이름을 조회한다.

```powershell
$dbUser = (
    docker exec `
        aerotrace-timescaledb `
        printenv `
        POSTGRES_USER |
    Out-String
).Trim()

$dbName = (
    docker exec `
        aerotrace-timescaledb `
        printenv `
        POSTGRES_DB |
    Out-String
).Trim()
```

재생성 전 Span 수를 조회한다.

```powershell
$beforeSpanCount = (
    docker exec `
        aerotrace-timescaledb `
        psql `
        -U $dbUser `
        -d $dbName `
        -t `
        -A `
        -c "SELECT COUNT(*) FROM public.spans;" |
    Out-String
).Trim()
```

Container와 Network를 제거한다.

```powershell
.\scripts\runtime\aerotrace.ps1 Down
```

Named Volume이 유지된 상태에서 전체 서비스를 다시 생성한다.

```powershell
.\scripts\runtime\aerotrace.ps1 Up
```

재생성 후 DB 환경변수를 다시 읽는다.

```powershell
$dbUser = (
    docker exec `
        aerotrace-timescaledb `
        printenv `
        POSTGRES_USER |
    Out-String
).Trim()

$dbName = (
    docker exec `
        aerotrace-timescaledb `
        printenv `
        POSTGRES_DB |
    Out-String
).Trim()
```

재생성 후 Span 수를 조회한다.

```powershell
$afterSpanCount = (
    docker exec `
        aerotrace-timescaledb `
        psql `
        -U $dbUser `
        -d $dbName `
        -t `
        -A `
        -c "SELECT COUNT(*) FROM public.spans;" |
    Out-String
).Trim()
```

결과를 비교한다.

```powershell
[PSCustomObject]@{
    Before = [Int64]$beforeSpanCount
    After = [Int64]$afterSpanCount
    Preserved = (
        [Int64]$afterSpanCount -ge
        [Int64]$beforeSpanCount
    )
} |
Format-List
```

검증 결과:

```text
Before    : 120107
After     : 120107
Preserved : True
```

검증 중 새 Span이 저장될 수 있으므로 성공 조건은 `After >= Before`다.

## 8. 기본 점검 명령

Backend Health:

```powershell
curl.exe `
  http://localhost:8080/actuator/health
```

예상:

```json
{"status":"UP"}
```

Frontend HTTP 상태:

```powershell
curl.exe `
  -s `
  -o NUL `
  -w "%{http_code}" `
  http://localhost:3000
```

예상:

```text
200
```

Collector Queue:

```powershell
curl.exe `
  http://localhost:8888/metrics |
Select-String `
  "otelcol_exporter_queue_size"
```

정상 상태에서는 AeroTrace exporter Queue가 `0`이어야 한다.

## 9. 장애 확인 순서

Dashboard 조회가 실패하면 다음 순서로 확인한다.

```text
1. Frontend Container 상태와 Health
2. Backend Container 상태와 Health
3. TimescaleDB Container 상태와 Health
4. Frontend 로그
5. Backend 로그
6. Collector exporter 오류
7. Collector persistent queue 크기
```

전체 로그:

```powershell
.\scripts\runtime\aerotrace.ps1 Logs
```

서비스별 로그:

```powershell
docker compose `
  -f .\docker-compose.yaml `
  -f .\docker-compose.app.yaml `
  --profile app `
  logs `
  --tail 200 `
  frontend `
  backend `
  otel-collector `
  timescaledb
```

## 10. 개발 모드 복귀

Docker 통합 모드를 종료한다.

```powershell
.\scripts\runtime\aerotrace.ps1 Down
```

인프라만 다시 실행한다.

```powershell
docker compose `
  -f .\docker-compose.yaml `
  up -d
```

이후 Backend는 IntelliJ에서 실행하고 Frontend는 다음 명령으로 실행한다.

```powershell
cd .\frontend

npm run dev
```
