package pos;

import org.junit.jupiter.api.BeforeEach;
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
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Timeout(5)
class PosCommunicatorTest {

    /** "Today" for every test: 2026-11-22 10:00 UTC. */
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final int TIMEOUT_MS = 3000;
    private static final byte[] EXPECTED_COMMAND = "GET_CHEQUE_DATA\n".getBytes(StandardCharsets.US_ASCII);

    @Mock
    private SerialChannel channel;

    private PosCommunicator communicator;

    /** Bytes the stubbed POS will send; {@code null} entries simulate a read returning 0 (nothing received). */
    private final Deque<Integer> incoming = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        communicator = new PosCommunicator(channel, TIMEOUT_MS, new SteppingClock(NOW, Duration.ofMillis(1), ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------ helpers

    /** The POS accepts the whole command when it is written. */
    private void commandIsSent() {
        when(channel.writeBytes(any(byte[].class), anyInt())).thenAnswer(inv -> inv.getArgument(1));
    }

    /** Stubs the POS so that it answers {@code frame} byte by byte, then stays silent. */
    private void posAnswers(String frame) {
        commandIsSent();
        for (byte b : frame.getBytes(StandardCharsets.ISO_8859_1)) {
            incoming.add(b & 0xFF);
        }
        when(channel.readBytes(any(byte[].class), anyInt())).thenAnswer(inv -> {
            Integer next = incoming.poll();
            if (next == null) {
                return 0;
            }
            byte[] buffer = inv.getArgument(0);
            buffer[0] = (byte) next.intValue();
            return 1;
        });
    }

    private ChequeData fetch(String frame) {
        posAnswers(frame);
        return communicator.fetchChequeData();
    }

    private void assertInvalid(String frame) {
        posAnswers(frame);
        assertThatThrownBy(communicator::fetchChequeData).isInstanceOf(InvalidResponseException.class);
    }

    // ------------------------------------------------------------------ tests

    @Nested
    @DisplayName("Envoi de la commande")
    class SendCommand {

        @Test
        void envoie_exactement_GET_CHEQUE_DATA_en_ASCII() {
            fetch("OK;2026-11-16;145.50\n");

            ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
            verify(channel).writeBytes(captor.capture(), org.mockito.ArgumentMatchers.eq(EXPECTED_COMMAND.length));
            assertThat(captor.getValue()).isEqualTo(EXPECTED_COMMAND);
        }

        @Test
        void envoie_la_commande_avant_de_lire_la_reponse() {
            fetch("OK;2026-11-16;145.50\n");

            InOrder order = inOrder(channel);
            order.verify(channel).writeBytes(any(byte[].class), anyInt());
            order.verify(channel, atLeastOnce()).readBytes(any(byte[].class), anyInt());
        }

        @ParameterizedTest(name = "{0} octets écrits")
        @ValueSource(ints = {-1, 0, 5, 15})
        void ecriture_incomplete_leve_une_erreur_de_communication_sans_lire(int written) {
            when(channel.writeBytes(any(byte[].class), anyInt())).thenReturn(written);

            assertThatThrownBy(communicator::fetchChequeData)
                    .isInstanceOf(PosCommunicationException.class)
                    .hasMessageContaining(written + "/16");
            verify(channel, never()).readBytes(any(byte[].class), anyInt());
        }
    }

    @Nested
    @DisplayName("Réponse nominale")
    class Nominal {

        @Test
        void exemple_de_la_specification() {
            ChequeData data = fetch("OK;2026-11-16;145.50\n");

            assertThat(data.date()).isEqualTo(LocalDate.of(2026, 11, 16));
            assertThat(data.amount()).isEqualTo(new BigDecimal("145.50"));
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
            ChequeData data = fetch("OK;2026-11-16;" + raw + "\n");

            assertThat(data.amount()).isEqualTo(new BigDecimal(expected));
            assertThat(data.amount().scale()).isEqualTo(2);
        }

        @Test
        void accepte_une_fin_de_ligne_CRLF() {
            ChequeData data = fetch("OK;2026-11-16;145.50\r\n");

            assertThat(data).isEqualTo(new ChequeData(LocalDate.of(2026, 11, 16), new BigDecimal("145.50")));
        }

        @Test
        void tolere_des_silences_entre_les_octets_avant_le_timeout() {
            commandIsSent();
            byte[] frame = "OK;2026-11-16;145.50\n".getBytes(StandardCharsets.US_ASCII);
            int[] calls = {0};
            // la caisse envoie par paquets : une lecture vide (0) s'intercale entre chaque octet
            when(channel.readBytes(any(byte[].class), anyInt())).thenAnswer(inv -> {
                int call = calls[0]++;
                if (call % 2 == 0) {
                    return 0;
                }
                ((byte[]) inv.getArgument(0))[0] = frame[call / 2];
                return 1;
            });

            assertThat(communicator.fetchChequeData().amount()).isEqualTo(new BigDecimal("145.50"));
        }

        @Test
        void ne_lit_pas_au_dela_de_la_fin_de_trame() {
            fetch("OK;2026-11-16;145.50\nOK;2026-11-17;10.00\n");

            assertThat(incoming).hasSize("OK;2026-11-17;10.00\n".length());
        }

        @Test
        void deux_appels_successifs_lisent_deux_trames() {
            posAnswers("OK;2026-11-16;145.50\nOK;2026-11-17;10.00\n");

            assertThat(communicator.fetchChequeData().date()).isEqualTo(LocalDate.of(2026, 11, 16));
            assertThat(communicator.fetchChequeData().date()).isEqualTo(LocalDate.of(2026, 11, 17));
        }
    }

    @Nested
    @DisplayName("Réponses d'erreur de la caisse")
    class PosErrors {

        @Test
        void ERR_NO_TRANSACTION_leve_NoTransactionException() {
            posAnswers("ERR_NO_TRANSACTION\n");

            assertThatThrownBy(communicator::fetchChequeData).isInstanceOf(NoTransactionException.class);
        }

        @Test
        void ERR_BUSY_leve_PosBusyException() {
            posAnswers("ERR_BUSY\n");

            assertThatThrownBy(communicator::fetchChequeData).isInstanceOf(PosBusyException.class);
        }

        @Test
        void toutes_les_exceptions_metier_derivent_de_PosException() {
            posAnswers("ERR_BUSY\n");

            assertThatThrownBy(communicator::fetchChequeData).isInstanceOf(PosException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"ERR_UNKNOWN\n", "ERR_BUSY_\n", "err_busy\n", "ERR_NO_TRANSACTION;X\n"})
        void code_erreur_inconnu_est_une_trame_invalide(String frame) {
            assertInvalid(frame);
        }
    }

    @Nested
    @DisplayName("Trames malformées ou corrompues")
    class Malformed {

        @ParameterizedTest
        @ValueSource(strings = {"\n", "\r\n", "   \n"})
        void trame_vide(String frame) {
            assertInvalid(frame);
        }

        @ParameterizedTest
        @ValueSource(strings = {
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
            assertInvalid(frame);
        }

        @Test
        void trame_trop_longue_est_rejetee_sans_lire_indefiniment() {
            posAnswers("X".repeat(1000) + "\n");

            assertThatThrownBy(communicator::fetchChequeData)
                    .isInstanceOf(InvalidResponseException.class)
                    .hasMessageContaining("exceeds");
            assertThat(incoming).isNotEmpty();
        }

        @Test
        void trame_de_256_caracteres_n_est_pas_trop_longue() {
            posAnswers("X".repeat(256) + "\n");

            assertThatThrownBy(communicator::fetchChequeData)
                    .isInstanceOf(InvalidResponseException.class)
                    .hasMessageStartingWith("Malformed response");
        }

        @Test
        void trame_de_257_caracteres_est_trop_longue() {
            posAnswers("X".repeat(257) + "\n");

            assertThatThrownBy(communicator::fetchChequeData)
                    .isInstanceOf(InvalidResponseException.class)
                    .hasMessageContaining("exceeds 256");
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
            assertThat(fetch("OK;" + date + ";10.00\n").date()).isEqualTo(LocalDate.parse(date));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "2025-02-29", // pas bissextile
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
            posAnswers("OK;" + date + ";10.00\n");

            assertThatThrownBy(communicator::fetchChequeData)
                    .isInstanceOf(InvalidResponseException.class)
                    .hasMessageContaining("date");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"2026-11-23", "2027-01-01", "9999-12-31"})
        void date_future_rejetee(String date) {
            posAnswers("OK;" + date + ";10.00\n");

            assertThatThrownBy(communicator::fetchChequeData)
                    .isInstanceOf(InvalidResponseException.class)
                    .hasMessageContaining("future");
        }

        @Test
        void aujourd_hui_depend_du_fuseau_de_l_horloge_injectee() {
            // 2026-11-22 23:30 UTC = 2026-11-23 00:30 à Paris : le 23 n'est pas futur
            Clock paris = Clock.fixed(Instant.parse("2026-11-22T23:30:00Z"), java.time.ZoneId.of("Europe/Paris"));
            communicator = new PosCommunicator(channel, TIMEOUT_MS, paris);

            assertThat(fetch("OK;2026-11-23;10.00\n").date()).isEqualTo(LocalDate.of(2026, 11, 23));
        }
    }

    @Nested
    @DisplayName("Validation du montant")
    class AmountValidation {

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"0", "0.00", "-1", "-145.50", "-0.01"})
        void montant_nul_ou_negatif(String amount) {
            posAnswers("OK;2026-11-16;" + amount + "\n");

            assertThatThrownBy(communicator::fetchChequeData)
                    .isInstanceOf(InvalidResponseException.class)
                    .hasMessageContaining("positive");
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"145.505", "0.001", "1.000"})
        void plus_de_deux_decimales(String amount) {
            posAnswers("OK;2026-11-16;" + amount + "\n");

            assertThatThrownBy(communicator::fetchChequeData)
                    .isInstanceOf(InvalidResponseException.class)
                    .hasMessageContaining("2 decimals");
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"abc", "145,50", "145.50EUR", "1.2.3", ".", "1E+3", "1e2", "+145.50", " 145.50", "145.", ".50", "NaN", "Infinity"})
        void montant_non_numerique_ou_mal_forme(String amount) {
            assertInvalid("OK;2026-11-16;" + amount + "\n");
        }
    }

    @Nested
    @DisplayName("Timeout et erreurs de lecture")
    class TimeoutAndReadErrors {

        @Test
        void aucune_reponse_leve_PosTimeoutException() {
            posAnswers("");

            assertThatThrownBy(communicator::fetchChequeData)
                    .isInstanceOf(PosTimeoutException.class)
                    .hasMessageContaining("3000 ms");
        }

        @Test
        void trame_sans_fin_de_ligne_leve_PosTimeoutException() {
            posAnswers("OK;2026-11-16;145.50");

            assertThatThrownBy(communicator::fetchChequeData).isInstanceOf(PosTimeoutException.class);
        }

        @Test
        void le_timeout_est_mesure_avec_l_horloge_injectee() {
            Clock clock = mock(Clock.class);
            // départ à 0 ; lectures vides à 1000, 3000 (= échéance, encore autorisé) puis 3001 (dépassé)
            when(clock.millis()).thenReturn(0L, 1000L, 3000L, 3001L);
            communicator = new PosCommunicator(channel, TIMEOUT_MS, clock);
            posAnswers("");

            assertThatThrownBy(communicator::fetchChequeData).isInstanceOf(PosTimeoutException.class);
            verify(channel, org.mockito.Mockito.times(2)).readBytes(any(byte[].class), anyInt());
        }

        @Test
        void reponse_recue_juste_a_l_echeance_est_acceptee() {
            Clock clock = mock(Clock.class);
            when(clock.millis()).thenReturn(0L, 3000L);
            when(clock.instant()).thenReturn(NOW);
            when(clock.getZone()).thenReturn(ZoneOffset.UTC);
            communicator = new PosCommunicator(channel, TIMEOUT_MS, clock);

            assertThat(fetch("OK;2026-11-16;145.50\n").amount()).isEqualTo(new BigDecimal("145.50"));
        }

        @Test
        void erreur_de_lecture_leve_PosCommunicationException() {
            commandIsSent();
            when(channel.readBytes(any(byte[].class), anyInt())).thenReturn(-1);

            assertThatThrownBy(communicator::fetchChequeData).isInstanceOf(PosCommunicationException.class);
        }

        @Test
        void erreur_de_lecture_au_milieu_de_la_trame() {
            commandIsSent();
            when(channel.readBytes(any(byte[].class), anyInt())).thenAnswer(inv -> {
                ((byte[]) inv.getArgument(0))[0] = 'O';
                return 1;
            }).thenReturn(-1);

            assertThatThrownBy(communicator::fetchChequeData).isInstanceOf(PosCommunicationException.class);
        }
    }

    @Nested
    @DisplayName("Cycle de vie")
    class Lifecycle {

        @Test
        void close_ferme_le_canal() {
            communicator.close();

            verify(channel).close();
        }

        @Test
        void constructeur_par_defaut_utilise_un_timeout_de_3000_ms() {
            communicator = new PosCommunicator(channel);
            posAnswers("");

            assertThatThrownBy(communicator::fetchChequeData)
                    .isInstanceOf(PosTimeoutException.class)
                    .hasMessageContaining("3000 ms");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1})
        void timeout_non_positif_refuse(int timeout) {
            Clock clock = Clock.systemUTC();
            assertThatThrownBy(() -> new PosCommunicator(channel, timeout, clock))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void timeout_de_1_ms_accepte() {
            assertThatCode(() -> new PosCommunicator(channel, 1, Clock.systemUTC())).doesNotThrowAnyException();
        }

        @Test
        void dependances_nulles_refusees() {
            Clock clock = Clock.systemUTC();
            assertThatThrownBy(() -> new PosCommunicator(null, TIMEOUT_MS, clock))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("channel");
            assertThatThrownBy(() -> new PosCommunicator(channel, TIMEOUT_MS, null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("clock");
        }
    }

    @Nested
    @DisplayName("ChequeData")
    class ChequeDataTest {

        @Test
        void refuse_les_champs_nuls() {
            assertThatThrownBy(() -> new ChequeData(null, BigDecimal.ONE)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ChequeData(LocalDate.now(), null)).isInstanceOf(NullPointerException.class);
        }
    }
}
