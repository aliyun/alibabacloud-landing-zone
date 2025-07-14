variable "directory_name" {
  description = "The name of a new cloud sso directory."
  type        = string
}
variable "mfa_authentication_status" {
  description = "The mfa authentication status. Valid values: Enabled or Disabled. Default to Enabled."
  type        = string
  default     = "Disabled"
}
variable "scim_synchronization_status" {
  description = "The scim synchronization status. Valid values: Enabled or Disabled. Default to Disabled."
  type        = string
  default     = "Disabled"
}


# access configuration
variable "permission_policies" {
  type = list(object({
    access_configuration_name = string
    permission_policy_list  = list(object({
      policy_type = string
      policy_document = string
      policy_name = string
    }))
  }))
  default = [
    {
      access_configuration_name = ""
      permission_policy_list = [{
        policy_type = "System"
        policy_name = "AliyunRAMFullAccess"
        policy_document = ""
      },{
        policy_type = "Inline"
        policy_name = ""
        policy_document = <<EOF
          {
          "Statement":[
            {
              "Action":"ecs:Get*",
              "Effect":"Allow",
              "Resource":[
                  "*"
              ]
            }
          ],
              "Version": "1"
          }
        EOF
      }]
    }]
  description = "The Policy List"
}

variable "relay_state" {
  type = string
  description = "The RelayState of the Access Configuration, Cloud SSO users use this access configuration to access the RD account, the initial access page address. "
  default = "https://home.console.aliyun.com/"
}
variable "session_duration" {
  type = string
  description = "The SessionDuration of the Access Configuration.Valid Value: 900 to 43200. Unit: Seconds. "
  default = "1800"
}
