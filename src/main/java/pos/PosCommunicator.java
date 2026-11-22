package pos;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Objects;

/**
 * Communicates with a POS (cash register) over a serial link to retrieve the data
 * of the cheque currently being paid.
 *
 * <p>The serial link ({@link SerialChannel}) and the time source ({@link Clock}) are injected,
 * so that both can be replaced by stubs/mocks in unit tests.
 *
 * <p>Protocol:
 * <ul>
 *     <li>Request: {@code GET_CHEQUE_DATA\n}</li>
 *     <li>Nominal response: {@code OK;YYYY-MM-DD;AMOUNT\n}</li>
 *     <li>Error responses: {@code ERR_NO_TRANSACTION\n}, {@code ERR_BUSY\n}</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * try (PosCommunicator pos = new PosCommunicator(JSerialCommChannel.open("COM3", 3000))) {
 *     ChequeData data = pos.fetchChequeData();
 * }
 * }</pre>
 */
public class PosCommunicator implements AutoCloseable {

    private static final String COMMAND = "GET_CHEQUE_DATA\n";
    private static final String RESPONSE_OK = "OK";
    private static final String ERR_NO_TRANSACTION = "ERR_NO_TRANSACTION";
    private static final String ERR_BUSY = "ERR_BUSY";
    private static final String FIELD_SEPARATOR = ";";
    private static final char END_OF_FRAME = '\n';

    private static final int DEFAULT_TIMEOUT_MS = 3000;
    private static final int MAX_FRAME_LENGTH = 256;

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private final SerialChannel channel;
    private final int timeoutMs;
    private final Clock clock;

    /**
     * Creates a communicator using the default timeout (3000 ms) and the system clock.
     *
     * @param channel an already opened serial channel
     */
    public PosCommunicator(SerialChannel channel) {
        this(channel, DEFAULT_TIMEOUT_MS, Clock.systemDefaultZone());
    }

    /**
     * Creates a communicator.
     *
     * @param channel   an already opened serial channel
     * @param timeoutMs maximum time to wait for a complete response, in milliseconds
     * @param clock     time source used for the response timeout and the cheque date validation
     */
    public PosCommunicator(SerialChannel channel, int timeoutMs, Clock clock) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive");
        }
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.timeoutMs = timeoutMs;
    }

    /**
     * Sends the {@code GET_CHEQUE_DATA} command, reads and validates the POS response.
     *
     * @return the validated cheque data
     * @throws NoTransactionException    if no cheque payment is in progress
     * @throws PosBusyException          if the POS is busy
     * @throws PosTimeoutException       if no complete response is received in time
     * @throws InvalidResponseException  if the response is empty, malformed or contains invalid data
     * @throws PosCommunicationException on serial I/O failure
     */
    public ChequeData fetchChequeData() {
        sendCommand();
        String response = readResponse();
        return parseResponse(response);
    }

    private void sendCommand() {
        byte[] data = COMMAND.getBytes(StandardCharsets.US_ASCII);
        int written = channel.writeBytes(data, data.length);
        if (written != data.length) {
            throw new PosCommunicationException("Failed to send command to POS (" + written + "/" + data.length + " bytes written)");
        }
    }

    private String readResponse() {
        StringBuilder frame = new StringBuilder();
        byte[] buffer = new byte[1];
        long deadline = clock.millis() + timeoutMs;

        while (true) {
            if (clock.millis() > deadline) {
                throw new PosTimeoutException("No complete response from POS within " + timeoutMs + " ms");
            }
            int read = channel.readBytes(buffer, 1);
            if (read < 0) {
                throw new PosCommunicationException("Error while reading from serial port");
            }
            if (read == 0) {
                continue;
            }
            char c = (char) buffer[0];
            if (c == END_OF_FRAME) {
                break;
            }
            frame.append(c);
            if (frame.length() > MAX_FRAME_LENGTH) {
                throw new InvalidResponseException("Response frame exceeds " + MAX_FRAME_LENGTH + " characters");
            }
        }
        return frame.toString().trim();
    }

    private ChequeData parseResponse(String response) {
        if (response == null || response.isEmpty()) {
            throw new InvalidResponseException("Empty response from POS");
        }
        if (ERR_NO_TRANSACTION.equals(response)) {
            throw new NoTransactionException("No cheque transaction in progress");
        }
        if (ERR_BUSY.equals(response)) {
            throw new PosBusyException("POS is busy");
        }

        String[] parts = response.split(FIELD_SEPARATOR);
        if (parts.length != 3 || !RESPONSE_OK.equals(parts[0])) {
            throw new InvalidResponseException("Malformed response: " + response);
        }

        LocalDate date = parseDate(parts[1]);
        BigDecimal amount = parseAmount(parts[2]);
        return new ChequeData(date, amount);
    }

    private LocalDate parseDate(String value) {
        LocalDate date;
        try {
            date = LocalDate.parse(value, DATE_FORMAT);
        } catch (DateTimeParseException e) {
            throw new InvalidResponseException("Invalid cheque date: " + value, e);
        }
        if (date.isAfter(LocalDate.now(clock))) {
            throw new InvalidResponseException("Cheque date is in the future: " + value);
        }
        return date;
    }

    private BigDecimal parseAmount(String value) {
        BigDecimal amount;
        try {
            amount = new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new InvalidResponseException("Invalid cheque amount: " + value, e);
        }
        if (amount.signum() <= 0) {
            throw new InvalidResponseException("Cheque amount must be strictly positive: " + value);
        }
        if (amount.scale() > 2) {
            throw new InvalidResponseException("Cheque amount has more than 2 decimals: " + value);
        }
        return amount.setScale(2);
    }

    /**
     * Closes the underlying serial channel.
     */
    @Override
    public void close() {
        channel.close();
    }
}
