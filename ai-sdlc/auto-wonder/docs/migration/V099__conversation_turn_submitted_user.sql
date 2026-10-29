-- Additive sender snapshot; historical and non-web turns remain NULL.
ALTER TABLE `agent_conversation_turn`
  ADD COLUMN `submitted_user_id` BIGINT NULL COMMENT 'Authenticated web sender';
