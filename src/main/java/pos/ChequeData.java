package pos;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Immutable data of the cheque currently being paid at the POS.
 *
 * @param date   the cheque date
 * @param amount the cheque amount (strictly positive, scale 2)
 */
public record ChequeData(LocalDate date, BigDecimal amount) {

    public ChequeData {
        Objects.requireNonNull(date, "date must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
    }
}
