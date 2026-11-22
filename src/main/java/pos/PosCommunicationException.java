package pos;

/**
 * Raised on low-level serial communication failures (port cannot be opened, write/read error...).
 */
public class PosCommunicationException extends PosException {

    public PosCommunicationException(String message) {
        super(message);
    }

    public PosCommunicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
