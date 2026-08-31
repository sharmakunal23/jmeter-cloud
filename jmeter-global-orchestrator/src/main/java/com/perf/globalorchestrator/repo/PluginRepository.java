package com.perf.globalorchestrator.repo;

import com.perf.globalorchestrator.domain.Plugin;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@code ORCH_PLUGIN} — the global plugin library. Rows are
 * immutable (no UPDATE anywhere): an upgrade is delete + re-register, so the
 * two unique constraints ({@code NAME}, {@code SHA256}) are the whole
 * version-collision story.
 */
@Repository
public class PluginRepository {

    private static final RowMapper<Plugin> MAPPER = (rs, n) -> new Plugin(
            rs.getString("PLUGIN_ID"),
            rs.getString("NAME"),
            rs.getString("VERSION"),
            rs.getString("BLOB_ID"),
            rs.getString("SHA256"),
            rs.getLong("SIZE_BYTES"),
            rs.getString("FILE_NAME"),
            rs.getString("DESCRIPTION"),
            rs.getString("CREATED_BY"),
            OracleBind.instant(rs, "CREATED_AT"));

    private final JdbcTemplate jdbc;

    public PluginRepository(@Qualifier("runStateJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the row; a name or content collision surfaces as {@code DuplicateKeyException}. */
    public Plugin insert(Plugin p) {
        jdbc.update(
                "INSERT INTO ORCH_PLUGIN "
                + "(PLUGIN_ID,NAME,VERSION,BLOB_ID,SHA256,SIZE_BYTES,FILE_NAME,DESCRIPTION,CREATED_BY,CREATED_AT) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                p.pluginId(), p.name(), p.version(), p.blobId(), p.sha256(), p.sizeBytes(),
                p.fileName(), OracleBind.text(p.description(), OracleBind.TEXT_CHARS),
                OracleBind.text(p.createdBy(), OracleBind.NAME_CHARS), OracleBind.ts(p.createdAt()));
        return p;
    }

    public List<Plugin> findAll() {
        return jdbc.query("SELECT * FROM ORCH_PLUGIN ORDER BY NAME", MAPPER);
    }

    public Optional<Plugin> findById(String pluginId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM ORCH_PLUGIN WHERE PLUGIN_ID=?", MAPPER, pluginId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<Plugin> findByName(String name) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM ORCH_PLUGIN WHERE NAME=?", MAPPER, name));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<Plugin> findBySha256(String sha256) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM ORCH_PLUGIN WHERE SHA256=?", MAPPER, sha256));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** True when a registry row still references this blob — the orphan-delete guard. */
    public boolean existsByBlobId(String blobId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ORCH_PLUGIN WHERE BLOB_ID=?", Integer.class, blobId);
        return n != null && n > 0;
    }

    /** Idempotent — deleting an unknown id is a no-op. */
    public void delete(String pluginId) {
        jdbc.update("DELETE FROM ORCH_PLUGIN WHERE PLUGIN_ID=?", pluginId);
    }

    /**
     * Non-terminal runs whose launch snapshot references this plugin — the
     * delete gate. The indexed {@code STATE} predicate prunes to the handful of
     * live rows before the JSON probe touches a CLOB; the quoted {@code "id"}
     * is the SQL/JSON path variable, not a database identifier.
     */
    /**
     * Non-terminal runs whose launch snapshot stages from {@code blobId} —
     * the orphan-delete guard: scale-up joiners re-fetch plugin bytes by the
     * snapshot's blobId, so those bytes must outlive any registry state.
     */
    public int activeRunsReferencingBlob(String blobId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ORCH_RUN "
                + "WHERE STATE IN ('PREPARING','STARTING','RUNNING','DRAINING') "
                + "AND JSON_EXISTS(PLUGINS, '$[*]?(@.blobId == $id)' PASSING ? AS \"id\")",
                Integer.class, blobId);
        return n == null ? 0 : n;
    }

    public int countActiveRunsReferencing(String pluginId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ORCH_RUN "
                + "WHERE STATE IN ('PREPARING','STARTING','RUNNING','DRAINING') "
                + "AND JSON_EXISTS(PLUGINS, '$[*]?(@.pluginId == $id)' PASSING ? AS \"id\")",
                Integer.class, pluginId);
        return n == null ? 0 : n;
    }
}
