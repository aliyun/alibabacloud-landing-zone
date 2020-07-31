provider "alicloud" {
  profile = "default"
}

# 创建企业默认专有网络
resource "alicloud_vpc" "default_vpc" {
  name       = "default_vpc"
  cidr_block = "192.168.0.0/16"
}
# 创建交换机
resource "alicloud_vswitch" "default_vswitch" {
  name              = "default_vswitch"
  vpc_id            = alicloud_vpc.default_vpc.id
  cidr_block        = "192.168.0.0/24"
  availability_zone = "cn-hangzhou-h"
}
# 创建企业默认安全组
resource "alicloud_security_group" "charles_security_group" {
  name   = "default-sg"
  vpc_id = alicloud_vpc.default_vpc.id
}