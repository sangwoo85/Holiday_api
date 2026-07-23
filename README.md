# korea-holiday-fetcher

대한민국 공휴일(국경일·대체공휴일·임시공휴일) 조회 클라이언트.
공공데이터포털 **[한국천문연구원_특일 정보](https://www.data.go.kr/data/15012690/openapi.do)** API(`getRestDeInfo`)를 사용한다.

- 토·일요일은 데이터에 없음 — **공휴일 날짜 목록만** 반환 (주말 제외 요구사항에 그대로 부합)
- `isHoliday=Y` 항목만 반영: 법정공휴일 + 국경일 중 공휴일 + **대체공휴일** + 임시공휴일·선거일
- 의존성: JDK 내장 `java.net.http.HttpClient` + `jackson-databind` (그 외 없음, Spring 미사용). **Java 17+**

## 0. 라이브러리로 사용하기

### 방법 A — 같은 PC에서 (로컬 설치)

이 저장소에서 한 번 설치:

```bash
./mvnw clean install
```

사용하는 프로젝트 `pom.xml`:

```xml
<dependency>
    <groupId>kr.holiday</groupId>
    <artifactId>korea-holiday-fetcher</artifactId>
    <version>0.1.0</version>
</dependency>
```

### 방법 B — 어디서든 (JitPack, GitHub 태그 기반)

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.sangwoo85</groupId>
    <artifactId>Holiday_api</artifactId>
    <version>v0.1.0</version>
</dependency>
```

### 코드 사용 예

```java
import kr.holiday.Holiday;
import kr.holiday.HolidayApiClient;

// 키는 코드에 하드코딩하지 말고 환경변수/설정에서 주입
HolidayApiClient client = new HolidayApiClient(System.getenv("HOLIDAY_API_SERVICE_KEY"));

List<Holiday> holidays = client.fetchHolidays(2027);
holidays.forEach(h -> System.out.println(h.date() + " " + h.name()
        + (h.substitute() ? " (대체공휴일)" : "")));
```

> 테스트/미러 서버가 필요하면 `new HolidayApiClient(key, baseUrl)` 로 엔드포인트 override 가능.

## 1. 발급키 준비

1. [공공데이터포털](https://www.data.go.kr) 회원가입 → [특일 정보 페이지](https://www.data.go.kr/data/15012690/openapi.do)에서 **활용신청** (자동승인)
2. 마이페이지에서 **일반 인증키** 확인
3. ⚠️ 신청 직후에는 게이트웨이 반영 전이라 `401 Unauthorized` 가 뜰 수 있음 — **최대 1시간 대기**

## 2. 테스트 실행

```bash
# 오프라인 파싱 테스트만 (키 불필요, 실 API 테스트는 skip)
./mvnw test

# 실 API 포함 전체 테스트
HOLIDAY_API_SERVICE_KEY='발급키' ./mvnw test
```

IDE 에서 돌리려면 `HolidayApiLiveTest` 상단의 `SERVICE_KEY` 상수에 키를 붙여넣고 실행
(⚠️ 키를 넣은 상태로 커밋 금지 — 확인 후 placeholder 복원).

## 3. CLI 실행

```bash
HOLIDAY_API_SERVICE_KEY='발급키' ./mvnw -q compile exec:java -Dexec.args="2027"
```

출력 예:

```
=== 2027년 대한민국 공휴일 (24일) ===
2027-01-01 (금) 1월1일
2027-02-06 (토) 설날
...
2027-02-09 (화) 대체공휴일(설날)  ★
(★ 대체공휴일 7일 포함)
```

## 4. 구조

```
src/main/java/kr/holiday/
├── Holiday.java           공휴일 1건 (record: date, name, substitute())
├── HolidayApiClient.java  API 호출 + 응답 파싱 (parse 는 static — 오프라인 테스트 가능)
└── Main.java              CLI 진입점
src/test/java/kr/holiday/
├── HolidayParseTest.java  파싱 단위 테스트 (항상 실행: 배열/단건 객체/빈 items/isHoliday=N/오류코드)
└── HolidayApiLiveTest.java 실 API 스모크 (키 있을 때만 실행)
```

## 5. API 응답 스펙 메모 (활용가이드 v1.4)

- 요청: `?solYear=YYYY&_type=json&numOfRows=100&ServiceKey=발급키`
- 성공: `response.header.resultCode == "00"`
- 항목: `response.body.items.item[]` — `locdate`(yyyyMMdd), `dateName`, `isHoliday`(Y/N)
- data.go.kr JSON 특성: 결과 0건이면 `items` 가 빈 문자열 `""`, 1건이면 `item` 이 배열 아닌 **단일 객체**
- 데이터 갱신: 연 단위 일괄 + 임시공휴일은 발표 후 반영 → **주기적 재조회 권장**
- 트래픽: 개발계정 일 10,000건
