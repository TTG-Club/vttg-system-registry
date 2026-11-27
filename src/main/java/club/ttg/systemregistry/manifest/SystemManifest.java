package club.ttg.systemregistry.manifest;

/**
 * Проверенный {@code system.json}: поля, которые нужны реестру, и сам
 * документ как пришёл — его видит модератор.
 *
 * @param author         имена из {@code authors} через запятую (или {@code author})
 * @param compatibility  с какими версиями VTTG работает система
 * @param download       архив именно этой версии системы
 * @param updateManifest поле {@code manifest} — постоянная ссылка на свежий
 *                       манифест, по которой VTTG проверяет обновления
 */
public record SystemManifest(
        String id,
        String name,
        String version,
        String description,
        String author,
        Compatibility compatibility,
        String download,
        String updateManifest,
        String json
) {

    /** {@code compatibility} из манифеста; любое поле может отсутствовать. */
    public record Compatibility(String minimum, String verified, String maximum) {

        public static final Compatibility NONE = new Compatibility(null, null, null);

        /** Подходит ли система версии VTTG: {@code minimum ≤ версия ≤ maximum}. */
        public boolean accepts(String vttgVersion) {
            return (minimum == null || Versions.compare(minimum, vttgVersion) <= 0)
                    && (maximum == null || Versions.compare(vttgVersion, maximum) <= 0);
        }
    }
}
