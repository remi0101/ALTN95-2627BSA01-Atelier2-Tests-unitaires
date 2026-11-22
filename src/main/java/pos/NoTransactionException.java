package pos;

/**
 * Raised when the POS answers {@code ERR_NO_TRANSACTION}: no cheque payment is in progress.
 */
public class NoTransactionException extends PosException {

    public NoTransactionException(String message) {
        super(message);
    }
}
