package kr.holiday;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 공휴일 1건. 특일 API 의 item 하나에 대응하는 불변(immutable) 값 객체.
 *
 * <p>JDK 8 호환을 위해 record 가 아닌 일반 클래스로 구현한다.
 * 접근자는 두 스타일을 모두 제공한다:
 * <ul>
 *   <li>record 스타일: {@link #date()}, {@link #name()}, {@link #substitute()} — 0.1.0 소스 호환</li>
 *   <li>JavaBean 스타일: {@link #getDate()}, {@link #getName()}, {@link #isSubstitute()}
 *       — ORM(MyBatis/JPA)·Jackson 직렬화·DB 저장 시 편의</li>
 * </ul>
 */
public final class Holiday implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 날짜 (API locdate, yyyyMMdd). */
    private final LocalDate date;

    /** 명칭 (API dateName, 예: "설날", "대체공휴일(설날)"). */
    private final String name;

    public Holiday(LocalDate date, String name) {
        this.date = Objects.requireNonNull(date, "date");
        this.name = Objects.requireNonNull(name, "name");
    }

    public LocalDate date() {
        return date;
    }

    public String name() {
        return name;
    }

    /** 대체공휴일 여부 (dateName 에 "대체공휴일" 포함). */
    public boolean substitute() {
        return name.contains("대체공휴일");
    }

    public LocalDate getDate() {
        return date;
    }

    public String getName() {
        return name;
    }

    public boolean isSubstitute() {
        return substitute();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Holiday)) {
            return false;
        }
        Holiday other = (Holiday) o;
        return date.equals(other.date) && name.equals(other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(date, name);
    }

    @Override
    public String toString() {
        return "Holiday[date=" + date + ", name=" + name + "]";
    }
}
