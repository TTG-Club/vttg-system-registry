package club.ttg.systemregistry.catalog;

public class CatalogSystemNotFoundException extends RuntimeException {

    public CatalogSystemNotFoundException(String systemId) {
        super("Система «" + systemId + "» не найдена в каталоге");
    }
}
