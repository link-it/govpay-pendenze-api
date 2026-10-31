# JDBC Drivers

I driver JDBC non sono inclusi nel fat jar dell'applicazione e devono essere forniti esternamente.
Collocare i file JAR dei driver in questa directory: verranno montati nel container al path `/opt/jdbc-drivers`.

L'entrypoint del container imposta automaticamente `LOADER_PATH=/opt/jdbc-drivers` in modo che
il `PropertiesLauncher` di Spring Boot carichi i driver a runtime.

## Driver supportati

Scaricare il driver JDBC corrispondente al database utilizzato (versioni gestite da [govpay-bom](https://github.com/link-it/govpay-bom)):

### PostgreSQL

```bash
wget https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.14/postgresql-42.7.14.jar
```

**License**: BSD-2-Clause

### MySQL

```bash
wget https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/26.10.0/mysql-connector-j-26.10.0.jar
```

**License**: GPL-2.0

### Oracle

```bash
wget https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc11/23.26.3.0.0/ojdbc11-23.26.3.0.0.jar
```

**License**: Oracle Free Use Terms and Conditions (FUTC)
**Note**: Verificare i termini di licenza Oracle prima dell'uso in produzione.

### SQL Server

```bash
wget https://repo1.maven.org/maven2/com/microsoft/sqlserver/mssql-jdbc/13.6.1.jre11/mssql-jdbc-13.6.1.jre11.jar
```

**License**: MIT

## Verifica

Dopo il download, verificare che il driver sia presente:

```bash
ls -lh *.jar
```

## Database multipli

Se necessario supportare più tipi di database, è possibile collocare più driver in questa directory.
L'applicazione caricherà quello appropriato in base alla configurazione `GOVPAY_DB_TYPE`.

Esempio:
```
jdbc-drivers/
├── postgresql-42.7.14.jar
├── mysql-connector-j-26.10.0.jar
└── ojdbc11-23.26.3.0.0.jar
```

## Troubleshooting

Se si ottiene l'errore "No suitable driver found", verificare che:

1. Il file JAR del driver sia presente in questa directory
2. Il file JAR abbia i permessi corretti (leggibile)
3. Il valore di `GOVPAY_DB_TYPE` corrisponda al database in uso
4. Il nome della classe driver sia corretto per il tipo di database
