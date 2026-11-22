package pos;

/**
 * Raised when the POS response is empty, malformed or contains invalid data.
 */
public class InvalidResponseException extends PosException {

    public InvalidResponseException(String message) {
        super(message);
    }

    public InvalidResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
