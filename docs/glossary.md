# 용어집

다른 문서에서 반복되는 용어를 한곳에 정의한다.

| 용어 | 뜻 |
|---|---|
| **SUT** (System Under Test) | 테스트 대상 앱. 여기서는 분석할 기존 Java/Spring 애플리케이션. |
| **unit / integration / docker / e2e 테스트** | 테스트 분류 4종. 레이어·프로세스·docker 정의와 접미사/`@Tag` 규칙은 [05-testing](05-testing.md) 참고. |
| **도구 1 / graph-rag-builder** | SUT를 외부 프로세스로 띄워 호출해 보며 사실을 캡처하는 CLI. 산출물은 `graph.json`. |
| **도구 2 / test-generator** | `graph.json`과 요청 한 건으로 RestAssured 테스트를 합성하는 CLI. |
| **graph.json** | 도구 1이 캡처한 사실 모음(엔드포인트·분기·발행 SQL·외부 호출·DB 스키마). 두 도구를 잇는 유일한 인터페이스. |
| **endpointId** | 엔드포인트 식별자. HTTP 메서드 + 경로를 소문자로 바꾸고 영숫자가 아닌 구간을 `-`로 바꾼 값(예: `POST /api/orders` → `post-api-orders`). |
| **GenerationRequest** | 도구 2 입력 JSON. 어느 `endpointId`의 테스트를, 어떤 클래스/패키지 이름으로, 어떤 `authMode`로 만들지 지정. |
| **분기 탐색 (exploration)** | 도구 1이 입력을 바꿔 가며 SUT를 호출해 코드의 여러 분기를 실행해 보는 과정. |
| **InputOracle** | 분기를 여는 입력 후보를 찾는 교체 가능한 구성요소. 현재 두 구현을 합집합으로 쓴다. |
| **StaticLiteralOracle** | 소스의 리터럴 값을 Spoon으로 읽어 입력 후보로 삼는 오라클. |
| **ConcolicOracle** | ASM으로 바이트코드를 심볼릭 스캔하고 Z3로 풀어, 소스에 없는 값을 도출하는 오라클(예: `amount*3==21`이면 `7`, `code.length()==5`면 `"xxxxx"`). |
| **arm-level coverage** | 한 분기의 true/false 같은 각 갈래(arm) 단위로 측정한 커버리지. 요청 단위 JaCoCo 실행 데이터를 누적 병합해 얻는다. |
| **path fingerprint** | 한 요청이 실행한 분기 갈래들의 지문. 같은 엔드포인트라도 서로 다른 경로를 탄 입력을 별개 테스트로 구분하는 데 쓴다. |
| **sink capture** | 탐색 중 SUT가 바깥으로 낸 것(발행 SQL·외부 HTTP·WebSocket·Kafka)을 관측해 기록하는 단계. |
| **fixture** | 생성된 테스트가 실행 전에 DB에 넣는 사전 데이터(INSERT)와 끝나고 지우는 정리(DELETE) 코드. FK 순서를 지켜 합성한다. |
| **baggage isolation** | 병렬 테스트가 서로의 mock 응답을 침범하지 않도록, 추적 헤더(baggage)의 test-id로 요청과 스텁을 짝짓는 격리 방식. |
| **분석 환경 vs 실행 환경** | 도구 1이 사실을 *관측하려고* 띄우는 환경과, 생성된 테스트가 *실행되는* 환경은 별개다. 혼동하지 않는다([02-architecture](02-architecture.md)). |
| **결정적 합성** | 같은 입력이면 항상 같은 결과를 내는 생성. 두 도구 안에 LLM이 없어서 가능하다. |
