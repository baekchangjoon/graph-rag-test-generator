package io.graphrag.sample.orders;

import java.text.ParseException;
import java.util.Locale;
import org.springframework.format.Formatter;
import org.springframework.stereotype.Component;

/**
 * 참조-name 변환(spec §5-2): name 토큰 → Color 엔티티. @Component라 Spring Boot가 MVC 변환서비스에 자동 등록.
 * 빌더는 Formatter<Color>의 제네릭 인자로 Color를 convertedTypes에 수집, 러너가 colors 행의 name을 토큰으로 합성.
 */
@Component
public class ColorFormatter implements Formatter<Color> {

    private final ColorRepository colors;

    public ColorFormatter(ColorRepository colors) {
        this.colors = colors;
    }

    @Override
    public Color parse(String text, Locale locale) throws ParseException {
        return colors.findByName(text)
                .orElseThrow(() -> new ParseException("Unknown color: " + text, 0));
    }

    @Override
    public String print(Color object, Locale locale) {
        return object.getName();
    }
}
