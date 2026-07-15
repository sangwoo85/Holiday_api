package kr.holiday;

import java.time.LocalDate;

/**
 * 공휴일 1건. 특일 API 의 item 하나에 대응한다.
 *
 * @param date 날짜 (locdate, yyyyMMdd)
 * @param name 명칭 (dateName, 예: "설날", "대체공휴일(설날)")
 */
public record Holiday(LocalDate date, String name) {

    /** 대체공휴일 여부 (dateName 에 "대체공휴일" 포함). */
    public boolean substitute() {
        return name.contains("대체공휴일");
    }
}
