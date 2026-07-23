package kr.holiday;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 공공데이터포털 "특일 정보제공 서비스"(한국천문연구원, SpcdeInfoService) 공휴일 조회 클라이언트.
 *
 * <p><b>JDK 8 호환</b>: HTTP 는 {@link HttpURLConnection}(JDK 내장)만 사용한다.
 * 외부 의존성은 jackson-databind 하나. 스레드 세이프(요청마다 커넥션 생성, 상태 불변).
 *
 * <p>getRestDeInfo 오퍼레이션은 <b>공휴일만</b> 반환한다: 법정공휴일 + 국경일 중 공휴일 +
 * 대체공휴일 + 임시공휴일·선거일. 토·일요일 자체는 데이터에 없다(공휴일 날짜 목록만 제공).
 *
 * <p>data.go.kr JSON 특성 처리: 결과 0건이면 items 가 빈 문자열(""), 1건이면 item 이
 * 배열이 아닌 단일 객체로 내려온다 — 모두 허용한다. isHoliday=Y 항목만 반영한다.
 */
public class HolidayApiClient {

    static final String DEFAULT_BASE_URL =
            "https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getRestDeInfo";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter LOCDATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;

    private final String serviceKey;
    private final String baseUrl;

    /**
     * @param serviceKey 공공데이터포털 발급키. 디코딩(원본)·인코딩 키 모두 허용 —
     *                   '%' 포함 여부로 자동 판별해 이중 인코딩을 방지한다.
     */
    public HolidayApiClient(String serviceKey) {
        this(serviceKey, DEFAULT_BASE_URL);
    }

    /**
     * @param serviceKey 공공데이터포털 발급키
     * @param baseUrl    getRestDeInfo 엔드포인트 전체 URL (테스트/미러 서버용 override)
     */
    public HolidayApiClient(String serviceKey, String baseUrl) {
        if (serviceKey == null || serviceKey.trim().isEmpty()) {
            throw new IllegalArgumentException("serviceKey 가 비어 있습니다 (data.go.kr 발급키 필요)");
        }
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("baseUrl 이 비어 있습니다");
        }
        this.serviceKey = serviceKey;
        this.baseUrl = baseUrl;
    }

    /**
     * 해당 연도의 공휴일 전체를 날짜 오름차순으로 반환한다.
     *
     * @throws IOException           네트워크 오류
     * @throws IllegalStateException HTTP 비정상 응답(401 등) 또는 API 오류 resultCode
     *                               — 빈 목록으로 오인하지 않도록 예외로 구분한다
     */
    public List<Holiday> fetchHolidays(int year) throws IOException {
        String url = baseUrl + "?solYear=" + year + "&_type=json&numOfRows=100&ServiceKey=" + encodedKey();
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");

            int status = conn.getResponseCode();
            if (status != 200) {
                throw new IllegalStateException("HTTP " + status
                        + " — 키 미등록/활용신청 미승인 시 401 Unauthorized (승인 반영까지 최대 1시간)");
            }
            return parse(readAll(conn.getInputStream()));
        } finally {
            conn.disconnect();
        }
    }

    /** 포털 발급 키 2형태 모두 허용: '%' 포함(인코딩 키)은 그대로, 아니면(디코딩 키) 인코딩. */
    private String encodedKey() {
        if (serviceKey.contains("%")) {
            return serviceKey;
        }
        try {
            return URLEncoder.encode(serviceKey, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError("UTF-8 은 항상 지원됨", e);   // 도달 불가
        }
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        } finally {
            reader.close();
        }
        return sb.toString();
    }

    /**
     * 응답 JSON → 공휴일 목록(날짜순). 테스트에서 네트워크 없이 검증할 수 있도록 분리.
     * resultCode 가 성공(00)이 아니면 {@link IllegalStateException}.
     */
    static List<Holiday> parse(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        JsonNode header = root.path("response").path("header");
        if (!"00".equals(header.path("resultCode").asText())) {
            throw new IllegalStateException("API 오류 응답: " + header);
        }

        JsonNode item = root.path("response").path("body").path("items").path("item");
        List<JsonNode> nodes = new ArrayList<JsonNode>();
        if (item.isArray()) {
            for (JsonNode n : item) {          // 일반: 배열
                nodes.add(n);
            }
        } else if (item.isObject()) {
            nodes.add(item);                   // 1건: 단일 객체
        }                                      // 0건: items 가 "" → path() 는 missing node → 빈 목록

        List<Holiday> holidays = new ArrayList<Holiday>();
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
