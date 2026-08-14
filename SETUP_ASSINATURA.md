# Configurar assinatura de release do Enviador

## Secrets (Settings → Secrets and variables → Actions)

| Nome                    | Valor                                   |
|--------------------------|-------------------------------------------|
| `RXS_KEYSTORE_B64`       | conteúdo de `replayx-sender-release.jks.b64` |
| `RXS_KEYSTORE_PASSWORD`  | (te mandei junto)                         |
| `RXS_KEY_ALIAS`          | `replayxsender`                           |
| `RXS_KEY_PASSWORD`       | igual a `RXS_KEYSTORE_PASSWORD`           |

Guarda o `.jks` em local seguro, nunca sobe ele no repositório (já protegido
no `.gitignore`). Depois de cadastrar os 4 secrets, dá push e o Actions
builda `assembleRelease` automaticamente, assinado.

## O que já vem pronto nesse projeto
- Root (emulador) com fallback pra Shizuku, tentando os dois automaticamente.
- Pareamento por código de 6 caracteres, uso único, expira em 10 min.
- Transferência do replay via Firestore (sem Storage, sem Cloud Function,
  sem custo) — o arquivo vira texto e é enviado em pedaços.
- Build release assinado, `debuggable=false`, verificação de assinatura
  em runtime, `FLAG_SECURE`.
