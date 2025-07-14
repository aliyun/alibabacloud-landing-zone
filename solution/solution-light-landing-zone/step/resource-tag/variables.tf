variable "tag_policy" {
   type = object({
      policy_name = string
      policy_desc = string
      user_type   = string
   })
}

variable "policy_content" {
   type = string
}

variable "target_type" {
   type = string
}

