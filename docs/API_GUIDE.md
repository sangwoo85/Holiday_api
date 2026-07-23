# korea-holiday-fetcher API 사용 가이드

대한민국 공휴일(법정공휴일·국경일 중 공휴일·**대체공휴일**·임시공휴일·선거일)을 조회하는 Java 라이브러리 사용 가이드입니다.
데이터 소스는 공공데이터포털 **[한국천문연구원_특일 정보](https://www.data.go.kr/data/15012690/openapi.do)** API(`getRestDeInfo`)입니다.

## 1. 요구사항

| 항목 | 값 |
|---|---|
| Java | **8 이상** (JDK 8/11/17/21 모두 동작) |
| 외부 의존성 | `jackson-databind` 하나 (HTTP 는 JDK 내장 `HttpURLConnection`) |
| 프레임워크 | 불필요 (Spring 없이 동작, Spring 프로젝트에서도 그대로 사용 가능) |
| 네트워크 | `apis.data.go.kr` HTTPS 아웃바운드 |

## 2. 설치

### 방법 A — JitPack (권장, 어디서든)

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
    <version>v0.2.0</version>
</dependency>
```

### 방법 B — 로컬 설치 (같은 PC)

```bash
git clone https://github.com/sangwoo85/Holiday_api.git
cd Holiday_api && ./mvnw clean install
```

```xml
<dependency>
    <groupId>kr.holiday</groupId>
    <artifactId>korea-holiday-fetcher</artifactId>
    <version>0.2.0</version>
</dependency>
```

## 3. 발급키 준비

1. [공공데이터포털](https://www.data.go.kr) 회원가입
2. [특일 정보 페이지](https://www.data.go.kr/data/15012690/openapi.do) → **활용신청** (자동승인)
3. 마이페이지 → **일반 인증키** 확인
4. ⚠️ 신청 직후에는 게이트웨이 반영 전이라 `401 Unauthorized` 가 발생할 수 있습니다 — **최대 1시간 대기**

> **키 보안**: 키를 소스코드·pom.xml·Git 에 넣지 마세요. 환경변수/외부 설정으로 주입하고,
> 유출 시 포털에서 재발급하세요.

## 4. 빠른 시작

```java
import kr.holiday.Holiday;
import kr.holiday.HolidayApiClient;

import java.util.List;

// 키는 런타임 주입 (환경변수/설정파일/Vault 등)
HolidayApiClient client = new HolidayApiClient(System.getenv("HOLIDAY_API_SERVICE_KEY"));

List<Holiday> holidays = client.fetchHolidays(2027);
for (Holiday h : holidays) {
    System.out.println(h.getDate() + " " + h.getName() + (h.isSubstitute() ? " (대체공휴일)" : ""));
}
```

## 5. API 레퍼런스

### `HolidayApiClient`

| 시그니처 | 설명 |
|---|---|
| `HolidayApiClient(String serviceKey)` | 발급키로 생성. 디코딩(원본)·인코딩 키 모두 허용('%' 포함 여부 자동 판별) |
| `HolidayApiClient(String serviceKey, String baseUrl)` | 엔드포인트 override (테스트/미러 서버) |
| `List<Holiday> fetchHolidays(int year)` | 해당 연도 공휴일 전체, **날짜 오름차순**. 토·일요일은 포함되지 않음(공휴일 날짜만) |

- 스레드 세이프: 인스턴스 하나를 애플리케이션 전역에서 재사용해도 됩니다.
- 타임아웃: 연결 3초 / 읽기 5초.

### `Holiday` (불변 값 객체, `Serializable`)

| 접근자 (record 스타일) | 접근자 (JavaBean 스타일) | 타입 | 설명 |
|---|---|---|---|
| `date()` | `getDate()` | `LocalDate` | 공휴일 날짜 |
| `name()` | `getName()` | `String` | 명칭 (예: `설날`, `대체공휴일(설날)`) |
| `substitute()` | `isSubstitute()` | `boolean` | 대체공휴일 여부 |

`equals`/`hashCode` 는 (date, name) 기준 — 중복 판정·컬렉션 사용 가능.

### 예외

| 예외 | 상황 | 대응 |
|---|---|---|
| `IllegalArgumentException` | 생성 시 키/URL 누락 | 설정 확인 |
| `IllegalStateException` | HTTP 비정상(401 등) 또는 API `resultCode` ≠ 00 | 키 승인 여부·트래픽 확인. **빈 목록과 구분됨** |
| `IOException` | 네트워크 오류/타임아웃 | 재시도 (호출부 정책) |

> 라이브러리는 의도적으로 재시도를 내장하지 않습니다. 배치/스케줄러에서 호출부 정책으로 재시도하세요.

## 6. DB 저장 패턴

조회 결과를 DB에 저장하는 권장 방법입니다.

### 테이블 (MySQL 예시)

```sql
CREATE TABLE holiday (
    holiday_date DATE         NOT NULL,
    name         VARCHAR(100) NOT NULL,
    substitute   TINYINT(1)   NOT NULL DEFAULT 0,
    fetched_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (holiday_date, name)          -- 같은 날짜에 명칭 2건 가능(예: 겹친 특일)
);
```

> 날짜만 필요하면 `PRIMARY KEY (holiday_date)` 로 단순화하고 저장 전 날짜 기준 중복 제거하세요.

### 저장 (JDBC upsert 예시)

```java
String sql = "INSERT INTO holiday (holiday_date, name, substitute) VALUES (?, ?, ?) "
        + "ON DUPLICATE KEY UPDATE substitute = VALUES(substitute)";
try (PreparedStatement ps = conn.prepareStatement(sql)) {
    for (Holiday h : client.fetchHolidays(year)) {
        ps.setDate(1, java.sql.Date.valueOf(h.getDate()));   // JDK 8 호환 변환
        ps.setString(2, h.getName());
        ps.setBoolean(3, h.isSubstitute());
        ps.addBatch();
    }
    ps.executeBatch();
}
```

### 갱신 전략 (중요)

- **연 1회 일괄 + 주기 재동기화(월 1회 권장)**: 임시공휴일·선거일은 정부 발표 *후에* API에 추가되므로
  연초 1회 저장만 하면 놓칠 수 있습니다. upsert 방식이면 재실행이 안전합니다.
- **다음 연도 데이터**: 보통 전년도 말에 확정 반영됩니다. 미반영 연도는 빈 목록이 옵니다(오류 아님).
- 삭제 동기화까지 하려면 `DELETE WHERE holiday_date BETWEEN 연초 AND 연말` 후 일괄 INSERT (트랜잭션으로).

## 7. 제한·주의사항

- 트래픽: 개발계정 **일 10,000건** — 연 단위 조회 후 DB 캐싱이면 충분합니다.
- 이 API의 공휴일은 「관공서의 공휴일에 관한 규정」 기준입니다. **증권시장 휴장일(연말 휴장 등)·회사 휴무일과는 다를 수 있습니다.**
- `401 Unauthorized`: 활용신청 미완료/미반영이 대부분 — 3장 참고.
- 응답이 정상(200, resultCode 00)인데 0건: 해당 연도 데이터 미공개 시점입니다.

## 8. 문제 해결 (FAQ)

| 증상 | 원인/해결 |
|---|---|
| `401 Unauthorized` | 해당 API 활용신청 안 됨 / 승인 반영 대기(최대 1시간) / 키 오탈자 |
| `resultCode 30` | 등록되지 않은 키 |
| `resultCode 22` | 일 트래픽 초과 → 다음날 또는 운영계정 신청 |
| 빈 목록 | 해당 연도 데이터 미공개 (예외 아님 — 정상 흐름으로 처리) |
| 타임아웃 | 포털 점검 시간대 확인 후 재시도 |
