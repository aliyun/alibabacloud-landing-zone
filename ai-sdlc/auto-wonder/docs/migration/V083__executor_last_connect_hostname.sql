ALTER TABLE `executor`
  ADD COLUMN `last_connect_hostname` VARCHAR(128) DEFAULT NULL
    COMMENT '最近一次成功 WebSocket 接入时客户端自报的主机名（旧客户端未上报为 NULL）' AFTER `last_connect_ip`;
