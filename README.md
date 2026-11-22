# Atelier 2 – Tests unitaires : module de communication POS

Module Java permettant à un lecteur de chèques de récupérer auprès de la caisse (POS), via une liaison
série, la date et le montant du chèque en cours.

- Sujet : [`atelier2.pdf`](atelier2.pdf)
- Prompt de génération (étape 2) : [`docs/prompt.md`](docs/prompt.md)

## Prérequis

- JDK 21+
- Maven 3.9+

## Protocole

| Sens            | Trame                                  |
|-----------------|----------------------------------------|
| Périphérique →  | `GET_CHEQUE_DATA\n`                    |
| ← Caisse (OK)   | `OK;YYYY-MM-DD;MONTANT\n` (ex. `OK;2026-11-16;145.50\n`) |
| ← Caisse (err.) | `ERR_NO_TRANSACTION\n`, `ERR_BUSY\n`   |

Toute trame malformée, vide ou l'absence de réponse (timeout) est une erreur.

## Étape 3 – Analyse des dépendances

Couplages forts relevés dans le code généré (commit « Étape 2 ») :

| Couplage | Où | Problème pour les tests |
|---|---|---|
| `SerialPort.getCommPort(portName)` + `openPort()` | constructeur | impossible d'instancier la classe sans vrai port COM |
| `serialPort.writeBytes / readBytes` | `sendCommand`, `readResponse` | impossible de simuler les trames de la caisse |
| `System.currentTimeMillis()` | `readResponse` (timeout) | un test de timeout attendrait réellement 3 s |
| `LocalDate.now()` | `parseDate` (date future) | résultat des tests dépendant du jour d'exécution |

Refactorisation :

- interface `SerialChannel` (`writeBytes`, `readBytes`, `close`) injectée par le constructeur ;
- adaptateur `JSerialCommChannel` (seule classe dépendant de jSerialComm, ouverture/configuration du port) ;
- `java.time.Clock` injectée pour le timeout et la validation de la date.

```java
try (PosCommunicator pos = new PosCommunicator(JSerialCommChannel.open("COM3", 3000))) {
    ChequeData data = pos.fetchChequeData();
}
```
