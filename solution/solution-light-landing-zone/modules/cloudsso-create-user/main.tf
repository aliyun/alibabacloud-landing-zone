data "alicloud_cloud_sso_directories" "this" {
  name_regex = var.cloudsso_directory_name
}

resource "alicloud_cloud_sso_user" "default" {
  count = length(var.cloudsso_user)
  directory_id = data.alicloud_cloud_sso_directories.this.ids[0]
  user_name    = var.cloudsso_user[count.index].user_name
  display_name = var.cloudsso_user[count.index].display_name
  first_name   = var.cloudsso_user[count.index].first_name
  last_name    = var.cloudsso_user[count.index].last_name
  email        = var.cloudsso_user[count.index].email
}

