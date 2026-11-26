-- Folders to organise forms (one level), and archived forms: hidden from the list and no longer
-- served to respondents, with every submission and setting kept until restored.
CREATE TABLE form_folder (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        varchar(120) NOT NULL CHECK (length(btrim(name)) > 0),
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX form_folder_name ON form_folder (lower(name));

-- A folder can only be deleted when no form is in it (RESTRICT).
ALTER TABLE form ADD COLUMN folder_id uuid REFERENCES form_folder (id);
ALTER TABLE form ADD COLUMN archived_at timestamptz;
CREATE INDEX form_folder_id ON form (folder_id);
