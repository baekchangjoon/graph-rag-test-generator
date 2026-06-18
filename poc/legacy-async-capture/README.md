# PoC: 레거시 비동기 SQL 캡처 가능성 진단

**대상:** Java 8 + Spring Cloud Sleuth(Brave, B3) + Eventuate/Tram(Waffle) 기반 레거시 MSA.
A → B → C 에서 **B → C 가 메시지 발행/구독**이고, **C 컨슈머가 실행하는 SQL** 을 캡처하려는 상황.

OTEL agent 는 (1) Sleuth 와 부팅 충돌(`brave.Tracing` 빈 의존), (2) 컨텍스트가 Tram 메시지 경계를
못 넘어 비동기 SQL 누락 — 두 이유로 부적합. 대신 **이미 메시징 너머로 전파되는 Sleuth(Brave)
trace-id 를 로그 상관 키로** 쓰는 방안을 검증한다.

이 PoC 는 **결론을 내리는 게 아니라, 설계 판단에 필요한 사실을 최대한 수집**한다. 실패해도 정보가 남는다.

## 검증할 3가지 질문

1. **전파** — A 에 주입한 B3 trace-id 가 **C 로그까지** 나타나는가?
2. **MDC 상관** — C 의 SQL/bind 로그 라인에 그 trace-id 가 **박히는가**, 박힌다면 **MDC 키 이름/형식**은?
3. **파싱 가능성** — bind 로그가 **Hibernate 5(BasicBinder) / 6(orm.jdbc.bind) / MyBatis** 중 무엇이고,
   **인코딩**으로 한글 바인드가 깨지는가, 애초에 bind 로그가 **나오긴** 하는가?

## 절차 (대상이 있는 PC에서)

1. **로깅 주입** — `logging-injection.snippet.yml` 의 environment 를 C(권장: A·B 도)에 병합 후 재기동.
   - Hibernate 5/6 bind 로거를 **둘 다** 켠다 (버전 모름).
   - `logging.pattern.level: "%5p [%X]"` 로 **전체 MDC 덤프** → trace-id 키 정체 노출.
   - `-Dfile.encoding=UTF-8` 로 stdout UTF-8 통일.
   - ⚠️ 기존 `SPRING_APPLICATION_JSON` / `JAVA_TOOL_OPTIONS` 가 있으면 **덮지 말고 병합**.

2. **요청 주입** — A 진입점에 알려진 B3 trace-id 로 요청:
   ```bash
   ./send-request.sh http://<A-host>:<port>/<엔드포인트> POST '{"...":"..."}' application/json
   ```
   출력 마지막 줄의 `TRACE_ID=...` 를 복사.

3. **로그 분석** — C(필요시 A·B 포함) 로그를 수집해 분석. trace-id 와 **probe 마커**(요청에 심은
   유니크 값)를 둘 다 넘기면 두 상관 방식을 나란히 비교한다:
   ```bash
   docker compose logs --no-color <C서비스> \
     | ./analyze-logs.sh --trace-id <TRACE_ID> --probe <요청에-심은-유니크값> -
   # 또는 여러 서비스 로그 파일을 직접:
   ./analyze-logs.sh --trace-id <TRACE_ID> --probe <값> a.log b.log c.log
   ```

4. **보고** — `analyze-logs.sh` 출력의 **6개 섹션 전체**를 복사해 전달. 이걸로 설계를 확정한다.

## 결과 해석

| 관찰 | 의미 | 다음 |
|---|---|---|
| 전파 O, **trace-id 상관 > 0** | trace-id 가 C 의 SQL/bind 라인 MDC 까지 박힘 | **trace-id 로그상관 성립** → SqlLogParser 에 trace-id 필터 + H5 패턴 |
| 전파 O, **trace-id 상관 = 0** | trace 는 전파되나 **SQL 스레드 MDC 동기화 안 됨** | trace-id 경로 막힘 → **probe 상관**으로 전환 검토 (아래) |
| **probe 상관 > 0** | 요청에 심은 마커가 C 의 SQL bind 까지 도달 | **payload 상관 성립**(SUT 무수정) → entry SQL 한정 캡처 |
| 전파 X | trace-id 가 C 에 도달 못 함 | 로그패턴 주입 실패거나 Tram 전파 안 됨 — A·B 로그로 단절점 추적 |
| 섹션3 H5 > 0 | Hibernate 5 형식 (로거명 축약 가능: `o.h...BasicBinder`) | 파서에 BasicBinder(축약 포함) 패턴 필요 |
| 섹션3 전부 0 | bind 로그 자체가 안 나옴 | 로그레벨 주입 누락 또는 ORM 이 JPA 아님 |
| 섹션5 모지바케 | stdout 비-UTF8 | `-Dfile.encoding=UTF-8` 적용 확인 (DB collation 과는 별개) |

### PoC 실측 결과 (2026-06-18, 다른 PC 레거시)

2차 정밀 측정(축약 로거 대응 + `--probe`):
- **전파 = 0** — trace-id 가 C 로그에 **텍스트로 전혀 안 나타남** → **trace-id/Sleuth 상관 확정 사망.**
- **bind = Hibernate 5 (85줄)**, H6/MyBatis 0. bind 로깅 + 축약 로거 매칭 정상.
- **trace-id 상관 0 / probe 상관 1줄** — payload 마커는 C 의 SQL 까지 도달(앵커 가능).
- 로그 인코딩 UTF-8 정상(DB collation euc-kr 은 별개).

→ (이 시점 판단) 메커니즘에서 Sleuth 가 빠짐. probe/window 방향으로 기울었음.

**미해결 핵심: bind 85줄 vs probe 1줄이 (a)정상 footprint 인가 (b)오염인가.**

### ⚠️ 번복 (이후 확인): trace-id 경로가 다시 살아남

위 "전파 = 0"의 원인은 전파 실패가 **아니라** 레거시의 **커스텀 logback 이 trace-id 를 출력하지 않은** 것이었다.
SUT 가 `logback-trace.xml` 의 appender 패턴에 `%X{traceId}` 를 넣자 **모든 SQL/bind(binding parameter) 라인에
traceId 가 출력**됨이 확인됐다. 즉 Brave 는 Tram 너머 C 컨슈머 스레드 MDC 까지 trace context 를 이미
동기화하고 있었고, 단지 **로그 패턴이 안 찍었을 뿐**이다(우리가 주입한 `logging.pattern.level` 은 커스텀
logback 이 무시).

→ 따라서 채택 방향은 **trace-id 로그 상관**으로 전환:
요청별 고유 B3 traceId 주입 → 모든 서비스 로그 수집 → 그 traceId 가 박힌 라인만 필터 → H5 파싱.
인프라 폴링 SQL 은 요청 traceId 가 없어 자동 배제됨(85 vs 노이즈 문제도 traceId 필터로 해소). 설계는
[docs/superpowers/specs/2026-06-18-traceid-log-sql-capture-design.md](../../docs/superpowers/specs/2026-06-18-traceid-log-sql-capture-design.md) 참조.

**남은 전제(미확정 R1)**: 주입한 traceId 가 **동일 값으로** Tram 너머 C 까지 전파되는지 — 라이브 샘플(Spec 1)로
실증 예정. 단, `logback-trace.xml`(traceId 패턴)은 SUT 제공자가 제공해야 함.

### 깨끗한 단일요청 윈도우 캡처 (a/b 판별)

`docker compose logs`(전체 히스토리) 대신 **요청 1건만의 윈도우**를 떠야 한다:
```bash
# 1) C 로그의 현재 끝 라인 수 기록 (오프셋 마커)
docker compose logs --no-color <C서비스> | wc -l        # => OFFSET

# 2) 요청 1건 주입
./send-request.sh http://<A>:<port>/<엔드포인트> POST '{"...":"..."}' application/json
sleep 5   # 비동기 C 처리 + 로그 flush 대기

# 3) OFFSET 이후 새 라인만 분석 (probe 컨텍스트 포함)
docker compose logs --no-color <C서비스> | tail -n +$((OFFSET+1)) \
  | ./analyze-logs.sh --probe <TRACE_ID 출력의 probe 값> -
```
판별: 윈도우 내 H5 줄 수와 probe 컨텍스트(±8라인)가 **같은 thread 의 SQL+bind 묶음**이면 (a);
무관 SQL 이 섞여 있으면 (b) → thread 격리나 추가 앵커 필요.

## 이 세션/CI 에서의 자가검증

실제 SUT 없이 분석기 동작을 보장:
```bash
./selftest.sh   # 합성 H5/H6 픽스처로 PASS 확인
```

## 파일

- `analyze-logs.sh` — 로그 분석기(핵심). 6개 섹션 리포트.
- `send-request.sh` — B3 trace-id 주입 요청.
- `logging-injection.snippet.yml` — 로그레벨/패턴/인코딩 주입 조각.
- `fixtures/` — 합성 H5/H6 로그(자가검증용).
- `selftest.sh` — 분석기 자가검증.

> 이 PoC 는 진단 전용이며 graph-rag 도구 본체와 분리돼 있다(빌드/배선 불필요). 결과에 따라
> `superpowers:brainstorming` → 계획 → 설계리뷰를 거쳐 attach-legacy 캡처 경로를 정식 구현한다.
