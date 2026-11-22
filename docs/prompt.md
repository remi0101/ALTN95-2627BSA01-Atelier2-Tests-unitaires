# Étape 2 – Prompt utilisé pour générer le module de communication POS

Prompt soumis tel quel au LLM :

---

> Tu es un développeur Java senior. Génère une classe Java 21 `PosCommunicator` (package `pos`)
> qui permet à un périphérique de lecture de chèques de récupérer les données du chèque en cours auprès
> d'une caisse (POS) via une liaison série (port COM), en utilisant la bibliothèque `com.fazecast:jSerialComm`.
>
> **Protocole (octets ASCII, chaque trame se termine par `\n`) :**
> - Requête envoyée par le périphérique : `GET_CHEQUE_DATA\n`
> - Réponse nominale de la caisse : `OK;YYYY-MM-DD;MONTANT\n` (exemple : `OK;2026-11-16;145.50\n`)
> - Réponses d'erreur possibles :
>   - `ERR_NO_TRANSACTION\n` : aucun paiement par chèque en cours
>   - `ERR_BUSY\n` : caisse occupée
>   - toute trame malformée, vide, ou absence de réponse (timeout) doit être traitée comme une erreur.
>
> **Exigences :**
> 1. Le constructeur prend le nom du port (ex. `COM3`) et un timeout de lecture optionnel en millisecondes
>    (3000 ms par défaut), configure le port (9600 bauds, 8N1) et l'ouvre.
> 2. Une méthode publique `ChequeData fetchChequeData()` qui :
>    - envoie la commande,
>    - lit la réponse jusqu'au `\n` en respectant le timeout,
>    - valide les données : date réelle au format `YYYY-MM-DD` et non future, montant décimal strictement
>      positif avec au plus 2 décimales,
>    - retourne un `record ChequeData(LocalDate date, BigDecimal amount)`,
>    - ou lève une exception métier explicite (non vérifiée) : une hiérarchie dont la racine est
>      `PosException`, avec des sous-classes distinctes pour « pas de transaction », « caisse occupée »,
>      « timeout », « réponse invalide » et « erreur de communication ».
> 3. La classe implémente `AutoCloseable` pour fermer le port.
> 4. Code complet, compilable, avec Javadoc, une classe publique par fichier. Pas de tests.

---

Le code produit a été intégré **sans modification** dans `src/main/java/pos` (commit « Étape 2 »).
