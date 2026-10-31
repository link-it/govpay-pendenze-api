<p align="center">
<img src="https://www.link.it/wp-content/uploads/2025/01/logo-govpay.svg" alt="GovPay Logo" width="200"/>
</p>

# GovPay - Porta di accesso al sistema pagoPA - Pendenze API

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=link-it_govpay-pendenze-api&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=link-it_govpay-pendenze-api)
[![Docker Hub](https://img.shields.io/docker/v/linkitaly/govpay-pendenze-api?label=Docker%20Hub&logo=docker)](https://hub.docker.com/r/linkitaly/govpay-pendenze-api)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://raw.githubusercontent.com/link-it/govpay-pendenze-api/main/LICENSE)
[![Spring Boot](https://img.shields.io/badge/dynamic/xml?url=https%3A%2F%2Fraw.githubusercontent.com%2Flink-it%2Fgovpay-bom%2Fmain%2Fpom.xml&query=%2F%2F*%5Blocal-name()%3D'spring-boot.version'%5D&label=Spring%20Boot&color=brightgreen)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)

API REST con cui gli applicativi dell'Ente caricano, aggiornano e consultano le posizioni debitorie in GovPay: API Pendenze v3 e, per i client non ancora migrati, un wrapper compatibile con l'API Pendenze v2 del core.

## Sommario

Il servizio espone due versioni dell'API Pendenze sullo stesso processo, con le stesse credenziali:

- **v3** ([src/main/resources/govpay-api-pendenze-v3.yaml](src/main/resources/govpay-api-pendenze-v3.yaml)): modello nativo a posizioni debitorie, opzioni di pagamento e pendenze;
- **v2** ([src/main/resources/govpay-api-pendenze-v2.yaml](src/main/resources/govpay-api-pendenze-v2.yaml)): stessi path e messaggi dell'API Pendenze v2 del monolite GovPay, tradotti sul modello v3.

Le operazioni e il modello dei dati sono quelli della libreria [govpay-common-pendenze](https://github.com/link-it/govpay-common-pendenze); i path sono relativi al base path della versione (vedi [Base path delle API](#base-path-delle-api)).

### API v3

| Operazione | Metodo e path | Descrizione |
|------------|---------------|-------------|
| `findPosizioniDebitorie` | `GET /posizioni-debitorie/{idA2A}` | Elenco delle posizioni debitorie di un debitore |
| `addPosizioneDebitoria` | `POST /posizioni-debitorie/{idA2A}` | Creazione di una posizione debitoria |
| `getPosizioneDebitoria` | `GET /posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}` | Dettaglio di una posizione debitoria |
| `updatePosizioneDebitoria` | `PATCH /posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}` | Aggiornamento di una posizione debitoria |
| `getStampaPosizioneDebitoria` | `GET /posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/stampa` | Avviso di pagamento in PDF |
| `addOpzionePagamento` | `POST /posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/opzioni-pagamento` | Aggiunta di un'opzione di pagamento |
| `updateOpzionePagamento` | `PATCH /posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/opzioni-pagamento/{idOpzionePagamento}` | Aggiornamento dello stato di un'opzione di pagamento |
| `addPendenza` | `POST /posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/opzioni-pagamento/{idOpzionePagamento}/pendenze` | Aggiunta di una pendenza a un'opzione di pagamento |
| `findPendenze` | `GET /pendenze/{idA2A}` | Ricerca delle pendenze per numero avviso |
| `getPendenza` | `GET /pendenze/{idA2A}/{idPendenza}` | Dettaglio di una pendenza |
| `updatePendenza` | `PUT /pendenze/{idA2A}/{idPendenza}` | Aggiornamento di una pendenza |
| `getStampaPendenza` | `POST /pendenze/{idA2A}/{idPendenza}/stampa` | Avviso di una pendenza in PDF |
| `findRicevutePendenza` | `GET /pendenze/{idA2A}/{idPendenza}/ricevute` | Ricevute di pagamento di una pendenza |
| `getRicevutaPendenza` | `GET /pendenze/{idA2A}/{idPendenza}/ricevute/{iur}` | Dettaglio di una ricevuta |
| `getStampaRicevutaPendenza` | `GET /pendenze/{idA2A}/{idPendenza}/ricevute/{iur}/stampa` | Ricevuta in PDF |
| `findRendicontazioniPendenza` | `GET /pendenze/{idA2A}/{idPendenza}/rendicontazioni` | Rendicontazioni di una pendenza |

### API v2 (retrocompatibilità)

| Operazione | Metodo e path | Descrizione |
|------------|---------------|-------------|
| `findPendenze` | `GET /pendenze` | Elenco delle pendenze |
| `addPendenza` | `PUT /pendenze/{idA2A}/{idPendenza}` | Inserimento o aggiornamento di una pendenza |
| `updatePendenza` | `PATCH /pendenze/{idA2A}/{idPendenza}` | Aggiornamento di uno o più campi |
| `getPendenza` | `GET /pendenze/{idA2A}/{idPendenza}` | Dettaglio di una pendenza |
| `getPendenzaByAvviso` | `GET /pendenze/byAvviso/{idDominio}/{numeroAvviso}` | Dettaglio di una pendenza per numero avviso |
| `getAvviso` | `GET /avvisi/{idDominio}/{numeroAvviso}` | Avviso di pagamento (JSON o PDF) |
| `getAvvisiDocumento` | `GET /documenti/{idDominio}/{numeroDocumento}/avvisi` | Avvisi di un documento (PDF) |
| `findRpp` | `GET /rpp` | Elenco delle richieste di pagamento |
| `getRpp` | `GET /rpp/{idDominio}/{iuv}/{ccp}` | Dettaglio di una richiesta di pagamento (anche con `ccp` = `n/a`) |
| `getRpt` | `GET /rpp/{idDominio}/{iuv}/{ccp}/rpt` | Richiesta di pagamento pagoPA |
| `getRt` | `GET /rpp/{idDominio}/{iuv}/{ccp}/rt` | Ricevuta pagoPA (XML, JSON o PDF) |
| `getProfilo` | `GET /profilo` | Profilo e ACL dell'utenza chiamante |

`GET /allegati/{id}` (`getAllegatoPendenza`) è nella specifica v2 ma non è implementato: risponde `501 Not Implemented`.

Una voce di tipo bollo (`tipoBollo`, `hashDocumento`, `provinciaResidenza`) va caricata con `tipoContabilita` e `codiceContabilita`, che ne formano la tassonomia: senza, la richiesta è rifiutata con `400`.

### Amministrazione

| Metodo e path | Descrizione |
|---------------|-------------|
| `GET /admin/logging/loggers` | Livelli di log correnti |
| `GET /admin/logging/loggers/dinamici` | Livelli impostati da database (riga `log_level` della tabella `configurazione`) |
| `PUT /admin/logging/loggers/{logger}` | Imposta il livello di un logger sul nodo |
| `DELETE /admin/logging/loggers/{logger}` | Ripristina il livello di un logger |
| `DELETE /admin/logging/loggers` | Ripristina tutti i livelli |
| `POST /admin/logging/loggers/refresh` | Rilegge subito i livelli dal database |

Questi path non hanno base path. La documentazione interattiva generata dai controller è su `/swagger-ui.html` e `/v3/api-docs`, senza autenticazione.

### Autenticazione e autorizzazione

- **Autenticazione**: HTTP Basic, verificata con [govpay-common-auth](https://github.com/link-it/govpay-common-auth) sulle utenze di GovPay (tabella `utenze`, password con hash SHA-512 crypt; in lettura anche MD5 crypt). L'utenza deve essere abilitata ed essere quella di un'applicazione (`applicazioni.id_utenza`).
- **Applicazione**: l'`idA2A` nel path deve essere il codice dell'applicazione autenticata, altrimenti la risposta è `403`.
- **ACL** (tabella `acl`, dirette sull'utenza o tramite i ruoli in `utenze.ruoli`): servizio `API Pendenze` con diritto `R` per le letture e `W` per le scritture; servizio `Configurazione e manutenzione` per gli endpoint `/admin/logging`.
- **Domini e tipi pendenza**: nel caricamento di una pendenza con l'API v2, un'applicazione non `trusted` deve essere autorizzata sul dominio e sul tipo pendenza (`utenze_domini`, `utenze_tipo_vers`, o i flag "tutti" dell'utenza).

Gli errori sono restituiti in formato `application/problem+json`; gli errori della richiesta rilevati prima dell'operazione hanno il codice corrispondente (path inesistente `404`, metodo non ammesso `405` con l'header `Allow`, `Content-Type` non supportato `415`, parametro mancante o non valido `400`).

## Integrazione con GovPay

| Componente | Quando serve | Obbligatorio | Ruolo |
|------------|--------------|--------------|-------|
| Database di GovPay | runtime | sì | Legge e scrive le tabelle dell'aggregato pendenza (`versamenti`, `singoli_versamenti`, `documenti`, `opzioni_pagamento`, `soggetti_debitori`, `allegati`); legge anagrafica e configurazione (`applicazioni`, `domini`, `uo`, `iban_accredito`, `tipi_versamento`, `tipi_vers_domini`, `tipi_tributo`, `tributi`, `intermediari`, `stazioni`, `connettori`, `configurazione`), utenze e autorizzazioni (`utenze`, `acl`, `utenze_domini`, `utenze_tipo_vers`) e pagamenti (`rpt`, `pagamenti`, `fr`, `rendicontazioni`, `notifiche`). Richiede lo schema del modello v3 introdotto con la patch 3.11.0 di GovPay: con `ddl-auto=validate` (default) il servizio non parte se lo schema non corrisponde alle entità, e non crea né modifica lo schema |
| [govpay-bom](https://github.com/link-it/govpay-bom) | build | sì | Parent Maven: versioni delle dipendenze e dei plugin |
| [govpay-common-pendenze](https://github.com/link-it/govpay-common-pendenze) | build | sì | Modello, entità e servizi dell'aggregato pendenza, generazione degli IUV |
| [govpay-common](https://github.com/link-it/govpay-common) | build | sì | Libreria comune (dipendenza di `govpay-common-pendenze`): entità di anagrafica, connettori, client GDE, transaction id e livelli di log da database |
| [govpay-common-auth](https://github.com/link-it/govpay-common-auth) | build | sì | Catena di sicurezza condivisa: autenticazione BASIC e path pubblici |
| [govpay-stampe-api](https://github.com/link-it/govpay-stampe-api) | runtime | no | Produce i PDF di avvisi e ricevute (`POST /standard`, `/cds_violation`, `/receipt`). Senza `app.stampe.base-url` le stampe rispondono `503`, le altre operazioni funzionano |
| [govpay-gde-api](https://github.com/link-it/govpay-gde-api) | runtime | no | Riceve un evento per ogni richiesta API (`POST /eventi`), se il servizio GDE è configurato e abilitato in GovPay (tabella `configurazione` e connettore `govpay_gde_api`). Un GDE non raggiungibile non blocca le richieste |
| Applicativi dell'Ente | runtime | - | Client dell'API: ciascuno si autentica con l'utenza della propria applicazione GovPay |

I dati caricati con questa API sono gli stessi del monolite GovPay: pagamento, notifiche e rendicontazione restano a carico degli altri componenti. Per il ruolo delle API Pendenze nell'integrazione dell'Ente vedi la [documentazione di GovPay](https://govpay.readthedocs.io/it/3.10.x/integrazione/index.html).

## Compilazione

Requisiti: JDK 21 e Maven 3.8.1 o successivo.

```bash
mvn clean install -P jar     # fat jar, per esecuzione standalone e container (profilo di default)
mvn clean install -P war     # war, per il deploy su application server
```

Il profilo sceglie il packaging; il risultato è in `target/govpay-pendenze-api.jar` o `target/govpay-pendenze-api.war`. La build genera gli stub server dalle due specifiche OpenAPI e il client di govpay-stampe-api, esegue i test (H2 in memoria) e l'analisi OWASP Dependency-Check delle dipendenze; per escludere l'analisi OWASP, per esempio in sviluppo:

```bash
mvn clean install -P jar -Dowasp.phase=none
```

Nessuno dei due artefatti include i driver JDBC: vedi [Esecuzione](#esecuzione).

## Configurazione

Le proprietà si impostano in `application.properties`, da riga di comando (`-D<proprietà>=<valore>`) o con variabili d'ambiente. Molte proprietà leggono una variabile dedicata, indicata nella colonna "Variabile"; per le altre valgono le variabili equivalenti di Spring Boot (es. `app.gde.async` → `APP_GDE_ASYNC`), che funzionano anche per quelle con variabile dedicata (es. `SPRING_DATASOURCE_URL`).

### Base path delle API

| Proprietà | Variabile | Default | Descrizione |
|-----------|-----------|---------|-------------|
| `govpay.pendenze.api.v3.base-path` | `PENDENZE_API_V3_BASE_PATH` | `/v3` | Prefisso dei path dell'API v3 |
| `govpay.pendenze.api.v2.base-path` | `PENDENZE_API_V2_BASE_PATH` | `/v2` | Prefisso dei path dell'API v2 |

Con i default l'API v3 risponde su `/v3/...` e la v2 su `/v2/...`. Le due versioni sono sempre attive insieme e hanno path in comune (`/pendenze/{idA2A}/{idPendenza}`): se si cambiano i base path, almeno uno dei due deve restare valorizzato, altrimenti il servizio non parte (`Ambiguous mapping`). Per esporre i path del monolite senza un reverse proxy si usano, per esempio, `/govpay/backend/api/pendenze/rs/basic/v2` e `/govpay/backend/api/pendenze/rs/basic/v3`.

### Wrapper v2

| Proprietà | Variabile | Default | Descrizione |
|-----------|-----------|---------|-------------|
| `govpay.pendenze.v2.conversione-pagopa-v1.enable` | `PENDENZE_V2_CONVERSIONE_PAGOPA_V1_ENABLE` | `false` | Restituisce RPT e RT dei pagamenti più recenti (`ctReceipt`, `ctReceiptV2`) nel vecchio formato RPT/RT v1, per i client legati al formato storico. Con `false` sono restituite nel formato nativo |
| `govpay.pendenze.v2.giorni-validita-pendenza-senza-data` | `PENDENZE_V2_GIORNI_VALIDITA_PENDENZA_SENZA_DATA` | - | Giorni di validità usati come scadenza quando una RPT viene ricostruita dalla RT e la pendenza non ha né data di validità né data di scadenza; senza valore la scadenza è il 31/12/2999 |

### Server e API

| Proprietà | Variabile | Default | Descrizione |
|-----------|-----------|---------|-------------|
| `server.port` | `SERVER_PORT` | `8080` | Porta HTTP del servizio |
| `spring.mvc.date-format` | - | `yyyy-MM-dd` | Formato delle date nei parametri |
| `spring.mvc.datetime-format` | - | `yyyy-MM-dd'T'HH:mm:ssZZZ` | Formato di data e ora nei parametri |
| `spring.mvc.throw-exception-if-no-handler-found` | - | `true` | Risponde con un errore applicativo sui path inesistenti |
| `spring.jackson.default-property-inclusion` | - | `non_absent` | Esclude dalle risposte JSON i campi nulli o assenti |
| `server.error.whitelabel.enabled` | - | `true` | Pagina di errore di default di Spring Boot |
| `spring.main.banner-mode` | - | `off` | Banner di Spring Boot all'avvio |
| `springdoc.swagger-ui.path` | - | `/swagger-ui.html` | Path della Swagger UI |
| `springdoc.swagger-ui.operations-sorter` | - | `method` | Ordinamento delle operazioni nella Swagger UI |
| `springdoc.swagger-ui.tags-sorter` | - | `alpha` | Ordinamento dei tag nella Swagger UI |

### Sicurezza

Proprietà di [govpay-common-auth](https://github.com/link-it/govpay-common-auth#configurazione) impostate dal servizio:

| Proprietà | Default | Descrizione |
|-----------|---------|-------------|
| `govpay.auth.basic.enabled` | `true` | Autenticazione HTTP Basic sulle utenze di GovPay |
| `govpay.auth.public-chain.enabled` | `true` | Abilita i path accessibili senza autenticazione |
| `govpay.auth.public-chain.permit-all-paths[0].path` | `/swagger-ui/**` | Swagger UI |
| `govpay.auth.public-chain.permit-all-paths[1].path` | `/swagger-ui.html` | Swagger UI |
| `govpay.auth.public-chain.permit-all-paths[2].path` | `/v3/api-docs/**` | Specifica OpenAPI generata |
| `govpay.auth.public-chain.permit-all-paths[3].path` | `/actuator/prometheus` | Metriche Prometheus |
| `govpay.auth.public-chain.permit-all-paths[3].methods[0]` | `GET` | Metodo ammesso senza autenticazione |
| `govpay.auth.public-chain.permit-all-paths[4].path` | `/actuator/health` | Stato del servizio |
| `govpay.auth.public-chain.permit-all-paths[4].methods[0]` | `GET` | Metodo ammesso senza autenticazione |

La libreria supporta altri metodi (LDAP, SSL, header, API key, OAuth2...), attivabili con `govpay.auth.<metodo>.enabled=true`: il servizio è pensato per il solo BASIC tra applicazioni e gli altri metodi non sono configurati né verificati.

### Database

| Proprietà | Variabile | Default | Descrizione |
|-----------|-----------|---------|-------------|
| `spring.datasource.url` | `DATASOURCE_URL` | - | URL JDBC del database di GovPay (obbligatoria, in alternativa a `spring.datasource.jndi-name`) |
| `spring.datasource.username` | `DATASOURCE_USERNAME` | - | Utente del database |
| `spring.datasource.password` | `DATASOURCE_PASSWORD` | - | Password del database |
| `spring.datasource.driver-class-name` | `DATASOURCE_DRIVER` | `org.postgresql.Driver` | Classe del driver JDBC: `org.postgresql.Driver`, `com.mysql.cj.jdbc.Driver`, `oracle.jdbc.OracleDriver`, `com.microsoft.sqlserver.jdbc.SQLServerDriver`, `org.h2.Driver` |
| `spring.jpa.database-platform` | `JPA_DIALECT` | `org.hibernate.dialect.PostgreSQLDialect` | Dialetto Hibernate: `PostgreSQLDialect`, `MySQLDialect`, `OracleDialect`, `SQLServerDialect`, `H2Dialect` (package `org.hibernate.dialect`) |
| `spring.jpa.hibernate.ddl-auto` | `HIBERNATE_DDL_AUTO` | `validate` | Verifica dello schema all'avvio: lasciare `validate` (o `none`), lo schema è di GovPay |
| `spring.jpa.open-in-view` | - | `false` | Sessione JPA limitata al servizio, non estesa alla vista |
| `spring.datasource.jndi-name` | - | - | Nome JNDI del datasource, per il deploy del war su application server |

### Logging

| Proprietà | Variabile | Default | Descrizione |
|-----------|-----------|---------|-------------|
| `logging.file.name` | `LOGGING_FILE_NAME` | - (solo console) | File di log |
| `logging.level.it.govpay` | `LOGGING_LEVEL_IT_GOVPAY` | `INFO` | Livello di log dei package GovPay |
| `logging.pattern.level` | - | `%5p [%X{transactionId:-},%X{correlationId:-}]` | Aggiunge a ogni riga transaction id e correlation id (header `X-Correlation-ID` o `X-Request-ID`, restituito in risposta) |
| `govpay.logging.livelli-dinamici.enabled` | - | `true` | Livelli di log letti dalla riga `log_level` della tabella `configurazione`: una modifica raggiunge tutti i nodi entro l'intervallo di refresh |
| `govpay.logging.livelli-dinamici.intervallo-refresh` | `LOG_LEVEL_REFRESH_INTERVAL` | `60s` | Intervallo di rilettura dei livelli dal database; `0` li legge solo all'avvio |
| `govpay.logging.livelli-dinamici.ritardo-iniziale` | - | `60s` | Ritardo della prima rilettura dopo quella di avvio |
| `govpay.logging.livelli-dinamici.logger-gestiti[0]` | - | `it.govpay` | Prefissi dei logger modificabili da database |
| `govpay.logging.tracciatura.*` | - | vedi [govpay-common](https://github.com/link-it/govpay-common#tracciatura-dei-log) | Header letti e restituiti per transaction id e correlation id |

### Stampe (govpay-stampe-api)

| Proprietà | Variabile | Default | Descrizione |
|-----------|-----------|---------|-------------|
| `app.stampe.base-url` | `STAMPE_BASE_URL` | vuoto | URL base di govpay-stampe-api; vuoto: le stampe in PDF rispondono `503` |
| `app.stampe.connect-timeout-ms` | `STAMPE_CONNECT_TIMEOUT_MS` | `5000` | Timeout di connessione, in ms |
| `app.stampe.read-timeout-ms` | `STAMPE_READ_TIMEOUT_MS` | `30000` | Timeout di lettura, in ms |
| `govpay.stampe.logo.pagopa` | - | vuoto | Logo pagoPA in base64 da inserire nel PDF della ricevuta |
| `resilience4j.circuitbreaker.instances.stampe.failure-rate-threshold` | - | `50` | Percentuale di errori oltre cui il circuito si apre |
| `resilience4j.circuitbreaker.instances.stampe.minimum-number-of-calls` | - | `10` | Chiamate minime prima di valutare la percentuale di errori |
| `resilience4j.circuitbreaker.instances.stampe.sliding-window-size` | - | `20` | Chiamate considerate per la percentuale di errori |
| `resilience4j.circuitbreaker.instances.stampe.wait-duration-in-open-state` | - | `30s` | Durata del circuito aperto, in cui le stampe rispondono subito con errore |
| `resilience4j.circuitbreaker.instances.stampe.permitted-number-of-calls-in-half-open-state` | - | `3` | Chiamate di prova alla riapertura |
| `resilience4j.circuitbreaker.instances.stampe.ignore-exceptions` | - | `org.springframework.web.client.HttpClientErrorException` | Le risposte 4xx di govpay-stampe-api non aprono il circuito |

Le chiamate verso govpay-stampe-api non vengono ritentate.

### GDE e connettori

URL e autenticazione del GDE non sono proprietà del servizio: sono nella configurazione di GovPay (tabella `configurazione`) e nel connettore `govpay_gde_api` (tabella `connettori`).

| Proprietà | Default | Descrizione |
|-----------|---------|-------------|
| `app.gde.async` | `true` | Invio degli eventi al GDE su un pool di thread dedicato; `false` li invia nel thread della richiesta |
| `app.gde.pool.core-size` | `2` | Thread minimi del pool di invio |
| `app.gde.pool.max-size` | `4` | Thread massimi del pool di invio |
| `app.gde.pool.queue-capacity` | `100` | Eventi in coda in attesa di invio |
| `govpay.client.cache.enabled` | `false` | Cache in memoria dei connettori; disabilitata, il connettore è letto dal database a ogni chiamata |

### Monitoraggio

| Proprietà | Variabile | Default | Descrizione |
|-----------|-----------|---------|-------------|
| `management.server.port` | `MANAGEMENT_SERVER_PORT` | `9090` | Porta dedicata agli endpoint actuator; sulla porta del servizio non sono esposti |
| `management.endpoints.web.exposure.include` | - | `health,prometheus` | Endpoint actuator esposti |
| `management.metrics.distribution.percentiles-histogram.http.server.requests` | - | `true` | Istogrammi delle latenze delle richieste ricevute |
| `management.metrics.distribution.percentiles-histogram.http.client.requests` | - | `true` | Istogrammi delle latenze delle chiamate verso stampe e GDE |
| `management.metrics.distribution.slo.http.server.requests` | - | `100ms,500ms,1s,2s,5s` | Soglie esplicite di latenza, compresa quella SLA pagoPA di 2s |
| `management.metrics.tags.application` | - | `govpay-pendenze-api` | Tag che identifica il servizio nelle metriche |
| `govpay.metrics.enabled` | - | `false` | Metriche custom di govpay-common (chiamate interne ed esterne) |

## Esecuzione

### Driver JDBC

I driver JDBC **non sono inclusi** nel fat jar e vanno forniti a runtime. Creare una directory (es. `jdbc-drivers/`) e copiarvi il driver del database utilizzato:

| Database   | Driver |
|------------|--------|
| PostgreSQL | [postgresql-42.7.14.jar](https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.14/postgresql-42.7.14.jar) |
| MySQL      | [mysql-connector-j-26.10.0.jar](https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/26.10.0/mysql-connector-j-26.10.0.jar) |
| Oracle     | [ojdbc11-23.26.3.0.0.jar](https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc11/23.26.3.0.0/ojdbc11-23.26.3.0.0.jar) |
| SQL Server | [mssql-jdbc-13.6.1.jre11.jar](https://repo1.maven.org/maven2/com/microsoft/sqlserver/mssql-jdbc/13.6.1.jre11/mssql-jdbc-13.6.1.jre11.jar) |
| H2         | [h2-2.5.252.jar](https://repo1.maven.org/maven2/com/h2database/h2/2.5.252/h2-2.5.252.jar) |

### Jar standalone

Il jar usa `PropertiesLauncher`: la proprietà `loader.path` indica la directory dei driver JDBC. Configurazione minima per PostgreSQL:

```bash
java -Dloader.path=./jdbc-drivers \
     -Dserver.port=8080 \
     -Dmanagement.server.port=9090 \
     -Dspring.datasource.url=jdbc:postgresql://localhost:5432/govpay \
     -Dspring.datasource.username=govpay \
     -Dspring.datasource.password=<password> \
     -Dapp.stampe.base-url=<url-govpay-stampe-api> \
     -jar target/govpay-pendenze-api.jar
```

L'API v3 risponde su `http://localhost:8080/v3/...`, la v2 su `http://localhost:8080/v2/...`. Per un altro database impostare anche `spring.datasource.driver-class-name` e `spring.jpa.database-platform`.

### War su application server

Il war usa il datasource dell'application server, indicato con `spring.datasource.jndi-name` (es. `java:comp/env/it.govpay.datasource`); il driver JDBC va installato nell'application server. Dove si trova il war:

| Origine | Dove |
|---------|------|
| Release (tag) | Allegato alla [release GitHub](https://github.com/link-it/govpay-pendenze-api/releases) come `govpay-pendenze-api-<tag>.war` |
| Qualsiasi build | Artifact `govpay-pendenze-api` della run di GitHub Actions (retention 90 giorni) |
| Build locale | `mvn clean install -P war`, in `target/govpay-pendenze-api.war` |

## Esecuzione con Docker

L'immagine è pubblicata su Docker Hub come [linkitaly/govpay-pendenze-api](https://hub.docker.com/r/linkitaly/govpay-pendenze-api) (release) e `linkitaly/govpay-pendenze-api-dev` (sviluppo, a ogni push su `main` con versione SNAPSHOT). Nel container il servizio risponde sulla porta `10005`, gli endpoint actuator sulla `9090`.

I driver JDBC non sono nell'immagine: si montano in `/opt/jdbc-drivers` (directory indicata da `GOVPAY_DS_JDBC_LIBS`), da cui l'entrypoint li carica con `loader.path`; per i download vedi [docker/jdbc-drivers/README.md](docker/jdbc-drivers/README.md). L'entrypoint costruisce URL JDBC, driver e dialetto dalle variabili `GOVPAY_DB_*`:

```bash
docker run -d --name govpay-pendenze-api -p 10005:10005 -p 9090:9090 \
  -v ./jdbc-drivers:/opt/jdbc-drivers:ro \
  -e GOVPAY_DB_TYPE=postgresql \
  -e GOVPAY_DB_SERVER=postgres:5432 \
  -e GOVPAY_DB_NAME=govpay \
  -e GOVPAY_DB_USER=govpay \
  -e GOVPAY_DB_PASSWORD=<password> \
  -e STAMPE_BASE_URL=<url-govpay-stampe-api> \
  linkitaly/govpay-pendenze-api
```

L'API v3 risponde su `http://localhost:10005/v3/...`, la v2 su `http://localhost:10005/v2/...`. In alternativa alle `GOVPAY_DB_*` si possono passare direttamente `DATASOURCE_URL`, `DATASOURCE_USERNAME`, `DATASOURCE_PASSWORD`, `DATASOURCE_DRIVER` e `JPA_DIALECT`.

Variabili d'ambiente dell'immagine, lette dall'entrypoint:

| Variabile | Obbligatoria | Default | Descrizione |
|-----------|--------------|---------|-------------|
| `GOVPAY_DB_TYPE` | sì (se manca `DATASOURCE_URL`) | - | Tipo di database: `postgresql`, `mysql`, `mariadb`, `oracle`, `sqlserver` |
| `GOVPAY_DB_SERVER` | sì (se manca `DATASOURCE_URL`) | - | Server del database, `host` o `host:porta` (porta di default del database se omessa) |
| `GOVPAY_DB_NAME` | sì (se manca `DATASOURCE_URL`) | - | Nome del database (per Oracle, service name o SID) |
| `GOVPAY_DB_USER` | sì (se manca `DATASOURCE_URL`) | - | Utente del database |
| `GOVPAY_DB_PASSWORD` | sì (se manca `DATASOURCE_URL`) | - | Password del database |
| `GOVPAY_DS_CONN_PARAM` | no | - | Parametri aggiunti all'URL JDBC (es. `zeroDateTimeBehavior=convertToNull` per MySQL) |
| `GOVPAY_DS_JDBC_LIBS` | no | `/opt/jdbc-drivers` | Directory dei driver JDBC |
| `GOVPAY_ORACLE_JDBC_URL_TYPE` | no | `servicename` | Formato dell'URL Oracle: `servicename` o `sid` |
| `ORACLE_TNS_ADMIN` | no | - | Directory dei file TNS di Oracle |
| `GOVPAY_PENDENZE_API_MIN_POOL` | no | `2` | Connessioni minime inattive del pool |
| `GOVPAY_PENDENZE_API_MAX_POOL` | no | `5` | Connessioni massime del pool |
| `SERVER_PORT` | no | `10005` | Porta HTTP del servizio |
| `MANAGEMENT_SERVER_PORT` | no | `9090` | Porta degli endpoint actuator |
| `GOVPAY_PENDENZE_API_JVM_MAX_RAM_PERCENTAGE` | no | `80` | `-XX:MaxRAMPercentage` della JVM |
| `GOVPAY_PENDENZE_API_JVM_INITIAL_RAM_PERCENTAGE` | no | - | `-XX:InitialRAMPercentage` della JVM |
| `GOVPAY_PENDENZE_API_JVM_MIN_RAM_PERCENTAGE` | no | - | `-XX:MinRAMPercentage` della JVM |
| `GOVPAY_PENDENZE_API_JVM_MAX_METASPACE_SIZE` | no | - | `-XX:MaxMetaspaceSize` della JVM |
| `GOVPAY_PENDENZE_API_JVM_MAX_DIRECT_MEMORY_SIZE` | no | - | `-XX:MaxDirectMemorySize` della JVM |
| `JAVA_OPTS` | no | - | Opzioni aggiuntive della JVM |
| `LOGGING_FILE_NAME` | no | `/var/log/govpay-pendenze-api/govpay-pendenze-api.log` | File di log |
| `TZ` | no | `Europe/Rome` | Fuso orario del container |

Variabili lette da `application.properties`, oltre alle proprietà Spring Boot in forma di variabile (es. `LOGGING_LEVEL_IT_GOVPAY=DEBUG`):

| Variabile | Obbligatoria | Default | Descrizione |
|-----------|--------------|---------|-------------|
| `DATASOURCE_URL` | sì (se mancano le `GOVPAY_DB_*`) | - | URL JDBC del database di GovPay |
| `DATASOURCE_USERNAME` | sì (se mancano le `GOVPAY_DB_*`) | - | Utente del database |
| `DATASOURCE_PASSWORD` | sì (se mancano le `GOVPAY_DB_*`) | - | Password del database |
| `DATASOURCE_DRIVER` | no | `org.postgresql.Driver` | Classe del driver JDBC |
| `JPA_DIALECT` | no | `org.hibernate.dialect.PostgreSQLDialect` | Dialetto Hibernate |
| `HIBERNATE_DDL_AUTO` | no | `validate` | Verifica dello schema all'avvio |
| `PENDENZE_API_V3_BASE_PATH` | no | `/v3` | Base path dell'API v3 |
| `PENDENZE_API_V2_BASE_PATH` | no | `/v2` | Base path dell'API v2 |
| `PENDENZE_V2_CONVERSIONE_PAGOPA_V1_ENABLE` | no | `false` | RPT e RT nel formato v1 per i client v2 |
| `PENDENZE_V2_GIORNI_VALIDITA_PENDENZA_SENZA_DATA` | no | - | Giorni di validità per le RPT ricostruite senza scadenza |
| `STAMPE_BASE_URL` | no | vuoto | URL base di govpay-stampe-api |
| `STAMPE_CONNECT_TIMEOUT_MS` | no | `5000` | Timeout di connessione verso govpay-stampe-api, in ms |
| `STAMPE_READ_TIMEOUT_MS` | no | `30000` | Timeout di lettura da govpay-stampe-api, in ms |
| `LOG_LEVEL_REFRESH_INTERVAL` | no | `60s` | Intervallo di rilettura dei livelli di log dal database |

Costruzione dell'immagine in locale (`docker/build_image.sh`, con `-l target/govpay-pendenze-api.jar` per usare il jar appena compilato) e scansione delle vulnerabilità sono descritte in [docker/SCANSIONE.md](docker/SCANSIONE.md).

## Monitoraggio

| Proprietà | Variabile | Default | Descrizione |
|-----------|-----------|---------|-------------|
| `management.server.port` | `MANAGEMENT_SERVER_PORT` | `9090` | Porta dedicata agli endpoint actuator; sulla porta del servizio non sono esposti |
| `management.endpoints.web.exposure.include` | - | `health,prometheus` | Endpoint actuator esposti |
| `management.metrics.distribution.percentiles-histogram.http.server.requests` | - | `true` | Istogrammi delle latenze delle richieste ricevute |
| `management.metrics.distribution.percentiles-histogram.http.client.requests` | - | `true` | Istogrammi delle latenze delle chiamate verso stampe e GDE |
| `management.metrics.distribution.slo.http.server.requests` | - | `100ms,500ms,1s,2s,5s` | Soglie esplicite di latenza, compresa quella SLA pagoPA di 2s |
| `management.metrics.tags.application` | - | `govpay-pendenze-api` | Tag che identifica il servizio nelle metriche |
| `govpay.metrics.enabled` | - | `false` | Metriche custom di govpay-common (chiamate interne ed esterne) |

## Esecuzione

### Driver JDBC

I driver JDBC **non sono inclusi** nel fat jar e vanno forniti a runtime. Creare una directory (es. `jdbc-drivers/`) e copiarvi il driver del database utilizzato:

| Database   | Driver |
|------------|--------|
| PostgreSQL | [postgresql-42.7.14.jar](https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.14/postgresql-42.7.14.jar) |
| MySQL      | [mysql-connector-j-26.10.0.jar](https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/26.10.0/mysql-connector-j-26.10.0.jar) |
| Oracle     | [ojdbc11-23.26.3.0.0.jar](https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc11/23.26.3.0.0/ojdbc11-23.26.3.0.0.jar) |
| SQL Server | [mssql-jdbc-13.6.1.jre11.jar](https://repo1.maven.org/maven2/com/microsoft/sqlserver/mssql-jdbc/13.6.1.jre11/mssql-jdbc-13.6.1.jre11.jar) |
| H2         | [h2-2.5.252.jar](https://repo1.maven.org/maven2/com/h2database/h2/2.5.252/h2-2.5.252.jar) |

### Jar standalone

Il jar usa `PropertiesLauncher`: la proprietà `loader.path` indica la directory dei driver JDBC. Configurazione minima per PostgreSQL:

```bash
java -Dloader.path=./jdbc-drivers \
     -Dserver.port=8080 \
     -Dmanagement.server.port=9090 \
     -Dspring.datasource.url=jdbc:postgresql://localhost:5432/govpay \
     -Dspring.datasource.username=govpay \
     -Dspring.datasource.password=<password> \
     -Dapp.stampe.base-url=<url-govpay-stampe-api> \
     -jar target/govpay-pendenze-api.jar
```

L'API v3 risponde su `http://localhost:8080/v3/...`, la v2 su `http://localhost:8080/v2/...`. Per un altro database impostare anche `spring.datasource.driver-class-name` e `spring.jpa.database-platform`.

### War su application server

Il war usa il datasource dell'application server, indicato con `spring.datasource.jndi-name` (es. `java:comp/env/it.govpay.datasource`); il driver JDBC va installato nell'application server. Dove si trova il war:

| Origine | Dove |
|---------|------|
| Release (tag) | Allegato alla [release GitHub](https://github.com/link-it/govpay-pendenze-api/releases) come `govpay-pendenze-api-<tag>.war` |
| Qualsiasi build | Artifact `govpay-pendenze-api` della run di GitHub Actions (retention 90 giorni) |
| Build locale | `mvn clean install -P war`, in `target/govpay-pendenze-api.war` |

## Esecuzione con Docker

L'immagine `linkitaly/govpay-pendenze-api` non è ancora pubblicata su Docker Hub e il repository non contiene ancora un `Dockerfile`. Fino ad allora il fat jar si esegue in un container con una JRE 21 generica, montando il jar e i driver JDBC:

```bash
docker run -d --name govpay-pendenze-api -p 8080:8080 -p 9090:9090 \
  -v ./target/govpay-pendenze-api.jar:/opt/govpay-pendenze-api.jar:ro \
  -v ./jdbc-drivers:/opt/jdbc-drivers:ro \
  -e DATASOURCE_URL=jdbc:postgresql://postgres:5432/govpay \
  -e DATASOURCE_USERNAME=govpay \
  -e DATASOURCE_PASSWORD=<password> \
  -e STAMPE_BASE_URL=<url-govpay-stampe-api> \
  eclipse-temurin:21-jre \
  java -Dloader.path=/opt/jdbc-drivers -jar /opt/govpay-pendenze-api.jar
```

Variabili d'ambiente lette da `application.properties`, oltre alle proprietà Spring Boot in forma di variabile (es. `SERVER_PORT`, `LOGGING_LEVEL_IT_GOVPAY=DEBUG`):

| Variabile | Obbligatoria | Default | Descrizione |
|-----------|--------------|---------|-------------|
| `DATASOURCE_URL` | sì | - | URL JDBC del database di GovPay |
| `DATASOURCE_USERNAME` | sì | - | Utente del database |
| `DATASOURCE_PASSWORD` | sì | - | Password del database |
| `DATASOURCE_DRIVER` | no | `org.postgresql.Driver` | Classe del driver JDBC |
| `JPA_DIALECT` | no | `org.hibernate.dialect.PostgreSQLDialect` | Dialetto Hibernate |
| `HIBERNATE_DDL_AUTO` | no | `validate` | Verifica dello schema all'avvio |
| `PENDENZE_API_V3_BASE_PATH` | no | `/v3` | Base path dell'API v3 |
| `PENDENZE_API_V2_BASE_PATH` | no | `/v2` | Base path dell'API v2 |
| `PENDENZE_V2_CONVERSIONE_PAGOPA_V1_ENABLE` | no | `false` | RPT e RT nel formato v1 per i client v2 |
| `PENDENZE_V2_GIORNI_VALIDITA_PENDENZA_SENZA_DATA` | no | - | Giorni di validità per le RPT ricostruite senza scadenza |
| `STAMPE_BASE_URL` | no | vuoto | URL base di govpay-stampe-api |
| `STAMPE_CONNECT_TIMEOUT_MS` | no | `5000` | Timeout di connessione verso govpay-stampe-api, in ms |
| `STAMPE_READ_TIMEOUT_MS` | no | `30000` | Timeout di lettura da govpay-stampe-api, in ms |
| `LOG_LEVEL_REFRESH_INTERVAL` | no | `60s` | Intervallo di rilettura dei livelli di log dal database |
| `MANAGEMENT_SERVER_PORT` | no | `9090` | Porta degli endpoint actuator |

## Monitoraggio

Gli endpoint actuator rispondono solo sulla porta di management (default `9090`):

```bash
curl http://localhost:9090/actuator/health       # stato, senza autenticazione
curl http://localhost:9090/actuator/prometheus   # metriche in formato Prometheus, senza autenticazione
```

`health` restituisce solo lo stato (`UP`/`DOWN`), senza dettagli; la porta di management va tenuta su una rete interna. L'endpoint actuator `loggers` non è esposto, perché non applicherebbe le ACL: per cambiare i livelli su tutti i nodi si usa la riga `log_level` della tabella `configurazione` (o la console di GovPay); gli endpoint `/admin/logging` sulla porta del servizio agiscono sul singolo nodo e richiedono le ACL di `Configurazione e manutenzione`.

## Pipeline CI

La pipeline GitHub Actions (`.github/workflows/maven.yml`) esegue:

- build di war e jar, test, report di copertura JaCoCo e analisi OWASP Dependency-Check;
- analisi SonarCloud e verifica delle licenze delle dipendenze di terze parti;
- scansione OSV e generazione dell'SBOM CycloneDX, con upload su Dependency-Track;
- costruzione e scansione dell'immagine Docker; su push su `main` con versione SNAPSHOT, pubblicazione dell'immagine di sviluppo;
- su tag, creazione della release GitHub (war, jar e report) e pubblicazione dell'immagine Docker.

Il tag deve coincidere con la versione del `pom.xml`, che non deve essere una SNAPSHOT. Il workflow `.github/workflows/refresh-owasp-db.yml` aggiorna ogni notte la cache del database NVD usata da OWASP Dependency-Check.

## Licenza

GovPay Pendenze API è distribuito con licenza [GNU General Public License v3.0](LICENSE).
