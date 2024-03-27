# Terraform module for ActionTrail event alert

This module can be used to create alerts from ActionTrail events.

## Usage

```terraform
provider "alicloud" {
  region     = "cn-shanghai"
}

provider "alicloud" {
  alias      = "log_resource_record"
  region     = "cn-heyuan"
}

module "actiontrail_events" {
  # Please download the module directly and specify the source locally.
  source                       = "xxxx"
  providers = {
    alicloud                     = alicloud
    alicloud.log_resource_record = alicloud.log_resource_record
  }
  project_name                 = "sls_project_name"
  log_store                    = "sls_log_store"
  lang                         = "en-US"
  users                        = [
    {
      id    = "user.lsvlwl"
      name  = "user.name1"
      email = "email1@test.com"
    }
  ]
  user_group_id                = "group_lsmzvo"
  user_group_name              = "group_name"
  action_policy_id             = "policy_qlji9g"
  action_policy_name           = "policy.name"
  notification_period          = "any"
  enabled_alerts               = [
    "cis.at.abnormal_login",
    "cis.at.root_login",
    "cis.at.ram_mfa_login",
    "cis.at.unauth_login",
    "cis.at.off_duty_login",
    "cis.at.abnormal_ak_usage",
    "cis.at.ak_conf_change",
    "cis.at.root_ak_usage",
    "cis.at.ram_auth_change",
    "cis.at.ram_policy_change",
    "cis.at.pwd_login_attemp_policy",
    "cis.at.pwd_expire_policy",
    "cis.at.pwd_reuse_prevention_policy",
    "cis.at.pwd_length_policy",
    "cis.at.abnormal_pwd_mod_cnt",
    "cis.at.password_reset",
    "cis.at.password_change",
    "ip_insight",
    "ip_insight_v2",
    "cis.at.trail_off",
    "cis.at.ecs_force_reboot",
    "cis.at.ecs_reboot_alot",
    "cis.at.esc_release",
    "cis.at.ecs_disk_release",
    "cis.at.ecs_release_protec_off",
    "cis.at.ecs_disk_reinit",
    "cis.at.ecs_auto_snapshot_policy",
    "cis.at.ecs_disk_encry_detc",
    "cis.at.securitygroup_change",
    "db.at.rds_instance_del",
    "cis.at.rds_access_whitelist",
    "cis.at.rds_sql_audit",
    "cis.at.rds_ssl_config",
    "cis.at.rds_conf_change",
    "cis.at.oss_policy_change",
    "cis.at.sas_webshell_unbind",
    "cis.at.sas_webshell_detection",
    "cis.at.vpc_flowlog_off",
    "cis.at.vpc_route_change",
    "cis.at.vpc_conf_change",
    "dataflow.at.slb_http",
    "cis.at.api_err",
    "cis.at.unauth_apicall",
    "cis.at.cloudfirewall_conf_change",
    "cis.at.cfw_basic_rule_off",
    "cis.at.cfw_ai_off",
    "cis.at.cfw_ti_off",
    "cis.at.cfw_patch_off",
    "cis.at.cfw_log_off",
    "cis.at.cfw_obs_mode",
    "cis.at.cfw_loose_block",
    "cis.at.cfw_assets_protec_off",
    "cis.at.cfw_assets_auto_protec_off"
  ]
}
```

## Contributing

Report issues/questions/feature requests on in the [issues](https://github.com/aliyun/alibabacloud-landing-zone/issues)
section.

<!-- BEGINNING OF PRE-COMMIT-TERRAFORM DOCS HOOK -->

## Requirements

* You need to create trails for ActionTrail using SLS. For Landing Zone environment, we recommended using multi-account trail as described in [doc](https://www.alibabacloud.com/help/en/actiontrail/user-guide/create-a-multi-account-trail).
* You can use Terraform module [terraform-alicloud-landing-zone-log-archive](https://registry.terraform.io/modules/alibabacloud-automation/landing-zone-log-archive/alicloud/latest) to create a multi-account trail.
* For this module, please see Terraform and provider requirements from [versions.tf](versions.tf).

## Docs

### Providers

You need to pass two providers explicitly to module.

* `alicloud`: This provider used to create alert of log service. The region configured for this provider should be the same as the SLS project for ActionTrail trail.
* `alicloud.log_resource_record`：This provider used to create user and user group of log service. The region configured for this provider must be cn-heyuan.

### Variables

* `project_name`: the SLS project for ActionTrail trail.
* `log_store`: the log store used for store ActionTrail events in the SLS project specified above.
* `users`: the users whom the alert notifications are sent.
  * `id`: the ID of the user. The ID must be unique in an Alibaba Cloud account. The ID must be 5 to 60 characters in length, and can contain digits, letters, underscores (_), hyphens (-), and periods (.). It must start with a letter.
  * `name`: the name of the user. The name must be 1 to 20 characters in length, and cannot contain any of the following characters: \ $ | ~ ? & < > { } ` ' ".
  * `email`: the email address of the user.
* `user_group_id`: the ID of the user group that the user specified above will add to. The ID must be unique. The ID must be 5 to 60 characters in length and can contain digits, letters, underscores (_), hyphens (-), and periods (.). It must start with a letter.
* `user_group_name`: the name of the user group that the user specified above will add to. The group name must be 1 to 20 characters in length, and cannot contain the following characters: \ $ | ~ ? & < > { } ` ' ".
* `action_policy_id`: the ID of the action policy that to manage how alert notifications are sent.Make sure that the ID is unique within your Alibaba Cloud account. The ID must be 5 to 60 characters in length and can contain digits, letters, underscores (_), hyphens (-), and periods (.). It must start with a letter.
* `action_policy_name`: the name of the action policy that to manage how alert notifications are sent.The name must be 1 to 40 characters in length, and cannot contain the following characters: \ $ | ~ ? & < > { } ` ' ".
* `notification_period`: determine the periods during which alert notifications can be sent.
  * `any`: sends notifications by using a specified method at any time.
  * `workday`: sends notifications by using a specified method on business days.
  * `non_workday`: sends notifications by using a specified method on non-business days.
  * `worktime`: sends notifications by using a specified method during the business hours of business days.
  * `non_worktime`: sends notifications by using a specified method on non-business days or during the non-business hours of business days.
* `enabled_alerts`: alerts list you want to enable. Please refer to the alerts list below.

### Alerts List

| Alert | Alert Name | Description |
| --- | --- | --- |
| cis.at.abnormal_login | Account Continuous Login Failure Alert | Check every 15 minutes. The alert will be triggered if the number of failed logins is too many within 30 minutes. The trigger threshold can be configured in the rule parameters, and the default is 5 times. |
| cis.at.root_login | Alert for Continuous Login of Root Account | Root users should not login too frequently. Check every 15 minutes, the trigger condition is: root account has more than 5 times of login (configurable in rule parameters) within 30 minutes. |
| cis.at.ram_mfa_login | Alert of RAM User Login without MFA | Check every 15 minutes and scan logs in the past 30 minutes. When there exist logs of RAM user logins without MFA check, an alert will be triggered. |
| cis.at.unauth_login | Unauthorized IP Login Alert | Check every 15 minutes, and check the log of the past 30 minutes. IP login outside the scope of white list triggers alert |
| cis.at.off_duty_login | Alert of Login During Non-working Time | Check every 1 minutes, and the trigger condition is: during the past 1 minutes, there is a non-working time login behavior. Working time/non-working time range can be set in the Global Calendar component. |
| cis.at.abnormal_ak_usage | Alert of Frequency of AK Abnormal Usage | Check every 15 minutes. In the past 30 minutes, if the abnormal frequency of using AK exceeds the specified threshold, the alert will be triggered. The trigger threshold can be configured in rule parameters. |
| cis.at.ak_conf_change | KMS Key Configuration Change Alert | Check every 15 minutes, and the trigger condition is: in the past 30 minutes, there exists an operation of changing the KMS key configuration (such as deleting or disabling, etc.). |
| cis.at.root_ak_usage | Root Account AK Usage Detection | Check every 15 minutes, the trigger condition is that there is a usage record of Root account AK in the past 30 minutes.The Root account should not create and use the Access Key, otherwise an alert will be triggered. |
| cis.at.ram_auth_change | Alert of RAM Auth Change | Check every 15 minutes and scan logs in the past 30 minutes. When there exit logs of RAM auth change, an alert will be triggered. |
| cis.at.ram_policy_change | RAM policy Change Alert | Check every 15 minutes, check the log of the past 30 minutes. Alerts are triggered when the RAM policy changes. |
| cis.at.pwd_login_attemp_policy | Alert of Abnoraml Settings for RAM Password Login Retry Policy | According to Alibaba Cloud CIS rules, in RAM password login retry policy, the number of login attempts with wrong password within one hour cannot be more than 5 times (the threshold can be configured in the parameters of alert rule). This rule is checked every 15 minutes, and the trigger condition is: in the past 30 minutes, some operations have set the non-compliant RAM password login retry policy. |
| cis.at.pwd_expire_policy | Alert of Abnormal Setting of RAM Password Expire Policy | According to Alibaba Cloud CIS rules, in RAM password policy, the validity period of RAM password should be set to 90 days or less (configurable in the parameter of alert rule). This rule is checked every 15 minutes, and the trigger condition is: in the past 30 minutes, some actions have set too long password validity period in RAM password policy. |
| cis.at.pwd_reuse_prevention_policy | Alert of Setting of RAM Historical Passwords Check Policy | In the RAM history password check policy, it is forbidden to use the previous N passwords. The minimum value of n can be configured in the parameters of alert rules. If the value is less than this value, the alert will be triggered. This rule is checked every 15 minutes, and check the log of the past 30 minutes. |
| cis.at.pwd_length_policy | Alert of Abnormal Setting of RAM Password Length Policy | In the RAM password policy, the minimum length of RAM password cannot be less than 14 (which can be configured in the alert rule parameters), otherwise an alert will be triggered. This rule is checked every 15 minutes to check the log of the past 30 minutes. |
| cis.at.abnormal_pwd_mod_cnt | Alert of Abnormal Password Modification Frequency | Checking every 15 minutes. The trigger condition is that the number of password modification operations exceeds the specified threshold in the past half hour (the default threshold is 1), which can be configured in the rule parameters. |
| cis.at.password_reset | Alert of Password Reset Event | Check every 15 minutes, the trigger condition is that there is a password reset event in the past 30 minutes. |
| cis.at.password_change | Alert of Attempt to Modify Password Policy | Check every 15 minutes, the trigger condition is: in the past 30 minutes, there has been an operation to try to modify the password policy. |
| ip_insight | IpInsight Alert(Old Version) | Check at every 15 minutes, trigger condition is: there exists events of IpInsight in the past 30 minutes. Only valid for old version Insights. |
| ip_insight_v2 | IpInsight Alert(New Version) | Check at every 15 minutes, trigger condition is: there exists events of IpInsight in the past 30 minutes. Only valid for new version Insights. |
| cis.at.trail_off | Alert of Attempt to Turn off Trails | Check every 15 minutes, and the trigger condition is that there is an attempt to turn off trails in the past 30 minutes. |
| cis.at.ecs_force_reboot | Alert of ECS Instance Forced Reboot | After the ECS instance is forcibly rebooted, an alert is triggered. Check at every 15 minutes, the trigger condition is: in the past 30 minutes, there is an event of forced reboot of ECS instance. |
| cis.at.ecs_reboot_alot | Excessive Restart of ECS instance | Check every 15 minutes, the trigger condition is that the ECS instance has been restarted too many times in the past 30 minutes. The trigger threshold can be configured in rule parameters. |
| cis.at.esc_release | ECS Instance released Alert | Check every 15 minutes, the trigger condition is that there was an event that ECS instance was released in the past 30 minutes. |
| cis.at.ecs_disk_release | ECS Cloud Disk Released Alert | Check every 15 minutes, the trigger condition is: the ECS cloud disk was released in the past 30 minutes. |
| cis.at.ecs_release_protec_off | Alert of ECS Instance Release Protection Close | Check every 15 minutes, the trigger condition is: in the past 30 minutes, there is an operation to close the ECS instance release protection. |
| cis.at.ecs_disk_reinit | ECS Cloud Disk Reinit Alert | Check every 15 minutes, the trigger condition is that there is an ECS cloud disk reinitialization event in the past 30 minutes. |
| cis.at.ecs_auto_snapshot_policy | ECS Automatic Snapshot Policy Shutdown Alert | Check every 15 minutes, the trigger condition is that there was an operation to close the ECS automatic snapshot policy in the past 30 minutes. ECS disks are recommended to use the automatic snapshot policy for automatic backups. Turning off the automatic snapshot policy will trigger an alert. |
| cis.at.ecs_disk_encry_detc | Alert of ECS Cloud Disk Encryption Not Enabled | When creating ECS cloud disk, you should enable disk encryption, otherwise an alert will be triggered. Check every 15 minutes, the trigger condition is: in the past 30 minutes, an ECS cloud disk has been created without enabling encryption. |
| cis.at.securitygroup_change | Security Group Configurations Change alert | Check every 15 minutes, the trigger condition is that there is an event of security group configuration change in the past 30 minutes. |
| db.at.rds_instance_del | RDS Instance Released Alert | Check every 15 minutes, the trigger condition is: there exist RDS instance release events in the past 30 minutes. |
| cis.at.rds_access_whitelist | Alert of Abnormal Setting for RDS Instance Access Whitelist | The access whitelist of RDS instance should not be set to 0.0.0.0, otherwise an alert will be triggered. It is checked every 15 minutes, and the trigger condition is: in the past 30 minutes, there is an RDS instance whitelist setting operation related to the above abnormality. |
| cis.at.rds_sql_audit | Alert of Turning off RDS SQL Insight | The SQL insight of RDS instance should remain on, the turning off of which will trigger an alert. Check at every 15 minutes, the trigger condition is: in the past 30 minutes, there is an operation to turn off RDS SQL insight. |
| cis.at.rds_ssl_config | Alert of Turning off RDS Instance SSL | SSL of RDS instance should remain on, the turning off of which will trigger an alert. Check at every 15 minutes, and the trigger condition is: in the past 30 minutes, there has been an operation to turn off the SSL of RDS instance. |
| cis.at.rds_conf_change | RDS instance Configurations Change Alert | Check every 15 minutes, the trigger condition is that there are RDS instance configuration change events in the past 30 minutes. |
| cis.at.oss_policy_change | OSS Bucket Policy Change Alert | Check every 15 minutes, the trigger condition is: in the past 30 minutes, there is an operation to change the permission of OSS Bucket. |
| cis.at.sas_webshell_unbind | SAS Webpage Anti-tampering Protection Unbinding Alert | The webpage anti-tampering of the Cloud Security Center(SAS) will trigger an alert after unbinding the protection of the server. Check at every 15 minutes, and check the events in the past 30 minutes. |
| cis.at.sas_webshell_detection | SAS Webpage Anti-tampering Protection Status Disabled Alert | The protection status of Cloud Security Center(SAS) webpage anti-tampering on your servers should be kept enabled, and an alert will be triggered when it is disabled. Check at every 15 minutes, and check the events in the past 30 minutes. |
| cis.at.vpc_flowlog_off | Alert of Abnormal Change of VPC Flowlog Configuration | All VPCs should open the flow log, and closing or deleting the flow log will trigger an alert. Check at every 15 minutes, and check the events of the past 30 minutes. |
| cis.at.vpc_route_change | VPC Network Route Change Alert | Check every 15 minutes, the trigger condition is that there is a change event of VPC network route configuration in the past 30 minutes. |
| cis.at.vpc_conf_change | VPC Configuration Change Alert | Check every 15 minutes, the trigger condition is that there are VPC configuration change events in the past 30 minutes. |
| dataflow.at.slb_http | LoadBalancer(SLB) HTTP Access Protocol Enabled Alert | LoadBalancer(SLB) should disable access over the HTTP protocol and only allow access over the HTTPS protocol. Check every 15 minutes, the trigger condition is that there was an event to open the LoadBalancer HTTP access protocol in the past 30 minutes. |
| cis.at.api_err | Alert of Frequency of API Error | Check every 15 minutes, the trigger condition is that the number of API call errors in the past 30 minutes exceeds the specified threshold, which can be configured in the rule parameters. |
| cis.at.unauth_apicall | Alert for Unauthorized API calls | Check every 15 minutes, the trigger condition is that the number of unauthorized API calls within 30 minutes exceeds the specified threshold. The trigger threshold can be configured in rule parameters. |
| cis.at.cloudfirewall_conf_change | VPC Firewall Control Policy Change Alert | It is checked every 15 minutes, and the trigger condition is: in the past 30 minutes, there has been one or more changes in the control policy of VPC Firewall. |
| cis.at.cfw_basic_rule_off | Alert of Turning off of Cloudfirewall Basic Defense | After the basic defense rules of the cloudfirewall is turned off, an alert will be triggered. Check at every 15 minutes, and check the events of the past 30 minutes |
| cis.at.cfw_ai_off | Alert of Turning off of Cloudfirewall Intelligent Defense | After the intelligent defense of the cloudfirewall is turned off, an alert will be triggered. Check at every 15 minutes, and check the events of the past 30 minutes. |
| cis.at.cfw_ti_off | Alert of Turning off of Cloudfirewall Threat Intelligence | After the threat intelligence of the cloudfirewall is turned off, an alert will be triggered. Check at every 15 minutes, and check the events of the past 30 minutes. |
| cis.at.cfw_patch_off | Alert of Turning off of Cloudfirewall Virtual Patch | After the virtual patch of the cloudfirewall is turned off, an alert will be triggered. Check at every 15 minutes, and check the events of the past 30 minutes. |
| cis.at.cfw_log_off | Alert of Turning off of Cloudfirewall Log Analysis | After the log analysis of the cloudfirewall is turned off, an alert will be triggered. Check at every 15 minutes, and check the events of the past 30 minutes. |
| cis.at.cfw_obs_mode | Alert of Cloudfirewall Switched to Observation Mode | After the threat engine of the cloudfirewall is switched to the observation mode, an alert is triggered. Check every 15 minutes, and check the events of the past 30 minutes. |
| cis.at.cfw_loose_block | Alert of Cloudfirewall Switched to Loose Interception Mode | After the threat engine of the cloudfirewall is switched to loose interception mode, an alert is triggered. Check every 15 minutes, and check the events of the past 30 minutes. |
| cis.at.cfw_assets_protec_off | Alert of Turning off of Cloudfirewall Protection for Assets | An alert will be triggered when the cloudfirewall protection of specified asset is turned off. Check at every 15 minutes, and check the events of the past 30 minutes. |
| cis.at.cfw_assets_auto_protec_off | Alert of Disabled Auto Protection of New Assets in Cloudfirewall | After the automatic protection of new assets in cloudfirewall is turned off, an alert will be triggered. Check at every 15 minutes, and check the events of the past 30 minutes. |

## Authors

Created and maintained by Alibaba Cloud Landing Zone Team

## License

MIT License. See LICENSE for full details.

## Reference

* [Terraform-Provider-Alicloud Github](https://github.com/aliyun/terraform-provider-alicloud)
* [Terraform-Provider-Alicloud Release](https://releases.hashicorp.com/terraform-provider-alicloud/)
* [Terraform-Provider-Alicloud Docs](https://registry.terraform.io/providers/aliyun/alicloud/latest/docs)
