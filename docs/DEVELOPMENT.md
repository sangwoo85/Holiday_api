# korea-holiday-fetcher 개발 문서

라이브러리를 유지보수·확장하는 개발자를 위한 문서입니다. (사용법은 [API_GUIDE.md](API_GUIDE.md))

## 1. 설계 원칙

1. **JDK 8 호환** — 범용 라이브러리 목표. 9+ API 사용 금지(아래 3장). `maven.compiler.release=8` 이
   컴파일 단계에서 자동 차단한다.
2. **의존성 최소** — jackson-databind 하나. HTTP 는 JDK 내장 `HttpURLConnection`.
   (소비 프로젝트와의 의존성 충돌 표면적 최소화)
3. **키는 런타임 주입** — 생성자 파라미터로만 받는다. 키를 저장·로깅하지 않고,
   코드/설정/Git 에 키를 넣는 API 를 만들지 않는다.
4. **실패는 예외로** — 조회 실패를 빈 목록으로 뭉개지 않는다(`IllegalStateException`/`IOException`).
   "정상 0건"(연도 데이터 미공개)과 "실패"를 소비자가 구분할 수 있어야 한다.
5. **파싱 분리** — `parse(String)` 은 static package-private. 네트워크 없이 단위 테스트 가능.

## 2. 구조

```
kr.holiday
├── Holiday          불변 값 객체 (Serializable, equals/hashCode = date+name)
│                    접근자 2벌: record 스타일(date()) + JavaBean(getDate()) — ORM/직렬화 편의
├── HolidayApiClient 조회 클라이언트 (스레드 세이프, 상태 불변)
│   ├── fetchHolidays(int year)      HTTP GET + parse
│   ├── parse(String) [static]       JSON → List<Holiday>  ← 단위 테스트 대상
│   └── encodedKey()                 '%' 포함 여부로 이중 인코딩 방지
└── Main             CLI (라이브러리 사용에는 불필요, exec-maven-plugin 으로 실행)
```

## 3. JDK 8 제약 — 사용 금지 API 목록

`release=8` 로 컴파일이 막아주지만, 리뷰 시 아래를 특히 주의:

| 금지 (9+) | 대체 (8) |
|---|---|
| `java.net.http.HttpClient` (11+) | `HttpURLConnection` |
| `record` (16+) | final 클래스 + final 필드 + 접근자 |
| 텍스트 블록 `"""` (15+) | 문자열 연결 |
| `String.isBlank()` (11+) | `s.trim().isEmpty()` |
| `List.of() / Set.of() / Map.of()` (9+) | `Arrays.asList()`, `new ArrayList<>()` |
| `URLEncoder.encode(String, Charset)` (10+) | `URLEncoder.encode(String, "UTF-8")` |
| `InputStream.readAllBytes()` (9+) | 버퍼 루프로 직접 읽기 |
| `var` (10+) | 명시적 타입 |

- 테스트 코드도 동일하게 8 호환 유지(전체 `release=8` 적용).
- jackson-databind 2.17.x / JUnit Jupiter 5.10.x 는 Java 8 지원 최신선 — 업그레이드 시 8 지원 여부 확인.

## 4. 특일 API 스펙 요약 (활용가이드 v1.4)

- Base: `https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService`
- 오퍼레이션: `getRestDeInfo` (공휴일만). `getHoliDeInfo` 는 비공휴일 국경일(제헌절 등)까지 포함하므로 사용하지 않음.
- 요청: `solYear`(필수), `solMonth`(선택), `_type=json`, `numOfRows`(연간 전체 수신 위해 100), `ServiceKey`
- 응답: `response.header.resultCode`(00=성공) / `response.body.items.item[]` → `locdate`(yyyyMMdd, 숫자), `dateName`, `isHoliday`(Y/N)

### data.go.kr JSON 특성 (파서가 반드시 대응해야 하는 것)

1. 결과 0건 → `items` 가 객체가 아니라 **빈 문자열 `""`**
2. 결과 1건 → `item` 이 배열이 아니라 **단일 객체**
3. `locdate` 는 숫자 타입으로 옴 → `asText()` 후 `BASIC_ISO_DATE` 파싱
4. 인증 실패는 HTTP 401(신형 게이트웨이) 또는 `resultCode 30`(XML) 두 형태 존재
5. 키가 URL 인코딩된 형태로 발급되기도 함 → '%' 포함 시 재인코딩 금지(이중 인코딩 = 인증 실패)

## 5. 테스트 전략

| 테스트 | 실행 조건 | 검증 |
|---|---|---|
| `HolidayParseTest` | 항상 (오프라인) | 배열/단건 객체/빈 items/isHoliday=N 필터/오류 resultCode/값 객체 동등성 |
| `HolidayApiLiveTest` | 키 있을 때만 (없으면 skip) | 실 API 200 + 연간 10건 이상 + 신정 + 대체공휴일(2027-02-09) |

- 라이브 테스트 키 주입: 환경변수 `HOLIDAY_API_SERVICE_KEY` (권장) 또는 상수에 임시 붙여넣기.
  **상수에 키를 넣은 상태로 커밋 금지** — 커밋 전 placeholder(`여기에_...`) 복원.
- `YEAR` 상수 변경 시 연도 고정 단언(대체공휴일 날짜)도 함께 조정.

```bash
./mvnw test                                        # 오프라인만
HOLIDAY_API_SERVICE_KEY='발급키' ./mvnw test         # 라이브 포함
```

## 6. 빌드·릴리스 절차

```bash
./mvnw clean install        # 로컬 배포(~/.m2)
```

릴리스(JitPack 반영):

1. `pom.xml` `<version>` 올리기 (예: 0.3.0)
2. 커밋 → `git tag v0.3.0` → `git push origin main v0.3.0`
3. JitPack 은 태그 요청 시점에 자동 빌드. 상태 확인:
   `https://jitpack.io/api/builds/com.github.sangwoo85/Holiday_api/v0.3.0`
4. `jitpack.yml`(openjdk17) 유지 — JitPack 기본 JDK 8 이미지에는 최신 Maven 플러그인이 안 돌아
   빌드 JDK 는 17 을 쓰되, `release=8` 이 산출물의 JDK 8 호환을 보장한다.

## 7. 버전 이력

| 버전 | 내용 |
|---|---|
| v0.1.0 | 최초 릴리스 (Java 17 타깃, java.net.http + record) |
| v0.2.0 | **JDK 8 호환 전환**(HttpURLConnection, record→클래스), JavaBean 접근자 추가(DB/ORM 편의), `Serializable`, 문서(가이드/개발) 추가. `fetchHolidays` throws 에서 `InterruptedException` 제거 |

## 8. 하지 말 것

- 키를 받는 static/설정파일 로딩 API 추가 금지 — 생성자 주입 유지
- 응답 스펙 필드명을 임의 확장 해석 금지 — 활용가이드 문서 기준으로만
- 실패를 빈 목록으로 반환하도록 변경 금지 (소비자의 "미공개 연도" 판정 로직이 깨짐)
- `jitpack.yml` 삭제 금지
