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
