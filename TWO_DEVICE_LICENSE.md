# ReplayX — uma key para dois dispositivos

A nova versão usa uma única key compartilhada. O primeiro aparelho que fizer login ocupa `slot1DeviceId`; o segundo ocupa `slot2DeviceId`. Depois que os dois slots estiverem preenchidos, qualquer terceiro aparelho recebe `Esta key já está vinculada a 2 dispositivos`.

O Sender e o Receiver usam o mesmo formato. A key começa a validade na primeira ativação e o contador de uso aparece como `1/2` ou `2/2` nos aplicativos. Não existe botão de reset no APK.

## Antes de publicar as Rules

Não apague a coleção `keys`. O aplicativo novo migra automaticamente o campo antigo `deviceId` para `slot1DeviceId` na primeira validação, preservando o primeiro aparelho já autorizado.

Mantenha os blocos existentes de `pair_codes`, `pairings` e `transfers`. Na aba **Firestore Database → Regras**, a opção mais simples é copiar o arquivo completo `firestore.rules.updated` e publicar. Ele já mantém as regras que você enviou e altera somente o bloco `keys`. Se preferir, use `firestore_keys_rules_snippet.txt` para substituir somente o bloco `match /keys/{keyId}`. Não apague as regras das outras coleções.

O bloco impede apagar, reduzir ou limpar os slots já ocupados e limita `devicesUsed` a 2. Como o aplicativo atual usa Firestore REST com API key pública e sem Firebase Auth, esse bloqueio limita a quantidade de vínculos, mas não é uma proteção absoluta contra um APK modificado. Para segurança máxima seria necessário mover a reserva dos slots para uma Cloud Function autenticada.

## Estrutura final de um documento keys

Os campos antigos `keyString`, `status`, `days`, `minutes`, `firstUsed`, `pausedAt` e `user` continuam. Os campos novos são `slot1DeviceId`, `slot1Type`, `slot1Model`, `slot1UsedAt`, `slot2DeviceId`, `slot2Type`, `slot2Model`, `slot2UsedAt` e `devicesUsed`.

Não é necessário preencher os campos novos manualmente. O primeiro e o segundo login dos APKs fazem isso automaticamente, desde que a regra de update esteja publicada.
