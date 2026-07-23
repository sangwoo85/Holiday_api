package kr.holiday;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 실 API 호출 확인 테스트 — 아래 SERVICE_KEY 에 발급키를 넣거나
 * 환경변수 HOLIDAY_API_SERVICE_KEY 를 설정하면 실행된다(둘 다 없으면 skip).
 *
 * <p>⚠️ 실제 발급키를 넣은 상태로 커밋하지 말 것. 확인 후 placeholder 로 되돌린다.
 * (YEAR 를 바꾸면 아래 연도별 단언도 함께 조정할 것 — 기본 단언은 2027년 기준)
 *
 * <pre>
 * # 방법 1: 아래 상수에 키 붙여넣고 IDE 에서 실행
 * # 방법 2: HOLIDAY_API_SERVICE_KEY='발급키' ./mvnw test
 * </pre>
 */
class HolidayApiLiveTest {

    /** 공공데이터포털 발급키. placeholder 그대로면 환경변수 HOLIDAY_API_SERVICE_KEY 를 확인한다. */
    private static final String SERVICE_KEY = "여기에_공공데이터포털_발급키_입력";

    private static final int YEAR = 2027;

    @Test
    void 실제_API_에서_공휴일과_대체공휴일을_가져온다() throws Exception {
        String key = SERVICE_KEY.startsWith("여기에")
                ? System.getenv("HOLIDAY_API_SERVICE_KEY")
                : SERVICE_KEY;
        assumeTrue(key != null && !key.trim().isEmpty(),
                "발급키 미설정(상수/환경변수 모두 없음) — 실 API 테스트 skip");

        List<Holiday> holidays = new HolidayApiClient(key).fetchHolidays(YEAR);

        for (Holiday h : holidays) {
            System.out.printf("[LIVE] %s (%s) %s%n", h.date(),
                    h.date().getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN), h.name());
        }
        System.out.printf("[LIVE] %d년 공휴일 %d일 (대체공휴일 %d일)%n", YEAR, holidays.size(),
                holidays.stream().filter(Holiday::substitute).count());

        assertTrue(holidays.size() >= 10, "연간 공휴일이 비정상적으로 적음: " + holidays.size());
        assertTrue(holidays.stream().allMatch(h -> h.date().getYear() == YEAR), "요청 연도 외 날짜 포함");
        assertTrue(holidays.stream().anyMatch(h -> h.date().equals(LocalDate.of(YEAR, 1, 1))),
                "신정(1/1) 누락");
        // 2027년 기준: 설날 연휴(02-06 토 ~ 02-08 월)가 토요일과 겹쳐 지정되는 대체공휴일
        assertTrue(holidays.stream().anyMatch(h -> h.substitute()
                        && h.date().equals(LocalDate.of(2027, 2, 9))),
                "대체공휴일(설날, 2027-02-09) 누락");
    }
}
