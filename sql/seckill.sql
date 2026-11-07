CREATE DATABASE IF NOT EXISTS `seckill`;

USE `seckill`;

CREATE TABLE `t_user` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `nickname` VARCHAR(32) NOT NULL COMMENT '用户昵称',
  `password` VARCHAR(72) NOT NULL COMMENT '密码(加密存储)',
  `mobile` VARCHAR(11) NOT NULL COMMENT '手机号',
  `avatar` VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态(0:禁用 1:启用)',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_mobile` (`mobile`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

CREATE TABLE `t_goods` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `goods_name` VARCHAR(200) NOT NULL COMMENT '商品名称(完整，含规格)',
  `goods_img` VARCHAR(255) NOT NULL COMMENT '商品主图(冗余字段，用于列表展示)',
  `goods_price` DECIMAL(10,2) NOT NULL COMMENT '商品原价',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '商品状态(0:下架 1:上架)',
  `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除(0:未删除 1:已删除)',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_status_deleted` (`status`, `is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品表';

CREATE TABLE `t_goods_img` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `goods_id` BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
  `img_url` VARCHAR(255) NOT NULL COMMENT '图片URL',
  `sort` INT NOT NULL DEFAULT 0 COMMENT '排序(数字越小越靠前)',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_goods_id` (`goods_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品图片表';

CREATE TABLE `t_goods_detail` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `goods_id` BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
  `detail_content` LONGTEXT DEFAULT NULL COMMENT '商品详情(HTML/富文本)',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_goods_id` (`goods_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品详情表';

CREATE TABLE `t_seckill_activity` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '活动ID',
  `activity_name` VARCHAR(100) NOT NULL COMMENT '活动名称',
  `begin_time` DATETIME NOT NULL COMMENT '活动开始时间',
  `end_time` DATETIME NOT NULL COMMENT '活动结束时间',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '活动状态(0:未开始 1:进行中 2:已结束)',
  `description` VARCHAR(500) DEFAULT NULL COMMENT '活动描述',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_begin_time` (`begin_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='秒杀活动表';

CREATE TABLE `t_seckill_goods` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `activity_id` BIGINT UNSIGNED NOT NULL COMMENT '活动ID',
  `goods_id` BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
  `seckill_title` VARCHAR(100) NOT NULL COMMENT '秒杀展示名称(简短，用于列表)',
  `seckill_img` VARCHAR(255) NOT NULL COMMENT '秒杀展示图片(用于列表)',
  `seckill_price` DECIMAL(10,2) NOT NULL COMMENT '秒杀价',
  `seckill_total` INT NOT NULL DEFAULT 0 COMMENT '秒杀库存总量(不变)',
  `seckill_stock` INT NOT NULL DEFAULT 0 COMMENT '秒杀剩余库存(扣减)',
  `seckill_limit` INT NOT NULL DEFAULT 1 COMMENT '秒杀限购数量',
  `sort` INT NOT NULL DEFAULT 0 COMMENT '展示排序(数字越小越靠前)',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '版本号(乐观锁)',
  `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除(0:未删除 1:已删除)',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_activity_goods` (`activity_id`, `goods_id`),
  KEY `idx_activity_sort` (`activity_id`, `sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='秒杀商品关联表';

CREATE TABLE `t_seckill_order` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
  `activity_id` BIGINT UNSIGNED NOT NULL COMMENT '活动ID',
  `goods_id` BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
  `order_no` VARCHAR(64) NOT NULL COMMENT '订单号',
  `seckill_price` DECIMAL(10,2) NOT NULL COMMENT '秒杀价格(冗余字段)',
  `goods_name` VARCHAR(100) NOT NULL COMMENT '商品名称(冗余字段)',
  `goods_img` VARCHAR(255) NOT NULL COMMENT '商品图片(冗余字段)',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '订单状态(0:待支付 1:待发货 2:已发货 3:已收货 4:已退款 5:已取消 6:已关闭)',
  `expire_time` DATETIME NOT NULL COMMENT '订单过期时间',
  `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除(0:未删除 1:已删除)',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_activity_goods` (`user_id`, `activity_id`, `goods_id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_activity_id` (`activity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='秒杀订单表';

CREATE TABLE `t_seckill_order_send_compensation` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `order_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单号',
  `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  `activity_id` bigint unsigned NOT NULL COMMENT '活动ID',
  `goods_id` bigint unsigned NOT NULL COMMENT '商品ID',
  `seckill_goods_id` bigint unsigned NOT NULL COMMENT '秒杀商品ID',
  `message_body` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '下单消息体JSON(补偿重发的数据源)',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '补偿状态(0:待重试 1:已补偿 2:待人工处理)',
  `retry_count` int NOT NULL DEFAULT '0' COMMENT '已重发次数',
  `fail_reason` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最近一次失败原因',
  `next_retry_time` datetime NOT NULL COMMENT '下次允许重发时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除(0:未删除 1:已删除)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_status_next_retry` (`status`,`next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='秒杀订单发送失败补偿表';
