locals {
  user_data              = <<EOF
#!/bin/sh
wget http://logtail-release-${data.alicloud_regions.this.ids.0}.oss-${data.alicloud_regions.this.ids.0}-internal.aliyuncs.com/linux64/logtail.sh -O logtail.sh; chmod 755 logtail.sh; ./logtail.sh install ${data.alicloud_regions.this.ids.0}
touch /etc/ilogtail/users/${var.log_account_id}
touch /etc/ilogtail/user_defined_id; echo "${var.userdefined[0]}" > /etc/ilogtail/user_defined_id
EOF
}



data "alicloud_regions" "this" {
  current = true
}




