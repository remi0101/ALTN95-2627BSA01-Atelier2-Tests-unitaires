package pos;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class ChequeDataTest {

    @Test
    void refuse_une_date_nulle() {
        // Arrange
        BigDecimal amount = BigDecimal.ONE;

        // Act
        Throwable thrown = catchThrowable(() -> new ChequeData(null, amount));

        // Assert
        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessage("date must not be null");
    }

    @Test
    void refuse_un_montant_nul() {
        // Arrange
        LocalDate date = LocalDate.of(2026, 11, 16);

        // Act
        Throwable thrown = catchThrowable(() -> new ChequeData(date, null));

        // Assert
        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessage("amount must not be null");
    }
}
