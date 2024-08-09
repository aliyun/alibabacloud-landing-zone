variable "ALIYUN__AccountId" {
  type = string
}

variable "role_name" {
  type        = string
  description = <<EOT
  {
    "ConstraintDescription": {
      "zh-cn": "不得超过 64 个字符、英文字母、数字或'-'。",
      "en": "No more than 64 characters,English letters, Numbers, or '-' are allowed."
    },
    "Description": {
      "zh-cn": "角色的名称，如果已经存在，请更改名称，<br>由英文字母、数字或'-'组成，不超过64个字符。",
      "en": "The name of role, Change the name if it already exists,<br>Consist of english letters, numbers or '-',not more than 64 characters."
    },
    "MinLength": 1,
    "Label": {
      "zh-cn": "角色的名称",
      "en": "Role Name"
    },
    "AllowedPattern": "^[a-zA-Z0-9\\-]+$",
    "MaxLength": 64,
    "Type": "String"
  }
  EOT
}

variable "policy_name" {
  type        = string
  description = <<EOT
  {
      "Type": "String",
      "Description": {
        "zh-cn": "策略名，改变名称如果它已经存在，<br>由英文字母，数字或'-'，5-128个字符组成。",
        "en": "The policy name, Change the name if it already exists,<br>Consist of english letters, numbers or '-', 5-128 characters."
      },
      "MinLength": 5,
      "Label": {
        "zh-cn": "策略名",
        "en": "Policy Name"
      },
      "AllowedPattern": "^[a-zA-Z0-9\\-]+$",
      "MaxLength": 128,
      "ConstraintDescription": {
        "zh-cn": "由英文字母、数字或'-',5-128个字符组成。",
        "en": "Consist of english letters, numbers or '-',5-128 characters."
      }
    }
  EOT
}

variable "policy_document" {
  type        = any
  description = <<EOT
  {
      "Type": "Json",
      "Description": {
        "zh-cn": "策略内容。其中 Action 和 Resource 必须配置为数组格式。",
        "en": "The policy document. Action and Resource must be configured in array format."
      },
      "Label": {
        "zh-cn": "策略内容",
        "en": "Policy Document"
      }
    }
  EOT
}

variable "assume_role_principal_account" {
  type        = string
  default     = ""
  description = <<EOT
  {
      "Default": "",
      "Type": "String",
      "Description": {
        "zh-cn": "该角色可信的账号。置空，则默认为当前账号。",
        "en": "The trusted account for this role. Default is current account while empty."
      },
      "Label": {
        "zh-cn": "角色可信的账号",
        "en": "Principal Account"
      }
    }
  EOT
}

variable "assume_role_principal_type" {
  type        = string
  default     = "RamRole"
  description = <<EOT
  {
      "Type": "String",
      "Required": true,
      "Label": {
        "zh-cn": "授信对象类型",
        "en": "Principal Type"
      },
      "AllowedValues": [
        "RamRole",
        "RamUser"
      ],
      "AssociationPropertyMetadata": {
        "ValueLabelMapping": {
          "RamRole": {
            "zh-cn": "RAM角色",
            "en": "RAM Role"
          },
          "RamUser": {
            "zh-cn": "RAM用户",
            "en": "RAM User"
          }
        },
        "AutoChangeType": false
      }
  }
  EOT
}

variable "assume_role_principal_name" {
  type        = string
  description = <<EOT
  {
      "Type": "String",
      "Description": {
        "zh-cn": "允许扮演该角色的可信账号下的RAM角色或者RAM用户名称。请确保填写RAM角色或者RAM用户在可信账号下已经存在，否则会创建失败。",
        "en": "Ram role or ram user of trusted account that are allowed to assume this role."
      },
      "Label": {
        "zh-cn": "可信账号下允许扮演的RAM角色或者RAM用户",
        "en": "Principal RAM Role or RAM User"
      }
    }
  EOT
}
