variable "vpc_id" {
  description = "The vpc id used to launch several vswitches."
  type        = string
  default     = ""
}

variable "vpc_name" {
  description = "VPC name"
  type        = string
  default     = "TF-VPC"
}

variable "vpc_desc" {
  description = "VPC description"
  type        = string
  default     = "A new VPC created by Terrafrom module"
}

variable "vpc_cidr" {
  description = "The cidr block used to launch a new vpc."
  type        = string
  default     = "172.16.0.0/12"
}

variable "vpc_tags" {
  description = "The tags used to launch a new vpc. Before 1.5.0, it used to retrieve existing VPC."
  type        = map(string)
  default     = {}
}

variable "vswitch_configuration" {
  type = list(object({
    vswitch_name = string
    vswitch_desc = string
    vswitch_cidr = string
    zone_id      = string
    vswitch_tags = map(string)
  }))
}





