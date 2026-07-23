# 공휴일 데이터 DB 저장 설계 가이드

`korea-holiday-fetcher`로 가져온 공휴일 데이터를 저장할 때의 **DB 선택 기준**과 **권장 테이블 구조**입니다.
(적재 코드는 [API_GUIDE.md 6장](API_GUIDE.md) 참고)

## 1. 데이터 특성 (설계 근거)

설계 판단은 전부 아래 특성에서 나옵니다:

| 특성 | 값 | 설계에 주는 영향 |
|---|---|---|
| 규모 | 연간 15~25건, **10년치도 ~250행** | 어떤 DB든 성능 문제 없음 — 인프라 단순성이 우선 |
| 읽기/쓰기 비율 | 읽기 압도적(영업일 판정 등), 쓰기는 연 수회 | 읽기 캐시 친화적, 잠금 고민 불필요 |
| 원본 | 특일 API (DB는 **캐시/스냅샷** 성격) | 날려도 재적재 가능 — 백업 부담 낮음 |
| 갱신 | 임시공휴일이 연중 추가될 수 있음 | **upsert 재동기화** 전제로 설계 |
| 특이점 | **같은 날짜에 공휴일 2건 가능** (예: 2025-05-05 어린이날+부처님오신날) | PK 설계에 반영 필수 |

## 2. DB 선택 추천 (상황별)

### 결론 요약

> **이미 운영 중인 RDB가 있으면 그 안에 테이블 1개** — 이 데이터를 위해 새 DB를 세우지 마세요.
> 없으면(독립 도구/배치) **임베디드 DB(H2/SQLite)**, 초고빈도 조회만 **Redis 캐시 병행**.

| 상황 | 추천 | 이유 |
|---|---|---|
| 기존 서비스(MySQL/PostgreSQL/Oracle 등)에 기능 추가 | ✅ **기존 RDB에 테이블 1개** (1순위) | 백업·모니터링·권한 인프라 재사용. 250행짜리에 신규 인프라는 낭비 |
| 독립 소형 도구·데스크톱·배치 전용 | ✅ **H2(파일 모드)** 또는 SQLite | 서버 설치 불필요, 파일 하나. H2 는 Java/JDK 8 호환이라 이 라이브러리와 궁합 좋음 |
| 조회가 초당 수천 건 이상(대규모 트래픽) | ✅ RDB(원본) + **Redis 캐시** | `SISMEMBER` O(1) 판정. 단, 원본은 RDB 유지(6장) |
| 여러 서비스가 공유하는 사내 공통 데이터 | ✅ 공통 RDB 스키마 또는 내부 API 화 | 서비스마다 따로 적재하면 갱신 시점 불일치 발생 |
| MongoDB 등 도큐먼트 NoSQL | ❌ 비추천 | 고정 스키마·소규모·관계 조회 — 도큐먼트 모델의 이점이 전혀 없음 |
| 매 요청 API 직접 호출(DB 없이) | ❌ 금지 수준 | 일 10,000건 트래픽 제한 + 외부 의존으로 가용성 하락. 저장 결정은 옳음 |

> 참고: 데이터가 워낙 작아 **애플리케이션 메모리 캐시(연도별 `Map<Integer, Set<LocalDate>>`)만으로 충분한 경우도 많습니다.**
> 그래도 DB 저장을 권하는 이유: 재기동 시 API 재호출 불필요, 여러 인스턴스 간 일관성, 수동 보정(회사 휴무일 추가) 가능.

## 3. 권장 테이블 구조

### 3.1 핵심 설계 결정 3가지

1. **PK = (holiday_date, name) 복합키** — 같은 날짜에 공휴일이 2건 올 수 있음(1장).
   날짜 단독 PK 로 덮어쓰면 한 건이 유실됩니다. *날짜만 필요한 서비스라면* 날짜 단독 PK 로 단순화하되
   적재 전 날짜 기준 중복 제거하세요.
2. **`source` 컬럼으로 API 데이터와 수동 데이터 분리** — 회사 창립기념일·거래소 휴장일 등
   API 에 없는 휴일을 같은 테이블에 넣을 때 재동기화가 수동 행을 지우지 않도록 구분합니다.
3. **연도 컬럼 불필요** — `holiday_date BETWEEN '2027-01-01' AND '2027-12-31'` 범위 조회가
   PK 인덱스를 그대로 타므로 파생 컬럼을 두지 않습니다(정규화 유지).

### 3.2 MySQL 8.x / MariaDB (권장 기본)

```sql
CREATE TABLE holiday (
    holiday_date  DATE          NOT NULL COMMENT '공휴일 날짜',
    name          VARCHAR(100)  NOT NULL COMMENT '명칭 (예: 설날, 대체공휴일(설날))',
    is_substitute TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '대체공휴일 여부',
    source        VARCHAR(20)   NOT NULL DEFAULT 'API' COMMENT 'API | MANUAL',
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (holiday_date, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='대한민국 공휴일 (특일 API 스냅샷 + 수동 등록분)';
```

- `utf8mb4` 필수(한글 명칭). PK 가 (date, name)이라 날짜 범위 조회에 추가 인덱스 불필요.

### 3.3 PostgreSQL

```sql
CREATE TABLE holiday (
    holiday_date  DATE         NOT NULL,
    name          VARCHAR(100) NOT NULL,
    is_substitute BOOLEAN      NOT NULL DEFAULT FALSE,
    source        VARCHAR(20)  NOT NULL DEFAULT 'API',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (holiday_date, name)
);
COMMENT ON TABLE holiday IS '대한민국 공휴일 (특일 API 스냅샷 + 수동 등록분)';
```

### 3.4 H2 / SQLite (임베디드, 독립 도구용)

```sql
-- H2 (MODE=MySQL 이면 3.2 DDL 그대로 사용 가능) / SQLite 공통 최소형
CREATE TABLE holiday (
    holiday_date  DATE         NOT NULL,
    name          VARCHAR(100) NOT NULL,
    is_substitute INTEGER      NOT NULL DEFAULT 0,
    source        VARCHAR(20)  NOT NULL DEFAULT 'API',
    updated_at    TIMESTAMP,
    PRIMARY KEY (holiday_date, name)
);
```

## 4. 적재(동기화) 규칙

> 코드 예시는 [API_GUIDE.md 6장](API_GUIDE.md) — 여기서는 규칙만 정리합니다.

1. **upsert 기준**: `(holiday_date, name)` 충돌 시 `is_substitute`, `updated_at` 만 갱신 (MySQL `ON DUPLICATE KEY UPDATE`, PostgreSQL `ON CONFLICT DO UPDATE`)
2. **삭제 동기화까지 원하면**: 트랜잭션 안에서
   `DELETE FROM holiday WHERE holiday_date BETWEEN 연초 AND 연말 AND source = 'API'` → 일괄 INSERT.
   (`source='API'` 조건이 수동 등록분을 보호)
3. **주기**: 연 1회 전체(당해+익년) + **월 1회 재동기화** — 임시공휴일은 발표 후에야 API에 추가됨
4. **sanity check**: 적재 후 연간 건수 `>= 10` 확인, 미달이면 커밋하지 말고 경보 (미공개 연도의 0건 응답을 기존 데이터 삭제로 오인하지 않도록)
5. 주말(토·일)은 저장하지 않음 — 요일 계산으로 충분하며 저장하면 데이터만 오염

## 5. 표준 조회 패턴

```sql
-- 특정일 공휴일 여부
SELECT EXISTS(SELECT 1 FROM holiday WHERE holiday_date = ?) AS is_holiday;

-- 연간 목록 (PK 인덱스 범위 스캔)
SELECT holiday_date, name, is_substitute
FROM holiday
WHERE holiday_date BETWEEN ? AND ?
ORDER BY holiday_date;
```

영업일 판정은 DB 가 아니라 앱에서 조합하는 것을 권장합니다(주말은 계산):

```java
boolean isBusinessDay(LocalDate d, Set<LocalDate> holidays) {   // holidays = 연간 1회 로드
    DayOfWeek w = d.getDayOfWeek();
    return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY && !holidays.contains(d);
}
```

## 6. Redis 캐시 형태 (대규모 조회 시에만)

원본은 RDB, Redis 는 조회 가속용:

```
SADD holiday:2027 20270101 20270206 20270207 ...   # 연도별 Set, 값은 yyyyMMdd
EXPIRE holiday:2027 90000                          # 25h — 일 배치가 항상 재적재
SISMEMBER holiday:2027 20270209                    # O(1) 판정
```

- 캐시 미스 시 RDB 조회 후 재적재(read-through). Redis 를 원본으로 쓰지 말 것(휘발).
- 트래픽이 크지 않으면 이 층은 생략 — 앱 메모리 캐시로 충분.

## 7. 안티패턴 정리

| 안티패턴 | 문제 |
|---|---|
| 요청마다 특일 API 호출 | 일 10,000건 제한 소진, 외부 장애 전파 |
| 날짜 단독 PK + 덮어쓰기 | 같은 날 2건(어린이날+부처님오신날) 중 1건 유실 |
| 주말까지 테이블에 저장 | 데이터 부풀림, 요일 계산과 이중 관리 |
| API 재동기화가 수동 등록분 삭제 | `source` 컬럼으로 반드시 분리 |
| 공휴일 전용 신규 DB 서버 구축 | 250행에 운영 부담만 증가 |
| 발급키를 DB에 저장 | 키는 데이터가 아니라 설정 — 환경변수/시크릿 관리로 |
