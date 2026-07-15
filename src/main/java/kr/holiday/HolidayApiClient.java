package kr.holiday;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 공공데이터포털 "특일 정보제공 서비스"(한국천문연구원, SpcdeInfoService) 공휴일 조회 클라이언트.
 *
 * <p>getRestDeInfo 오퍼레이션은 <b>공휴일만</b> 반환한다: 법정공휴일 + 국경일 중 공휴일 +
 * 대체공휴일 + 임시공휴일·선거일. 토·일요일 자체는 데이터에 없다(공휴일 날짜 목록만 제공).
 *
 * <p>data.go.kr JSON 특성 처리: 결과 0건이면 items 가 빈 문자열(""), 1건이면 item 이
 * 배열이 아닌 단일 객체로 내려온다 — 모두 허용한다. isHoliday=Y 항목만 반영한다.
 */
public class HolidayApiClient {

    static final String BASE_URL =
            "https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getRestDeInfo";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter LOCDATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final HttpClient http;
    private final String serviceKey;

    /**
     * @param serviceKey 공공데이터포털 발급키. 디코딩(원본)·인코딩 키 모두 허용 —
     *                   '%' 포함 여부로 자동 판별해 이중 인코딩을 방지한다.
     */
    public HolidayApiClient(String serviceKey) {
        if (serviceKey == null || serviceKey.isBlank()) {
            throw new IllegalArgumentException("serviceKey 가 비어 있습니다 (data.go.kr 발급키 필요)");
        }
        this.serviceKey = serviceKey;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /** 해당 연도의 공휴일 전체를 날짜순으로 반환한다. 실패 시 예외(빈 목록으로 오인 방지). */
    public List<Holiday> fetchHolidays(int year) throws IOException, InterruptedException {
        String key = serviceKey.contains("%")
                ? serviceKey
                : URLEncoder.encode(serviceKey, StandardCharsets.UTF_8);
        String url = BASE_URL + "?solYear=" + year + "&_type=json&numOfRows=100&ServiceKey=" + key;

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode()
                    + " — 키 미등록/활용신청 미승인 시 401 Unauthorized (승인 반영까지 최대 1시간)");
        }
        return parse(response.body());
    }

    /**
     * 응답 JSON → 공휴일 목록(날짜순). 테스트에서 네트워크 없이 검증할 수 있도록 분리.
     * resultCode 가 성공(00)이 아니면 예외를 던진다.
     */
    static List<Holiday> parse(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        JsonNode header = root.path("response").path("header");
        if (!"00".equals(header.path("resultCode").asText())) {
            throw new IllegalStateException("API 오류 응답: " + header);
        }

        JsonNode item = root.path("response").path("body").path("items").path("item");
        List<JsonNode> nodes = new ArrayList<>();
        if (item.isArray()) {
            item.forEach(nodes::add);          // 일반: 배열
        } else if (item.isObject()) {
            nodes.add(item);                   // 1건: 단일 객체
        }                                      // 0건: items 가 "" → path() 는 missing node → 빈 목록

        List<Holiday> holidays = new ArrayList<>();
        for (JsonNode n : nodes) {
            if (!"Y".equalsIgnoreCase(n.path("isHoliday").asText())) {
                continue;                      // 공휴일 아닌 특일(isHoliday=N) 제외
            }
            holidays.add(new Holiday(
                    LocalDate.parse(n.path("locdate").asText(), LOCDATE),
                    n.path("dateName").asText()));
        }
        holidays.sort(Comparator.comparing(Holiday::date));
        return holidays;
    }
}
