package dev.tally.data;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Offline mode: the four exports loaded into an in-memory H2 database, one schema per system. */
public class H2Backend extends Backend {

    /** The same column types as the Postgres databases Zetaris reads in live mode (zetaris/load_postgres.py). */
    static final Map<String, String> DDL = Map.of(
            "erp.invoices", "invoice_id varchar primary key, vendor_id varchar, invoice_no varchar, invoice_date date, "
                    + "amount numeric(12,2), po_number varchar, requested_by varchar, approved_by varchar, approved_date date, "
                    + "status varchar, memo varchar",
            "erp.payments", "payment_id varchar primary key, invoice_id varchar, vendor_id varchar, amount numeric(12,2), "
                    + "paid_date date, method varchar, bank_account varchar",
            "erp.policies", "policy varchar primary key, value numeric, description varchar",
            "vendors.vendors", "vendor_id varchar primary key, name varchar, category varchar, tax_id varchar, "
                    + "bank_account varchar, address varchar, created_date date, created_by varchar, status varchar, "
                    + "coi_disclosed varchar, coi_approved_by varchar, coi_note varchar",
            "hr.employees", "employee_id varchar primary key, name varchar, department varchar, title varchar, "
                    + "hire_date date, status varchar, bank_account varchar, home_address varchar",
            "bank.transactions", "txn_id varchar primary key, posted_date date, direction varchar, txn_type varchar, "
                    + "amount numeric(12,2), counterparty_name varchar, counterparty_account varchar, reference varchar, "
                    + "description varchar");

    private final Connection con;

    public H2Backend(Path sources) {
        try {
            con = DriverManager.getConnection("jdbc:h2:mem:;NON_KEYWORDS=VALUE,KEY");
            try (Statement st = con.createStatement()) {
                for (String system : SYSTEM_ORDER) {
                    st.execute("CREATE SCHEMA " + system);
                    for (String table : TABLES.get(system)) {
                        String csv = sources.resolve(system).resolve(table + ".csv").toAbsolutePath().toString().replace("'", "''");
                        st.execute("CREATE TABLE " + system + "." + table + " (" + DDL.get(system + "." + table) + ") AS "
                                + "SELECT * FROM CSVREAD('" + csv + "', NULL, 'charset=UTF-8')");
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not load the books from " + sources + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String mode() {
        return "offline";
    }

    @Override
    public String label() {
        return "Offline: the same SQL on H2 over the four exports";
    }

    @Override
    protected String where(String system, String table) {
        return system + "." + table;
    }

    @Override
    protected List<Map<String, Object>> run(String sql) {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    row.put(md.getColumnLabel(i).toLowerCase(), plain(rs.getObject(i)));
                }
                rows.add(row);
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage() + "\n" + sql, e);
        }
    }
}
