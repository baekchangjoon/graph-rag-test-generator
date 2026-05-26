# Examples

## spec/ — `--spec` 모드 입력 예시

| 파일 | 내용 |
|---|---|
| [minimal.json](spec/minimal.json) | 단일 path, 캡처 없음 |
| [multi-path-with-http.json](spec/multi-path-with-http.json) | 3 paths (201/400/404), SQL + HTTP 캡처 포함 |

사용:
```bash
./gradlew :test-generator:installDist
./test-generator/build/install/test-generator/bin/test-generator \
    --spec examples/spec/multi-path-with-http.json \
    --out /tmp/generated
```

## archive/ — `--archive` 모드 입력 예시

graph-rag-builder가 생성하는 archive 디렉터리 레이아웃과 동일:
- `endpoints.json` — 모든 endpoint
- `paths.json` — 탐색된 path들 (endpoint_id로 연결)
- `captured_sql.json` — 각 path의 SQL 캡처
- `captured_http.json` — 각 path의 외부 HTTP 캡처

사용:
```bash
./test-generator/build/install/test-generator/bin/test-generator \
    --archive examples/archive \
    --endpoint "POST:/api/orders" \
    --package com.example.tests \
    --out /tmp/generated
```

## env/ — 테스트 런타임 환경변수

[test-runtime.env](env/test-runtime.env) — 생성된 테스트가 docker-compose 환경을 가리키도록 하는 모든 환경변수의 default + 주석.

```bash
export $(grep -v '^#' examples/env/test-runtime.env | xargs)
# 그 다음 생성된 테스트 실행 (./gradlew test 등)
```
