package kr.holiday;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * CLI: 연도별 대한민국 공휴일 출력.
 *
 * <pre>
 * HOLIDAY_API_SERVICE_KEY='발급키' ./mvnw -q compile exec:java -Dexec.args="2027"
 * </pre>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String key = System.getenv("HOLIDAY_API_SERVICE_KEY");
        if (key == null || key.isBlank()) {
            System.err.println("환경변수 HOLIDAY_API_SERVICE_KEY 에 공공데이터포털 발급키를 설정하세요.");
            System.err.println("예) HOLIDAY_API_SERVICE_KEY='발급키' ./mvnw -q compile exec:java -Dexec.args=\"2027\"");
            System.exit(1);
        }
        int year = args.length > 0 ? Integer.parseInt(args[0]) : LocalDate.now().getYear();

        List<Holiday> holidays = new HolidayApiClient(key).fetchHolidays(year);

        System.out.printf("=== %d년 대한민국 공휴일 (%d일) ===%n", year, holidays.size());
        for (Holiday h : holidays) {
            System.out.printf("%s (%s) %s%s%n",
                    h.date(),
                    h.date().getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN),
                    h.name(),
                    h.substitute() ? "  ★" : "");
        }
        long substitutes = holidays.stream().filter(Holiday::substitute).count();
        System.out.printf("(★ 대체공휴일 %d일 포함)%n", substitutes);
    }
}
