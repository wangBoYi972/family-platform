#!/bin/sh
# 创建业务账号并最小授权（仅各自数据库权限，禁止跨库访问）。
# 密码来自 MYSQL_APP_PASSWORD 环境变量，禁止硬编码到镜像或代码。
set -eu

mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<-EOSQL
CREATE USER IF NOT EXISTS 'family_app'@'%' IDENTIFIED BY '${MYSQL_APP_PASSWORD}';
GRANT ALL PRIVILEGES ON family_user.* TO 'family_app'@'%';
GRANT ALL PRIVILEGES ON family_content.* TO 'family_app'@'%';
GRANT ALL PRIVILEGES ON family_file.* TO 'family_app'@'%';
GRANT ALL PRIVILEGES ON family_archive.* TO 'family_app'@'%';
GRANT ALL PRIVILEGES ON family_im.* TO 'family_app'@'%';
GRANT ALL PRIVILEGES ON family_media.* TO 'family_app'@'%';
FLUSH PRIVILEGES;
EOSQL
