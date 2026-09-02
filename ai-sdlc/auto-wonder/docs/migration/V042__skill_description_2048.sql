-- Widen skill.description to VARCHAR(2048) for longer skill descriptions.
ALTER TABLE skill MODIFY COLUMN description VARCHAR(2048) DEFAULT NULL;
