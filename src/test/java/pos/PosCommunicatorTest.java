package pos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de {@link PosCommunicator}, écrits selon le motif AAA (Arrange / Act / Assert).
 * La liaison série est un mock Mockito de {@link SerialChannel} ; l'horloge est simulée.
 */
@ExtendWith(MockitoExtension.class)
@Timeout(5)
class PosCommunicatorTest {

    /** "Aujourd'hui" pour tous les tests : 2026-11-22 10:00 UTC. */
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final int TIMEOUT_MS = 3000;
    private static final byte[] EXPECTED_COMMAND = "GET_CHEQUE_DATA\n".getBytes(StandardCharsets.US_ASCII);

    @Mock
    private SerialChannel channel;

    /** Octets que la caisse simulée enverra, un par lecture ; ensuite les lectures renvoient 0 (silence). */
    private final Deque<Byte> incoming = new ArrayDeque<>();

    // ------------------------------------------------------------------ helpers (Arrange)

    private PosCommunicator communicator() {
        return communicator(new SteppingClock(NOW, Duration.ofMillis(1), ZoneOffset.UTC));
    }

    private PosCommunicator communicator(Clock clock) {
        return new PosCommunicator(channel, TIMEOUT_MS, clock);
    }

    /** La caisse accepte intégralement la commande écrite. */
    private void commandIsAccepted() {
        when(channel.writeBytes(any(byte[].class), anyInt())).thenAnswer(inv -> inv.getArgument(1));
    }

    /** La caisse accepte la commande puis répond {@code frame} octet par octet, puis se tait. */
    private void posAnswers(String frame) {
        commandIsAccepted();
        for (byte b : frame.getBytes(StandardCharsets.ISO_8859_1)) {
            incoming.add(b);
        }
        when(channel.readBytes(any(byte[].class), anyInt())).thenAnswer(inv -> {
            Byte next = incoming.poll();
            if (next == null) {
                return 0;
            }
            ((byte[]) inv.getArgument(0))[0] = next;
            return 1;
        });
    }

    private static ChequeData cheque(String date, String amount) {
        return new ChequeData(LocalDate.parse(date), new BigDecimal(amount));
    }

    // ------------------------------------------------------------------ tests

    @Nested
    @DisplayName("Envoi de la commande")
    class SendCommand {

        @Test
        void envoie_exactement_GET_CHEQUE_DATA_en_ASCII() {
            // Arrange
            posAnswers("OK;2026-11-16;145.50\n");
            PosCommunicator communicator = communicator();
            ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);

            // Act
            communicator.fetchChequeData();

            // Assert
            verify(channel).writeBytes(sent.capture(), eq(EXPECTED_COMMAND.length));
            assertThat(sent.getValue()).isEqualTo(EXPECTED_COMMAND);
        }

        @Test
        void envoie_la_commande_avant_de_lire_la_reponse() {
            // Arrange
            posAnswers("OK;2026-11-16;145.50\n");
            PosCommunicator communicator = communicator();

            // Act
            communicator.fetchChequeData();

            // Assert
            InOrder order = inOrder(channel);
            order.verify(channel).writeBytes(any(byte[].class), anyInt());
            order.verify(channel, atLeastOnce()).readBytes(any(byte[].class), anyInt());
        }

        @ParameterizedTest(name = "{0} octets écrits")
        @ValueSource(ints = {-1, 0, 5, 15})
        void ecriture_incomplete_leve_une_erreur_de_communication_sans_lire(int written) {
            // Arrange
            when(channel.writeBytes(any(byte[].class), anyInt())).thenReturn(written);
            PosCommunicator communicator = communicator();

            // Act
            Throwable thrown = catchThrowable(communicator::fetchChequeData);

            // Assert
            assertThat(thrown).isInstanceOf(PosCommunicationException.class)
                    .hasMessageContaining(written + "/16");
            verify(channel, never()).readBytes(any(byte[].class), anyInt());
        }
    }

    @Nested
    @DisplayName("Réponse nominale")
    class Nominal {

        @Test
        void exemple_de_la_specification() {
            // Arrange
            posAnswers("OK;2026-11-16;145.50\n");
            PosCommunicator communicator = communicator();

            // Act
            ChequeData data = communicator.fetchChequeData();

            // Assert
            assertThat(data).isEqualTo(cheque("2026-11-16", "145.50"));
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "145.50, 145.50",
                "145.5,  145.50",
                "145,    145.00",
                "0.01,   0.01",
                "007.10, 7.10",
                "99999999999.99, 99999999999.99"
        })
        void montant_normalise_a_deux_decimales(String raw, String expected) {
            // Arrange
            posAnswers("OK;2026-11-16;" + raw + "\n");
            PosCommunicator communicator = communicator();

            // Act
            ChequeData data = communicator.fetchChequeData();

            // Assert
            assertThat(data.amount()).isEqualTo(new BigDecimal(expected));
            assertThat(data.amount().scale()).isEqualTo(2);
        }

        @Test
        void accepte_une_fin_de_ligne_CRLF() {
            // Arrange
            posAnswers("OK;2026-11-16;145.50\r\n");
            PosCommunicator communicator = communicator();

            // Act
            ChequeData data = communicator.fetchChequeData();

            // Assert
            assertThat(data).isEqualTo(cheque("2026-11-16", "145.50"));
        }

        @Test
        void tolere_des_silences_entre_les_octets_avant_le_timeout() {
            // Arrange : une lecture vide (0) s'intercale entre chaque octet reçu
            commandIsAccepted();
            byte[] frame = "OK;2026-11-16;145.50\n".getBytes(StandardCharsets.US_ASCII);
            int[] calls = {0};
            when(channel.readBytes(any(byte[].class), anyInt())).thenAnswer(inv -> {
                int call = calls[0]++;
                if (call % 2 == 0) {
                    return 0;
                }
                ((byte[]) inv.getArgument(0))[0] = frame[call / 2];
                return 1;
            });
            PosCommunicator communicator = communicator();

            // Act
            ChequeData data = communicator.fetchChequeData();

            // Assert
            assertThat(data).isEqualTo(cheque("2026-11-16", "145.50"));
        }

        @Test
        void ne_lit_pas_au_dela_de_la_fin_de_trame() {
            // Arrange
            posAnswers("OK;2026-11-16;145.50\nOK;2026-11-17;10.00\n");
            PosCommunicator communicator = communicator();

            // Act
            communicator.fetchChequeData();

            // Assert
            assertThat(incoming).hasSize("OK;2026-11-17;10.00\n".length());
        }

        @Test
        void deux_appels_successifs_lisent_deux_trames() {
            // Arrange
            posAnswers("OK;2026-11-16;145.50\nOK;2026-11-17;10.00\n");
            PosCommunicator communicator = communicator();

            // Act
            ChequeData first = communicator.fetchChequeData();
            ChequeData second = communicator.fetchChequeData();

            // Assert
            assertThat(first).isEqualTo(cheque("2026-11-16", "145.50"));
            assertThat(second).isEqualTo(cheque("2026-11-17", "10.00"));
        }
    }

    @Nested
    @DisplayName("Réponses d'erreur de la caisse")
    class PosErrors {

        @Test
        void ERR_NO_TRANSACTION_leve_NoTransactionException() {
            // Arrange
            posAnswers("ERR_NO_TRANSACTION\n");
            PosCommunicator communicator = communicator();

            // Act
            Throwable thrown = catchThrowable(communicator::fetchChequeData);

            // Assert
            assertThat(thrown).isInstanceOf(NoTransactionException.class).isInstanceOf(PosException.class);
        }

        @Test
        void ERR_BUSY_leve_PosBusyException() {
            // Arrange
            posAnswers("ERR_BUSY\n");
            PosCommunicator communicator = communicator();

            // Act
            Throwable thrown = catchThrowable(communicator::fetchChequeData);

            // Assert
            assertThat(thrown).isInstanceOf(PosBusyException.class).isInstanceOf(PosException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"ERR_UNKNOWN\n", "ERR_BUSY_\n", "err_busy\n", "ERR_NO_TRANSACTION;X\n"})
        void code_erreur_inconnu_est_une_trame_invalide(String frame) {
            // Arrange
            posAnswers(frame);
            PosCommunicator communicator = communicator();

            // Act
            Throwable thrown = catchThrowable(communicator::fetchChequeData);

            // Assert
            assertThat(thrown).isInstanceOf(InvalidResponseException.class);
        }
    }

    @Nested
    @DisplayName("Trames vides, malformées ou corrompues")
    class Malformed {

        @ParameterizedTest
        @ValueSource(strings = {"\n", "\r\n"})
        void trame_vide(String frame) {
            // Arrange
            posAnswers(frame);
            PosCommunicator communicator = communicator();

            // Act
            Throwable thrown = catchThrowable(communicator::fetchChequeData);

            // Assert
            assertThat(thrown).isInstanceOf(InvalidResponseException.class).hasMessageContaining("Empty");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "   \n",
                "OK\n",
                "OK;2026-11-16\n",
                "OK;2026-11-16;145.50;EXTRA\n",
                "OK;2026-11-16;145.50;\n",
                "OK;2026-11-16;145.50;;\n",
                "OK,2026-11-16,145.50\n",
                "KO;2026-11-16;145.50\n",
                "ok;2026-11-16;145.50\n",
                ";2026-11-16;145.50\n",
                "OK;;145.50\n",
                "OK;2026-11-16;\n",
                "OK;145.50;2026-11-16\n",
                "OK;2026-11-16 ;145.50\n",
                "OK;2026-11-16;145.50\u0000\n",
                "OK;2026-11-16;14é5.50\n"
        })
        void trame_mal_formee(String frame) {
            // Arrange
            posAnswers(frame);
            PosCommunicator communicator = communicator();

            // Act
            Throwable thrown = catchThrowable(communicator::fetchChequeData);

            // Assert
            assertThat(thrown).isInstanceOf(InvalidResponseException.class);
        }

        @Test
        void trame_trop_longue_est_rejetee_sans_lire_indefiniment() {
            // Arrange
            posAnswers("X".repeat(1000) + "\n");
            PosCommunicator communicator = communicator();

            // Act
            Throwable thrown = catchThrowable(communicator::fetchChequeData);

            // Assert
            assertThat(thrown).isInstanceOf(InvalidResponseException.class).hasMessageContaining("exceeds");
            assertThat(incoming).isNotEmpty();
        }

        @Test
        void trame_de_256_caracteres_n_est_pas_trop_longue() {
            // Arrange
            posAnswers("X".repeat(256) + "\n");
            PosCommunicator communicator = communicator();

            // Act
            Throwable thrown = catchThrowable(communicator::fetchChequeData);

            // Assert
            assertThat(thrown).isInstanceOf(InvalidResponseException.class)
                    .hasMessageStartingWith("Malformed response");
        }

        @Test
        void trame_de_257_caracteres_est_trop_longue() {
            // Arrange
            posAnswers("X".repeat(257) + "\n");
            PosCommunicator communicator = communicator();

            // Act
            Throwable thrown = catchThrowable(communicator::fetchChequeData);

            // Assert
            assertThat(thrown).isInstanceOf(InvalidResponseException.class).hasMessageContaining("exceeds 256");
        }
    }

    @Nested
    @DisplayName("Validation de la date")
    class DateValidation {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "2026-11-22", // aujourd'hui
                "2026-11-21", // hier
                "2024-02-29", // 29 février d'une année bissextile
                "2000-02-29", // bissextile séculaire (divisible par 400)
                "2025-12-31", // changement d'année
                "2026-01-01"
        })
        void dates_valides(String date) {
            // Arrange
            posAnswers("OK;" + date + ";10.00\n");
            PosCommunicator communicator = communicator();

            // Act
            ChequeData data = communicator.fetchChequeData();

            // Assert
            assertThat(data.date()).isEqualTo(LocalDate.parse(date));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "2025-02-29", // année non bissextile
                "1900-02-29", // séculaire non bissextile
                "2026-02-30",
                "2026-04-31",
                "2026-13-01",
                "2026-00-10",
                "2026-11-00",
                "2026-11-32",
                "2026-1-16",
                "26-11-16",
                "16/11/2026",
                "2026/11/16",
                "20261116",
                "+2026-11-16",
                "2026-11-16T10:00",
                "AAAA-MM-JJ"
        })
        void dates_invalides(String date) {
            // Arrange
            posAnswers("OK;" + date + ";10.00\n");
            PosCommunicator communicator = communicator();

            // Act
            Throwable thrown = catchThrowable(communicator::fetchChequeData);

            // Assert
            assertThat(thrown).isInstanceOf(InvalidResponseException.class).hasMessageContaining("date");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"2026-11-23", "2027-01-01", "9999-12-31"})
        void date_future_rejetee(String date) {
            // Arrange
            posAnswers("OK;" + date + ";10.00\n");
            PosCommunicator communicator = communicator();

            // Act
            Throwable thrown = catchThrowable(communicator::fetchChequeData);

            // Assert
            assertThat(thrown).isInstanceOf(InvalidResponseException.class).hasMessageContaining("future");
        }

        @Test
        void aujourd_hui_depend_du_fuseau_de_l_horloge_injectee() {
            // Arrange : 2026-11-22 23:30 UTC = 2026-11-23 00:30 à Paris, le 23 n'est donc pas futur
            Clock paris = Clock.fixed(Instant.parse("2026-11-22T23:30:00Z"), ZoneId.of("Europe/Paris"));
            posAnswers("OK;2026-11-23;10.00\n");
            PosCommunicator communicator = communicator(paris);

            // Act
            ChequeData data = communicator.fetchChequeData();

            // Assert
            assertThat(data.date()).isEqualTo(LocalDate.of(2026, 11, 23));
        }
    }

    @Nested
    @DisplayName("Validation du montant")
    class AmountValidation {

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"0", "0.00", "-1", "-145.50", "-0.01"})
        void montant_nul_ou_negatif(String amount) {
            // Arrange
            posAnswers("OK;2026-11-16;" + amount + "\n");
            PosCommunicator communicator = communicator();

            // Act
            Throwable thrown = catchThrowable(communicator::fetchChequeData);

            // Assert
            assertThat(thrown).isInstanceOf(InvalidResponseException.class).hasMessageContaining("positive");
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"145.505", "0.001", "1.000"})
        void plus_de_deux_decimales(String amount) {
            // Arrange
            posAnswers("OK;2026-11-16;" + amount + "\n");
            PosCommunicator communicator = communicator();

            // Act
            Throwable thrown = catchThrowable(communicator::fetchChequeData);

            // Assert
            assertThat(thrown).isInstanceOf(InvalidResponseException.class).hasMessageContaining("2 decimals");
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"abc", "145,50", "145.50EUR", "1.2.3", ".", "1E+3", "1e2", "+145.50",
                " 145.50", "145.", ".50", "NaN", "Infinity"})
        void montant_non_numerique_ou_mal_forme(String amount) {
            // Arrange
            posAnswers("OK;2026-11-16;" + amount + "\n");
            PosCommunicator communicator = communicator();

            // Act
            Throwable thrown = catchThrowable(communicator::fetchChequeData);

            // Assert
            assertThat(thrown).isInstanceOf(InvalidResponseException.class).hasMessageContaining("Invalid cheque amount");
        }
    }

    @Nested
    @DisplayName("Timeout et erreurs de lecture")
    class TimeoutAndReadErrors {

        @Test
        void aucune_reponse_leve_PosTimeoutException() {
            // Arrange
            posAnswers("");
            PosCommunicator communicator = communicator();

            // Act
            Throwable thrown = catchThrowable(communicator::fetchChequeData);

            // Assert
            assertThat(thrown).isInstanceOf(PosTimeoutException.class).hasMessageContaining("3000 ms");
        }

        @Test
        void trame_sans_fin_de_ligne_leve_PosTimeoutException() {
            // Arrange
            posAnswers("OK;2026-11-16;145.50");
            PosCommunicator communicator = communicator();

            // Act
            Throwable thrown = catchThrowable(communicator::fetchChequeData);

            // Assert
            assertThat(thrown).isInstanceOf(PosTimeoutException.class);
        }

        @Test
        void le_timeout_est_mesure_avec_l_horloge_injectee() {
            // Arrange : départ à 0 ; contrôles à 1000, 3000 (= échéance, encore autorisé) puis 3001 (dépassé)
            Clock clock = mock(Clock.class);
            when(clock.millis()).thenReturn(0L, 1000L, 3000L, 3001L);
            posAnswers("");
            PosCommunicator communicator = communicator(clock);

            // Act
            Throwable thrown = catchThrowable(communicator::fetchChequeData);

            // Assert
            assertThat(thrown).isInstanceOf(PosTimeoutException.class);
            verify(channel, times(2)).readBytes(any(byte[].class), anyInt());
        }

        @Test
        void reponse_recue_juste_a_l_echeance_est_acceptee() {
            // Arrange
            Clock clock = mock(Clock.class);
            when(clock.millis()).thenReturn(0L, 3000L);
            when(clock.instant()).thenReturn(NOW);
            when(clock.getZone()).thenReturn(ZoneOffset.UTC);
            posAnswers("OK;2026-11-16;145.50\n");
            PosCommunicator communicator = communicator(clock);

            // Act
            ChequeData data = communicator.fetchChequeData();

            // Assert
            assertThat(data).isEqualTo(cheque("2026-11-16", "145.50"));
        }

        @Test
        void erreur_de_lecture_leve_PosCommunicationException() {
            // Arrange
            commandIsAccepted();
            when(channel.readBytes(any(byte[].class), anyInt())).thenReturn(-1);
            PosCommunicator communicator = communicator();

            // Act
            Throwable thrown = catchThrowable(communicator::fetchChequeData);

            // Assert
            assertThat(thrown).isInstanceOf(PosCommunicationException.class);
        }

        @Test
        void erreur_de_lecture_au_milieu_de_la_trame() {
            // Arrange
            commandIsAccepted();
            when(channel.readBytes(any(byte[].class), anyInt())).thenAnswer(inv -> {
                ((byte[]) inv.getArgument(0))[0] = 'O';
                return 1;
            }).thenReturn(-1);
            PosCommunicator communicator = communicator();

            // Act
            Throwable thrown = catchThrowable(communicator::fetchChequeData);

            // Assert
            assertThat(thrown).isInstanceOf(PosCommunicationException.class);
        }
    }

    @Nested
    @DisplayName("Cycle de vie")
    class Lifecycle {

        @Test
        void close_ferme_le_canal() {
            // Arrange
            PosCommunicator communicator = communicator();

            // Act
            communicator.close();

            // Assert
            verify(channel).close();
        }

        @Test
        void constructeur_par_defaut_utilise_un_timeout_de_3000_ms() {
            // Arrange
            posAnswers("");
            PosCommunicator communicator = new PosCommunicator(channel);

            // Act
            Throwable thrown = catchThrowable(communicator::fetchChequeData);

            // Assert
            assertThat(thrown).isInstanceOf(PosTimeoutException.class).hasMessageContaining("3000 ms");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1})
        void timeout_non_positif_refuse(int timeout) {
            // Arrange
            Clock clock = Clock.systemUTC();

            // Act
            Throwable thrown = catchThrowable(() -> new PosCommunicator(channel, timeout, clock));

            // Assert
            assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void timeout_de_1_ms_accepte() {
            // Arrange
            Clock clock = Clock.systemUTC();

            // Act
            Throwable thrown = catchThrowable(() -> new PosCommunicator(channel, 1, clock));

            // Assert
            assertThat(thrown).isNull();
        }

        @Test
        void canal_nul_refuse() {
            // Arrange
            Clock clock = Clock.systemUTC();

            // Act
            Throwable thrown = catchThrowable(() -> new PosCommunicator(null, TIMEOUT_MS, clock));

            // Assert
            assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("channel");
        }

        @Test
        void horloge_nulle_refusee() {
            // Arrange
            Clock clock = null;

            // Act
            Throwable thrown = catchThrowable(() -> new PosCommunicator(channel, TIMEOUT_MS, clock));

            // Assert
            assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("clock");
        }
    }

    @Nested
    @DisplayName("ChequeData")
    class ChequeDataTest {

        @Test
        void refuse_une_date_nulle() {
            // Arrange
            BigDecimal amount = BigDecimal.ONE;

            // Act
            Throwable thrown = catchThrowable(() -> new ChequeData(null, amount));

            // Assert
            assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("date");
        }

        @Test
        void refuse_un_montant_nul() {
            // Arrange
            LocalDate date = LocalDate.of(2026, 11, 16);

            // Act
            Throwable thrown = catchThrowable(() -> new ChequeData(date, null));

            // Assert
            assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("amount");
        }
    }
}
