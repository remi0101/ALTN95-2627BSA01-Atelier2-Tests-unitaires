package pos;

/**
 * Raised when the POS answers {@code ERR_BUSY}: the POS is currently busy.
 */
public class PosBusyException extends PosException {

    public PosBusyException(String message) {
        super(message);
    }
}
