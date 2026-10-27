-- Register the company's four systems as four Zetaris datasources. Zetaris reads each one in place over JDBC;
-- nothing is copied into Zetaris.
-- Register the four datasources: run one statement at a time in the Zetaris SQL Editor,
-- after replacing ${PG_HOST} (usually postgres), ${PG_USER} and ${PG_PASSWORD}.
-- Host `postgres` is the Postgres service inside the Zetaris bundle's Docker network.

CREATE DATASOURCE tally_erp DESCRIBE BY "Brightwater ERP: invoices, payments, approval policy" OPTIONS (
  jdbcdriver "org.postgresql.Driver",
  jdbcurl "jdbc:postgresql://${PG_HOST}:5432/tally_erp",
  username "${PG_USER}",
  password "${PG_PASSWORD}",
  fetchsize "1000"
);

REGISTER DATASOURCE TABLES FROM tally_erp;

CREATE DATASOURCE tally_vendors DESCRIBE BY "Brightwater vendor master (procurement)" OPTIONS (
  jdbcdriver "org.postgresql.Driver",
  jdbcurl "jdbc:postgresql://${PG_HOST}:5432/tally_vendors",
  username "${PG_USER}",
  password "${PG_PASSWORD}",
  fetchsize "1000"
);

REGISTER DATASOURCE TABLES FROM tally_vendors;

CREATE DATASOURCE tally_hr DESCRIBE BY "Brightwater HR system: employees" OPTIONS (
  jdbcdriver "org.postgresql.Driver",
  jdbcurl "jdbc:postgresql://${PG_HOST}:5432/tally_hr",
  username "${PG_USER}",
  password "${PG_PASSWORD}",
  fetchsize "1000"
);

REGISTER DATASOURCE TABLES FROM tally_hr;

CREATE DATASOURCE tally_bank DESCRIBE BY "Brightwater bank statement feed" OPTIONS (
  jdbcdriver "org.postgresql.Driver",
  jdbcurl "jdbc:postgresql://${PG_HOST}:5432/tally_bank",
  username "${PG_USER}",
  password "${PG_PASSWORD}",
  fetchsize "1000"
);

REGISTER DATASOURCE TABLES FROM tally_bank;

-- Check: one statement that reads all four systems at once.
SELECT 'erp' AS system, COUNT(*) AS n FROM tally_erp.invoices
UNION ALL SELECT 'vendors', COUNT(*) FROM tally_vendors.vendors
UNION ALL SELECT 'hr', COUNT(*) FROM tally_hr.employees
UNION ALL SELECT 'bank', COUNT(*) FROM tally_bank.transactions;
