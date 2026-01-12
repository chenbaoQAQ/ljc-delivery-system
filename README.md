🚚 老乡鸡配送数据自动化审计系统
📌 项目背景

在连锁餐饮行业中，门店的配送计划（底板）与物流实际送货数据往往存在偏差。本项目专为老乡鸡设计，旨在通过自动化手段替代人工对账，处理跨度达 12 年（2014-2026）的海量配送明细，实现对全国门店配送准确性的高效审计。
🚀 核心功能

    自动化审计引擎：系统自动对比“配送底板模板”与“实际明细”，动态识别异常，生成 0A/1B/C 等审计状态标签。

    多维审计看板：

        矩阵大表：横向平铺展示全月每天的配送状态，红色高亮异常单元格。

        异常整改清单：精准定位配送偏差的门店与日期，直接导出供管理层复核。

    灵活底板管理：支持按月份配置不同的配送模版（如：135配送、隔日配等），支持图形化勾选配置。

    大数据异步导入：支持百万级 CSV 数据流式解析与批量入库，具备防 OOM 机制。

🛠️ 技术深度（面试亮点）
1. 亿级数据量下的查询性能优化

   复合索引设计：针对 (date, shop_id) 建立复合索引，严格遵循最左匹配原则。

   局部聚合策略：审计报表放弃全表扫描，采用“先分页去重门店 ID，再局部聚合明细”的二次查询方案，将分钟级延迟降低至毫秒级。

   索引覆盖 (Covering Index)：优化 SQL 使其尽可能在索引树上完成计算，减少回表 IO 次数。

2. 高可靠的数据幂等架构

   UUID 版本控制：引入 batch_no 机制，为每一批次导入提供唯一身份标识，确保数据重跑时不重复。

   全量覆盖式导入：采用“先清理当月、后批量插入”的事务化逻辑，保证月度数据的最终一致性。

3. 前后端分离的高性能交互

   真正物理分页：基于 MyBatis-Plus 拦截器实现数据库层面的物理分页，避免内存分页导致的溢出问题。

   前端虚拟渲染思想：在看板大表中使用表格固定列（Sticky Column）技术，确保在大规模数据横向滚动时的用户体验。

📊 数据库模型

    delivery_detail: 配送事实表，存储亿级颗粒度的 SKU 配送明细。

    delivery_template: 审计标准表，存储各月份的配送计划配置。

    shop_delivery_status: 状态映射表，存储审计判定的最终结论。

📦 快速开始

    环境要求：JDK 17+, MySQL 8.0。

    数据库初始化：执行 SQL 脚本创建相关表。

    配置文件：修改 application.properties 中的数据库连接信息。

    mvn clean package
    java -jar target/ljc-delivery-system-0.0.1-SNAPSHOT.jar

    访问：打开浏览器访问 http://localhost:8181/index.html。

📊 数据库文件

      CREATE DATABASE ljc_delivery_system;
      -- 1. 清理旧表（如果存在）
      DROP TABLE IF EXISTS `delivery_detail`;
      DROP TABLE IF EXISTS `shop_delivery_status`;
      DROP TABLE IF EXISTS `delivery_template`;
      CREATE DATABASE IF NOT EXISTS `ljc_delivery_system` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
      USE `ljc_delivery_system`;
      -- 1. 配送明细表 (针对亿级数据优化)
      CREATE TABLE `delivery_detail` (
      `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
      `shop_id` VARCHAR(50) NOT NULL COMMENT '门店ID',
      `sku_id` VARCHAR(50) DEFAULT NULL COMMENT '产品SKU ID',
      `qty` INT DEFAULT '0' COMMENT '配送数量',
      `date` DATE NOT NULL COMMENT '配送日期',
      `batch_no` VARCHAR(64) DEFAULT NULL COMMENT '导入批次号(UUID)',
      PRIMARY KEY (`id`),
      -- 索引1：用于第一步分页获取去重的 shop_id (覆盖索引)
      KEY `idx_date_shop` (`date`, `shop_id`),
      -- 索引2：用于第二步局部精准聚合 SUM(qty) (覆盖索引，避免回表)
      KEY `idx_shop_date_qty` (`shop_id`, `date`, `qty`),
      -- 索引3：用于数据维护、按批次秒级回滚
      KEY `idx_batch_no` (`batch_no`)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配送明细事实表';
      
      -- 2. 配送底板表
      CREATE TABLE `delivery_template` (
      `id` INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
      `template_name` VARCHAR(100) NOT NULL,
      `year_month` VARCHAR(7) NOT NULL, -- 格式: YYYY-MM
      `config` TEXT NOT NULL,           -- 存储1-31天的状态
      KEY `idx_ym` (`year_month`)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
      
      -- 3. 门店审计状态底稿表
      CREATE TABLE `shop_delivery_status` (
      `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
      `shop_id` VARCHAR(50) NOT NULL,
      `date` DATE NOT NULL,
      `shop_status` VARCHAR(10) DEFAULT NULL, -- 存储 0A, 1B 等
      UNIQUE KEY `uk_shop_date` (`shop_id`, `date`),
      KEY `idx_date` (`date`) -- 方便按日期拉取整改清单
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-------------------------------------------------------------------------------
V2：

      -- 2. 清理旧表
      DROP TABLE IF EXISTS `delivery_detail`;
      DROP TABLE IF EXISTS `delivery_template`;
      DROP TABLE IF EXISTS `shop_delivery_status`;
      
      -- 3. 配送明细表 (V2精简版：适配 CSV 直接导入)
      -- 移除 sku_id 和 batch_no，将 CSV 中的 id 设为主键
      CREATE TABLE `delivery_detail` (
      `id` BIGINT NOT NULL COMMENT '来自CSV的唯一流水ID',
      `shop_id` VARCHAR(50) NOT NULL COMMENT '门店ID',
      `date` DATE NOT NULL COMMENT '配送日期',
      `qty` INT DEFAULT '0' COMMENT '配送数量',
      PRIMARY KEY (`id`),
      -- 索引优化：支撑“先分页门店，再聚合明细”的高性能算法
      -- 索引1：用于快速分页获取去重的 shop_id
      KEY `idx_date_shop` (`date`, `shop_id`),
      -- 索引2：用于局部精准聚合 SUM(qty) (覆盖索引，避免回表)
      KEY `idx_shop_date_qty` (`shop_id`, `date`, `qty`)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='精简后的配送明细表';
      
      -- 4. 配送底板表 (保持不变，支持 CRUD)
      CREATE TABLE `delivery_template` (
      `id` INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
      `template_name` VARCHAR(100) NOT NULL COMMENT '底板名称 (如: B-单号)',
      `year_month` VARCHAR(7) NOT NULL COMMENT '适用月份 (YYYY-MM)',
      `config` TEXT NOT NULL COMMENT '1-31天的配送规律配置 (0/1字符串)',
      KEY `idx_ym` (`year_month`)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配送底板配置表';
      
      -- 5. 门店审计状态底稿表 (用于存储审计结论)
      CREATE TABLE `shop_delivery_status` (
      `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
      `shop_id` VARCHAR(50) NOT NULL,
      `date` DATE NOT NULL,
      `shop_status` VARCHAR(10) DEFAULT NULL COMMENT '存储 0A, 1B 等状态标识',
      UNIQUE KEY `uk_shop_date` (`shop_id`, `date`),
      KEY `idx_date` (`date`)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门店审计状态底稿表';