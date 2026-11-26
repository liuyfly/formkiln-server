package io.formkiln.server.forms;

import io.formkiln.server.workspaces.WorkspaceContext;
import io.formkiln.server.forms.FormService.NotFoundException;
import io.formkiln.server.platform.RuntimeException4xx;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Folders that organise forms in the admin app: one level, unique names (ignoring case), each form
 * in at most one folder. Folders are an admin convenience: they do not affect access, runtime
 * behaviour or exports.
 */
@Service
public class FolderService {

    /** A folder and how many active (not archived) forms are in it. */
    public record Folder(UUID id, String name, long formCount) {}

    private static final int MAX_NAME = 120;

    private final JdbcClient jdbc;

    public FolderService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Folder> list() {
        return jdbc.sql("""
                        SELECT d.id, d.name,
                               (SELECT COUNT(*) FROM form f WHERE f.folder_id = d.id AND f.archived_at IS NULL) AS form_count
                        FROM form_folder d
                        WHERE d.workspace_id = :ws
                        ORDER BY lower(d.name)
                        """)
                .param("ws", WorkspaceContext.id())
                .query((rs, n) -> new Folder(rs.getObject("id", UUID.class), rs.getString("name"), rs.getLong("form_count")))
                .list();
    }

    @Transactional
    public Folder create(String name) {
        String clean = cleanName(name);
        try {
            UUID id = jdbc.sql("INSERT INTO form_folder (name, workspace_id) VALUES (:name, :ws) RETURNING id")
                    .param("name", clean).param("ws", WorkspaceContext.id()).query(UUID.class).single();
            return new Folder(id, clean, 0);
        } catch (DuplicateKeyException e) {
            throw nameTaken();
        }
    }

    @Transactional
    public Folder rename(UUID id, String name) {
        String clean = cleanName(name);
        try {
            int updated = jdbc.sql("UPDATE form_folder SET name = :name WHERE id = :id AND workspace_id = :ws")
                    .param("name", clean).param("id", id).param("ws", WorkspaceContext.id()).update();
            if (updated == 0) {
                throw notFound();
            }
        } catch (DuplicateKeyException e) {
            throw nameTaken();
        }
        return list().stream().filter(folder -> folder.id().equals(id)).findFirst().orElseThrow(FolderService::notFound);
    }

    /**
     * Deletes an empty folder. Archived forms do not count: they move to the top level (they are
     * not shown in the folder anyway).
     */
    @Transactional
    public String delete(UUID id) {
        String name = jdbc.sql("SELECT name FROM form_folder WHERE id = :id AND workspace_id = :ws FOR UPDATE")
                .param("id", id).param("ws", WorkspaceContext.id())
                .query(String.class).optional().orElseThrow(FolderService::notFound);
        long active = jdbc.sql("SELECT COUNT(*) FROM form WHERE folder_id = :id AND archived_at IS NULL")
                .param("id", id).query(Long.class).single();
        if (active > 0) {
            throw new RuntimeException4xx(HttpStatus.CONFLICT, "folderNotEmpty",
                    "Move the forms out of this folder before deleting it.");
        }
        jdbc.sql("UPDATE form SET folder_id = NULL WHERE folder_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM form_folder WHERE id = :id").param("id", id).update();
        return name;
    }

    /** Moves a form into a folder, or to the top level ({@code folderId} null). Returns the folder name. */
    @Transactional
    public String moveForm(String key, UUID folderId) {
        String name = null;
        if (folderId != null) {
            name = jdbc.sql("SELECT name FROM form_folder WHERE id = :id AND workspace_id = :ws")
                    .param("id", folderId).param("ws", WorkspaceContext.id())
                    .query(String.class).optional().orElseThrow(FolderService::notFound);
        }
        int updated = jdbc.sql("UPDATE form SET folder_id = :folder WHERE key = :key AND workspace_id = :ws")
                .param("folder", folderId).param("key", key).param("ws", WorkspaceContext.id()).update();
        if (updated == 0) {
            throw new NotFoundException("Form " + key + " does not exist");
        }
        return name;
    }

    private static String cleanName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.length() > MAX_NAME) {
            throw new RuntimeException4xx(HttpStatus.BAD_REQUEST, "invalidFolderName",
                    "A folder name has 1 to " + MAX_NAME + " characters.");
        }
        return clean;
    }

    private static RuntimeException4xx nameTaken() {
        return new RuntimeException4xx(HttpStatus.CONFLICT, "folderNameTaken", "A folder with this name already exists.");
    }

    private static RuntimeException4xx notFound() {
        return new RuntimeException4xx(HttpStatus.NOT_FOUND, "folderNotFound", "This folder does not exist.");
    }
}
