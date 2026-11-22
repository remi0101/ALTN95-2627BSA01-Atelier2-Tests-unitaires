package pos;

/**
 * Root of all business exceptions raised while dialoguing with the POS.
 */
public class PosException extends RuntimeException {

    public PosException(String message) {
        super(message);
    }

    public PosException(String message, Throwable cause) {
        super(message, cause);
    }
}
