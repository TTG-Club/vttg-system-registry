package club.ttg.systemregistry.submission;

/** У id системы уже есть живая заявка — своя или чужая. */
public class SystemIdTakenException extends RuntimeException {

    public SystemIdTakenException(String message) {
        super(message);
    }
}
