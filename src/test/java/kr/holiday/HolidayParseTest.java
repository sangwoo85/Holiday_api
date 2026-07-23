package kr.holiday;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 응답 파싱 단위 테스트 — 네트워크·발급키 없이 항상 실행된다.
 * 샘플 JSON 은 실제 getRestDeInfo 응답 구조(활용가이드 v1.4) 그대로.
 * (JDK 8 호환을 위해 텍스트 블록 대신 문자열 연결 사용)
 */
class HolidayParseTest {

    @Test
    void 배열_응답을_날짜순으로_파싱하고_대체공휴일을_식별한다() throws IOException {
        String json = "{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"NORMAL SERVICE.\"},"
                + "\"body\":{\"items\":{\"item\":["
                + "{\"dateKind\":\"01\",\"dateName\":\"삼일절\",\"isHoliday\":\"Y\",\"locdate\":20270301,\"seq\":1},"
                + "{\"dateKind\":\"01\",\"dateName\":\"설날\",\"isHoliday\":\"Y\",\"locdate\":20270206,\"seq\":1},"
                + "{\"dateKind\":\"01\",\"dateName\":\"대체공휴일(설날)\",\"isHoliday\":\"Y\",\"locdate\":20270209,\"seq\":1}"
                + "]},\"numOfRows\":100,\"pageNo\":1,\"totalCount\":3}}}";

        List<Holiday> holidays = HolidayApiClient.parse(json);

        assertEquals(3, holidays.size());
        assertEquals(LocalDate.of(2027, 2, 6), holidays.get(0).date());   // 날짜순 정렬
        assertEquals("대체공휴일(설날)", holidays.get(1).name());
        assertTrue(holidays.get(1).substitute());
        assertTrue(holidays.get(1).isSubstitute());                        // JavaBean 접근자 동등성
        assertEquals(1, holidays.stream().filter(Holiday::substitute).count());
    }

    @Test
    void 단건_응답은_배열이_아닌_단일_객체로_와도_파싱한다() throws IOException {
        String json = "{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"NORMAL SERVICE.\"},"
                + "\"body\":{\"items\":{\"item\":"
                + "{\"dateKind\":\"01\",\"dateName\":\"1월1일\",\"isHoliday\":\"Y\",\"locdate\":20270101,\"seq\":1}"
                + "},\"numOfRows\":100,\"pageNo\":1,\"totalCount\":1}}}";

        List<Holiday> holidays = HolidayApiClient.parse(json);

        assertEquals(1, holidays.size());
        assertEquals(LocalDate.of(2027, 1, 1), holidays.get(0).getDate());
    }

    @Test
    void 결과_없음은_items_빈문자열로_와도_빈_목록을_반환한다() throws IOException {
        String json = "{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"NORMAL SERVICE.\"},"
                + "\"body\":{\"items\":\"\",\"numOfRows\":100,\"pageNo\":1,\"totalCount\":0}}}";

        assertTrue(HolidayApiClient.parse(json).isEmpty());
    }

    @Test
    void isHoliday_N_인_특일은_제외한다() throws IOException {
        String json = "{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"NORMAL SERVICE.\"},"
                + "\"body\":{\"items\":{\"item\":["
                + "{\"dateKind\":\"01\",\"dateName\":\"공휴일아닌특일\",\"isHoliday\":\"N\",\"locdate\":20270401,\"seq\":1},"
                + "{\"dateKind\":\"01\",\"dateName\":\"어린이날\",\"isHoliday\":\"Y\",\"locdate\":20270505,\"seq\":1}"
                + "]},\"numOfRows\":100,\"pageNo\":1,\"totalCount\":2}}}";

        List<Holiday> holidays = HolidayApiClient.parse(json);

        assertEquals(1, holidays.size());
        assertEquals("어린이날", holidays.get(0).name());
    }

    @Test
    void resultCode_가_성공이_아니면_예외를_던진다() {
        String json = "{\"response\":{\"header\":{\"resultCode\":\"30\","
                + "\"resultMsg\":\"SERVICE_KEY_IS_NOT_REGISTERED_ERROR\"}}}";

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> HolidayApiClient.parse(json));
        assertTrue(e.getMessage().contains("SERVICE_KEY_IS_NOT_REGISTERED_ERROR"));
    }

    @Test
    void Holiday_값_객체는_동등성과_직렬화_인터페이스를_보장한다() {
        Holiday a = new Holiday(LocalDate.of(2027, 2, 9), "대체공휴일(설날)");
        Holiday b = new Holiday(LocalDate.of(2027, 2, 9), "대체공휴일(설날)");

        assertEquals(a, b);                                   // DB 중복 판정 등에 사용 가능
        assertEquals(a.hashCode(), b.hashCode());
        assertTrue(a instanceof java.io.Serializable);
    }
}
