# Mutant survivant : longueur de lecture `readBytes(buffer, 1)` non vérifiée

**Outil :** PIT 1.17.4 (`mvn test-compile org.pitest:pitest-maven:mutationCoverage`, mutateurs `ALL`)
**Résultat :** 139 mutants, 138 tués, **1 survivant**.

| Classe | Méthode | Ligne | Mutateur | Mutation |
|---|---|---|---|---|
| `pos.PosCommunicator` | `readResponse` | 115 | `InlineConstantMutator` | `channel.readBytes(buffer, 1)` → `channel.readBytes(buffer, 0)` |

**Analyse :** les stubs de `SerialChannel` utilisent `anyInt()` pour la longueur demandée : aucun test ne
vérifie que le module demande bien **1 octet** par lecture. Avec un vrai port série, demander 0 octet ne
renverrait jamais de données et la lecture finirait systématiquement en timeout. Mutant **pertinent**.

**Correction attendue :** vérifier dans un test que chaque appel à `readBytes` demande exactement 1 octet
dans un tampon d'au moins 1 octet.
