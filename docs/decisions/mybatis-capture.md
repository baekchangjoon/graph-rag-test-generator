# 의사결정: MyBatis SQL 캡처는 로그 기반 (Interceptor/ProxyDataSource 보류)

날짜: 2026-06-10 / 단계: 1.3, 1.4

## 선택지 (docs/11의 비교)

| 방식 | 전제 |
|---|---|
| MyBatis Interceptor | SUT in-process 부착 (`ConfigurationCustomizer` 빈 주입) |
| ProxyDataSource wrap | SUT in-process 부착 (`BeanPostProcessor` 주입) |
| **MyBatis 로거 파싱 (채택)** | env 주입만 (`logging.level.<namespace>=TRACE`) |

## 결정과 근거

Phase 1 분석 환경은 "운영 jar 외부 프로세스 + env 주입"
(`builder-analysis-environment.md`)이므로 in-process 부착 방식은 결합 지점이
없다. MyBatis의 `==>  Preparing:` 로그는 **동적 SQL 평가가 끝난 최종 형태**를
출력하므로 docs/03이 요구하는 "동적 SQL의 실제 형태 캡처"를 충족한다.
`==> Parameters:`의 `값(타입)` 토큰으로 바인딩도 추출된다.

구현: 인덱서가 찾은 mapper namespace를 SutProcess가 `SPRING_APPLICATION_JSON`에
TRACE로 동적 주입 → `SqlLogParser`가 Hibernate/MyBatis 형식을 한 번의 스캔으로
발행 순서 보존 파싱.

## 알려진 한계

- Parameters 값에 `", "`가 포함되면 토큰 분리가 부정확 (best-effort, 파서에 기록)
- provenance(어느 statement가 발행했는지)는 CapturedSql에 저장하지 않음 —
  mapper 사실(MapperStatement)은 별도 노드로 존재. 필요 시 로그의 로거 이름으로
  추가 가능
- batch INSERT 미지원 (docs/11과 동일한 Phase 1+ TODO)

## 복귀 조건

향후 in-process 분석 모드(예: 같은 팀이 소유한 SUT에 testImplementation 부착)를
도입하면 docs/11의 ProxyDataSource 패턴이 1순위.
