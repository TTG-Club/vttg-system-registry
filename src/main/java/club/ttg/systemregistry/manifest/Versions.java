package club.ttg.systemregistry.manifest;

import java.util.regex.Pattern;

/**
 * Сравнение версий VTTG и систем: числовые компоненты по порядку, недостающие
 * считаются нулями ({@code 0.9} = {@code 0.9.0}). Пререлиз и метаданные сборки
 * ({@code -beta}, {@code +build}) не учитываются — для проверки совместимости
 * этого достаточно.
 */
public final class Versions {

    /** Полная версия системы: {@code 1.2.3}, допускается {@code -beta.1}/{@code +build}. */
    public static final Pattern SEMANTIC =
            Pattern.compile("^\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.+-]+)?$");

    /** Версия VTTG в {@code compatibility}: от одного до трёх числовых компонентов. */
    public static final Pattern LOOSE =
            Pattern.compile("^\\d+(?:\\.\\d+){0,2}(?:[-+][0-9A-Za-z.+-]+)?$");

    private Versions() {
    }

    public static int compare(String left, String right) {
        long[] a = numbers(left);
        long[] b = numbers(right);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long x = i < a.length ? a[i] : 0;
            long y = i < b.length ? b[i] : 0;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        return 0;
    }

    private static long[] numbers(String version) {
        String core = version.strip().split("[-+]", 2)[0];
        String[] parts = core.split("\\.");
        long[] numbers = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                numbers[i] = Long.parseLong(parts[i]);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Некорректная версия: " + version);
            }
        }
        return numbers;
    }
}
