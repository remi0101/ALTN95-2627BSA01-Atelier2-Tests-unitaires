package pos;

import com.fazecast.jSerialComm.SerialPort;

/**
 * {@link SerialChannel} implementation backed by a real serial port (jSerialComm).
 *
 * <p>This thin adapter is the only class touching the hardware; it is not unit tested
 * (integration concern) and is excluded from coverage and mutation analysis.
 */
public final class JSerialCommChannel implements SerialChannel {

    private static final int BAUD_RATE = 9600;
    private static final int DATA_BITS = 8;

    private final SerialPort serialPort;

    private JSerialCommChannel(SerialPort serialPort) {
        this.serialPort = serialPort;
    }

    /**
     * Configures (9600 bauds, 8N1) and opens the given serial port.
     *
     * @param portName      serial port name, e.g. {@code COM3} or {@code /dev/ttyUSB0}
     * @param readTimeoutMs timeout of a single read call, in milliseconds
     * @throws PosCommunicationException if the port cannot be opened
     */
    public static JSerialCommChannel open(String portName, int readTimeoutMs) {
        SerialPort port = SerialPort.getCommPort(portName);
        port.setComPortParameters(BAUD_RATE, DATA_BITS, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, readTimeoutMs, readTimeoutMs);
        if (!port.openPort()) {
            throw new PosCommunicationException("Unable to open serial port " + portName);
        }
        return new JSerialCommChannel(port);
    }

    @Override
    public int writeBytes(byte[] data, int length) {
        return serialPort.writeBytes(data, length);
    }

    @Override
    public int readBytes(byte[] buffer, int length) {
        return serialPort.readBytes(buffer, length);
    }

    @Override
    public void close() {
        if (serialPort.isOpen()) {
            serialPort.closePort();
        }
    }
}
