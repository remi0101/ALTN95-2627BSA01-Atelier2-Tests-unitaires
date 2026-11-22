package pos;

/**
 * Abstraction of a byte-oriented serial link (COM port / virtual serial link).
 *
 * <p>Decouples {@link PosCommunicator} from the concrete serial library so that the
 * communication layer can be replaced by a stub or a mock in unit tests.
 */
public interface SerialChannel extends AutoCloseable {

    /**
     * Writes {@code length} bytes from {@code data}.
     *
     * @return the number of bytes actually written, or a negative value on error
     */
    int writeBytes(byte[] data, int length);

    /**
     * Reads at most {@code length} bytes into {@code buffer}.
     *
     * @return the number of bytes read, {@code 0} if nothing was received before the
     * channel read timeout, or a negative value on error
     */
    int readBytes(byte[] buffer, int length);

    /**
     * Releases the underlying link.
     */
    @Override
    void close();
}
