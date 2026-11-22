package pos;

import com.fazecast.jSerialComm.SerialPort;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * Communicates with a POS (cash register) over a serial link to retrieve the data
 * of the cheque currently being paid.
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
 * try (PosCommunicator pos = new PosCommunicator("COM3")) {
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
    private static final int BAUD_RATE = 9600;
    private static final int DATA_BITS = 8;
    private static final int MAX_FRAME_LENGTH = 256;

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private final SerialPort serialPort;
    private final int timeoutMs;

    /**
     * Opens the given serial port with the default timeout (3000 ms).
     *
     * @param portName serial port name, e.g. {@code COM3} or {@code /dev/ttyUSB0}
     */
    public PosCommunicator(String portName) {
        this(portName, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Opens the given serial port.
     *
     * @param portName  serial port name, e.g. {@code COM3} or {@code /dev/ttyUSB0}
     * @param timeoutMs read timeout in milliseconds
     * @throws PosCommunicationException if the port cannot be opened
     */
    public PosCommunicator(String portName, int timeoutMs) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive");
        }
        this.timeoutMs = timeoutMs;
        this.serialPort = SerialPort.getCommPort(portName);
        this.serialPort.setComPortParameters(BAUD_RATE, DATA_BITS, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        this.serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, timeoutMs, timeoutMs);
        if (!this.serialPort.openPort()) {
            throw new PosCommunicationException("Unable to open serial port " + portName);
        }
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
        int written = serialPort.writeBytes(data, data.length);
        if (written != data.length) {
            throw new PosCommunicationException("Failed to send command to POS (" + written + "/" + data.length + " bytes written)");
        }
    }

    private String readResponse() {
        StringBuilder frame = new StringBuilder();
        byte[] buffer = new byte[1];
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw new PosTimeoutException("No complete response from POS within " + timeoutMs + " ms");
            }
            int read = serialPort.readBytes(buffer, 1);
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
        if (date.isAfter(LocalDate.now())) {
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
     * Closes the underlying serial port.
     */
    @Override
    public void close() {
        if (serialPort.isOpen()) {
            serialPort.closePort();
        }
    }
}
