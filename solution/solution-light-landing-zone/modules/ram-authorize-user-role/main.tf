locals {
  // system policy
  user_policy_list_system = flatten([
    for entry in var.ram_users :[
        for policy_name in entry.system_policy :{
          entry_name  = entry.name
          policy_name = policy_name
          policy_type = "System"
        }
      ]
  ])

}



resource "alicloud_ram_user_policy_attachment" "user_policy_attach" {
  for_each    = {for idx, policy in local.user_policy_list_system : idx => policy}
  policy_name = each.value.policy_name
  policy_type = each.value.policy_type
  user_name   = each.value.entry_name
}
