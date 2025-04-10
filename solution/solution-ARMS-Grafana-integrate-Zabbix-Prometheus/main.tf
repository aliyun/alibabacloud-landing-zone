#设置变量
variable "region" {
  default = "cn-qingdao"
}

variable "instance_name" {
  default = "deploying-zabbix-server"
}

variable "instance_type" {
  default = "ecs.c6.large"
}

variable "image_id" {
  default = "centos_7_9_x64_20G_alibase_20240628.vhd"
}

variable "internet_bandwidth" {
  default = "10"
}

variable "instance_password" {
  default = "Test@12345"
}

variable "mysql_zabbix_password" {
  default = "zabbix"
}

provider "alicloud" {
  region = var.region
}

data "alicloud_zones" "default" {
  available_disk_category     = "cloud_efficiency"
  available_resource_creation = "VSwitch"
  available_instance_type     = var.instance_type
}

resource "alicloud_vpc" "vpc" {
  vpc_name   = var.instance_name
  cidr_block = "172.16.0.0/12"
}

resource "alicloud_vswitch" "vsw" {
  vpc_id     = alicloud_vpc.vpc.id
  cidr_block = "172.16.0.0/21"
  zone_id    = data.alicloud_zones.default.zones.0.id
}

resource "alicloud_security_group" "default" {
  name   = var.instance_name
  vpc_id = alicloud_vpc.vpc.id
}

resource "alicloud_instance" "instance" {
  availability_zone          = data.alicloud_zones.default.zones.0.id
  security_groups            = alicloud_security_group.default.*.id
  password                   = var.instance_password
  instance_type              = var.instance_type
  system_disk_category       = "cloud_efficiency"
  image_id                   = var.image_id
  instance_name              = var.instance_name
  vswitch_id                 = alicloud_vswitch.vsw.id
  internet_max_bandwidth_out = var.internet_bandwidth
}

resource "alicloud_security_group_rule" "allow_tcp_22" {
  type              = "ingress"
  ip_protocol       = "tcp"
  nic_type          = "intranet"
  policy            = "accept"
  port_range        = "22/22"
  priority          = 1
  security_group_id = alicloud_security_group.default.id
  cidr_ip           = "0.0.0.0/0"
}

resource "alicloud_security_group_rule" "allow_tcp_80" {
  type              = "ingress"
  ip_protocol       = "tcp"
  nic_type          = "intranet"
  policy            = "accept"
  port_range        = "80/80"
  priority          = 1
  security_group_id = alicloud_security_group.default.id
  cidr_ip           = "0.0.0.0/0"
}

resource "alicloud_security_group_rule" "allow_tcp_10050" {
  type              = "ingress"
  ip_protocol       = "tcp"
  nic_type          = "intranet"
  policy            = "accept"
  port_range        = "10050/10050"
  priority          = 1
  security_group_id = alicloud_security_group.default.id
  cidr_ip           = "0.0.0.0/0"
}

# deploy zabbix server
resource "null_resource" "deploy" {
  triggers = {
    always_run = "${timestamp()}"
    script_hash = sha256("${local.mirrorlist_Adjust}")
  }
  provisioner "remote-exec" {
    connection {
      type     = "ssh"
      user     = "root"
      password = var.instance_password
      host     = alicloud_instance.instance.public_ip
    }
    inline = [
      # 1、关闭防火墙，修改主机名
      "systemctl disable --now firewalld",
      "setenforce 0",
      "hostnamectl set-hostname zbx-server",
      
      # 2、获取 zabbix 的下载源
      "rpm -ivh https://mirrors.aliyun.com/zabbix/zabbix/5.0/rhel/7/x86_64/zabbix-release-5.0-1.el7.noarch.rpm",
      
      # 3、更换 zabbix.repo 为阿里源，安装zabbix-server-mysql和zabbix-agent
      #"cd /etc/yum.repos.d",
      "sed -i.bak 's#http://repo.zabbix.com#https://mirrors.aliyun.com/zabbix#' /etc/yum.repos.d/zabbix.repo",
      #Centos7使用yum命令失效，报错：Could not retrieve mirrorlist, 通过如下命令添加参数到CentOS-Base.repo中解决。
      "echo \"${local.mirrorlist_Adjust}\" >> /etc/yum.repos.d/CentOS-Base.repo",

      "yum clean all && yum makecache",
      "yum install -y zabbix-server-mysql zabbix-agent",
      # 安装SCL（Software Collections），便于后续安装高版本的 php，默认 yum 安装的 php 版本为 5.4,版本过低，zabbix 5.0 版本对 php 版本最低要 7.2.0 版本。SCL 可以使得在同一台机器上使用多个版本的软件，而又不会影响整个系统的依赖环境。软件包会安装在 /etc/opt/rh 目录下。
      "yum install -y centos-release-scl",
      "yum list centos-release-scl  # 应显示可用包",
      "rpm -q centos-release-scl    # 验证安装结果",
      
      # 4、修改 zabbix-front 前端源，安装 zabbix 前端环境到 scl 环境下
      "sed -i.bak 's#enabled=0#enabled=1#' /etc/yum.repos.d/zabbix.repo",
      # 安装zabbix前端环境到 scl 环境下
      "yum install -y zabbix-web-mysql-scl zabbix-apache-conf-scl",

      # 5、安装 zabbix 所需的数据库
      "yum install -y mariadb-server mariadb",
      #将数据库设置为开机自启，并立即启动
      "systemctl enable --now mariadb", 
      # 6、初始化数据库,添加数据库用户，以及 zabbix 所需的数据库信息
      "echo \"${local.mysqlConfig}\" > /tmp/mysqlConfig.sh",
      "source /tmp/mysqlConfig.sh",

      #查询已安装的zabbix-server-mysql的文件列表，找到 sql.gz 文件的位置
      "rpm -ql zabbix-server-mysql",
      #导入数据库信息，使用zcat将sql.gz文件导入数据库
      "zcat /usr/share/doc/zabbix-server-mysql-*/create.sql.gz | mysql -uroot -pabc123 zabbix",

      # 7、修改 zabbix-server 配置文件，修改数据库的密码
      #124行，取消注释，指定 zabbix 数据库的密码，DBPassword的值是数据库授权zabbix用户的密码。
      "sed -i.bak 's/# DBPassword=/DBPassword=${var.mysql_zabbix_password}/' /etc/zabbix/zabbix_server.conf",

      # 8、修改 zabbix 的 php 配置文件，修改时区
      "sed -i.bak 's/; php_value\\[date.timezone\\] = Europe\\/Riga/php_value\\[date.timezone\\] = Asia\\/Shanghai/'  /etc/opt/rh/rh-php72/php-fpm.d/zabbix.conf",

      # 9、启动 zabbix 相关服务
      "systemctl restart zabbix-server zabbix-agent httpd rh-php72-php-fpm",
      "systemctl enable zabbix-server zabbix-agent httpd rh-php72-php-fpm",
      "netstat -natp | grep zabbix",

      # 10、服务端和客户端都配置时间同步，使用阿里云的时钟源
      "yum install -y ntpdate",
      "ntpdate -u ntp.aliyun.com",

      # 11、服务端安装zabbix-get，方便日后排查
      "yum install -y zabbix-get",


      "sleep 2"
    ]
  }
}


#Centos7使用yum命令失效，报错：Could not retrieve mirrorlist http://mirrorlist.centos.org/?release=7&arch=x86_64&repo=os&infra=stock error was 14: curl#6 - "Could not resolve host: mirrorlist.centos.org; 未知的错误", 通过如下命令添加参数到CentOS-Base.repo中解决。
locals {
  mirrorlist_Adjust = <<EOF

[centos-sclo-rh]
name=CentOS-7 - SCLo rh
baseurl=http://vault.centos.org/centos/7/sclo/\$basearch/rh/
gpgcheck=1
enabled=1
gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-CentOS-SIG-SCLo

[centos-sclo-sclo]
name=CentOS-7 - SCLo sclo
baseurl=http://vault.centos.org/centos/7/sclo/\$basearch/sclo/
gpgcheck=1
enabled=1
gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-CentOS-SIG-SCLo

EOF
}

#初始化数据库,添加数据库用户，以及 zabbix 所需的数据库信息
locals {
  mysqlConfig = <<EOF
#!/bin/bash
#6、添加数据库用户，以及 zabbix 所需的数据库信息

mysqladmin -u root password 'abc123'
#设置utf8字符集
mysql -u root -pabc123 -e \"CREATE DATABASE zabbix character set utf8 collate utf8_bin\"
#创建并授权用户，使得zabbix可以访问数据库
mysql -u root -pabc123 -e \"GRANT all ON zabbix.* TO 'zabbix'@'%' IDENTIFIED BY 'zabbix'\"
mysql -u root -pabc123 -e \"GRANT ALL ON zabbix.* TO 'zabbix'@'localhost' IDENTIFIED BY 'zabbix'\"

#刷新权限
mysql -u root -pabc123 -e \"flush privileges\"
mysql -u root -pabc123 -e \"show databases\"

EOF
}





##############################
#新增Zabbix被监控的Agent相关配置
##############################

variable "agent_instance_name" {
  default = "zabbix-agent-1"
}

resource "alicloud_instance" "agent_instance" {
  availability_zone          = data.alicloud_zones.default.zones.0.id
  security_groups            = alicloud_security_group.default.*.id
  password                   = var.instance_password
  instance_type              = var.instance_type
  system_disk_category       = "cloud_efficiency"
  image_id                   = var.image_id
  instance_name              = var.agent_instance_name
  vswitch_id                 = alicloud_vswitch.vsw.id
  internet_max_bandwidth_out = var.internet_bandwidth
}


# deploy zabbix agent
resource "null_resource" "agent_deploy" {
  triggers = {
    always_run = "${timestamp()}"
    #script_hash = sha256("${local.mirrorlist_Adjust}")
  }
  provisioner "remote-exec" {
    connection {
      type     = "ssh"
      user     = "root"
      password = var.instance_password
      host     = alicloud_instance.agent_instance.public_ip
    }
    inline = [
      # 1、关闭防火墙，修改主机名
      "systemctl disable --now firewalld",
      "setenforce 0",
      "hostnamectl set-hostname zbx-agent01",

      # 2、服务端和客户端都配置时间同步，使用阿里云的时钟源
      "yum install -y ntpdate",
      "ntpdate -u ntp.aliyun.com",
      
      #3、客户端配置时区，与服务器保持一致
      "mv /etc/localtime{,.bak}",
      "ln -s /usr/share/zoneinfo/Asia/Shanghai /etc/localtime",
      "date",

      # 4、设置 zabbix 的下载源，安装 zabbix-agent2
      "rpm -ivh https://mirrors.aliyun.com/zabbix/zabbix/5.0/rhel/7/x86_64/zabbix-release-5.0-1.el7.noarch.rpm",
      "cd /etc/yum.repos.d",
      "sed -i 's#http://repo.zabbix.com#https://mirrors.aliyun.com/zabbix#'  /etc/yum.repos.d/zabbix.repo",
      "yum install -y zabbix-agent2",

      # 5、修改 agent2 配置文件
      "sed -i.bak 's/Server=127.0.0.1/Server=${alicloud_instance.instance.private_ip}/' /etc/zabbix/zabbix_agent2.conf",
      "sed -i.bak 's/ServerActive=127.0.0.1/Server=${alicloud_instance.instance.private_ip}/' /etc/zabbix/zabbix_agent2.conf",
      "sed -i.bak 's/Hostname=Zabbix server/Hostname=zbx-agent01/' /etc/zabbix/zabbix_agent2.conf",

      #6、启动 zabbix-agent2
      "systemctl start zabbix-agent2",
      "systemctl enable zabbix-agent2",
      "netstat -natp | grep zabbix",

      "sleep 2"
    ]
  }
}

output "zabbix_Server_URL" {
  value = format("http://%v/zabbix", alicloud_instance.instance.public_ip)
}

output "zabbix_Server_Private_IP" {
  value = format("%v", alicloud_instance.instance.private_ip)
}

output "zabbix_Agent_Private_IP" {
  value = format("%v", alicloud_instance.agent_instance.private_ip)
}