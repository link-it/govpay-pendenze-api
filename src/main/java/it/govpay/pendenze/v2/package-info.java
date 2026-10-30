/**
 * Wrapper verso il contratto REST v2 ({@code govpay-api-pendenze-v2.yaml}), per i client esterni
 * che non migrano a v3 nell'immediato. Tutta la logica applicativa resta sulle funzioni di
 * common-pendenze (v3): questo package si limita a tradurre richieste/risposte, mai a duplicare
 * regole di dominio.
 *
 * <p>Isolato deliberatamente da {@code it.govpay.pendenze.pendenza}/{@code .posizionedebitoria}
 * (v3): nessuna classe v3 dipende da questo package. Da rimuovere per intero quando i client v2
 * non ne avranno piu' bisogno, insieme a {@code govpay-api-pendenze-v2.yaml}, all'esecuzione
 * {@code generate-pendenze-api-v2} del codegen in {@code pom.xml} e alla property
 * {@code govpay.pendenze.api.v2.base-path}.
 */
@Deprecated(forRemoval = true)
package it.govpay.pendenze.v2;
