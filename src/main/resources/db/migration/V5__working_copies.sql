-- Working copies: the unpublished document a builder edits, at most one per form.
-- revision changes on every save (optimistic concurrency: a save must name the revision it was
-- based on). Revisions come from one sequence and never repeat, so an editor holding a revision
-- of a copy that was published or discarded meanwhile cannot overwrite a newer copy.
-- base_version is the published version the copy started from, if any.

CREATE SEQUENCE form_working_copy_revision;

CREATE TABLE form_working_copy (
    form_id       uuid PRIMARY KEY REFERENCES form (id),
    document      jsonb NOT NULL,
    revision      bigint NOT NULL,
    base_version  integer,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    updated_by    uuid REFERENCES admin_user (id)
);
