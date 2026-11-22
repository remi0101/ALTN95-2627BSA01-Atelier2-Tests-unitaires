package pos;

/**
 * Raised when the POS does not send a complete response within the configured timeout.
 */
public class PosTimeoutException extends PosException {

    public PosTimeoutException(String message) {
        super(message);
    }
}
