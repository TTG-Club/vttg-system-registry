package club.ttg.systemregistry.common;

/** Параметр запроса не прошёл проверку — ответ 400. */
public class InvalidRequestParameterException extends RuntimeException {

    public InvalidRequestParameterException(String message) {
        super(message);
    }
}
